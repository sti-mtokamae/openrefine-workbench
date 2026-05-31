# XTDB ワークベンチ API リファレンス

## workbench.core

REPL / AI Agent 向けの統合エントリポイント。
`(require '[workbench.core :as core])` で読み込む。

| 関数 | 説明 |
|---|---|
| `(core/start!)` | XTDB ノードを起動（デフォルト: `.xtdb/` に永続化） |
| `(core/stop!)` | ノードを停止 |
| `(core/ingest! root)` | ディレクトリ以下を `:files` テーブルへ |
| `(core/xref! paths)` | Clojure ソースを解析して `:refs` テーブルへ（差分同期） |
| `(core/xref! paths :trial t)` | trial スコープ付きで Clojure xref を同期 |
| `(core/jref! paths)` | Java ソースを解析して `:refs` テーブルへ（差分同期） |
| `(core/jref! paths :trial t)` | trial スコープ付きで Java xref を同期 |
| `(core/q xtql)` | XTQL / SQL クエリを実行 |
| `(core/tree)` | `:files` テーブルをツリー表示（stdout） |
| `(core/tree-str)` | `tree` の文字列版（AI Agent / テスト向け） |
| `(core/refs)` | プロジェクト内部の呼び出しグラフ（ノイズフィルタ済み） |
| `(core/refs ns-prefix)` | `ns-prefix` で始まる名前空間の呼び出しグラフのみ |
| `(core/call-tree refs root)` | `root` を起点に呼び出し木をテキスト表示（stdout） |
| `(core/call-tree-str refs root)` | `call-tree` の文字列版（AI Agent / テスト向け） |
| `(core/fan-out)` | 全シンボルの fan-out（依存数）を降順で返す |
| `(core/fan-out refs)` | refs を指定して fan-out を計算 |
| `(core/fan-in)` | 全シンボルの fan-in（被依存数）を降順で返す |
| `(core/fan-in refs)` | refs を指定して fan-in を計算 |
| `(core/hotspots)` | fan-in 上位 10 シンボルを返す |
| `(core/hotspots n)` | fan-in 上位 n シンボルを返す |
| `(core/impact sym)` | sym を変えると壊れる上流シンボル集合（BFS） |
| `(core/impact sym :depth n)` | 探索深さを n ホップに限定 |
| `(core/deps sym)` | sym が依存する下流シンボル集合（BFS） |
| `(core/deps sym :depth n)` | 探索深さを n ホップに限定 |
| `(core/neighborhood sym)` | sym の上流+下流 2 ホップ以内のシンボル集合（切り出し範囲推定） |
| `(core/neighborhood sym :depth n)` | 探索深さを指定 |
| `(core/export-gexf! refs path)` | refs を GEXF ファイルとして出力（Gephi / Cytoscape 用） |
| `(core/export-gexf! refs path :level :class)` | クラス単位に集約（推奨。千クラス規模でも Gephi が軽い） |
| `(core/export-gexf! refs path :level :method)` | メソッド単位（デフォルト） |
| `(core/export-graphml! refs path)` | refs を GraphML ファイルとして出力（Cytoscape 向け） |
| `(core/export-graphml! refs path :level :class)` | クラス単位に集約 |
| `(core/export-cytoscape-csv! refs prefix)` | Cytoscape 向け CSV 2 枚（`-edges.csv` / `-nodes.csv`）を出力 |
| `(core/jrefs)` | Java xref クエリ（ノイズフィルタ済み） |
| `(core/jrefs :trial t)` | trial でフィルタ |
| `(core/jrefs :trial t :exclude-test true)` | テストクラスを除外 |
| `(core/jrefs :trial t :prefix cls)` | 呼び出し元クラス名前方一致でフィルタ |
| `(core/topo-sort)` | クラス依存順トポロジカルソート（切り出し順推定） |
| `(core/topo-sort :rs jrefs)` | 任意の jrefs を渡してソート |
| `(core/jsig! paths :trial t)` | Java メソッドシグネチャを `:jsigs` テーブルへ（差分同期） |
| `(core/jsigs :trial t :class cls)` | `:jsigs` クエリ（trial / class / method でフィルタ） |
| `(core/tref! paths :trial t)` | テストクラス（`*Test.java`）の構造を `:test-refs` テーブルへ（差分同期） |
| `(core/trefs :trial t)` | `:test-refs` クエリ（trial / class / target / disabled でフィルタ） |
| `(core/test-targets :trial t)` | 未カバー × SQL縛りメソッドのうちテストが存在するクラスを返す |

### 使用例

