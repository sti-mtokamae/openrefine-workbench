# Trial ワークフローリファレンス

## Trial とは

分析ツールを使った探索セッションの単位。`trial.edn` をセッション記述子として、再現可能な分析を実現する。

対応ツール：
- **`:openrefine`** — OpenRefine GUI を使った探索・変換
- **`:xtdb-workbench`** — XTDB + JavaParser による静的解析・テスト生成

---

## Trial Lifecycle

### :openrefine

```
trial.edn
    ↓ ./bin/run-trial trial.edn
[explore phase]
    ↓ OpenRefine project 自動作成
    ↓ GUI で手動探索・変換
    ↓ notes.md に観察記録
    ↓
seed-history.json
    ↓
[stabilize phase]
    ↓ Clojure / Tablecloth でコード化
    ↓ 再実行可能な処理に定着
    ↓ exports/ に結果出力
```

### :xtdb-workbench

```
trial.edn
    ↓ REPL または analyze.clj
[ingest phase]
    ↓ jref! / jsig! で Java 呼び出しグラフを XTDB へ投入
    ↓ jacoco! でカバレッジ情報を投入（任意）
    ↓
[analyze phase]
    ↓ query / hotspots / uncovered-sql-methods 等で分析
    ↓ notes.md に観察記録
    ↓
[generate phase]
    ↓ gen-tests-uncovered でテストスケルトンを生成
    ↓ exports/ に出力
```

---

## ディレクトリ構成

```
trials/YOUR-TRIAL-ID/
  ├── trial.edn              # 試行設定（必須）
  ├── notes.md               # 試行の記録・メモ
  ├── analyze.clj            # (オプション) XTDB workbench 用解析スクリプト
  ├── seed-history.json      # (オプション) OpenRefine の操作履歴
  └── exports/               # 結果出力ディレクトリ
```

- `trials/samples/` — 公開リポジトリに含まれる（ツール例・ドキュメント用）
- `trials/experiments/` — `.gitignore` で除外（実際のソースコード含む）

---

## Step 1: trial.edn スケルトンを生成

### パターン検索ベース

```bash
./bin/init-trial --trial-id "2026-03-26-mapper-study" \
  --pattern "*Mapper.java" \
  --pattern "*ServiceImpl.java"
```

### git diff ベース

```bash
./bin/init-trial --trial-id "2026-03-26-subtotal-analysis" \
  --git-repo "../tradehub-web-backend_0107" \
  --git-range "develop..sonnt-4232" \
  --include-diff \
  --include-stat
```

**オプション一覧**

| オプション | 説明 |
|---|---|
| `--trial-id ID` | Trial の一意な ID（必須） |
| `--git-repo PATH` | Git リポジトリパス（デフォルト: `trials/repo`） |
| `--git-range RANGE` | Git の範囲（例: `develop..sonnt-4232`） |
| `--pattern GLOB` | ファイルパターン（複数指定可） |
| `--include-diff` | git diff の完全テキストファイルを生成 |
| `--include-stat` | git diff --stat の表形式ファイルを生成 |

**実行結果：**

```
✓ Trial initialized: trials/2026-03-26-subtotal-analysis
  ├── trial.edn
  ├── notes.md
  ├── develop..sonnt-4232.diff
  ├── develop..sonnt-4232.stat
  └── exports/

Next step:
  1. Edit: $EDITOR trials/2026-03-26-subtotal-analysis/trial.edn
  2. Run:  ./bin/run-trial trials/2026-03-26-subtotal-analysis/trial.edn
```

---

## Step 2: trial.edn を編集

```bash
$EDITOR trials/YOUR-TRIAL-ID/trial.edn
```

最低限編集が必要な項目：
- `:goal` — この trial で何を検証するか
- `:trial/tags` — 分類タグ（例: `[:java :mapper :service]`）

### trial.edn スキーマ — :openrefine

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

| キー | 説明 |
|---|---|
| `:trial/id` | 試行の一意な ID（ディレクトリ名と同じにする） |
| `:trial/tool` | `:openrefine` |
| `:trial/tags` | 分類タグ |
| `:input/files` | 分析対象ファイル（相対パス） |
| `:goal` | この trial で何を検証するか |
| `:seed/files` | (オプション) OpenRefine 操作履歴 |
| `:openrefine/url` | OpenRefine のアクセス URL |
| `:openrefine/project-name` | OpenRefine 内のプロジェクト名 |
| `:openrefine/open-browser?` | 実行後ブラウザを自動で開くか |

### trial.edn スキーマ — :xtdb-workbench

```edn
{:trial/id   "2026-04-28-tradehub"
 :trial/tool :xtdb-workbench
 :trial/tags [:java :spring-boot :migration]

 ;; Java ソースのみ jref!/jsig! 対象
 :input/java-roots
 ["trials/experiments/2026-04-28-tradehub/repo/common-lib"
  "trials/experiments/2026-04-28-tradehub/repo/common-app"]

 ;; Clojure ソースのみ xref! 対象（任意）
 :input/clj-roots
 ["trials/experiments/2026-04-28-tradehub/repo/clojure"]

 :goal "マイクロサービス分解に向けた影響分析"

 :notes/file "notes.md"
 :output/dir "exports"}
```

| キー | 説明 |
|---|---|
| `:trial/tool` | `:xtdb-workbench` |
| `:input/java-roots` | jref!/jsig! の対象ディレクトリ（複数可） |
| `:input/clj-roots` | xref! の対象ディレクトリ（任意） |
| `:input/roots` | Java/Clojure 両方を含む場合の簡略指定 |

---

## Step 3: trial を実行

### :openrefine

```bash
./bin/run-trial trials/YOUR-TRIAL-ID/trial.edn
```

**実行フェーズ：**

1. **Phase 1**: OpenRefine 接続確認 → project 自動作成 → ブラウザオープン
2. **Phase 2**: `seed-history.json` があれば `orcli transform` で自動適用
3. **Phase 3**: `output/dir` が設定されていれば `orcli export` で TSV/CSV 出力

### :xtdb-workbench

```bash
# REPL で対話実行（推奨）
guix shell -m manifest.scm -- clojure -A:xtdb:repl
```

```clojure
;; REPL 内
(require '[workbench.core :as core])
(core/start!)
(core/jref!  ["trials/experiments/YOUR-TRIAL-ID/repo"])
(core/jsig!  ["trials/experiments/YOUR-TRIAL-ID/repo"])
(core/gen-tests-uncovered "YOUR-TRIAL-ID")
```

またはスクリプトで実行：

```bash
guix shell -m manifest.scm -- clojure -A:xtdb -M trials/experiments/YOUR-TRIAL-ID/analyze.clj
```

---

## seed-history.json

OpenRefine の操作履歴を JSON として export したもの。

```
Undo / Redo → Extract → JSON
```

これを `seed-history.json` として保存すると trial の再実行が可能になる。

---

## notes.md

trial ごとの実験記録。

```markdown
# java-scope-001

## goal
class / method scope extraction

## observation
- FooController.java の 7 行を OpenRefine で読み込み成功
- メソッド foo() を検出

## next
- GREL で Java スコープ抽出ルール定義
- 結果を CSV で出力
```

---

## 入力ファイルの準備

`trials/repo/` は分析対象のコードベース置き場。通常は Git リポジトリを clone して配置する。

```bash
cd trials/repo
git clone https://github.com/your-org/your-java-project.git .
```
