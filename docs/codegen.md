# AI テスト生成ガイド（codegen）

静的解析情報（`:refs` × `:jsigs` × `:sql-refs` × `:jacoco`）を XTDB に集約し、
GitHub Models API 経由で JUnit 5 + Mockito テストスケルトンを生成するワークフローのガイドです。

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

### 候補確認（dry-run）

```clojure
(core/uncovered-sql-methods :trial "my-project")
;; => [{:class "GenericMasterCsvServiceImpl"
;;      :method "importCsvFilesToStagingTables"
;;      :sql-deps ["GenericMasterImportMapper/createGenericMasterImportStagingFile"]}
;;     ...]
```

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

---

## 生成コードを使うまでの手順

### 1. パッケージ宣言を修正する

生成コードは `package your.package;` の仮置きになっています。  
テスト対象クラスのパッケージ名に合わせて書き換えてください。

```java
// 生成コード（仮置き）
package your.package;

// 実際のパッケージに修正
package com.example.tradehub.service;
```

### 2. インポートを補完する

生成コードには `import` 文が含まれないか、不完全な場合があります。  
IDE の「未解決シンボルの自動インポート」機能（IntelliJ: `Alt+Enter`）で補完してください。

### 3. `.java` ファイルに配置する

テスト対象クラスのパッケージに対応するディレクトリに配置します。

```
src/test/java/com/example/tradehub/service/
  DocumentAggregateServiceImplTest.java
```

### 4. `expected` 値を埋める

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
