# XTDB ワークベンチ API リファレンス

## workbench.core

REPL / AI Agent 向けの統合エントリポイント。
`(require '[workbench.core :as core])` で読み込む。

| 関数 | 説明 |
|---|---|
| `(core/start!)` | XTDB ノードを起動（インメモリ） |
| `(core/stop!)` | ノードを停止 |
| `(core/ingest! root)` | ディレクトリ以下を `:files` テーブルへ |
| `(core/xref! paths)` | Clojure ソースを解析して `:refs` テーブルへ |
| `(core/jref! paths)` | Java ソースを解析して `:refs` テーブルへ |
| `(core/q xtql)` | XTQL / SQL クエリを実行 |
| `(core/tree)` | `:files` テーブルをツリー表示（stdout） |
| `(core/tree-str)` | `tree` の文字列版（AI Agent / テスト向け） |
| `(core/refs)` | プロジェクト内部の呼び出しグラフ（ノイズフィルタ済み） |
| `(core/refs ns-prefix)` | `ns-prefix` で始まる名前空間の呼び出しグラフのみ |

### 使用例

```clojure
(require '[workbench.core :as core])

(core/start!)

;; ファイルツリーを取り込む
(core/ingest! "src")               ; => 5

;; Clojure xref を取り込む
(core/xref! ["src"])               ; => 500

;; Java xref を取り込む
(core/jref! ["trials/samples/repo"]) ; => 1

;; クエリ
(core/q '(from :files [*] (limit 1)))
(core/q '(from :refs [{:ref/from from :ref/to to :ref/kind kind}] (limit 3)))

;; 呼び出しグラフ（プロジェクト内部のみ・ノイズ除外済み）
(core/refs)                        ; => [{:from "ns/fn" :to "ns/fn" ...} ...]
(core/refs "workbench.core")       ; workbench.core 名前空間の呼び出しのみ

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
| `:xt/id` | string | `from->to@file:line`（主キー） |
| `:ref/from` | string | 呼び出し元（`ClassName/method` 形式） |
| `:ref/to` | string | 呼び出し先 |
| `:ref/kind` | string | `:call` / `:reference` / `:macroexpand` など |
| `:ref/file` | string | ソースファイルパス |
| `:ref/line` | int | 行番号 |
| `:ref/col` | int | 列番号 |
| `:ref/arity` | int | 引数の数 |

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
