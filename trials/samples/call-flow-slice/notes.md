# sample-call-flow-slice

## goal

`bin/run-trial` から最小の call-flow slicing を実行し、
`slice-results` の出力を `exports/foo-call-flow.tsv` に保存する。
あわせて、OpenRefine で `cross()` しやすい補助表として
`foo-method-spans.tsv` と `foo-source-lines-enriched.tsv` も生成する。

## expected output

起点 `FooController/foo` を含む行と、
深さ 1 の `BarService/bar` を含む行が TSV に出力される。

root 行では、`FooController/foo` の定義位置として
`method-file` と `method-start-line` が埋まることを期待する。

子ノード行では、

- `method-file` / `method-start-line`
  そのメソッド自身の定義位置
- `call-file` / `call-line`
  親メソッドからそのメソッドを呼んでいる場所

が両方入ることを期待する。

主要列:

- `root-method`
- `method-id`
- `depth`
- `parent-method`
- `method-file`
- `method-start-line`
- `call-file`
- `call-line`

補助表:

- `foo-method-spans.tsv`
  - scope に含まれるメソッドの span 一覧
  - `in-slice-method? = true` の行は root からの slice にも含まれる
- `foo-source-lines-enriched.tsv`
  - package / class scope に含めた全ソース行に、所属メソッド・slice depth・call-to を付加した表

## OpenRefine guide

この sample では、`foo-call-flow.tsv` を
「root から見た呼び出し骨格を確認する表」とみなし、
実際にコードを読む主軸は `foo-source-lines-enriched.tsv` に置く。
`foo-source-lines-enriched.tsv` と `foo-method-spans.tsv` の対象範囲は、
trial 側で `:package-prefixes ["com.example"]` により決めている。

ここでの考え方は、

- スコープ定義
- slice 計算
- 補助表の生成

を `trial.edn` / Clojure 側で行い、
OpenRefine ではその結果を facet / sort / filter / `cross()` で読む、という分担である。
GREL は補助的な列追加には使えるが、分析ロジックの本体には置かない。

### import order

次の 3 表をそれぞれ OpenRefine project として取り込む。

1. `foo-call-flow.tsv`
2. `foo-method-spans.tsv`
3. `foo-source-lines-enriched.tsv`

### intended roles

- `foo-call-flow.tsv`
  - root からの呼び出し骨格と depth を確認する表
- `foo-method-spans.tsv`
  - `method-id` 単位の定義位置・シグネチャ確認用
- `foo-source-lines-enriched.tsv`
  - 行単位で slice を確認し、クラス間の流れを読むための主表

### scenario

基本シナリオは次の通り。

1. trial 側で package / class / file / root-depth のどれで source scope を作るか決める
2. `foo-source-lines-enriched.tsv` で root クラスのソースコードを起点に読む
3. `call-to` を手掛かりに、スコープ内の別クラス・別メソッドへ視線を移す
4. `foo-call-flow.tsv` で呼び出し骨格と depth を確認する
5. 必要に応じて `foo-method-spans.tsv` と突き合わせて定義位置やシグネチャを補う

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

- `foo-source-lines-enriched.tsv`
  - `file-class = FooController` で root ファイル全体を見る
  - `file` で root クラスのソースに絞る
  - `blank-line? = false` で空白行を落とす
  - `in-slice-method? = true` の行だけ見る
  - `slice-depth` で depth ごとの行を分ける
  - `call-count > 0` の行だけ見る
  - `call-to` を見て、どの行が次の depth へつながるか確認する
- `foo-method-spans.tsv`
  - `in-slice-method? = true` で今回の call-flow に載るメソッドへ絞る
  - `return-type` や `mods` で helper / API の違いを見る
- `foo-call-flow.tsv`
  - `depth` facet で層ごとの広がりを見る
  - `parent-method` で直接の呼出元を確認する
  - 主表から少し離れて、骨格を見直すときの索引に使う

### expected reading flow

たとえば `FooController/foo` を root にした場合、
基本の読み順は次のようになる。

1. `foo-call-flow.tsv` で今回のスコープが
   `FooController`, `BarService`, `BazRepository`, `AuditClient`, `FormatterUtil`
   まで広がることを確認する
2. `foo-source-lines-enriched.tsv` を `file = src/main/java/com/example/FooController.java`
   で絞り、`call-count > 0` の行を見る
3. `call-to = BarService/bar` を見つけたら、
   `foo-method-spans.tsv` で `method-id = BarService/bar` を見て
   `BarService.java:12-16` だと確認する
4. 次に `file = src/main/java/com/example/BarService.java` へ移り、
   `call-to = BazRepository/fetchMessage` と `AuditClient/recordAccess` を見る
5. 必要に応じて `foo-call-flow.tsv` へ戻り、
   それらが `depth = 2` であることを確認する

このように、

- `foo-call-flow.tsv` でスコープを定める
- `foo-source-lines-enriched.tsv` を主画面としてコードを読む
- `foo-method-spans.tsv` で定義位置やシグネチャを補う

という使い方を想定している。

## run

```bash
bin/run-trial trials/samples/call-flow-slice/trial.edn
```
