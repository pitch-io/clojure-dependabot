(ns smoke-test
  (:require
    [camel-snake-kebab.core :as csk]
    [cheshire.core :as json]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [clojure-dependabot :refer [main flatten-mvn-tree list-outdated severities-above keys-eq-not-nil antq-dependency-matches
                                antq-dependency-should-keep antq-dependency-matches parse-ignore-dependencies slugify]]))

(defn- bail [& args]
  (throw (ex-info "Called a banned function" {:args args})))

(defn- get-resource [file]
  (-> (str "resources/" file)
      io/resource
      slurp))

(defn- json-resource [file]
  (-> (get-resource file)
      (json/parse-string csk/->kebab-case-keyword)))

(def default-opts
  {:github-workspace "/dev/null"
   :github-step-summary "/dev/null"})

(defn- noop [& args])

(defn- mock-git-ls-files [dir]
  (seq ["deps.edn" "foo/project.clj"]))

(defn- mock-outdated-cmd [dir]
  {:out (get-resource "outdated.json")})

(defn mock-gh-api-dependabot-alerts [& opts]
  (get-resource "dependabot-alerts.json"))

(defn- mock-mvn-tree-txt [dir temp-file]
  (str "foo:foo:jar:0.1.0\n"
       "+- com.cognitect.aws:ssm:jar:871.2.34.6:compile\n"
       "|  +- commons-codec:commons-codec:jar:1.17.1:compile\n"
       "|  +- org.bouncycastle:bcpkix-jdk18on:jar:1.78.1:compile\n"
       "|  |  \\- org.bouncycastle:bcutil-jdk18on:jar:1.78.1:compile\n"))

(defn- mock-mvn-tree-json [dir temp-file]
  (get-resource "maven-dependencies.json"))

(deftest test-list-outdated
  (testing "listing outdated files produces a nice hashmap"
    (with-redefs [babashka.process/sh bail
                  clojure-dependabot/outdated-cmd mock-outdated-cmd]
      (let [file {:full-path "/dev/null/deps.edn"
                   :project-path "deps.edn"}
            parsed (list-outdated [file] {})]
        (is (some? parsed))
        (is (not-empty (->> parsed keys (filter some?))))
        (is (not-empty (->> parsed (map second) (filter some?) (filter not-empty))))
        (is (not-empty (get parsed (:project-path file))))))))

(deftest severity-filtering
  (is (= (severities-above "critical") (seq ["critical"])))
  (is (= (severities-above "high") (seq ["critical" "high"])))
  (is (= (severities-above nil) (seq ["critical" "high" "medium" "low"]))))

(deftest test-flatten-mvn-tree
  (let [input (json-resource "maven-dependencies.json")
        expected (set ["parent" "child-1" "child-2" "child-1-1" "child-1-2" "child-1-1-1"])]
    (testing "MVN dependency trees can be flattened"
      (let [result (flatten-mvn-tree input)]
        (is (= expected
               (set (map :artifact-id result))))))))

(deftest test-slugify
  (is (= (slugify "test") "test"))
  (is (= (slugify "one two") "one-two"))
  (is (= (slugify " WAT lol / okay??? ") "wat-lol-okay")))

(deftest test-keys-eq-not-nil
  (is (not (keys-eq-not-nil :foo {} {})))
  (is (not (keys-eq-not-nil :foo {:foo :bar} {})))
  (is (not (keys-eq-not-nil :foo {:foo :bar} {:foo :baz})))
  (is (not (keys-eq-not-nil :foo {:foo :bar} {:foo :baz})))
  (is (keys-eq-not-nil :foo {:foo :bar} {:foo :bar})))

