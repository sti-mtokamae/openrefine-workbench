# テスト増幅ワークフローガイド（test-amplification）

静的解析情報（`:refs` × `:jsigs` × `:sql-refs` × `:jacoco`）を XTDB に集約し、
GitHub Models API 経由でテストスケルトンを生成・統合・修正し、カバレッジを増幅するサイクルのガイドです。

API リファレンス（関数一覧・スキーマ・オプション表）は [docs/api.md](api.md) の「AI テスト生成 API（codegen）」セクションを参照してください。

---

## テストスケルトン生成とは

このツールが生成するのは **テストスケルトン（骨格）** です。  
完全なテストコードではありません。

| 生成物 | 説明 |
|---|---|
| `@ExtendWith(MockitoExtension.class)` クラス宣言 | JUnit 5 + Mockito の枠組み |
| `@Mock` フィールド宣言 | `test-context` の `:direct-deps` から型推定した Mock 配線 |
| `@Test` メソッドの骨格 | `covered=0` のメソッドを優先、SQL 縛り付きは Mapper の戻り値 Mock も含む |
| 日本語コメント | テストの意図の説明（推測ベース） |

**AI が補えるもの**: Mock の配線・引数の型・戻り値の型・修飾子  
**人間が書くもの**: `expected` 値・分岐条件・ビジネスロジックの検証

---

## なぜこの設計か

### 静的解析だけでわかること・わからないこと

JavaParser による静的 AST 解析（`jsig!` / `jref!`）でわかるのはシグネチャ（型・修飾子）と呼び出しグラフです。  
メソッドボディ・フィールド定義・JavaDoc・継承構造・パッケージ名は **落ちています**。

```
jsig! で取得できるもの:
  private UUID resolveTargetProcessId(List<DocumentsEntity> documents)
              ↑戻り値型   ↑修飾子     ↑引数名・型

落ちているもの:
  - メソッドボディ（ロジック）
  - フィールド定義（@Autowired 等）
  - パッケージ名（→ 生成コードは your.package 仮置き）
  - JavaDoc
  - 継承元・実装インターフェース
```

### なぜ GitHub Models API か

外部サービスへの依存を最小化しつつ、JVM 標準の `java.net.http.HttpClient` だけで動作します。  
`gh auth token` で既存の GitHub 認証を再利用するため、新たなクレデンシャル管理が不要です。  
`models:read` スコープは不要。

---

## データフロー

```
Java ソース
    │
    ├─ jsig!  ──────────────────→ :jsigs テーブル
    │            (シグネチャ)       (型・修飾子)
    │
    ├─ jref!  ──────────────────→ :refs テーブル
    │            (呼び出しグラフ)   (from/to/kind)
    │
    ├─ sqlref! ─────────────────→ :sql-refs テーブル
    │            (MyBatis SQL)      (col-binds/param-binds)
    │
    └─ jacoco! ─────────────────→ :jacoco テーブル
                 (JaCoCo XML)       (covered/missed)

                          │
                          ▼
                    test-context
                 (コンテキスト統合)
                          │
                          ▼
                       gen-test
                  (プロンプト構築 → GitHub Models API)
                          │
                          ▼
               JUnit 5 + Mockito テストスケルトン
```

---

## AI に渡されるプロンプト構成

```
## 対象
<クラス名>[/<メソッド名>]

## メソッドシグネチャ
  - private UUID resolveTargetProcessId(List<DocumentsEntity> documents)
  - ...

## 直接依存（Mock 候補）
  - DocumentAggregateMapper/getAggregatableDocumentTypes
  - ...

## SQL 縛り（MyBatis Mapper 経由）
  - DocumentAggregateMapper/getAggregatableDocumentTypes
    col-binds: p.project_id=cp.project_id, ...
    param-binds: processId=#{context.processId}, ...

## JaCoCo カバレッジ（covered=0 = 完全未テスト）
  - resolveTargetProcessIds  [covered=0 missed=15]
  - ...

## 要件
- JUnit 5 (@Test, @ExtendWith(MockitoExtension.class))
- Mockito (@Mock, when(...).thenReturn(...))
- covered=0 のメソッドを優先してテストする
- SQL 縛りがある場合は Mapper の戻り値を Mock で制御するテストを含める
- 日本語コメントでテストの意図を説明する
```

