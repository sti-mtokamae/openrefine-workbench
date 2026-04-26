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

- **Clojure 側 `group-by` パターン**が XTDB `aggregate` より柔軟で使いやすい。`docs/analysis.md` に追記候補
- `normalize-trial`（fanout 18）を `call-tree` で展開するとアーキテクチャの全体像が見えそう
- `find-ancestor` / `opt->val` が複数回表示される問題 → `call-tree` の重複表示を抑制する余地あり
