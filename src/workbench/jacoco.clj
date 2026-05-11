(ns workbench.jacoco
  "JaCoCo XML レポートを解析して :jacoco テーブルへ取り込む。

   抽出する情報（メソッド単位）:
     :jacoco/trial        — トライアル識別子
     :jacoco/class        — パッケージ付きクラス名（スラッシュ区切り）
     :jacoco/class-simple — クラス名のみ（:refs のクラス名照合用）
     :jacoco/method       — メソッド名
     :jacoco/desc         — JVM ディスクリプタ
     :jacoco/line         — メソッド開始行番号
     :jacoco/covered      — カバー済み行数（LINE counter）
     :jacoco/missed       — 未カバー行数（LINE counter）

   :refs / :sql-refs と結合して
   「テストが届いていないかつ SQL 縛りのある経路」をクエリできる。"
  (:require
   [clojure.java.io :as io]
   [clojure.set     :as set]
   [clojure.string  :as str]
   [xtdb.api        :as xt])
  (:import
   [javax.xml.parsers SAXParserFactory]
   [org.xml.sax Attributes]
   [org.xml.sax.helpers DefaultHandler]))

;; -------------------------
;; XML パース（SAX — DTD 参照を無効化）
;; -------------------------

(defn- parse-jacoco-xml
  "jacoco.xml を SAX でパースしてメソッド単位のマップのシーケンスを返す。
   DTD（jacoco.dtd）への外部参照を無効化してオフライン動作に対応する。"
  [path]
  (let [factory (doto (SAXParserFactory/newInstance)
                  (.setValidating false)
                  (.setFeature "http://apache.org/xml/features/nonvalidating/load-external-dtd" false))
        parser  (.newSAXParser factory)
        results (atom [])
        cur-pkg (atom nil)
        cur-cls (atom nil)]
    (.parse parser
            (io/file path)
            (proxy [DefaultHandler] []
              (startElement [uri local-name q-name ^Attributes attrs]
                (condp = q-name
                  "package"
                  (reset! cur-pkg (.getValue attrs "name"))

                  "class"
                  (let [cls-name (.getValue attrs "name")]
                    (reset! cur-cls {:class cls-name
                                     :pkg   @cur-pkg}))

                  "method"
                  (when @cur-cls
                    (swap! cur-cls assoc
                           :method (.getValue attrs "name")
                           :desc   (.getValue attrs "desc")
                           :line   (some-> (.getValue attrs "line")
                                           (Long/parseLong))))

                  "counter"
                  (when (and @cur-cls
                             (:method @cur-cls)
                             (= "LINE" (.getValue attrs "type")))
                    (let [covered (Long/parseLong (.getValue attrs "covered"))
                          missed  (Long/parseLong (.getValue attrs "missed"))]
                      (swap! results conj
                             (assoc @cur-cls
                                    :covered covered
                                    :missed  missed))
                      ;; method は counter の後にリセットして次のカウンタで上書きしない
                      (swap! cur-cls dissoc :method :desc :line)))

                  nil))

              (endElement [uri local-name q-name]
                (condp = q-name
                  "class"   (reset! cur-cls nil)
                  "package" (reset! cur-pkg nil)
                  nil))))
    @results))

;; -------------------------
;; XTDB 取り込み
;; -------------------------

(defn- record->doc
  "パース結果マップを XTDB ドキュメントに変換する。"
  [{:keys [class method desc line covered missed]} trial]
  (let [class-simple (-> class (str/replace #".*/" "") (str/replace #"\$.*" ""))
        id (str "jacoco/" (or trial "_") "/" class "/" method desc)]
    {:xt/id               id
     :jacoco/trial        trial
     :jacoco/class        class
     :jacoco/class-simple class-simple
     :jacoco/method       method
     :jacoco/desc         desc
     :jacoco/line         line
     :jacoco/covered      covered
     :jacoco/missed       missed}))

(defn jacoco!
  "jacoco.xml を解析して XTDB :jacoco テーブルに取り込む（差分同期・冪等）。

   xml-path: jacoco.xml のファイルパス（文字列）
   opts:
     :trial - トライアル識別子

   例:
     (jacoco! \"/tmp/jacoco-report/jacoco.xml\" :trial \"tradehub\")"
  [node xml-path & {:keys [trial]}]
  (let [records   (parse-jacoco-xml xml-path)
        new-docs  (mapv #(record->doc % trial) records)
        new-ids   (set (map :xt/id new-docs))
        ;; 既存の同 trial の :jacoco ドキュメントを取得
        existing  (xt/q node '(from :jacoco [{:xt/id id :jacoco/trial t}]))
        old-ids   (->> existing
                       (filter #(= trial (:t %)))
                       (map :id)
                       set)
        to-delete (set/difference old-ids new-ids)
        to-put    new-docs]
    (xt/execute-tx node
      (vec
       (concat
        (mapv (fn [id] [:delete :jacoco id]) to-delete)
        (mapv (fn [doc] [:put-docs :jacoco doc]) to-put))))
    {:put    (count to-put)
     :delete (count to-delete)}))

(defn jacocos
  "XTDB :jacoco テーブルから全レコードを返す。
   opts:
     :trial   - トライアル識別子でフィルタ
     :class   - クラス名（シンプル名）でフィルタ
     :uncovered? - true のとき covered=0 のメソッドのみ返す

   例:
     (jacocos :trial \"tradehub\")
     (jacocos :trial \"tradehub\" :uncovered? true)
     (jacocos :trial \"tradehub\" :class \"DocumentAggregateServiceImpl\")"
  [node & {:keys [trial class uncovered?]}]
  (let [rs (xt/q node '(from :jacoco [*]))]
    (cond->> rs
      trial      (filter #(= trial (:jacoco/trial %)))
      class      (filter #(= class (:jacoco/class-simple %)))
      uncovered? (filter #(zero? (:jacoco/covered %))))))
