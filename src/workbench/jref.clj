(ns workbench.jref
  "Java ソースの cross-reference 解析。JavaParser を使って :refs テーブルへ取り込む。
   解析は静的 AST レベル（シンボルリゾルバなし）。メソッド呼び出しを抽出する。"
  (:require
   [clojure.java.io :as io]
   [xtdb.api        :as xt])
  (:import
   [com.github.javaparser StaticJavaParser]
   [com.github.javaparser.ast.expr MethodCallExpr]
   [com.github.javaparser.ast.body MethodDeclaration ClassOrInterfaceDeclaration]))

;; -------------------------
;; helpers
;; -------------------------

(defn- opt->val [^java.util.Optional opt]
  (when (.isPresent opt) (.get opt)))

(defn- find-ancestor
  "expr から上に向かって親ノードを辿り、cls のインスタンスを返す。
   見つからなければ nil。（findAncestor varargs 競合を回避）"
  [node ^Class cls]
  (loop [opt (.getParentNode node)]
    (when (.isPresent opt)
      (let [p (.get opt)]
        (if (instance? cls p)
          p
          (recur (.getParentNode p)))))))

(defn- from-sym
  "呼び出し式が属するクラス名/メソッド名を返す。
   例: \"FooController/foo\"  (ネストしていない場合は \"<top-level>\")"
  [^MethodCallExpr expr]
  (let [cls (some-> (find-ancestor expr ClassOrInterfaceDeclaration) .getNameAsString)
        mth (some-> (find-ancestor expr MethodDeclaration)           .getNameAsString)]
    (cond
      (and cls mth) (str cls "/" mth)
      cls           (str cls "/<init>")
      :else         "<top-level>")))

(defn- call->doc
  "MethodCallExpr 1 件を :refs ドキュメントに変換する。"
  [^MethodCallExpr expr rel-path]
  (let [from  (from-sym expr)
        scope (some-> (.getScope expr) opt->val str)
        mname (.getNameAsString expr)
        to    (if scope (str scope "." mname) mname)
        pos   (opt->val (.getBegin expr))
        line  (some-> pos .-line)
        col   (some-> pos .-column)]
    {:xt/id     (str from "->" to "@" rel-path ":" line)
     :ref/kind  ":call"
     :ref/from  from
     :ref/to    to
     :ref/file  rel-path
     :ref/line  line
     :ref/col   col
     :ref/arity (.size (.getArguments expr))}))

;; -------------------------
;; file-level parsing
;; -------------------------

(defn- java-files
  "root 以下の .java ファイルを列挙する。"
  [root]
  (->> (file-seq (io/file root))
       (filter #(and (.isFile ^java.io.File %)
                     (.endsWith (.getName ^java.io.File %) ".java")))))

(defn- parse-file
  "1 つの .java ファイルを解析し、call-doc のシーケンスを返す。
   パースエラーは警告を出してスキップする。"
  [root ^java.io.File f]
  (let [abs      (.getAbsolutePath f)
        root-abs (.getAbsolutePath (io/file root))
        rel      (subs abs (inc (count root-abs)))]
    (try
      (let [cu (.findAll (StaticJavaParser/parse f) MethodCallExpr)]
        (map #(call->doc % rel) cu))
      (catch Exception e
        (binding [*out* *err*]
          (println (str "[jref] parse error: " rel " — " (.getMessage e))))
        []))))

;; -------------------------
;; public API
;; -------------------------

(defn jref!
  "Java ソースの cross-reference を解析して XTDB :refs テーブルに put する。
   JavaParser (静的解析) を使う。シンボルリゾルバなしのため :ref/to はスコープ付き
   単純名 (例: \"System.out.println\") になる。

   paths: 解析対象パスのベクタ（例: [\"trials/samples/repo\"]）

   例:
     (jref! node [\"trials/samples/repo\"])
     (jref! node [\"src/main/java\"])"
  [node paths]
  (let [docs (->> paths
                  (mapcat (fn [root]
                            (mapcat #(parse-file root %) (java-files root))))
                  vec)
        txs  (mapv #(vector :put-docs :refs %) docs)]
    (when (seq txs)
      (xt/execute-tx node txs))
    (count txs)))
