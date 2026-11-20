(ns smoke-test
  (:require
    [camel-snake-kebab.core :as csk]
    [cheshire.core :as json]
    [babashka.fs :as fs]
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

;; strings owned by the code under test, so the tests don't hard-code them
(def ^:private commit-message #'clojure-dependabot/commit-message)
(def ^:private pr-title #'clojure-dependabot/pr-title)
(def ^:private pr-branch #'clojure-dependabot/pr-branch)
(def ^:private branch (pr-branch "deps.edn"))

(defn- warned-about?
  "Whether `out` contains a GitHub Actions warning annotation mentioning `file`."
  [out file]
  (boolean (some #(and (str/starts-with? % "::warning::") (str/includes? % file))
                 (str/split-lines out))))

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

(defmacro ^:private with-temp-dir
  "Binds `sym` to the path of a new temporary directory, which is deleted after `body` runs."
  [[sym] & body]
  `(let [~sym (str (fs/create-temp-dir))]
     (try
       ~@body
       (finally
         (fs/delete-tree ~sym)))))

(deftest test-manage-prs-runs-antq-updates
  (testing "each update is run with antq in the project's directory, not on the project file"
    (with-temp-dir [workspace]
      (let [project-dir (str (fs/create-dirs (fs/path workspace "foo")))
            file {:full-path (str (fs/path project-dir "deps.edn"))
                  :project-path "foo/deps.edn"}
            alerts [{:dependency {:manifest-path "foo/deps.edn" :package {:name "foo:bar"}}}]
            outdated {"foo/deps.edn" #{{:name "baz/quux" :latest-version "1.0.0"}}}
            calls (atom [])]
        (with-redefs [babashka.process/sh bail
                      clojure-dependabot/list-prs (constantly "[]")
                      clojure-dependabot/antq-update (fn [& args] (swap! calls conj (vec args)))
                      clojure-dependabot/file-changed-in-git? (constantly false)]
          (#'clojure-dependabot/manage-prs alerts outdated [file] {:security-updates-only false}))
        (is (= [[project-dir "foo/bar" nil "leiningen"]
                [project-dir "baz/quux" "1.0.0" "leiningen"]]
               @calls))))))

(deftest test-gh-package->antq-name
  (let [gh-package->antq-name #'clojure-dependabot/gh-package->antq-name]
    (is (= "com.fasterxml.jackson.core/jackson-databind"
           (gh-package->antq-name "com.fasterxml.jackson.core:jackson-databind")))
    (is (= "foo/bar" (gh-package->antq-name "foo/bar")) "antq-style names are unchanged")))

(deftest test-antq-skip
  (let [antq-skip #'clojure-dependabot/antq-skip]
    (is (= "leiningen" (antq-skip "/ws/foo/deps.edn")))
    (is (= "clojure-cli" (antq-skip "/ws/foo/project.clj")))))

(deftest test-antq-updates
  (let [antq-updates #'clojure-dependabot/antq-updates
        file {:full-path "/ws/deps.edn" :project-path "deps.edn"}
        alert (fn [manifest-path package]
                {:dependency {:manifest-path manifest-path :package {:name package}}
                 :security-advisory {:vulnerabilities [{:package {:name package}}
                                                       {:package {:name "not:in-this-project"}}]}})
        outdated {"deps.edn" #{{:name "baz/quux" :version "0.9.0" :latest-version "1.0.0"}}
                  "other/deps.edn" #{{:name "other/thing" :version "1" :latest-version "2"}}}]
    (testing "security updates come first, in antq's group/artifact form, followed by outdated dependencies"
      (is (= [["foo/bar" nil] ["baz/quux" "1.0.0"]]
             (antq-updates file [(alert "deps.edn" "foo:bar")] outdated false))))

    (testing "only security updates when security-updates-only"
      (is (= [["foo/bar" nil]]
             (antq-updates file [(alert "deps.edn" "foo:bar")] outdated true))))

    (testing "security updates target each alert's own package, once, not every package in the advisory"
      (is (= [["foo/bar" nil]]
             (antq-updates file
                           [(alert "deps.edn" "foo:bar")
                            ;; a second alert for the same package, e.g. from a different advisory
                            (alert "deps.edn" "foo:bar")
                            ;; an alert for a different project
                            (alert "other/deps.edn" "baz:quux")]
                           outdated
                           true))))

    (testing "nothing to update"
      (is (empty? (antq-updates file [] {} false))))))

(deftest test-pr-action
  (let [pr-action #'clojure-dependabot/pr-action]
    (doseq [[open-pr? changed? expected] [[false true :open]
                                          [true true :update]
                                          [true false :close]
                                          [false false :none]]]
      (is (= expected (pr-action {:open-pr? open-pr? :changed? changed?}))
          (str "open PR: " open-pr? ", changed: " changed?)))))

(deftest test-branch-plan
  (let [branch-plan #'clojure-dependabot/branch-plan]
    (doseq [[remote-branch? bot-tip? expected] [[false nil :create]
                                                [true true :overwrite]
                                                [true false :skip]]]
      (is (= expected (branch-plan {:remote-branch? remote-branch? :bot-tip? bot-tip?}))
          (str "remote branch: " remote-branch? ", bot tip: " bot-tip?)))))

(deftest test-gh-pr-create-args
  (let [gh-pr-create-args #'clojure-dependabot/gh-pr-create-args
        opts {:file "deps.edn" :body "body" :main-branch "main"}
        base ["gh" "pr" "create" "--base" "main" "--title" (pr-title "deps.edn") "--body" "body"]]
    (is (= base (gh-pr-create-args opts)))
    (is (= base (gh-pr-create-args (assoc opts :reviewers [] :labels []))) "empty lists are left out")
    (is (= (into base ["--reviewer" "alice,bob" "--label" "deps,security"])
           (gh-pr-create-args (assoc opts :reviewers ["alice" "bob"] :labels ["deps" "security"]))))))

(deftest test-manage-prs-handles-each-project-once
  (testing "PRs are managed once per project, regardless of how many alerts it has (including none)"
    (with-temp-dir [workspace]
      (let [many-alerts {:full-path (str (fs/path workspace "a" "deps.edn"))
                         :project-path "a/deps.edn"}
            no-alerts {:full-path (str (fs/path workspace "b" "deps.edn"))
                       :project-path "b/deps.edn"}
            alerts [{:dependency {:manifest-path "a/deps.edn" :package {:name "foo:bar"}}}
                    {:dependency {:manifest-path "a/deps.edn" :package {:name "baz:quux"}}}]
            calls (atom [])]
        (with-redefs [babashka.process/sh bail
                      clojure-dependabot/list-prs (constantly (json/generate-string [{:number 7 :headRefName (pr-branch "b/deps.edn")}]))
                      clojure-dependabot/antq-update (constantly nil)
                      clojure-dependabot/vuln-summary (constantly "body")
                      clojure-dependabot/file-changed-in-git? #(= % (:full-path many-alerts))
                      clojure-dependabot/open-pr (fn [{:keys [file]}] (swap! calls conj [:open file]))
                      clojure-dependabot/close-pr (fn [number] (swap! calls conj [:close number]))]
          (#'clojure-dependabot/manage-prs alerts {} [many-alerts no-alerts] {:security-updates-only true}))
        (is (= [[:open "a/deps.edn"] [:close 7]] @calls))))))

(def ^:private pr-opts
  {:file "deps.edn"
   :branch branch
   :body "body"
   :main-branch "main"
   :github-ref "refs/heads/main"
   :github-repository "owner/repo"
   :github-workspace "/workspace"})

(defn- git! [dir & args]
  (let [{:keys [exit out err]} (apply babashka.process/sh {:dir (str dir)} "git" args)]
    (when-not (zero? exit)
      (throw (ex-info "git failed" {:args args :out out :err err})))
    (str/trim out)))

(defn- init-repo-with-pr-branch
  "Sets up a bare `origin`, plus a clone on `main` with an uncommitted update to deps.edn,
  and an existing PR branch on origin with a different version of deps.edn."
  [root]
  (let [origin (fs/path root "origin.git")
        work (fs/path root "work")]
    (git! root "init" "-q" "--bare" "-b" "main" (str origin))
    (git! root "clone" "-q" (str origin) (str work))
    (doseq [[k v] {"user.name" "test" "user.email" "test@example.com" "commit.gpgsign" "false"}]
      (git! work "config" k v))
    (git! work "switch" "-q" "-c" "main")
    (spit (str (fs/path work "deps.edn")) "{:deps {foo/bar {:mvn/version \"1\"}}}\n")
    (git! work "add" "deps.edn")
    (git! work "commit" "-q" "-m" "base")
    (git! work "push" "-q" "origin" "main")
    (git! work "switch" "-q" "-c" branch)
    (spit (str (fs/path work "deps.edn")) "{:deps {foo/bar {:mvn/version \"2\"}}}\n")
    (git! work "commit" "-q" "-am" (commit-message "deps.edn"))
    (git! work "push" "-q" "origin" branch)
    (git! work "switch" "-q" "main")
    (git! work "branch" "-q" "-D" branch)
    (spit (str (fs/path work "deps.edn")) "{:deps {foo/bar {:mvn/version \"3\"}}}\n")
    {:origin origin :work work}))

(defn- push-foreign-commit!
  "Has someone other than the bot push a commit to the PR branch on `origin`, from a separate
  clone under `root`. Returns the new tip of the branch."
  [root origin]
  (let [other (fs/path root "other")]
    (git! root "clone" "-q" "-b" branch (str origin) (str other))
    (spit (str (fs/path other "deps.edn")) "{:deps {foo/bar {:mvn/version \"2\"}} :paths [\"src\"]}\n")
    (git! other "-c" "user.name=human" "-c" "user.email=human@example.com" "-c" "commit.gpgsign=false"
          "commit" "-q" "-am" "manual fix")
    (git! other "push" "-q" "origin" branch)
    (git! other "rev-parse" "HEAD")))

(defn- remote-pr-head [origin]
  (git! origin "rev-parse" (str "refs/heads/" branch)))

(def ^:private fake-clock
  "Seconds since the epoch, advanced for every git call made by the code under test."
  (atom 1700000000))

(defn- with-fake-git-date
  "Adds a fake, always-increasing commit date to the options of a git call, so that rebuilding a
  commit always gives it a new hash, even within the same second. Otherwise an unnecessary
  force-push of an identical commit would be invisible on origin."
  [opts]
  (let [date (str (swap! fake-clock inc) " +0000")]
    (update opts :extra-env merge {"GIT_AUTHOR_DATE" date "GIT_COMMITTER_DATE" date})))

(defn- run-with-stubbed-gh!
  "Calls `(pr-fn opts)` against the real git repo `work`, with `gh` stubbed.
  `comments` are the PR's existing comment bodies. Returns the `gh` calls made and the output logged."
  [pr-fn work & {:keys [comments]}]
  (let [real-sh @#'clojure-dependabot/sh
        gh-calls (atom [])
        out (with-out-str
              (with-redefs [clojure-dependabot/sh (fn [& args]
                                                    (cond
                                                      (= "gh" (first args))
                                                      (do (swap! gh-calls conj (vec args))
                                                          {:out (json/generate-string [(map (fn [b] {:body b}) comments)])})

                                                      (and (map? (first args)) (= "git" (second args)))
                                                      (apply real-sh (with-fake-git-date (first args)) (rest args))

                                                      :else
                                                      (apply real-sh args)))]
                (pr-fn (assoc pr-opts :github-workspace (str work)))))]
    {:gh-calls @gh-calls :out out}))

(defn- update-pr! [work & opts]
  (apply run-with-stubbed-gh! #(#'clojure-dependabot/update-pr 7 %) work opts))

(defn- open-pr! [work & opts]
  (apply run-with-stubbed-gh! #'clojure-dependabot/open-pr work opts))

(deftest test-update-pr
  (with-temp-dir [root]
    (let [{:keys [origin work]} (init-repo-with-pr-branch root)]
      (testing "an existing PR branch is updated even though it has its own version of the file"
        (let [{:keys [gh-calls]} (update-pr! work)]
          (is (= "{:deps {foo/bar {:mvn/version \"3\"}}}"
                 (git! origin "show" (str branch ":deps.edn"))))
          (is (= (git! origin "rev-parse" "main") (git! origin "rev-parse" (str branch "~1")))
              "the PR branch is rebuilt on top of the base branch")
          (is (= "main" (git! work "branch" "--show-current")) "the base branch is checked out afterwards")
          (is (= "" (git! work "status" "--porcelain")) "the working tree is left clean")
          (is (= [["gh" "pr" "edit" 7]] (map #(subvec % 0 4) gh-calls)) "the PR is edited by number")))

      (testing "re-running with the same update does not push a new commit"
        (let [head-before (remote-pr-head origin)]
          (spit (str (fs/path work "deps.edn")) "{:deps {foo/bar {:mvn/version \"3\"}}}\n")
          (let [{:keys [gh-calls]} (update-pr! work)]
            (is (= head-before (remote-pr-head origin)))
            (is (= [["gh" "pr" "edit" 7]] (map #(subvec % 0 4) gh-calls)) "the PR description is still refreshed")))))))

(deftest test-update-pr-skips-branches-with-other-commits
  (with-temp-dir [root]
    (let [{:keys [origin work]} (init-repo-with-pr-branch root)
          marker @#'clojure-dependabot/skipped-pr-comment-marker
          human-head (push-foreign-commit! root origin)]
      (testing "the update is skipped and the PR is told why"
        (let [{:keys [gh-calls]} (update-pr! work)]
          (is (= human-head (remote-pr-head origin)) "the other commit is not overwritten")
          (is (= "main" (git! work "branch" "--show-current")))
          (is (= "" (git! work "status" "--porcelain")) "the skipped update is discarded")
          (is (= ["gh" "api" "repos/owner/repo/issues/7/comments"] (subvec (first gh-calls) 0 3)))
          (is (= ["gh" "pr" "comment" 7 "--body"] (subvec (second gh-calls) 0 5)))
          (is (str/starts-with? (nth (second gh-calls) 5) marker))
          (is (= 2 (count gh-calls)) "the PR description is not edited")))

      (testing "the comment is only posted the first time"
        (spit (str (fs/path work "deps.edn")) "{:deps {foo/bar {:mvn/version \"3\"}}}\n")
        (let [{:keys [gh-calls]} (update-pr! work :comments ["unrelated" (str marker "\nalready posted")])]
          (is (= human-head (remote-pr-head origin)))
          (is (= [["gh" "api"]] (map #(subvec % 0 2) gh-calls)) "no new comment is posted"))))))

(deftest test-open-pr-over-leftover-bot-branch
  (testing "a leftover branch whose tip is the bot's commit (e.g. from a closed PR) is force-pushed over"
    (with-temp-dir [root]
      (let [{:keys [origin work]} (init-repo-with-pr-branch root)
            {:keys [gh-calls out]} (open-pr! work)]
        (is (= "{:deps {foo/bar {:mvn/version \"3\"}}}"
               (git! origin "show" (str branch ":deps.edn"))))
        (is (= (git! origin "rev-parse" "main") (git! origin "rev-parse" (str branch "~1")))
            "the branch is rebuilt on top of the base branch")
        (is (= "main" (git! work "branch" "--show-current")))
        (is (= "" (git! work "status" "--porcelain")))
        (is (= [["gh" "pr" "create"]] (map #(subvec % 0 3) gh-calls)))
        (is (not (str/includes? out "::warning::")))))))

(deftest test-open-pr-skips-leftover-branch-with-other-commits
  (testing "a leftover branch with someone else's commit is not overwritten, and a warning is emitted"
    (with-temp-dir [root]
      (let [{:keys [origin work]} (init-repo-with-pr-branch root)
            human-head (push-foreign-commit! root origin)
            {:keys [gh-calls out]} (open-pr! work)]
        (is (= human-head (remote-pr-head origin)) "the other commit is not overwritten")
        (is (= [] gh-calls) "no PR is opened")
        (is (warned-about? out "deps.edn"))
        (is (= "main" (git! work "branch" "--show-current")))
        (is (= "" (git! work "status" "--porcelain")) "the skipped update is discarded")))))

(defn- delete-remote-pr-branch! [origin]
  (git! origin "branch" "-q" "-D" branch))

(deftest test-open-pr-new-branch
  (testing "a new branch is created on top of the base branch, pushed, and a PR opened"
    (with-temp-dir [root]
      (let [{:keys [origin work]} (init-repo-with-pr-branch root)
            _ (delete-remote-pr-branch! origin)
            {:keys [gh-calls out]} (open-pr! work)]
        (is (= "{:deps {foo/bar {:mvn/version \"3\"}}}"
               (git! origin "show" (str branch ":deps.edn"))))
        (is (= (git! origin "rev-parse" "main") (git! origin "rev-parse" (str branch "~1"))))
        (is (= "main" (git! work "branch" "--show-current")))
        (is (= "" (git! work "status" "--porcelain")))
        (is (= [["gh" "pr" "create"]] (map #(subvec % 0 3) gh-calls)))
        (is (not (str/includes? out "::warning::")))))))

(deftest test-update-pr-deleted-branch
  (testing "a PR whose branch was deleted from origin has it pushed again"
    (with-temp-dir [root]
      (let [{:keys [origin work]} (init-repo-with-pr-branch root)
            _ (delete-remote-pr-branch! origin)
            {:keys [gh-calls]} (update-pr! work)]
        (is (= "{:deps {foo/bar {:mvn/version \"3\"}}}"
               (git! origin "show" (str branch ":deps.edn"))))
        (is (= (git! origin "rev-parse" "main") (git! origin "rev-parse" (str branch "~1"))))
        (is (= "main" (git! work "branch" "--show-current")))
        (is (= "" (git! work "status" "--porcelain")))
        (is (= [["gh" "pr" "edit" 7]] (map #(subvec % 0 4) gh-calls)))))))

(defn- with-stubbed-git
  "Calls `f` with git, git-ok?, and `gh` stubbed, and returns the git and gh calls made, in
  order, plus everything logged. The PR branch's tip, if it exists, is the bot's own commit.
  - `remote-branch?`: whether fetching the PR branch succeeds, i.e. it exists on origin
  - `gh-fails?`: whether `gh` calls throw (the exception is caught and recorded as a `[:threw msg]` call)"
  [f & {:keys [remote-branch? gh-fails?]}]
  (let [calls (atom [])
        out (with-out-str
              (with-redefs [babashka.process/sh bail
                            clojure-dependabot/git (fn [dir & args]
                                                     (is (= "/workspace" dir))
                                                     (swap! calls conj (into [:git] args))
                                                     {:out (if (or (= ["config" "user.email"] args) (= "log" (first args)))
                                                             "bot@example.com\n"
                                                             "")})
                            clojure-dependabot/git-ok? (fn [dir & args]
                                                         (is (= "/workspace" dir))
                                                         (swap! calls conj (into [:git-ok?] args))
                                                         (case (first args)
                                                           "fetch" (boolean remote-branch?)
                                                           "diff" false))
                            clojure-dependabot/sh (fn [& args]
                                                    (is (= "gh" (first args)) "all git calls go through the git helpers")
                                                    (swap! calls conj (vec args))
                                                    (when gh-fails?
                                                      (throw (ex-info "gh failed" {})))
                                                    {:out ""})]
                (try
                  (f)
                  (catch clojure.lang.ExceptionInfo e
                    (swap! calls conj [:threw (ex-message e)])))))]
    {:calls @calls :out out}))

(defn- call-matches? [prefix call]
  (= (seq prefix) (seq (take (count prefix) call))))

(defn- in-order?
  "Whether a call matching each prefix was made, in this order (other calls may be in between)."
  [calls & prefixes]
  (loop [calls calls
         [prefix & more :as prefixes] prefixes]
    (cond
      (empty? prefixes) true
      (empty? calls) false
      (call-matches? prefix (first calls)) (recur (rest calls) more)
      :else (recur (rest calls) prefixes))))

(def ^:private git-write-commands
  #{"switch" "checkout" "commit" "push" "restore" "reset" "merge" "rebase"})

(defn- git-writes
  "The git calls that change the workspace or the remote."
  [calls]
  (filterv #(and (= :git (first %)) (git-write-commands (second %))) calls))

(def ^:private switch-to-base [:git "switch" "main"])

(defn- ends-on-base-branch? [calls]
  (= switch-to-base (last (git-writes calls))))

(deftest test-open-pr-gh-fails
  (testing "the base branch is checked out again when opening the PR fails"
    (let [{:keys [calls]} (with-stubbed-git #(#'clojure-dependabot/open-pr pr-opts)
                            :remote-branch? false :gh-fails? true)]
      (is (in-order? calls ["gh" "pr" "create"] switch-to-base))
      (is (ends-on-base-branch? calls))
      (is (= [:threw "gh failed"] (last calls)) "the error is still thrown, after switching back"))))

(deftest test-update-pr-gh-fails
  (testing "the base branch is checked out again when editing the PR fails"
    (let [{:keys [calls]} (with-stubbed-git #(#'clojure-dependabot/update-pr 7 pr-opts)
                            :remote-branch? true :gh-fails? true)]
      (is (in-order? calls ["gh" "pr" "edit" 7] switch-to-base))
      (is (ends-on-base-branch? calls))
      (is (= [:threw "gh failed"] (last calls)) "the error is still thrown, after switching back"))))

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
