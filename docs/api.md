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

;; ツリー表示
(core/tree)

(core/stop!)
```

---

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

### 認証

`GITHUB_TOKEN` 環境変数を優先し、なければ `gh auth token` で自動取得する。  
`models:read` スコープがなくても動作することを確認済み。

### 使用例

```clojure
;; コンテキストを確認してから生成
(test-context "DocumentAggregateServiceImpl" :trial "tradehub")
;; => {:trial "tradehub"
;;     :class "DocumentAggregateServiceImpl"
;;     :method nil
;;     :direct-deps ["DocumentAggregateMapper/getAggregatableDocumentTypes" ...]
;;     :sql-refs    [{:symbol "..." :col-binds [...] :param-binds [...]} ...]
;;     :coverage    [{:method "resolveTargetProcessIds" :covered 0 :missed 15 :line 42} ...]}

;; テストコードを生成（クラス全体）
(println (gen-test "DocumentAggregateServiceImpl" :trial "tradehub"))

;; 特定メソッドだけ
(println (gen-test "DocumentAggregateServiceImpl" :trial "tradehub"
                   :method "resolveTargetProcessIds"))

;; モデルを指定
(println (gen-test "DocumentAggregateServiceImpl" :trial "tradehub"
                   :model "openai/gpt-4.1-mini"))

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

### AI に渡されるプロンプト構成

```
## 対象
<クラス名>[/<メソッド名>]

## 直接依存（Mock 候補）
  - DocumentAggregateMapper/getAggregatableDocumentTypes
  - ...

## SQL 縛り（MyBatis Mapper 経由）
  - DocumentAggregateMapper/getAggregatableDocumentTypes
    col-binds: p.project_id=cp.project_id, ...
    param-binds: processId=#{context.processId}, ...

## JaCoCo カバレッジ（covered=0 = 完全未テスト）
  - resolveTargetProcessIds  [covered=0 missed=15]
  - ...

## 要件
- JUnit 5 (@Test, @ExtendWith(MockitoExtension.class))
- Mockito (@Mock, when(...).thenReturn(...))
- covered=0 のメソッドを優先してテストする
- SQL 縛りがある場合は Mapper の戻り値を Mock で制御するテストを含める
- 日本語コメントでテストの意図を説明する
```
