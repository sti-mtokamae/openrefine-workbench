(ns workbench.jref
  "Java ソースの cross-reference 解析。JavaParser を使って :refs テーブルへ取り込む。
   解析は静的 AST レベル（シンボルリゾルバなし）。メソッド呼び出しを抽出する。"
  (:require
   [clojure.java.io :as io]
   [clojure.set     :as set]
   [xtdb.api        :as xt])
  (:import
   [com.github.javaparser StaticJavaParser ParserConfiguration ParserConfiguration$LanguageLevel]
   [com.github.javaparser.ast.expr MethodCallExpr]
   [com.github.javaparser.ast.body MethodDeclaration ClassOrInterfaceDeclaration]))

;; Java 21 の構文（Record, Switch expression, Text Block, Pattern matching 等）を解析できるよう設定
(.setLanguageLevel (StaticJavaParser/getParserConfiguration)
                   ParserConfiguration$LanguageLevel/JAVA_21)

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
  [^MethodCallExpr expr rel-path trial]
  (let [from  (from-sym expr)
        scope (some-> (.getScope expr) opt->val str)
        mname (.getNameAsString expr)
        to    (if scope (str scope "." mname) mname)
        pos   (opt->val (.getBegin expr))
        line  (some-> pos .-line)
        col   (some-> pos .-column)
        id-prefix (if trial (str trial "::") "")]
    {:xt/id     (str id-prefix from "->" to)
     :ref/trial trial
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
  [root ^java.io.File f trial]
  (let [abs      (.getAbsolutePath f)
        root-abs (.getAbsolutePath (io/file root))
        rel      (subs abs (inc (count root-abs)))]
    (try
      (let [cu (.findAll (StaticJavaParser/parse f) MethodCallExpr)]
        (map #(call->doc % rel trial) cu))
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
   opts:
     :trial - トライアル識別子（文字列）。省略可。

   例:
     (jref! node [\"trials/samples/repo\"])
     (jref! node [\"src/main/java\"] :trial \"aca-spring\")"
  [node paths & {:keys [trial]}]
  (let [docs    (->> paths
                     (mapcat (fn [root]
                               (mapcat #(parse-file root % trial) (java-files root))))
                     vec)
        new-ids (set (map :xt/id docs))
        old-ids (->> (xt/q node '(from :refs [{:xt/id id :ref/trial t}]))
                     (filter #(= (:t %) trial))
                     (map :id)
                     set)
        del-txs (mapv (fn [id] [:delete-docs :refs id]) (set/difference old-ids new-ids))]
    (when (seq del-txs) (xt/execute-tx node del-txs))
    (doseq [batch (partition-all 2000 docs)]
      (xt/execute-tx node (mapv #(vector :put-docs :refs %) batch)))
    (count docs)))
