#!/usr/bin/env clojure
;;
;; smoke_test.clj — Issue #2 動作確認スクリプト
;;
;; 実行方法:
;;   clojure -A:xtdb -M test/smoke_test.clj trials/samples/repo

(require '[clojure.java.io :as io]
         '[clojure.string :as str])

;; xtdb は JVM クラスパスにある前提
(require '[xtdb.api :as xt])
(require '[xtdb.node :as xtn])

;; --------------- ingest ---------------

(defn file->doc [root-path ^java.io.File f]
  (let [abs  (.getAbsolutePath f)
        root (.getAbsolutePath (io/file root-path))
        rel  (subs abs (inc (count root)))
        ext  (let [n (.getName f)]
               (when (str/includes? n ".") (last (str/split n #"\."))))
        par  (.getParent (io/file rel))]
    {:xt/id       rel
     :file/path   rel
     :file/name   (.getName f)
     :file/ext    ext
     :file/size   (.length f)
     :file/dir?   (.isDirectory f)
     :file/parent par}))

(defn ingest-dir! [node root]
  (let [root-file (io/file root)
        docs (->> (file-seq root-file)
                  (remove #(= % root-file))
                  (map #(file->doc root %)))
        txs  (mapv (fn [d] [:put-docs :files d]) docs)]
    (xt/execute-tx node txs)
    (count docs)))

;; --------------- visualize ---------------

(defn build-tree [docs]
  (reduce (fn [m {:keys [file/path file/dir?]}]
            (when path
              (assoc-in m (conj (vec (str/split path #"/")) ::leaf?) (not dir?))))
          {}
          docs))

(defn render-tree [m prefix]
  (doseq [[k v] (sort-by first (dissoc m ::leaf?))]
    (when (string? k)
      (let [leaf? (get v ::leaf?)]
        (println (str prefix k (if leaf? "" "/")))
        (when-not leaf? (render-tree v (str prefix "  ")))))))

;; --------------- main ---------------

(let [root (or (first *command-line-args*) "trials/samples/repo")]
  (println (str "=== ingest: " root " ==="))
  (with-open [node (xtn/start-node {})]
    (let [n (ingest-dir! node root)]
      (println (str "ingested: " n " entries"))
      (println)
      (println "=== query: all files ===")
      (let [result (xt/q node '(from :files [*]))]
        (println (str "rows: " (count result)))
        (println)
        (println "=== visualize: tree ===")
        (render-tree (build-tree result) "")))))
