(ns workbench.core
  "REPL / AI Agent 向けの統合エントリポイント。
   node のライフサイクルを管理し、ingest / query / visualize を一枚の操作面として提供する。

   基本的な使い方:
     (start!)
     (ingest! \"trials/samples/repo\")
     (xref! [\"src\"])
     (tree)
     (q '(from :files [*]))
     (stop!)"
  (:require
   [clojure.string      :as str]
   [workbench.codegen   :as codegen]
   [workbench.ingest    :as ingest]
   [workbench.jacoco    :as jacoco]
   [workbench.jref      :as jref]
   [workbench.query     :as query]   [workbench.sqlref    :as sqlref]   [workbench.visualize :as visualize]
   [xtdb.api            :as xt]
   [xtdb.node           :as xtn]))

;; -------------------------
;; node lifecycle
;; -------------------------

(defonce ^:private state (atom nil))

(defn start!
  "XTDB ノードを起動する。すでに起動済みなら何もしない。
   永続化ログが存在する場合はリプレイ完了を待機する。

   オプション:
     :persist? - true でローカルディレクトリに永続化（デフォルト true）
     :db-path  - 永続化先ディレクトリ（デフォルト \".xtdb\"）

   例:
     (start!)                          ; .xtdb/ に永続化
     (start! {:persist? false})        ; インメモリ（一時利用）
     (start! {:db-path \".xtdb-dev\"})   ; 任意のパスに永続化"
  ([] (start! {}))
  ([{:keys [persist? db-path] :or {persist? true db-path ".xtdb"}}]
   (when-not @state
     (reset! state
       (xtn/start-node
         (if persist?
           {:log     [:local {:path (str db-path "/log")}]
            :storage [:local {:path (str db-path "/storage")}]}
           {})))
     ;; ログが存在する場合はリプレイ完了を待機する
     (let [log-file (java.io.File. (str db-path "/log/LOG"))]
       (when (.exists log-file)
         (print "[start!] replaying log...")
         (flush)
         (xt/execute-tx @state [])  ; 空 tx でリプレイを同期的に完了させる
         (println " done."))))
   :started))

(defn stop!
  "XTDB ノードを停止する。"
  []
  (when-let [node @state]
    (.close node)
    (reset! state nil))
  :stopped)

(defn node
  "現在の XTDB ノードを返す。未起動なら start! を呼ぶこと。"
  []
  (or @state (throw (ex-info "node not started — call (start!) first" {}))))

;; -------------------------
;; ingest
;; -------------------------

(defn ingest!
  "root 以下のファイル・ディレクトリを XTDB :files テーブルに取り込む。

   例:
     (ingest! \"trials/samples/repo\")"
  [root]
  (ingest/dir! (node) root))

(defn xref!
  "Clojure ソースを cross-reference 解析して XTDB :refs テーブルに取り込む。
   投入済みの refs と差分を取って削除＋追加を行う（同期）。

   opts:
     :trial - トライアル識別子（素持ち同期のスコープ）

   例:
     (xref! [\"src\"])
     (xref! [\"src\"] :trial \"aca-spring\")"
  [paths & {:keys [trial]}]
  (ingest/xref! (node) paths :trial trial))

(defn jref!
  "Java ソースを cross-reference 解析して XTDB :refs テーブルに取り込む。
   投入済みの refs と差分を取って削除＋追加を行う（同期）。

   opts:
     :trial - トライアル識別子（素持ち同期のスコープ）

   例:
     (jref! [\"trials/samples/repo\"])
     (jref! [\"src/main/java\"] :trial \"aca-spring\")"
  [paths & {:keys [trial]}]
  (jref/jref! (node) paths :trial trial))

(defn jsig!
  "Java ソースのメソッドシグネチャを解析して XTDB :jsigs テーブルに取り込む（差分同期・冪等）。
   jref! と同じパスを指定すること。

   opts:
     :trial - トライアル識別子

   例:
     (jsig! [\"trials/experiments/2026-04-28-tradehub/repo\"] :trial \"tradehub\")"
  [paths & {:keys [trial]}]
  (jref/jsig! (node) paths :trial trial))

(defn jsigs
  "XTDB :jsigs テーブルからメソッドシグネチャを返す。
   opts:
     :trial  - トライアル識別子でフィルタ
     :class  - クラス名（シンプル名）でフィルタ
     :method - メソッド名でフィルタ

   例:
     (jsigs :trial \"tradehub\" :class \"DocumentAggregateServiceImpl\")"
  [& {:keys [trial cls method]}]
  (jref/jsigs (node) :trial trial :class cls :method method))

(defn sqlref!
  "Java ソースの MyBatis @Select 等アノテーション SQL を解析して
   XTDB :sql-refs テーブルに取り込む。

   opts:
     :trial - トライアル識別子

   例:
     (sqlref! [\"trials/experiments/2026-04-28-tradehub/repo/common-lib\"]
              :trial \"tradehub\")"
  [paths & {:keys [trial]}]
  (sqlref/sqlref! (node) paths :trial trial))

(defn sqlrefs
  "XTDB :sql-refs テーブルから全レコードを返す。
   opts:
     :trial - トライアル識別子でフィルタ

   例:
     (sqlrefs :trial \"tradehub\")"
  [& {:keys [trial]}]
  (sqlref/sqlrefs (node) :trial trial))

(defn jacoco!
  "JaCoCo XML レポートを解析して XTDB :jacoco テーブルに取り込む（差分同期・冪等）。

   xml-path: jacoco.xml のファイルパス（文字列）
   opts:
     :trial - トライアル識別子

   例:
     (jacoco! \"/tmp/jacoco-report/jacoco.xml\" :trial \"tradehub\")"
  [xml-path & {:keys [trial]}]
  (jacoco/jacoco! (node) xml-path :trial trial))

(defn jacocos
  "XTDB :jacoco テーブルから全レコードを返す。
   opts:
     :trial      - トライアル識別子でフィルタ
     :class      - クラス名（シンプル名）でフィルタ
     :uncovered? - true のとき covered=0 のメソッドのみ返す

   例:
     (jacocos :trial \"tradehub\")
     (jacocos :trial \"tradehub\" :uncovered? true)
     (jacocos :trial \"tradehub\" :class \"DocumentAggregateServiceImpl\")"
  [& {:keys [trial class uncovered?]}]
  (jacoco/jacocos (node) :trial trial :class class :uncovered? uncovered?))

(defn coverage
  "クラス名（シンプル名）のカバレッジサマリを返す。
   :covered-methods / :total-methods / :covered-lines / :total-lines を付与。
   opts:
     :trial      - トライアル識別子
     :uncovered? - true のとき covered-methods=0 のクラスのみ返す

   例:
     (coverage :trial \"tradehub\")
     (coverage :trial \"tradehub\" :uncovered? true)"
  [& {:keys [trial uncovered?]}]
  (let [rs      (jacocos :trial trial)
        grouped (group-by :jacoco/class-simple rs)
        rows    (->> grouped
                     (map (fn [[cls methods]]
                            (let [total-m   (count methods)
                                  covered-m (count (filter #(pos? (:jacoco/covered %)) methods))
                                  total-l   (reduce + (map #(+ (:jacoco/covered %) (:jacoco/missed %)) methods))
                                  covered-l (reduce + (map :jacoco/covered methods))]
                              {:class           cls
                               :covered-methods covered-m
                               :total-methods   total-m
                               :covered-lines   covered-l
                               :total-lines     total-l})))
                     (sort-by :covered-lines))]
    (if uncovered?
      (filter #(zero? (:covered-methods %)) rows)
      rows)))

;; -------------------------
;; query
;; -------------------------

(defn q
  "XTQL または SQL でクエリを送る。

   例:
     (q '(from :files [*]))
     (q '(from :refs [*]))"
  [query]
  (query/q (node) query))

(defn refs
  "プロジェクト内部の呼び出しグラフを返す。
   clojure.core / java.* / xtdb.* 等の標準ライブラリ呼び出しと
   <top-level> 宣言ノイズを除外済み。

   例:
     (refs)
     (refs \"workbench.core\")"
  ([]
   (->> (q '(from :refs [{:ref/from from :ref/to to :ref/file file :ref/line line}]
                  (order-by from to)))
        (remove #(re-find #"^(clojure\.|java\.|xtdb\.)" (:to %)))
        (remove #(re-find #"/<top-level>$" (:from %)))))
  ([ns-prefix]
   (->> (refs)
        (filter #(str/starts-with? (:from %) ns-prefix)))))

(defn jrefs
  "Java ソースの呼び出しグラフを返す。
   JUnit/Mockito・Java 標準ライブラリ等のノイズを除外し、
   prefix で始まるクラス呼び出しのみ残す。

   除外されるノイズ:
     - JUnit/Mockito の assert/verify/mock 系
     - Java 標準ライブラリ（Collections. / List. / String. 等）
     - ログ・super・this・単一文字変数（log/ super/ e/ s/ 等のスラッシュ形式）
     - stream chain 式（カッコを含む複合式。例: foo.bar().baz()...）

   opts:
     :trial        - トライアル識別子でフィルタ（文字列）
     :prefix       - 残す :ref/from の先頭文字列（デフォルト nil = 全件）
     :exclude-test - true のとき *Test / *Tests クラスを除外する

   例:
     (jrefs :trial \"tradehub\")
     (jrefs :trial \"tradehub\" :exclude-test true)
     (jrefs :trial \"tradehub\" :prefix \"AclService\")"
  [& {:keys [trial prefix exclude-test]}]
  (let [;; ドット記法ノイズ（Java 標準・テスト・ロガー等）
        dot-noise #"^(when|verify|any|eq|times|never|mock|spy|doReturn|doThrow|assert|assertEquals|assertThat|assertNotNull|assertNull|assertTrue|assertFalse|given|then|willReturn|Arrays\.|Collections\.|List\.|Map\.|Optional\.|String\.|Objects\.|UUID\.|Math\.|System\.|Boolean\.|log\.|super\.|this\.|result\.)"
        ;; スラッシュ形式の定型ノイズ（Java 標準クラス static 呼び出し・ロガー等）
        slash-noise #"^(log|super|this|e|s|r|m|t|entry|row|sheet|result|childBuilder|getUserId|getId|getMessage|getLogger|LOGGER|Logger|UUID|List|Map|Objects|Optional|Collections|Collectors|Arrays|String|Math|Boolean|Integer|Long|Double|LocalDate|LocalDateTime|LocalTime|ZonedDateTime|Calendar|LoggerFactory|StringUtils|HttpStatus|ObjectUtils|Assert|Comparator|Instant|JdbcTemplate|SecurityContextHolder|CellReference|CellRangeAddress|DateUtil|WorkbookFactory)/"
        rs*   (->> (q '(from :refs [{:ref/from from :ref/to to :ref/trial t :ref/file file :ref/line line}]
                              (order-by from to)))
                   (filter #(or (nil? trial) (= trial (:t %))))
                   (remove #(re-find dot-noise (:to %)))
                   (remove #(re-find slash-noise (:to %)))
                   ;; stream chain 式: カッコを含む複合式を除外
                   (remove #(str/includes? (:to %) "("))
                   ;; 未解決のローカル変数参照（スラッシュなし小文字始まり）を除外
                   (remove #(re-matches #"[a-z][a-zA-Z0-9]*" (:to %)))
                   ;; ローカル変数/メソッド呼び出し（小文字始まりのスラッシュ形式: workbook/getSheet 等）を除外
                   (remove #(re-find #"^[a-z][^/]*/" (:to %)))
                   (remove #(= "<top-level>" (:from %))))
        rs    (if exclude-test
                (remove #(re-find #"(Test|Tests)/" (:from %)) rs*)
                rs*)]
    (if prefix
      (filter #(str/starts-with? (:from %) prefix) rs)
      rs)))

(defn topo-sort
  "クラスレベルの依存グラフをトポロジカルソートし、
   「先に切り出せる順（葉から根へ）」のクラス名ベクタを返す。

   スコープ付きメソッド呼び出し（Foo.bar() / foo.bar()）から
   クラス間依存を推定する。シンボルリゾルバなしのため推定精度は限定的。
   循環依存があるクラスは末尾に追記される。

   opts:
     :rs - refs（デフォルト: (jrefs :exclude-test true)）

   例:
     (topo-sort)
     (topo-sort :rs (jrefs :trial \"tradehub\" :exclude-test true))"
  [& {:keys [rs]}]
  (let [rs        (or rs (jrefs :exclude-test true))
        ;; :ref/from のクラス名セット
        known     (->> rs (map :from)
                       (map #(first (str/split % #"/")))
                       set)
        ;; scope → 既知クラス名。大文字始まり=静的呼び出し、小文字始まり=先頭大文字化して照合
        scope->cls (fn [to]
                     (when (str/includes? to ".")
                       (let [scope (first (str/split to #"\."))
                             cap   (if (Character/isUpperCase (.charAt scope 0))
                                     scope
                                     (str (Character/toUpperCase (.charAt scope 0))
                                          (subs scope 1)))]
                         (when (contains? known cap) cap))))
        from-cls  (fn [from] (first (str/split from #"/")))
        edges     (->> rs
                       (keep (fn [{:keys [from to]}]
                               (let [fc (from-cls from)
                                     tc (scope->cls to)]
                                 (when (and tc (not= fc tc))
                                   [fc tc]))))
                       distinct)
        all-cls   (into known (concat (map first edges) (map second edges)))
        in-deg    (reduce (fn [m [_ t]] (update m t (fnil inc 0)))
                          (zipmap all-cls (repeat 0))
                          edges)
        adj       (reduce (fn [m [f t]] (update m f (fnil conj #{}) t))
                          {} edges)]
    ;; Kahn's algorithm（BFS トポロジカルソート）
    (loop [queue  (into clojure.lang.PersistentQueue/EMPTY
                        (keep (fn [[c d]] (when (zero? d) c)) in-deg))
           deg    in-deg
           result []]
      (if (empty? queue)
        ;; 循環依存の残りを末尾に追記
        (vec (concat result (keys (filter (fn [[_ d]] (pos? d)) deg))))
        (let [node (peek queue)
              deps (get adj node #{})]
          (recur (reduce (fn [q t]
                           (let [d (dec (get deg t 0))]
                             (if (zero? d) (conj q t) q)))
                         (pop queue) deps)
                 (reduce (fn [d t] (update d t dec)) deg deps)
                 (conj result node)))))))

;; -------------------------
;; visualize
;; -------------------------

(defn tree
  "files テーブルの全内容をツリー表示する（stdout）。"
  []
  (visualize/tree (q '(from :files [*]))))

(defn tree-str
  "tree と同じだが文字列として返す（AI Agent / テスト向け）。"
  []
  (visualize/tree-str (q '(from :files [*]))))

(defn call-tree
  "refs を起点に呼び出し木をテキスト表示する（stdout）。
   refs は (refs) や (refs ns-prefix) の出力を渡す。

   例:
     (call-tree (refs) \"workbench.core/ingest!\")
     (call-tree (refs \"workbench.core\") \"workbench.core/tree\")"
  [refs root]
  (visualize/call-tree refs root))

(defn call-tree-str
  "call-tree と同じだが文字列として返す（AI Agent / テスト向け）。"
  [refs root]
  (visualize/call-tree-str refs root))

(defn export-gexf!
  "refs を GEXF ファイルとして書き出す。Gephi でインポート可能。

   opts:
     :level - :method（デフォルト）
              :class（クラス単位に集約）
              :both（クラスノード + メソッドノード混在。contains/calls エッジあり）

   例:
     (export-gexf! (jrefs :trial \"tradehub\") \"tradehub.gexf\")
     (export-gexf! (jrefs :trial \"tradehub\") \"tradehub-class.gexf\" :level :class)
     (export-gexf! (jrefs :trial \"tradehub\") \"tradehub-both.gexf\"  :level :both)"
  [refs path & {:keys [level module-fn] :or {level :method}}]
  (spit path (visualize/gexf refs :level level :module-fn module-fn))
  (println (str "written → " path " ("
                (count (slurp path))
                " bytes)"))
  path)

(defn export-graphml!
  "refs を GraphML ファイルとして書き出す。Cytoscape でインポート可能。

   opts:
     :level     - :method（デフォルト）or :class（クラス単位に集約）
     :module-fn - ラベル文字列 → モジュール名を返す関数

   例:
     (export-graphml! (jrefs :trial \"tradehub\") \"tradehub-class.graphml\" :level :class)
     (export-graphml! (jrefs :trial \"tradehub\") \"tradehub-class.graphml\"
                      :level :class :module-fn module-fn)"
  [refs path & {:keys [level module-fn] :or {level :method}}]
  (spit path (visualize/graphml refs :level level :module-fn module-fn))
  (println (str "written → " path " ("
                (count (slurp path))
                " bytes)"))
  path)

(defn export-cytoscape-csv!
  "refs を Cytoscape 向け CSV 2 枚（エッジリスト + ノード属性）として書き出す。
   path は共通プレフィックス。-edges.csv / -nodes.csv が生成される。
   GraphML が読み込めない場合の最終手段。

   Cytoscape での読み込み手順:
     1. File → Import → Network from File → *-edges.csv
        ダイアログで source=source, target=target
     2. File → Import → Table from File → *-nodes.csv
        Key Column = name（ノード名で属性を照合）

   opts:
     :level     - :method（デフォルト）or :class
     :module-fn - ラベル文字列 → モジュール名を返す関数

   例:
     (export-cytoscape-csv! (jrefs :trial \"tradehub\") \"exports/tradehub-class\"
                            :level :class :module-fn module-fn)"
  [refs path-prefix & {:keys [level module-fn] :or {level :method}}]
  (let [{:keys [edges nodes]} (visualize/cytoscape-csvs refs :level level :module-fn module-fn)
        edges-path (str path-prefix "-edges.csv")
        nodes-path (str path-prefix "-nodes.csv")]
    (spit edges-path edges)
    (spit nodes-path nodes)
    (println (str "written → " edges-path " (" (count (slurp edges-path)) " bytes)"))
    (println (str "written → " nodes-path " (" (count (slurp nodes-path)) " bytes)"))
    path-prefix))

;; -------------------------
;; metrics
;; -------------------------

(defn fan-out
  "各シンボルが呼び出している関数の数（依存数）を降順で返す。
   高 fan-out = 多くの関数に依存している（= 変更影響が広い）。

   例:
     (fan-out)
     (fan-out (refs \"workbench.core\"))"
  ([] (fan-out (refs)))
  ([rs]
   (->> rs
        (group-by :from)
        (map (fn [[sym cs]]
               {:symbol sym :count (count (distinct (map :to cs)))}))
        (sort-by :count >))))

(defn fan-in
  "各シンボルへの呼び出し元数（被依存数）を降順で返す。
   高 fan-in = 多くの箇所から呼ばれている（= 重要・変更コスト高）。

   例:
     (fan-in)
     (fan-in (refs \"workbench\"))"
  ([] (fan-in (refs)))
  ([rs]
   (->> rs
        (group-by :to)
        (map (fn [[sym cs]]
               {:symbol sym :count (count (distinct (map :from cs)))}))
        (sort-by :count >))))

(defn hotspots
  "fan-in が高い上位 n シンボルを返す（デフォルト n=10）。
   DB に蓄積した refs 全体が対象。

   例:
     (hotspots)
     (hotspots 5)"
  ([] (hotspots 10))
  ([n] (take n (fan-in))))

;; -------------------------
;; pinpoint analysis
;; -------------------------

(defn impact
  "sym を変更したときに影響を受けるシンボル（上流方向）を返す。
   すなわち sym を直接・間接的に呼び出している呼び出し元を BFS で展開する。
   sym に '/' がない場合（クラス名）は :ref/to に対して
   sym + '/' の前方一致で展開する。

   opts:
     :depth - 探索深さ（デフォルト Integer/MAX_VALUE = 全上流）
     :rs    - 対象 refs（デフォルト (refs)）

   例:
     (impact \"com.example.OrderService/save\")
     (impact \"com.example.OrderService/save\" :depth 2)"
  [sym & {:keys [depth rs] :or {depth Integer/MAX_VALUE}}]
  (let [rs     (or rs (refs))
        ;; to -> #{from ...} の逆引きマップ
        rev    (reduce (fn [m {:keys [from to]}]
                         (update m to (fnil conj #{}) from))
                       {} rs)
        rev-keys (set (keys rev))
        ;; sym がクラス名（'/' なし）の場合は sym + '/' 前方一致で展開
        expand  (fn [s]
                  (if (clojure.string/includes? s "/")
                    (get rev s #{})
                    (let [prefix (str s "/")]
                      (->> rev-keys
                           (filter #(clojure.string/starts-with? % prefix))
                           (mapcat #(get rev % #{}))
                           set))))]
    (loop [frontier #{sym} visited #{sym} d 0]
      (if (or (empty? frontier) (>= d depth))
        (disj visited sym)
        (let [next (->> frontier
                        (mapcat expand)
                        (remove visited)
                        set)]
          (recur next (into visited next) (inc d)))))))

(defn deps
  "sym が依存しているシンボル（下流方向）を返す。
   すなわち sym が直接・間接的に呼び出している呼び出し先を BFS で展開する。
   sym に '/' がない場合（クラス名）は :ref/from に対して
   sym + '/' の前方一致で展開する。

   opts:
     :depth - 探索深さ（デフォルト Integer/MAX_VALUE = 全下流）
     :rs    - 対象 refs（デフォルト (refs)）

   例:
     (deps \"com.example.OrderService/save\")
     (deps \"com.example.OrderService/save\" :depth 2)"
  [sym & {:keys [depth rs] :or {depth Integer/MAX_VALUE}}]
  (let [rs      (or rs (refs))
        ;; from -> #{to ...} の順引きマップ
        forward (reduce (fn [m {:keys [from to]}]
                          (update m from (fnil conj #{}) to))
                        {} rs)
        fwd-keys (set (keys forward))
        ;; sym がクラス名（'/' なし）の場合は sym + '/' 前方一致で展開
        expand  (fn [s]
                  (if (clojure.string/includes? s "/")
                    (get forward s #{})
                    (let [prefix (str s "/")]
                      (->> fwd-keys
                           (filter #(clojure.string/starts-with? % prefix))
                           (mapcat #(get forward % #{}))
                           set))))]
    (loop [frontier #{sym} visited #{sym} d 0]
      (if (or (empty? frontier) (>= d depth))
        (disj visited sym)
        (let [next (->> frontier
                        (mapcat expand)
                        (remove visited)
                        set)]
          (recur next (into visited next) (inc d)))))))

(defn neighborhood
  "sym を中心に上流・下流両方向へ depth ホップ以内のシンボル集合を返す。
   切り出し範囲の推定に使う。

   opts:
     :depth - 探索深さ（デフォルト 2）
     :rs    - 対象 refs（デフォルト (refs)）

   例:
     (neighborhood \"com.example.OrderService\")
     (neighborhood \"com.example.OrderService\" :depth 3)"
  [sym & {:keys [depth rs] :or {depth 2}}]
  (let [rs   (or rs (refs))
        ups  (impact sym :depth depth :rs rs)
        downs (deps   sym :depth depth :rs rs)]
    (into #{sym} (concat ups downs))))

(defn sql-impact
  "SQL 縛りパターンにマッチする Mapper メソッドを起点に
   :refs を逆向きたどって影響を受ける全上流シンボルを返す。

   bind-pat: バインド条件に対してマッチする正規表現
   opts:
     :trial  - トライアル識別子
     :depth  - 上流探索深さ（デフォルト Integer/MAX_VALUE = 全上流）
     :rs     - 対象 refs（デフォルト (jrefs :trial trial :exclude-test true)）
     :side   - col-binds 検索時の対象サイド: :lhs :rhs :any（デフォルト :any）
     :source - 検索対象の bind 種別:
               :col-binds   (デフォルト) JOIN 縛り（table.col = alias.col 形式）
               :param-binds WHERE 絞り込み（col = #{param} 形式。:col に対してマッチ）
               :any         両方

   戻り値: [{:mapper-sym \"...\"  ; マッチした Mapper メソッド
             :col-binds [...]    ; マッチした col-binds（:source に :col-binds が含まれる場合）
             :param-binds [...]  ; マッチした param-binds（:source に :param-binds が含まれる場合）
             :upstream #{...}}]  ; 上流シンボル集合

   例:
     (sql-impact #\"source_process_id\" :trial \"tradehub\")
     (sql-impact #\"process_id\" :trial \"tradehub\" :source :param-binds)
     (sql-impact #\"process_id\" :trial \"tradehub\" :source :any)"
  [bind-pat & {:keys [trial depth rs side source]
               :or   {depth Integer/MAX_VALUE side :any source :col-binds}}]
  (let [rs           (or rs (jrefs :trial trial :exclude-test true))
        sqls         (sqlrefs :trial trial)
        use-col?     (#{:col-binds :any} source)
        use-param?   (#{:param-binds :any} source)
        col-side-fn  (case side
                       :lhs (fn [b] (re-find bind-pat (:lhs b)))
                       :rhs (fn [b] (re-find bind-pat (:rhs b)))
                       (fn [b] (or (re-find bind-pat (:lhs b))
                                   (re-find bind-pat (:rhs b)))))
        param-side-fn (fn [b] (re-find bind-pat (:col b)))
        matchers     (->> sqls
                          (keep (fn [r]
                                  (let [col-hits   (when use-col?
                                                     (filter col-side-fn (:sqlref/col-binds r)))
                                        param-hits (when use-param?
                                                     (filter param-side-fn (:sqlref/param-binds r)))]
                                    (when (or (seq col-hits) (seq param-hits))
                                      (cond-> {:mapper-sym (:sqlref/symbol r)}
                                        (seq col-hits)   (assoc :col-binds col-hits)
                                        (seq param-hits) (assoc :param-binds param-hits)))))))]
    (->> matchers
         (mapv (fn [{:keys [mapper-sym] :as m}]
                 (assoc m :upstream (impact mapper-sym :depth depth :rs rs)))))))

(defn- classify-layer
  "クラス名末尾からレイヤーを推定する。"
  [cls]
  (cond
    (re-find #"Controller$" cls)          :controller
    (re-find #"ServiceImpl$|Service$" cls) :service
    (re-find #"Mapper$|Repository$|Dao$" cls) :mapper
    (re-find #"Aspect$|Interceptor$|Filter$|Handler$" cls) :infra
    :else                                  :other))

(defn sql-impact-report
  "sql-impact の結果に fan-in スコア・レイヤー分類を付与した変更影響レポートを返す。

   各 Mapper エントリ:
     :mapper-sym  - Mapper メソッドのシンボル
     :col-binds   - マッチした col-binds（:source に :col-binds/:any が含まれる場合）
     :param-binds - マッチした param-binds（:source に :param-binds/:any が含まれる場合）
     :upstream    - [{:class :layer :fan-in} ...] fan-in 降順

   サマリ:
     :all-classes - 全 Mapper に渡るユニーク上流クラス集合（fan-in 付き、降順）

   opts: bind-pat, trial, depth, rs, side, source, noise-cls?（除外述語）
   :source の詳細は sql-impact を参照。

   例:
     (sql-impact-report #\"source_process_id\" :trial \"tradehub\")
     (sql-impact-report #\"process_id\" :trial \"tradehub\" :source :any)"
  [bind-pat & {:keys [trial depth rs side source noise-cls?]
               :or   {depth Integer/MAX_VALUE side :any source :col-binds
                      noise-cls? (constantly false)}}]
  (let [rs       (or rs (jrefs :trial trial :exclude-test true))
        fi-map   (->> (fan-in rs)
                      (map (fn [{:keys [symbol count]}]
                             [(first (str/split symbol #"/")) count]))
                      (group-by first)
                      (reduce (fn [m [cls pairs]]
                                (assoc m cls (apply max (map second pairs))))
                              {}))
        cls-only (fn [sym] (first (str/split sym #"/")))
        impacts  (sql-impact bind-pat :trial trial :depth depth :rs rs :side side :source source)
        enrich   (fn [upstream]
                   (->> upstream
                        (map cls-only)
                        (remove noise-cls?)
                        distinct
                        (map (fn [cls]
                               {:class  cls
                                :layer  (classify-layer cls)
                                :fan-in (get fi-map cls 0)}))
                        (sort-by :fan-in >)))
        rows     (mapv (fn [m] (assoc m :upstream (enrich (:upstream m)))) impacts)
        all-cls  (->> rows
                      (mapcat :upstream)
                      (group-by :class)
                      (map (fn [[cls xs]] (first xs)))
                      (sort-by :fan-in >))]
    {:mappers     rows
     :all-classes all-cls}))

(defn sql-impact-report-multi
  "複数の bind-pat を一括処理して変更影響レポートのマップを返す。

   bind-pats: [[label regexp] ...] のベクタ
   opts: sql-impact-report と同じ（:trial :depth :rs :side :source :noise-cls?）

   戻り値: [{:label :bind-pat :mappers :all-classes} ...]

   例:
     (sql-impact-report-multi
       [[\"source_process_id\" #\"source_process_id\"]
        [\"work_process_id\"   #\"work_process_id\"]]
       :trial \"tradehub\")
     (sql-impact-report-multi
       [[\"process_id\" #\"process_id\"]]
       :trial \"tradehub\" :source :any)"
  [bind-pats & opts]
  (mapv (fn [[label pat]]
          (let [r (apply sql-impact-report pat opts)]
            (assoc r :label label :bind-pat pat)))
        bind-pats))

;; -------------------------
;; co-change (git history)
;; -------------------------

(defn cochange!
  "Git 履歴から共変更（co-change）ペアを集計して XTDB :cochanges テーブルに取り込む。
   差分同期を行うので冪等。

   repo-path: git リポジトリルート（文字列）
   opts:
     :trial       - トライアル識別子（文字列）
     :filter-path - git log -- <path> で解析対象を絞り込む

   例:
     (cochange! \"/path/to/repo\")
     (cochange! \"/path/to/tradehub\" :trial \"tradehub\" :filter-path \"src/main/java\")"
  [repo-path & {:keys [trial filter-path]}]
  (ingest/cochange! (node) repo-path :trial trial :filter-path filter-path))

(defn cochanges
  "共変更ペアを count 降順で返す。

   opts:
     :trial     - トライアル識別子でフィルタ
     :top       - 上位 N 件に絞る（デフォルト nil = 全件）
     :min-count - 最小共変更回数（デフォルト 1）

   例:
     (cochanges :trial \"tradehub\" :top 20)
     (cochanges :trial \"tradehub\" :min-count 3)"
  [& {:keys [trial top min-count] :or {min-count 1}}]
  (let [rs (->> (q '(from :cochanges [{:xt/id id :cc/a a :cc/b b :cc/count cnt :cc/trial t}]))
                (filter #(or (nil? trial) (= trial (:t %))))
                (filter #(>= (:cnt %) min-count))
                (sort-by :cnt >))]
    (if top (take top rs) rs)))

(defn sql-cochange-check
  "sql-impact-report の結果と cochange 履歴を照合し、
   影響クラスが実際に Mapper と共変更されているかを検証する。

   各上流クラスに :cochange-cnt（共変更回数、0=履歴なし）を付与する。
   - cnt > 0 : 変更履歴が裏付けている（影響分析と一致）
   - cnt = 0 : 静的解析で検出されたが git 履歴に変更の痕跡なし

   opts:
     :trial     - トライアル識別子
     :min-count - cochange の最小カウント（デフォルト 1）

   例:
     (-> (sql-impact-report #\"source_process_id\" :trial \"tradehub\")
         (sql-cochange-check :trial \"tradehub\"))"
  [{:keys [mappers all-classes] :as report}
   & {:keys [trial min-count] :or {min-count 1}}]
  (let [;; cochange ペアを [ClassName-a ClassName-b] → max-count で索引化
        ;; :cc/a / :cc/b は git log の repo 相対パス ("common-app/.../Foo.java") なので
        ;; ファイル名のステム（= クラス名）で正規化してマッチングする
        path->cls (fn [path]
                    (-> path
                        (str/replace #".*/" "")         ; ディレクトリ除去
                        (str/replace #"\.java$" "")))   ; 拡張子除去
        cc-by-cls (->> (cochanges :trial trial :min-count min-count)
                       (reduce (fn [m {:keys [a b cnt]}]
                                 (let [ca (path->cls a)
                                       cb (path->cls b)]
                                   (-> m
                                       (update [ca cb] (fnil max 0) cnt)
                                       (update [cb ca] (fnil max 0) cnt))))
                               {}))
        cc-cnt    (fn [cls-a cls-b]
                    (or (get cc-by-cls [cls-a cls-b]) 0))
        enrich-upstream
        (fn [mapper-sym upstream]
          (let [mapper-cls (first (str/split mapper-sym #"/"))]
            (mapv (fn [{:keys [class] :as u}]
                    (assoc u :cochange-cnt (cc-cnt mapper-cls class)))
                  upstream)))
        new-mappers
        (mapv (fn [{:keys [mapper-sym upstream] :as m}]
                (assoc m :upstream (enrich-upstream mapper-sym upstream)))
              mappers)
        new-all
        (let [cls->cnt (->> new-mappers
                            (mapcat :upstream)
                            (group-by :class)
                            (map (fn [[cls xs]] [cls (apply max (map #(or (:cochange-cnt %) 0) xs))]))
                            (into {}))]
          (mapv (fn [cls-entry]
                  (assoc cls-entry :cochange-cnt (get cls->cnt (:class cls-entry) 0)))
                all-classes))]
    (assoc report :mappers new-mappers :all-classes new-all)))

;; -------------------------
;; AI テスト生成支援
;; -------------------------

(defn test-context
  "クラス（+メソッド）のテスト生成コンテキストを構築する。
   :refs / :sqlrefs / :jacoco を統合した構造化情報を返す。

   opts:
     :trial  - トライアル識別子
     :method - メソッド名（nil のとき全メソッド）

   例:
     (test-context \"DocumentAggregateServiceImpl\" :trial \"tradehub\")
     (test-context \"DocumentAggregateServiceImpl\" :trial \"tradehub\"
                   :method \"resolveTargetProcessIds\")"
  [class-name & {:keys [trial method]}]
  (let [;; 直接の下流依存（深さ1）— Mock 候補
        rs      (jrefs :trial trial :prefix class-name)
        direct  (if method
                  (->> rs
                       (filter #(= (:from %) (str class-name "/" method)))
                       (map :to) distinct vec)
                  (->> rs (map :to) distinct vec))
        ;; SQL 縛り（直接依存の Mapper メソッドの sqlref を引く）
        all-sqls   (sqlrefs :trial trial)
        direct-set (set direct)
        sqls    (->> all-sqls
                     (filter #(contains? direct-set (:sqlref/symbol %)))
                     (mapv (fn [r]
                             (cond-> {:symbol (:sqlref/symbol r)
                                      :method (:sqlref/method r)}
                               (seq (:sqlref/col-binds r))
                               (assoc :col-binds (:sqlref/col-binds r))
                               (seq (:sqlref/param-binds r))
                               (assoc :param-binds (:sqlref/param-binds r))))))
        ;; JaCoCo カバレッジ
        jac     (jacocos :trial trial :class class-name)
        jac-flt (if method
                  (filter #(= method (:jacoco/method %)) jac)
                  jac)
        cov     (mapv (fn [r]
                        {:method  (:jacoco/method r)
                         :covered (:jacoco/covered r)
                         :missed  (:jacoco/missed r)
                         :line    (:jacoco/line r)})
                      jac-flt)
        ;; メソッドシグネチャ（:jsigs から）
        sigs    (jsigs :trial trial :cls class-name)
        sigs-flt (if method
                   (filter #(= method (:jsig/method %)) sigs)
                   sigs)
        signatures (mapv (fn [s]
                           {:method  (:jsig/method s)
                            :package (:jsig/package s)
                            :params  (:jsig/params s)
                            :return  (:jsig/return s)
                            :throws  (:jsig/throws s)
                            :mods    (:jsig/mods s)})
                         sigs-flt)]
    {:trial       trial
     :class       class-name
     :method      method
     :direct-deps direct
     :sql-refs    sqls
     :coverage    cov
     :signatures  signatures}))

(defn gen-test
  "test-context の情報を AI に渡して JUnit 5 + Mockito テストコードを生成する。

   戻り値: 生成されたテストコードの文字列

   opts:
     :trial  - トライアル識別子
     :method - メソッド名（nil のとき全メソッド対象）
     :model  - 使用モデル (default: \"openai/gpt-4.1\")

   例:
     (gen-test \"DocumentAggregateServiceImpl\" :trial \"tradehub\")
     (gen-test \"DocumentAggregateServiceImpl\" :trial \"tradehub\"
               :method \"resolveTargetProcessIds\")"
  [class-name & {:keys [trial method model]}]
  (let [ctx    (test-context class-name :trial trial :method method)
        target (if method
                 (str class-name "/" method)
                 class-name)
        deps-txt
        (if (seq (:direct-deps ctx))
          (str/join "\n" (map #(str "  - " %) (:direct-deps ctx)))
          "  (なし)")
        sql-txt
        (if (seq (:sql-refs ctx))
          (str/join "\n"
            (map (fn [s]
                   (str "  - " (:symbol s)
                        (when (seq (:col-binds s))
                          (str "\n    col-binds: "
                               (str/join ", " (map #(str (:lhs %) "=" (:rhs %)) (:col-binds s)))))
                        (when (seq (:param-binds s))
                          (str "\n    param-binds: "
                               (str/join ", " (map #(str (:col %) "=#{" (:param %) "}") (:param-binds s)))))))
                 (:sql-refs ctx)))
          "  (なし)")
        cov-txt
        (if (seq (:coverage ctx))
          (str/join "\n"
            (map (fn [c]
                   (str "  - " (:method c)
                        "  [covered=" (:covered c)
                        " missed=" (:missed c) "]"))
                 (:coverage ctx)))
          "  (情報なし)")
        sig-txt
        (if (seq (:signatures ctx))
          (str/join "\n"
            (map (fn [s]
                   (let [params-str (str/join ", "
                                     (map #(str (:type %) " " (:name %))
                                          (:params s)))]
                     (str "  - " (str/join " " (:mods s))
                          " " (:return s)
                          " " (:method s)
                          "(" params-str ")"
                          (when (seq (:throws s))
                            (str " throws " (str/join ", " (:throws s)))))))
                 (:signatures ctx)))
          "  (情報なし)")
        ;; signatures からパッケージ名を取得（最初の1件から）
        pkg     (some :package (:signatures ctx))
        prompt
        (str "以下は Java クラス " target " の静的解析情報です。\n"
             "JUnit 5 + Mockito を使ったユニットテストコードを生成してください。\n\n"
             "## 対象\n" target "\n"
             (when pkg (str "パッケージ: " pkg "\n"))
             "\n"
             "## メソッドシグネチャ\n" sig-txt "\n\n"
             "## 直接依存（Mock 候補）\n" deps-txt "\n\n"
             "## SQL 縛り（MyBatis Mapper 経由）\n" sql-txt "\n\n"
             "## JaCoCo カバレッジ（covered=0 = 完全未テスト）\n" cov-txt "\n\n"
             "## 要件\n"
             "- JUnit 5 (@Test, @ExtendWith(MockitoExtension.class))\n"
             "- Mockito (@Mock, when(...).thenReturn(...))\n"
             "- covered=0 のメソッドを優先してテストする\n"
             "- SQL 縛りがある場合は Mapper の戻り値を Mock で制御するテストを含める\n"
             "- 日本語コメントでテストの意図を説明する")]
    (codegen/chat-complete
     [{:role "system"
       :content "あなたは Java の JUnit 5 + Mockito テストコードを生成する専門家です。与えられた静的解析情報を元に、実用的なテストコードを生成してください。"}
      {:role "user"
       :content prompt}]
     :model (or model "openai/gpt-4.1"))))

;; -------------------------
;; 一括テスト生成支援
;; -------------------------

(defn uncovered-sql-methods
  "未カバー（covered=0）かつ SQL 縛りがあるメソッドを全件洗い出す。

   アルゴリズム:
     1. sqlrefs から Mapper シンボルセットを取得
     2. jrefs から 直接依存マップ（from → #{to}）を構築
     3. jacocos で covered=0 のメソッドを列挙
     4. 直接依存に Mapper シンボルが含まれるものを抽出

   opts:
     :trial - トライアル識別子

   戻り値: [{:class cls :method method :sql-deps [mapper-sym ...]} ...]

   例:
     (uncovered-sql-methods :trial \"tradehub\")"
  [& {:keys [trial]}]
  (let [sql-syms (->> (sqlrefs :trial trial)
                      (map :sqlref/symbol)
                      set)
        dep-map  (->> (jrefs :trial trial :exclude-test true)
                      (reduce (fn [m {:keys [from to]}]
                                (update m from (fnil conj #{}) to))
                              {}))
        uncov    (jacocos :trial trial :uncovered? true)]
    (->> uncov
         (keep (fn [j]
                 (let [cls    (:jacoco/class-simple j)
                       method (:jacoco/method j)
                       sym    (str cls "/" method)
                       deps   (get dep-map sym #{})
                       sqls   (filterv #(contains? sql-syms %) deps)]
                   (when (seq sqls)
                     {:class    cls
                      :method   method
                      :sql-deps sqls}))))
         vec)))

;; -------------------------
;; phase tracking
;; -------------------------

(defn- phase-id [trial-id phase]
  (str trial-id ":" (subs (str phase) 1)))

(defn phase-done?
  "trial-id の phase が XTDB に :done として記録されているか確認する。"
  [trial-id phase]
  (boolean
    (->> (q '(from :trial-phases [{:xt/id id :tphase/status st}]))
         (some #(and (= (phase-id trial-id phase) (:id %))
                     (= :done (:st %)))))))

(defn mark-phase!
  "trial-id の phase の進捗を XTDB に記録する。
   status: :done | :failed | :running"
  [trial-id phase status]
  (xt/execute-tx (node)
    [[:put-docs :trial-phases
      {:xt/id         (phase-id trial-id phase)
       :tphase/trial  trial-id
       :tphase/phase  phase
       :tphase/status status
       :tphase/at     (java.time.Instant/now)}]]))

(defn phase-summary
  "trial-id の全フェーズ進捗一覧を返す。

   例:
     (phase-summary \"tradehub\")"
  [trial-id]
  (->> (q '(from :trial-phases [{:xt/id id :tphase/trial t
                                  :tphase/phase ph :tphase/status st
                                  :tphase/at at}]))
       (filter #(= trial-id (:t %)))
       (map #(select-keys % [:ph :st :at]))
       (sort-by :ph)))

(defn reset-phase!
  "trial-id の phase の進捗を削除して再実行可能にする。

   例:
     (reset-phase! \"tradehub\" :ingest/jref)"
  [trial-id phase]
  (xt/execute-tx (node)
    [[:delete-docs :trial-phases (phase-id trial-id phase)]]))

;; -------------------------
;; bulk test generation
;; -------------------------

(defn gen-tests-uncovered
  "未カバー×SQL縛りのメソッドを全件洗い出し、gen-test でテストコードを生成する。

   各メソッドについて gen-test を呼び出し、結果を返す。
   :out-dir を指定するとクラス名ごとにファイルへ書き出す:
     <out-dir>/<ClassName>/<method>.md

   opts:
     :trial   - トライアル識別子
     :model   - 使用モデル (default: \"openai/gpt-4.1\")
     :out-dir - 出力ディレクトリ（nil のとき書き出しなし）
     :dry-run - true のとき gen-test を呼ばず候補一覧だけ返す

   戻り値:
     :dry-run true  → [{:class :method :sql-deps} ...]
     :dry-run false → [{:class :method :sql-deps :code} ...]

   例:
     (gen-tests-uncovered :trial \"tradehub\" :dry-run true)
     (gen-tests-uncovered :trial \"tradehub\" :out-dir \"/tmp/gen-tests\")
     (gen-tests-uncovered :trial \"tradehub\" :model \"openai/gpt-4o\")"
  [& {:keys [trial model out-dir dry-run] :or {dry-run false}}]
  (let [candidates (uncovered-sql-methods :trial trial)]
    (println (str "[gen-tests-uncovered] 候補: " (count candidates) " メソッド"))
    (if dry-run
      candidates
      (mapv (fn [{:keys [class method sql-deps] :as c}]
              (println (str "  生成中: " class "/" method " ..."))
              (let [code (gen-test class :trial trial :method method :model model)
                    result (assoc c :code code)]
                (when out-dir
                  (let [dir  (str out-dir "/" class)
                        path (str dir "/" method ".md")]
                    (.mkdirs (java.io.File. dir))
                    (spit path (str "# " class "/" method "\n\n```java\n" code "\n```\n"))))
                result))
            candidates))))
