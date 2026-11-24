(ns e2e-test
  "Runs clojure-dependabot for real against the intentionally vulnerable repository at
  `../clojure-dependabot-test`, and checks the step summary it writes."
  (:require
    [babashka.fs :as fs]
    [babashka.process :as proc]
    [clj-yaml.core :as yaml]
    [clojure.data.xml :as xml]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]))

(def ^:private project-root
  (-> *file* fs/absolutize fs/parent fs/parent))

(def ^:private test-repo
  (fs/normalize (fs/path project-root ".." "clojure-dependabot-test")))

(def ^:private asdf-shims
  (fs/path (or (System/getenv "ASDF_DATA_DIR") (fs/path (fs/home) ".asdf")) "shims"))

(defn- asdf-env
  "The environment that puts the asdf shims first on the PATH, and pins each tool to its version in
  this repository's .tool-versions (e.g. `ASDF_LEIN_VERSION`), unless that variable is already set.
  asdf otherwise picks versions from the .tool-versions of the directory a command runs in, but the
  tools run in the test repository."
  []
  (->> (str/split-lines (slurp (str (fs/path project-root ".tool-versions"))))
       (map str/trim)
       (remove #(or (str/blank? %) (str/starts-with? % "#")))
       (map (fn [line]
              (let [[tool version] (str/split line #"\s+")
                    version-var (str "ASDF_" (-> tool str/upper-case (str/replace "-" "_")) "_VERSION")]
                [version-var (or (System/getenv version-var) version)])))
       (into {"PATH" (str asdf-shims fs/path-separator (System/getenv "PATH"))})))

(defn- cmd!
  "Runs a command in `dir` and returns its trimmed stdout, throwing if it fails."
  [dir & args]
  (let [{:keys [exit out err]} (apply proc/sh {:dir (str dir)} args)]
    (when-not (zero? exit)
      (throw (ex-info (str "Command failed: " (str/join " " args)) {:exit exit :out out :err err})))
    (str/trim out)))

(defn- github-env
  "The environment variables GitHub Actions would provide, derived from the repository at `repo`."
  [repo step-summary]
  (let [token (cmd! repo "gh" "auth" "token")]
    {"GITHUB_WORKSPACE" (cmd! repo "git" "rev-parse" "--show-toplevel")
     "GITHUB_REPOSITORY" (cmd! repo "gh" "repo" "view" "--json" "nameWithOwner" "--jq" ".nameWithOwner")
     "GITHUB_REF" (cmd! repo "git" "symbolic-ref" "HEAD")
     "GITHUB_SHA" (cmd! repo "git" "rev-parse" "HEAD")
     "GITHUB_TOKEN" token
     "GITHUB_PAT" token
     "GITHUB_STEP_SUMMARY" step-summary}))

(defn- action-inputs
  "The `INPUT_*` environment variables for every input in action.yml, set to its default."
  []
  (->> (yaml/parse-string (slurp (str (fs/path project-root "action.yml"))) :keywords false)
       (#(get % "inputs"))
       (map (fn [[input-name {:strs [default]}]]
              [(str "INPUT_" (str/upper-case input-name)) (str default)]))
       (into {})))

(defn- project-files
  "The project files clojure-dependabot is expected to scan in `repo`."
  [repo]
  (->> (str/split-lines (cmd! repo "git" "ls-files"))
       (filter #(#{"deps.edn" "project.clj"} (fs/file-name %)))
       set))

(defn- relay
  "Copies `stream` line by line to `writer` on another thread. Through `*out*` or `*err*`, Kaocha
  captures the lines and prints them only when the test fails (or live with `--no-capture-output`)."
  [stream writer]
  (future
    (binding [*out* writer]
      (doseq [line (line-seq (io/reader stream))]
        (println line)))))

(defn- elements [node]
  (filter xml/element? (:content node)))

(defn- text [node]
  (if (string? node)
    node
    (apply str (map text (:content node)))))

(defn- summary-by-project
  "Parses the step summary HTML into a map of project path (each `<h1>`) to the elements after it."
  [html]
  (->> (xml/parse-str (str "<summary>" html "</summary>"))
       elements
       (reduce (fn [acc el]
                 (if (= :h1 (:tag el))
                   (conj acc [(text el) []])
                   (update-in acc [(dec (count acc)) 1] conj el)))
               [])
       (into {})))

(defn- security-alert-rows
  "The rows of the table following the \"Security alerts\" heading."
  [els]
  (let [table (->> els
                   (drop-while #(not (and (= :h2 (:tag %)) (= "Security alerts" (text %)))))
                   second)]
    (->> (elements table)
         (filter #(= :tbody (:tag %)))
         (mapcat elements)
         (filter #(= :tr (:tag %))))))

(deftest ^:e2e test-end-to-end
  (is (fs/directory? test-repo) (str "The test repository must be checked out at " test-repo))
  (is (fs/directory? asdf-shims) (str "asdf must be installed, with its shims at " asdf-shims))
  (let [step-summary (str (fs/create-temp-file {:prefix "clojure-dependabot-step-summary-" :suffix ".html"}))]
    (try
      (let [env (merge (action-inputs)
                       {;; don't push branches or open PRs in the test repository
                        "INPUT_AUTO_PULL_REQUEST" "false"
                        ;; don't change the global git config of whoever runs the tests
                        "LOCAL_DEV" "1"}
                       (github-env test-repo step-summary)
                       (asdf-env))
            ;; run from the workspace, like GitHub Actions does
            proc (proc/process {:dir (str test-repo)
                                :extra-env env
                                :out :stream
                                :err :stream}
                               (str (fs/path asdf-shims "bb")) "--config" (str (fs/path project-root "bb.edn"))
                               (str (fs/path project-root "clojure_dependabot.clj")))
            ;; each stream needs its own thread, or the process blocks once the other's pipe fills
            out (relay (:out proc) *out*)
            err (relay (:err proc) *err*)
            ;; wait for both relays, so no output is missing from a failure report
            _ [@out @err]
            {:keys [exit]} @proc]
        (is (zero? exit) "clojure-dependabot exits successfully")

        (let [slurped-summary (slurp step-summary)
              ;; this should never happen. catch weird errors with this check
              _ (is (not (= "" (slurp step-summary))))
              summary (summary-by-project slurped-summary)
              expected-projects (project-files test-repo)]
          (is (seq expected-projects) "the test repository has projects to scan")
          (is (= expected-projects (set (keys summary))) "every project has a summary")
          (doseq [project expected-projects]
            (testing project
              (is (seq (security-alert-rows (get summary project)))
                  "the project has at least one vulnerability")))))
      (finally
        (fs/delete-if-exists step-summary)))))
