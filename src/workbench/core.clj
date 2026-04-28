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
                       {} rs)]
    (loop [frontier #{sym} visited #{sym} d 0]
      (if (or (empty? frontier) (>= d depth))
        (disj visited sym)
        (let [next (->> frontier
                        (mapcat #(get rev % #{}))
                        (remove visited)
                        set)]
          (recur next (into visited next) (inc d)))))))

(defn deps
  "sym が依存しているシンボル（下流方向）を返す。
   すなわち sym が直接・間接的に呼び出している呼び出し先を BFS で展開する。

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
                        {} rs)]
    (loop [frontier #{sym} visited #{sym} d 0]
      (if (or (empty? frontier) (>= d depth))
        (disj visited sym)
        (let [next (->> frontier
                        (mapcat #(get forward % #{}))
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