# OpenRefine Workbench

> Human REPL と AI Agent が同じ操作面を共有する、汎用データ/コード解析ワークベンチ。

---

## 概要

2 層構成のワークベンチです。

| 層 | ツール | 役割 |
|---|---|---|
| **探索層** | OpenRefine | messy data を GUI で手探り。試行は trial 単位で管理 |
| **永続層** | XTDB v2 | ingest → query → visualize のループを Clojure REPL / AI Agent で操作 |

どちらの層も `trial.edn` をセッション記述子として共有し、再現可能な分析を目指します。

---

## 設計方針

**最小 3 ステップの REPL ループ**

```
ingest → query → visualize
```

**Human REPL と AI Agent が同じ操作面を使う**

```clojure
(require '[workbench.core :as core])

(core/start!)                          ; デフォルト: .xtdb/ に永続化
(core/ingest! "src")                   ; ファイルツリー → :files テーブル
(core/xref!   ["src"])                 ; Clojure xref  → :refs テーブル
(core/xref!   ["src"] :trial "t1")    ; trial スコープ付きで同期
(core/jref!   ["trials/samples/repo"]) ; Java xref     → :refs テーブル
(core/tree)                            ; ツリー表示
(core/q '(from :refs [*]))             ; 任意クエリ
(core/stop!)
```

---

## ディレクトリ構成

```
.
├── bin/
│   ├── analyze                # コード解析ショートカット（1コマンド完結）
│   ├── init-trial             # trial.edn スケルトン生成
│   ├── run-trial              # OpenRefine trial 実行
│   └── start-openrefine.ps1   # Windows 用 OpenRefine 起動スクリプト
├── src/
│   ├── openrefine_runner.clj  # OpenRefine API クライアント
│   └── workbench/
│       ├── core.clj           # REPL / AI Agent 統合エントリポイント
│       ├── ingest.clj         # dir! / xref! — XTDB への取り込み
│       ├── jref.clj           # jref!  — Java xref 解析（JavaParser）
│       ├── query.clj          # q      — XTDB クエリ薄ラッパー
│       └── visualize.clj      # tree / tree-str — 結果の可視化
├── test/
│   └── smoke_test.clj
├── docs/
│   ├── analysis.md            # Java/Clojure 解析 end-to-end・クエリ例
│   ├── api.md                 # workbench.core API・スキーマ詳細
│   ├── trial.md               # Trial ワークフロー詳細
│   └── setup.md               # 環境セットアップ詳細
├── manifest.scm               # Guix 環境定義（推奨実行環境）
├── deps.edn                   # Clojure 依存定義
└── trials/
    ├── samples/               # 公開可能なサンプル trial
    └── experiments/           # ローカル作業用（.gitignore で除外）
```

---

## クイックスタート

### A. XTDB ワークベンチ（Clojure REPL）

```bash
# Guix 環境で nREPL 起動
guix shell -m manifest.scm -- clojure -A:xtdb:repl
```

```clojure
(require '[workbench.core :as core])

(core/start!)                          ; デフォルト: .xtdb/ に永続化
(core/ingest! "src")                   ; => 5
(core/xref!   ["src"])                 ; => 500（Clojure xref）
(core/xref!   ["src"] :trial "t1")    ; trial スコープ付きで同期
(core/jref!   ["trials/samples/repo"]) ; => 1（Java xref）
(core/tree)
(core/q '(from :refs [*] (limit 3)))

;; メトリクス
(core/fan-out)                     ; 依存数降順
(core/fan-in)                      ; 被依存数降順
(core/hotspots)                    ; fan-in 上位 10
(core/hotspots 5)                  ; 上位 5
(core/stop!)
```

**`bin/analyze` — 1 コマンド完結**

```bash
# Clojure + Java 両方を解析（デフォルト）
bin/analyze src/

# trial スコープ付き・Java のみ・hotspots 上位 5
bin/analyze trials/samples/repo --trial my-trial --lang java --top 5
```

smoke test:

```bash
guix shell -m manifest.scm -- clojure -A:xtdb -M test/smoke_test.clj trials/samples/repo
```

### B. OpenRefine trial（GUI 探索）

```bash
# trial を初期化
./bin/init-trial --trial-id "my-analysis" --pattern "*.java"

# trial を実行
./bin/run-trial trials/experiments/my-analysis/trial.edn

# サンプルを実行
./bin/run-trial trials/samples/test-csv-import/trial.edn
```

---

## ドキュメント

| ドキュメント | 内容 |
|---|---|
| [docs/analysis.md](docs/analysis.md) | Java/Clojure 解析 end-to-end・クエリ例・限界 |
| [docs/api.md](docs/api.md) | workbench.core API・テーブルスキーマ・XTDB 落とし穴 |
| [docs/trial.md](docs/trial.md) | Trial ワークフロー・trial.edn・seed-history・init-trial |
| [docs/setup.md](docs/setup.md) | 事前要件・orcli・OpenRefine Windows 起動・WSL 接続 |

---

## 実装状況

### XTDB ワークベンチ

| 機能 | 状態 |
|---|---|
| `ingest!` — ファイルツリー → `:files` | ✅ |
| `xref!` — Clojure cross-reference → `:refs` | ✅ |
| `jref!` — Java cross-reference → `:refs`（JavaParser） | ✅ |
| `q` — XTQL クエリ | ✅ |
| `tree` / `tree-str` — ツリー表示 | ✅ |
| `refs` — 内部呼び出しグラフ（ノイズフィルタ付き） | ✅ |
| `call-tree` / `call-tree-str` — 呼び出し木表示 | ✅ |
| `fan-out` / `fan-in` / `hotspots` — 依存メトリクス | ✅ |
| `bin/analyze` — 解析ショートカット CLI | ✅ |

### OpenRefine trial ワークフロー

| 機能 | 状態 |
|---|---|
| `run-trial` — project 自動作成 + ブラウザ起動 | ✅ |
| `orcli transform` — seed-history 自動適用 | ✅ |
| `orcli export` — TSV/CSV 出力 | ✅ |
| `init-trial` — trial.edn スケルトン生成 | ✅ |
