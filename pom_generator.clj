(ns pom-generator
  (:require [clojure.data.xml :as xml]
            [clojure.tools.deps.tree :as tree]))

(defn effective-deps [trace]
    (let [tree  (tree/trace->tree trace)]
      (->> tree
           (tree-seq :children (fn [{:keys [children]}]
                                 (map
                                  (fn [k v]
                                    (assoc v :name k))
                                  (keys children)
                                  (vals children))))
           (rest)
           (map #(dissoc % :children))
           (remove #(-> % :coord :git/url))
           (filter :include))))

(defn exclusion-element [exclusion]
  (xml/element :exclusion {}
               (xml/element :groupId {} (namespace exclusion))
               (xml/element :artifactId {} (str (name exclusion)))))

(defn- excluded? [{:keys [include reason]}]
  (and (false? include)
       (= :excluded reason)))

(defn deps->pom [deps repository destination]
  (let [tags (xml/element
               :project {:xmlns "http://maven.apache.org/POM/4.0.0"
                         :xmlns:xsi "http://www.w3.org/2001/XMLSchema-instance"
                         :xsi:schemaLocation "http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd"}
               (xml/element :modelVersion {} "4.0.0")
               (xml/element :packaging {} "jar")
               (xml/element :groupId {} repository)
               (xml/element :artifactId {} repository)
               (xml/element :version {} "0.1.0")
               (xml/element :name {} repository)
               (xml/element
                 :dependencies {}
                 (for [{dep-name :lib :as dep} deps
                       :when (not (excluded? dep))]
                   (xml/element
                    :dependency {}
                    (xml/element :groupId {} (namespace dep-name))
                    (xml/element :artifactId {} (str (name dep-name)))
                    (xml/element :version {} (str (-> dep :coord :mvn/version)))
                    (when-let [exclusions (-> dep :coord :exclusions)]
                      (xml/element :exclusions {}
                                   (map exclusion-element exclusions))))))

               (xml/element :build {}
                            (xml/element :sourceDirectory {} "src-shared"))

               (xml/element
                 :repositories {}
                 (xml/element
                   :repository {}
                   (xml/element :id {} "central")
                   (xml/element :url {} "https://repo1.maven.org/maven2"))

                 (xml/element
                   :repository {}
                   (xml/element :id {} "clojars")
                   (xml/element :url {} "https://clojars.org/repo"))

                 (xml/element
                   :repository {}
                   (xml/element :id {} "jboss")
                   (xml/element :url {} "https://repository.jboss.org/maven2"))

                 ))]

    (with-open [out-file (java.io.FileWriter. destination)]
      (xml/emit tags out-file))))

(defn generate-pom [{:keys [repository trace-path]
                     :or {trace-path "trace.edn"}}]
  (-> (effective-deps (read-string (slurp trace-path)))
      (deps->pom repository "pom.xml")))

(comment
  (clojure.repl.deps/add-lib 'djblue/portal {:mvn/version "RELEASE"})
  (tree/trace->tree (read-string (slurp "../utwig/trace.edn")))
  (generate-pom {:repository "utwig" :trace-path "../utwig/trace.edn" })
  ;; Testing this is relatively easy:
  ;; copy this file into utwig/pom-generator/pom_generator.clj (or whichever project you want)
  ;; (so - pom-generator is in the same level as src, src-shared etc.)
  ;; in terminal (in the root directory of the project) run:
  ;; clojure -X:deps prep
  ;; clojure -A:app -Strace
  ;; ^ these generate a trace.edn file
  ;; then execute (replace $GITHUB_REPOSITORY with some text you like
  ;;       clojure -Sdeps \{\:deps\ \{org.clojure/tools.deps\ \{\:mvn/version\ \"0.22.1492\"\}\ org.clojure/data.xml\ \{\:mvn/version\ \"0.0.8\"\}\}\ \:paths\ \[\"pom-generator\"\]\} -X pom-generator/generate-pom :repository \"$GITHUB_REPOSITORY\"
  
  :.)
