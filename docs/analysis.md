# コード解析 end-to-end ガイド

Clojure / Java のソースコードを XTDB に取り込み、クエリで読み解く一連の流れ。

**サンプルとして、このワークベンチ自身のソース（`src/`）を解析する。**  
実行中の REPL が自分自身のコードを読み込む構造—いわばデバッガで自分をデバッグする形。  
（詳細: [trials/samples/self-analysis/notes.md](../trials/samples/self-analysis/notes.md)）

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

```clojure
(core/ingest! "src")    ; ファイルツリー → :files
(core/xref!   ["src"])  ; 呼び出しグラフ → :refs
```

Java ソースが混在する場合：

```clojure
(core/ingest! "trials/samples/repo")
(core/jref!   ["trials/samples/repo"])  ; Java xref → 同じ :refs テーブルへ
```

---

## 2. 何が入ったか確認する

```clojure
(core/tree)
```

```
src/
  openrefine_runner.clj
  workbench/
    core.clj
    ingest.clj
    jref.clj
    query.clj
    visualize.clj
```

```clojure
;; :refs の先頭3件
(core/q '(from :refs [{:ref/from from :ref/to to :ref/file file}]
               (order-by from)
               (limit 3)))

;; => [{:from "openrefine-runner/<top-level>" :to "clojure.core/defn"
;;      :file "src/openrefine_runner.clj"}
;;     {:from "openrefine-runner/apply-operations!" :to "clojure.core/."
;;      :file "src/openrefine_runner.clj"}
;;     {:from "openrefine-runner/create-project!" :to "clojure.core/ex-info"
;;      :file "src/openrefine_runner.clj"}]
```

---

## 3. 基本クエリ

### プロジェクト内部の呼び出しのみ（`clojure.core` を除外）

```clojure
(core/q '(from :refs [{:ref/from from :ref/to to :ref/file file}]
               (where (not (like to "clojure.%")))
               (order-by from)))
```

実際の結果（抜粋）：

```clojure
;; [{:from "openrefine-runner/export-results!" :to "openrefine-runner/http-client" ...}
;;  {:from "openrefine-runner/fetch-csrf-token" :to "openrefine-runner/http-get-text" ...}
;;  {:from "openrefine-runner/normalize-trial"  :to "openrefine-runner/input-files" ...}
;;  {:from "openrefine-runner/normalize-trial"  :to "openrefine-runner/resolve-path" ...}
;;  {:from "openrefine-runner/run-trial"        :to "openrefine-runner/create-project!" ...}
;;  {:from "openrefine-runner/run-trial"        :to "openrefine-runner/open-browser!" ...}
;;  {:from "workbench.core/ingest!"  :to "workbench.ingest/dir!" ...}
;;  {:from "workbench.core/jref!"    :to "workbench.jref/jref!" ...}
;;  {:from "workbench.core/q"        :to "workbench.query/q" ...}
;;  {:from "workbench.core/tree"     :to "workbench.visualize/tree" ...}
;;  {:from "workbench.ingest/dir!"   :to "workbench.ingest/dir" ...}
;;  {:from "workbench.ingest/xref!"  :to "clj-xref.core/analyze" ...}
;;  {:from "workbench.jref/call->doc" :to "workbench.jref/from-sym" ...}
;;  {:from "workbench.jref/from-sym"  :to "workbench.jref/find-ancestor" ...}
;;  {:from "workbench.jref/parse-file" :to "workbench.jref/call->doc" ...}
;;  {:from "workbench.visualize/render-tree" :to "workbench.visualize/render-tree" ...}  ; 再帰
;;  {:from "workbench.visualize/tree" :to "workbench.visualize/render-tree" ...}]
```

これが `workbench.core` の委譲構造です：

```
core/ingest!  → ingest/dir!
core/xref!    → ingest/xref!  → clj-xref.core/analyze
core/jref!    → jref/jref!
core/q        → query/q
core/tree     → visualize/tree → visualize/render-tree（再帰）
```

### 特定関数のファンアウト（何を呼んでいるか）

```clojure
(core/q '(from :refs [{:ref/from from :ref/to to}]
               (where (= from "workbench.core/ingest!"))
               (order-by to)))

;; => [{:from "workbench.core/ingest!" :to "workbench.core/node"}
;;     {:from "workbench.core/ingest!" :to "workbench.ingest/dir!"}]
```

### 特定関数のファンイン（誰から呼ばれているか）

```clojure
(core/q '(from :refs [{:ref/from from :ref/to to}]
               (where (= to "workbench.ingest/dir!"))
               (order-by from)))

;; => [{:from "workbench.core/ingest!" :to "workbench.ingest/dir!"}]
```

### ファイル別の呼び出し数

```clojure
(core/q '(from :refs [{:ref/file file}]
               (aggregate {:count (count file)})
               (order-by (desc count))))
```