```clojure
(require '[workbench.core :as core])

;; デフォルトは .xtdb/ に永続化
(core/start!)
;; インメモリで起動する場合
;; (core/start! {:persist? false})

;; ファイルツリーを取り込む
(core/ingest! "src")               ; => 5

;; Clojure xref を取り込む（trial なし）
(core/xref! ["src"])               ; => 500
;; trial スコープ付き（差分同期）
(core/xref! ["src"] :trial "my-trial") ; => 500

;; Java xref を取り込む（trial なし）
(core/jref! ["trials/samples/repo"]) ; => 12
;; trial スコープ付き（差分同期）
(core/jref! ["trials/samples/repo"] :trial "my-trial") ; => 12

;; クエリ
(core/q '(from :files [*] (limit 1)))
(core/q '(from :refs [{:ref/from from :ref/to to :ref/kind kind}] (limit 3)))

;; 呼び出しグラフ（プロジェクト内部のみ・ノイズ除外済み）
(core/refs)                        ; => [{:from "ns/fn" :to "ns/fn" ...} ...]
(core/refs "workbench.core")       ; workbench.core 名前空間の呼び出しのみ

;; 呼び出し木を表示
(core/call-tree (core/refs) "workbench.core/ingest!")   ; stdout に木表示
(core/call-tree-str (core/refs) "workbench.core/tree")  ; 文字列として取得

;; メトリクス（fan-in / fan-out）
(core/fan-out)                     ; 依存数降順。高いほど変更影響が広い
(core/fan-out (core/refs "workbench.core")) ; refs を絞り込んで計算
(core/fan-in)                      ; 被依存数降順。高いほど重要
(core/hotspots)                    ; fan-in 上位 10 シンボル（デフォルト）
(core/hotspots 5)                  ; 上位 5 シンボル

;; ピンポイント分析（切り出し候補の調査）
(core/impact "com.example.OrderService/save")        ; 上流（何が壊れるか）
(core/impact "com.example.OrderService/save" :depth 2) ; 2 ホップまで
(core/deps   "com.example.OrderService/save")        ; 下流（何に依存しているか）
(core/deps   "com.example.OrderService/save" :depth 2)
(core/neighborhood "com.example.OrderService")       ; 上流+下流 2 ホップの切り出し範囲
(core/neighborhood "com.example.OrderService" :depth 3)

;; GEXF エクスポート（Gephi / Cytoscape 向け）
(core/export-gexf! (core/jrefs :trial "my-trial") "exports/graph.gexf" :level :class)
;; メソッド単位（ファイルが大きい。局所ビュー向け）
(core/export-gexf! (core/jrefs :trial "my-trial") "exports/graph-method.gexf")
;; GraphML エクスポート（Cytoscape 推奨）
(core/export-graphml! (core/jrefs :trial "my-trial") "exports/graph.graphml" :level :class)
;; Cytoscape CSV（GraphML が読み込めない場合の代替）
(core/export-cytoscape-csv! (core/jrefs :trial "my-trial") "exports/graph" :level :class)

;; Java xref クエリ（ノイズフィルタ・テスト除外・クラス前方一致）
(core/jrefs :trial "my-trial")
(core/jrefs :trial "my-trial" :exclude-test true)
(core/jrefs :trial "my-trial" :prefix "OrderServiceImpl")

;; トポロジカルソート（葉クラスから根クラスへの切り出し順）
(core/topo-sort)
(core/topo-sort :rs (core/jrefs :trial "my-trial" :exclude-test true))

;; テストクラス構造を投入（*Test.java → :test-refs テーブル）
(core/tref! ["trials/experiments/xxx/repo/common-lib/src/test"]
            :trial "my-project")
;; => {:put 1549, :delete 0}

;; テストメソッド一覧を取得
(core/trefs :trial "my-project")                                  ; 全件
(core/trefs :trial "my-project" :target "DocumentAggregateServiceImpl") ; 対象クラスでフィルタ
(core/trefs :trial "my-project" :disabled true)                   ; @Disabled のみ

;; 未カバーSQL × テスト存在クラスの交差（「暗闇の中の意味ある失敗」を特定）
(core/test-targets :trial "my-project")
;; => [{:class "DocumentAggregateServiceImpl"
;;      :uncovered-methods ["resolveTargetProcessId" ...]
;;      :test-methods [{:tref/class "DocumentAggregateServiceImplTest"
;;                      :tref/method "testResolve_xxx"
;;                      :tref/disabled? false} ...]}]

;; ツリー表示
(core/tree)

(core/stop!)
```

---


---

## Javaコンパイルエラー判定 API

| 関数 | 説明 |
|---|---|
| `(core/compile-errors-dir! root)` | 指定ディレクトリ以下の全JavaファイルをDiagnosticCollectorでチェックし、エラー情報をXTDBに格納する |
| `(core/compile-ok-java-files root)` | コンパイルエラーがないJavaファイルのパスだけをベクタで返す |

### 使用例

```clojure
;; Javaファイルのコンパイルエラーをチェック
(core/compile-errors-dir! "trials/experiments/2026-04-28-tradehub/repo")

;; コンパイル成功したファイルのみ取得
(core/compile-ok-java-files "trials/experiments/2026-04-28-tradehub/repo")
```

