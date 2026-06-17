# sample-call-flow-slice

## goal

`bin/run-trial` から最小の call-flow slicing を実行し、
`source-lines-enriched.tsv` を主表として使い、
ソースコード行単位で slice 内容を確認できる表を生成する。
あわせて、行の分析を補助する `foo-method-spans.tsv` と
スコープ全体の骨格確認用に `foo-call-flow.tsv` も生成する。

## expected output

package / class scope に含めた全ソース行に対して、
それぞれが属するメソッド、slice depth、呼び出し情報を付加した表。

`foo-source-lines-enriched.tsv` の主要列:

- `file` — ソースファイルのパス
- `line` — ファイル内の行番号
- `text` — その行のソースコード
- `blank-line?` — 空白行・コメント行の判別
- `method-id` — その行が属するメソッド ID（format: `ClassName/methodName`）
- `method-start-line` — メソッド定義の開始行
- `method-end-line` — メソッド定義の終了行
- `class` — その行が属するクラス名（method span の外では空欄）
- `call-to` — その行が呼び出しているメソッド（呼び出しがない行は空欄）
- `call-count` — その行から呼び出されるメソッド数
- `slice-depth` — root メソッドからの呼び出し深さ
- `in-slice-method?` — そのメソッドが root からの slice に含まれるか
- `file-class` — ファイル内の主要クラス名

補助表:

- `foo-method-spans.tsv`
  - scope に含まれるメソッドの span 一覧（method-id 単位の定義位置・シグネチャ）
  - `in-slice-method? = true` の行は root からの slice に含まれる
- `foo-call-flow.tsv`
  - root メソッドからの呼び出し構造（method-id 単位、depth・parent 情報付き）
  - 全体のスコープ確認と骨格の把握に用いる

## OpenRefine guide

この sample では、`foo-source-lines-enriched.tsv` を主表として、
OpenRefine の facet / sort / filter / `cross()` でコードを読む。

`foo-method-spans.tsv` は、呼び出し先メソッドの定義位置やシグネチャを
quick lookup する参照表として使う。

`foo-call-flow.tsv` は、スコープ全体の構造を確認したり、
現在見ている location が root から何ホップ先なのかを
俯瞰的に確認する骨格図として使う。

対象範囲（package / class scope）は
trial 側で `:package-prefixes ["com.example"]` により決める。

ここでの考え方は、

- スコープ定義
- slice 計算
- 補助表の生成

を `trial.edn` / Clojure 側で行い、
OpenRefine ではその結果を **行単位で** facet / sort / filter / `cross()` で読む、という分担である。
GREL は補助的な列追加には使えるが、分析ロジックの本体には置かない。

### import order

次の 3 表をそれぞれ OpenRefine project として取り込む。

1. `foo-source-lines-enriched.tsv` — **主表**
2. `foo-method-spans.tsv` — 参照表（method span lookup 用）
3. `foo-call-flow.tsv` — 骨格表（スコープ全体と depth 確認用）

### intended roles

- `foo-source-lines-enriched.tsv` — **主表**
  - 行単位でソースコードを読む
  - method span 内にいるか、呼び出し行か、空白行か、slice 外か を facet で区別
  - `call-to` を手掛かりに、別ファイル・別メソッドへ視線を移す
  - slice depth でスコープの層を把握する
- `foo-method-spans.tsv` — 参照表
  - 現在行の `call-to` や `method-id` から method span を quick lookup
  - シグネチャ・return type・修飾子を補足
- `foo-call-flow.tsv` — 骨格表
  - スコープ全体の呼び出し構造を俯瞰
  - root から見た各メソッドの位置付けを確認

### scenario

基本シナリオは次の通り。

