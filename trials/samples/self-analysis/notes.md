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

## next

- `:ref/to` を `clojure.core/*` で除外して、プロジェクト内部呼び出しのみ抽出するクエリ
- `(aggregate {:count (count from)})` でファンアウト集計
- `visualize.clj` に呼び出しグラフ表示（`call-tree`）を追加する可能性
