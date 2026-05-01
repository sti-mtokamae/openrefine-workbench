(ns workbench.visualize
  "クエリ結果をテキスト形式で可視化する関数群。"
  (:require [clojure.string :as str])
  (:import [java.time LocalDate]
           [java.time.format DateTimeFormatter]))

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

(defn- render-call-tree [by-from node depth expanded]
  (let [indent (apply str (repeat depth "  "))
        seen?  (@expanded node)]
    (println (str indent node (when seen? " [...]")))
    (when-not seen?
      (swap! expanded conj node)
      (doseq [{:keys [to]} (sort-by :to (get by-from node))]
        (render-call-tree by-from to (inc depth) expanded)))))

(defn call-tree
  "refs（[{:from from :to to}] のシーケンス）から root を起点に
   呼び出し木をテキスト表示する（stdout）。
   既展開ノードは [...] で示し、再展開しない（DAG 重複・循環参照どちらも抑制）。

   例:
     (visualize/call-tree (core/refs) \"workbench.core/ingest!\")"
  [refs root]
  (render-call-tree (group-by :from refs) root 0 (atom #{})))

(defn call-tree-str
  "call-tree と同じだが文字列として返す（REPL / AI 向け）。

   例:
     (println (visualize/call-tree-str (core/refs) \"workbench.core/ingest!\"))"
  [refs root]
  (with-out-str (call-tree refs root)))

;; -------------------------
;; GEXF export (Gephi)
;; -------------------------

(defn- xml-escape [s]
  (-> s
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn gexf
  "refs を GEXF 1.3 形式の文字列に変換する。Gephi / Cytoscape でインポート可能。

   opts:
     :level - :method（デフォルト、シンボル単位）
              :class（クラス名単位に集約。エッジに weight 付き）

   :class レベルでは同クラス内呼び出しは除外され、
   同クラス間エッジの重複は weight（出現回数）として集計される。

   例:
     ;; メソッド単位でフルグラフ
     (spit \"graph.gexf\" (visualize/gexf (core/jrefs :trial \"tradehub\")))

     ;; クラス単位に集約（Gephi が軽くなる）
     (spit \"graph.gexf\" (visualize/gexf (core/jrefs :trial \"tradehub\") :level :class))"
  [refs & {:keys [level] :or {level :method}}]
  (let [normalize   (if (= level :class)
                      (fn [s] (first (str/split s #"/")))
                      identity)
        ;; [from to] → 出現回数（weight）
        edge-counts (->> refs
                         (map (fn [{:keys [from to]}]
                                [(normalize from) (normalize to)]))
                         (remove (fn [[f t]] (= f t)))
                         frequencies)
        edges       (seq edge-counts)
        ;; ノード一覧（ソート済み）→ ラベル→整数ID マップ
        nodes       (->> edges
                         (mapcat (fn [[[f t] _]] [f t]))
                         (into #{})
                         sort
                         (map-indexed (fn [i n] [n i]))
                         (into {}))
        today       (.format (LocalDate/now) DateTimeFormatter/ISO_LOCAL_DATE)]
    (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
         "<gexf xmlns=\"http://gexf.net/1.3\" version=\"1.3\">\n"
         "  <meta lastmodifieddate=\"" today "\">\n"
         "    <creator>openrefine-workbench</creator>\n"
         "  </meta>\n"
         "  <graph defaultedgetype=\"directed\">\n"
         "    <nodes>\n"
         (str/join
          (map (fn [[label id]]
                 (str "      <node id=\"" id
                      "\" label=\"" (xml-escape label) "\"/>\n"))
               nodes))
         "    </nodes>\n"
         "    <edges>\n"
         (str/join
          (map-indexed (fn [i [[f t] w]]
                         (str "      <edge id=\"" i
                              "\" source=\"" (get nodes f)
                              "\" target=\"" (get nodes t)
                              "\" weight=\"" w "\"/>\n"))
                       edges))
         "    </edges>\n"
         "  </graph>\n"
         "</gexf>\n")))