1. trial 側で package / class / file / root-depth のどれで source scope を作るか決める
2. `foo-source-lines-enriched.tsv` を開き、`file = root-file` で root ソースコードを起点に読む
3. 各行の `call-to` を見て、スコープ内のどのメソッドが呼び出されているかを確認
4. `call-to` 値で `foo-method-spans.tsv` を lookup して、呼び出し先の定義位置やシグネチャを確認
5. `in-slice-method? = true` で slice に含まれるメソッドだけに絞り直す
6. 必要に応じて `foo-call-flow.tsv` へ移り、全体の構造と depth を俯瞰確認

### OpenRefine と Clojure の分担

この sample では、

- scope 定義
- slice 計算
- source 行 / method span の出力

は `trial.edn` / Clojure 側で行い、
OpenRefine では facet / sort / filter / `cross()` で読む。

ただし現時点では、
OpenRefine 上で GREL の代わりに Clojure を直接評価する仕組みは
この repo にはまだ見当たらない。
そのため、OpenRefine 内で補助列を足す具体例は、
当面 GREL と Clojure を並記して案内する。

添付メモの前提では、OpenRefine の式入力欄で言語ドロップダウンを
`Clojure` に切り替えると、`cross()` を次のように書ける。

- `cross value "project-name" "join-key-column"`
- 1件目を取るなら `(nth 0)`
- 深い値を取るなら `->` または `get-in`

以下の Clojure 例は、この前提に基づく。
この repo だけではまだ実地確認できていないので、
OpenRefine 側で試した結果に応じて微調整する。

### concrete operations

以下は、`foo-source-lines-enriched.tsv` を主表として使う具体例。

#### 1. root クラスを起点にする

`foo-source-lines-enriched.tsv` を開き、まず `file` で
`src/main/java/com/example/FooController.java` に絞る。

`file-class = FooController` でもほぼ同じ見方ができる。
ここでの `file-class` はファイル単位の代表クラス名で、
`class` 列は「その行がどのメソッド span に属しているか」を表す。
そのため、`class` は import 文、class 宣言、field 定義、空白行では空欄になりうる。
空白行は `blank-line? = true` で判別できる。

この状態が、
「root メソッドのクラスのソースコードを起点軸に見る」
基本画面になる。

#### 2. 呼び出し行だけを見る

`foo-source-lines-enriched.tsv` 側で:

1. `call-count` 列で `Text filter` を開く
2. `^[1-9]` を入れる
3. 必要なら列を数値変換して `Numeric facet` で 1 以上に絞る

により、呼び出しのない行を落とせる。

これで、root クラスの中で
「他メソッドを呼んでいる行」だけを見られる。

#### 3. 呼び出し先メソッドの定義位置を引く

`foo-source-lines-enriched.tsv` で `call-to` 列を選び、
`Edit column` → `Add column based on this column...` を実行する。

- 新しい列名: `called-method-span`
- GREL:

```grel
if(
  isBlank(value),
  null,
  with(
    cell.cross("foo-method-spans", "method-id")[0],
    r,
    r.cells["file"].value + ":" + r.cells["method-start-line"].value + "-" + r.cells["method-end-line"].value
  )
)
```

- Clojure:

```clojure
(if
  (or (nil? value) (= "" value))
  nil
  (let [r (-> (cross value "foo-method-spans" "method-id")
              (nth 0))]
    (str (get-in r ["cells" "file" "value"])
         ":"
         (get-in r ["cells" "method-start-line" "value"])
         "-"
         (get-in r ["cells" "method-end-line" "value"]))))
```

これで、
`BarService/bar` や `BazRepository/fetchMessage` の定義位置を、
呼び出し行の横に直接出せる。

#### 4. 現在行が属するメソッドの span を引く

`foo-source-lines-enriched.tsv` で `method-id` 列を選び、
`Edit column` → `Add column based on this column...` を実行する。

- 新しい列名: `method-span`
- GREL:

```grel
if(
  isBlank(value),
  null,
  with(
    cell.cross("foo-method-spans", "method-id")[0],
    r,
    r.cells["file"].value + ":" + r.cells["method-start-line"].value + "-" + r.cells["method-end-line"].value
  )
)
```