`## メソッドシグネチャ` セクションは `jsig!` が投入済みの場合のみ付与される。  
それ以外のセクションは `test-context` が返す情報から常に構築される。

---

## 準備手順

### 1. データ投入

```clojure
(require '[workbench.core :as core])
(core/start!)

;; Java xref（呼び出しグラフ）
(core/jref! ["trials/experiments/xxx/repo"] :trial "my-project")
;; => {:put 12345, :delete 0}

;; MyBatis SQL アノテーション
(core/sqlref! ["trials/experiments/xxx/repo"] :trial "my-project")
;; => {:put 800, :delete 0}

;; JaCoCo カバレッジ（ビルドして jacoco.xml を生成してから）
(core/jacoco! "/path/to/target/site/jacoco/jacoco.xml" :trial "my-project")
;; => {:put 2329, :delete 0}

;; メソッドシグネチャ（型情報の精度向上に必須）
(core/jsig! ["trials/experiments/xxx/repo"] :trial "my-project")
;; => {:put 3415, :delete 0}

;; テストクラス構造（@InjectMocks / @Mock / @Test の関係）
;; mvn test 実行前に投入しておくと test-targets で「暗闇」解消に使える
(core/tref! ["trials/experiments/xxx/repo/common-lib/src/test"]
            :trial "my-project")
;; => {:put 1549, :delete 0}
```

すべて差分同期・冪等です。2 回実行しても副作用はありません。

### 2. コンテキストの確認

```clojure
;; 生成前に AI に渡される情報を確認する
(core/test-context "DocumentAggregateServiceImpl" :trial "my-project")
```

戻り値の見どころ：
- `:signatures` — メソッドシグネチャ一覧。なければ `jsig!` が未実行
- `:coverage` — `covered=0` が多いほど生成の優先度が高い
- `:direct-deps` — Mock 候補。Mapper が含まれているか確認
- `:sql-refs` — SQL 縛り情報。JOIN 条件が多いほど Mock 設定が複雑になる

### 3. 単体生成

```clojure
;; クラス全体（全メソッドのスケルトン）
(println (core/gen-test "DocumentAggregateServiceImpl" :trial "my-project"))

;; 特定メソッドのみ
(println (core/gen-test "DocumentAggregateServiceImpl" :trial "my-project"
                        :method "resolveTargetProcessId"))

;; モデルを変える（デフォルト: openai/gpt-4.1）
(println (core/gen-test "DocumentAggregateServiceImpl" :trial "my-project"
                        :model "openai/gpt-4.1-mini"))
```

---

## 一括生成ワークフロー

「未カバー（JaCoCo）かつ SQL 縛りあり（MyBatis Mapper 経由）」のメソッドを対象に全件生成します。

### 候補確認（dry-run）と分岐判断

```clojure
(core/uncovered-sql-methods :trial "my-project")
;; => [{:class "GenericMasterCsvServiceImpl"
;;      :method "importCsvFilesToStagingTables"
;;      :sql-deps ["GenericMasterImportMapper/createGenericMasterImportStagingFile"]}
;;     ...]
```

### 分岐: test-targets で「既存テストあり」を確認する

候補一覧を確認したら、`tref!` 投入済みであれば `test-targets` で分岐を判断します。

```clojure
(core/test-targets :trial "my-project")
;; => [{:class "DocumentAggregateServiceImpl"
;;      :uncovered-methods ["resolveTargetProcessId" ...]
;;      :test-methods [{:tref/class "DocumentAggregateServiceImplTest"
;;                      :tref/method "testResolve_xxx"
;;                      :tref/disabled? false} ...]}]
;;     ...]
```

