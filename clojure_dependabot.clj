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
  (s/keys {:req-un [:github/identifier]}))

(s/def :github/package
  (s/keys {:req-un [:github/name]}))

(s/def :github/manifest-path string?)

(s/def :github/dependency
  (s/keys {:req-un [:github/manifest-path
                    :github/package]}))

(s/def :github/ecosystem (s/and string? not-empty))

(s/def :github/package
  (s/keys {:req-un [:github/ecosystem]
           :opt-un [:github/first-patched-version]}))

(s/def :github/vulnerability
  (s/keys {:req-un [:github/package]}))

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

(defn- boolean? [x]
  (instance? Boolean x))

(defn- strict-parse-bool [x]
  (let [bool (parse-boolean x)]
    (if (some? bool)
      bool
      (throw (ex-info "Not a boolean" :value x)))))

(defn- slugify [string]
  (as-> string $
      (str/trim $)
      (Normalizer/normalize $ Normalizer$Form/NFD)
      (str/replace $ #"[\P{ASCII}]+" "")
      (str/lower-case $)
      (str/split $ #"[\p{Space}\p{P}]+")
      (filter not-empty $)
      (str/join "-" $)))

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
    (sh "git" "config" "--global" "user.email" "github-actions[bot] <41898282+github-actions[bot]@users.noreply.github.com")
    (sh "git" "config" "--global" "user.name" "github-actions[bot]")
    ;; Unsafe decision to fix https://github.com/actions/runner/issues/2033
    (sh "git" "config" "--global" "--add" "safe.directory" github-workspace)))