- Clojure:

```clojure
(if
  (or (nil? value) (= "" value))
  nil
  (let [r (-> (cross value "foo-method-spans" "method-id")
              (nth 0))]
    (str (get-in r ["cells" "file" "value"])
         ":"
         (get-in r ["cells" "method-start-line" "value"])
         "-"
         (get-in r ["cells" "method-end-line" "value"]))))
```

これで、たとえば `BarService/bar` に対して
`src/main/java/com/example/BarService.java:12-16`
のような span を 1 列で見られる。

#### 5. return type を引く

「現在行が属するメソッドの return type」と
「この行が呼び出している先の return type」は別物なので、分けて列を作る。

- 現在メソッド用の列名: `current-return-type`
- GREL:

```grel
if(
  isBlank(value),
  null,
  cell.cross("foo-method-spans", "method-id")[0].cells["return-type"].value
)
```

- Clojure:

```clojure
(if
  (or (nil? value) (= "" value))
  nil
  (-> (cross value "foo-method-spans" "method-id")
      (nth 0)
      (get "cells")
      (get "return-type")
      (get "value")))
```

これは `method-id` 列を選んだ状態で使う。

- 呼び出し先用の列名: `called-return-type`
- GREL:

```grel
if(
  isBlank(value),
  null,
  cell.cross("foo-method-spans", "method-id")[0].cells["return-type"].value
)
```

- Clojure:

```clojure
(if
  (or (nil? value) (= "" value))
  nil
  (-> (cross value "foo-method-spans" "method-id")
      (nth 0)
      (get "cells")
      (get "return-type")
      (get "value")))
```

これは `call-to` 列を選んだ状態で使う。

#### 6. `foo-call-flow.tsv` に戻って depth を確認する

いま見ている `method-id` が root から何ホップ先かを確認したいときは、
`foo-call-flow.tsv` で同じ `method-id` を見る。

ただし `foo-source-lines-enriched.tsv` 側にも
`slice-depth` 列があるので、普段はそこで足りる。

#### 7. `foo-call-flow.tsv` 側で source-lines の行数を引く

以下は `foo-call-flow.tsv` を開いた状態で、
対象メソッドの本文規模を確認したいときに使う。

- 新しい列名: `source-line-count`
- GREL:

```grel
if(
  isBlank(value),
  0,
  cell.cross("foo-source-lines-enriched", "method-id").length()
)
```

- Clojure:

```clojure
(if
  (or (nil? value) (= "" value))
  0
  (count (cross value "foo-source-lines-enriched" "method-id")))
```

これで、そのメソッドに属する行数を `foo-call-flow.tsv` 側で見られる。

#### 8. `foo-call-flow.tsv` 側で call 行だけを圧縮表示する

これも `foo-call-flow.tsv` を開いた状態で、
「このメソッドの本文のどの行が次のメソッドを呼んでいるか」を
1 列でざっと見たいときに使う。

- 新しい列名: `call-lines`
- GREL:

```grel
forEach(
  cell.cross("foo-source-lines-enriched", "method-id"),
  r,
  if(
    r.cells["call-count"].value > 0,
    r.cells["line"].value + ": " + r.cells["text"].value,
    null
  )
).filter(v, !isBlank(v)).join(" | ")
```

- Clojure:

```clojure
(->> (cross value "foo-source-lines-enriched" "method-id")
     (map (fn [r]
            (let [call-count (get-in r ["cells" "call-count" "value"])]
              (when (and call-count (not= "0" (str call-count)))
                (str (get-in r ["cells" "line" "value"])
                     ": "
                     (get-in r ["cells" "text" "value"]))))))
     (remove nil?)
     (clojure.string/join " | "))
```

これで、対象メソッドの本文のうち、
実際に他メソッドを呼んでいる行だけを 1 列に圧縮して見られる。

#### note

