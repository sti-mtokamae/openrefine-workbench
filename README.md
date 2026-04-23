# OpenRefine Workbench

> This repository documents a working setup for running OpenRefine from WSL.
> Some setup steps are intentionally verbose because they capture pitfalls encountered during setup.

OpenRefine を messy data exploration の作業台として使い、試行を trial 単位で管理します。

**特徴**

- OpenRefine project の自動作成
- 試行の再現可能性（trial model）
- WSL / Windows / OpenRefine 連携ワークフロー

---

# 目的

- OpenRefine を Java ソース分析の作業台として使う
- trial ごとに設定 / notes / history を保存
- WSL + Clojure からセッションを自動起動

---

# 設計方針（最小3ステップ）

このリポジトリは、Human REPL と AI Agent が同じ操作面を使う分析基盤として育てます。

1. コマンド契約を最小定義する（`ingest` / `query` / `visualize`）
2. clone 後の入口として、ディレクトリ構造 ingest を先に実装する
3. ingest 結果を REPL で問い合わせ、1つ可視化してループを回す

**XTDB v2 導入時のエッセンス（参考: xt-hledger-lab）**

- 再現可能な実行環境を先に固定する（例: `manifest.scm` + `guix shell -m manifest.scm`）
- ノードのライフサイクルは `with-open` で管理する
- 書き込みは `execute-tx` を基準 API にする

**XTDB v2 実装時の落とし穴（2026-04-24 確認）**

- **JVM フラグが必須** — Apache Arrow の初期化に `--add-opens` が必要。`deps.edn` の `:xtdb` エイリアスに収めてある
  ```bash
  clojure -A:xtdb -M smoke_test.clj trials/samples/repo
  ```
- **SQL では `?` 付き列名を返せない** — `:file/dir?` のような Clojure キーワードは SQL の SELECT 結果に出てこない。全フィールドを取得するには XTQL を使う
  ```clojure
  ;; NG: SQL では dir? が欠落する
  (xt/q node "SELECT * FROM files")
  ;; OK: XTQL なら全フィールドが返る
  (xt/q node '(from :files [*]))
  ```
- **smoke_test.clj でループを一発確認できる** — ingest → query → visualize の3ステップが通れば設計方針の達成とみなす

---

# ディレクトリ構成

```
.
├── bin/
│   ├── run-trial              # CLI ツール実行スクリプト
│   └── start-openrefine.ps1   # Windows 用 OpenRefine 起動スクリプト
├── src/                       # OpenRefine runner (Clojure)
├── bb.edn                     # Babashka タスク定義
└── trials/
    ├── samples/               # 公開可能なサンプル trial
    │   ├── repo/              # サンプル分析対象コード
    │   ├── test-csv-import/   # サンプル trial: CSV インポート
    │   ├── test-trim/         # サンプル trial: テキスト整形
    │   └── 2026-03-06-java-scope-001/  # サンプル trial: Java 分析例
    │
    └── experiments/           # ローカル作業用（.gitignore で除外）
        ├── 2026-03-26-subtotal/           # 実際のプロジェクト分析
        ├── 2026-03-26-subtotal-diff/      # 差分分析
        └── 2026-03-26-test-init/          # 初期化試験
```

**構成方針:**
- `trials/samples/` — 公開リポジトリに含まれる（ツール例・ドキュメント用）
- `trials/experiments/` — `.gitignore` で除外（実際のソースコード含む）
- バージョン更新時も構造は変わらない

---

# Setup

## 1. 事前要件

- **Babashka (bb)**
  Clojure スクリプト実行用
  https://babashka.org/

- **Clojure**
  言語ランタイム
  https://clojure.org/guides/install_clojure

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

# 次のステップ

## 現在の段階（Phase 1: Project 自動作成）

✅ 実装済み：

- trial.edn を共通データモデルで正規化
- OpenRefine への接続確認
- Project の自動作成
- ブラウザの自動起動