| test-targets の結果 | 対処 |
|---|---|
| クラスが **含まれる** | テストクラスは存在する → 既存 `*Test.java` に生成メソッドを追記 |
| クラスが **含まれない** | テストクラスがない → `gen-tests-uncovered` で新規生成（次節） |

返り値は `:test-methods` の件数降順でソートされます。  
件数が多いクラスほど「テストはあるのに未カバーメソッドが残っている」= Mock 設定が展開されていない可能性が高いクラスです。  

`mvn test` を実行すると大量の failure が出ますが、  
`test-targets` に含まれるクラスの failure は **意味のある失敗**（Mock 補完で解消できる）です。

### 全件生成

```clojure
(core/gen-tests-uncovered :trial "my-project"
                          :out-dir "trials/experiments/xxx/gen-tests")
```

出力レイアウト：

```
gen-tests/
  DocumentAggregateServiceImpl/
    resolveTargetProcessId.md
    getTargetDocumentTypes.md
  GenericMasterCsvServiceImpl/
    importCsvFilesToStagingTables.md
  ...
```

各 `.md` ファイルは Markdown のコードブロック（` ```java ` ）内に Java コードが入っています。

### .md → Test.java 統合（merge-all-test-mds）

クラス単位の `.md` を 1 つの `*Test.java` にまとめます。  
同名メソッド・フィールドの重複排除・import の統合を自動で行います。

```clojure
(core/merge-all-test-mds
  :gen-dir "trials/experiments/xxx/gen-tests")
;; => [{:status :merged, :class "DocumentAggregateServiceImpl", :test-count 8} ...]
```

出力後のレイアウト：

```
gen-tests/
  DocumentAggregateServiceImpl/
    resolveTargetProcessId.md          ← 素材（保持）
    getTargetDocumentTypes.md          ← 素材（保持）
    DocumentAggregateServiceImplTest.java  ← ★ 統合済み Java ファイル
```

---

## 生成コードを使うまでの手順

### 1. Test.java を配置する

**既存テストがない場合（test-targets に含まれなかったクラス）:**

`merge-all-test-mds` で生成した `*Test.java` を、テスト対象クラスのパッケージパスと  
**同じディレクトリ構造** に配置します。

```bash
# 例: DocumentAggregateServiceImpl → subtotal/service/impl/ パッケージ
cp gen-tests/DocumentAggregateServiceImpl/DocumentAggregateServiceImplTest.java \
   repo/common-lib/src/test/java/com/example/subtotal/service/impl/
```

**既存テストがある場合（test-targets に含まれていたクラス）:**

`gen-test` で個別生成したメソッドを既存の `*Test.java` に手動で追記します。  
Mock フィールド（`@Mock`）は既存のものと重複しないよう確認してから追加してください。

```clojure
;; 既存テストのクラス構造が ai に渡るので Mock 配線が整合しやすい
(println (core/gen-test "DocumentAggregateServiceImpl" :trial "my-project"
                        :method "resolveTargetProcessId"))
```

### 2. コンパイルエラーを確認する

```bash
MAVEN_OPTS="--add-opens java.base/java.lang=ALL-UNNAMED" \
  guix shell 'openjdk@21:jdk' maven -- \
  ./mvnw test-compile -pl common-lib
```

### 3. コンパイルエラーを AI で修正する（fix-tests-dir）

`fix-tests-dir` はコンパイルエラーを自動取得して AI に渡し、テストシナリオを保持したまま修正します。

```clojure
(core/fix-tests-dir
  :gen-dir  "trials/experiments/xxx/gen-tests"
  :trial    "my-project"
  :src-root "trials/experiments/xxx/repo/common-lib/src/main/java"
  :mvn-root "trials/experiments/xxx/repo"
  :module   "common-lib")