- `compile-errors-dir!` は各ファイルごとに `{:file ... :errors [...]}` のベクタを返します。
- `compile-ok-java-files` は `:errors` が空のファイルのみ抽出します。

## テーブルスキーマ

### `:files` テーブル（`ingest!` で投入）

| フィールド | 型 | 説明 |
|---|---|---|
| `:xt/id` | string | ファイルパス（主キー） |
| `:file/path` | string | パス |
| `:file/name` | string | ファイル名 |
| `:file/ext` | string | 拡張子 |
| `:file/size` | long | バイト数 |
| `:file/dir?` | bool | ディレクトリなら true |
| `:file/parent` | string | 親ディレクトリパス |

### `:refs` テーブル（`xref!` / `jref!` で投入）

| フィールド | 型 | 説明 |
|---|---|---|
| `:xt/id` | string | `"trial::from->to"` または `"from->to"`（主キー） |
| `:ref/trial` | string \| nil | trial 識別子（`nil` = trial なし） |
| `:ref/from` | string | 呼び出し元（`ClassName/method` 形式） |
| `:ref/to` | string | 呼び出し先 |
| `:ref/kind` | string | `:call` / `:reference` / `:macroexpand` など |
| `:ref/file` | string | ソースファイルパス |
| `:ref/line` | int | 行番号 |
| `:ref/col` | int | 列番号 |
| `:ref/arity` | int | 引数の数 |

**`:xt/id` の形式：**

| 呼び出し方 | `:xt/id` 例 |
|---|---|
| `(xref! ["src"])` | `"workbench.core/start!->xtdb.node/start-node"` |
| `(xref! ["src"] :trial "t1")` | `"t1::workbench.core/start!->xtdb.node/start-node"` |

trial スコープの同期は `:ref/trial` 値が一致する既存 refs を差分削除してから新 refs を投入する。
trial なし（`nil`）も同様に nil スコープとして同期対象になる。

**`xref!` vs `jref!` の違い：**

| | `xref!` (Clojure) | `jref!` (Java) |
|---|---|---|
| 実装 | clj-xref (clj-kondo ベース) | JavaParser (静的 AST) |
| `:ref/from` | `namespace/fn-name` | `ClassName/methodName` |
| `:ref/to` | 完全修飾シンボル | スコープ付き単純名（例: `System.out.println`） |
| シンボル解決 | あり | なし（シンボルリゾルバ未使用） |

---

## XTDB v2 実装時の落とし穴

**JVM フラグが必須**

Apache Arrow の初期化に `--add-opens` が必要。`deps.edn` の `:xtdb` エイリアスに収めてある。

**SQL では `?` 付き列名を返せない**

`:file/dir?` のような Clojure キーワードは SQL の SELECT 結果に出てこない。XTQL を使うこと。

```clojure
;; NG: SQL では dir? が欠落する
(xt/q node "SELECT * FROM files")

;; OK: XTQL なら全フィールドが返る
(xt/q node '(from :files [*]))
```

**XTDB に保存できる型**

`clojure.lang.Symbol` は直接保存不可。`(str sym)` で文字列に変換すること。

**JavaParser `findAncestor` の varargs 競合**

Clojure から `(.findAncestor expr SomeClass)` を呼ぶと varargs オーバーロードと競合して `ClassCastException` になる。`getParentNode` で親を手動に辿ること（`jref.clj` の `find-ancestor` 参照）。

**XTDB v2 の `:storage` キー（インデックス永続化）**

`:index-store` は XTDB v2 では**無効キー**（エラーにならずサイレントに MemoryStorage へフォールバック）。
プロセス終了のたびにインデックスが消えて「毎回壊れる」現象が起きる。正しい設定は `:storage`。

```clojure
;; NG: :index-store は XTDB v2 では無効
(xtn/start-node {:log     [:local {:path ".../.xtdb/log"}]
                 :index-store [:local {:path ".../.xtdb/index"}]})

;; OK: :storage が正しいキー
(xtn/start-node {:log     [:local {:path ".../.xtdb/log"}]
                 :storage [:local {:path ".../.xtdb/storage"}]})
```

---

## SQL 解析 API（sqlref）

MyBatis の `@Select` / `@Insert` / `@Update` / `@Delete` アノテーションの SQL を
JavaParser で AST 解析し、`:sql-refs` テーブルに取り込む。

| 関数 | 説明 |
|---|---|
| `(core/sqlref! paths)` | Java ソースの MyBatis SQL アノテーションを解析して `:sql-refs` テーブルへ |
| `(core/sqlref! paths :trial t)` | trial スコープ付きで投入 |
| `(core/sqlrefs)` | `:sql-refs` テーブルの全レコードを返す |
| `(core/sqlrefs :trial t)` | trial でフィルタ |