(defn- install-local-dependencies [{:keys [local-dependencies directory]
                                    :or {local-dependencies ""}}]
  (let [parsed-deps (->> (str/split local-dependencies #",")
                         (filter not-empty)
                         (map #(str/split % #":")))]
    (doseq [[file group-id artifact-id version packaging] parsed-deps]
      (do
        (log "Using 'mvn' to install file: " file)
        (sh {:dir directory}
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

  (let [scan-root (->> (cons github-workspace
                             (if (and (some? directory) (not-empty directory))
                                 [directory]
                                 []))
                       (apply fs/file)
                       str)]
    (log "Scanning for" file-name "files in" scan-root)
    (->> (git-ls-files scan-root)
         ;; filter for the correct file name
         (filter (fn [path] (or (= path file-name) (str/ends-with? path (str "/" file-name)))))
         (filter
           (if-not include-subdirectories
             (fn [path] (= (->> path (fs/path github-workspace) fs/parent str) scan-root))
             (constantly true)))
         ;; TODO filter again for "include-subdirectories"
         (map (fn [path]
                {:full-path (str (fs/path github-workspace path))
                 :project-path path})))))

(defn- mvn-scan [files & {:keys [github-token github-repository github-ref github-sha]}]
  ;; TODO parallel ?
  (doseq [{:keys [full-path project-path]} files]
    (let [file-name (fs/file-name project-path)
          pom-gen (if (= file-name "deps.edn")
                    #(sh {:dir %} "clojure" "-X:deps" "mvn-pom")
                    #(sh {:dir %} "lein" "pom"))]
      (log "Generating pom.xml for" project-path)
      (pom-gen (-> full-path fs/parent str))
      (log "Submitting to Dependabot API for" project-path)
      (retry 2
        (sh "maven-dependency-submission-linux"
            "--token" github-token
            "--repository" github-repository
            "--branch-ref" github-ref
            "--sha" github-sha
            "--directory" (fs/parent full-path)
            ;; TODO this option appears to do nothing, but leaving it for now because it *seems* like what we want
            ;; https://github.com/advanced-security/maven-dependency-submission-action/issues/122
            "--snapshot-exclude-file-name"
            "--detector-name" "clojure-dependabot"
            "--detector-url" homepage
            "--detector-version" version
            "--job-name" project-path)))))

(defn gh-api-dependabot-alerts
  [{:keys [github-repository github-pat severity]}]

  (let [sev-str (if (or (nil? severity) (= "low" severity))
                    ""
                    (str "&severity=" (str/join "," (severities-above severity))))]
    (-> (sh {:extra-env {"GH_TOKEN" github-pat}}
            "gh" "api"
            "-H" "Accept: application/vnd.github+json"
            (str "/repos/" github-repository "/dependabot/alerts?ecosystem=maven&state=open" sev-str)
            "--paginate")
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
  (let [temp-file (mk-temp-file "clojure-dependabot-mvn-tree-" ".json")]
    {:text (mvn-tree-txt dir temp-file)
     :json (-> (mvn-tree-json dir temp-file)
               (json/parse-string csk/->kebab-case-keyword))}))

(defn flatten-mvn-tree* [deps level]
  (reduce (fn [acc dep]
            (concat acc
                    [(-> dep
                         (dissoc :children)
                         (assoc :level level))]
                    (if (:children dep)
                      (flatten-mvn-tree* (:children dep) (inc level))
                      [])))
          []
          deps))

(defn- flatten-mvn-tree [data]
  (flatten-mvn-tree* [data] 1))

(defn- vuln-summary [file alerts outdated]
  (log "Writing summary for" (:project-path file))
  (let [dep-tree (-> file :full-path fs/parent str mvn-tree)
        flat-deps (->> dep-tree
                       :json
                       flatten-mvn-tree
                       (map (fn [dep] str (:group-id dep) ":" (:artifact-id dep)))
                       set)
        filtered-alerts (filter
                          (fn [alert] (= (-> alert :depdendency :manifest-path)
                                         (:project-path file)))
                          alerts)]
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
                          [:td (-> alert :depdendency :package :name)]
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

(defn- list-prs []
  (-> (sh "gh" "pr" "list" "--state" "open" "--limit" "1000" "--json" "number,headRefName")
      :out))

(defn- open-pr
  [{:keys [file branch body reviewers labels main-branch github-ref]}]

  (log "Opening a PR for" file)
  (sh "git" "switch" github-ref) ;; ensure we're starting on branch that triggered this workflow
  (sh "git" "checkout" "-b" branch)
  ;; TODO a nicer commit message with full details
  (sh "git" "commit" file "-m" (str "[bot] Updating dependencies in" file))
  (sh "git" "push" "origin" branch)
  (apply sh (concat ["gh" "pr" "create"
                     "--base" main-branch]
                     "--title" (str "clojure-dependabot updates: " file)
                     "--body" body
                    (if (and (some? reviewers) (not-empty reviewers))
                      ["--reviewers" (str/join "," reviewers)]
                      [])
                    (if (and (some? labels) (not-empty labels))
                      ["--labels" (str/join "," labels)]
                      [])))
  (sh "git" "switch" github-ref)
  nil)

(defn- update-pr
  [number {:keys [branch file body github-ref]}]

  (log "Updating PR" number)

  (sh "git" "fetch")
  (let [checkout-success (try
                           (do
                             (sh "git" "checkout" branch)
                             true)
                           (catch Exception e
                             (do
                               (log "Could not checkout branch" branch "because of:" (.getMessage e))
                               false)))]
    (when checkout-success
      ;; TODO a nicer commit message with full details
      (sh "git" "commit" file "-m" (str "[bot] Updating dependencies in" file))
      (sh "git" "push" "origin" branch)
      (sh "gh" "pr" "edit"
          "--title" (str "clojure-dependabot updates: " file)
          "--body" body)
      (sh "git" "switch" github-ref)))
  nil)

(defn- close-pr
  [number]
  {:pre [(s/assert number? number)]}

  (log "Closing PR" number)
  (sh "gh" "pr" "close" "--comment" "The most recent scan indicates that this PR is no longer needed." number)
  nil)

(defn- file-changed-in-git?
  [file]
  {:pre [(s/assert string? file)]
   :post [(s/assert boolean? %)]}

  (-> (sh "git" "diff" "--porcelain" file)
      :out
      str/trim
      not-empty))

(defn- manage-prs
  [alerts outdated files & {:keys [severity security-updates-only]
                            :as opts}]

  (log "Managing PRs")
  (let [open-prs (->> (-> (list-prs) (json/parse-string csk/->kebab-case-keyword))
                      (map (juxt :head-ref-name :number))
                      (into {}))]
    ;; NOTE: this *must* be sequential, not parallel because of using the `git` CLI
    (doseq [file files]
      ;; TODO check that target file is clean
      (let [skip (if (= (fs/file-name (:full-path file)) "deps.edn")
                     "clojure-cli"
                     "leiningen")]

        ;; TODO merge all the update calls from both sec and outdated
        (doseq [alert (filter (fn [alert] (= (-> alert :dependency :manifest-path)
                                             (:project-path file)))
                              alerts)]
          (doseq [vuln (-> alert :security-advisory :vulnerabilities)]
            ;; TODO see if we can opportunistically pull the version from the outdated list
            ;; TODO do all the updates at once with multiple --focus calls
            (antq-update (:full-path file) (-> vuln :package :name) nil skip))

        (when-not security-updates-only
          (doseq [od (get outdated (:project-path file) [])]
            ;; TODO do all the updates at once with multiple --focus calls
            (antq-update (:full-path file) (:name od) (:latest-version od) skip)))

          ;; NOTE: avoid changing the branch name as it will break the logic
          (let [pr-branch (str "clojure-dependabot/" (slugify (:project-path file)))
                existing-pr-number (get open-prs pr-branch nil)
                changed? (-> file :full-path file-changed-in-git?)
                pr-body-fn #(vuln-summary file alerts outdated)]
            (case [(some? existing-pr-number) changed?]
              [false true] (open-pr (merge {:file (:project-path file)
                                            :branch pr-branch
                                            :body (pr-body-fn)}
                                           opts))
              [true false] (close-pr existing-pr-number)
              [true true] (update-pr existing-pr-number
                                     (merge {:file (:project-path file)
                                             :branch pr-branch
                                             :body (pr-body-fn)}
                                            opts))
              [false false] (log "WARNING: unreachable code for non-existent PR with no changes to" (:project-path file)))))))))

(defn- git-cleanup [{:keys [github-ref github-workspace directory]}]
  (log "Cleaning up")
  (sh "git" "restore" "--" (->> (cons github-workspace
                                      (if (and (some? directory) (not-empty directory))
                                          [directory]
                                          []))
                                (apply fs/file)
                                str))
  ;; TODO delete all the generated pom.xmls ?
  (sh "git" "checkout" github-ref))

(defn main [& args]
  (when (or (some? args) (not-empty args))
    (throw (ex-info "CLI arguments are not permitted" {:args args})))
  (let [opts (parse-opts)]
    (configure-git (:github-workspace opts))

    (let [deps-edn-files (list-files "deps.edn" opts)
          project-clj-files (list-files "project.clj" opts)
          all-files (concat deps-edn-files project-clj-files)]
        (install-local-dependencies opts)
        (mvn-scan all-files opts)
        (let [outdated (if-not (:security-updates-only opts)
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
