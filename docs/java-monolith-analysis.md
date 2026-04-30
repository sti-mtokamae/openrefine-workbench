# Java モノリシックアプリ分析ガイド

JavaParser + XTDB を使って Spring Boot モノリスの依存構造と変更履歴を解析し、
マイクロサービス分解やバグ調査の材料を得るワークフロー。

**テンプレートスクリプト**: [`src/workbench/analyze_template.clj`](../src/workbench/analyze_template.clj)

---

## 全体フロー

```
jref!        →  fan-in / fan-out / topo-sort   （静的依存 overview）
cochange!    →  cochanges / クロスモジュール    （変更履歴 overview）
                    ↓ 怪しいクラスを発見
neighborhood / impact / deps                   （ピンポイント深掘り）
```

XTDB に一度投入すれば（`.xtdb/` ディレクトリ）、以降の分析クエリは高速に動く。
データを作り直す場合は `rm -rf .xtdb/` してから再実行。

---

## フェーズ 1: jrefs 投入

```clojure
(core/jref! ["path/to/repo/common-lib"
             "path/to/repo/common-app"]
            :trial "my-project")
```

- `JavaParser` でソースを静的解析し `:refs` テーブルに書き込む
- scope 解決（`FieldDeclaration` + `implements` スキャン）により
  `:ref/to` が `ClassName/methodName` 形式に統一される
- テストコードは `:exclude-test true` で除外するのが基本

### scope 解決の仕組み

| ステップ | 内容 |
|---------|------|
| `build-iface-impl-map` | `class Foo implements Bar` → `{"Bar" "Foo"}` |
| `build-field-map` | フィールド宣言 → `{クラス名 {変数名 型名}}` |
| `resolve-scope` | 変数名 → I/F → impl の 2 段階解決 |

scope 解決前は `service.doSomething()` の `service` が未解決のまま残るが、
解決後は `FooServiceImpl/doSomething` として正確に記録される。

---

## フェーズ 2: 静的依存 overview

### fan-in（呼ばれる回数）= hotspot

```clojure
(core/fan-in rs)  ; → [{:symbol "X/method" :count N} ...]
```

上位に来るのは `log/info` 等のロガー呼び出し（ノイズ）と、
真の hotspot（多くのクラスから呼ばれる共通サービス）が混在する。
クラス名プレフィックスでノイズを除外してから読む。

### fan-out（呼ぶ回数）= モンスターメソッド候補

```clojure
(core/fan-out rs)  ; → [{:symbol "X/method" :count N} ...]
```

fan-out が高いメソッドは多くの依存先を持つ「神メソッド」。
**分解の本命ターゲット**として最初に注目する。

### topo-sort（依存が少ない順）= 切り出し順

```clojure
(core/topo-sort :rs rs)  ; → ["JacksonConfig" "DocumentPropertiesEntity" ...]
```

先頭に来るクラス（Config・Entity・マスター系）は依存が少なく、
マイクロサービスへの切り出しが容易。移植計画の土台になる。

---

## フェーズ 3: cochange 投入

```clojure
(core/cochange! "path/to/repo"
                :trial "my-project"
                :filter-path "src/main/java")
```

`git log --name-only` で同一コミットに含まれるファイルペアを集計し
`:cochanges` テーブルに書き込む。

---

## フェーズ 4: cochange 分析

### 全体 top（変更が連動しやすいペア）

同一モジュール内の `FooService <-> FooServiceImpl` が上位を占めるのは正常。
これを除外すると**本当に気になるペア**が浮かぶ。

### クロスモジュール top（境界シグナル）

```
[document] DocumentsController  <->  [project] ProjectServiceImpl  (20)
```

このようなペアは「モジュールをまたいで一緒に変更されている」事実を示す。
**分離するなら API 境界が必要**というシグナルになる。

クロスモジュール抽出のフィルタ条件：

```clojure
;; Interface/Impl ノイズ除外
(or (= ca (str cb "Impl")) (= cb (str ca "Impl")))

;; 同一モジュール除外
(not= ma mb)

;; パスからモジュール名を抽出
(re-find #"com/example/web/([^/]+)/" path)
```

