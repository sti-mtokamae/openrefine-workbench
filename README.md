# OpenRefine Workbench

> Human REPL と AI Agent が同じ操作面を共有する、汎用データ/コード解析ワークベンチ。

---

# 概要

このリポジトリは **2 層構成**のワークベンチです。

| 層 | ツール | 役割 |
|---|---|---|
| **探索層** | OpenRefine | messy data を GUI で手探り。試行は trial 単位で管理 |
| **永続層** | XTDB v2 | ingest → query → visualize のループを Clojure REPL / AI Agent で操作 |

どちらの層も `trial.edn` をセッション記述子として共有し、再現可能な分析を目指します。

---

# 設計方針

**最小 3 ステップの契約**

```
ingest → query → visualize
```

1. `ingest` — ファイルツリーや Clojure cross-reference を XTDB に取り込む
2. `query` — XTQL / SQL でデータを問い合わせる
3. `visualize` — 結果をツリーや表で見る

**Human REPL と AI Agent が同じ操作面を使う**

```clojure
;; REPL からも、AI Agent からも同じ関数を呼ぶだけ
(require '[workbench.core :as core])
(core/start!)
(core/ingest! "trials/samples/repo")   ; ファイルツリー → :files テーブル
(core/xref!   ["src"])                 ; Clojure xref → :refs テーブル
(core/tree)                            ; ツリー表示
(core/q '(from :refs [*]))             ; 任意クエリ
(core/stop!)
```

---

# ディレクトリ構成

```
.
├── bin/
│   ├── init-trial             # trial.edn スケルトン生成
│   ├── run-trial              # OpenRefine trial 実行
│   └── start-openrefine.ps1   # Windows 用 OpenRefine 起動スクリプト
├── src/
│   ├── openrefine_runner.clj  # OpenRefine API クライアント
│   └── workbench/
│       ├── core.clj           # REPL / AI Agent 統合エントリポイント
│       ├── ingest.clj         # dir! / xref! — XTDB への取り込み
│       ├── query.clj          # q — XTDB クエリ薄ラッパー
│       └── visualize.clj      # tree / tree-str — 結果の可視化
├── test/
│   └── smoke_test.clj         # ingest → query → visualize の動作確認
├── bb.edn                     # Babashka タスク定義（ローカル開発用）
├── manifest.scm               # Guix 環境定義（推奨実行環境）
├── deps.edn                   # Clojure 依存定義
└── trials/
    ├── samples/               # 公開可能なサンプル trial
    │   ├── repo/              # サンプル分析対象コード
    │   ├── test-csv-import/   # サンプル trial: CSV インポート
    │   ├── test-trim/         # サンプル trial: テキスト整形
    │   └── 2026-03-06-java-scope-001/  # サンプル trial: Java 分析例
    └── experiments/           # ローカル作業用（.gitignore で除外）
```

**構成方針:**
- `trials/samples/` — 公開リポジトリに含まれる（ツール例・ドキュメント用）
- `trials/experiments/` — `.gitignore` で除外（実際のソースコード含む）

---

---

# クイックスタート

## A. XTDB ワークベンチ（Clojure REPL）

```bash
# Guix 環境で nREPL 起動
guix shell -m manifest.scm -- clojure -A:xtdb:repl
```

```clojure
;; REPL から
(require '[workbench.core :as core])

(core/start!)                          ; XTDB ノード起動

(core/ingest! "trials/samples/repo")  ; ファイルツリーを :files テーブルへ
;; => 7

(core/tree)                            ; ツリー表示
;; src/
;;   main/
;;     java/
;;       com/
;;         example/
;;           BarService.java
;;           FooController.java

(core/xref! ["src"])                   ; Clojure cross-reference を :refs テーブルへ
;; => 500

(core/q '(from :refs [{:ref/from from :ref/to to :ref/kind kind}]
               (limit 3)))             ; 任意クエリ

(core/stop!)                           ; ノード停止
```

**smoke test（動作確認）:**

```bash
guix shell -m manifest.scm -- clojure -A:xtdb -M test/smoke_test.clj trials/samples/repo
```

## B. OpenRefine trial（GUI 探索）

OpenRefine が起動済みの前提で：

```bash
# サンプル trial を実行
./bin/run-trial trials/samples/test-csv-import/trial.edn

# 新しい trial を初期化
./bin/init-trial --trial-id "my-analysis" --pattern "*.java"
./bin/run-trial trials/experiments/my-analysis/trial.edn
```