### ファンアウト/ファンイン集計（Clojure 側でまとめる）

XTDB の `aggregate` より柔軟。`core/refs` の戻り値（seq of map）を直接操作する。

```clojure
;; ファンアウト TOP — 何を多く呼ぶ関数か
(->> (core/refs)
     (group-by :from)
     (map (fn [[k v]] {:from k :fanout (count v)}))
     (sort-by :fanout >)
     (take 5))

;; => [{:from "openrefine-runner/normalize-trial" :fanout 18}
;;     {:from "openrefine-runner/run-trial"        :fanout 9}
;;     {:from "openrefine-runner/multipart-body"   :fanout 6}
;;     {:from "openrefine-runner/create-project!"  :fanout 3}
;;     {:from "workbench.jref/call->doc"           :fanout 3}]

;; ファンイン TOP — 誰から多く呼ばれる関数か
(->> (core/refs)
     (group-by :to)
     (map (fn [[k v]] {:to k :fanin (count v)}))
     (sort-by :fanin >)
     (take 5))

;; => [{:to "openrefine-runner/utf8-bytes"   :fanin 6}
;;     {:to "workbench.core/state"            :fanin 5}
;;     {:to "workbench.core/node"             :fanin 4}
;;     {:to "openrefine-runner/resolve-path"  :fanin 4}
;;     {:to "workbench.visualize/render-tree" :fanin 3}]
```

`normalize-trial`（fanout 18）が突出 → 責務が広い関数の目印。  
`utf8-bytes`（fanin 6）が最多 → 多用されるユーティリティ。

---

## 4. 呼び出し木を表示する

```clojure
(core/call-tree (core/refs) "workbench.core/ingest!")
```

```
workbench.core/ingest!
  workbench.core/node
    workbench.core/state
  workbench.ingest/dir!
    workbench.ingest/dir
      workbench.ingest/file->doc
```

名前空間を絞り込んでから木を承ることもできる：

```clojure
(core/call-tree (core/refs) "workbench.jref/jref!")
```

```
workbench.jref/jref!
  workbench.jref/java-files
  workbench.jref/parse-file
    workbench.jref/call->doc
      workbench.jref/from-sym
        workbench.jref/find-ancestor
        workbench.jref/find-ancestor [...]
      workbench.jref/opt->val
      workbench.jref/opt->val [...]
```

> **`[...]` の意味**: 同じノードが既に展開済みの場合、再展開せず `[...]` で示す。  
> DAG（複数経路から同じノードに到達）でも循環参照でも同様に抑制される。

`core/refs` に名前空間フィルタを組み合わせると範囲を絞れる：

### Java での call-tree（`trials/samples/aca-spring`）

```clojure
(core/ingest! "trials/samples/aca-spring/src/main/java")
(core/jref!   ["trials/samples/aca-spring/src/main/java"])
(let [refs (core/q '(from :refs [{:ref/from from :ref/to to}]))]
  (core/call-tree refs "AuthController/login"))
```

```
AuthController/login
  Map.of
  Map.of [...]
  ResponseEntity.ok
  ResponseEntity.status
  ResponseEntity.status(HttpStatus.UNAUTHORIZED).body
  credentials.get
  credentials.get [...]
  jwtProvider.generateToken
  passwordEncoder.matches
  userDetails.getPassword
  userDetailsService.loadUserByUsername
```

`Map.of` / `credentials.get` が `[...]` になっているのは同一ノードの再展開抑制。
fanout 11 は認証フローをこの 1 関数で処理していることを示す。

```clojure
(core/call-tree refs "JwtAuthenticationFilter/doFilterInternal")
```

```
JwtAuthenticationFilter/doFilterInternal
  List.of
  SecurityContextHolder.getContext
  SecurityContextHolder.getContext().setAuthentication
  authHeader.startsWith
  authHeader.substring
  filterChain.doFilter
  jwtProvider.extractUsername
  jwtProvider.isTokenValid
  request.getHeader
```

`jwtProvider.*` は `@Autowired` フィールドだが、**明示的メソッド呼び出しは取れる**。



```clojure
(core/call-tree (core/refs "workbench.core") "workbench.core/tree")
```

```
workbench.core/tree
  workbench.core/q
    workbench.core/node
      workbench.core/state
    workbench.query/q
  workbench.visualize/tree
    workbench.visualize/build-tree
    workbench.visualize/render-tree
      workbench.visualize/render-tree [...]
```

---

## 5. ファイルツリーと結合する

`:files` と `:refs` を `:ref/file` で結合してディレクトリ単位で集計できる。

```clojure
(core/q '(unify (from :refs  [{:ref/from from :ref/to to :ref/file rfile}]
                      (where (not (like to "clojure.%"))))
                (from :files [{:xt/id fid :file/parent parent}]
                      (where (= fid rfile)))))
```

