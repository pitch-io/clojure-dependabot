#!/usr/bin/env bb
(ns clojure-dependabot
  (:require
    [babashka.fs :as fs]
    [babashka.process :as proc]
    [camel-snake-kebab.core :as csk]
    [cheshire.core :as json]
    [clj-yaml.core :as yaml]
    [clojure.string :as str]
    [clojure.spec.alpha :as s]
    [hiccup2.core :as h])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.text Normalizer Normalizer$Form]))

(s/check-asserts true)

(def homepage
  "https://github.com/pitch-io/clojure-dependabot")

(def version
  ;; TODO figure out a versioning/release scheme somehow
  "unversioned")

(def severities
  {"critical" 4
   "high" 3
   "medium" 2
   "low" 1})

(s/def ::severity (->> severities keys set))

(s/def :github/number int?)

(s/def :github/name string?)

(s/def :github/ghsa-id string?)

(s/def :github/html-url string?)

(s/def :github/identifier string?)

(s/def :github/first-patched-version
  (s/nilable (s/keys {:req-un [:github/identifier]})))

(s/def :github/ecosystem (s/and string? not-empty))

(s/def :github/package
  (s/keys {:req-un [:github/name
                    :github/ecosystem]}))

(s/def :github/manifest-path string?)

(s/def :github/dependency
  (s/keys {:req-un [:github/manifest-path
                    :github/package]}))

(s/def :github/vulnerability
  (s/keys {:req-un [:github/package]
           :opt-un [:github/first-patched-version]}))

(s/def :github/vulnerabilities
  (s/coll-of :github/vulnerability))

(s/def :github/security-advisory
  (s/keys {:req-un [:github/vulnerabilities
                    :github/ghsa-id
                    ::severity]}))

(s/def :github/dependabot-api-response
  (s/keys {:req-un [:github/number
                    :github/html-url
                    :github/security-advisory]}))


(s/def :maven/group-id string?)

(s/def :maven/artifact-id string?)

(s/def :maven/children
  (s/nilable (s/coll-of :maven/dependency)))

(s/def :maven/dependency
  (s/keys {:req-un [:maven/group-id
                    :maven/artifact-id]
           :opt-un [:maven/children]}))

(s/def :maven/text string?)

(s/def :maven/json
  (s/spec :maven/dependency))

(s/def :maven/tree
  (s/keys {:req-un [:maven/text :maven/json]}))

(s/def :antq/name string?)

(s/def :antq/file string?)

(s/def :antq/version string?)

(s/def :antq/latest-version
  (s/nilable string?))

(s/def :antq/outdated
  (s/keys {:req-un [:antq/name :antq/version :antq/latest-version :antq/file]}))

(s/def :antq/outdateds
  (s/coll-of :antq/outdated))

(def deps-edn (-> *file* fs/parent (fs/path "bb.edn") str))

(defn- bail [msg]
  (println msg)
  (flush)
  (System/exit 1))

