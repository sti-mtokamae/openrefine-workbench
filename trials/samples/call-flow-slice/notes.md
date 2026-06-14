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
  - slice に含まれるメソッドの span 一覧
- `foo-source-lines-enriched.tsv`
  - slice 対象クラス群の全ソース行に、所属メソッド・slice depth・call-to を付加した表

## OpenRefine guide

この sample では、`foo-call-flow.tsv` を
「今回どのクラス群・メソッド群を分析スコープに含めるかを定める表」とみなし、
実際にコードを読む主軸は `foo-source-lines-enriched.tsv` に置く。

### import order

次の 3 表をそれぞれ OpenRefine project として取り込む。

1. `foo-call-flow.tsv`
2. `foo-method-spans.tsv`
3. `foo-source-lines-enriched.tsv`

### intended roles

- `foo-call-flow.tsv`
  - root からの呼び出し骨格を見て、分析スコープを確認する表
- `foo-method-spans.tsv`
  - `method-id` 単位の定義位置・シグネチャ確認用
- `foo-source-lines-enriched.tsv`
  - 行単位で slice を確認し、クラス間の流れを読むための主表

### scenario

基本シナリオは次の通り。

1. `foo-call-flow.tsv` で root から何ホップ先までのクラス群を今回の分析対象にするかを確認する
2. `foo-source-lines-enriched.tsv` で root クラスのソースコードを起点に読む
3. `call-to` を手掛かりに、スコープ内の別クラス・別メソッドへ視線を移す
4. 必要に応じて `foo-method-spans.tsv` を `cross()` して定義位置やシグネチャを補う

### concrete operations

以下は、`foo-source-lines-enriched.tsv` を主表として使う具体例。

#### 1. root クラスを起点にする

`foo-source-lines-enriched.tsv` を開き、まず `file` で
`src/main/java/com/example/FooController.java` に絞る。

あるいは `class = FooController` でもよい。

この状態が、
「root メソッドのクラスのソースコードを起点軸に見る」
基本画面になる。

#### 2. 呼び出し行だけを見る

`foo-source-lines-enriched.tsv` 側で:

- `Numeric facet` on `call-count`
- `call-count > 0`

に絞る。

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

これで、たとえば `BarService/bar` に対して
`src/main/java/com/example/BarService.java:12-16`
のような span を 1 列で見られる。

#### 5. return type を引く

- 新しい列名: `return-type-from-span`
- GREL:

```grel
if(
  isBlank(value),
  null,
  cell.cross("foo-method-spans", "method-id")[0].cells["return-type"].value
)
```

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
  - `file` で root クラスのソースに絞る
  - `in-slice-method? = true` の行だけ見る
  - `slice-depth` で depth ごとの行を分ける
  - `call-count > 0` の行だけ見る
  - `call-to` を見て、どの行が次の depth へつながるか確認する
- `foo-method-spans.tsv`
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
   `called-method-span` で `BarService.java:12-16` だと確認する
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