```

コンパイルエラーが複数ラウンドにわたる場合は、test-compile → fix-tests-dir を繰り返します。  
エラーが解消できないメソッドは `// TODO: fix` でボディをクリアして BUILD SUCCESS を優先します。

### 4. mvn verify を実行して JaCoCo レポートを生成する

```bash
MAVEN_OPTS="--add-opens java.base/java.lang=ALL-UNNAMED" \
  guix shell 'openjdk@21:jdk' maven -- \
  ./mvnw verify -pl common-lib -Dmaven.test.failure.ignore=true
```

### 5. JaCoCo XML を XTDB に再投入する

```clojure
(core/jacoco! "trials/experiments/xxx/repo/common-lib/target/site/jacoco/jacoco.xml"
              :trial "my-project")

;; カバレッジ確認
(let [stats (core/jacocos :trial "my-project")]
  (println "covered:" (reduce + (map #(or (:jacoco/covered %) 0) stats)))
  (println "missed:"  (reduce + (map #(or (:jacoco/missed %)  0) stats))))

;; 残件確認 → 改善サイクルへ
(count (core/uncovered-sql-methods :trial "my-project"))
```

### 6. `expected` 値を埋める（手動）

```java
// 生成コード（AI の推測）
assertEquals(/* expected */, result);

// 人間が実際の仕様に合わせて埋める
assertEquals(3, result.size());
assertEquals(ProcessStatus.COMPLETED, result.get(0).getStatus());
```

---

## 既知の制約

| 制約 | 理由 | 対処 |
|---|---|---|
| パッケージ名が `your.package` | 静的解析ではパッケージ名を取得しない | 手動で修正 |
| `import` 文が不完全 | メソッドボディを解析しないため依存型が不明 | IDE の自動補完 |
| `expected` 値が `/* expected */` | ビジネスロジックは AI が推測できない | 仕様を確認して手動記入 |
| 同クラスの複数メソッドが別ファイルに分割 | `gen-tests-uncovered` はメソッド単位で出力 | 手動でクラス単位に統合 |
| メソッドボディなし → Mock 設定が浅い | `jsig!` はシグネチャのみ取得 | メソッド本体を参照して補完 |
| `private` メソッドのテストが冗長になる | アクセス制御を無視して骨格生成 | Reflection を使うか public メソッド経由で間接テスト |

---

## ディレクトリレイアウトの意図（exports/ vs repo/）

テストスケルトンは **2 か所** に置かれます。

| パス | 役割 |
|---|---|
| `exports/gen-tests/<クラス名>/<メソッド名>.md` | AI 生成物のアーカイブ（素材）。再生成の比較・ロールバック用 |
| `repo/.../src/test/java/.../XxxTest.java` | Maven が実行する修正済みコード（成果物） |

分離の理由：
- `.md` は人間・AI が手を入れる前の「素材」。試行ごとに保存しておくことでモデル変更や prompt 改善の効果を比較できる。
- 修正済み `.java` は Maven が直接実行する成果物。`.md` との差分が「AI エージェントが補った量」の記録になる。

---

## 典型エラーと対処

### `UnnecessaryStubbingException`

```
org.mockito.exceptions.misusing.UnnecessaryStubbingException:
  Unnecessary stubbings detected.
    1. -> at ...Test.java:42
```

**原因**: 早期リターンするテストメソッド内で、実際には呼ばれない `when(...).thenReturn(...)` が残っている。  
**対処**: エラー行を確認し、そのスタブ行を削除する。

```java
// 削除対象（早期リターンのため呼ばれない）
when(doc.getProjectId()).thenReturn(projectId);
```

### `InaccessibleObjectException`（cglib × Java 21）

```
java.lang.reflect.InaccessibleObjectException:
  Unable to make ... accessible: module java.base does not "opens java.lang" to ...
```