(defmacro retry
  [n expr]
  (letfn [(go [n]
            (if (zero? n)
              expr
              `(try ~expr
                 (catch Exception e#
                   (log "Expression failed. Retrying." (quote ~expr))
                   (retry ~(dec n) ~expr)))))]
    (go n)))

(defn- log [& msg]
  ;; just a println for now, but has its own function so this can be changed later
  (apply println msg))

(defn- sh [& args]
  (let [data (apply proc/sh args)]
    (when-not (= (-> data :proc .exitValue) 0)
      (throw (ex-info "Process erred" (select-keys data [:out :err :cmd]))))
    data))

(defn- git
  "Runs git in `dir`, throwing if it fails."
  [dir & args]
  (apply sh {:dir dir} "git" args))

(defn- git-ok?
  "Runs git in `dir`, returning whether it succeeded."
  [dir & args]
  (-> (apply proc/sh {:dir dir} "git" args) :exit zero?))

(defn- gh
  "Runs gh in `dir`, throwing if it fails. gh infers the repository from the git remote of
  `dir`, so this must be the workspace, not the process's working directory."
  [dir & args]
  (apply sh {:dir dir} "gh" args))

(defn- scan-root
  "The absolute directory to scan: `directory` (relative to the workspace) within `github-workspace`."
  [github-workspace directory]
  (->> (cons github-workspace
             (if (and (some? directory) (not-empty directory))
               [directory]
               []))
       (apply fs/file)
       str))

(defn- strict-parse-bool [x]
  (let [bool (parse-boolean x)]
    (if (some? bool)
      bool
      (throw (ex-info "Not a boolean" {:value x})))))

(defn- slugify [string]
  (as-> string $
      (str/trim $)
      (Normalizer/normalize $ Normalizer$Form/NFD)
      (str/replace $ #"[\P{ASCII}]+" "")
      (str/lower-case $)
      (str/split $ #"[\p{Space}\p{P}]+")
      (filter not-empty $)
      (str/join "-" $)))

(defn- ref->branch
  "Converts a full git ref (e.g. GITHUB_REF=refs/heads/main) to the short branch
  name that `git switch` requires. Short names are returned unchanged."
  [ref]
  (str/replace-first ref #"^refs/heads/" ""))

(defn- gh-package->antq-name
  "GitHub names Maven packages `group:artifact`, while antq (and its `--focus`
  option) names them `group/artifact`."
  [package-name]
  (str/replace-first package-name ":" "/"))

(defn- severities-above [sev]
  (->> severities
       (filter #(>= (second %) (get severities sev 0)))
       (map first)
       seq))

(defn- parse-sev
  [sev]
  (if (contains? (-> severities keys set) sev)
    sev
    (bail (str "Illegal severity: " sev))))

(defn- comma-separated-string-list [x]
  (->> (str/split x #",")
       (filter not-empty)))

(defn- parse-ignore-dependencies [dep-str]
  (->> (str/split dep-str #",")
       (filter not-empty)
       (map (fn [dep]
              (let [split (str/split dep #":")
                    third (nth split 2 nil)
                    base {:group-id (first split) :artifact-id (second split)}]
                (if (and (some? third) (not-empty third))
                  (assoc base :version third)
                  base))))))

(def custom-parsers
  {:directory (fn [val]
                (->> val
                     ;; TODO remove mid 2027
                     ;; for backwards compatibility with previous versions
                     (drop-while #(= % \/))
                     (str/join "")))
   :ignore-dependencies parse-ignore-dependencies
   :labels comma-separated-string-list
   :reviewers comma-separated-string-list
   :severity parse-sev})

(defn- map-opt-spec [[opt-name & {:keys [description required default]}]]
  (let [bool-opt? (boolean? default)
        env-var-name (str "INPUT_" (csk/->SCREAMING_SNAKE_CASE_STRING opt-name))
        raw-value (System/getenv env-var-name)
        custom-parser (get custom-parsers opt-name)
        parser (cond
                 bool-opt? strict-parse-bool
                 (some? custom-parser) custom-parser
                 :default identity)
        parsed-value (try
                       (parser raw-value)
                       (catch Exception e
                         (bail (str "Could not parse " env-var-name "\nValue: " (pr-str raw-value) "\n  " e))))]
    [opt-name parsed-value]))

(defn opts-post-filter [[key value]]
  (-> {:directory (fn [x] (and (some? x) (not-empty x)))}
      (get key (constantly true))
      (#(% value))))

(defn- github-vars []
  (let [gh-vars ["GITHUB_PAT"
                 "GITHUB_REF"
                 "GITHUB_REPOSITORY"
                 "GITHUB_RUN_ID"
                 "GITHUB_SHA"
                 "GITHUB_STEP_SUMMARY"
                 "GITHUB_TOKEN"
                 "GITHUB_WORKSPACE"]]
    (->> gh-vars
         (map (fn [v] [(csk/->kebab-case-keyword v) (System/getenv v)]))
         (filter #(-> % second not-empty))
         (into {}))))

(defn parse-opts []
  (->>
    (-> (fs/parent *file*)
        (fs/path "action.yml")
        str
        slurp
        (yaml/parse-string :key-fn #(-> % :key csk/->kebab-case-keyword))
        :inputs
        seq)
     (map map-opt-spec)
     (filter opts-post-filter)
     (into (github-vars))))

(defn- configure-git [github-workspace]
  (when-not (System/getenv "LOCAL_DEV")
    (log "Configuring git")
    (sh "git" "config" "--global" "user.email" "41898282+github-actions[bot]@users.noreply.github.com")
    (sh "git" "config" "--global" "user.name" "github-actions[bot]")
    ;; Unsafe decision to fix https://github.com/actions/runner/issues/2033
    (sh "git" "config" "--global" "--add" "safe.directory" github-workspace)))

(defn- install-local-dependencies [{:keys [local-dependencies directory github-workspace]
                                    :or {local-dependencies ""}}]
  (let [dir (scan-root github-workspace directory)
        parsed-deps (->> (str/split local-dependencies #",")
                         (filter not-empty)
                         (map #(str/split % #":")))]
    (doseq [[file group-id artifact-id version packaging] parsed-deps]
      (do
        (log "Using 'mvn' to install file: " file)
        (sh {:dir dir}
            "mvn" "-ntp" "install:install-file"
            (str "-Dfile=" file)
            (str "-DgroupId=" group-id)
            (str "-DartifactId=" artifact-id)
            (str "-Dversion=" version)
            (str "-Dpackaging=" packaging))))))

(defn- git-ls-files [dir]
  (-> (sh {:dir dir} "git" "ls-files" "-z" "--full-name")
      :out
      (str/split #"\u0000")))

(defn- list-files
  [file-name & {:keys [github-workspace directory include-subdirectories]}]

  (let [scan-root (scan-root github-workspace directory)]
    (log "Scanning for" file-name "files in" scan-root)
    (->> (git-ls-files scan-root)
         (filter (fn [path] (or (= path file-name)
                                (str/ends-with? path (str "/" file-name)))))
         (filter
           (if-not include-subdirectories
             (fn [path] (= (->> path (fs/path github-workspace) fs/parent str) scan-root))
             (constantly true)))
         ;; TODO filter again for "include-subdirectories"
         (map (fn [path]
                {:full-path (str (fs/path github-workspace path))
                 :project-path path})))))

(defn gh-api-dependabot-alerts
  [{:keys [github-repository github-pat severity]}]

  (let [sev-str (if (or (nil? severity) (= "low" severity))
                    ""
                    (str "&severity=" (str/join "," (severities-above severity))))]
    (-> (sh {:extra-env {"GH_TOKEN" github-pat}}
            "gh" "api"
            "-H" "Accept: application/vnd.github+json"
            (str "/repos/" github-repository "/dependabot/alerts?ecosystem=maven&state=open&per_page=100" sev-str)
            "--paginate"
            "--slurp")
        :out)))

(defn- filter-gh-alert [alert ignore-dependencies]
  (let [[alert-group-id alert-artifact-id] (-> alert :dependency :package :name (str/split #":"))]
    (every? (fn [{:keys [group-id artifact-id]}]
              (not (and (= alert-group-id group-id) (= alert-artifact-id artifact-id)))) ignore-dependencies)))

(defn- fetch-dependabot-alerts
  [{:keys [ignore-dependencies] :as opts}]
  {:post [(s/assert (s/coll-of :github/dependabot-api-response) %)]}

  (log "Fetching Dependabot alerts")
  (as-> (gh-api-dependabot-alerts opts) $
        (json/parse-string $ csk/->kebab-case-keyword)
        (apply concat $)
        (filter #(filter-gh-alert % ignore-dependencies) $)
        (or (seq $) [])))

(defn mk-temp-file [prefix suffix]
  (let [file (Files/createTempFile prefix suffix (into-array FileAttribute []))]
    (-> file .toFile .deleteOnExit)
    (.toString file)))

(defn- mvn-tree-base [dir temp-file output-type]
  (sh {:dir dir}
      "mvn" "-B" "-ntp" "dependency:tree" (str "-DoutputType=" output-type) (str "-DoutputFile=" temp-file))
  (slurp temp-file))

(defn- mvn-tree-txt [dir temp-file]
  (mvn-tree-base dir temp-file "text"))

(defn- mvn-tree-json [dir temp-file]
  (mvn-tree-base dir temp-file "json"))

(defn- mvn-tree
  [dir]
  {:pre [(s/assert string? dir)]
   :post [(s/assert :maven/tree %)]}

  (log "Building Maven dependency tree for:" dir)
  (let [temp-file (mk-temp-file "clojure-dependabot-mvn-tree-" ".txt")]
    {:text (mvn-tree-txt dir temp-file)
     :json (-> (mvn-tree-json dir temp-file)
               (json/parse-string csk/->kebab-case-keyword))}))

(defn- maven-package-url
  "The package URL (purl) of a dependency from the `mvn dependency:tree` JSON, in the same form
  maven-dependency-submission used: `pkg:maven/<group>/<artifact>@<version>?classifier=<c>&type=<t>`."
  [{:keys [group-id artifact-id version classifier type]}]
  (let [qualifiers (->> [["classifier" classifier] ["type" type]]
                        (filter (comp not-empty second))
                        (map (fn [[k v]] (str k "=" v))))]
    (str "pkg:maven/" group-id "/" artifact-id "@" version
         (when (seq qualifiers)
           (str "?" (str/join "&" qualifiers))))))

(defn- merge-resolved-dependency
  "Merges two entries for the same package, which can be reached through several paths."
  [a b]
  {:package_url (:package_url a)
   :relationship (if (some #{"direct"} [(:relationship a) (:relationship b)]) "direct" "indirect")
   :scope (if (some #{"runtime"} [(:scope a) (:scope b)]) "runtime" "development")
   :dependencies (vec (distinct (concat (:dependencies a) (:dependencies b))))})

(defn- snapshot-resolved
  "The `resolved` dependencies of a snapshot manifest, from the root of the `mvn dependency:tree`
  JSON. The root is the project itself, so it is left out, and its children are the direct dependencies."
  [root]
  (letfn [(walk [deps relationship]
            (mapcat (fn [dep]
                      (let [purl (maven-package-url dep)]
                        (cons [purl {:package_url purl
                                     :relationship relationship
                                     ;; the API only has these two scopes
                                     :scope (if (= "test" (:scope dep)) "development" "runtime")
                                     :dependencies (mapv maven-package-url (:children dep))}]
                              (walk (:children dep) "indirect"))))
                    deps))]
    (reduce (fn [acc [purl entry]]
              (assoc acc purl (if-let [existing (get acc purl)]
                                (merge-resolved-dependency existing entry)
                                entry)))
            {}
            (walk (:children root) "direct"))))

(defn- dependency-snapshot
  "A dependency snapshot for the GitHub dependency submission API, with the project file (not the
  generated pom.xml) as the manifest, so Dependabot alerts name the project file."
  [{:keys [project-path]} dep-tree {:keys [github-ref github-sha github-run-id]}]
  {:version 0
   :sha github-sha
   :ref github-ref
   ;; the correlator is what maven-dependency-submission's --job-name was, so each project's
   ;; snapshot replaces the one previously submitted with it
   :job {:correlator project-path
         :id (or github-run-id (str (System/currentTimeMillis)))}
   :detector {:name "clojure-dependabot"
              :version version
              :url homepage}
   :scanned (str (java.time.Instant/now))
   :manifests {project-path {:name project-path
                             :file {:source_location project-path}
                             :resolved (snapshot-resolved dep-tree)}}})

(defn- submit-snapshot
  [snapshot {:keys [github-repository github-token]}]
  (sh {:extra-env {"GH_TOKEN" github-token}
       :in (json/generate-string snapshot)}
      "gh" "api"
      "--method" "POST"
      "-H" "Accept: application/vnd.github+json"
      (str "/repos/" github-repository "/dependency-graph/snapshots")
      "--input" "-"))

(defn- mvn-scan
  "Submits a dependency snapshot for each project, and returns `files` with each project's Maven
  dependency tree (see `mvn-tree`) as `:dep-tree`, so later steps don't need the pom.xml."
  [files & {:as opts}]
  ;; TODO parallel ?
  (mapv
    (fn [{:keys [full-path project-path] :as file}]
      (let [dir (-> full-path fs/parent str)
            pom-gen (if (= (fs/file-name project-path) "deps.edn")
                      #(sh {:dir %} "clojure" "-X:deps" "mvn-pom")
                      #(sh {:dir %} "lein" "pom"))]
        (log "Generating pom.xml for" project-path)
        (try
          (pom-gen dir)
          ;; the tree is built right after generating the pom.xml, as a directory with both a deps.edn
          ;; and a project.clj generates both into the same pom.xml
          (let [dep-tree (mvn-tree dir)
                snapshot (dependency-snapshot file (:json dep-tree) opts)]
            (log "Submitting to Dependabot API for" project-path)
            (retry 2 (submit-snapshot snapshot opts))
            (assoc file :dep-tree dep-tree))
          (finally
            ;; always remove the pom.xml so `antq` or other tools don't try to manage it
            (fs/delete-if-exists (fs/path dir "pom.xml"))))))
    files))

(defn- alert-for-project?
  "Whether a Dependabot `alert` belongs to the project `file`. The snapshots `mvn-scan` submits
  name the project file (e.g. `foo/deps.edn`) as the manifest, not the generated pom.xml, so
  an alert's manifest path is the project path."
  [file alert]
  (= (:project-path file) (-> alert :dependency :manifest-path)))

(defn- vuln-summary
  "The summary of `file`, which has the `:dep-tree` added by `mvn-scan`."
  [file alerts outdated]
  (log "Writing summary for" (:project-path file))
  (let [dep-tree (:dep-tree file)
        filtered-alerts (filter #(alert-for-project? file %) alerts)]
    (str
      (h/html [:h1 (:project-path file)]
              "\n\n"
              [:h2 "Security alerts"]
              "\n\n"
              [:table
               [:thead [:tr [:th "Number"]
                            [:th "Package"]
                            [:th "Severity"]
                            [:th "GHSA"]
                            [:th "CVE"]
                            [:th "Patched In"]]]
              "\n"
               [:tbody (for [alert filtered-alerts]
                         [:tr
                          [:td [:a {:href (:html-url alert)} (:number alert)]]
                          [:td (-> alert :dependency :package :name)]
                          [:td (-> alert :security-advisory :severity)]
                          (let [ghsa (-> alert :security-advisory :ghsa-id)]
                            (if (some? ghsa)
                              [:td [:a {:href (str "https://github.com/advisories/" ghsa)} ghsa]]
                              ""))
                          (let [cve (-> alert :security-advisory :cve-id)]
                            (if (some? cve)
                              [:td [:a {:href (str "https://nvd.nist.gov/vuln/detail/" cve)} cve]]
                              ""))
                          [:td (-> alert :security-vulnerability :first-patched-version :identifier)]])]]
              "\n\n"
              [:details
               [:code [:pre (:text dep-tree)]]]
              "\n\n")
      (if (nil? outdated)
        ""
        (h/html
          [:h2 "Outdated Dependencies"]
          "\n\n"
          [:table
           [:thead [:tr [:th "Name"]
                        [:th "Version"]
                        [:th "Latest Version"]]]
          "\n"
           [:tbody (for [od (get outdated (:project-path file) [])]
                     [:tr
                      [:td (:name od)]
                      [:td (:version od)]
                      [:td (:latest-version od)]])]])))))

(defn- vuln-summaries
  [alerts outdated scannable-files & {:keys [github-step-summary]}]

  ;; TODO parallel ?
  (doseq [file scannable-files]
    (do
      (spit github-step-summary (vuln-summary file alerts outdated) :append true))))

;; TODO figure out if there's a way to set the SLF4J logging level for antq to INFO and not DEBUG
(defn- outdated-cmd [dir]
  (proc/sh {:dir dir}
           "clojure" "-Sdeps" deps-edn "-M" "-m" "antq.core" "--reporter=json"))

(defn- strip-debug-lines [string]
  (->> (str/split string #"\n")
       (map str/trim)
       (filter #(not (str/includes? % "] DEBUG ")))
       (str/join "\n")))

(defn- list-outdated-for
  [file]
  {:post [(s/assert :antq/outdateds %)]}

  (log "Generating antq report for" (:project-path file))
  (let [dir (-> file :full-path fs/parent str)]
    (retry
      2
      (do
        (let [proc-output (outdated-cmd dir)
              parsed-json (-> proc-output
                              :out
                              ;; because for whatever reason, sometimes antq dumps to stdout the full SLF4J DEBUG logging ???
                              strip-debug-lines
                              ((fn [s]
                                 (try
                                   (json/parse-string s csk/->kebab-case-keyword)
                                 (catch Exception e
                                   (let [err-msg (-> e .getMessage strip-debug-lines)]
                                     (log "Failed to parse JSON:" err-msg)))))))]
          (when (nil? parsed-json)
            (throw (ex-info (str "Could not generate antq report for dir: " (:project-path file))
                            (merge {:notice "Output has been filtered of DEBUG lines"
                                    :out (-> proc-output :out strip-debug-lines)}
                                   (select-keys proc-output [:err :cmd])))))
          parsed-json)))))

(defn- keys-eq-not-nil [k a b]
  (let [val-a (k a)
        val-b (k b)]
    (if (and (nil? val-a) (nil? val-b))
      false
      (= val-a val-b))))

(defn- antq-dependency-matches [dependency rule]
  (and (keys-eq-not-nil :group-id dependency rule)
       (keys-eq-not-nil :artifact-id dependency rule)
       (let [rule-version (:version rule)]
         (if (some? rule-version)
           (= (:version dependency) rule-version)
           true))))

(defn- antq-dependency-should-keep [dependency ignore-dependencies]
  (if (or (nil? ignore-dependencies) (empty? ignore-dependencies))
    true
    (let [split (str/split (:name dependency) #"/")
          parsed {:group-id (first split)
                  :artifact-id (second split)
                  :version (:version dependency)}]
      (every? #(not (antq-dependency-matches parsed %)) ignore-dependencies))))

(defn- list-outdated [files & {:keys [ignore-dependencies]}]
  (->> files
       (map (fn [f] {:file (:project-path f)
                     :reports (->> (list-outdated-for f)
                                   (filter #(antq-dependency-should-keep % ignore-dependencies)))}))
       (reduce (fn [acc {:keys [file reports]}]
                 (assoc acc
                        file
                        (reduce (fn [acc report]
                                  (conj acc report))
                                (get acc file #{})
                                reports)))
               {})
       (filter #(and (some? %) (not-empty %)))
       (into {})))

(defn- antq-update [dir package version skip]
  {:pre [(s/assert fs/directory? dir)]}
  (log "Updating" (pr-str package) "in" (pr-str dir))
  (-> (sh {:dir dir}
          "clojure"
          "-Sdeps" deps-edn
          "-M"
          "-m" "antq.core"
          "--upgrade"
          "--force"
          "--directory" dir
          (str "--focus=" package (if (and (some? version) (not-empty version)) (str "@" version) ""))
          (str "--skip=" skip))
      :out))

(defn- list-prs [github-workspace]
  (-> (gh github-workspace "pr" "list" "--state" "open" "--limit" "1000" "--json" "number,headRefName")
      :out))

(defn- commit-message [file]
  (str "[bot] Updating dependencies in " file))

(defn- pr-title [file]
  (str "clojure-dependabot updates: " file))

(defn- pr-branch
  "The branch a project's PR is opened from.
  NOTE: avoid changing the branch name, as existing PRs would no longer be found."
  [project-path]
  (str "clojure-dependabot/" (slugify project-path)))

(def ^:private skipped-pr-comment-marker
  "<!-- clojure-dependabot:skipped-update-new-commits -->")

(defn- bot-commit?
  [github-workspace rev file]
  (let [bot-email (-> (git github-workspace "config" "user.email") :out str/trim)
        author-email (-> (git github-workspace "log" "-1" "--format=%ae" rev) :out str/trim)]
    (= author-email bot-email)))

(defn- warn
  "Logs a message as a GitHub Actions warning annotation."
  [& msg]
  (log (str "::warning::" (str/join " " msg))))

(defn- branch-plan
  "Decides what to do with a PR branch before pushing an update to it"
  [{:keys [remote-branch? bot-tip?]}]
  (cond
    (not remote-branch?) :create
    bot-tip? :overwrite
    :else :skip))

(defn- fetch-branch-plan
  "Fetches the PR `branch` (leaving it in FETCH_HEAD) and decides what to do with it. See `branch-plan`."
  [github-workspace branch file]
  (let [remote-branch? (git-ok? github-workspace "fetch" "origin" branch)]
    (branch-plan {:remote-branch? remote-branch?
                  :bot-tip? (and remote-branch? (bot-commit? github-workspace "FETCH_HEAD" file))})))

(defn- gh-pr-create-args
  [{:keys [file body reviewers labels main-branch]}]
  (concat ["pr" "create"
           "--base" main-branch
           "--title" (pr-title file)
           "--body" body]
          (when (seq reviewers)
            ["--reviewer" (str/join "," reviewers)])
          (when (seq labels)
            ["--label" (str/join "," labels)])))

(defn- open-pr
  [{:keys [file branch github-ref github-workspace] :as opts}]

  (log "Opening a PR for" file)
  (let [base-branch (ref->branch github-ref)
        plan (fetch-branch-plan github-workspace branch file)]
    (if (= :skip plan)
      (do
        (warn "Not opening a PR for" file "because branch" branch "already exists and has commits"
              "not made by clojure-dependabot. Delete the branch to have a PR opened on the next run.")
        (git github-workspace "restore" "--" file))
      (do
        (git github-workspace "switch" base-branch)
        (git github-workspace "checkout" "-B" branch)
        (try
          ;; TODO a nicer commit message with full details
          (git github-workspace "commit" file "-m" (commit-message file))
          (git github-workspace "push" "--force" "origin" branch)
          (apply gh github-workspace (gh-pr-create-args opts))
          (finally
            (git github-workspace "switch" base-branch))))))
  nil)

(defn- pr-comment-bodies [github-workspace github-repository number]
  (->> (gh github-workspace "api" (str "repos/" github-repository "/issues/" number "/comments") "--paginate" "--slurp")
       :out
       json/parse-string
       (apply concat)
       (map #(get % "body" ""))))

(defn- notify-pr-update-skipped
  [number {:keys [branch github-repository github-workspace]}]
  (if (some #(str/includes? % skipped-pr-comment-marker) (pr-comment-bodies github-workspace github-repository number))
    (log "PR" number "has already been told that updates are skipped")
    (gh github-workspace "pr" "comment" number
        "--body" (str skipped-pr-comment-marker "\n"
                      "clojure-dependabot has stopped updating this PR because `" branch "` has commits "
                      "that weren't made by clojure-dependabot, and updating the branch would overwrite them.\n\n"
                      "To have clojure-dependabot update this PR again, remove those commits from the branch. "
                      "Alternatively, close this PR and delete its branch, and a new PR will be opened on the next run."))))

(defn- update-pr
  [number {:keys [branch file body github-ref github-workspace] :as opts}]

  (log "Updating PR" number)

  (let [base-branch (ref->branch github-ref)
        plan (fetch-branch-plan github-workspace branch file)]
    (if (= :skip plan)
      (do
        (log "Skipping update of PR" number "because branch" branch "has commits not made by clojure-dependabot")
        (git github-workspace "restore" "--" file)
        (notify-pr-update-skipped number opts))
      (do
        (git github-workspace "switch" base-branch)
        (git github-workspace "checkout" "-B" branch)
        (try
          ;; TODO a nicer commit message with full details
          (git github-workspace "commit" file "-m" (commit-message file))
          (if (and (= :overwrite plan) (git-ok? github-workspace "diff" "--quiet" "FETCH_HEAD" "HEAD"))
            (log "PR" number "branch is already up to date")
            (git github-workspace "push" "--force" "origin" branch))
          (gh github-workspace "pr" "edit" number
              "--title" (pr-title file)
              "--body" body)
          (finally
            (git github-workspace "switch" base-branch))))))
  nil)

(defn- close-pr
  [number {:keys [github-workspace]}]
  {:pre [(s/assert number? number)]}

  (log "Closing PR" number)
  (gh github-workspace "pr" "close"
      "--delete-branch"
      "--comment" "The most recent scan indicates that this PR is no longer needed."
      number)
  nil)

(defn- file-changed-in-git?
  [file]
  {:pre [(s/assert string? file)]}
  (-> (sh {:dir (-> file fs/parent str)} "git" "status" "--porcelain" "--" file)
      :out
      str/trim
      not-empty
      boolean))

(defn- antq-skip
  "The project type antq should skip, so it only updates the given project file."
  [full-path]
  (if (= (fs/file-name full-path) "deps.edn")
    "leiningen"
    "clojure-cli"))

(defn- antq-updates
  "The updates to run with antq for `file`, as [package version] pairs (a nil version means the
  latest). Security updates come first, once per vulnerable package, followed by the outdated
  dependencies unless `security-updates-only`."
  [file alerts outdated security-updates-only]
  ;; TODO merge the security and outdated updates
  ;; TODO see if we can opportunistically pull the security update's version from the outdated list
  (concat
    (->> alerts
         (filter #(alert-for-project? file %))
         (map #(-> % :dependency :package :name gh-package->antq-name))
         distinct
         (map (fn [package] [package nil])))
    (when-not security-updates-only
      (->> (get outdated (:project-path file) [])
           (map (juxt :name :latest-version))))))

(defn- pr-action
  [{:keys [open-pr? changed?]}]
  (case [(boolean open-pr?) (boolean changed?)]
    [false true] :open
    [true true] :update
    [true false] :close
    [false false] :none))

(defn- manage-prs
  [alerts outdated files & {:keys [security-updates-only github-workspace]
                            :as opts}]

  (log "Managing PRs")
  (let [open-prs (->> (-> (list-prs github-workspace) (json/parse-string csk/->kebab-case-keyword))
                      (map (juxt :head-ref-name :number))
                      (into {}))]
    ;; NOTE: this *must* be sequential, not parallel because of using the `git` CLI
    (doseq [file files]
      ;; TODO check that target file is clean
      (let [skip (antq-skip (:full-path file))
            project-dir (-> file :full-path fs/parent str)]

        (doseq [[package version] (antq-updates file alerts outdated security-updates-only)]
          ;; TODO do all the updates at once with multiple --focus calls
          (antq-update project-dir package version skip))

        (let [branch (pr-branch (:project-path file))
              existing-pr-number (get open-prs branch)
              pr-opts #(merge {:file (:project-path file)
                               :branch branch
                               :body (vuln-summary file alerts outdated)}
                              opts)]
          (case (pr-action {:open-pr? (some? existing-pr-number)
                            :changed? (-> file :full-path file-changed-in-git?)})
            :open (open-pr (pr-opts))
            :update (update-pr existing-pr-number (pr-opts))
            :close (close-pr existing-pr-number opts)
            :none (log "No updates for" (:project-path file) "- no PR needed")))))))

(defn- git-cleanup [{:keys [github-ref github-workspace directory]}]
  (log "Cleaning up")
  (git github-workspace
       "restore" "--" (scan-root github-workspace directory))
  ;; TODO delete all the generated pom.xmls ?
  (git github-workspace "checkout" (ref->branch github-ref)))

(defn main [& args]
  (when (or (some? args) (not-empty args))
    (throw (ex-info "CLI arguments are not permitted" {:args args})))
  (let [opts (parse-opts)]
    (configure-git (:github-workspace opts))

    (let [deps-edn-files (list-files "deps.edn" opts)
          project-clj-files (list-files "project.clj" opts)
          project-files (concat deps-edn-files project-clj-files)]
        (install-local-dependencies opts)
        (let [all-files (mvn-scan project-files opts)
              outdated (if-not (:security-updates-only opts)
                               (list-outdated all-files opts)
                               nil)
              ;; NOTE: fetching alerts *after* so that there's time to allow new reports to appear in the API
              alerts (fetch-dependabot-alerts opts)]
          (vuln-summaries alerts outdated all-files opts)

          (when (:auto-pull-request opts)
            (manage-prs alerts outdated all-files opts))))
    (git-cleanup opts))
  (log "Job complete"))

(when (= *file* (System/getProperty "babashka.file"))
  (apply main *command-line-args*))
