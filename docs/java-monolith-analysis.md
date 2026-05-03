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

## フェーズ 6: GEXF エクスポートと Gephi 可視化

静的依存グラフを GEXF に出力し、Gephi で対話的に可視化する。

```clojure
(core/export-gexf! rs "exports/my-project.gexf"
                   :level :class
                   :module-fn module-fn)
```

### パラメータ

| パラメータ | 既定値 | 説明 |
|-----------|--------|------|
| `rs` | — | `core/q` で取得した `:refs` クエリ結果 |
| `path` | — | 出力先ファイルパス（`.gexf` 拡張子）|
| `:level` | `:method` | `:class`（クラス粒度）または `:method`（メソッド粒度）|
| `:module-fn` | `nil` | クラス名 → モジュール名の関数。指定しない場合は `"(unknown)"`|

クラス粒度から始め、全体像を把握してからメソッド粒度に掘り下げると見通しがよい。

### `:module-fn` の作り方

XTDB の `:refs` に含まれる `:ref/file` パスからモジュール名を逆引きする：

```clojure
(let [mod-map (->> (core/q '(from :refs [{:ref/from from :ref/trial t :ref/file file}]))
                   (filter #(= "my-project" (:t %)))
                   (keep (fn [{:keys [from file]}]
                           (when (and file
                                      (re-find #"com/example/web/([^/]+)/" file))
                             [(first (clojure.string/split from #"/"))
                              (second (re-find #"com/example/web/([^/]+)/" file))])))
                   (into {}))
      module-fn (fn [label] (get mod-map label "(unknown)"))]
  (core/export-gexf! rs "exports/my-project-class.gexf"
                     :level :class :module-fn module-fn))
```

### GEXF ノード属性（Data Laboratory で確認可能）

| attvalue | 型 | 内容 |
|----------|----|------|
| `in_degree` | integer | このノードへの参照数（呼ばれる多さ）|
| `out_degree` | integer | このノードからの参照数（呼ぶ多さ）|
| `module` | string | `module-fn` が返すモジュール名 |
| `type` | string | `Controller` / `ServiceImpl` / `Service` / `Mapper` / `Other` |

エッジの `weight` は2クラス（またはメソッド）間の静的呼び出し本数を集計した値。

---

### Gephi での操作手順

#### 1. インポート

1. **File → Open** で `.gexf` を開く
2. インポートダイアログ: **Graph Type: Directed**、**New workspace** → **OK**

#### 2. レイアウト（Layout パネル）

| レイアウト | 用途 |
|-----------|------|
| **Force Atlas 2** | モジュール・クラスタを自然に分離。最初にこれをかける |
| **Yifan Hu** | 大規模グラフ向け。素早く収束 |

Force Atlas 2 の推奨設定：
- **Scaling**: 2.0〜5.0（ノードが重なる場合は大きくする）
- **Prevent Overlap**: ON にして実行後に止める

#### 3. ノードサイズ → in-degree（Ranking）

GEXF の `viz:size` が読み込み時に自動反映される。手動調整したい場合：

1. **Appearance**（パレットアイコン）→ **Nodes** タブ → **サイズアイコン**（○の大きさ）
2. **Ranking** タブ → `in_degree` を選択
3. Min size / Max size を設定 → **Apply**

#### 4. ノード色 → type で種別色分け（Partition）

1. **Appearance** → **Nodes** タブ → **色アイコン（🎨）**
2. **Partition** タブ → `type` を選択（リストに自動表示される）
3. 各 type に色を設定 → **Apply**

推奨配色：

| type | 色 | 意味 |
|------|----|------|
| `Controller` | 赤 `#E74C3C` | HTTP エントリポイント |
| `ServiceImpl` | 青 `#3498DB` | ビジネスロジック実装 |
| `Service` | 水色 `#85C1E9` | インターフェース |
| `Mapper` | 緑 `#2ECC71` | DB アクセス層 |
| `Other` | グレー `#95A5A6` | その他 |

> **Note**: GEXF の `viz:shape` は Gephi 0.10.x では読み込み時に無視される。
> 種別の識別には上記 Partition 配色を使う。

#### 5. エッジ太さ → weight（Ranking）

GEXF の `viz:thickness` が自動反映される。手動調整：

1. **Appearance** → **Edges** タブ → **サイズアイコン**
2. **Ranking** タブ → `weight` を選択 → **Apply**

#### 6. モジュール境界の確認（Partition → module）

1. **Appearance** → **Nodes** → **🎨 Partition** → `module` を選択 → **Apply**
2. 同色のノード群が空間的にもクラスタを形成していれば凝集度が高い
3. 異色ノードへのエッジが多いクラスは**モジュール境界を越えた依存**の候補

#### 7. フィルタ・統計

- **Data Laboratory**: ノード/エッジの生データを確認・ソート（`in_degree` 降順など）
- **Filters パネル** → **Attributes → Equal** → `type = "Controller"` で特定種別を抽出
- **Statistics パネル** → **Modularity** でクラスタ自動検出（結果は `modularity_class` attvalue として追加される）

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