`cross(project, column)` の `project` には OpenRefine の project 名を指定する。
ここでは sample として、

- `foo-method-spans`
- `foo-source-lines-enriched`

という project 名で取り込んでいる前提で書いている。
別名で取り込んだ場合は、式中の project 名も合わせて変える。

### useful checks

**主表 `foo-source-lines-enriched.tsv` での基本的な見方:**

- `file = src/main/java/com/example/FooController.java` で root ファイル全体を見る
- `blank-line? = false` で空白行・コメント行を落とす
- `in-slice-method? = true` の行だけに絞る（slice に含まれるメソッドだけ）
- `slice-depth = 0` で root メソッド自身の行だけを見る
- `slice-depth = 1` で root から直接呼び出されるメソッドを見る
- `call-count > 0` の行だけを見る（他メソッドを呼んでいる行）
- `method-id` でメソッド単位に絞る
- `call-to` 列で現在行が呼び出している先を確認

**参照表 `foo-method-spans.tsv` での使い方:**

- 主表で見ている `call-to` 値（例: `BarService/bar`）を `method-id` で検索
- `file`, `method-start-line`, `method-end-line` で定義位置を確認
- `return-type`, `mods`, `params` を見てメソッドのシグネチャを把握
- `in-slice-method? = true` のメソッドは root からの slice に含まれる

**骨格表 `foo-call-flow.tsv` での使い方:**

- 全体のスコープと depth 構造を俯瞰
- `depth` facet で層ごとの広がりを見る
- 主表から少し離れて、骨格を見直すときに参照
- 特定の `method-id` が root から何ホップ先かを確認

### expected reading flow

たとえば `FooController/foo` を root にした場合、
基本の読み順は次のようになる。

**1. 主表を開く — `foo-source-lines-enriched.tsv`**

- Project を作成して `foo-source-lines-enriched.tsv` を import
- `file = src/main/java/com/example/FooController.java` で root ファイルに絞る

**2. Root メソッドのソースを読む**

- `blank-line? = false` で空白行を落とす
- root クラスのソース行を上から読む
- 現在行が属するメソッド (`method-id`), 所属クラス (`class`), 行の内容 (`text`) を見ながら進む

**3. 呼び出し行を見つける**

- `call-count > 0` の行だけに絞る
- または `call-to` 列で Text filter を使い、空欄行を落とす
- 現在行が呼び出している先 (`call-to` = `BarService/bar` など) を確認

**4. 呼び出し先の定義位置を確認 — `foo-method-spans.tsv` を参照**

- `foo-method-spans.tsv` を別 tab で開く（または cross() で統合）
- `method-id = BarService/bar` で検索
- `file`, `method-start-line`, `method-end-line` を確認
- `return-type`, `params` でシグネチャを把握

**5. 呼び出し先メソッドのソースを読む**

- 主表に戻り、`file = src/main/java/com/example/BarService.java` で絞る
- `method-id = BarService/bar` に該当する行だけを見る
- そのメソッド内の呼び出し行 (`call-count > 0`) を確認

**6. 段階的に下層へ進む**

- `slice-depth` や `in-slice-method? = true` で scope を確認
- 深さ 1, 2, ... と層ごとに読み進める

**7. 構造を俯瞰確認 — `foo-call-flow.tsv` を参照**

- 全体のスコープが `FooController`, `BarService`, `BazRepository`, `AuditClient`, `FormatterUtil` 
  まで広がることを確認
- 各メソッドが root からの何ホップ先か (depth) を確認
- 現在見ている location がスコープ内でどの位置付けか を把握

このように、

- **主軸**: `foo-source-lines-enriched.tsv` で行単位でコードを読む
- **参照**: `foo-method-spans.tsv` で method span や シグネチャを lookup
- **確認**: `foo-call-flow.tsv` で全体構造と depth を俯瞰

という使い分けを想定している。

## run

```bash
bin/run-trial trials/samples/call-flow-slice/trial.edn
```
