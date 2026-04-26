# コード解析 end-to-end ガイド

Java / Clojure のソースコードを XTDB に取り込み、クエリで読み解く一連の流れ。

---

## 0. 前提

```bash
guix shell -m manifest.scm -- clojure -A:xtdb:repl
```

```clojure
(require '[workbench.core :as core])
(core/start!)
```

---

## 1. データを投入する

### Java ソース

```clojure
(core/ingest! "trials/samples/repo")          ; ファイルツリー → :files
(core/jref!   ["trials/samples/repo"])         ; メソッド呼び出し → :refs
```

### Clojure ソース（混在もできる）

```clojure
(core/ingest! "src")
(core/xref!   ["src"])
```

---

## 2. 何が入ったか確認する

### ファイルツリーを見る

```clojure
(core/tree)
```

```
trials/samples/repo/
  src/
    main/
      java/
        com/
          example/
            BarService.java   (167 B)
            FooController.java (143 B)
```

### :refs テーブルの全件

```clojure
(core/q '(from :refs [*]))
```

```clojure
;; => [{:xt/id    "BarService/bar->System.out.println@src/main/java/com/example/BarService.java:5"
;;      :ref/from "BarService/bar"
;;      :ref/to   "System.out.println"
;;      :ref/kind ":call"
;;      :ref/file "src/main/java/com/example/BarService.java"
;;      :ref/line 5
;;      :ref/col  8
;;      :ref/arity 1}]
```

---

## 3. 基本クエリ

### 特定クラスが呼んでいるもの（ファンアウト）

```clojure
(core/q '(from :refs [{:ref/from from :ref/to to :ref/file file :ref/line line}]
               (where (like from "BarService/%"))
               (order-by line)))
```

```clojure
;; => [{:from "BarService/bar" :to "System.out.println" :file "..." :line 5}]
```

### 特定メソッドを呼んでいる箇所（ファンイン / 使用箇所検索）

```clojure
(core/q '(from :refs [{:ref/from from :ref/to to :ref/file file :ref/line line}]
               (where (= to "System.out.println"))))
```

### ファイル別の呼び出し数

```clojure
(core/q '(from :refs [{:ref/file file}]
               (aggregate {:count (count file)})
               (order-by (desc count))))
```

---

## 4. ファイルツリーと結合する

`:files` テーブルと `:refs` テーブルを `:ref/file` で結合すると、
パッケージ / ディレクトリ単位の分析ができる。

```clojure
(core/q '(unify (from :refs    [{:ref/from from :ref/to to :ref/file rfile}])
                (from :files   [{:xt/id fid :file/parent parent}]
                       (where (= fid rfile))))
         )
```

> **注意**: `(unify ...)` は XTDB v2 の join 構文。`(join ...)` ではない。

---

## 5. Java と Clojure を横断して見る

`jref!` と `xref!` は同じ `:refs` テーブルに入るため、言語を跨いだ検索ができる。

```clojure
;; Java と Clojure の呼び出しを混在で取得（:ref/file 拡張子で区別）
(core/q '(from :refs [{:ref/from from :ref/to to :ref/file file}]
               (where (or (like file "%.java")
                          (like file "%.clj")))))
```

---

## 6. 限界と注意点

| 項目 | 内容 |
|---|---|
| `:ref/to` の精度 | スコープ付き単純名（例: `System.out.println`）。完全修飾名ではない |
| シンボル解決 | JavaParser のシンボルリゾルバを使っていないため、型情報はない |
| インタフェース/継承 | 呼び出し先の実装クラスは特定できない |
| Clojure `xref!` との差 | `xref!` は完全修飾シンボルを持つ。Java はクラス名ベースのため混在クエリは file 拡張子で区別する |

---

## 7. 後片付け

```clojure
(core/stop!)
```
