# self-analysis

## goal

workbench 自身のソース（`src/`）を `xref!` で解析し、呼び出しグラフを確認する。

## 実行コマンド

```clojure
(core/start!)
(core/ingest! "src")
(core/xref!   ["src"])
(core/tree)
(core/q '(from :refs [{:ref/from from :ref/to to :ref/file file}]
               (order-by from)))
(core/stop!)
```

## observation

### workbench.core の他 namespace への委譲構造

```
core/ingest!  → ingest/dir!
core/xref!    → ingest/xref!  → clj-xref.core/analyze
core/jref!    → jref/jref!
core/q        → query/q
core/tree     → visualize/tree → visualize/render-tree（再帰）
```

### workbench.jref の内部呼び出しチェーン

```
jref/jref!    → jref/parse-file → jref/call->doc → jref/from-sym → jref/find-ancestor
```

### openrefine-runner の主要フロー

```
run-trial → create-project!
run-trial → open-browser!
run-trial → export-results! → http-client
fetch-csrf-token → http-get-text
normalize-trial → input-files
normalize-trial → resolve-path
```

## call-tree（`core/call-tree` 追加後）

### workbench.core/ingest!

```
workbench.core/ingest!
  workbench.core/node
    workbench.core/state
  workbench.ingest/dir!
    workbench.ingest/dir
      workbench.ingest/file->doc
```

### workbench.core/xref!

```
workbench.core/xref!
  workbench.core/node
    workbench.core/state
  workbench.ingest/xref!
    clj-xref.core/analyze
    workbench.ingest/ref->doc
```

### workbench.jref/jref!

```
workbench.jref/jref!
  workbench.jref/java-files
  workbench.jref/parse-file
    workbench.jref/call->doc
      workbench.jref/from-sym
        workbench.jref/find-ancestor
        workbench.jref/find-ancestor
      workbench.jref/opt->val
      workbench.jref/opt->val
```

> `find-ancestor` / `opt->val` が2回現れるのは複数の呼び出しサイトに対応（同一 visited セットで重複表示）。

## ファンアウト/ファンイン集計

### ファンアウト TOP（何を多く呼ぶか）

```clojure
(->> (core/refs)
     (group-by :from)
     (map (fn [[k v]] {:from k :fanout (count v)}))
     (sort-by :fanout >)
     (take 10))
```

| 関数 | fanout |
|---|---|
| `openrefine-runner/normalize-trial` | 18 |
| `openrefine-runner/run-trial` | 9 |
| `openrefine-runner/multipart-body` | 6 |
| `openrefine-runner/create-project!` | 3 |
| `workbench.jref/call->doc` | 3 |

→ `normalize-trial` が突出。関数の責務が広いことを示す。

### ファンイン TOP（誰から多く呼ばれるか）

```clojure
(->> (core/refs)
     (group-by :to)
     (map (fn [[k v]] {:to k :fanin (count v)}))
     (sort-by :fanin >)
     (take 10))
```

| 関数 | fanin |
|---|---|
| `openrefine-runner/utf8-bytes` | 6 |
| `workbench.core/state` | 5 |
| `workbench.core/node` | 4 |
| `openrefine-runner/resolve-path` | 4 |
| `workbench.visualize/render-tree` | 3 |

→ `utf8-bytes` が最多。バイト変換のユーティリティとして多用されている。

## 発見と次のアクション

- **Clojure 側 `group-by` パターン**が XTDB `aggregate` より柔軟で使いやすい。`docs/analysis.md` に追記候補 → ✅ 追記済み
- `normalize-trial`（fanout 18）を `call-tree` で展開するとアーキテクチャの全体像が見えそう → ✅ 下記参照
- `find-ancestor` / `opt->val` が複数回表示される問題 → `call-tree` の重複表示を抑制する余地あり → ✅ `[...]` で対処済み

## openrefine-runner アーキテクチャ（call-tree で読む）

### normalize-trial（fanout 18 の正体）

```
openrefine-runner/normalize-trial
  openrefine-runner/read-trial          ← trial.edn 読み込み
  openrefine-runner/trial-dir           ← メタデータ取得 (×6)
  openrefine-runner/trial-id
  openrefine-runner/trial-tool
  openrefine-runner/project-name
  openrefine-runner/goal
  openrefine-runner/notes-file
  openrefine-runner/input-files         ← ファイルリスト取得
  openrefine-runner/seed-files
  openrefine-runner/output-dir          ← 出力設定取得
  openrefine-runner/export-format
  openrefine-runner/import-format
  openrefine-runner/open-browser?
  openrefine-runner/openrefine-url
  openrefine-runner/resolve-path        ← パス解決（4回）
  openrefine-runner/resolve-path [...]
  openrefine-runner/resolve-path [...]
  openrefine-runner/resolve-path [...]
```

**読み**: fanout 18 は「複雑な処理」ではなく「フィールドアクセサを 18 個呼ぶ設計」。  
`normalize-trial` の責務は純粋な trial.edn 正規化。`resolve-path` が4回 `[...]` になるのは、複数パスに同じユーティリティを繰り返すため。

### run-trial の全体フロー

```
openrefine-runner/run-trial
  openrefine-runner/normalize-trial     ← trial.edn 正規化（上記）
    ...
  openrefine-runner/validate-trial!     ← 入力ファイル存在確認
    openrefine-runner/ensure-file-exists!
    openrefine-runner/ensure-file-exists! [...]
  openrefine-runner/reachable?          ← OpenRefine 起動確認
    openrefine-runner/http-get-text
  openrefine-runner/create-project!     ← プロジェクト作成（multipart POST）
    openrefine-runner/http-client
    openrefine-runner/multipart-body
      openrefine-runner/utf8-bytes      ← バイト変換ユーティリティ（×6）
      ...
    openrefine-runner/random-boundary
  openrefine-runner/apply-operations!  ← seed-history 適用
  openrefine-runner/fetch-csrf-token    ← CSRF トークン取得
    openrefine-runner/extract-csrf-token
    openrefine-runner/http-get-text [...]
  openrefine-runner/export-results!    ← TSV/CSV エクスポート
    openrefine-runner/fetch-csrf-token [...]
    openrefine-runner/format-to-openrefine-format
    openrefine-runner/http-client [...]
  openrefine-runner/open-browser!      ← ブラウザ起動（オプション）
    openrefine-runner/debug
      openrefine-runner/*debug*
  openrefine-runner/format-to-file-extension
```

**読み**: `run-trial` は 9 ステップのオーケストレーター。  
HTTP ユーティリティ（`http-client` / `http-get-text` / `utf8-bytes`）が各ステップで共有されており、ファンイン集計で `utf8-bytes` が最多（6）になる理由がここで明確になる。