### 使用例

```clojure
;; MyBatis Mapper ディレクトリを指定して投入
(core/sqlref! ["repo/common-lib" "repo/common-app"] :trial "tradehub")

;; 全レコード参照
(core/sqlrefs :trial "tradehub")
```

### `:sql-refs` テーブルスキーマ（`sqlref!` で投入）

| フィールド | 型 | 説明 |
|---|---|---|
| `:xt/id` | string | `"trial::ClassName/method"` または `"ClassName/method"` |
| `:sqlref/trial` | string \| nil | trial 識別子 |
| `:sqlref/symbol` | string | `"ClassName/methodName"` 形式の Mapper メソッド |
| `:sqlref/kind` | keyword | `:select` / `:insert` / `:update` / `:delete` |
| `:sqlref/sql` | string | アノテーション内の SQL 文字列 |
| `:sqlref/param-binds` | vector | `#{col}` / `#{param}` 形式の埋め込み変数。`[{:col "colName" :param "paramExpr"}]` |
| `:sqlref/col-binds` | vector | `table.col = alias.col` 形式の JOIN 縛り条件。`[{:lhs "d.work_process_id" :rhs "cp.source_process_id"}]` |

---

## テストクラス解析 API（tref）

`*Test.java` を JavaParser で AST 解析し、テストメソッド構造を `:test-refs` テーブルに取り込む。  
`@InjectMocks` でテスト対象クラスを、`@Mock`/`@MockBean` でモック依存を抽出する。

| 関数 | 説明 |
|---|---|
| `(core/tref! paths :trial t)` | テストクラスを解析して `:test-refs` テーブルへ（差分同期・冪等） |
| `(core/trefs :trial t)` | `:test-refs` テーブルの全レコードを返す |
| `(core/trefs :trial t :class cls)` | テストクラス名でフィルタ |
| `(core/trefs :trial t :target cls)` | `@InjectMocks` で指定された対象クラス名でフィルタ |
| `(core/trefs :trial t :disabled true)` | `@Disabled` テストのみ返す |
| `(core/test-targets :trial t)` | 未カバー SQL × テスト存在クラスの交差クエリ |

### 使用例

```clojure
;; テストソースディレクトリを指定して投入
(core/tref! ["repo/common-lib/src/test"] :trial "my-project")
;; => {:put 1549, :delete 0}

;; 対象クラスでフィルタ（誰がこのクラスをテストしているか）
(core/trefs :trial "my-project" :target "DocumentAggregateServiceImpl")

;; 無効化されたテストだけ確認
(core/trefs :trial "my-project" :disabled true)

;; 未カバー SQL メソッドのうち、すでにテストが存在するクラスを特定
;; 「どの failure が意味のある失敗か」を絞り込む
(core/test-targets :trial "my-project")
;; => [{:class "DocumentAggregateServiceImpl"
;;      :uncovered-methods ["resolveTargetProcessId" ...]
;;      :test-methods [{:tref/class "DocumentAggregateServiceImplTest"
;;                      :tref/method "testResolve_xxx"
;;                      :tref/disabled? false} ...]}
;;     ...]
```

### `:test-refs` テーブルスキーマ（`tref!` で投入）

| フィールド | 型 | 説明 |
|---|---|---|
| `:xt/id` | string | `"tref/<trial>/<TestClass>/<testMethod>"` |
| `:tref/trial` | string \| nil | trial 識別子 |
| `:tref/class` | string | テストクラス名（例: `DocumentAggregateServiceImplTest`） |
| `:tref/method` | string | テストメソッド名 |
| `:tref/target` | string \| nil | `@InjectMocks` で宣言されたテスト対象クラス名 |
| `:tref/mocks` | vector | `@Mock`/`@MockBean`/`@Spy`/`@SpyBean` の型名リスト |
| `:tref/disabled?` | bool | `@Disabled` アノテーションが付いていれば `true` |
| `:tref/package` | string | テストクラスのパッケージ名 |
| `:tref/file` | string | ソースファイルパス |

**`test-targets` 戻り値スキーマ：**

```clojure
[{:class             "DocumentAggregateServiceImpl"   ; 対象クラス名
  :uncovered-methods ["resolveTargetProcessId" ...]   ; covered=0 かつ SQL 縛りあり
  :test-methods      [{:tref/class     "DocumentAggregateServiceImplTest"
                       :tref/method    "testXxx"
                       :tref/disabled? false} ...]}   ; 既存テストメソッド（多い順）
 ...]
```

---

## 共変更解析 API（cochange）

Git 履歴からコミット単位の共変更ペアを集計し、静的解析では見えない結合を炙り出す。