> **注意**: XTDB v2 の join 構文は `(unify ...)` を使う。`(join ...)` ではない。

---

## 6. Java と Clojure を横断して見る

`jref!` と `xref!` は同じ `:refs` テーブルに入るため、言語を跨いだ検索ができる。

**サンプル**: `trials/samples/aca-spring/` には `HelloController → ClojureHelloService`  
（Java→Clojure ブリッジ）が含まれる。

```clojure
;; Java ソースを jref! で取り込む
(core/ingest! "trials/samples/aca-spring/src/main/java")
(core/jref!   ["trials/samples/aca-spring/src/main/java"])

;; Clojure ソースも xref! で取り込む（同じ :refs テーブルへ）
(core/ingest! "src")
(core/xref!   ["src"])
```

```clojure
;; Java 側の HelloController から Clojure 側まで call-tree で追う
(let [refs (core/q '(from :refs [{:ref/from from :ref/to to}]))]
  (core/call-tree refs "HelloController/hello"))

;; =>
;; HelloController/hello
;;   clojureHelloService.hello   ← Java→Clojure ブリッジ（葉で止まる）
```

> **注意**: `clojureHelloService.hello` は Java 側の `:ref/to` 。  
> Clojure 側の `hello-payload` まで繋がらない。シンボルが一致しないためクロスは困難。  
> Java→Clojure ブリッジの存在確認には `:ref/file` 拡張子で区別するクエリが有効：

```clojure
;; .java ファイルからの呼び出しのうち、to に "Clojure.var" を含むもの
(core/q '(from :refs [{:ref/from from :ref/to to :ref/file file}]
               (where (like file "%.java"))))
;; ClojureHelloService/<init> → Clojure.var (×4) が確認できる
```

---

## 7. 限界と注意点

| 項目 | 内容 |
|---|---|
| `:ref/to` の精度 | スコープ付き単純名（例: `System.out.println`）。完全修飾名ではない |
| シンボル解決 | JavaParser のシンボルリゾルバを使っていないため、型情報はない |
| インタフェース/継承 | 呼び出し先の実装クラスは特定できない |
| DI / `@Autowired` | 注入の依存関係は取れない。フィールド経由の**メソッド呼び出し**は取れる |
| method chaining | `a.b().c().d()` が中間ノードごとに個別エントリになる（ノイズ増） |
| Java→Clojure 横断 | `clojureHelloService.hello` 止まり。Clojure 側の関数名と一致しない |
| Clojure `xref!` との差 | `xref!` は完全修飾シンボルを持つ。Java はクラス名ベースのため混在クエリは `:ref/file` 拡張子で区別する |

---

## 8. グラフを Gephi で可視化する

千クラス・万メソッド規模のコードベースはテキスト出力では全体像を掴みにくい。
`export-gexf!` で GEXF ファイルを出力し、Gephi（Windows 推奨）で可視化する。

### GEXF を出力する

```clojure
;; クラス単位（Gephi での俯瞰用・推奨）
(core/export-gexf! (core/jrefs :trial "tradehub")
                   "exports/tradehub-class.gexf"
                   :level :class)

;; メソッド単位（局所ビュー用・ファイルが大きい）
(core/export-gexf! (core/jrefs :trial "tradehub")
                   "exports/tradehub-method.gexf"
                   :level :method)
```

`:class` レベルでは同クラス間エッジの重複が `weight` として集計される。
フィルタ済みの refs を渡すことで特定ノイズ（ACL/IDA 等）を除外できる。

### ファイルサイズの目安

| レベル | 1202クラス(tradehub) |
|---|---|
| `:class` | 約 670 KB |
| `:method` | 約 1.3 MB |

### Gephi の基本ワークフロー

1. **開く** — File → Open → `.gexf`
2. **レイアウト** — Layout パネル → **ForceAtlas 2** → Run（数十秒〜数分）→ Stop
3. **コミュニティ検出** — Statistics → **Modularity** → Run（コミュニティごとに色分け）
4. **色付け** — Appearance → Nodes → Partition → **Modularity Class** → Apply
5. **サイズ** — Appearance → Nodes → Ranking → **Degree**（または In-Degree）→ Apply
6. **フィルタ** — Filters → Topology → **Degree Range** で孤立ノード除外
7. **保存** — File → Save → `.gephi`（以降はここから再開）

### Windows へのファイル受け渡し

```clojure
;; WSL から Windows デスクトップに直接出力
(core/export-gexf! rs "/mnt/c/Users/<username>/Desktop/graph.gexf" :level :class)
```

または エクスプローラーのアドレスバーに `\\wsl$\Ubuntu\home\<user>\...` と入力してアクセス。

---

## 9. 後片付け

```clojure
(core/stop!)
```
