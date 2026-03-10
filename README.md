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

# ディレクトリ構成

```
.
├── bin/run-trial          # 実行スクリプト
├── src/                   # OpenRefine runner (Clojure)
├── bb.edn                 # Babashka タスク定義
└── trials/                # trial ごとの作業ディレクトリ
    ├── repo/              # 分析対象のコードベース（Git clone 推奨）
    └── YOUR-TRIAL-ID/     # 個別の試行ディレクトリ
```

---

# Setup

## 1. 事前要件

- **Babashka (bb)**
  Clojure スクリプト実行用
  https://babashka.org/

- **Clojure**
  言語ランタイム
  https://clojure.org/guides/install_clojure

---

## 2. Windows 側: OpenRefine 起動

WSL からアクセスするため、OpenRefine を **0.0.0.0 で起動**します。

```powershell
$env:JAVA_HOME="C:\Users\mtoka\usrapp\openrefine-3.9.5\server\target\jre"
cd C:\Users\mtoka\usrapp\openrefine-3.9.5
.\refine.bat /i 0.0.0.0 run
```

### なぜこの設定が必要か

OpenRefine はデフォルトでは localhost のみで起動します。WSL から接続する場合は `/i 0.0.0.0` オプションが必須です。

### 注意

以下の方法では WSL から接続できません：

- ❌ `openrefine.exe` を使う
- ❌ `JAVA_HOME` を設定しない

必ず `refine.bat /i 0.0.0.0 run` で起動してください。

---

## 3. WSL 側: 接続確認

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

## 最初の trial を実行

```bash
./bin/run-trial trials/2026-03-06-java-scope-001/trial.edn
```

### 実行時の流れ

1. OpenRefine に接続確認
2. CSRF トークン取得
3. Java ファイルをアップロード
4. 新しいプロジェクトを作成
5. ブラウザで自動的に開く

### 成功時の出力例

```
trial: 2026-03-06-java-scope-001
openrefine: http://172.27.160.1:3333
[DEBUG] Reachable: fetching CSRF token...
[DEBUG] CSRF token: yhPZ7BOudDLKz95nuLK7...
[DEBUG] Creating project...
project-id: 2398035680029
project-url: http://172.27.160.1:3333/project?project=2398035680029
opening browser via wslview ...
[DEBUG] Browser opened successfully
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
project 作成
    ↓
operations 適用
```

という形で trial の再現が可能になります。

**現在の runner では seed の自動適用はまだ実装していませんが、** 将来的には `apply-operations` API を使って再実行可能にする予定です。

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

🔧 次にやるべき：

1. **OpenRefine CLI の検証**
   - `orcli` または `openrefine-client` で Windows/WSL 間の動作確認
   - 以下を確認：
     - `import` - ファイル投入が通るか
     - `apply-operations` - seed history の適用が通るか
     - `export` - 結果出力が通るか

2. **CLI が足りるなら**
   ```
   trial.edn → CLI で投入 → 出力ファイル
   ```
   この軽量な構成で OpenRefine automation を完結できる

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