**対処**:

```bash
export MAVEN_OPTS="--add-opens java.base/java.lang=ALL-UNNAMED"
```

### `javac: command not found`（guix の JRE のみ問題）

```bash
# NG: JRE のみ（javac なし）
guix shell openjdk@21 maven

# OK: JDK 込み
guix shell 'openjdk@21:jdk' maven
```

---

## AI エージェントへの渡し方

テストをパスさせる作業を AI エージェント（GitHub Copilot Agent Mode 等）に委任するときのテクニックです。

### 作業の分解

| 作業 | AI に任せる | 人間が行う |
|---|---|---|
| パッケージ宣言の修正 | ✅（実ソース参照） | |
| `import` 文の補完 | ✅（IDE 自動補完 or 参照） | |
| Mock の型・戻り値型の修正 | ✅（実ソース照合） | |
| `UnnecessaryStubbingException` の修正 | ✅（エラー → 行削除） | |
| `expected` 値の決定 | | ✅（仕様確認） |
| `private` メソッドのテスト方針 | | ✅（設計判断） |

### プロンプトテンプレート

```
以下のテストスケルトンを修正して mvn test を通してください。

## テストスケルトン
[.md から抽出した Java コード]

## テスト対象クラスのパス
trials/experiments/2026-04-28-tradehub/repo/common-lib/src/main/java/.../DocumentAggregateServiceImpl.java

## 依存 Mapper のパス
trials/experiments/2026-04-28-tradehub/repo/common-lib/src/main/java/.../DocumentAggregateMapper.java

## 配置先
trials/experiments/2026-04-28-tradehub/repo/common-lib/src/test/java/.../DocumentAggregateServiceImplTest.java

## 実行コマンド
MAVEN_OPTS="--add-opens java.base/java.lang=ALL-UNNAMED" \
  guix shell 'openjdk@21:jdk' maven -- \
  mvn test -pl common-lib -Dtest=DocumentAggregateServiceImplTest

## 指示
1. パッケージ宣言を実ソースと照合して修正する
2. Mock の型・Mapper 戻り値型を実 Mapper インターフェースと照合して修正する
3. ファイルを配置先に書き込む
4. mvn test を実行する
5. エラーがあれば修正して再実行する（最大 3 回）
6. expected 値が /* expected */ のままのテストは残したままで OK
```

### エージェントにエラーを渡すときのコツ

- **エラーメッセージはそのまま貼る**。要約せず、スタックトレース全文を渡すと原因特定が速い。
- **「最大 N 回」を明示する**。ループ修正が必要な作業では試行回数の上限を与えることで無限ループを防ぐ。
- **修正スコープを絞る**。「このファイルだけ修正して」と対象を限定することで、意図しないファイルの書き換えを防ぐ。
- **実ソースのパスを渡す**。AI は「参照すべきファイル」を明示されると型照合の精度が上がる。

---

## カバレッジ増幅サイクル

```
jacoco.xml
    │
    ▼
jacoco!                     ← XTDB に取り込み（差分同期・冪等）
    │
    ▼
uncovered-sql-methods        ← 未カバー × SQL 縛りを一覧
    │
    ▼
gen-tests-uncovered          ← .md を一括生成（exports/gen-tests/）
    │
    ▼
AI エージェントで修正         ← パッケージ修正・型照合・mvn test
    │
    ▼
テストパス → mvn verify → jacoco.xml 再生成
    │
    └──────────────────────→ jacoco! で差分更新 → 繰り返し
```

**効率の目安**:
- `uncovered-sql-methods` で候補を絞ることで、数百メソッドの中から「今すぐテストを書ける」ものだけに集中できる。
- AI エージェントへの 1 回の指示でクラス単位（10〜20 メソッド）のテストが通れば、カバレッジカウントの増幅は現実的。
- `jacoco!` は冪等なので、何度再実行してもデータが壊れない。