---

## フェーズ 5: ピンポイント分析

クロスモジュール分析や fan-out で目星をつけたクラスを深掘りする。

```clojure
;; 誰から呼ばれているか（直接の呼び出し元）
(core/impact "MyServiceImpl" :depth 1 :rs rs)

;; 何に依存しているか（直接の呼び出し先）
(core/deps "MyServiceImpl" :depth 1 :rs rs)

;; N ホップ以内の近傍クラス全体（切り出し範囲の確認）
(core/neighborhood "MyServiceImpl" :depth 2 :rs rs)
```

**クラス名のみ（`/` なし）で渡すと `ClassName/` 前方一致で自動展開**するので、
メソッド名を知らなくても呼び出せる。

neighborhood の結果から Java 標準クラス（`Collections`・`String` 等）を除くと、
実際の切り出し対象クラス群が見える。

---

## ノイズ除外戦略

### クラス名プレフィックスによる除外

触らない冷凍コード・自動生成コードはプレフィックスで一括除外する。

```clojure
(def noise-cls-patterns [#"^Acl" #"^ACL" #"^Ida" #"^IDA"])
(def noise-cls?
  (fn [cls] (boolean (some #(re-find % cls) noise-cls-patterns))))
```

### モジュール名による除外

```clojure
(def noise-modules #{"acl" "ida" "idaTask"})
```

### Interface/Impl ノイズ除外

cochange で `FooService <-> FooServiceImpl` は自明なペアなので除外する。

```clojure
(defn impl-noise? [{:keys [ca cb]}]
  (or (= ca (str cb "Impl")) (= cb (str ca "Impl"))))
```

---

## 読み方のコツ

| シグナル | 意味 | アクション |
|---------|------|-----------|
| fan-out 上位（100件超） | 神メソッド | 分解の最優先ターゲット |
| クロスモジュール cochange 上位 | 隠れた境界越え依存 | API 境界の設計が必要 |
| `impact` が多い（呼び出し元多数） | 変更影響が広い | 修正前に影響範囲を必ず確認 |
| `deps` が多い（呼び出し先多数） | 多数に依存 | 切り出し時に依存解消が必要 |
| neighborhood サイズが小さい（10件以下） | 疎結合 | 先に切り出せる候補 |

---

## 実行方法

```bash
# XTDB データを作り直す場合
rm -rf .xtdb/

# 分析実行（bash の ! ヒストリ展開を避けるため -M でファイル指定）
guix shell -m manifest.scm -- clojure -A:xtdb -J-Xmx768m -M src/workbench/analyze_template.clj
```

再実行時は `jref!` / `cochange!` の行をコメントアウトするとデータ投入をスキップできる。

---

## 実例: tradehub-web-backend (2026-04-28)

Spring Boot モノリス（Java 1,202 ファイル）の分析。

**静的依存**
- refs: 34,146（scope 解決: iface-impl-map 91 エントリ, field-map 1,135 クラス）
- fan-out 上位: `DocumentsServiceImpl/updateDocumentPropertyAndRowProperty` (156) など Document 系が支配
- topo-sort: Config・Entity・マスター系が先頭（依存が少なく切り出しやすい）

**cochange で判明したクロスモジュール依存**
```
20  [document] DocumentsController  <->  [project]        ProjectServiceImpl
13  [document] DocumentsController  <->  [documentImport] DocumentImportController
```
→ `document` モジュールが `project` の実装層まで直接結合。分離時に API 境界が必要。

**neighborhood でバグ調査（aggregate_sync_issue）**
- `impact "DocumentAggregateServiceImpl"` → 呼び出し元 3 クラスを特定
  （`DocumentsController`・`DocumentImportController`・`ExcelImportController`）
- `deps` → 依存先 7 クラスを特定し、未注入フィールド（設計上の欠落）を発見
- 詳細: `trials/experiments/2026-04-28-tradehub/repo/docs/aggregate_sync_issue.md`
