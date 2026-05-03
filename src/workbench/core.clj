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
   [workbench.ingest    :as ingest]
   [workbench.jref      :as jref]
   [workbench.query     :as query]
   [workbench.visualize :as visualize]
   [xtdb.node           :as xtn]))

;; -------------------------
;; node lifecycle
;; -------------------------

(defonce ^:private state (atom nil))

(defn start!
  "XTDB ノードを起動する。すでに起動済みなら何もしない。

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
           {:log         [:local {:path (str db-path "/log")}]
            :index-store [:local {:path (str db-path "/index-store")}]}
           {}))))
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
  "refs を GEXF ファイルとして書き出す。Gephi / Cytoscape でインポート可能。

   opts:
     :level - :method（デフォルト）or :class（クラス単位に集約）

   ファイルサイズの目安:
     :method 10万エッジ → 数十 MB
     :class  1万クラス  → 数 MB（Gephi が快適）

   例:
     (export-gexf! (jrefs :trial \"tradehub\") \"tradehub.gexf\")
     (export-gexf! (jrefs :trial \"tradehub\") \"tradehub-class.gexf\" :level :class)"
  [refs path & {:keys [level module-fn] :or {level :method}}]
  (spit path (visualize/gexf refs :level level :module-fn module-fn))
  (println (str "written → " path " ("
                (count (slurp path))
                " bytes)"))
  path)

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