| 関数 | 説明 |
|---|---|
| `(core/cochange! repo-path)` | Git 履歴を解析して `:cochanges` テーブルへ（冪等） |
| `(core/cochange! repo-path :trial t :filter-path p)` | trial 付き・対象パス絞り込み |
| `(core/cochanges)` | 共変更ペアを count 降順で返す |
| `(core/cochanges :trial t :top n :min-count k)` | trial フィルタ・上位 N 件・最小回数指定 |

### 使用例

```clojure
;; 初回投入（Java ファイルのみに絞り込む）
(core/cochange! "repo/tradehub"
                :trial "tradehub"
                :filter-path "src/main/java")

;; 上位30件（3回以上同時変更）
(core/cochanges :trial "tradehub" :top 30 :min-count 3)
```

### `:cochanges` テーブルスキーマ（`cochange!` で投入）

| フィールド | 型 | 説明 |
|---|---|---|
| `:xt/id` | string | `"trial::a<->b"` または `"a<->b"` |
| `:cc/trial` | string \| nil | trial 識別子 |
| `:cc/a` | string | ファイルパス（git log の repo 相対パス。例: `common-app/src/main/.../Foo.java`） |
| `:cc/b` | string | ファイルパス（同上） |
| `:cc/count` | int | 同一コミットで変更された回数 |

**注意**: `:cc/a` / `:cc/b` は repo 相対パスで、`:ref/file`（モジュールルート相対）とはパス形式が異なる。
照合にはファイル名のステム（拡張子なし）= クラス名で正規化すること。

---

## SQL 変更影響分析 API

SQL 縛り条件をパターンマッチして影響クラスを呼び出しグラフから逆引きし、
cochange 履歴と照合して変更コストを可視化する。

| 関数 | 説明 |
|---|---|
| `(core/sql-impact pat)` | SQL 縛りパターンにマッチする Mapper を起点に上流シンボルを返す |
| `(core/sql-impact-report pat)` | fan-in・レイヤー分類付きレポートを返す |
| `(core/sql-impact-report-multi bind-pats)` | 複数パターンを一括処理 |
| `(core/sql-cochange-check report)` | レポートの各クラスに `:cochange-cnt` を付与 |

### `:source` オプション（検索対象の bind 種別）

| 値 | 検索対象 | フィールド |
|---|---|---|
| `:col-binds`（デフォルト）| JOIN 縛り（`table.col = alias.col` 形式） | `:lhs` / `:rhs` |
| `:param-binds` | WHERE 絞り込み（`col = #{param}` 形式） | `:col` |
| `:any` | 両方 | — |

### 使用例

```clojure
;; JOIN 縛り（デフォルト）
(core/sql-impact #"source_process_id" :trial "tradehub")

;; WHERE 句パラメータ（param-binds）で検索
(core/sql-impact #"process_id" :trial "tradehub" :source :param-binds)

;; 両方まとめて検索
(core/sql-impact #"process_id" :trial "tradehub" :source :any)

;; fan-in + レイヤー付きレポート（ノイズ除外）
(core/sql-impact-report #"source_process_id"
                         :trial "tradehub"
                         :noise-cls? noise-cls?)

;; 複数パターン一括 + cochange 照合（:source も指定可）
(let [rs      (core/jrefs :trial "tradehub" :exclude-test true)
      reports (->> (core/sql-impact-report-multi
                     [["process_id" #"process_id"]]
                     :trial "tradehub"
                     :rs rs
                     :source :any
                     :noise-cls? noise-cls?)
                   (map #(core/sql-cochange-check % :trial "tradehub")))]
  ...)
```

### `sql-impact-report` 戻り値スキーマ

```clojure
{:mappers     [{:mapper-sym   "SourceProjectAggregateMapper/fetchAggregationResultData"
                :col-binds    [{:lhs "d.work_process_id" :rhs "cp.source_process_id"}]  ; :source に :col-binds/:any を含む場合
                :param-binds  [{:col "processId" :param "context.processId"}]           ; :source に :param-binds/:any を含む場合
                :upstream     [{:class "DocumentAggregateServiceImpl"
                                :layer :service
                                :fan-in 3
                                :cochange-cnt 0}  ; sql-cochange-check 付与後
                               ...]}]
 :all-classes [{:class "DocumentsController" :layer :controller :fan-in 8 :cochange-cnt 12}
               ...]}
```

**レイヤー分類ルール:**

| 末尾パターン | レイヤー |
|---|---|
| `Controller` | `:controller` |
| `ServiceImpl` / `Service` | `:service` |
| `Mapper` / `Repository` / `Dao` | `:mapper` |
| `Aspect` / `Interceptor` / `Filter` / `Handler` | `:infra` |
| その他 | `:other` |

**`:cochange-cnt = 0` の意味**: 静的解析で影響が検出されたが git 履歴に変更の痕跡がない
= 未テスト経路の疑い、または実際には変更不要なパス。

---

## JaCoCo カバレッジ API（jacoco）

