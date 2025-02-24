(ns pom-generator
  (:require [clojure.data.xml :as xml]
            [clojure.tools.deps.tree :as tree]))

(defn effective-deps [project-dir]
  (let [trace (tree/calc-trace {:dir project-dir})
        tree  (tree/trace->tree trace)]
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

(defn deps->pom [deps destination]
  (let [tags (xml/element
              :project {}
              (xml/element
               :dependencies {}
               (for [{dep-name :name :as dep} deps]
                (xml/element
                 :dependency {}
                 (xml/element :groupId {} (str (namespace dep-name)))
                 (xml/element :artifactId {} (str (name dep-name)))
                 (xml/element :version {} (str (-> dep :coord :mvn/version)))))))]
    (with-open [out-file (java.io.FileWriter. destination)]
      (xml/emit tags out-file))))


;; example - note the escaping of the quotes. WEIRD
;; clojure -X pom-generator/generate-pom :path \"/home/ryan/lc/utwig\"

;; clojure -Sdeps \{\:deps\ \{org.clojure/tools.deps\ \{\:mvn/version\ \"0.22.1492\"\}\ org.clojure/data.xml\ \{\:mvn/version\ \"0.0.8\"\}\}\ \:paths\ \[\"pom-generator\"\]\} -X pom-generator/generate-pom :path \"/home/ryan/lc/utwig\"
(defn generate-pom [{:keys [path]}]
  (->  path
       effective-deps
       (deps->pom "pom.xml")))


