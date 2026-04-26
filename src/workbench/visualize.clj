(ns workbench.visualize
  "クエリ結果をテキスト形式で可視化する関数群。"
  (:require [clojure.string :as str]))

;; -------------------------
;; helpers
;; -------------------------

(defn- build-tree [docs]
  "パス文字列のリストからネスト map を組み立てる。"
  (reduce (fn [m {:keys [file/path file/dir?]}]
            (if path
              (let [parts (str/split path #"/")]
                (assoc-in m (conj (vec parts) ::leaf?) (not dir?)))
              m))
          {}
          docs))

(defn- render-tree [m prefix]
  (doseq [[k v] (sort-by first (dissoc m ::leaf?))]
    (when (string? k)
      (let [leaf? (get v ::leaf?)]
        (println (str prefix k (if leaf? "" "/")))
        (when-not leaf?
          (render-tree v (str prefix "  ")))))))

;; -------------------------
;; public API
;; -------------------------

(defn tree
  "クエリ結果（:file/path を持つ map のシーケンス）をツリー表示する（stdout）。

   例:
     (visualize/tree (xt/q node '(from :files [*])))"
  [result]
  (render-tree (build-tree result) ""))

(defn tree-str
  "tree と同じだが、文字列として返す（REPL / AI 向け）。

   例:
     (println (visualize/tree-str (xt/q node '(from :files [*]))))"
  [result]
  (with-out-str (render-tree (build-tree result) "")))

;; -------------------------
;; call-tree
;; -------------------------

(defn- render-call-tree [by-from node depth visited]
  (println (str (apply str (repeat depth "  ")) node))
  (when-not (visited node)
    (doseq [{:keys [to]} (sort-by :to (get by-from node))]
      (render-call-tree by-from to (inc depth) (conj visited node)))))

(defn call-tree
  "refs（[{:from from :to to}] のシーケンス）から root を起点に
   呼び出し木をテキスト表示する（stdout）。循環参照は 1 段で止める。

   例:
     (visualize/call-tree (core/refs) \"workbench.core/ingest!\")"
  [refs root]
  (render-call-tree (group-by :from refs) root 0 #{}))

(defn call-tree-str
  "call-tree と同じだが文字列として返す（REPL / AI 向け）。

   例:
     (println (visualize/call-tree-str (core/refs) \"workbench.core/ingest!\"))"
  [refs root]
  (with-out-str (call-tree refs root)))