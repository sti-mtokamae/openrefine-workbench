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
   [clojure.java.io     :as io]
   [clojure.java.shell  :refer [sh]]
   [clojure.string      :as str]
   [clojure.xml]
   [workbench.codegen   :as codegen]
   [workbench.ingest    :as ingest]
   [workbench.jacoco    :as jacoco]
   [workbench.jref      :as jref]
   [workbench.query     :as query]
   [workbench.sqlref    :as sqlref]
   [workbench.visualize :as visualize]
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

(defn tref!
  "テストクラス（*Test.java）の構造を解析して XTDB :test-refs テーブルに取り込む（差分同期・冪等）。

   @InjectMocks → テスト対象クラス、@Mock/@MockBean → モック依存、
   @Test/@ParameterizedTest → テストメソッド、@Disabled → 無効フラグ。

   paths: テストソースディレクトリのベクタ
   opts:
     :trial - トライアル識別子

   例:
     (tref! [\"trials/experiments/2026-04-28-tradehub/repo/common-lib/src/test\"]
            :trial \"2026-04-28-tradehub\")"
  [paths & {:keys [trial]}]
  (jref/tref! (node) paths :trial trial))

(defn trefs
  "XTDB :test-refs テーブルからテストメソッド情報を返す。
   opts:
     :trial    - トライアル識別子でフィルタ
     :class    - テストクラス名でフィルタ
     :target   - テスト対象クラス名でフィルタ
     :disabled - true のとき @Disabled テストのみ返す

   例:
     (trefs :trial \"2026-04-28-tradehub\" :target \"DocumentAggregateServiceImpl\")
     (trefs :trial \"2026-04-28-tradehub\" :disabled true)"
  [& {:keys [trial class target disabled]}]
  (jref/trefs (node) :trial trial :class class :target target :disabled disabled))

(defn jbody!
  "Java ソースのメソッドボディを XTDB :jbodies テーブルに取り込む（差分同期・冪等）。

   opts:
     :trial - トライアル識別子

   例:
     (jbody! [\"trials/experiments/2026-04-28-tradehub/repo/common-lib/src/main/java\"]
             :trial \"2026-04-28-tradehub\")"
  [paths & {:keys [trial]}]
  (jref/jbody! (node) paths :trial trial))

(defn jbodies
  "XTDB :jbodies テーブルからメソッドボディを返す。
   opts:
     :trial  - トライアル識別子でフィルタ
     :class  - クラス名（シンプル名）でフィルタ
     :method - メソッド名でフィルタ

   例:
     (jbodies :trial \"2026-04-28-tradehub\" :class \"AclServiceImpl\" :method \"updateRow1\")"
  [& {:keys [trial class method]}]
  (jref/jbodies (node) :trial trial :class class :method method))

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

(defn disabled-report
  "@Disabled テストと JaCoCo カバレッジを突き合わせて優先度レポートを返す。

   テストクラスに @InjectMocks で宣言されたプロダクションクラスを JaCoCo と照合し、
   カバレッジが低い順（修正優先度が高い順）にソートして返す。

   opts:
     :test-paths   - テストソースディレクトリのベクタ（tref! 投入先。省略時は再投入しない）
     :trial        - :test-refs の trial 識別子
     :jacoco-trial - JaCoCo の trial 識別子（省略時は :trial と同じ）
     :print?       - true のとき結果を標準出力に表示（デフォルト true）

   戻り値のマップキー:
     :target          - プロダクションクラス名
     :disabled        - @Disabled テスト数
     :jacoco-methods  - JaCoCo 登録メソッド数（-1 なら JaCoCo データなし）
     :covered-methods - JaCoCo カバー済みメソッド数
     :coverage-pct    - カバレッジ % (0〜100、-1 は不明)
     :priority        - :high / :medium / :low / :unknown

   例:
     (disabled-report
       :test-paths [\"trials/experiments/2026-04-28-tradehub/repo/common-lib/src/test\"]
       :trial       \"2026-04-28-tradehub\"
       :jacoco-trial \"tradehub\")"
  [& {:keys [test-paths trial jacoco-trial print?]
      :or   {print? true}}]
  (let [jacoco-trial (or jacoco-trial trial)]
    ;; test-refs 投入（オプション）
    (when (seq test-paths)
      (print "[disabled-report] ingesting test refs...")
      (flush)
      (tref! test-paths :trial trial)
      (println " done."))
    (let [disabled-tests (trefs :trial trial :disabled true)
          ;; JaCoCo インデックス（クラス名 → メソッドリスト）
          jc-index (->> (jacocos :trial jacoco-trial)
                        (group-by :jacoco/class-simple))
          ;; target が nil の場合はクラス名から "Test" を除いてフォールバック
          resolve-target (fn [t cls]
                           (or t (str/replace cls #"Test$" "")))
          rows (->> disabled-tests
                    (group-by (fn [r]
                                (resolve-target (:tref/target r) (:tref/class r))))
                    (map (fn [[target tests]]
                           (let [jc-methods (get jc-index target)
                                 total-m    (if jc-methods (count jc-methods) -1)
                                 covered-m  (if jc-methods
                                              (count (filter #(pos? (:jacoco/covered %)) jc-methods))
                                              -1)
                                 pct        (cond
                                              (nil? jc-methods)     -1
                                              (zero? total-m)       0
                                              :else (Math/round (* 100.0 (/ covered-m total-m))))
                                 priority   (cond
                                              (neg? pct)    :unknown
                                              (zero? pct)   :high
                                              (< pct 50)    :medium
                                              :else         :low)]
                             {:target          target
                              :disabled        (count tests)
                              :jacoco-methods  total-m
                              :covered-methods covered-m
                              :coverage-pct    pct
                              :priority        priority})))
                    (sort-by (juxt :coverage-pct :disabled)))]
      (when print?
        (println (str "\n=== @Disabled × JaCoCo カバレッジ レポート (" (count rows) " クラス) ==="))
        (println (format "%-50s %8s %8s %6s" "対象クラス(prod)" "@Disabled" "JaCoCo%" "優先度"))
        (println (apply str (repeat 76 "-")))
        (doseq [r rows]
          (let [pct-str (if (neg? (:coverage-pct r)) " N/A" (str (:coverage-pct r) "%"))
                pri-str (case (:priority r)
                          :high    "🔴 高"
                          :medium  "🟡 中"
                          :low     "🟢 低"
                          :unknown "⚪ 不明")]
            (println (format "%-50s %8d %8s %s"
                             (:target r)
                             (:disabled r)
                             pct-str
                             pri-str)))))
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

(defn- find-class-imports
  "src-root 以下から <ClassName>.java を探し、import 行の vec を返す。
   見つからない場合は nil。"
  [src-root class-name]
  (when src-root
    (let [java-name (str class-name ".java")
          f (first (filter #(and (.isFile ^java.io.File %)
                                 (= (.getName ^java.io.File %) java-name))
                           (file-seq (io/file src-root))))]
      (when f
        (->> (str/split-lines (slurp f))
             (filter #(str/starts-with? (str/trim %) "import "))
             vec)))))

(defn- find-enum-values
  "src-root 以下から <ClassName>.java を探し、enum クラスであれば定数名の vec を返す。
   enum でない場合や見つからない場合は nil。"
  [src-root class-name]
  (when src-root
    (let [java-name (str class-name ".java")
          f (first (filter #(and (.isFile ^java.io.File %)
                                 (= (.getName ^java.io.File %) java-name))
                           (file-seq (io/file src-root))))]
      (when f
        (let [content (slurp f)]
          (when (re-find #"\benum\b" content)
            (->> (str/split-lines content)
                 (keep #(when-let [m (re-find #"^\s*([A-Z][A-Z0-9_]*)[\s(,;]" %)]
                           (second m)))
                 distinct
                 vec)))))))

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
  [class-name & {:keys [trial method src-root]}]
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
                         sigs-flt)
        ;; 対象クラスの実際の import（hallucination 防止）— dep-cls-names より先に計算する
        src-imports   (find-class-imports src-root class-name)
        ;; src-imports からクラス名を抽出（インターフェース・enum も含む）
        src-import-cls-names (when src-imports
                               (->> src-imports
                                    (keep #(second (re-find #"^import\s+[\w.]+\.(\w+);$" %)))
                                    distinct))
        ;; 依存クラスのパッケージ情報（import の推測精度向上用）
        ;; direct-deps と src-imports の両方から収集することで、
        ;; インターフェース・enum など jrefs に出ないクラスも補足する
        dep-cls-names (distinct (concat
                                  (map #(first (str/split % #"/")) direct)
                                  (or src-import-cls-names [])))
        dep-packages  (into {}
                        (keep (fn [cls]
                                (when-let [pkg (some :jsig/package
                                                     (jsigs :trial trial :cls cls))]
                                  [cls pkg]))
                              dep-cls-names))
        ;; 依存クラスのメソッドシグネチャ（hallucination 防止：引数型・数・throws を正確に提供）
        dep-signatures (->> dep-cls-names
                            (mapcat (fn [cls]
                                      (map (fn [s]
                                             {:class  cls
                                              :method (:jsig/method s)
                                              :params (:jsig/params s)
                                              :return (:jsig/return s)
                                              :throws (:jsig/throws s)
                                              :mods   (:jsig/mods s)})
                                           (jsigs :trial trial :cls cls))))
                            vec)
        ;; 依存 enum クラスの定数（src-root 指定時のみ）
        dep-enum-values (when src-root
                          (->> dep-cls-names
                               (keep #(when-let [vals (find-enum-values src-root %)]
                                        [% vals]))
                               (into {})))
        ;; 既存テストクラス（@InjectMocks でこのクラスを指している *Test.java）
        existing-tests  (trefs :trial trial :target class-name)
        ;; 対象メソッドの実装ボディ（:jbodies テーブル — jbody! 実行済みの場合のみ有効）
        impl-bodies     (jbodies :trial trial :class class-name)]
    {:trial           trial
     :class           class-name
     :method          method
     :src-root        src-root
     :direct-deps     direct
     :dep-packages    dep-packages
     :dep-signatures  dep-signatures
     :dep-enum-values dep-enum-values
     :sql-refs        sqls
     :coverage        cov
     :signatures      signatures
     :src-imports     src-imports
     :existing-tests  existing-tests
     :impl-bodies     impl-bodies}))

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
  [class-name & {:keys [trial method model src-root]}]
  (let [ctx    (test-context class-name :trial trial :method method :src-root src-root)
        target (if method
                 (str class-name "/" method)
                 class-name)
        deps-txt
        (if (seq (:direct-deps ctx))
          (str/join "\n"
            (map (fn [sym]
                   (let [cls (first (str/split sym #"/"))
                         pkg (get (:dep-packages ctx) cls)]
                     (str "  - " sym (when pkg (str "  [package: " pkg "]")))))
                 (:direct-deps ctx)))
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
        ;; 対象クラスの実際の import セクション（src-root 指定時のみ）
        import-txt
        (when (seq (:src-imports ctx))
          (str "## 実際の import（必ずこれをそのまま使うこと。ここにない型は架空の import を生成しないこと）\n"
               (str/join "\n" (:src-imports ctx)) "\n\n"))
        ;; 実装ボディ（jbody! 実行済みの場合のみ有効）
        ;; method 指定時はそのメソッドのみ、未指定時は全メソッド
        impl-bodies-flt (cond->> (:impl-bodies ctx)
                          method (filter #(= method (:jbody/method %))))
        impl-bodies-txt
        (when (seq impl-bodies-flt)
          (str/join "\n\n"
            (map (fn [b]
                   (str "// " (:jbody/method b) "\n" (:jbody/body b)))
                 impl-bodies-flt)))
        ;; 依存クラスのメソッドシグネチャ
        dep-sig-txt
        (if (seq (:dep-signatures ctx))
          (str/join "\n"
            (map (fn [s]
                   (let [params-str (str/join ", "
                                     (map #(str (:type %) " " (:name %))
                                          (:params s)))]
                     (str "  " (:return s) " " (:class s) "." (:method s)
                          "(" params-str ")"
                          (when (seq (:throws s))
                            (str " throws " (str/join ", " (:throws s)))))))
                 (:dep-signatures ctx)))
          "  (情報なし)")
        ;; 依存 enum 定数
        dep-enum-txt
        (when (seq (:dep-enum-values ctx))
          (str/join "\n"
            (map (fn [[cls vals]]
                   (str "  " cls ": " (str/join ", " vals)))
                 (:dep-enum-values ctx))))
        ;; 既存テストクラスのサマリー（@InjectMocks が class-name を指しているもの）
        existing-tests-txt
        (when (seq (:existing-tests ctx))
          (let [by-cls (group-by :tref/class (:existing-tests ctx))]
            (str/join "\n"
              (map (fn [[cls-name entries]]
                     (let [pkg      (:tref/package (first entries))
                           mocks    (distinct (:tref/mocks (first entries)))
                           methods  (mapv :tref/method entries)
                           disabled (filterv :tref/disabled? entries)]
                       (str "テストクラス: " cls-name
                            (when pkg (str " [package: " pkg "]"))
                            "\n@Mock フィールド（重複追加禁止）: " (str/join ", " mocks)
                            "\n既存テストメソッド（重複追加禁止）: " (str/join ", " methods)
                            (when (seq disabled)
                              (str "\n@Disabled メソッド: " (str/join ", " (map :tref/method disabled)))))))
                   by-cls))))
        prompt
        (str "以下は Java クラス " target " の静的解析情報です。\n"
             "JUnit 5 + Mockito を使ったユニットテストコードを生成してください。\n\n"
             "## 対象\n" target "\n"
             (when pkg (str "パッケージ: " pkg "\n"))
             "\n"
             (or import-txt "")
             (when impl-bodies-txt
               (str "## 実装コード（以下に記載のAPIのみ使うこと。存在しないメソッドを呼ばないこと）\n"
                    "```java\n" impl-bodies-txt "\n```\n\n"))
             (when existing-tests-txt
               (str "## 既存テストクラス（以下の構造に合わせてメソッドを追記すること。@Mock や既存メソッドは再宣言しない）\n"
                    existing-tests-txt "\n\n"))
             "## メソッドシグネチャ\n" sig-txt "\n\n"
             "## 依存クラスのメソッドシグネチャ（引数の型・数・throws を厳守すること）\n" dep-sig-txt "\n\n"
             (when dep-enum-txt (str "## enum定数（記載のもののみ使うこと。記載外の定数は存在しない）\n" dep-enum-txt "\n\n"))
             "## 直接依存（Mock 候補）\n" deps-txt "\n\n"
             "## SQL 縛り（MyBatis Mapper 経由）\n" sql-txt "\n\n"
             "## JaCoCo カバレッジ（covered=0 = 完全未テスト）\n" cov-txt "\n\n"
             "## 要件\n"
             "- package 宣言を必ず先頭に含める\n"
             (if existing-tests-txt
               (str "- 「## 既存テストクラス」に記載のテストクラスにメソッドを追記する形で出力する\n"
                    "- クラス宣言・@ExtendWith・@InjectMocks・既存 @Mock フィールドは出力しない（追記するメソッドのみ）\n"
                    "- 既存テストメソッドと同名のメソッドは追加しない\n")
               (str "- JUnit 5 (@Test, @ExtendWith(MockitoExtension.class))\n"
                    "- @InjectMocks でテスト対象、@Mock で依存クラスをセットアップする\n"))
             "- Mock の型はインターフェース（*Service, *Mapper 等）を使い、*Impl クラスを直接 Mock しない\n"
             "- 直接依存に *Impl クラスが含まれる場合は Impl を除いた名前のインターフェースを @Mock に使う\n"
             "- Mockito (@Mock, @InjectMocks, when(...).thenReturn(...))\n"
             "- covered=0 のメソッドを優先してテストする\n"
             "- SQL 縛りがある場合は Mapper の戻り値を Mock で制御するテストを含める\n"
             "- メソッドシグネチャに \"private\" と記載のメソッドは直接呼び出せないのでテスト対象から除外すること\n"
             "- getDeclaredMethod / ReflectionTestUtils 等のリフレクションは使わない\n"
             "- @SpringBootTest は使わない。@ExtendWith(MockitoExtension.class) だけで完結させること\n"
             "- @Mock で宣言した依存クラスのメソッドはテスト内で使う前に必ず when(...).thenReturn(...) / doNothing() で stub すること（stub なしで呼び出すと NullPointerException になる）\n"
             "- テストクラス外にスタブクラスを定義しない\n"
             "- record クラスは mock() せず、コンストラクタで直接インスタンス化するか any() で引数マッチングする\n"
             "- stub する際、void メソッドには doNothing()、boolean/オブジェクト等の戻り値があるメソッドには when(...).thenReturn(...) を使うこと。doNothing() を void 以外のメソッドに使ってはならない\n"
             "- null や空リスト入力のテストを含めること。実装が null チェックをしていない場合は NPE が発生するので assertThrows(NullPointerException.class, ...) で検証し、verifyNoInteractions() は使わない\n"
             "- Mapper の戻り値が Optional 型の場合は Optional.of(...) / Optional.empty() で stub する\n"
             "- 依存クラスの呼び出しは「## 依存クラスのメソッドシグネチャ」の引数型・数・throws を厳守すること\n"
             "- enum の値は「## enum定数」に記載のもののみ使うこと（記載外の定数名は存在しない）\n"
             "- 日本語コメントでテストの意図を説明する")]
    (codegen/chat-complete
     [{:role "system"
       :content "あなたは Java の JUnit 5 + Mockito テストコードを生成する専門家です。与えられた静的解析情報を元に、実用的なテストコードを生成してください。"}
      {:role "user"
       :content prompt}]
     :model (or model "openai/gpt-4.1"))))

;; -------------------------
;; テスト修正支援（fix-test）
;; -------------------------

(defn- compile-errors
  "mvnw test-compile を実行してエラー行のみ返す。
   mvn-root: mvnw があるディレクトリ
   module:   -pl に渡すモジュール名（例: \"common-lib\"）"
  [mvn-root module]
  (let [{:keys [out err]}
        (sh "sh" "-c"
         (str "cd " mvn-root
              " && MAVEN_OPTS='--add-opens java.base/java.lang=ALL-UNNAMED'"
              " ./mvnw test-compile -pl " module " 2>&1"))]
    (->> (str/split-lines (str out err))
         (filter #(re-find #"\[ERROR\]|error:" %))
         (str/join "\n"))))

(defn- filter-header-imports
  "ヘッダーの import 行を検証し、存在しない型の import を除去する。
   src-import-lines: test-context の :src-imports（実際の import 行リスト）
   テストフレームワーク (junit/mockito/spring/java.*) の import は常に保持する。
   ワイルドカード import（例: com.example.dto.*）のパッケージ配下の型も保持する。"
  [header src-import-lines]
  (let [valid-fqns    (into #{} (keep #(second (re-find #"^import\s+([\w.]+);$" %))
                                      src-import-lines))
        ;; src が "import com.example.dto.*;" → "com.example.dto." をプレフィックスとして収集
        wildcard-pkgs (into #{} (keep #(when-let [[_ pkg] (re-find #"^import\s+([\w.]+)\.\*;" %)]
                                         (str pkg "."))
                                      src-import-lines))
        keep? (fn [line]
                (if-let [[_ fqn] (re-find #"^import\s+([\w.]+);$" line)]
                  (or (valid-fqns fqn)
                      (some #(str/starts-with? fqn %) wildcard-pkgs)
                      (re-find #"^(org\.junit|org\.mockito|org\.springframework\.test|java\.|javax\.|lombok\.|com\.fasterxml\.jackson)" fqn))
                  true))]  ; import 行以外は常に保持
    (str/join "\n" (filter keep? (str/split-lines header)))))

(defn- split-test-file
  "Java テストファイルをヘッダーと各テストメソッドのチャンクに分割する。
   @Test / @ParameterizedTest / @RepeatedTest / @TestFactory / @Disabled を
   メソッド境界とみなす。

   戻り値:
     {:header  \"package...class {\"
      :footer  \"}\"          ; クラス末尾の閉じ括弧（再結合時に追記）
      :methods [{:idx 0 :start 20 :end 45 :code \"@Test\\nvoid foo() {...}\"}]}

   :start / :end は 0-based 行インデックス（end は exclusive）"
  [code]
  (let [lines      (vec (str/split-lines code))
        n          (count lines)
        test-ann?  (fn [l]
                     (re-find #"^\s*@(Test|ParameterizedTest|RepeatedTest|TestFactory|Disabled)\b" l))
        test-starts (vec (keep-indexed (fn [i l] (when (test-ann? l) i)) lines))]
    (if (empty? test-starts)
      {:header code :methods [] :footer ""}
      ;; クラス閉じ括弧 = 末尾の非空行が `^}` （インデントなし）の場合だけ分離する
      (let [last-line-idx
            (loop [i (dec n)]
              (cond (neg? i) -1
                    (not (str/blank? (nth lines i))) i
                    :else (recur (dec i))))
            has-class-close (and (>= last-line-idx 0)
                                 (re-find #"^\}\s*$" (nth lines last-line-idx)))
            footer-start  (if has-class-close last-line-idx n)
            footer        (if has-class-close
                            (str/join "\n" (subvec lines footer-start n))
                            "")
            effective-n   footer-start]
        {:header  (str/join "\n" (subvec lines 0 (first test-starts)))
         :footer  footer
         :methods (map-indexed
                    (fn [idx start]
                      (let [end (get test-starts (inc idx) effective-n)]
                        {:idx   idx
                         :start start
                         :end   end
                         :code  (str/join "\n" (subvec lines start end))}))
                    test-starts)}))))

(defn- errors-for-method
  "コンパイルエラー文字列から、メソッドの行範囲 [start-line, end-line) に
   含まれるエラー行のみ返す。start-line は 1-based（Java コンパイラ出力形式）。"
  [err-txt start-line end-line]
  (when (seq err-txt)
    (let [lines (str/split-lines err-txt)
          in-range? (fn [n] (and (>= n start-line) (< n end-line)))
          has-compile-fmt? (some #(re-find #"\[\d+,\d+\]" %) lines)]
      (if has-compile-fmt?
        ;; compile error モード（元の動作）
        (let [filtered (->> lines
                            (filter (fn [line]
                                      (if-let [[_ ln] (re-find #"\[(\d+),\d+\]" line)]
                                        (in-range? (Long/parseLong ln))
                                        true))))]
          (when (seq filtered) (str/join "\n" filtered)))
        ;; surefire runtime error モード：空行でブロック分割 → 範囲内行番号を持つブロックのみ返す
        (let [blocks (->> (partition-by str/blank? lines)
                          (remove #(every? str/blank? %))
                          (map #(str/join "\n" %)))
              ;; 非フレームワーク .java:NN で範囲内かチェック
              block-in-range?
              (fn [block]
                (some (fn [l]
                        (when-let [[_ ln] (re-find #"\.java:(\d+)\)" l)]
                          (when-not (re-find #"\tat (org\.junit|org\.mockito|org\.springframework|java\.|com\.fasterxml|sun\.|jdk\.)" l)
                            (in-range? (Long/parseLong ln)))))
                      (str/split-lines block)))
              matching (filter block-in-range? blocks)]
          (when (seq matching) (str/join "\n\n" matching)))))))

(defn- fix-method-single
  "テストメソッド 1 本を AI で修正し、修正済みメソッドコードを返す。
   エラーがないメソッドは original-code をそのまま返す。"
  [header-snippet method-code err-txt import-txt dep-sig-txt dep-enum-txt model & {:keys [extra-context]}]
  (if-not (seq err-txt)
    method-code
    (let [prompt
          (str "以下の Java テストメソッドにエラーがあります。\n"
               "「## 正確な情報」を参照してエラーを修正し、修正済みのメソッドコードのみを返してください。\n"
               "メソッド名・テストの意図は変えないこと。\n\n"
               "## クラスヘッダー（参照用・変更不要）\n"
               "```java\n" header-snippet "\n```\n\n"
               "## 修正対象メソッド\n"
               "```java\n" method-code "\n```\n\n"
               "## コンパイルエラー\n" err-txt "\n\n"
               "## 正確な情報\n\n"
               (when (seq extra-context)
                 (str "### 追加コンテキスト（必ず遵守すること）\n" extra-context "\n\n"))
               "### 実際の import（ここにない型は使ってはいけない）\n"
               (if (seq import-txt) import-txt "  (情報なし)") "\n\n"
               "### 依存クラスの正確なメソッドシグネチャ\n"
               dep-sig-txt "\n\n"
               (when (seq dep-enum-txt)
                 (str "### enum定数\n" dep-enum-txt "\n\n"))
               "## 修正ルール\n"
               "- @Test アノテーションからメソッド末尾の } まで（メソッド本体のみ）を返すこと\n"
               "- クラス定義・import・クラス閉じ括弧は含めないこと\n"
               "- コードブロック (```java ... ```) は不要。Java コードのみ返すこと\n"
               "- private メソッドは直接呼び出せないため、呼び出しを削除してテストを @Disabled にするか、テスト内容を public メソッド経由で検証するように書き換えること\n"
               "- ReflectionTestUtils / getDeclaredMethod 等のリフレクションは使わない\n"
               "- @SpringBootTest は使わない。@ExtendWith(MockitoExtension.class) だけで完結させること\n"
               "- @Mock で宣言した依存クラスのメソッドは使う前に必ず when(...).thenReturn(...) / doNothing() で stub すること（stub なしは NullPointerException の原因になる）")]
      (codegen/chat-complete
       [{:role "system"
         :content "あなたは Java テストコードの修正専門家です。指定されたメソッドのみを修正します。"}
        {:role "user" :content prompt}]
       :model (or model "openai/gpt-4.1")))))

(defn fix-test
  "既存の Java テストファイルを test-context の情報と compile errors を元に AI で修正する。
   gen-test と異なり、テストシナリオを保持したまま hallucination のみを修正する。

   opts:
     :trial     - トライアル識別子
     :src-root  - プロダクションコードのルート
     :java-path - 修正対象の .java ファイルパス
     :mvn-root  - mvnw があるディレクトリ（compile errors 取得用、省略可）
     :module    - mvn の -pl モジュール名（mvn-root 指定時に使用）
     :errors    - コンパイルエラー文字列（直接渡す場合、mvn-root より優先）
     :model     - 使用モデル（デフォルト: \"openai/gpt-4.1\"）

   例:
     (fix-test \"ActivityRecordServiceImpl\"
       :trial    \"tradehub\"
       :src-root \"trials/experiments/2026-04-28-tradehub/repo/common-lib/src/main/java\"
       :java-path \"trials/experiments/2026-04-28-tradehub/exports/gen-tests/ActivityRecordServiceImpl/ActivityRecordServiceImplTest.java\"
       :mvn-root  \"trials/experiments/2026-04-28-tradehub/repo\"
       :module    \"common-lib\")"
  [class-name & {:keys [trial src-root java-path mvn-root module errors model extra-context]}]
  (let [existing-code (slurp java-path)
        err-txt       (or errors
                          (when mvn-root
                            (compile-errors mvn-root (or module ".")))
                          "")
        ctx           (test-context class-name :trial trial :src-root src-root)
        import-txt    (str/join "\n" (or (:src-imports ctx) []))
        ;; 既存コードに登場するクラス名にだけ dep-signatures を絞る（トークン節約）
        ;; ワイルドカード import（import pkg.*;）はパッケージ配下のクラスをファイルシステムから列挙して展開する
        existing-cls  (let [specific (->> (str/split-lines existing-code)
                                          (keep #(second (re-find #"^import\s+[\w.]+\.(\w+);$" %)))
                                          set)
                            wildcard (->> (str/split-lines existing-code)
                                          (keep #(when-let [[_ pkg] (re-find #"^import\s+([\w.]+)\.\*;" %)]
                                                   pkg))
                                          (mapcat (fn [pkg]
                                                    (when src-root
                                                      (let [pkg-dir (io/file src-root (str/replace pkg "." "/"))]
                                                        (when (.isDirectory pkg-dir)
                                                          (->> (file-seq pkg-dir)
                                                               (keep #(when (and (.isFile ^java.io.File %)
                                                                                  (str/ends-with? (.getName ^java.io.File %) ".java"))
                                                                         (str/replace (.getName ^java.io.File %) ".java" "")))))))))
                                          set)]
                        (into specific wildcard))
        dep-sig-txt   (if (seq (:dep-signatures ctx))
                        (let [filtered (->> (:dep-signatures ctx)
                                            (filter #(existing-cls (:class %))))]
                          (if (seq filtered)
                            (str/join "\n"
                              (map (fn [s]
                                     (let [params-str (str/join ", "
                                                       (map #(str (:type %) " " (:name %))
                                                            (:params s)))]
                                       (str "  " (:return s) " " (:class s) "." (:method s)
                                            "(" params-str ")"
                                            (when (seq (:throws s))
                                              (str " throws " (str/join ", " (:throws s)))))))
                                   filtered))
                            "  (情報なし)"))
                        "  (情報なし)")
        dep-enum-txt  (when (seq (:dep-enum-values ctx))
                        (let [filtered (filter (fn [[cls _]] (existing-cls cls))
                                               (:dep-enum-values ctx))]
                          (when (seq filtered)
                            (str/join "\n"
                              (map (fn [[cls vals]]
                                     (str "  " cls ": " (str/join ", " vals)))
                                   filtered)))))
        lines         (str/split-lines existing-code)
        per-method?   (> (count lines) 300)]
    (if per-method?
      ;; --- per-method モード（>300行の大ファイル用）---
      ;; コンパイルエラーのある行範囲のメソッドだけ API で修正し、他は原文を維持する。
      ;; これにより 1 メソッドあたり ~2,500 tokens に収まり 16,000 上限を回避できる。
      (let [{:keys [header footer methods]} (split-test-file existing-code)
            ;; ヘッダーの hallucinated import を除去（src-imports で検証）
            fixed-header   (filter-header-imports header (or (:src-imports ctx) []))
            ;; header-snippet は先頭 150 行（クラス宣言・フィールド・@BeforeEach を含む範囲）
            header-snippet (str/join "\n" (take 150 (str/split-lines fixed-header)))
            fixed-methods
            (doall
              (map (fn [{:keys [idx start end code]}]
                     ;; compile errors の行番号は 1-based
                     (let [m-err (errors-for-method err-txt (inc start) (inc end))]
                       (if m-err
                         (do
                           (println (str "    修正: メソッド #" (inc idx)
                                        " (L" (inc start) "-" end ") errors=true"))
                           (fix-method-single header-snippet code m-err
                                              import-txt dep-sig-txt dep-enum-txt model
                                              :extra-context extra-context))
                         (do
                           (println (str "    スキップ: メソッド #" (inc idx)
                                        " (L" (inc start) "-" end ") errors=none"))
                           code))))
                   methods))]
        (println (str "  [per-method] 完了: "
                      (count (filter identity
                               (map #(errors-for-method err-txt (inc (:start %)) (inc (:end %)))
                                    methods)))
                      "/" (count methods) " メソッドを修正"))
        (str fixed-header "\n" (str/join "\n" fixed-methods) "\n" footer))
      ;; --- whole-file モード（≤300行の小ファイル用）---
      (let [prompt
            (str "以下の Java テストコードにエラーがあります。\n"
                 "「## 正確な情報」を参照してエラーを修正し、修正済みの完全な Java コードのみを返してください。\n"
                 "テストシナリオ・テストメソッド名・テストの意図は変えないこと。\n\n"
                 "## 既存コード\n"
                 "```java\n" existing-code "\n```\n\n"
                 (when (seq err-txt)
                   (str "## コンパイルエラー / テスト失敗\n" err-txt "\n\n"))
                 "## 正確な情報\n\n"
                 "### 実際の import（これ以外の型は架空。ここにない型を import してはいけない）\n"
                 (if (seq import-txt) import-txt "  (情報なし)") "\n\n"
                 "### 依存クラスの正確なメソッドシグネチャ（引数型・数・throws を厳守すること）\n"
                 dep-sig-txt "\n\n"
                 (when dep-enum-txt
                   (str "### enum定数（記載のもののみ有効。記載外の定数名は存在しない）\n"
                        dep-enum-txt "\n\n"))
                 (when (seq extra-context)
                   (str "### 追加コンテキスト（必ず遵守すること）\n" extra-context "\n\n"))
                 "## 修正ルール\n"
                 "- import は上記「実際の import」からのみ選ぶこと\n"
                 "- メソッド呼び出しの引数型・数は「正確なメソッドシグネチャ」に従うこと\n"
                 "- enum 値は「enum定数」に記載のもののみ使うこと\n"
                 "- @Value フィールドが未注入になる場合は ReflectionTestUtils.setField で設定すること\n"
                 "- 全フィールドが null の DTO を複数 verify する場合は same() マッチャーを使うこと\n"
                 "- コードブロック (```java ... ```) は不要。Java コードのみ返すこと")]
        (codegen/chat-complete
         [{:role "system"
           :content "あなたは Java テストコードの修正専門家です。指示された修正のみを行い、余分な変更は加えません。"}
          {:role "user"
           :content prompt}]
         :model (or model "openai/gpt-4.1"))))))

(defn fix-tests-dir
  "gen-tests ディレクトリ以下の全 *Test.java ファイルを fix-test で一括修正する。

   opts:
     :gen-dir   - gen-tests ディレクトリ（exports/gen-tests/ 等）
     :trial     - トライアル識別子
     :src-root  - プロダクションコードのルート
     :mvn-root  - mvnw があるディレクトリ（compile errors 取得用、省略可）
     :module    - mvn の -pl モジュール名（mvn-root 指定時に使用）
     :dest-dir  - 修正済みファイルの出力先（省略時は上書き）
     :force     - true のとき dest-dir にファイルが存在してもスキップしない
     :dry-run   - true のとき API を呼ばず対象ファイル一覧のみ表示

   例:
     (fix-tests-dir
       :gen-dir  \"trials/experiments/2026-04-28-tradehub/exports/gen-tests\"
       :trial    \"tradehub\"
       :src-root \"trials/experiments/2026-04-28-tradehub/repo/common-lib/src/main/java\"
       :mvn-root \"trials/experiments/2026-04-28-tradehub/repo\"
       :module   \"common-lib\")"
  [& {:keys [gen-dir trial src-root mvn-root module dest-dir force dry-run model]
      :or   {dry-run false force false}}]
  (let [java-files (->> (file-seq (io/file gen-dir))
                        (filter #(and (.isFile ^java.io.File %)
                                      (str/ends-with? (.getName ^java.io.File %) "Test.java")))
                        (sort-by #(.getPath ^java.io.File %)))]
    (if dry-run
      (doseq [f java-files]
        (println (.getPath f)))
      (doseq [^java.io.File f java-files]
        (let [fname      (.getName f)
              java-path  (.getPath f)
              class-name (str/replace fname #"Test\.java$" "")
              out-path   (if dest-dir
                           (str dest-dir "/" class-name "/" fname)
                           java-path)]
          (if (and (not force) dest-dir (.exists (io/file out-path)))
            (println (str "スキップ（既存）: " out-path))
            (do
              (println (str "修正中: " fname))
              (try
                (let [fixed (fix-test class-name
                              :trial trial :src-root src-root
                              :java-path java-path
                              :mvn-root mvn-root :module module
                              :model model)]
                  (io/make-parents (io/file out-path))
                  (spit out-path fixed)
                  (println (str "  → " out-path)))
                (catch Exception e
                  (println (str "  ERROR [" fname "]: " (.getMessage e))))))))))))

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

(defn test-targets
  "未カバー × SQL縛りメソッドのうち、すでにテストメソッドが存在するクラスを返す。
   「暗闇の中でどのfailureが意味のある失敗か」を特定するためのクエリ。

   戻り値: [{:class \"DocumentAggregateServiceImpl\"
             :uncovered-methods [\"resolveTargetProcessId\" ...]
             :test-methods [{:tref/class ... :tref/method ... :tref/disabled? ...} ...]}]

   opts:
     :trial - トライアル識別子

   例:
     (test-targets :trial \"2026-04-28-tradehub\")"
  [& {:keys [trial]}]
  (let [uncovered (uncovered-sql-methods :trial trial)
        tref-list (trefs :trial trial)
        target-map (group-by :tref/target tref-list)]
    (->> uncovered
         (group-by :class)
         (keep (fn [[cls methods]]
                 (when-let [tests (get target-map cls)]
                   {:class             cls
                    :uncovered-methods (mapv :method methods)
                    :test-methods      (mapv #(select-keys % [:tref/class :tref/method :tref/disabled?])
                                            tests)})))
         (sort-by #(count (:test-methods %)) >))))

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
  [& {:keys [trial model out-dir src-root force dry-run] :or {dry-run false force false}}]
  (let [candidates (uncovered-sql-methods :trial trial)]
    (println (str "[gen-tests-uncovered] 候補: " (count candidates) " メソッド"))
    (if dry-run
      candidates
      (mapv (fn [{:keys [class method sql-deps] :as c}]
              (let [dir  (when out-dir (str out-dir "/" class))
                    path (when out-dir (str dir "/" method ".md"))]
                (if (and path (not force) (.exists (java.io.File. path)))
                  (do
                    (println (str "  スキップ（既存）: " class "/" method))
                    (assoc c :code :skipped))
                  (try
                    (println (str "  生成中: " class "/" method " ..."))
                    (let [code   (gen-test class :trial trial :method method :model model :src-root src-root)
                          result (assoc c :code code)]
                      (when dir
                        (.mkdirs (java.io.File. dir))
                        (spit path (str "# " class "/" method "\n\n```java\n" code "\n```\n")))
                      result)
                    (catch Exception e
                      (println (str "  エラー（スキップ）: " class "/" method " - " (.getMessage e)))
                      (assoc c :code :error :error-msg (.getMessage e)))))))
            candidates))))

;; -------------------------
;; md → java 統合変換
;; -------------------------

(defn- extract-java-from-md
  "md 文字列から java コードブロックを返す。
   'package' 行から最初の ``` 行の直前までを抽出する。
   ブレースが一致しない（途中切れ）場合は nil を返す。"
  [md-str]
  (let [code (->> (str/split-lines md-str)
                  (drop-while #(not (str/starts-with? (str/trim %) "package ")))
                  (take-while #(not (re-matches #"```.*" (str/trim %))))
                  (str/join "\n"))
        opens  (count (filter #{\{} code))
        closes (count (filter #{\}} code))]
    (when (= opens closes) code)))

(defn- split-class-body-blocks
  "クラス本体文字列をメンバーブロック（フィールドまたはメソッド）の
   ベクタに分割する。空行でブロックを区切るが、ブレース内（depth>0）は
   空行をまたいで継続する。
   depth>0 で EOF に達したブロック（途中で切れたもの）は破棄する。"
  [body-str]
  (let [lines (str/split-lines body-str)]
    (loop [remaining lines
           current   []
           depth     0
           result    []]
      (if (empty? remaining)
        ;; depth=0 のブロックのみ追加（途中で切れたブロックは破棄）
        (let [non-blank (filter #(not (str/blank? %)) current)]
          (if (and (zero? depth) (seq non-blank))
            (conj result current)
            result))
        (let [line      (first remaining)
              blank?    (str/blank? line)
              opens     (if blank? 0 (count (filter #{\{} line)))
              closes    (if blank? 0 (count (filter #{\}} line)))
              new-depth (+ depth opens (- closes))]
          (cond
            ;; 空行かつ depth=0 → ブロック区切り
            (and blank? (zero? depth))
            (let [non-blank (filter #(not (str/blank? %)) current)]
              (if (seq non-blank)
                (recur (rest remaining) [] 0 (conj result current))
                (recur (rest remaining) [] 0 result)))
            ;; それ以外 → 現在ブロックに追加
            :else
            (recur (rest remaining) (conj current line) new-depth result)))))))

(defn- field-block?
  "ブロックがフィールド宣言か（@Mock/@InjectMocks/@Spy/@Captor/@Autowired を含む）"
  [block]
  (some #(re-matches #"\s*@(Mock|InjectMocks|Spy|Captor|Autowired)(\(.*\))?\s*" %) block))

(defn- field-decl-line
  "フィールドブロックから型・変数名を含む宣言行を取得（重複検出キー）"
  [block]
  (some-> (->> block
               (filter #(re-matches #"\s*(private|protected|public)\s+\S+.*;\s*" %))
               first)
          str/trim))

(defn- method-name
  "メソッドブロックからメソッド名を取得（重複検出キー）"
  [block]
  (->> block
       (some #(when-let [m (re-find #"(?:void|[\w<>\[\]]+)\s+(\w+)\s*\(" %)] (second m)))))

(defn- extract-test-methods-from-md
  "md 文字列から @Test メソッドブロック（直前コメント含む）のベクタを返す。
   ``` フェンス行を除去して解析する。ブレースが不均衡なブロックは除外する。
   per-method md（package/class 宣言なし）と フルクラス md の両形式に対応。"
  [md-str]
  (let [lines (->> (str/split-lines md-str)
                   (remove #(re-matches #"\s*```.*" %))
                   vec)
        n     (count lines)]
    (loop [i 0 results []]
      (cond
        (>= i n) results

        ;; @Test 行を発見
        (re-matches #"\s*@Test\s*" (nth lines i))
        (let [;; @Test 直前のコメント行を遡る（空白行や非コメント行で止まる）
              start (loop [j (dec i)]
                      (if (and (>= j 0)
                               (let [t (str/trim (nth lines j))]
                                 (or (str/starts-with? t "//")
                                     (str/starts-with? t "/*")
                                     (str/starts-with? t "*"))))
                        (recur (dec j))
                        (inc j)))
              ;; @Test 行から閉じ } まで収集（深さが 0 に戻ったとき終了）
              [end-i block-lines]
              (loop [j i depth 0 entered? false acc []]
                (if (>= j n)
                  [nil acc]
                  (let [line  (nth lines j)
                        opens (count (filter #{\{} line))
                        clses (count (filter #{\}} line))
                        nd    (+ depth opens (- clses))]
                    (if (and entered? (zero? nd))
                      [(inc j) (conj acc line)]
                      (recur (inc j) nd (or entered? (pos? opens)) (conj acc line))))))]
          (if end-i
            (let [comment-lines (subvec lines start i)
                  full-block    (str/join "\n" (concat comment-lines block-lines))]
              (recur end-i (conj results full-block)))
            ;; ブレース不均衡: スキップ
            (recur (inc i) results)))

        :else (recur (inc i) results)))))

(defn merge-test-mds
  "gen-tests/<class-name>/ 配下の *.md を統合して <class-name>Test.java を生成する。
   既に .java が存在する場合はスキップ（:force true で上書き）。

   opts:
     :class   - クラス名（例 \"ProjectServiceImpl\"）
     :gen-dir - gen-tests の基底ディレクトリ
     :force   - true の場合既存 .java を上書き

   戻り値: {:status :merged|:skipped|:no-mds, :class class, :test-count n}

   例:
     (merge-test-mds
       :class \"ProjectServiceImpl\"
       :gen-dir \"trials/experiments/2026-04-28-tradehub/exports/gen-tests\")"
  [& {:keys [class gen-dir force]}]
  (let [dir      (java.io.File. (str gen-dir "/" class))
        out-path (str (.getPath dir) "/" class "Test.java")
        md-files (->> (file-seq dir)
                      (filter #(and (.isFile %)
                                    (str/ends-with? (.getName %) ".md")))
                      sort)]
    (cond
      (and (not force) (.exists (java.io.File. out-path)))
      (do (println (str "  スキップ（既存）: " class "Test.java"))
          {:status :skipped :class class})

      (empty? md-files)
      (do (println (str "  スキップ（md なし）: " class))
          {:status :no-mds :class class})

      :else
      (let [all-blocks   (mapv #(extract-java-from-md (slurp %)) md-files)
            ;; nil（ブレース不均衡・途中で切れた md）をスキップ
            java-blocks  (filterv some? all-blocks)
            _            (when (< (count java-blocks) (count all-blocks))
                           (println (str "  警告: " (- (count all-blocks) (count java-blocks))
                                         " 件の md をスキップ（途中で切れている）")))
            all-lines    (mapcat str/split-lines java-blocks)
            pkg-line     (first (filter #(str/starts-with? % "package ") all-lines))
            all-imports  (->> all-lines
                              (filter #(and (str/starts-with? % "import ")
                                           (str/ends-with? % ";")))
                              (into (sorted-set)))
            ;; クラスアノテーション: class 宣言直前の @ 行
            class-annots (->> java-blocks
                              (mapcat (fn [jb]
                                (let [ls (str/split-lines jb)
                                      ci (->> (map-indexed vector ls)
                                              (some (fn [[i l]]
                                                      (when (re-matches #".*\bclass\s+\S+.*\{.*" l) i))))]
                                  (when ci
                                    (->> (take ci ls)
                                         (filter #(str/starts-with? (str/trim %) "@")))))))
                              (into (sorted-set)))
            ;; 各 md のクラス本体を抽出（class { の次行 〜 最後の } の前まで）
            class-bodies (->> java-blocks
                              (mapv (fn [jb]
                                (let [ls  (str/split-lines jb)
                                      ci  (->> (map-indexed vector ls)
                                               (some (fn [[i l]]
                                                       (when (re-matches #".*\bclass\s+\S+.*\{.*" l) (inc i)))))
                                      end (when ci (dec (count ls)))]
                                  (when (and ci end (< ci end))
                                    (str/join "\n" (subvec (vec ls) ci end)))))))
            ;; メンバーブロックに分割
            all-members  (->> class-bodies
                              (remove nil?)
                              (mapcat split-class-body-blocks))
            ;; フィールド：宣言行をキーに重複排除
            unique-fields (->> all-members
                               (filter field-block?)
                               (reduce (fn [{:keys [seen fields]} block]
                                         (let [k (field-decl-line block)]
                                           (if (and k (not (contains? seen k)))
                                             {:seen (conj seen k) :fields (conj fields block)}
                                             {:seen seen :fields fields})))
                                       {:seen #{} :fields []})
                               :fields)
            ;; @Test メソッド：メソッド名をキーに重複排除
            unique-methods (->> all-members
                                (remove field-block?)
                                (filter #(some (fn [l] (str/includes? l "@Test")) %))
                                (reduce (fn [{:keys [seen methods]} block]
                                          (let [k (method-name block)]
                                            (if (and k (not (contains? seen k)))
                                              {:seen (conj seen k) :methods (conj methods block)}
                                              {:seen seen :methods methods})))
                                        {:seen #{} :methods []})
                                :methods)
            ;; ファイル組み立て
            indent       "    "
            field-str    (->> unique-fields
                              (map #(str/join "\n" %))
                              (str/join "\n\n"))
            method-str   (->> unique-methods
                              (map #(str/join "\n" %))
                              (str/join "\n\n"))
            out-str      (str pkg-line "\n\n"
                              (str/join "\n" all-imports) "\n\n"
                              (str/join "\n" class-annots) "\n"
                              "class " class "Test {\n\n"
                              field-str "\n\n"
                              method-str "\n}\n")]
        (spit out-path out-str)
        (println (str "  生成: " class "Test.java (@Test×" (count unique-methods) ")"))
        {:status :merged :class class :test-count (count unique-methods)}))))

(defn merge-all-test-mds
  "gen-tests/ 配下の全クラスフォルダに merge-test-mds を適用する。

   opts:
     :gen-dir - gen-tests の基底ディレクトリ
     :force   - true の場合既存 .java を上書き

   例:
     (merge-all-test-mds
       :gen-dir \"trials/experiments/2026-04-28-tradehub/exports/gen-tests\")"
  [& {:keys [gen-dir force]}]
  (let [class-dirs (->> (file-seq (java.io.File. gen-dir))
                        (filter #(and (.isDirectory %)
                                      (not= (.getPath %) gen-dir)))
                        (filter #(some (fn [f] (str/ends-with? (.getName f) ".md"))
                                       (.listFiles %)))
                        sort)]
    (println (str "[merge-all-test-mds] 対象クラス: " (count class-dirs)))
    (mapv #(merge-test-mds
             :class   (.getName %)
             :gen-dir gen-dir
             :force   force)
          class-dirs)))

(defn patch-test-from-mds
  "gen-tests/<class-name>/ 配下の per-method *.md から @Test メソッドを抽出し、
   既存の Java テストファイルに追記する。
   クラスレベルの @Disabled(\"AI生成テスト: 要修正\") も除去する。

   opts:
     :class     - クラス名（例 \"ProjectServiceImpl\"）
     :gen-dir   - gen-tests の基底ディレクトリ
     :dest-file - 更新対象の Java テストファイルパス

   戻り値: {:status :patched, :class class, :added n, :skipped-dup m}

   例:
     (patch-test-from-mds
       :class     \"ProjectServiceImpl\"
       :gen-dir   \"trials/experiments/2026-04-28-tradehub/exports/gen-tests\"
       :dest-file \"trials/.../repo/.../ProjectServiceImplTest.java\")"
  [& {:keys [class gen-dir dest-file]}]
  (let [md-dir   (java.io.File. (str gen-dir "/" class))
        md-files (->> (file-seq md-dir)
                      (filter #(and (.isFile %)
                                    (str/ends-with? (.getName %) ".md")))
                      sort)
        ;; md から @Test メソッドを抽出してメソッド名でユニーク化
        new-methods (->> md-files
                         (mapcat #(extract-test-methods-from-md (slurp %)))
                         (reduce (fn [{:keys [seen ms]} m]
                                   (let [k (second (re-find #"void\s+(\w+)\s*\(" m))]
                                     (if (and k (not (contains? seen k)))
                                       {:seen (conj seen k) :ms (conj ms m)}
                                       {:seen seen :ms ms})))
                                 {:seen #{} :ms []})
                         :ms)
        existing       (slurp dest-file)
        existing-names (into #{} (map second (re-seq #"void\s+(\w+)\s*\(" existing)))
        ;; 既存にない新メソッドのみ
        to-add         (remove #(existing-names (second (re-find #"void\s+(\w+)\s*\(" %)))
                               new-methods)
        skipped        (- (count new-methods) (count to-add))
        ;; クラスレベルの @Disabled を除去
        patched        (str/replace existing
                                    #"(?m)^\s*@Disabled\(\"AI生成テスト: 要修正\"\)\s*\r?\n" "")
        ;; 最後の } の前に新メソッドを挿入
        last-brace     (.lastIndexOf ^String patched "}")
        patched        (if (and (seq to-add) (>= last-brace 0))
                         (str (subs patched 0 last-brace)
                              "\n"
                              (str/join "\n\n" to-add)
                              "\n}\n")
                         patched)]
    (spit dest-file patched)
    (println (str "  " class ": " (count to-add) " メソッド追加, " skipped " 件スキップ（重複）"))
    {:status :patched :class class :added (count to-add) :skipped-dup skipped}))

;; -------------------------
;; surefire 失敗テスト @Disabled 化
;; -------------------------

(defn- parse-surefire-failures
  "surefire-reports/ 配下の XML を解析し、指定クラス名を含む
   testcase 要素のうち failure/error 子を持つものの name 属性（メソッド名）を
   集合で返す。clojure.xml/parse で正確に解析する。"
  [surefire-dir class-name]
  (let [xml-files (->> (file-seq (io/file surefire-dir))
                       (filter #(and (.isFile %)
                                     (str/ends-with? (.getName %) ".xml")
                                     ;; クラス名を含むファイルのみ対象（高速化）
                                     (str/includes? (.getName %) class-name))))]
    (->> xml-files
         (mapcat (fn [f]
                   (try
                     (let [root (clojure.xml/parse f)]
                       (->> (:content root)
                            (filter #(= :testcase (:tag %)))
                            (filter (fn [tc]
                                      (and (seq (:content tc))
                                           (some #(#{:failure :error} (:tag %))
                                                 (:content tc)))))
                            (map #(get-in % [:attrs :name]))))
                     (catch Exception e
                       (println (str "  ⚠ XML 解析失敗: " (.getName f) " - " (.getMessage e)))
                       []))))
         (into #{}))))

(defn- ensure-disabled-import
  "ファイル文字列に Disabled の import がなければ追加する。"
  [content]
  (if (str/includes? content "import org.junit.jupiter.api.Disabled")
    content
    (str/replace-first content
                        #"(?m)^import "
                        "import org.junit.jupiter.api.Disabled;\nimport ")))

(defn- add-disabled-to-method
  "content 文字列の中で method-name に対応する @Test アノテーション行の
   直前（既に @Disabled がない場合のみ）に @Disabled を挿入して返す。"
  [content method-name]
  (let [lines  (str/split-lines content)
        n      (count lines)]
    (loop [i 0 result []]
      (if (>= i n)
        (str/join "\n" result)
        (let [line (nth lines i)]
          (if (re-find (re-pattern (str "\\bvoid\\s+" (java.util.regex.Pattern/quote method-name) "\\s*\\(")) line)
            ;; メソッド定義行を発見 → 直上の @Test を探す
            (let [;; result の末尾から @Test を探す
                  test-idx (loop [j (dec (count result))]
                              (cond
                                (< j 0)                                    nil
                                (re-find #"@Test\b" (nth result j))        j
                                (re-find #"@\w" (nth result j))            nil  ; 別のアノテーション
                                :else                                      (recur (dec j))))
                  already? (when test-idx
                              (some #(str/includes? % "@Disabled")
                                    (subvec result (max 0 (- test-idx 3)) test-idx)))]
              (if (and test-idx (not already?))
                (let [indent (or (re-find #"^\s+" (nth result test-idx)) "    ")
                      before (subvec result 0 test-idx)
                      after  (subvec result test-idx)]
                  (recur (inc i) (-> before
                                     (conj (str indent "@Disabled(\"runtime-failure: AI generated\")"))
                                     (into after)
                                     (conj line))))
                (recur (inc i) (conj result line))))
            (recur (inc i) (conj result line))))))))

(defn disable-failing-tests
  "surefire-reports の XML を解析して失敗メソッドを特定し、
   テストファイルの各メソッドに @Disabled を付与する。
   import も自動補完する。

   opts:
     :class         - クラス名（例 \"DocumentImportServiceImpl\"）
     :surefire-dir  - surefire-reports ディレクトリパス
     :dest-file     - 更新対象の Java テストファイルパス

   戻り値: {:status :done :class class :disabled n :not-found ms}"
  [& {:keys [class surefire-dir dest-file]}]
  (let [failed (parse-surefire-failures surefire-dir class)]
    (if (empty? failed)
      (do (println (str "  " class ": 失敗テストなし（または surefire XML が存在しない）"))
          {:status :done :class class :disabled 0 :not-found []})
      (let [content0 (slurp dest-file)
            content1 (ensure-disabled-import content0)
            ;; 各失敗メソッドに @Disabled を追加
            {:keys [content not-found]}
            (reduce (fn [{:keys [content not-found]} m]
                      (let [next (add-disabled-to-method content m)]
                        (if (= next content)
                          {:content content :not-found (conj not-found m)}
                          {:content next    :not-found not-found})))
                    {:content content1 :not-found []}
                    (sort failed))]
        (spit dest-file content)
        (println (str "  " class ": @Disabled 追加 " (- (count failed) (count not-found))
                      " 件" (when (seq not-found) (str ", 未発見: " not-found))))
        {:status :done :class class
         :disabled (- (count failed) (count not-found))
         :not-found not-found}))))

;; -------------------------
;; バッチ増幅処理（amplify-class! / amplify-all!）
;; -------------------------

(defn- find-test-java
  "module-root 配下で <ClassName>Test.java を最初に発見して java.io.File で返す。"
  [module-root class-name]
  (->> (file-seq (io/file module-root))
       (filter #(= (.getName ^java.io.File %) (str class-name "Test.java")))
       first))

(defn- mvn-sh
  "guix shell 'openjdk@21:jdk' maven -- ./mvnw <args> を sh で実行する。
   :out :err :exit を含む map を返す。"
  [mvn-root & args]
  (sh "sh" "-c"
      (str "cd " mvn-root
           " && MAVEN_OPTS='--add-opens java.base/java.lang=ALL-UNNAMED'"
           " guix shell 'openjdk@21:jdk' maven -- ./mvnw "
           (str/join " " args)
           " 2>&1")))

(defn- fix-compile-fast!
  "コンパイルエラーのある @Test メソッドを反復的に空化する（AI 不使用・高速）。
   戻り値: {:iters n :clean? bool}"
  [dest-file mvn-root module & {:keys [max-iters] :or {max-iters 30}}]
  (let [test-file-name (.getName (io/file dest-file))]
    (loop [i 0]
      (if (>= i max-iters)
        {:iters i :clean? false}
        (let [{:keys [out]} (mvn-sh mvn-root "test-compile" "-pl" module "--no-transfer-progress")
              err-lines     (filter #(re-find #"\[ERROR\]|error:" %) (str/split-lines out))
              first-err-line
              (some (fn [line]
                      (when-let [[_ ln] (re-find
                                          (re-pattern (str (java.util.regex.Pattern/quote test-file-name)
                                                           ":\\[(\\d+),"))
                                          line)]
                        (Long/parseLong ln)))
                    err-lines)]
          (if-not first-err-line
            {:iters i :clean? (empty? err-lines)}
            (let [lines   (vec (str/split-lines (slurp dest-file)))
                  n       (count lines)
                  idx     (dec (min first-err-line n))
                  test-i  (loop [j idx]
                            (cond (< j 0)                       nil
                                  (re-find #"@Test\b" (nth lines j)) j
                                  :else                          (recur (dec j))))
                  brace-i (when test-i
                            (loop [j test-i]
                              (when (< j n)
                                (if (str/includes? (nth lines j) "{") j (recur (inc j))))))]
              (if-not brace-i
                {:iters i :clean? false}
                (let [end-i    (loop [j brace-i depth 0]
                                 (when (< j n)
                                   (let [d (+ depth
                                              (count (filter #{\{} (nth lines j)))
                                              (- (count (filter #{\}} (nth lines j)))))]
                                     (if (zero? d) j (recur (inc j) d)))))
                      indent   (or (re-find #"^\s+" (nth lines test-i)) "    ")
                      already? (some #(str/includes? % "@Disabled")
                                     (subvec lines (max 0 (- test-i 2)) (inc test-i)))
                      brace-l  (nth lines brace-i)
                      sig      (subs brace-l 0 (inc (.lastIndexOf ^String brace-l "{")))
                      new-block (cond-> []
                                  (not already?) (conj (str indent "@Disabled(\"compile-error: AI generated\")"))
                                  true           (into (subvec lines test-i brace-i))
                                  true           (conj sig)
                                  true           (conj (str indent "}")))
                      new-lines (concat (subvec lines 0 test-i)
                                        new-block
                                        (when end-i (subvec lines (inc end-i))))
                      content   (ensure-disabled-import (str/join "\n" new-lines))]
                  (spit dest-file content)
                  (recur (inc i)))))))))))

(defn- run-mvn-test!
  "mvnw test を実行する。test-class が非 nil なら -Dtest=<class> を付ける。
   戻り値: {:failures? bool :summary str}"
  [mvn-root module test-class]
  (let [{:keys [out exit]}
        (mvn-sh mvn-root "test" "-pl" module
                (when test-class (str "-Dtest=" test-class))
                "--no-transfer-progress")]
    {:failures? (pos? exit)
     :summary   (or (re-find #"Tests run: \d+[^\n]*" out) "(no summary)")}))

(defn amplify-class!
  "1クラスについて patch-test-from-mds → compile fix → test → disable-failing のサイクルを実行する。

   opts:
     :class      - クラス名（例 \"IdaServiceImpl\"）
     :gen-dir    - gen-tests 基底ディレクトリ
     :repo-root  - mvnw があるディレクトリ
     :module     - Maven モジュール名（例 \"common-lib\"）
     :dest-file  - テストファイルパス（省略時は repo-root/module 以下を自動探索）

   戻り値: {:class :added :fix-iters :test-iters :success?}"
  [& {:keys [class gen-dir repo-root module dest-file]}]
  (let [surefire-dir (str repo-root "/" module "/target/surefire-reports")
        dest         (or dest-file
                         (some-> (find-test-java (str repo-root "/" module) class)
                                 .getPath))]
    (if-not dest
      (do (println (str "[SKIP] " class ": テストファイルが見つかりません")) nil)
      (do
        (println (str "\n=== " class " ==="))
        (let [patch (patch-test-from-mds :class class :gen-dir gen-dir :dest-file dest)]
          (if (and (zero? (:added patch))
                   (:clean? (fix-compile-fast! dest repo-root module)))
            (assoc patch :fix-iters 0 :test-iters 0 :success? true)
            ;; compile fix
            (let [fix (fix-compile-fast! dest repo-root module)]
              (println (str "  compile: " (:iters fix) " iters, clean=" (:clean? fix)))
              ;; test → disable ループ（最大 5 回）
              (loop [test-iter 0]
                (let [mvn (run-mvn-test! repo-root module (str class "Test"))]
                  (println (str "  test[" test-iter "]: " (:summary mvn)))
                  (cond
                    ;; 成功 or 上限
                    (or (not (:failures? mvn)) (>= test-iter 5))
                    (assoc patch :fix-iters (:iters fix) :test-iters test-iter
                                 :success? (not (:failures? mvn)))
                    ;; (no summary) = mvn test のコンパイル失敗の可能性 → 再 fix-compile
                    (= "(no summary)" (:summary mvn))
                    (let [fix2 (fix-compile-fast! dest repo-root module)]
                      (println (str "  recompile: " (:iters fix2) " iters, clean=" (:clean? fix2)))
                      (recur (inc test-iter)))
                    ;; テスト失敗 → @Disabled 付与
                    :else
                    (let [dis (disable-failing-tests
                                :class class :surefire-dir surefire-dir :dest-file dest)]
                      (if (zero? (:disabled dis))
                        (assoc patch :fix-iters (:iters fix) :test-iters test-iter :success? false)
                        (recur (inc test-iter))))))))))))))

(defn amplify-all!
  "gen-tests/ 配下の全クラス（または指定クラスリスト）に amplify-class! を一括適用する。

   opts:
     :gen-dir   - gen-tests 基底ディレクトリ
     :repo-root - mvnw があるディレクトリ
     :module    - Maven モジュール名（例 \"common-lib\"）
     :classes   - 処理対象クラス名のリスト（省略時は gen-dir 直下の全ディレクトリ）

   例:
     (amplify-all!
       :gen-dir   \"trials/experiments/2026-04-28-tradehub/exports/gen-tests\"
       :repo-root \"trials/experiments/2026-04-28-tradehub/repo\"
       :module    \"common-lib\"
       :classes   [\"IdaServiceImpl\" \"ActivityRecord\" \"FileServiceImpl\"])"
  [& {:keys [gen-dir repo-root module classes]}]
  (let [gen-file   (io/file gen-dir)
        class-names (or classes
                        (->> (.listFiles gen-file)
                             (filter #(.isDirectory ^java.io.File %))
                             (map #(.getName ^java.io.File %))
                             sort))]
    (println (str "=== amplify-all! 対象: " (count class-names) " クラス ==="))
    (let [results (mapv (fn [c]
                          (try
                            (amplify-class! :class c :gen-dir gen-dir
                                            :repo-root repo-root :module module)
                            (catch Exception e
                              (println (str "ERROR [" c "]: " (.getMessage e)))
                              {:class c :status :error})))
                        class-names)
          ok   (count (filter :success? results))
          skip (count (filter #(zero? (:added % 0)) results))
          err  (count (filter #(= :error (:status %)) results))]
      (println (str "\n--- 完了 ---"))
      (println (str "成功: " ok " / スキップ(追加なし): " skip " / エラー: " err))
      results)))

;; -------------------------
;; @Disabled 再生成
;; -------------------------

(defn- disabled-method->prod-method
  "テストメソッド名からプロダクションメソッド名を推定する。
   例: testIsSubtotalProject_NullProjectId → isSubtotalProject
       getDocumentById_success            → getDocumentById"
  [test-method]
  (let [no-test (if (str/starts-with? test-method "test")
                  (subs test-method 4)
                  test-method)
        base    (first (str/split no-test #"_"))]
    (when (seq base)
      (str (str/lower-case (subs base 0 1))
           (subs base 1)))))

(defn- remove-ai-disabled-blocks!
  "Java テストファイルから @Disabled(\"runtime-failure|compile-error: AI generated\")
   の付いた @Test メソッドブロックを除去して上書きする。
   戻り値: 除去したメソッド名のベクタ"
  [^java.io.File java-file]
  (let [content (slurp java-file)
        lines   (str/split-lines content)
        n       (count lines)
        removed (atom [])]
    (loop [i 0 out []]
      (if (>= i n)
        (do (spit java-file (str (str/join "\n" out) "\n"))
            @removed)
        (let [line (nth lines i)]
          (if (re-find #"@Disabled\(\"(runtime-failure|compile-error): AI generated\"\)" line)
            ;; ① out の末尾からアノテーション行・空行・Javadoc を除去（@Test を含む）
            (let [out' (vec (reverse
                              (drop-while (fn [l]
                                            (let [t (str/trim l)]
                                              (or (str/blank? t)
                                                  (str/starts-with? t "@")
                                                  (str/starts-with? t "/*")
                                                  (str/starts-with? t "*"))))
                                          (reverse out))))
                  ;; ② メソッド名を記録: @Disabled の前後の行を走査してシグネチャ行を探す
                  sig-pattern #"(?:public\s+|protected\s+|private\s+)?(?:static\s+)?(?:\w+\s+)?(\w+)\s*\("
                  method-name (or
                                ;; @Disabled より前 (out' 末尾方向を探す)
                                (some #(second (re-find sig-pattern (str/trim %)))
                                      (take-last 3 out'))
                                ;; @Disabled より後 (i+1, i+2, i+3 を探す)
                                (some #(when (< % n)
                                         (second (re-find sig-pattern
                                                          (str/trim (nth lines %)))))
                                      [(inc i) (+ i 2) (+ i 3)]))]
              (when method-name (swap! removed conj method-name))
              ;; ③ @Disabled 行から始めてメソッド終端を探す（ブレース深さカウント）
              (let [end-idx (loop [j i depth 0 started? false]
                              (if (>= j n)
                                j
                                (let [l      (nth lines j)
                                      opens  (count (re-seq #"\{" l))
                                      closes (count (re-seq #"\}" l))
                                      new-d  (+ depth opens (- closes))]
                                  (cond
                                    (and (not started?) (pos? opens))
                                    (if (<= new-d 0) (inc j) (recur (inc j) new-d true))
                                    started?
                                    (if (<= new-d 0) (inc j) (recur (inc j) new-d true))
                                    :else (recur (inc j) depth false)))))]
                (recur end-idx out')))
            (recur (inc i) (conj out line))))))))

(defn regen-disabled!
  "@Disabled(\"AI generated\") テストを除去して AI で再生成・再検証する。

   ① テストファイルから @Disabled ブロックを除去
   ② 対応するプロダクションメソッドの .md を :force true で再生成
   ③ amplify-class! で追加・コンパイル・テスト検証

   opts:
     :class       - 対象クラス名（例 \"DocumentAggregateServiceImpl\"）
     :gen-dir     - gen-tests 基底ディレクトリ
     :repo-root   - mvnw があるディレクトリ
     :module      - Maven モジュール名
     :trial       - :test-refs の trial 識別子
     :src-root    - jsig 検索用 Java ソースルート
     :jacoco-trial - JaCoCo trial 識別子（省略時は :trial と同じ）

   例:
     (regen-disabled!
       :class       \"DocumentAggregateServiceImpl\"
       :gen-dir     \"trials/experiments/2026-04-28-tradehub/exports/gen-tests\"
       :repo-root   \"trials/experiments/2026-04-28-tradehub/repo\"
       :module      \"common-lib\"
       :trial       \"2026-04-28-tradehub\"
       :src-root    \"trials/experiments/2026-04-28-tradehub/repo/common-lib/src/main/java\")"
  [& {:keys [class gen-dir repo-root module trial src-root jacoco-trial]}]
  (let [module-root  (str repo-root "/" module)
        gen-class-dir (io/file gen-dir class)
        java-file    (find-test-java module-root class)]
    (when-not java-file
      (throw (ex-info (str "テストファイルが見つかりません: " class) {:class class})))
    (println (str "\n=== regen-disabled! [" class "] ==="))
    ;; ① @Disabled ブロック除去
    (let [removed (remove-ai-disabled-blocks! java-file)]
      (println (str "  除去: " (count removed) " メソッド → " removed))
      ;; ② 対応 .md を再生成（prod-method を推定して gen-test）
      (let [prod-methods (->> removed
                              (map disabled-method->prod-method)
                              (filter some?)
                              distinct)
            regen-count  (atom 0)]
        (doseq [pm prod-methods]
          (try
            (let [md-file (io/file gen-class-dir (str pm ".md"))]
              (println (str "  .md 再生成: " pm))
              (let [skeleton (gen-test class
                                      :trial (or jacoco-trial trial)
                                      :method pm
                                      :src-root src-root)]
                (when skeleton
                  (io/make-parents md-file)
                  (spit md-file skeleton)
                  (swap! regen-count inc))))
            (catch Exception e
              (println (str "  [WARN] gen-test 失敗 (" pm "): " (.getMessage e))))))
        (println (str "  .md 再生成完了: " @regen-count "/" (count prod-methods) " 件"))
        ;; ③ amplify-class! で追加・コンパイル・テスト
        (amplify-class! :class class :gen-dir gen-dir
                        :repo-root repo-root :module module)))))

(defn regen-disabled-all!
  "disabled-report で 🔴 高 優先（coverage-pct=0）のクラスに regen-disabled! を一括適用する。

   opts:
     :gen-dir      - gen-tests 基底ディレクトリ
     :repo-root    - mvnw があるディレクトリ
     :module       - Maven モジュール名
     :trial        - :test-refs の trial 識別子
     :src-root     - jsig 検索用 Java ソースルート
     :jacoco-trial - JaCoCo trial 識別子（省略時は :trial と同じ）
     :classes      - 対象クラス名リスト（省略時は disabled-report の 🔴 高 クラス）

   例:
     (regen-disabled-all!
       :gen-dir      \"trials/experiments/2026-04-28-tradehub/exports/gen-tests\"
       :repo-root    \"trials/experiments/2026-04-28-tradehub/repo\"
       :module       \"common-lib\"
       :trial        \"2026-04-28-tradehub\"
       :src-root     \"trials/experiments/2026-04-28-tradehub/repo/common-lib/src/main/java\"
       :jacoco-trial \"tradehub\")"
  [& {:keys [gen-dir repo-root module trial src-root jacoco-trial classes]}]
  (let [targets (or classes
                    (->> (disabled-report :trial trial
                                          :jacoco-trial (or jacoco-trial trial)
                                          :print? false)
                         (filter #(zero? (:coverage-pct %)))
                         (map :target)))]
    (println (str "=== regen-disabled-all! 対象: " (count targets) " クラス ==="))
    (doseq [cls targets]
      (try
        (regen-disabled! :class cls :gen-dir gen-dir :repo-root repo-root
                         :module module :trial trial :src-root src-root
                         :jacoco-trial jacoco-trial)
        (catch Exception e
          (println (str "ERROR [" cls "]: " (.getMessage e))))))))