(deftest test-antq-dependency-matches
  (testing "identical"
    (is (antq-dependency-matches
          {:group-id "foo" :artifact-id "bar"}
          {:group-id "foo" :artifact-id "bar"})))
  (testing "identical, with version"
    (is (antq-dependency-matches
          {:group-id "foo" :artifact-id "bar" :version "1.2.3"}
          {:group-id "foo" :artifact-id "bar" :version "1.2.3"})))
  (testing "wildcard version match"
    (is (antq-dependency-matches
          {:group-id "foo" :artifact-id "bar" :version "1.2.3"}
          {:group-id "foo" :artifact-id "bar"})))
  (testing "no match"
    (is (not (antq-dependency-matches
               {:group-id "foo" :artifact-id "bar"}
               {:group-id "baz" :artifact-id "quux"}))))
  (testing "wrong version"
    (is (not (antq-dependency-matches
               {:group-id "foo" :artifact-id "bar" :version "1.2.3"}
               {:group-id "foo" :artifact-id "bar" :version "4.5.6"})))))

(deftest test-antq-dependency-should-keep
  (testing "nothing in the ignore rules"
    (is (antq-dependency-should-keep
          {:name "foo/bar" :version "1.2.3"}
          nil))
    (is (antq-dependency-should-keep
          {:name "foo/bar" :version "1.2.3"}
          [])))
  (testing "non-matching ignore rule"
    (is (antq-dependency-should-keep
          {:name "foo/bar" :version "1.2.3"}
          [{:group-id "baz" :artifact-id "quux"}])))
  (testing "matching ignore rule, no version"
    (is (not (antq-dependency-should-keep
               {:name "foo/bar" :version "1.2.3"}
               [{:group-id "foo" :artifact-id "bar"}]))))
  (testing "matching ignore rule, with version"
    (is (not (antq-dependency-should-keep
               {:name "foo/bar" :version "1.2.3"}
               [{:group-id "foo" :artifact-id "bar" :version "1.2.3"}]))))
  (testing "matching ignore rule, wrong version"
    (is (antq-dependency-should-keep
           {:name "foo/bar" :version "1.2.3"}
           [{:group-id "foo" :artifact-id "bar" :version "4.5.6"}]))))

