(ns workbench.jref
  "Java ソースの cross-reference 解析。JavaParser を使って :refs テーブルへ取り込む。
   解析は静的 AST レベル（シンボルリゾルバなし）。メソッド呼び出しを抽出する。
   scope 解決: FieldDeclaration + implements 宣言を使って 変数名→インターフェース→実装クラス に解決。"
  (:require
   [clojure.java.io  :as io]
   [clojure.set      :as set]
   [clojure.string   :as str]
   [xtdb.api         :as xt])
  (:import
   [com.github.javaparser StaticJavaParser ParserConfiguration ParserConfiguration$LanguageLevel]
   [com.github.javaparser.ast.expr MethodCallExpr]
   [com.github.javaparser.ast.body
    MethodDeclaration ClassOrInterfaceDeclaration FieldDeclaration]))

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

;; -------------------------
;; scope 解決（フィールド宣言 + implements 解析）
;; -------------------------

;; java-files は後で定義されるため前方宣言
(declare java-files)

(defn- build-iface-impl-map
  "全パスの .java ファイルを解析し {インターフェース名 → 実装クラス名} を返す。
   同一インターフェースに複数の実装がある場合は後勝ち（単純化）。"
  [roots]
  (->> roots
       (mapcat java-files)
       (mapcat (fn [^java.io.File f]
                 (try
                   (let [cu (StaticJavaParser/parse f)]
                     (->> (.findAll cu ClassOrInterfaceDeclaration)
                          (filter #(not (.isInterface ^ClassOrInterfaceDeclaration %)))
                          (mapcat (fn [^ClassOrInterfaceDeclaration cls]
                                    (let [impl (.getNameAsString cls)]
                                      (map (fn [iface] [(.getNameAsString iface) impl])
                                           (.getImplementedTypes cls)))))))
                   (catch Exception _ []))))
       (into {})))

(defn- build-field-map
  "全パスの .java ファイルを解析し {クラス名 → {フィールド変数名 → 型名}} を返す。"
  [roots]
  (->> roots
       (mapcat java-files)
       (mapcat (fn [^java.io.File f]
                 (try
                   (let [cu (StaticJavaParser/parse f)]
                     (map (fn [^ClassOrInterfaceDeclaration cls]
                            (let [cls-name (.getNameAsString cls)
                                  var->type
                                  (->> (.findAll cls FieldDeclaration)
                                       (mapcat (fn [^FieldDeclaration fd]
                                                 (let [type-name (.asString (.getElementType fd))]
                                                   (map (fn [vd] [(.getNameAsString vd) type-name])
                                                        (.getVariables fd)))))
                                       (into {}))]
                              [cls-name var->type]))
                          (.findAll cu ClassOrInterfaceDeclaration)))
                   (catch Exception _ []))))
       (into {})))

(defn- resolve-scope
  "scope 文字列（変数名 or 式）を実装クラス名に解決する。
   - ドットを含む複合式（System.out 等）はそのまま返す
   - 'this' は from-cls（自クラス）に解決
   - 大文字始まりはすでにクラス名とみなしてそのまま返す
   - 小文字始まりはフィールド変数名として field-map → iface-impl-map の順で解決
   - 解決できない場合は scope をそのまま返す。"
  [scope from-cls field-map iface-impl-map]
  (when scope
    (cond
      (str/includes? scope ".")  scope
      (= scope "this")           from-cls
      (Character/isUpperCase (.charAt ^String scope 0)) scope
      :else
      (let [var->type (get field-map from-cls {})
            iface     (get var->type scope scope)
            impl      (get iface-impl-map iface iface)]
        impl))))

(defn- call->doc
  "MethodCallExpr 1 件を :refs ドキュメントに変換する。
   scope が解決できた場合は :ref/to を ClassName/methodName 形式で記録する。"
  [^MethodCallExpr expr rel-path trial field-map iface-impl-map]
  (let [from      (from-sym expr)
        from-cls  (let [idx (.indexOf ^String from "/")]
                    (if (>= idx 0) (subs from 0 idx) from))
        scope     (some-> (.getScope expr) opt->val str)
        mname     (.getNameAsString expr)
        res-scope (resolve-scope scope from-cls field-map iface-impl-map)
        ;; 解決済み・単純クラス名 → スラッシュ形式（:ref/from と統一）
        ;; 複合式（ドット含む）や未解決 → 従来どおりドット形式
        to        (if res-scope
                    (if (str/includes? res-scope ".")
                      (str res-scope "." mname)
                      (str res-scope "/" mname))
                    mname)
        pos       (opt->val (.getBegin expr))
        line      (some-> pos .-line)
        col       (some-> pos .-column)
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
  [root ^java.io.File f trial field-map iface-impl-map]
  (let [abs      (.getAbsolutePath f)
        root-abs (.getAbsolutePath (io/file root))
        rel      (subs abs (inc (count root-abs)))]
    (try
      (let [cu (.findAll (StaticJavaParser/parse f) MethodCallExpr)]
        (map #(call->doc % rel trial field-map iface-impl-map) cu))
      (catch Exception e
        (binding [*out* *err*]
          (println (str "[jref] parse error: " rel " — " (.getMessage e))))
        []))))

;; -------------------------
;; public API
;; -------------------------

(defn jref!
  "Java ソースの cross-reference を解析して XTDB :refs テーブルに put する。
   FieldDeclaration と implements 宣言を使ってフィールド変数名をクラス名に解決する。
   解決できた場合は :ref/to が ClassName/methodName 形式になる（:ref/from と同形式）。

   paths: 解析対象パスのベクタ（例: [\"trials/samples/repo\"]）
   opts:
     :trial - トライアル識別子（文字列）。省略可。

   例:
     (jref! node [\"trials/samples/repo\"])
     (jref! node [\"src/main/java\"] :trial \"aca-spring\")"
  [node paths & {:keys [trial]}]
  (println "[jref] building scope-resolution maps...")
  (let [iface-impl-map (build-iface-impl-map paths)
        field-map      (build-field-map paths)
        _              (println (str "[jref] iface-impl-map: " (count iface-impl-map)
                                    " entries, field-map: " (count field-map) " classes"))
        docs    (->> paths
                     (mapcat (fn [root]
                               (mapcat #(parse-file root % trial field-map iface-impl-map)
                                       (java-files root))))
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
