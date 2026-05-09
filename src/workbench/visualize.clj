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

(defn- label->module
  "ClassName or ClassName/method → パッケージ最終セグメントを返す。
   GEXF の :class レベルではラベルがクラス名のみ、:method では Class/method 形式。
   refs の :t（trial）から module を取れないため、ラベル名から推定する。
   analyze.clj 側で module 付き refs を渡すことで精度を上げられる。"
  [label]
  ;; ラベルが \"SomethingServiceImpl\" → そのまま（module 不明時は \"(unknown)\"）
  ;; analyze.clj の export-gexf! が :module キーを渡した場合はそちらを優先する
  "(unknown)")

(defn- gexf-both
  "refs を GEXF 1.3 形式に変換する（:level :both 用）。
   クラスノード（node_level=\"class\"）とメソッドノード（node_level=\"method\"）を
   同一グラフに混在させ、contains エッジ（クラス→メソッド）と
   calls エッジ（メソッド→メソッド）を出力する。"
  [refs module-fn]
  (let [today        (.format (LocalDate/now) DateTimeFormatter/ISO_LOCAL_DATE)
        ;; メソッドレベルの calls エッジ: [from to] → weight
        call-counts  (->> refs
                          (map (fn [{:keys [from to]}] [from to]))
                          (remove (fn [[f t]] (= f t)))
                          frequencies)
        call-edges   (seq call-counts)
        ;; 全メソッドノード（ソート済み）
        all-methods  (->> call-edges
                          (mapcat (fn [[[f t] _]] [f t]))
                          (into #{})
                          sort)
        ;; 全クラスノード（ソート済み）
        all-classes  (->> all-methods
                          (map #(first (str/split % #"/")))
                          (into #{})
                          sort)
        ;; ID マップ: method → "m{i}", class → "c{i}"
        mth-ids      (into {} (map-indexed (fn [i m] [m (str "m" i)]) all-methods))
        cls-ids      (into {} (map-indexed (fn [i c] [c (str "c" i)]) all-classes))
        ;; contains エッジ: class → method (重複なし)
        contains-edges (for [m all-methods
                             :let [c (first (str/split m #"/"))]]
                         [c m])
        ;; in/out degree（calls エッジのみ）
        mth-in-deg   (frequencies (map (fn [[[_ t] _]] t) call-edges))
        mth-out-deg  (frequencies (map (fn [[[f _] _]] f) call-edges))
        ;; クラスの in/out = 配下メソッドの合計
        cls-in-deg   (reduce (fn [acc mth]
                               (update acc (first (str/split mth #"/"))
                                       (fnil + 0) (get mth-in-deg mth 0)))
                             {} all-methods)
        cls-out-deg  (reduce (fn [acc mth]
                               (update acc (first (str/split mth #"/"))
                                       (fnil + 0) (get mth-out-deg mth 0)))
                             {} all-methods)
        max-mth-in   (apply max 1 (vals (merge {::_ 1} mth-in-deg)))
        max-cls-in   (apply max 1 (vals (merge {::_ 1} cls-in-deg)))
        max-mth-out  (apply max 1 (vals (merge {::_ 1} mth-out-deg)))
        max-cls-out  (apply max 1 (vals (merge {::_ 1} cls-out-deg)))
        max-weight   (apply max 1 (vals (merge {::_ 1} call-counts)))
        node-module  (fn [label]
                       (let [cls (first (str/split label #"/"))]
                         (or (when module-fn (module-fn cls)) "(unknown)")))
        node-type    (fn [label]
                       (let [cls (first (str/split label #"/"))]
                         (cond
                           (re-find #"Controller$" cls)          "Controller"
                           (re-find #"ServiceImpl$" cls)         "ServiceImpl"
                           (re-find #"Service$" cls)             "Service"
                           (re-find #"(Mapper|Repository)$" cls) "Mapper"
                           :else                                  "Other")))
        node-shape   (fn [label]
                       (case (node-type label)
                         "Controller" "diamond"
                         "ServiceImpl" "square"
                         "Service"     "square"
                         "Mapper"      "triangle"
                         "disc"))
        size-fn      (fn [d max-d] (+ 4.0 (* 56.0 (/ d max-d))))
        color-fn     (fn [d max-d]
                       (let [t (/ d max-d)
                             r (int (+ 50  (* t 205)))
                             g (int (- 200 (* t 170)))]
                         [r g 50]))]
    (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
         "<gexf xmlns=\"http://gexf.net/1.3\"\n"
         "      xmlns:viz=\"http://gexf.net/1.3/viz\"\n"
         "      version=\"1.3\">\n"
         "  <meta lastmodifieddate=\"" today "\">\n"
         "    <creator>openrefine-workbench</creator>\n"
         "  </meta>\n"
         "  <graph defaultedgetype=\"directed\">\n"
         ;; ノード属性定義（既存 0-5 + 6=node_level）
         "    <attributes class=\"node\">\n"
         "      <attribute id=\"0\" title=\"in_degree\"  type=\"integer\"/>\n"
         "      <attribute id=\"1\" title=\"out_degree\" type=\"integer\"/>\n"
         "      <attribute id=\"2\" title=\"module\"     type=\"string\"/>\n"
         "      <attribute id=\"3\" title=\"type\"       type=\"string\"/>\n"
         "      <attribute id=\"4\" title=\"class\"      type=\"string\"/>\n"
         "      <attribute id=\"5\" title=\"method\"     type=\"string\"/>\n"
         "      <attribute id=\"6\" title=\"node_level\" type=\"string\"/>\n"
         "      <attribute id=\"7\" title=\"name\"       type=\"string\"/>\n"
         "    </attributes>\n"
         ;; エッジ属性定義（edge_type: contains | calls）
         "    <attributes class=\"edge\">\n"
         "      <attribute id=\"0\" title=\"edge_type\"  type=\"string\"/>\n"
         "    </attributes>\n"
         "    <nodes>\n"
         ;; クラスノード
         (str/join
          (map (fn [cls]
                 (let [ind     (get cls-in-deg cls 0)
                       outd    (get cls-out-deg cls 0)
                       sz      (size-fn ind max-cls-in)
                       [r g b] (color-fn outd max-cls-out)
                       mod     (node-module cls)
                       typ     (node-type cls)
                       shp     (node-shape cls)]
                   (str "      <node id=\"" (get cls-ids cls)
                        "\" label=\"" (xml-escape (str cls ".")) "\">\n"
                        "        <attvalues>\n"
                        "          <attvalue for=\"0\" value=\"" ind "\"/>\n"
                        "          <attvalue for=\"1\" value=\"" outd "\"/>\n"
                        "          <attvalue for=\"2\" value=\"" (xml-escape mod) "\"/>\n"
                        "          <attvalue for=\"3\" value=\"" (xml-escape typ) "\"/>\n"
                        "          <attvalue for=\"4\" value=\"" (xml-escape cls) "\"/>\n"
                        "          <attvalue for=\"5\" value=\"\"/>\n"
                        "          <attvalue for=\"6\" value=\"class\"/>\n"
                        "          <attvalue for=\"7\" value=\"" (xml-escape cls) "\"/>\n"
                        "        </attvalues>\n"
                        "        <viz:size value=\"" (format "%.1f" sz) "\"/>\n"
                        "        <viz:color r=\"" r "\" g=\"" g "\" b=\"" b "\"/>\n"
                        "        <viz:shape value=\"" shp "\"/>\n"
                        "      </node>\n")))
               all-classes))
         ;; メソッドノード
         (str/join
          (map (fn [mth]
                 (let [parts   (str/split mth #"/")
                       cls-v   (first parts)
                       mth-v   (if (> (count parts) 1) (second parts) "")
                       ind     (get mth-in-deg mth 0)
                       outd    (get mth-out-deg mth 0)
                       sz      (size-fn ind max-mth-in)
                       [r g b] (color-fn outd max-mth-out)
                       mod     (node-module mth)
                       typ     (node-type mth)
                       shp     (node-shape mth)]
                   (str "      <node id=\"" (get mth-ids mth)
                        "\" label=\"" (xml-escape (str "." mth-v)) "\">\n"
                        "        <attvalues>\n"
                        "          <attvalue for=\"0\" value=\"" ind "\"/>\n"
                        "          <attvalue for=\"1\" value=\"" outd "\"/>\n"
                        "          <attvalue for=\"2\" value=\"" (xml-escape mod) "\"/>\n"
                        "          <attvalue for=\"3\" value=\"" (xml-escape typ) "\"/>\n"
                        "          <attvalue for=\"4\" value=\"" (xml-escape cls-v) "\"/>\n"
                        "          <attvalue for=\"5\" value=\"" (xml-escape mth-v) "\"/>\n"
                        "          <attvalue for=\"6\" value=\"method\"/>\n"
                        "          <attvalue for=\"7\" value=\"" (xml-escape mth) "\"/>\n"
                        "        </attvalues>\n"
                        "        <viz:size value=\"" (format "%.1f" sz) "\"/>\n"
                        "        <viz:color r=\"" r "\" g=\"" g "\" b=\"" b "\"/>\n"
                        "        <viz:shape value=\"" shp "\"/>\n"
                        "      </node>\n")))
               all-methods))
         "    </nodes>\n"
         "    <edges>\n"
         ;; contains エッジ（クラス→メソッド）
         (str/join
          (map-indexed (fn [i [c m]]
                         (str "      <edge id=\"cnt" i
                              "\" source=\"" (get cls-ids c)
                              "\" target=\"" (get mth-ids m) "\">\n"
                              "        <attvalues>\n"
                              "          <attvalue for=\"0\" value=\"contains\"/>\n"
                              "        </attvalues>\n"
                              "      </edge>\n"))
                       contains-edges))
         ;; calls エッジ（メソッド→メソッド）
         (str/join
          (map-indexed (fn [i [[f t] w]]
                         (str "      <edge id=\"call" i
                              "\" source=\"" (get mth-ids f)
                              "\" target=\"" (get mth-ids t)
                              "\" weight=\"" w "\">\n"
                              "        <attvalues>\n"
                              "          <attvalue for=\"0\" value=\"calls\"/>\n"
                              "        </attvalues>\n"
                              "        <viz:thickness value=\""
                              (format "%.1f" (+ 1.0 (* 7.0 (/ w max-weight))))
                              "\"/>\n"
                              "      </edge>\n"))
                       call-edges))
         "    </edges>\n"
         "  </graph>\n"
         "</gexf>\n")))


(defn gexf
  "refs を GEXF 1.3 形式の文字列に変換する。Gephi / Cytoscape でインポート可能。

   opts:
     :level      - :method（デフォルト、シンボル単位）
                   :class（クラス名単位に集約。エッジに weight 付き）
     :module-fn  - ラベル文字列 → モジュール名文字列を返す関数（省略時は :t フィールドを使用）。
                   analyze.clj から #(get label->module-map %) のように渡す。

   :class レベルでは同クラス内呼び出しは除外され、
   同クラス間エッジの重複は weight（出現回数）として集計される。

   Gephi で開くと即座に反映される情報:
     viz:size  = in-degree（依存される数） → コアクラスが大きく表示
     viz:color = out-degree（依存する数） 緑→赤グラデーション → リファクタリング候補が赤く
     viz:thickness = weight（呼び出し回数） → 重要な依存が太く
     attvalues: in_degree / out_degree / module
       → Appearance Ranking・Partition・Data Laboratory で自由に使える

   例:
     ;; クラス単位（モジュール付き）
     (spit \"graph.gexf\"
           (visualize/gexf (core/jrefs :trial \"tradehub\")
                           :level :class
                           :module-fn #(get mod-map %)))"
  [refs & {:keys [level module-fn] :or {level :method}}]
  (if (= level :both)
    (gexf-both refs module-fn)
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
        today       (.format (LocalDate/now) DateTimeFormatter/ISO_LOCAL_DATE)
        ;; in-degree（依存される数）→ ノードサイズ
        in-deg      (frequencies (map (fn [[[_ t] _]] t) edges))
        ;; out-degree（依存する数）→ ノードの色
        out-deg     (frequencies (map (fn [[[f _] _]] f) edges))
        max-in      (apply max 1 (vals (merge {::_ 1} in-deg)))
        max-out     (apply max 1 (vals (merge {::_ 1} out-deg)))
        ;; in-degree → size: 4 〜 60 px
        node-size   (fn [label]
                      (let [d (get in-deg label 0)]
                        (+ 4.0 (* 56.0 (/ d max-in)))))
        ;; out-degree → color: 緑(低) → 赤(高) rgb lerp
        node-color  (fn [label]
                      (let [d  (get out-deg label 0)
                            t  (/ d max-out)
                            r  (int (+ 50  (* t 205)))
                            g  (int (- 200 (* t 170)))
                            b  50]
                        [r g b]))
        ;; モジュール名（module-fn が nil なら "(unknown)"）
        node-module (fn [label]
                      (let [cls (first (str/split label #"/"))]
                        (or (when module-fn (module-fn cls)) "(unknown)")))
        ;; クラス種別 → viz:shape + type 属性
        ;; :method レベルは "ClassName/method" なので先頭クラス部分で判定
        node-type   (fn [label]
                      (let [cls (first (str/split label #"/"))]
                        (cond
                          (re-find #"Controller$" cls)          "Controller"
                          (re-find #"ServiceImpl$" cls)         "ServiceImpl"
                          (re-find #"Service$" cls)             "Service"
                          (re-find #"(Mapper|Repository)$" cls) "Mapper"
                          :else                                  "Other")))
        node-shape  (fn [label]
                      (case (node-type label)
                        "Controller" "diamond"
                        "ServiceImpl" "square"
                        "Service"     "square"
                        "Mapper"      "triangle"
                        "disc"))
        ;; エッジ太さ: weight を 1〜8 px にスケール
        max-weight  (apply max 1 (vals (merge {::_ 1} (into {} (map (fn [[k v]] [k v]) edge-counts)))))
        edge-thick  (fn [w]
                      (+ 1.0 (* 7.0 (/ w max-weight))))]
    (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
         "<gexf xmlns=\"http://gexf.net/1.3\"\n"
         "      xmlns:viz=\"http://gexf.net/1.3/viz\"\n"
         "      version=\"1.3\">\n"
         "  <meta lastmodifieddate=\"" today "\">\n"
         "    <creator>openrefine-workbench</creator>\n"
         "  </meta>\n"
         "  <graph defaultedgetype=\"directed\">\n"
         ;; ノード属性定義
         "    <attributes class=\"node\">\n"
         "      <attribute id=\"0\" title=\"in_degree\"  type=\"integer\"/>\n"
         "      <attribute id=\"1\" title=\"out_degree\" type=\"integer\"/>\n"
         "      <attribute id=\"2\" title=\"module\"     type=\"string\"/>\n"
         "      <attribute id=\"3\" title=\"type\"       type=\"string\"/>\n"
         "      <attribute id=\"4\" title=\"class\"      type=\"string\"/>\n"
         "      <attribute id=\"5\" title=\"method\"     type=\"string\"/>\n"
         "    </attributes>\n"
         "    <nodes>\n"
         (str/join
          (map (fn [[label id]]
                 (let [sz      (node-size label)
                       [r g b] (node-color label)
                       ind     (get in-deg label 0)
                       outd    (get out-deg label 0)
                       mod     (node-module label)
                       typ     (node-type label)
                       shp     (node-shape label)
                       parts   (str/split label #"/")
                       cls-v   (first parts)
                       mth-v   (if (> (count parts) 1) (second parts) "")]
                   (str "      <node id=\"" id
                        "\" label=\"" (xml-escape label) "\">\n"
                        "        <attvalues>\n"
                        "          <attvalue for=\"0\" value=\"" ind "\"/>\n"
                        "          <attvalue for=\"1\" value=\"" outd "\"/>\n"
                        "          <attvalue for=\"2\" value=\"" (xml-escape mod) "\"/>\n"
                        "          <attvalue for=\"3\" value=\"" (xml-escape typ) "\"/>\n"
                        "          <attvalue for=\"4\" value=\"" (xml-escape cls-v) "\"/>\n"
                        "          <attvalue for=\"5\" value=\"" (xml-escape mth-v) "\"/>\n"
                        "        </attvalues>\n"
                        "        <viz:size value=\"" (format "%.1f" sz) "\"/>\n"
                        "        <viz:color r=\"" r "\" g=\"" g "\" b=\"" b "\"/>\n"
                        "        <viz:shape value=\"" shp "\"/>\n"
                        "      </node>\n")))
               nodes))
         "    </nodes>\n"
         "    <edges>\n"
         (str/join
          (map-indexed (fn [i [[f t] w]]
                         (str "      <edge id=\"" i
                              "\" source=\"" (get nodes f)
                              "\" target=\"" (get nodes t)
                              "\" weight=\"" w "\">\n"
                              "        <viz:thickness value=\"" (format "%.1f" (edge-thick w)) "\"/>\n"
                              "      </edge>\n"))
                       edges))
         "    </edges>\n"
         "  </graph>\n"
         "</gexf>\n"))))

;; -------------------------
;; CSV export (Cytoscape: エッジリスト + ノード属性テーブル)
;; -------------------------

(defn cytoscape-csvs
  "refs を Cytoscape 向けの CSV 2 枚に変換する。
   戻り値: {:edges \"...\" :nodes \"...\"}

   Cytoscape での読み込み手順:
     1. File → Import → Network from File → *-edges.csv
        ダイアログで source=source, target=target, interaction=weight に設定
     2. File → Import → Table from File → *-nodes.csv
        ダイアログで Key Column = name（既存ノードと照合）

   opts:
     :level      - :method（デフォルト）or :class
     :module-fn  - ラベル文字列 → モジュール名文字列を返す関数"
  [refs & {:keys [level module-fn] :or {level :method}}]
  (let [normalize   (if (= level :class)
                      (fn [s] (first (str/split s #"/")))
                      identity)
        edge-counts (->> refs
                         (map (fn [{:keys [from to]}]
                                [(normalize from) (normalize to)]))
                         (remove (fn [[f t]] (= f t)))
                         frequencies)
        edges       (seq edge-counts)
        node-labels (->> edges
                         (mapcat (fn [[[f t] _]] [f t]))
                         (into #{})
                         sort)
        in-deg      (frequencies (map (fn [[[_ t] _]] t) edges))
        out-deg     (frequencies (map (fn [[[f _] _]] f) edges))
        node-module (fn [label]
                      (let [cls (first (str/split label #"/"))]
                        (or (when module-fn (module-fn cls)) "(unknown)")))
        node-type   (fn [label]
                      (let [cls (first (str/split label #"/"))]
                        (cond
                          (re-find #"Controller$" cls)          "Controller"
                          (re-find #"ServiceImpl$" cls)         "ServiceImpl"
                          (re-find #"Service$" cls)             "Service"
                          (re-find #"(Mapper|Repository)$" cls) "Mapper"
                          :else                                  "Other")))
        csv-escape  (fn [s]
                      (if (re-find #"[,\"\n]" s)
                        (str "\"" (str/replace s "\"" "\"\"") "\"")
                        s))
        edges-csv   (str "source,target,weight\n"
                         (str/join
                          (map (fn [[[f t] w]]
                                 (str (csv-escape f) "," (csv-escape t) "," w "\n"))
                               edges)))
        nodes-csv   (str "name,in_degree,out_degree,module,type\n"
                         (str/join
                          (map (fn [label]
                                 (str (csv-escape label) ","
                                      (get in-deg label 0) ","
                                      (get out-deg label 0) ","
                                      (csv-escape (node-module label)) ","
                                      (csv-escape (node-type label)) "\n"))
                               node-labels)))]
    {:edges edges-csv :nodes nodes-csv}))



(defn graphml
  "refs を GraphML 形式の文字列に変換する。Cytoscape でインポート可能。

   gexf と同じ opts・ロジックを使うが、XML 形式が異なる。
   viz 拡張なし（Cytoscape では不要）。
   ノード属性は Cytoscape の Node Table に直接マッピングされる。

   opts:
     :level      - :method（デフォルト）or :class
     :module-fn  - ラベル文字列 → モジュール名文字列を返す関数

   Cytoscape での属性利用:
     in_degree / out_degree → Style → Mapping（size / color）
     module / type          → Style → Mapping（fill color partition）
     weight（edge）         → Style → Mapping（width）

   例:
     (spit \"graph.graphml\"
           (visualize/graphml (core/jrefs :trial \"tradehub\")
                              :level :class
                              :module-fn #(get mod-map %)))"
  [refs & {:keys [level module-fn] :or {level :method}}]
  (let [normalize   (if (= level :class)
                      (fn [s] (first (str/split s #"/")))
                      identity)
        edge-counts (->> refs
                         (map (fn [{:keys [from to]}]
                                [(normalize from) (normalize to)]))
                         (remove (fn [[f t]] (= f t)))
                         frequencies)
        edges       (seq edge-counts)
        nodes       (->> edges
                         (mapcat (fn [[[f t] _]] [f t]))
                         (into #{})
                         sort
                         (map-indexed (fn [i n] [n i]))
                         (into {}))
        in-deg      (frequencies (map (fn [[[_ t] _]] t) edges))
        out-deg     (frequencies (map (fn [[[f _] _]] f) edges))
        node-module (fn [label]
                      (let [cls (first (str/split label #"/"))]
                        (or (when module-fn (module-fn cls)) "(unknown)")))
        node-type   (fn [label]
                      (let [cls (first (str/split label #"/"))]
                        (cond
                          (re-find #"Controller$" cls)          "Controller"
                          (re-find #"ServiceImpl$" cls)         "ServiceImpl"
                          (re-find #"Service$" cls)             "Service"
                          (re-find #"(Mapper|Repository)$" cls) "Mapper"
                          :else                                  "Other")))]
    (str "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>\n"
         "<graphml xmlns=\"http://graphml.graphdrawing.org/xmlns\">\n"
         ;; ノード属性キー定義
         ;; attr.name=\"name\" は Cytoscape がノードラベルとして自動認識する
         "  <key id=\"name\"       for=\"node\" attr.name=\"name\"       attr.type=\"string\"/>\n"
         "  <key id=\"class\"      for=\"node\" attr.name=\"class\"      attr.type=\"string\"/>\n"
         "  <key id=\"method\"     for=\"node\" attr.name=\"method\"     attr.type=\"string\"/>\n"
         "  <key id=\"in_degree\"  for=\"node\" attr.name=\"in_degree\"  attr.type=\"int\"/>\n"
         "  <key id=\"out_degree\" for=\"node\" attr.name=\"out_degree\" attr.type=\"int\"/>\n"
         "  <key id=\"module\"     for=\"node\" attr.name=\"module\"     attr.type=\"string\"/>\n"
         "  <key id=\"type\"       for=\"node\" attr.name=\"type\"       attr.type=\"string\"/>\n"
         ;; エッジ属性キー定義
         "  <key id=\"weight\"     for=\"edge\" attr.name=\"weight\"     attr.type=\"int\"/>\n"
         "  <graph id=\"G\" edgedefault=\"directed\">\n"
         ;; ノード
         (str/join
          (map (fn [[label id]]
                 (let [parts (str/split label #"/")
                       cls-v (first parts)
                       mth-v (if (> (count parts) 1) (second parts) "")]
                   (str "    <node id=\"n" id "\">\n"
                        "      <data key=\"name\">"       (xml-escape label) "</data>\n"
                        "      <data key=\"class\">"      (xml-escape cls-v) "</data>\n"
                        "      <data key=\"method\">"     (xml-escape mth-v) "</data>\n"
                        "      <data key=\"in_degree\">"  (get in-deg label 0) "</data>\n"
                        "      <data key=\"out_degree\">" (get out-deg label 0) "</data>\n"
                        "      <data key=\"module\">"     (xml-escape (node-module label)) "</data>\n"
                        "      <data key=\"type\">"       (xml-escape (node-type label)) "</data>\n"
                        "    </node>\n")))
               nodes))
         ;; エッジ
         (str/join
          (map-indexed (fn [i [[f t] w]]
                         (str "    <edge id=\"e" i
                              "\" source=\"n" (get nodes f)
                              "\" target=\"n" (get nodes t) "\">\n"
                              "      <data key=\"weight\">" w "</data>\n"
                              "    </edge>\n"))
                       edges))
         "  </graph>\n"
         "</graphml>\n")))
