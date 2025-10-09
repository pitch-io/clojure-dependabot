(ns smoke-test
  (:require
    [camel-snake-kebab.core :as csk]
    [cheshire.core :as json]
    [clojure.java.io :as io]
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

(deftest test-parse-ignore-dependencies
  (is (= (parse-ignore-dependencies "foo:bar:1.2.3") (seq [{:group-id "foo" :artifact-id "bar" :version "1.2.3"}])))
  (is (= (parse-ignore-dependencies "foo:bar") (seq [{:group-id "foo" :artifact-id "bar"}]))))

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
      (main))))