JaCoCo XML レポートを解析して XTDB に取り込み、「テストが届いていないメソッド」をクエリする。

### 使用例

```clojure
;; JaCoCo XML を投入（差分同期・冪等）
(jacoco! "/tmp/jacoco-report/jacoco.xml" :trial "tradehub")
;; => {:put 2329, :delete 0}

;; 全レコード取得
(jacocos :trial "tradehub")

;; 完全未カバーのみ
(jacocos :trial "tradehub" :uncovered? true)

;; 特定クラスのカバレッジ
(jacocos :trial "tradehub" :class "DocumentAggregateServiceImpl")

;; クラス単位サマリ（covered-methods=0 のクラスのみ）
(coverage :trial "tradehub" :uncovered? true)
```

### `:jacoco` テーブルスキーマ（`jacoco!` で投入）

| フィールド | 型 | 説明 |
|---|---|---|
| `:jacoco/trial` | string | トライアル識別子 |
| `:jacoco/class` | string | パッケージ付きクラス名（スラッシュ区切り） |
| `:jacoco/class-simple` | string | クラス名のみ（`:refs` との照合用） |
| `:jacoco/method` | string | メソッド名 |
| `:jacoco/desc` | string | JVM ディスクリプタ |
| `:jacoco/line` | long | メソッド開始行番号 |
| `:jacoco/covered` | long | カバー済み行数（LINE counter） |
| `:jacoco/missed` | long | 未カバー行数（LINE counter） |

### `coverage` 戻り値スキーマ

```clojure
[{:class           "DocumentAggregateServiceImpl"
  :covered-methods 0    ; covered > 0 のメソッド数
  :total-methods   33   ; 全メソッド数
  :covered-lines   0    ; カバー済み行数合計
  :total-lines     487} ; 全行数合計
 ...]
```

---

## AI テスト生成 API（codegen）

静的解析情報（`:refs` × `:sqlrefs` × `:jacoco`）を統合して、GitHub Models API 経由で JUnit 5 + Mockito テストコードを生成する。

> コンセプト・データフロー・プロンプト構成・制約・使い方手順は [docs/test-amplification.md](test-amplification.md) を参照。

### 認証

`GITHUB_TOKEN` 環境変数を優先し、なければ `gh auth token` で自動取得する。  
`models:read` スコープがなくても動作することを確認済み。

### 使用例

```clojure
;; 前提: メソッドシグネチャを投入する（差分同期・冪等）
(core/jsig! ["trials/experiments/xxx/repo"] :trial "tradehub")
;; => {:put 3415, :delete 0}

;; シグネチャをクエリ
(core/jsigs :trial "tradehub" :cls "DocumentAggregateServiceImpl")
;; => [{:jsig/method "resolveTargetProcessId"
;;      :jsig/params [{:name "documents" :type "List<DocumentsEntity>"}]
;;      :jsig/return "UUID" :jsig/throws [] :jsig/mods ["private"]} ...]

;; コンテキストを確認してから生成
(core/test-context "DocumentAggregateServiceImpl" :trial "tradehub")
;; => {:trial "tradehub"
;;     :class "DocumentAggregateServiceImpl"
;;     :method nil
;;     :direct-deps ["DocumentAggregateMapper/getAggregatableDocumentTypes" ...]
;;     :sql-refs    [{:symbol "..." :col-binds [...] :param-binds [...]} ...]
;;     :coverage    [{:method "resolveTargetProcessIds" :covered 0 :missed 15 :line 42} ...]
;;     :signatures  [{:method "resolveTargetProcessId"
;;                    :params [{:name "documents" :type "List<DocumentsEntity>"}]
;;                    :return "UUID" :throws [] :mods ["private"]} ...]}

;; テストコードを生成（クラス全体）
(println (core/gen-test "DocumentAggregateServiceImpl" :trial "tradehub"))

;; 特定メソッドだけ
(println (core/gen-test "DocumentAggregateServiceImpl" :trial "tradehub"
                        :method "resolveTargetProcessId"))

;; モデルを指定
(println (core/gen-test "DocumentAggregateServiceImpl" :trial "tradehub"
                        :model "openai/gpt-4.1-mini"))

;; 未カバー × SQL 縛りのメソッドを全件洗い出す（dry-run: API 呼び出しなし）
(core/uncovered-sql-methods :trial "tradehub")
;; => [{:class "GenericMasterCsvServiceImpl"
;;      :method "importCsvFilesToStagingTables"
;;      :sql-deps ["GenericMasterImportMapper/createGenericMasterImportStagingFile"]} ...]

;; 全件一括生成（:out-dir を指定するとファイル書き出し）
(core/gen-tests-uncovered :trial "tradehub"
                          :out-dir "trials/experiments/2026-04-28-tradehub/gen-tests")

;; 低レベル API（任意のプロンプト）
(require '[workbench.codegen :as cg])
(cg/chat-complete [{:role "system" :content "..."}
                   {:role "user"   :content "..."}])
```

