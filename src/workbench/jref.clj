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
   [com.github.javaparser.ast.expr MethodCallExpr ObjectCreationExpr]
   [com.github.javaparser.ast.body
    MethodDeclaration ClassOrInterfaceDeclaration FieldDeclaration
    RecordDeclaration]))

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
  [expr]
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
  [^MethodCallExpr expr rel-path trial field-map iface-impl-map & {:keys [tag]}]
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
        id-prefix (if trial (str trial "::") "")
        doc       {:xt/id     (str id-prefix from "->" to)
                   :ref/trial trial
                   :ref/kind  ":call"
                   :ref/from  from
                   :ref/to    to
                   :ref/file  rel-path
                   :ref/line  line
                   :ref/col   col
                   :ref/arity (.size (.getArguments expr))}]
    (cond-> doc
      tag (assoc :ref/tag tag))))

;; -------------------------
;; file-level parsing
;; -------------------------

(defn- java-files
  "root 以下の .java ファイルを列挙する。"
  [root]
  (->> (file-seq (io/file root))
       (filter #(and (.isFile ^java.io.File %)
                     (.endsWith (.getName ^java.io.File %) ".java")))))

(defn- new->doc
  "ObjectCreationExpr 1 件を :refs ドキュメントに変換する。
   :ref/to は ClassName/<init> 形式で記録する。"
  [^ObjectCreationExpr expr rel-path trial & {:keys [tag]}]
  (let [from      (from-sym expr)
        cls-name  (.getNameAsString (.getType expr))
        to        (str cls-name "/<init>")
        pos       (opt->val (.getBegin expr))
        line      (some-> pos .-line)
        col       (some-> pos .-column)
        id-prefix (if trial (str trial "::") "")
        doc       {:xt/id     (str id-prefix from "->" to)
                   :ref/trial trial
                   :ref/kind  ":new"
                   :ref/from  from
                   :ref/to    to
                   :ref/file  rel-path
                   :ref/line  line
                   :ref/col   col}]
    (cond-> doc
      tag (assoc :ref/tag tag))))