(deftest test-manage-prs-antq-update-dir
  (testing "antq-update is run in the project's directory, not on the project file"
    (let [workspace (str (babashka.fs/create-temp-dir))
          project-dir (str (babashka.fs/create-dirs (babashka.fs/path workspace "foo")))
          file {:full-path (str (babashka.fs/path project-dir "deps.edn"))
                :project-path "foo/deps.edn"}
          alerts [{:dependency {:manifest-path "foo/deps.edn" :package {:name "foo:bar"}}}]
          outdated {"foo/deps.edn" #{{:name "baz/quux" :latest-version "1.0.0"}}}
          dirs (atom [])]
      (try
        (with-redefs [babashka.process/sh bail
                      clojure-dependabot/list-prs (constantly "[]")
                      clojure-dependabot/antq-update (fn [dir & _] (swap! dirs conj dir))
                      clojure-dependabot/file-changed-in-git? (constantly false)]
          (#'clojure-dependabot/manage-prs alerts outdated [file] {:security-updates-only false}))
        (is (= [project-dir project-dir] @dirs))
        (finally
          (babashka.fs/delete-tree workspace))))))

(deftest test-gh-package->antq-name
  (let [gh-package->antq-name #'clojure-dependabot/gh-package->antq-name]
    (is (= "com.fasterxml.jackson.core/jackson-databind"
           (gh-package->antq-name "com.fasterxml.jackson.core:jackson-databind")))
    (is (= "foo/bar" (gh-package->antq-name "foo/bar")) "antq-style names are unchanged")))

(deftest test-manage-prs-antq-update-package-names
  (testing "packages are passed to antq-update in antq's group/artifact form"
    (let [workspace (str (babashka.fs/create-temp-dir))
          file {:full-path (str (babashka.fs/path workspace "deps.edn"))
                :project-path "deps.edn"}
          alerts [{:dependency {:manifest-path "deps.edn" :package {:name "foo:bar"}}}]
          outdated {"deps.edn" #{{:name "baz/quux" :latest-version "1.0.0"}}}
          packages (atom [])]
      (try
        (with-redefs [babashka.process/sh bail
                      clojure-dependabot/list-prs (constantly "[]")
                      clojure-dependabot/antq-update (fn [_dir package & _] (swap! packages conj package))
                      clojure-dependabot/file-changed-in-git? (constantly false)]
          (#'clojure-dependabot/manage-prs alerts outdated [file] {:security-updates-only false}))
        (is (= ["foo/bar" "baz/quux"] @packages))
        (finally
          (babashka.fs/delete-tree workspace))))))

(deftest test-manage-prs-security-updates-use-alert-package
  (testing "security updates target each alert's own package, once, not every package in the advisory"
    (let [workspace (str (babashka.fs/create-temp-dir))
          file {:full-path (str (babashka.fs/path workspace "deps.edn"))
                :project-path "deps.edn"}
          advisory {:vulnerabilities [{:package {:name "foo:bar"}}
                                      {:package {:name "not:in-this-project"}}]}
          alerts [{:dependency {:manifest-path "deps.edn" :package {:name "foo:bar"}}
                   :security-advisory advisory}
                  ;; a second alert for the same package, e.g. from a different advisory
                  {:dependency {:manifest-path "deps.edn" :package {:name "foo:bar"}}
                   :security-advisory advisory}
                  ;; an alert for a different project
                  {:dependency {:manifest-path "other/deps.edn" :package {:name "baz:quux"}}
                   :security-advisory advisory}]
          packages (atom [])]
      (try
        (with-redefs [babashka.process/sh bail
                      clojure-dependabot/list-prs (constantly "[]")
                      clojure-dependabot/antq-update (fn [_dir package & _] (swap! packages conj package))
                      clojure-dependabot/file-changed-in-git? (constantly false)]
          (#'clojure-dependabot/manage-prs alerts {} [file] {:security-updates-only true}))
        (is (= ["foo/bar"] @packages))
        (finally
          (babashka.fs/delete-tree workspace))))))

(deftest test-manage-prs-handles-each-project-once
  (testing "PRs are managed once per project, regardless of how many alerts it has (including none)"
    (let [workspace (str (babashka.fs/create-temp-dir))
          many-alerts {:full-path (str (babashka.fs/path workspace "a" "deps.edn"))
                       :project-path "a/deps.edn"}
          no-alerts {:full-path (str (babashka.fs/path workspace "b" "deps.edn"))
                     :project-path "b/deps.edn"}
          alerts [{:dependency {:manifest-path "a/deps.edn" :package {:name "foo:bar"}}}
                  {:dependency {:manifest-path "a/deps.edn" :package {:name "baz:quux"}}}]
          calls (atom [])]
      (try
        (with-redefs [babashka.process/sh bail
                      clojure-dependabot/list-prs (constantly "[{\"number\": 7, \"headRefName\": \"clojure-dependabot/b-deps-edn\"}]")
                      clojure-dependabot/antq-update (constantly nil)
                      clojure-dependabot/vuln-summary (constantly "body")
                      clojure-dependabot/file-changed-in-git? #(= % (:full-path many-alerts))
                      clojure-dependabot/open-pr (fn [{:keys [file]}] (swap! calls conj [:open file]))
                      clojure-dependabot/close-pr (fn [number] (swap! calls conj [:close number]))]
          (#'clojure-dependabot/manage-prs alerts {} [many-alerts no-alerts] {:security-updates-only true}))
        (is (= [[:open "a/deps.edn"] [:close 7]] @calls))
        (finally
          (babashka.fs/delete-tree workspace))))))

(defn- git! [dir & args]
  (let [{:keys [exit out err]} (apply babashka.process/sh {:dir (str dir)} "git" args)]
    (when-not (zero? exit)
      (throw (ex-info "git failed" {:args args :out out :err err})))
    (clojure.string/trim out)))

(defn- init-repo-with-pr-branch
  "Sets up a bare `origin`, plus a clone on `main` with an uncommitted update to deps.edn,
  and an existing PR branch on origin with a different version of deps.edn."
  [root]
  (let [origin (babashka.fs/path root "origin.git")
        work (babashka.fs/path root "work")]
    (git! root "init" "-q" "--bare" "-b" "main" (str origin))
    (git! root "clone" "-q" (str origin) (str work))
    (doseq [[k v] {"user.name" "test" "user.email" "test@example.com" "commit.gpgsign" "false"}]
      (git! work "config" k v))
    (git! work "switch" "-q" "-c" "main")
    (spit (str (babashka.fs/path work "deps.edn")) "{:deps {foo/bar {:mvn/version \"1\"}}}\n")
    (git! work "add" "deps.edn")
    (git! work "commit" "-q" "-m" "base")
    (git! work "push" "-q" "origin" "main")
    (git! work "switch" "-q" "-c" "clojure-dependabot/deps-edn")
    (spit (str (babashka.fs/path work "deps.edn")) "{:deps {foo/bar {:mvn/version \"2\"}}}\n")
    (git! work "commit" "-q" "-am" "[bot] Updating dependencies in deps.edn")
    (git! work "push" "-q" "origin" "clojure-dependabot/deps-edn")
    (git! work "switch" "-q" "main")
    (git! work "branch" "-q" "-D" "clojure-dependabot/deps-edn")
    (spit (str (babashka.fs/path work "deps.edn")) "{:deps {foo/bar {:mvn/version \"3\"}}}\n")
    {:origin origin :work work}))

(defn- update-pr-with-stubbed-gh!
  "Runs update-pr with real git but stubbed `gh`. `comments` are the PR's existing comment bodies.
  Returns the `gh` calls made."
  [work comments]
  (let [real-sh @#'clojure-dependabot/sh
        gh-calls (atom [])
        opts {:file "deps.edn"
              :branch "clojure-dependabot/deps-edn"
              :body "body"
              :github-ref "refs/heads/main"
              :github-repository "owner/repo"
              :github-workspace (str work)}]
    (with-redefs [clojure-dependabot/sh (fn [& args]
                                          (if (= "gh" (first args))
                                            (do (swap! gh-calls conj (vec args))
                                                {:out (json/generate-string [(map (fn [b] {:body b}) comments)])})
                                            (apply real-sh args)))]
      (#'clojure-dependabot/update-pr 7 opts))
    @gh-calls))

(deftest test-update-pr
  (let [root (babashka.fs/create-temp-dir)
        {:keys [origin work]} (init-repo-with-pr-branch root)
        pr-head #(git! origin "rev-parse" "refs/heads/clojure-dependabot/deps-edn")]
    (try
      (testing "an existing PR branch is updated even though it has its own version of the file"
        (let [gh-calls (update-pr-with-stubbed-gh! work [])]
          (is (= "{:deps {foo/bar {:mvn/version \"3\"}}}"
                 (git! origin "show" "clojure-dependabot/deps-edn:deps.edn")))
          (is (= (git! origin "rev-parse" "main") (git! origin "rev-parse" "clojure-dependabot/deps-edn~1"))
              "the PR branch is rebuilt on top of the base branch")
          (is (= "main" (git! work "branch" "--show-current")) "the base branch is checked out afterwards")
          (is (= "" (git! work "status" "--porcelain")) "the working tree is left clean")
          (is (= [["gh" "pr" "edit" 7]] (map #(subvec % 0 4) gh-calls)) "the PR is edited by number")))

      (testing "re-running with the same update does not push a new commit"
        (let [head-before (pr-head)]
          (spit (str (babashka.fs/path work "deps.edn")) "{:deps {foo/bar {:mvn/version \"3\"}}}\n")
          (let [gh-calls (update-pr-with-stubbed-gh! work [])]
            (is (= head-before (pr-head)))
            (is (= [["gh" "pr" "edit" 7]] (map #(subvec % 0 4) gh-calls)) "the PR description is still refreshed"))))
      (finally
        (babashka.fs/delete-tree root)))))

(deftest test-update-pr-skips-branches-with-other-commits
  (let [root (babashka.fs/create-temp-dir)
        {:keys [origin work]} (init-repo-with-pr-branch root)
        marker @#'clojure-dependabot/skipped-pr-comment-marker
        pr-head #(git! origin "rev-parse" "refs/heads/clojure-dependabot/deps-edn")
        ;; someone else pushes a commit to the PR branch
        human-head (let [other (babashka.fs/path root "other")]
                     (git! root "clone" "-q" "-b" "clojure-dependabot/deps-edn" (str origin) (str other))
                     (spit (str (babashka.fs/path other "deps.edn")) "{:deps {foo/bar {:mvn/version \"2\"}} :paths [\"src\"]}\n")
                     (git! other "-c" "user.name=human" "-c" "user.email=human@example.com" "-c" "commit.gpgsign=false"
                           "commit" "-q" "-am" "manual fix")
                     (git! other "push" "-q" "origin" "clojure-dependabot/deps-edn")
                     (git! other "rev-parse" "HEAD"))]
    (try
      (testing "the update is skipped and the PR is told why"
        (let [gh-calls (update-pr-with-stubbed-gh! work [])]
          (is (= human-head (pr-head)) "the other commit is not overwritten")
          (is (= "main" (git! work "branch" "--show-current")))
          (is (= "" (git! work "status" "--porcelain")) "the skipped update is discarded")
          (is (= ["gh" "api" "repos/owner/repo/issues/7/comments"] (subvec (first gh-calls) 0 3)))
          (is (= ["gh" "pr" "comment" 7 "--body"] (subvec (second gh-calls) 0 5)))
          (is (str/starts-with? (nth (second gh-calls) 5) marker))
          (is (= 2 (count gh-calls)) "the PR description is not edited")))

      (testing "the comment is only posted the first time"
        (spit (str (babashka.fs/path work "deps.edn")) "{:deps {foo/bar {:mvn/version \"3\"}}}\n")
        (let [gh-calls (update-pr-with-stubbed-gh! work ["unrelated" (str marker "\nalready posted")])]
          (is (= human-head (pr-head)))
          (is (= [["gh" "api"]] (map #(subvec % 0 2) gh-calls)) "no new comment is posted")))
      (finally
        (babashka.fs/delete-tree root)))))

(deftest test-ref->branch
  (let [ref->branch #'clojure-dependabot/ref->branch]
    (is (= "main" (ref->branch "refs/heads/main")))
    (is (= "feature/refs/heads/x" (ref->branch "refs/heads/feature/refs/heads/x")))
    (is (= "main" (ref->branch "main")))))

(deftest test-parse-ignore-dependencies
  (is (= (parse-ignore-dependencies "foo:bar:1.2.3") (seq [{:group-id "foo" :artifact-id "bar" :version "1.2.3"}])))
  (is (= (parse-ignore-dependencies "foo:bar") (seq [{:group-id "foo" :artifact-id "bar"}]))))

(deftest test-fetch-dependabot-alerts
  (testing "paginated responses parse all arrays of responses"
    (with-redefs [clojure-dependabot/gh-api-dependabot-alerts mock-gh-api-dependabot-alerts]
      (let [parsed-results (clojure-dependabot/fetch-dependabot-alerts {})]
        (is (= (->> parsed-results
                    (map :number)
                    (into #{}))
               #{1 2}))))))

(deftest the-whole-shebang
  (testing "the whole thing works when (most) I/O is mocked away"
    (with-redefs [babashka.process/sh bail
                  clojure-dependabot/parse-opts (constantly default-opts)
                  clojure-dependabot/configure-git noop
                  clojure-dependabot/git-ls-files mock-git-ls-files
                  clojure-dependabot/outdated-cmd mock-outdated-cmd
                  clojure-dependabot/gh-api-dependabot-alerts mock-gh-api-dependabot-alerts
                  clojure-dependabot/mvn-scan noop
                  clojure-dependabot/mvn-tree-txt mock-mvn-tree-txt
                  clojure-dependabot/mvn-tree-json mock-mvn-tree-json
                  clojure-dependabot/antq-update noop
                  clojure-dependabot/file-changed-in-git? (constantly true)
                  clojure-dependabot/list-prs noop
                  clojure-dependabot/open-pr noop
                  clojure-dependabot/update-pr noop
                  clojure-dependabot/close-pr noop
                  clojure-dependabot/git-cleanup noop]
      (is (nil? (main))))))
