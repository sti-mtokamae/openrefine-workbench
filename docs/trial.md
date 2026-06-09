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
  ↓ :ingest/jacoco（jacoco-xml 既存取り込み or module-dir から生成して取り込み）
    ↓
[analyze phase]
    ↓ query / hotspots / uncovered-sql-methods 等で分析
    ↓ notes.md に観察記録
    ↓
[generate phase]
    ↓ gen-tests-uncovered でテストスケルトンを生成
    ↓ merge-all-test-mds で Test.java に統合
    ↓ exports/ に出力
    ↓
[testfix phase]
    ↓ [:testfix/fix-bucket] で コンパイルエラーを AI 修正
    ↓ 修正済みテストを再出力
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

 ;; Maven classpath 自動生成設定（bin/run-trial 実行時に処理）
 :maven/classpath-config
 {:repo-root "trials/experiments/2026-04-28-tradehub/repo"
  :module "common-lib"
  :scope "test"
  :output-file "/tmp/tradehub-common-lib-gen-tests.classpath"
  :target-classes-append "trials/experiments/2026-04-28-tradehub/repo/common-lib/target/classes"}

 :goal "マイクロサービス分解に向けた影響分析"

 :notes/file "notes.md"
 :output/dir "exports"
```

| キー | 説明 |
|---|---|
| `:trial/tool` | `:xtdb-workbench` |
| `:input/java-roots` | jref!/jsig! の対象ディレクトリ（複数可） |
| `:input/clj-roots` | xref! の対象ディレクトリ（任意） |
| `:input/roots` | Java/Clojure 両方を含む場合の簡略指定 |
| `:maven/classpath-config` | (任意) Maven 依存関係解決設定（テストコンパイル時に必要） |
| `:maven/classpath-config.repo-root` | Maven プロジェクトのルートディレクトリ |
| `:maven/classpath-config.module` | 対象 Maven モジュール（複数モジュール構成の場合） |
| `:maven/classpath-config.scope` | 依存スコープ（通常は `"test"`） |
| `:maven/classpath-config.output-file` | 生成したクラスパスファイルの出力先（/tmp 推奨） |
| `:maven/classpath-config.target-classes-append` | プロジェクトの target/classes パスを末尾に追加 |

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

;; 1. テストスケルトン生成（未カバー×SQL縛り）
(core/gen-tests-uncovered "YOUR-TRIAL-ID")

;; 2. .md → Test.java に統合
(core/merge-all-test-mds "YOUR-TRIAL-ID")

;; 3. コンパイルエラーを AI で修正（mvn test-compile 後に実施）
(core/fix-tests-dir "YOUR-TRIAL-ID" "<compile-error-log>")
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

---

## Phase 3: Runner Flexibility — 複数ファイル・非線形実行

### `:testfix/fix-bucket-batch` — 複数ファイル・複数バケット一括処理

複数のテストファイルと複数のエラーバケットを一度に修正する。

```edn
{:phase :testfix/fix-bucket-batch
 :params {:java-paths ["exports/gen-tests/ClassA/ClassATest.java"
                       "exports/gen-tests/ClassB/ClassBTest.java"]
          :class-name "SomeClass"
          :src-root "repo/src/main/java"
          :bucket-indices [0 1 2]
          :classpath-file "/tmp/classpath.txt"}}
```

**実行フロー**: 各ファイルに対して全バケットを修正。進捗をファイル × バケットで表示。

### `:testfix/fix-bucket-cycle` — 非線形実行（modify → check → modify）

同じファイル・バケットで修正を繰り返す。エラー数が減少している間は継続。

```edn
{:phase :testfix/fix-bucket-cycle
 :params {:java-path "exports/gen-tests/ClassA/ClassATest.java"
          :class-name "ClassA"
          :src-root "repo/src/main/java"
          :bucket-index 0
          :max-retries 3
          :classpath-file "/tmp/classpath.txt"}}
```

**実行フロー**:
1. AI でバケットを修正 → コンパイル検証
2. エラー数が前回より減っていれば試行継続
3. エラー 0 に到達するか、エラー数が停滞したら終了
4. 最大 `max-retries` 回まで

### `:testfix/fix-selected-classes` — クラス単位の選別修正

複数クラスを指定して、対応するテストファイルだけを修正。

```edn
{:phase :testfix/fix-selected-classes
 :params {:java-root "exports/gen-tests"
          :class-names ["DocumentAggregateServiceImpl"
                        "GenericMasterCsvServiceImpl"]
          :src-root "repo/src/main/java"
          :classpath-file "/tmp/classpath.txt"}}
```

**実行フロー**: 各クラスに対して `<ClassName>Test.java` を探索して修正。見つからないファイルはスキップ。

---

## Phase 3 設定例（複合パイプライン）

```edn
{:trial/id "2026-04-28-tradehub-phase3"
 :trial/tool :xtdb-workbench
 
 :maven/classpath-config {...}
 
 :phases [
  ;; Phase 1-2: データ投入 + テスト生成
  {:phase :ingest/jref ...}
  {:phase :ingest/jacoco ...}
  {:phase :generate/tests ...}
  
  ;; Phase 3: Runner Flexibility
  ;; パターン 1: 複数ファイルの一括処理
  {:phase :testfix/fix-bucket-batch
   :params {:java-paths [".../ClassA/ClassATest.java"
                         ".../ClassB/ClassBTest.java"]
            :bucket-indices [0 1]}}
  
  ;; パターン 2: サイクル修正（エラーが減るまで繰り返し）
  {:phase :testfix/fix-bucket-cycle
   :params {:max-retries 3}}
  
  ;; パターン 3: クラス選別修正
  {:phase :testfix/fix-selected-classes
   :params {:class-names ["ClassA" "ClassB" "ClassC"]}}
 ]}
```

**利用シーン**:
- `fix-bucket-batch`: 同じエラーグループの複数ファイルを一度に処理
- `fix-bucket-cycle`: 単一ファイルで複数回の修正試行（難しいエラーパターン対応）
- `fix-selected-classes`: 特定のサービスクラスだけに集中（優先度付け）