(defn- parse-file
  "1 つの .java ファイルを解析し、call-doc のシーケンスを返す。
   パースエラーは警告を出してスキップする。"
  [root ^java.io.File f trial field-map iface-impl-map & {:keys [tag]}]
  (let [abs      (.getAbsolutePath f)
        root-abs (.getAbsolutePath (io/file root))
        rel      (subs abs (inc (count root-abs)))]
    (try
      (let [parsed (StaticJavaParser/parse f)
            calls  (.findAll parsed MethodCallExpr)
            news   (.findAll parsed ObjectCreationExpr)]
        (concat
          (map #(call->doc % rel trial field-map iface-impl-map :tag tag) calls)
          (map #(new->doc % rel trial :tag tag) news)))

      (catch Throwable e
        (println (str "[jref] parse error: " rel " — " (.getMessage e)))
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
     :tag   - 参照の分類タグ（例: \"gen-tests\"）。省略可。

   例:
     (jref! node [\"trials/samples/repo\"])
     (jref! node [\"src/main/java\"] :trial \"aca-spring\")
     (jref! node [\"exports/gen-tests\"] :trial \"trial-1\" :tag \"gen-tests\")"
  [node paths & {:keys [trial tag]}]
  (println "[jref] building scope-resolution maps...")
  (let [iface-impl-map (build-iface-impl-map paths)
        field-map      (build-field-map paths)
        _              (println (str "[jref] iface-impl-map: " (count iface-impl-map)
                                    " entries, field-map: " (count field-map) " classes"))
        docs    (->> paths
                     (mapcat (fn [root]
                               (mapcat #(parse-file root % trial field-map iface-impl-map :tag tag)
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

;; -------------------------
;; メソッドシグネチャ抽出（:jsigs テーブル）
;; -------------------------

(defn- method->sig-doc
  "MethodDeclaration 1 件を :jsigs ドキュメントに変換する。"
  [^MethodDeclaration md cls-name pkg trial]
  (let [mname   (.getNameAsString md)
        params  (->> (.getParameters md)
                     (mapv (fn [p]
                             {:name (.getNameAsString p)
                              :type (.asString (.getType p))})))
        ;; オーバーロード識別用のパラメータ型リスト
        ptypes  (str/join "," (map :type params))
        ret     (.asString (.getType md))
        throws  (->> (.getThrownExceptions md)
                     (mapv #(.asString %)))
        mods    (->> (.getModifiers md)
                     (mapv #(-> % .getKeyword .asString)))]
    {:xt/id         (str "jsig/" (or trial "_") "/" cls-name "/" mname "(" ptypes ")")
     :jsig/trial    trial
     :jsig/package  pkg
     :jsig/class    cls-name
     :jsig/method   mname
     :jsig/params   params
     :jsig/return   ret
     :jsig/throws   throws
     :jsig/mods     mods}))

(defn- extract-sigs-from-file
  "1 つの .java ファイルを解析し、メソッドシグネチャドキュメントのシーケンスを返す。"
  [^java.io.File f trial]
  (try
    (let [cu  (StaticJavaParser/parse f)
          pkg (-> cu .getPackageDeclaration
                  (.map #(.getNameAsString %))
                  (.orElse ""))]
      (concat
        ;; 通常クラス・インターフェース
        (->> (.findAll cu ClassOrInterfaceDeclaration)
             (mapcat (fn [^ClassOrInterfaceDeclaration cls]
                       (let [cls-name (.getNameAsString cls)]
                         (->> (.findAll cls MethodDeclaration)
                              (map #(method->sig-doc % cls-name pkg trial)))))))
        ;; Java record — コンポーネントをアクセサとして登録 + 明示的メソッド
        (->> (.findAll cu RecordDeclaration)
             (mapcat (fn [^RecordDeclaration rec]
                       (let [cls-name (.getNameAsString rec)
                             accessors (->> (.getParameters rec)
                                           (map (fn [p]
                                                  {:xt/id        (str "jsig/" (or trial "_") "/" cls-name "/" (.getNameAsString p) "()")
                                                   :jsig/trial   trial
                                                   :jsig/package pkg
                                                   :jsig/class   cls-name
                                                   :jsig/method  (.getNameAsString p)
                                                   :jsig/params  []
                                                   :jsig/return  (.asString (.getType p))
                                                   :jsig/throws  []
                                                   :jsig/mods    ["public"]})))
                             methods  (->> (.findAll rec MethodDeclaration)
                                          (map #(method->sig-doc % cls-name pkg trial)))]
                         (concat accessors methods)))))))

    (catch Exception e
      (println (str "[jsig] parse error: " (.getName f) " — " (.getMessage e)))
      [])))

(defn jsig!
  "Java ソースのメソッドシグネチャを解析して XTDB :jsigs テーブルに取り込む（差分同期・冪等）。

   paths: 解析対象パスのベクタ
   opts:
     :trial - トライアル識別子

   例:
     (jsig! node [\"trials/experiments/2026-04-28-tradehub/repo\"] :trial \"tradehub\")"
  [node paths & {:keys [trial]}]
  (let [docs    (->> paths
                     (mapcat java-files)
                     (mapcat #(extract-sigs-from-file % trial))
                     vec)
        new-ids (set (map :xt/id docs))
        old-ids (->> (xt/q node '(from :jsigs [{:xt/id id :jsig/trial t}]))
                     (filter #(= (:t %) trial))
                     (map :id)
                     set)
        to-del  (set/difference old-ids new-ids)]
    (when (seq to-del)
      (xt/execute-tx node (mapv #(vector :delete-docs :jsigs %) to-del)))
    (doseq [batch (partition-all 2000 docs)]
      (xt/execute-tx node (mapv #(vector :put-docs :jsigs %) batch)))
    {:put (count docs) :delete (count to-del)}))

(defn jsigs
  "XTDB :jsigs テーブルからメソッドシグネチャを返す。
   opts:
     :trial  - トライアル識別子でフィルタ
     :class  - クラス名（シンプル名）でフィルタ
     :method - メソッド名でフィルタ

   例:
     (jsigs node :trial \"tradehub\" :class \"DocumentAggregateServiceImpl\")
     (jsigs node :trial \"tradehub\" :class \"DocumentAggregateServiceImpl\"
                                    :method \"resolveTargetProcessIds\")"
  [node & {:keys [trial class method]}]
  (let [rs (xt/q node '(from :jsigs [*]))]
    (cond->> rs
      trial  (filter #(= trial (:jsig/trial %)))
      class  (filter #(= class (:jsig/class %)))
      method (filter #(= method (:jsig/method %))))))

;; -------------------------
;; メソッドボディ抽出（:jbodies テーブル）
;; -------------------------

(defn- method->body-doc
  "MethodDeclaration 1 件を :jbodies ドキュメントに変換する。
   ボディは 4000 文字を上限として切り捨てる（トークン節約）。"
  [^MethodDeclaration md cls-name pkg trial]
  (let [mname  (.getNameAsString md)
        ptypes (str/join "," (->> (.getParameters md) (map #(.asString (.getType %)))))
        body   (-> (.getBody md)
                   (.map #(.toString %))
                   (.orElse nil))]
    (when body
      {:xt/id         (str "jbody/" (or trial "_") "/" cls-name "/" mname "(" ptypes ")")
       :jbody/trial   trial
       :jbody/package pkg
       :jbody/class   cls-name
       :jbody/method  mname
       :jbody/body    (subs body 0 (min (count body) 4000))})))

(defn- extract-bodies-from-file
  "1 つの .java ファイルを解析し、:jbodies ドキュメントのシーケンスを返す。"
  [^java.io.File f trial]
  (try
    (let [cu  (StaticJavaParser/parse f)
          pkg (-> cu .getPackageDeclaration
                  (.map #(.getNameAsString %))
                  (.orElse ""))]
      (->> (.findAll cu ClassOrInterfaceDeclaration)
           (mapcat (fn [^ClassOrInterfaceDeclaration cls]
                     (let [cls-name (.getNameAsString cls)]
                       (->> (.findAll cls MethodDeclaration)
                            (keep #(method->body-doc % cls-name pkg trial))))))))

    (catch Exception e
      (println (str "[jbody] parse error: " (.getName f) " — " (.getMessage e)))
      [])))

(defn jbody!
  "Java ソースのメソッドボディを XTDB :jbodies テーブルに取り込む（差分同期・冪等）。

   paths: 解析対象パスのベクタ（src/main/java ルート等）
   opts:
     :trial - トライアル識別子

   例:
     (jbody! node [\"trials/experiments/2026-04-28-tradehub/repo\"] :trial \"tradehub\")"
  [node paths & {:keys [trial]}]
  (let [docs    (->> paths
                     (mapcat java-files)
                     (mapcat #(extract-bodies-from-file % trial))
                     vec)
        new-ids (set (map :xt/id docs))
        old-ids (->> (xt/q node '(from :jbodies [{:xt/id id :jbody/trial t}]))
                     (filter #(= (:t %) trial))
                     (map :id)
                     set)
        to-del  (set/difference old-ids new-ids)]
    (when (seq to-del)
      (xt/execute-tx node (mapv #(vector :delete-docs :jbodies %) to-del)))
    (doseq [batch (partition-all 2000 docs)]
      (xt/execute-tx node (mapv #(vector :put-docs :jbodies %) batch)))
    {:put (count docs) :delete (count to-del)}))

(defn jbodies
  "XTDB :jbodies テーブルからメソッドボディを返す。
   opts:
     :trial  - トライアル識別子でフィルタ
     :class  - クラス名（シンプル名）でフィルタ
     :method - メソッド名でフィルタ

   例:
     (jbodies node :trial \"tradehub\" :class \"AclServiceImpl\" :method \"updateRow1\")"
  [node & {:keys [trial class method]}]
  (let [rs (xt/q node '(from :jbodies [*]))]
    (cond->> rs
      trial  (filter #(= trial (:jbody/trial %)))
      class  (filter #(= class (:jbody/class %)))
      method (filter #(= method (:jbody/method %))))))

;; -------------------------
;; テストクラス解析（:test-refs テーブル）
;; -------------------------

(defn- annotation-names
  "ノードに付いたアノテーション名のセットを返す。"
  [node]
  (->> (.getAnnotations node)
       (map #(.getNameAsString %))
       set))

(defn- field-type-str
  "FieldDeclaration の最初の変数の型を文字列で返す。"
  [^com.github.javaparser.ast.body.FieldDeclaration fd]
  (-> fd .getVariables (.get 0) .getType .asString))

(defn- extract-test-info
  "1 つの *Test.java ファイルを解析し、:test-refs ドキュメントのシーケンスを返す。"
  [^java.io.File f trial]
  (try
    (let [cu       (StaticJavaParser/parse f)
          pkg      (-> cu .getPackageDeclaration
                       (.map #(.getNameAsString %))
                       (.orElse ""))
          file-rel (.getPath f)]
      (->> (.findAll cu ClassOrInterfaceDeclaration)
           (filter #(not (.isInterface ^ClassOrInterfaceDeclaration %)))
           (mapcat
             (fn [^ClassOrInterfaceDeclaration cls]
               (let [cls-name (.getNameAsString cls)
                     fields   (.findAll cls FieldDeclaration)
                     ;; @InjectMocks → テスト対象クラス（最初の1件）
                     target   (->> fields
                                   (filter #(contains? (annotation-names %) "InjectMocks"))
                                   (map field-type-str)
                                   first)
                     ;; @Mock / @MockBean / @Spy / @SpyBean → モック依存
                     mocks    (->> fields
                                   (filter #(some (annotation-names %)
                                                  ["Mock" "MockBean" "Spy" "SpyBean"]))
                                   (mapv field-type-str))]
                 ;; @Test / @ParameterizedTest が付いたメソッドだけ収集
                 (->> (.findAll cls MethodDeclaration)
                      (filter (fn [^MethodDeclaration md]
                                (some (annotation-names md)
                                      ["Test" "ParameterizedTest"])))
                      (map (fn [^MethodDeclaration md]
                             (let [mname     (.getNameAsString md)
                                   anns      (annotation-names md)
                                   disabled? (contains? anns "Disabled")]
                               {:xt/id          (str "tref/" (or trial "_") "/" cls-name "/" mname)
                                :tref/trial     trial
                                :tref/class     cls-name
                                :tref/method    mname
                                :tref/target    target
                                :tref/mocks     mocks
                                :tref/disabled? (boolean disabled?)
                                :tref/package   pkg
                                :tref/file      file-rel})))))))))

    (catch Exception e
      (println (str "[tref] parse error: " (.getName f) " — " (.getMessage e)))
      [])))

(defn tref!
  "テストクラス（*Test.java）の構造を解析して XTDB :test-refs テーブルに取り込む（差分同期・冪等）。

   @InjectMocks フィールドからテスト対象クラスを、@Mock/@MockBean フィールドから
   モック依存を抽出し、テストメソッド単位でドキュメントを生成する。

   paths: 解析対象パスのベクタ（test ディレクトリを指定）
   opts:
     :trial - トライアル識別子

   例:
     (tref! node [\"trials/experiments/2026-04-28-tradehub/repo/common-lib/src/test\"]
            :trial \"2026-04-28-tradehub\")"
  [node paths & {:keys [trial]}]
  (let [docs    (->> paths
                     (mapcat (fn [root]
                               (->> (java-files root)
                                    (filter #(.endsWith (.getName ^java.io.File %) "Test.java"))
                                    (mapcat #(extract-test-info % trial)))))
                     vec)
        new-ids (set (map :xt/id docs))
        old-ids (->> (xt/q node '(from :test-refs [{:xt/id id :tref/trial t}]))
                     (filter #(= (:t %) trial))
                     (map :id)
                     set)
        to-del  (set/difference old-ids new-ids)]
    (when (seq to-del)
      (xt/execute-tx node (mapv #(vector :delete-docs :test-refs %) to-del)))
    (doseq [batch (partition-all 2000 docs)]
      (xt/execute-tx node (mapv #(vector :put-docs :test-refs %) batch)))
    {:put (count docs) :delete (count to-del)}))

(defn trefs
  "XTDB :test-refs テーブルからテストメソッド情報を返す。
   opts:
     :trial    - トライアル識別子でフィルタ
     :class    - テストクラス名でフィルタ（例: \"DocumentAggregateServiceImplTest\"）
     :target   - テスト対象クラス名でフィルタ（例: \"DocumentAggregateServiceImpl\"）
     :disabled - true のみ返す場合は true を指定

   例:
     (trefs node :trial \"2026-04-28-tradehub\" :target \"DocumentAggregateServiceImpl\")
     (trefs node :trial \"2026-04-28-tradehub\" :disabled true)"
  [node & {:keys [trial class target disabled]}]
  (let [rs (xt/q node '(from :test-refs [*]))]
    (cond->> rs
      trial    (filter #(= trial (:tref/trial %)))
      class    (filter #(= class (:tref/class %)))
      target   (filter #(= target (:tref/target %)))
      disabled (filter :tref/disabled?))))