### `test-context` 戻り値スキーマ

| フィールド | 説明 |
|---|---|
| `:direct-deps` | このクラス（メソッド）が直接呼び出す依存先シンボル（Mock 候補） |
| `:sql-refs` | 依存先 Mapper メソッドの SQL 縛り情報（`:col-binds` / `:param-binds`） |
| `:coverage` | JaCoCo カバレッジ（`:covered 0` = 完全未テスト） |
| `:signatures` | メソッドシグネチャ（`jsig!` 投入済みの場合に付与。型推定精度が向上する） |

### `:jsigs` テーブルスキーマ（`jsig!` で投入）

| フィールド | 型 | 説明 |
|---|---|---|
| `:jsig/trial` | string | トライアル識別子 |
| `:jsig/class` | string | シンプルクラス名（`:refs` との照合用） |
| `:jsig/method` | string | メソッド名 |
| `:jsig/params` | `[{:name :type}]` | パラメータ一覧（名前 + 型） |
| `:jsig/return` | string | 戻り値型 |
| `:jsig/throws` | `[string]` | throws 節の例外型一覧 |
| `:jsig/mods` | `[string]` | アクセス修飾子（`"public"` / `"private"` など） |

### `gen-tests-uncovered` オプション

| オプション | デフォルト | 説明 |
|---|---|---|
| `:trial` | — | トライアル識別子 |
| `:model` | `"openai/gpt-4.1"` | 使用する GitHub Models モデル |
| `:out-dir` | `nil` | 指定すると `<out-dir>/<ClassName>/<method>.md` に書き出す |
| `:dry-run` | `false` | `true` のとき API を呼ばず候補一覧だけ返す |

---

## テストスケルトン統合・修正 API

`gen-tests-uncovered` で生成した `.md` スケルトンを Java ファイルに統合し、  
コンパイルエラーを AI で修正するためのユーティリティ群。

### 使用例

```clojure
;; 1. .md → Test.java 統合（クラス単位）
(core/merge-test-mds
  :class   "ProjectServiceImpl"
  :gen-dir "trials/experiments/2026-04-28-tradehub/exports/gen-tests")
;; => {:status :merged, :class "ProjectServiceImpl", :test-count 42}

;; 2. .md → Test.java 統合（全クラス一括）
(core/merge-all-test-mds
  :gen-dir "trials/experiments/2026-04-28-tradehub/exports/gen-tests")

;; 3. 既存の Test.java をコンパイルエラーを元に AI で修正（クラス単位）
(core/fix-test "ActivityRecordServiceImpl"
  :trial    "2026-04-28-tradehub"
  :src-root "trials/experiments/2026-04-28-tradehub/repo/common-lib/src/main/java"
  :java-path "trials/experiments/2026-04-28-tradehub/exports/gen-tests/ActivityRecordServiceImpl/ActivityRecordServiceImplTest.java"
  :mvn-root "trials/experiments/2026-04-28-tradehub/repo"
  :module   "common-lib")

;; 4. gen-tests/ 以下の全 *Test.java を一括修正
(core/fix-tests-dir
  :gen-dir  "trials/experiments/2026-04-28-tradehub/exports/gen-tests"
  :trial    "2026-04-28-tradehub"
  :src-root "trials/experiments/2026-04-28-tradehub/repo/common-lib/src/main/java"
  :mvn-root "trials/experiments/2026-04-28-tradehub/repo"
  :module   "common-lib")
```

### `merge-test-mds` / `merge-all-test-mds`

`.md` スケルトンから `.java` テストファイルを組み立てる。  
同名メソッドの重複排除・import の統合・フィールド宣言の重複排除を行う。

| 関数 | 説明 |
|---|---|
| `(core/merge-test-mds :class c :gen-dir d)` | `<gen-dir>/<c>/` の *.md を統合して `<c>Test.java` を生成 |
| `(core/merge-test-mds ... :force true)` | 既存 `.java` を上書き |
| `(core/merge-all-test-mds :gen-dir d)` | `<gen-dir>/` 以下の全クラスに一括適用 |

戻り値（`merge-test-mds`）: `{:status :merged|:skipped|:no-mds, :class c, :test-count n}`

### `fix-test` / `fix-tests-dir`

既存の `*Test.java` にコンパイルエラーがある場合、`test-context` の情報を使って AI で修正する。  
テストシナリオ（`@Test` の意図）を保持したまま hallucination（型不一致・存在しないメソッドなど）だけを修正する。