OpenRefine の起動手順は後述の [Setup](#setup) を参照。

---

# XTDB ワークベンチ詳細

## workbench.core API

| 関数 | 説明 |
|---|---|
| `(core/start!)` | XTDB ノードを起動（インメモリ） |
| `(core/stop!)` | ノードを停止 |
| `(core/ingest! root)` | ディレクトリ以下を `:files` テーブルへ |
| `(core/xref! paths)` | Clojure ソースを解析して `:refs` テーブルへ |
| `(core/q xtql)` | XTQL / SQL クエリを実行 |
| `(core/tree)` | `:files` テーブルをツリー表示（stdout） |
| `(core/tree-str)` | `tree` の文字列版（AI Agent / テスト向け） |

## テーブルスキーマ

**`:files` テーブル**（`ingest!` で投入）

| フィールド | 説明 |
|---|---|
| `:xt/id` | ファイルパス（文字列） |
| `:file/path` | パス |
| `:file/name` | ファイル名 |
| `:file/dir?` | ディレクトリなら true |

**`:refs` テーブル**（`xref!` で投入）

| フィールド | 説明 |
|---|---|
| `:xt/id` | `from->to@file:line` |
| `:ref/from` | 呼び出し元シンボル（文字列） |
| `:ref/to` | 呼び出し先シンボル（文字列） |
| `:ref/kind` | `:call` / `:reference` / `:macroexpand` など（文字列） |
| `:ref/file` | ソースファイルパス |
| `:ref/line` / `:ref/col` | 位置情報 |
| `:ref/arity` | 引数の数 |

## XTDB v2 実装時の落とし穴

- **JVM フラグが必須** — Apache Arrow の初期化に `--add-opens` が必要。`deps.edn` の `:xtdb` エイリアスに収めてある

- **SQL では `?` 付き列名を返せない** — `:file/dir?` のような Clojure キーワードは SQL の SELECT 結果に出てこない。XTQL を使う
  ```clojure
  ;; NG: SQL では dir? が欠落する
  (xt/q node "SELECT * FROM files")
  ;; OK: XTQL なら全フィールドが返る
  (xt/q node '(from :files [*]))
  ```

- **XTDB に保存できる型** — `clojure.lang.Symbol` は直接保存不可。`(str sym)` で文字列に変換すること

---

# Setup

## 1. 事前要件

推奨：**Guix（環境を完全に固定したい場合）**
```bash
guix shell -m manifest.scm
```

代替（手動セットアップ）：

- **Clojure**
  言語ランタイム
  https://clojure.org/guides/install_clojure

- **OpenJDK 21+**
  Java ランタイム
  https://openjdk.org/

- **Babashka (bb) [任意]**
  JVM 起動コスト削減時のみ（ローカル開発用）
  https://babashka.org/

- **GitHub CLI (gh) [任意]**
  GitHub 連携（issue / PR / repo 操作）を CLI で行う場合に使用
  https://cli.github.com/

---

## 2. orcli CLI ツールをダウンロード

OpenRefine の自動化に必要な `orcli` をダウンロードして `bin/` ディレクトリに配置：

```bash
cd openrefine-workbench
curl -sSfL https://raw.githubusercontent.com/opencultureconsulting/orcli/main/orcli -o bin/orcli
chmod +x bin/orcli
```

---

## 3. Windows 側: OpenRefine 起動

### PowerShell スクリプト使用（推奨）

`bin/start-openrefine.ps1` を OpenRefine インストールフォルダにコピーして実行：

```powershell
# このリポジトリから start-openrefine.ps1 をコピー
Copy-Item bin\start-openrefine.ps1 C:\Users\mtoka\usrapp\openrefine-3.9.5\

# OpenRefine フォルダに移動して実行
cd C:\Users\mtoka\usrapp\openrefine-3.9.5
.\start-openrefine.ps1
```

**スクリプトが自動で行うこと：**
- PATH から Java を自動検出
- JAVA_HOME を動的に設定
- OpenRefine を 0.0.0.0:3333 で起動（WSL からアクセス可能に）
- 接続先 URL を表示

**バージョン更新時：**
新しい OpenRefine ディレクトリに `start-openrefine.ps1` をコピーすれば同じように実行可能です。

#### ファイルロック解除（初回実行時）

Git clone したファイルを実行する場合、Windows がファイルをロックしていることがあります。その場合は `Unblock-File` で解除してください：

```powershell
Unblock-File -Path .\start-openrefine.ps1
```

#### PowerShell 実行ポリシーエラー

実行ポリシーが厳しい場合のエラーが出た場合：

```powershell
Set-ExecutionPolicy -ExecutionPolicy RemoteSigned -Scope CurrentUser
```

その後、スクリプトを実行：

```powershell
.\start-openrefine.ps1
```

**実行成功時の出力例：**

```
=========================================

  OpenRefine Remote Start Script

=========================================

✓ Detected Java: C:\Program Files\Eclipse Adoptium\jdk-25.0.1.8-hotspot\bin\java.exe
✓ Setting JAVA_HOME: C:\Program Files\Eclipse Adoptium\jdk-25.0.1.8-hotspot

Starting OpenRefine...

  Binding: 0.0.0.0:3333
  WSL Access: http://172.27.160.1:3333

=========================================
```

### 手動で起動する場合（参考）

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-25.0.1.8-hotspot"
cd C:\Users\mtoka\usrapp\openrefine-3.9.5
.\refine.bat /i 0.0.0.0 run
```

### なぜこの設定が必要か

OpenRefine はデフォルトでは localhost のみで起動します。WSL から接続する場合は `/i 0.0.0.0` オプションが必須です。

### よくあるミス

- ❌ `openrefine.exe` を使う（refine.bat を使用すること）
- ❌ `JAVA_HOME` を設定しない
- ❌ `/i 0.0.0.0` オプションなしで起動

---

## 4. WSL 側: 接続確認

OpenRefine に接続できるか確認：

```bash
# WSL のゲートウェイアドレス確認
ip route show | grep default
# 結果例: default via 172.27.160.1 dev eth0 proto kernel

# 接続テスト
curl http://172.27.160.1:3333/
```

---

# クイックスタート

## サンプル trial を実行

```bash
# サンプル trial: CSV インポート
./bin/run-trial trials/samples/test-csv-import/trial.edn

# サンプル trial: Java ソース分析
./bin/run-trial trials/samples/2026-03-06-java-scope-001/trial.edn
```

## 新しい trial を作成（ローカル作業用）

```bash
# experiments フォルダに新規作業ディレクトリ作成
# このフォルダは .gitignore で除外されているため、
# 実際のソースコードを含めても公開されません
./bin/run-trial trials/experiments/YOUR-TRIAL-ID/trial.edn
```

### 実行時の流れ

**Phase 1: Project 自動作成**
- OpenRefine 接続確認
- CSRF トークン取得
- Java ファイルをアップロード
- プロジェクト作成
- ブラウザ自動オープン

**Phase 2: Operations 自動適用** *(seed-history.json がある場合)*
- `orcli transform` で seed-history を実行
- 複数操作を順序実行

**Phase 3: Results 自動出力** *(output/dir が設定の場合)*
- `orcli export` で TSV/CSV 形式に変換
- exports/ ディレクトリに保存

### 成功時の出力例

```
trial: 2026-03-06-java-scope-001
tool: :openrefine
goal: class/method scope extraction

Phase 1: Project created
project-id: 2563553952142
project-url: http://172.27.160.1:3333/project?project=2563553952142
opening browser via wslview ...
[DEBUG] Browser opened successfully

Phase 2: Applying operations...
applying: trials/2026-03-06-java-scope-001/seed-history.json
[DEBUG] orcli transform stderr: transformed 2563553952142 with rename-column
Response: カラム 1 を src に名前変更

Phase 3: Exporting results...
exporting to: trials/2026-03-06-java-scope-001/exports/java-scope-001.tsv

✓ Trial completed successfully
```

### このリポジトリのワークフロー

This repository follows a Unix-style workflow:

```
trial.edn
    ↓
./bin/run-trial
    ↓
OpenRefine project 自動作成
    ↓
ブラウザで開く
    ↓
データ探索・変換（手動操作）
    ↓
notes.md に結果を記録
```

---

# Trial の初期化

## ワークフロー

trial の作成と実行は以下の流れで行います：

```
1. trial.edn スケルトンを自動生成
    ↓
2. trial.edn で :goal と :trial/tags を編集
    ↓
3. trial を実行
```

## Step 1: trial.edn スケルトンを自動生成

### パターン検索ベース

**複数のパターンで Java ファイルを検索：**

```bash
./bin/init-trial --trial-id "2026-03-26-mapper-study" \
  --pattern "*Mapper.java" \
  --pattern "*ServiceImpl.java"
```

### git diff ベース

**git diff からファイルを自動抽出：**

```bash
./bin/init-trial --trial-id "2026-03-26-subtotal-analysis" \
  --git-repo "../tradehub-web-backend_0107" \
  --git-range "develop..sonnt-4232" \
  --include-diff
```

**差分を詳細テーブル形式でも取得：**

```bash
./bin/init-trial --trial-id "2026-03-26-subtotal-analysis" \
  --git-repo "../tradehub-web-backend_0107" \
  --git-range "develop..sonnt-4232" \
  --include-diff \
  --include-stat
```

オプション `--include-stat` を指定すると、`git diff --stat` の表形式もファイルとして生成されます。

実行結果：

```
✓ Trial initialized: trials/2026-03-26-subtotal-analysis
  ├── trial.edn        (edit :goal and :trial/tags)
  ├── notes.md         (edit observations)
  ├── develop..sonnt-4232.diff          ; ← フルテキスト差分
  ├── develop..sonnt-4232.stat          ; ← 変更統計テーブル (--include-stat のみ)
  └── exports/         (results directory)

Next step:
  1. Edit: vim trials/2026-03-26-subtotal-analysis/trial.edn
  2. Run:  ./bin/run-trial trials/2026-03-26-subtotal-analysis/trial.edn
```

**diff.stat ファイルの中身例：**

```
 .../web/common/config/WebSecurityConfig.java       |   1 +
 .../service/impl/DocumentsServiceImpl.java         | 249 +++++++-
 .../subtotal/mapper/DocumentGenerateMapper.java    | 314 ++++++++++
 .../mapper/SourceProjectAggregateMapper.java       | 649 +++++++++++++++++++++
 ...
 18 files changed, 1693 insertions(+), 18 deletions(-)
```

**生成される trial.edn 例：**

```edn
{:trial/id "2026-03-26-subtotal-analysis"
 :trial/tool :openrefine
 :trial/tags []  ; TODO: 分類タグを追加

 :input/files
 ["../../tradehub-web-backend_0107/common-app/.../WebSecurityConfig.java"
  "../../tradehub-web-backend_0107/common-lib/.../DocumentsServiceImpl.java"
  ...
  "develop..sonnt-4232.diff"   ; 差分ファイル
  "develop..sonnt-4232.stat"]  ; 統計テーブル (--include-stat の場合)

 :goal ""  ; TODO: trial の目的を説明

 :notes/file "notes.md"
 :output/dir "exports"

 :openrefine/url "http://172.27.160.1:3333"
 :openrefine/project-name "2026-03-26-subtotal-analysis"
 :openrefine/open-browser? true}
```

**オプション説明**

| オプション | 説明 |
|-----------|------|
| `--trial-id ID` | Trial の一意な ID （必須） |
| `--git-repo PATH` | Git リポジトリパス（デフォルト: `trials/repo`） |
| `--git-range RANGE` | Git の範囲（例: `develop..sonnt-4232`） |
| `--pattern GLOB` | ファイルパターン（複数指定可） |
| `--include-diff` | git diff の完全テキストファイルを生成 |
| `--include-stat` | git diff --stat の表形式ファイルを生成 |

---

## Step 2: trial.edn を編集

```bash
vim trials/2026-03-26-subtotal-analysis/trial.edn
```

最小限編集が必要な項目：

- `:goal` - この trial で何を検証するか
- `:trial/tags` - 分類タグ（例: `[:java :mapper :service]`）

---

## Step 3: trial を実行

```bash
./bin/run-trial trials/2026-03-26-subtotal-analysis/trial.edn
```

---

# Trial の理解

## Trial ディレクトリ構成

各 trial は独立したディレクトリを持ちます：

```
trials/YOUR-TRIAL-ID/
  ├── trial.edn              # 試行設定（必須）
  ├── notes.md               # 試行の記録・メモ
  ├── seed-history.json      # (オプション) OpenRefine の操作履歴
  └── exports/               # 結果出力ディレクトリ
```

| ファイル | 説明 |
|---------|------|
| `trial.edn` | trial セッション定義 |
| `notes.md` | 試行の観察記録・メモ |
| `seed-history.json` | OpenRefine operations history（オプション） |
| `exports/` | 出力結果ディレクトリ |

---

## trial.edn

trial セッションを再現する descriptor。

簡潔に言うと：**trial.edn = session descriptor**

### テンプレート例

```edn
{:trial/id "2026-03-06-java-scope-001"
 :trial/tool :openrefine
 :trial/tags [:java :spring :scope]

 :input/files
 ["../repo/src/main/java/com/example/FooController.java"
  "../repo/src/main/java/com/example/BarService.java"]

 :goal "class/method scope extraction"

 :notes/file "notes.md"
 :output/dir "exports"

 :seed/files ["seed-history.json"]

 :openrefine/url "http://172.27.160.1:3333"
 :openrefine/project-name "java-scope-001"
 :openrefine/open-browser? true}
```

### 各フィールドの説明

| キー | 説明 |
|-----|------|
| `:trial/id` | 試行の一意な ID (ディレクトリ名と同じにする) |
| `:trial/tool` | 使用ツール (今は `:openrefine`、将来は `:tablecloth` など) |
| `:trial/tags` | trial の分類タグ |
| `:input/files` | 分析対象の Java ファイル (相対パス) |
| `:goal` | この trial で何を検証するか |
| `:notes/file` | trial の記録ファイル |
| `:output/dir` | 結果出力先ディレクトリ |
| `:seed/files` | (オプション) OpenRefine 操作履歴。trial 再実行時のテンプレート |
| `:openrefine/url` | OpenRefine のアクセスURL (`http://172.27.160.1:3333`) |
| `:openrefine/project-name` | OpenRefine 内のプロジェクト名 |
| `:openrefine/open-browser?` | 実行後ブラウザを自動で開くか (true/false) |

---

## seed-history.json

OpenRefine では操作履歴を JSON として export できます。

```
Undo / Redo → Extract → JSON
```

これを `seed-history.json` として保存すると、

```
trial.edn
    ↓
project 作成 (Phase 1)
    ↓
operations 適用 (Phase 2 - orcli)
    ↓
results 出力 (Phase 3 - orcli)
```

という形で trial の再現が可能になります。

**実装状況：**
✅ Phase 2 で `orcli transform` を使って自動適用
- 複数の操作を順序実行可能
- エラーハンドリング済み

---

## notes.md

trial ごとの実験記録です。

**役割：**
- **goal** - 試行の目的（trial.edn から参照）
- **observation** - 実行結果・観察内容を記載
- **next** - 次のステップの計画を記載

### 記載例

```markdown
# java-scope-001

## goal
class / method scope extraction

## observation
- FooController.java の 7 行を OpenRefine で読み込み成功
- メソッド foo() を検出
- クラス構造の確認完了

## next
- GREL で Java スコープ抽出ルール定義
- 結果を CSV で出力
- BarService.java を別 trial で処理
```

---

## 入力ファイルの準備

`trials/repo/` は分析対象のコードベースです。

通常は Git リポジトリを clone して配置します：

```bash
cd trials/repo
git clone https://github.com/your-org/your-java-project.git .
```

構造例：
```
trials/repo/
  src/main/java/com/example/
    ├── FooController.java
    ├── BarService.java
    └── ...
```

Java ファイルが揃ったら、新しい trial ディレクトリを作成して `trial.edn` で指定します。

---

# Trial Lifecycle

trial は 2 つのフェーズで構成されます：

```
trial.edn (session descriptor)
    ↓
[explore phase]
    ↓ OpenRefine project 自動作成
    ↓ GUI で手動探索・変換
    ↓ notes.md に観察記録
    ↓
operations.json (seed-history)
    ↓
[stabilize phase]
    ↓ Clojure / Tablecloth でコード化
    ↓ 再実行可能な処理に定着
    ↓ exports/ に結果出力
```

**フェーズの意味：**

- **explore** : OpenRefine で GUI を使った直感的な探索（Easy）
- **stabilize** : Clojure / Tablecloth でコードによる再実行可能化（Simple）

**trial.edn の役割** : この 2 フェーズの橋渡し

---

# 現在の実装状況

## XTDB ワークベンチ

| 機能 | 状態 |
|---|---|
| `ingest!` — ファイルツリー → `:files` | ✅ 実装済み |
| `xref!` — Clojure cross-reference → `:refs` | ✅ 実装済み |
| `q` — XTQL クエリ | ✅ 実装済み |
| `tree` / `tree-str` — ツリー表示 | ✅ 実装済み |
| `core.clj` — REPL / AI Agent エントリポイント | ✅ 実装済み |

## OpenRefine trial ワークフロー

| 機能 | 状態 |
|---|---|
| `run-trial` — project 自動作成 + ブラウザ起動 | ✅ 実装済み |
| `orcli transform` — seed-history 自動適用 | ✅ 実装済み |
| `orcli export` — TSV/CSV 出力 | ✅ 実装済み |
| `init-trial` — trial.edn スケルトン生成 | ✅ 実装済み |