**この段階の役割：**
workbench の最初のコネクタとして、trial セッションを OpenRefine で起動する入口を用意

---

## 次の段階（Phase 2: CLI による自動化テスト）

✅ 実装完了：

- `orcli` (OpenRefine Bash CLI) を統合
- `seed-history.json` の自動適用機能
- orcli の `transform` コマンドで複数操作を順序実行
- 実際の trial で動作確認済み

**この段階の構成：**
```
Phase 1: Project 作成 (REST API)
    ↓
Phase 2: Operations 適用 (orcli)
    ↓
Phase 3: Results 出力 (orcli export)
```

**実装詳細：**
- [src/openrefine_runner.clj](src/openrefine_runner.clj) に以下関数を装備
  - `apply-operations!` - orcli transform を実行して seed-history を apply
  - `export-results!` - orcli export で TSV/CSV など形式で出力
  - 環境変数 `OPENREFINE_URL` で OpenRefine 接続先を制御

**試行実績：**
- Trial: `2026-03-06-java-scope-001`
  - 入力: FooController.java, BarService.java
  - 中間: OpenRefine で rename-column 操作
  - 出力: exports/java-scope-001.tsv (101 bytes)
  - 🎉 完全成功

---

## 次の段階（Phase 3: Multi-tool Workbench）

🚀 設計中：

1. **Tablecloth (Clojure) による処理定着**
   - seed-history JSON → Clojure 関数に変換
   - クライアント側で再実行可能に

2. **複数ツール対応**
   - OpenRefine の次のステップ： Tablecloth / code-slice など
   - trial.edn の `:trial/tool` で柔軟に切り替え可能に設計

3. **長期ビジョン**
   - Unix pipes + Lisp data = Simple Made Easy
   - trial → tool chains → reproducible analysis

3. **CLI が足りなければ**
   - `fetch-csrf-token` / `multipart-body` / `create-project!` の API 直叩き実装に降りる
   - CSRF token 処理をきちんと組む

**判断基準：**
- CLI がある → **まずそれを試す**
- CLI で欠ける部分が出る → **その部分だけ API に降りる**
- コードを先に書くのではなく → **可能性を検証してから実装**

---

## 将来の拡張（Phase 3: Workbench の多ツール対応）

🚀 設計目標：

trial.edn は OpenRefine 専用ではなく、workbench の共通セッション記述です。

**拡張の想定：**

```edn
{:trial/id "..."
 :trial/tool :openrefine  ; ← これを切り替え可能に
 ...}
```

↓ 将来は

```edn
{:trial/id "..."
 :trial/tool :tablecloth   ; Clojure で定着化
 :tablecloth/script "scripts/analyze.clj"
 ...}
```

```edn
{:trial/id "..."
 :trial/tool :code-slice   ; code analysis
 :code-slice/query "method-calls"
 ...}
```

これが可能な理由：

- trial.edn の上半分（`:trial/id`, `:goal`, `:input/files`, `:output/dir` など）は tool に依存しない
- 下半分（`:openrefine/url`, `:tablecloth/script` など）は tool ごとの拡張
- runner は `:trial/tool` で backend を分岐

**こうすることで：**

- openrefine-runner が単なる CLI wrapper にならない
- Clojure workbench の中が Unix 的に組み合わさる
- explore (OpenRefine) → stabilize (Tablecloth) の流れが自然に

---

# 設計の本質

> simple made easy を目指す

**OpenRefine の役割：**
- Easy : GUI での直感的な探索・試行錯誤

**Tablecloth / Clojure の役割：**
- Simple : 再実行可能・理解可能なコード化

**trial.edn の役割：**
- 探索セッションをオブジェクト化
- Easy → Simple への橋渡し

**このリポジトリの意図：**

これは

- OpenRefine automation ツール ではなく
- **Clojure data workbench の最初のコネクタ**

つまり、ここから Tablecloth や code-slice が接続されるようになったとき、
`trial.edn` が全体をつなぐ接着剤になる設計です。