| オプション | 説明 |
|---|---|
| `:trial` | トライアル識別子 |
| `:src-root` | プロダクションコードのルート（シグネチャ解決用） |
| `:java-path` | 修正対象の `.java` ファイルパス |
| `:mvn-root` | `mvnw` があるディレクトリ（コンパイルエラー自動取得） |
| `:module` | Maven `-pl` モジュール名（`mvn-root` 指定時） |
| `:errors` | コンパイルエラー文字列（直接渡す場合、`mvn-root` より優先） |
| `:model` | 使用モデル（デフォルト: `"openai/gpt-4.1"`） |
| `:dest-dir` | 修正済みファイルの出力先（`fix-tests-dir` のみ。省略時は上書き） |
| `:force` | `true` のとき `dest-dir` に既存ファイルがあっても上書き（`fix-tests-dir` のみ） |
| `:dry-run` | `true` のとき API を呼ばず対象ファイル一覧のみ表示（`fix-tests-dir` のみ） |

### `patch-test-from-mds` — 既存テストへの追記

既にリポジトリにある `*Test.java` に、per-method md から抽出した `@Test` メソッドを追記する。  
`merge-all-test-mds`（新規生成）とは異なり、**既存ファイルを上書きしない**。

```clojure
(core/patch-test-from-mds
  :class     "ProjectServiceImpl"
  :gen-dir   "trials/experiments/2026-04-28-tradehub/exports/gen-tests"
  :dest-file "trials/.../repo/common-lib/src/test/java/.../ProjectServiceImplTest.java")
;; => {:status :patched, :class "ProjectServiceImpl", :added 24, :skipped-dup 0}
```

| オプション | 説明 |
|---|---|
| `:class` | クラス名（`gen-dir/<class>/` 以下の md を読む） |
| `:gen-dir` | gen-tests の基底ディレクトリ |
| `:dest-file` | 追記対象の既存 `*Test.java` ファイルパス |

戻り値: `{:status :patched, :class c, :added n, :skipped-dup m}`

---

### `disable-failing-tests` — 実行時失敗への @Disabled 付与

`mvn test` 後の surefire XML を解析し、失敗したテストメソッドに `@Disabled` を付与する。  
`import org.junit.jupiter.api.Disabled;` が未存在の場合は自動補完する。

```clojure
(core/disable-failing-tests
  :class        "ProjectServiceImpl"
  :surefire-dir "trials/.../repo/common-lib/target/surefire-reports"
  :dest-file    "trials/.../repo/common-lib/src/test/java/.../ProjectServiceImplTest.java")
;; => {:status :done, :class "ProjectServiceImpl", :disabled 36, :not-found []}
```

| オプション | 説明 |
|---|---|
| `:class` | クラス名（surefire XML のファイル名フィルタにも使用） |
| `:surefire-dir` | surefire-reports ディレクトリパス（`target/surefire-reports`） |
| `:dest-file` | `@Disabled` を付与する `*Test.java` ファイルパス |

戻り値: `{:status :done, :class c, :disabled n, :not-found [メソッド名…]}`  
`:not-found` にメソッド名が含まれる場合、ファイル内で対応する `void methodName(` が見つからなかったことを示す（通常は既に `@Disabled` 済み）。

> **注意**: `mvn test` を実行するたびに surefire XML は上書きされる。  
> `disable-failing-tests` は必ず **最新の `mvn test` 直後** に実行すること。

---

### テスト増幅の全体フロー

#### A. 既存テストなし（新規生成）

```
1. gen-tests-uncovered  → exports/gen-tests/<Class>/<method>.md  (AI 生成スケルトン)
       ↓
2. merge-all-test-mds   → exports/gen-tests/<Class>/<Class>Test.java  (md を統合)
       ↓
3. （手動）Test.java を src/test/java/ 以下にコピー
       ↓
4. mvn test-compile     → コンパイルエラー確認
       ↓
5. fix-tests-dir        → コンパイルエラーを AI で修正（反復）
       ↓
6. mvn test             → surefire XML 生成
       ↓
7. disable-failing-tests → 実行時失敗に @Disabled を付与（必要に応じ 6→7 を反復）
       ↓
8. mvn verify           → JaCoCo XML 生成
       ↓
9. jacoco!              → XTDB に再投入
       ↓
10. uncovered-sql-methods → 残件確認 → 1. へ戻る
```

#### B. 既存テストあり（追記方式）

```
1. gen-tests-uncovered  → exports/gen-tests/<Class>/<method>.md  (AI 生成スケルトン)
       ↓
2. patch-test-from-mds  → 既存 *Test.java に @Test メソッドを追記
       ↓
3. mvn test-compile     → コンパイルエラー確認
       ↓
4. fix-test（または手動）→ コンパイルエラーのあるメソッドを空化 + @Disabled
       ↓
5. mvn test             → surefire XML 生成
       ↓
6. disable-failing-tests → 実行時失敗に @Disabled を付与（必要に応じ 5→6 を反復）
       ↓
7. mvn verify           → JaCoCo XML 生成
       ↓
8. jacoco!              → XTDB に再投入
```
