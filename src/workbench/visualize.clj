(ns workbench.visualize
  "クエリ結果をテキスト形式で可視化する関数群。")

;; -------------------------
;; helpers
;; -------------------------

(defn- build-tree [docs]
  "パス文字列のリストからネスト map を組み立てる。"
  (reduce (fn [m {:keys [file/path file/dir?]}]
            (let [parts (clojure.string/split path #"/")]
              (assoc-in m (conj (vec parts) ::leaf?) (not dir?))))
          {}
          docs))

(defn- render-tree [m prefix]
  (doseq [[k v] (sort-by first (dissoc m ::leaf?))]
    (let [leaf? (get v ::leaf?)]
      (println (str prefix k (if leaf? "" "/")))
      (when-not leaf?
        (render-tree v (str prefix "  "))))))

;; -------------------------
;; public API
;; -------------------------

(defn tree
  "クエリ結果（:file/path を持つ map のシーケンス）をツリー表示する。

   例:
     (visualize/tree (query/q node \"SELECT path, dir FROM files ORDER BY path\"))"
  [result]
  (render-tree (build-tree result) ""))
