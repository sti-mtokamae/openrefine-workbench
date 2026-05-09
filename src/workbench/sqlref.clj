(ns workbench.sqlref
  "MyBatis @Select/@Insert/@Update/@Delete アノテーションの SQL を解析して
   :sql-refs テーブルへ取り込む。

   抽出する情報:
     :sqlref/param-binds — col = #{param} 形式の直接バインディング
     :sqlref/col-binds   — d.col = alias.col 形式の CTE/テーブル経由縛り条件

   これにより :refs（呼び出しグラフ）と結合して
   「このメソッドが持つ SQL の縛り条件」を XTDB でクエリできる。"
  (:require
   [clojure.java.io  :as io]
   [clojure.set      :as set]
   [clojure.string   :as str]
   [xtdb.api         :as xt])
  (:import
   [com.github.javaparser StaticJavaParser ParserConfiguration$LanguageLevel]
   [com.github.javaparser.ast.expr
    SingleMemberAnnotationExpr StringLiteralExpr TextBlockLiteralExpr]
   [com.github.javaparser.ast.body
    MethodDeclaration ClassOrInterfaceDeclaration]))

;; Java 21 の言語機能（テキストブロック・Record・switch 式）を有効化
(.setLanguageLevel (StaticJavaParser/getParserConfiguration)
                   ParserConfiguration$LanguageLevel/JAVA_21)

;; -------------------------
;; SQL テキスト抽出
;; -------------------------

(def ^:private sql-annotation-names
  #{"Select" "Insert" "Update" "Delete"})

(defn- annotation-sql
  "MyBatis SQL アノテーションから SQL 文字列を取り出す。
   テキストブロック（Java 13+）と通常文字列リテラルに対応。"
  [^com.github.javaparser.ast.expr.AnnotationExpr ann]
  (when (instance? SingleMemberAnnotationExpr ann)
    (let [val (.getMemberValue ^SingleMemberAnnotationExpr ann)]
      (cond
        (instance? TextBlockLiteralExpr val)
        (.asString ^TextBlockLiteralExpr val)
        (instance? StringLiteralExpr val)
        (.asString ^StringLiteralExpr val)))))

;; -------------------------
;; SQL 条件抽出（正規表現ベース）
;; -------------------------

(defn- extract-param-binds
  "col = #{param.field, ...} 形式の MyBatis バインディングを抽出する。
   戻り値: [{:col \"work_process_id\" :param \"context.targetProcessId\"} ...]"
  [sql]
  (let [re #"(?i)(\w+)\s*=\s*#\{([^,}\s]+)"]
    (->> (re-seq re sql)
         (map (fn [[_ col param]] {:col col :param (str/trim param)})))))

(defn- extract-col-binds
  "table.col = alias.col 形式の結合・CTE 縛り条件を抽出する。
   戻り値: [{:lhs \"d.work_process_id\" :rhs \"cp.source_process_id\"} ...]
   （大文字小文字混在・インデントを吸収する）"
  [sql]
  (let [re #"(\w+\.\w+)\s*=\s*(\w+\.\w+)"]
    (->> (re-seq re sql)
         (map (fn [[_ lhs rhs]] {:lhs lhs :rhs rhs})))))

;; -------------------------
;; ファイルレベル解析
;; -------------------------

(defn- java-files [root]
  (->> (file-seq (io/file root))
       (filter #(and (.isFile ^java.io.File %)
                     (.endsWith (.getName ^java.io.File %) ".java")))))

(defn- parse-mapper-file
  "1 つの .java ファイルを解析し、@Select 等のアノテーション付きメソッドから
   sqlref ドキュメントのシーケンスを返す。"
  [root ^java.io.File f trial]
  (let [abs      (.getAbsolutePath f)
        root-abs (.getAbsolutePath (io/file root))
        rel      (subs abs (inc (count root-abs)))]
    (try
      (let [cu      (StaticJavaParser/parse f)
            classes (.findAll cu ClassOrInterfaceDeclaration)]
        (mapcat
         (fn [^ClassOrInterfaceDeclaration cls]
           (let [cls-name (.getNameAsString cls)]
             (->> (.findAll cls MethodDeclaration)
                  (keep
                   (fn [^MethodDeclaration mth]
                     (let [mth-name   (.getNameAsString mth)
                           sql-ann    (->> (.getAnnotations mth)
                                          (filter #(sql-annotation-names (.getNameAsString %)))
                                          first)]
                       (when-let [sql (some-> sql-ann annotation-sql)]
                         (let [id-prefix (if trial (str trial "::") "")
                               sym       (str cls-name "/" mth-name)]
                           {:xt/id              (str id-prefix sym)
                            :sqlref/trial       trial
                            :sqlref/class       cls-name
                            :sqlref/method      mth-name
                            :sqlref/symbol      sym
                            :sqlref/file        rel
                            :sqlref/param-binds (extract-param-binds sql)
                            :sqlref/col-binds   (extract-col-binds sql)}))))))))
         classes))
      (catch Exception e
        (binding [*out* *err*]
          (println (str "[sqlref] parse error: " rel " — " (.getMessage e))))
        []))))

;; -------------------------
;; public API
;; -------------------------

(defn sqlref!
  "Java ソースの MyBatis アノテーション SQL を解析して XTDB :sql-refs テーブルに put する。

   paths: 解析対象パスのベクタ（例: [\"trials/experiments/.../common-lib\"]）
   opts:
     :trial - トライアル識別子（文字列）

   例:
     (sqlref! node [\"trials/experiments/2026-04-28-tradehub/repo/common-lib\"]
              :trial \"tradehub\")"
  [node paths & {:keys [trial]}]
  (let [docs    (->> paths
                     (mapcat (fn [root]
                               (mapcat #(parse-mapper-file root % trial)
                                       (java-files root))))
                     vec)
        new-ids (set (map :xt/id docs))
        old-ids (->> (xt/q node '(from :sql-refs [{:xt/id id :sqlref/trial t}]))
                     (filter #(= (:t %) trial))
                     (map :id)
                     set)
        del-txs (mapv (fn [id] [:delete-docs :sql-refs id]) (set/difference old-ids new-ids))]
    (when (seq del-txs) (xt/execute-tx node del-txs))
    (doseq [batch (partition-all 2000 docs)]
      (xt/execute-tx node (mapv #(vector :put-docs :sql-refs %) batch)))
    (count docs)))

(defn sqlrefs
  "XTDB :sql-refs テーブルから全レコードを返す。
   opts:
     :trial - トライアル識別子でフィルタ"
  [node & {:keys [trial]}]
  (cond->> (xt/q node '(from :sql-refs [*]))
    trial (filter #(= (:sqlref/trial %) trial))))
