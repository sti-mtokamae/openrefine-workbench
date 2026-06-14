(ns workbench.runner
  "trial.edn のフェーズパイプラインを実行するランナー。

   使い方:
     bin/run-trial trials/experiments/MY-TRIAL/trial.edn

   フェーズは :phases キーに順番に並べる。
   完了済みフェーズは XTDB に記録されてスキップされる。
   再実行: (core/reset-phase! \"trial-id\" :ingest/jref)"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [workbench.core :as core]))

;; -------------------------
;; フェーズ dispatch
;; -------------------------

(defmulti run-phase!
  "フェーズを実行する。
   trial    — パース済みの trial.edn マップ
   phase-spec — {:phase :ingest/jref :params {...}} 形式"
  (fn [_trial phase-spec] (:phase phase-spec)))

(defn- resolve-classpath
  "phase params から classpath 文字列を解決する。
   :classpath があればそれを優先し、無ければ :classpath-file を読み込む。"
  [{:keys [classpath classpath-file]}]
  (cond
    classpath classpath
    classpath-file (some-> classpath-file slurp str/trim not-empty)
    :else nil))

(defn- trial-base-dir
  "trial.edn のあるディレクトリを返す。"
  [trial]
  (some-> (:trial/file trial) io/file .getParentFile .getPath))

(defn- output-path
  "trial の output/dir 配下に出力するファイルの絶対/相対パスを返す。"
  [trial filename]
  (let [base (or (trial-base-dir trial) ".")
        out  (or (:output/dir trial) "exports")]
    (str (io/file base out filename))))

(defn- tsv-escape [v]
  (-> (str (or v ""))
      (str/replace #"\t" " ")
      (str/replace #"\r?\n" " ")))

(defn- write-tsv!
  "rows を TSV として path に書き出す。"
  [path columns rows]
  (io/make-parents path)
  (let [header (str/join "\t" (map name columns))
        lines  (map (fn [row]
                      (str/join "\t" (map #(tsv-escape (get row %)) columns)))
                    rows)]
    (spit path (str header "\n" (str/join "\n" lines) (when (seq lines) "\n")))))

(defn- resolve-source-file
  "trial の :input/java-roots から相対ファイルパスを解決する。"
  [trial rel-path]
  (some (fn [root]
          (let [f (io/file root rel-path)]
            (when (.exists f) f)))
        (:input/java-roots trial)))

(defn- method-id-from-sig [sig]
  (str (:jsig/class sig) "/" (:jsig/method sig)))

(defn- build-slice-context
  "root/depth から slice 関連の中間データを構築する。"
  [trial {:keys [root depth direction exclude-test]
          :or   {depth 2 direction :forward exclude-test true}}]
  (let [trial-id    (:trial/id trial)
        rs          (core/jrefs :trial trial-id :exclude-test exclude-test)
        method-locs (core/method-locations :trial trial-id)
        slice-rows  (core/slice-results root :depth depth :direction direction
                                        :rs rs :method-locations method-locs)
        slice-by-id (into {} (map (juxt :method-id identity) slice-rows))
        jsigs       (core/jsigs :trial trial-id)
        spans       (->> jsigs
                         (map (fn [sig]
                                {:method-id         (method-id-from-sig sig)
                                 :class             (:jsig/class sig)
                                 :method            (:jsig/method sig)
                                 :file              (:jsig/file sig)
                                 :method-start-line (:jsig/start-line sig)
                                 :method-end-line   (:jsig/end-line sig)
                                 :return-type       (:jsig/return sig)
                                 :mods              (str/join " " (:jsig/mods sig))
                                 :params            (pr-str (:jsig/params sig))}))
                         (sort-by (juxt :file :method-start-line :method-id))
                         vec)
        spans-by-file (group-by :file spans)
        calls-by-line (->> rs
                           (group-by (fn [{:keys [file line]}] [file line])))]
    {:slice-rows slice-rows
     :slice-by-id slice-by-id
     :spans spans
     :spans-by-file spans-by-file
     :calls-by-line calls-by-line}))

(defn- line-enrichment
  "1 行分の source-lines enriched row を返す。"
  [file line text spans-by-file calls-by-line slice-by-id]
  (let [span (->> (get spans-by-file file)
                  (filter (fn [{:keys [method-start-line method-end-line]}]
                            (and method-start-line method-end-line
                                 (<= method-start-line line method-end-line))))
                  first)
        slice-row (some-> span :method-id slice-by-id)
        calls     (get calls-by-line [file line] [])]
    {:file              file
     :line              line
     :text              text
     :class             (:class span)
     :method-id         (:method-id span)
     :method-start-line (:method-start-line span)
     :method-end-line   (:method-end-line span)
     :slice-root-method (:root-method slice-row)
     :slice-depth       (:depth slice-row)
     :slice-parent      (:parent-method slice-row)
     :in-slice-method?  (boolean slice-row)
     :call-count        (count calls)
     :call-to           (str/join " | " (sort (distinct (map :to calls))))}))

(defn- read-source-lines
  "対象ファイル群から行単位の source row を返す。"
  [trial files]
  (->> files
       distinct
       sort
       (mapcat (fn [rel-path]
                 (when-let [f (resolve-source-file trial rel-path)]
                   (with-open [rdr (io/reader f)]
                     (doall
                      (map-indexed (fn [idx line]
                                     {:file rel-path
                                      :line (inc idx)
                                      :text line})
                                   (line-seq rdr)))))))
       vec))

(defmethod run-phase! :ingest/jref [trial _]
  (let [n (core/jref! (:input/java-roots trial) :trial (:trial/id trial))]
    (println (str "  refs: " n))))

(defmethod run-phase! :ingest/jsig [trial _]
  (let [n (core/jsig! (:input/java-roots trial) :trial (:trial/id trial))]
    (println (str "  jsigs: " n))))

(defmethod run-phase! :ingest/cochange [trial phase-spec]
  (let [{:keys [repo-dir filter-path]} (:params phase-spec)
        n (core/cochange! repo-dir :trial (:trial/id trial) :filter-path filter-path)]
    (println (str "  co-change pairs: " n))))

(defmethod run-phase! :ingest/sqlref [trial _]
  (let [n (core/sqlref! (:input/java-roots trial) :trial (:trial/id trial))]
    (println (str "  sql-refs: " n))))

(defmethod run-phase! :ingest/jacoco [trial phase-spec]
  (let [trial-id (:trial/id trial)
        res (core/ingest-jacoco! phase-spec :trial trial-id)]
    (if (:error res)
      (throw (ex-info (str "jacoco ingest failed: " (:error res))
                      {:phase :ingest/jacoco :trial trial-id :res res}))
      (println (str "  jacocos: " res)))))


;; AI生成テストのjavacチェック（compile-errors-dir!）
(defmethod run-phase! :ingest/compile-errors-gen-tests [trial phase-spec]
  (let [{:keys [java-root] :as params} (:params phase-spec)
        classpath (resolve-classpath params)
        n (core/compile-errors-dir! java-root :classpath classpath)]
    (println (str "  compile errors checked: " (count n) " files"))))

;; コンパイルOKなファイルだけref投入
(defmethod run-phase! :ingest/jref-gen-tests [trial phase-spec]
  (let [{:keys [java-root] :as params} (:params phase-spec)
        classpath (resolve-classpath params)
        ok-files (->> (core/compile-ok-java-files java-root :classpath classpath)
                      (remove nil?))
        n (if (seq ok-files)
            (core/jref! ok-files :trial (:trial/id trial) :tag "gen-tests")
            0)]
    (println (str "  gen-test refs (compile OK only): " n))))

;; Clojure 呼び出しグラフ解析
(defmethod run-phase! :ingest/xref [trial _]
  (let [n (core/xref! (:input/clj-roots trial) :trial (:trial/id trial))]
    (println (str "  xref: " n))))

;; -------------------------
;; analyze フェーズ
;; -------------------------

(defmethod run-phase! :analyze/overview [trial phase-spec]
  (let [trial-id (:trial/id trial)
        top-n    (get-in phase-spec [:params :top-n] 20)
        rs       (core/jrefs :trial trial-id :exclude-test true)]
    (println (str "\n--- fan-in top " top-n " ---"))
    (doseq [h (take top-n (core/fan-in rs))]
      (println (format "  %3d  %s" (:count h) (:symbol h))))
    (println (str "\n--- fan-out top " top-n " ---"))
    (doseq [h (take top-n (core/fan-out rs))]
      (println (format "  %3d  %s" (:count h) (:symbol h))))
    (println "\n--- topo-sort top 40 ---")
    (let [sorted (core/topo-sort :rs rs)]
      (println (str "  total classes: " (count sorted)))
      (doseq [[i c] (map-indexed vector (take 40 sorted))]
        (println (format "  %3d  %s" (inc i) c))))))

(defmethod run-phase! :analyze/xref-overview [trial phase-spec]
  (let [trial-id (:trial/id trial)
        top-n    (get-in phase-spec [:params :top-n] 20)
        rs       (->> (core/q '(from :refs [{:ref/from from :ref/to to :ref/trial trial}]))
                      (filter #(= (:ref/trial %) trial-id))
                      (remove #(re-find #"^(clojure\.|java\.|xtdb\.)" (:to %)))
                      (remove #(re-find #"/<top-level>$" (:from %))))]
    (when (seq rs)
      (println (str "\n--- Clojure xref fan-in top " top-n " ---"))
      (doseq [[sym count] (take top-n (->> rs
                                            (group-by :ref/to)
                                            (map (fn [[k v]] [k (count v)]))
                                            (sort-by second >)))]
        (println (format "  %3d  %s" count sym)))
      (println (str "\n--- Clojure xref fan-out top " top-n " ---"))
      (doseq [[sym count] (take top-n (->> rs
                                            (group-by :ref/from)
                                            (map (fn [[k v]] [k (count v)]))
                                            (sort-by second >)))]
        (println (format "  %3d  %s" count sym))))))

(defmethod run-phase! :analyze/uncovered-sql [trial _]
  (let [trial-id   (:trial/id trial)
        candidates (core/uncovered-sql-methods :trial trial-id)]
    (println (str "  候補メソッド数: " (count candidates)))
    (println (str "  対象クラス数:   " (count (distinct (map :class candidates)))))
    (println "\n  --- クラス別内訳 ---")
    (doseq [[cls ms] (->> candidates
                          (group-by :class)
                          (sort-by (comp count second) >))]
      (println (format "  %-50s %2d メソッド" cls (count ms))))))

(defmethod run-phase! :analyze/slice-call-flow [trial phase-spec]
  (let [trial-id     (:trial/id trial)
        {:keys [root depth direction exclude-test output-file]
         :or   {depth 2 direction :forward exclude-test true
                output-file "slice-call-flow.tsv"}} (:params phase-spec)
        _            (when-not root
                       (throw (ex-info ":analyze/slice-call-flow requires :params {:root ...}"
                                       {:phase-spec phase-spec})))
        rs           (core/jrefs :trial trial-id :exclude-test exclude-test)
        method-locs  (core/method-locations :trial trial-id)
        rows         (core/slice-results root :depth depth :direction direction
                                         :rs rs :method-locations method-locs)
        path         (output-path trial output-file)
        columns      [:root-method :method-id :class :method :depth :direction
                      :parent-method
                      :method-file :method-start-line :method-end-line
                      :call-file :call-line
                      :edge-kind]]
    (write-tsv! path columns rows)
    (println (str "  root: " root))
    (println (str "  rows: " (count rows)))
    (println (str "  wrote: " path))))

(defmethod run-phase! :analyze/export-method-spans [trial phase-spec]
  (let [{:keys [root depth output-file]
         :or   {depth 2 output-file "method-spans.tsv"}} (:params phase-spec)
        {:keys [spans slice-by-id]} (build-slice-context trial {:root root :depth depth})
        rows (->> spans
                  (map (fn [row]
                         (assoc row
                                :slice-depth (get-in slice-by-id [(:method-id row) :depth])
                                :in-slice-method? (contains? slice-by-id (:method-id row)))))
                  (filter :in-slice-method?)
                  vec)
        path (output-path trial output-file)
        columns [:method-id :class :method :file :method-start-line :method-end-line
                 :return-type :mods :params :slice-depth :in-slice-method?]]
    (write-tsv! path columns rows)
    (println (str "  rows: " (count rows)))
    (println (str "  wrote: " path))))

(defmethod run-phase! :analyze/export-source-lines [trial phase-spec]
  (let [{:keys [root depth output-file]
         :or   {depth 2 output-file "source-lines-enriched.tsv"}} (:params phase-spec)
        {:keys [slice-rows slice-by-id spans-by-file calls-by-line]} (build-slice-context trial {:root root :depth depth})
        files (->> slice-rows
                   (map :method-file)
                   (remove nil?)
                   distinct
                   vec)
        rows  (->> (read-source-lines trial files)
                   (map (fn [{:keys [file line text]}]
                          (line-enrichment file line text spans-by-file calls-by-line slice-by-id)))
                   vec)
        path (output-path trial output-file)
        columns [:file :line :text :class :method-id :method-start-line :method-end-line
                 :slice-root-method :slice-depth :slice-parent :in-slice-method?
                 :call-count :call-to]]
    (write-tsv! path columns rows)
    (println (str "  files: " (count files)))
    (println (str "  rows: " (count rows)))
    (println (str "  wrote: " path))))

(defmethod run-phase! :generate/tests [trial phase-spec]
  (let [trial-id (:trial/id trial)
        params   (:params phase-spec)
        out-dir  (:out-dir params)
        model    (:model params)
        kwargs   (cond-> [:trial trial-id]
                   out-dir (conj :out-dir out-dir)
                   model   (conj :model model))
        results  (apply core/gen-tests-uncovered kwargs)]
    (println (str "  生成完了: " (count results) " メソッド"))
    (doseq [{:keys [class method]} results]
      (println (str "    " class "/" method)))))

(defmethod run-phase! :generate/merge-tests [_ phase-spec]
  (let [params  (:params phase-spec)
        gen-dir (:gen-dir params)
        force   (:force params false)
        results (core/merge-all-test-mds :gen-dir gen-dir :force force)
        merged  (filter #(= :merged (:status %)) results)
        skipped (filter #(= :skipped (:status %)) results)]
    (println (str "  統合完了: merged=" (count merged) " skipped=" (count skipped)))))

(defmethod run-phase! :analyze/gen-tests [trial phase-spec]
  (let [trial-id (:trial/id trial)
    params   (:params phase-spec)
    gen-tests-dir (:gen-tests-dir params)
    src-roots (:input/java-roots trial)
    result   (core/ingest-analyze-gen-tests! gen-tests-dir :trial trial-id :src-roots src-roots)]
    (println (str "  分析完了: " (:analyzed result) " テスト"))
    (let [summary (core/gen-tests-summary :trial trial-id)
          ranks [:A :B :C :D]
          all-results (core/gen-tests :trial trial-id)
          compiles-count (count (filter :gta/compiles? all-results))
          compiles-pct (if (seq all-results)
                         (double (* 100 (/ compiles-count (count all-results))))
                         0.0)]
      (doseq [r ranks]
        (println (format "    [%s] %2d テスト" r (get summary r 0))))
      (let [avg-loc (if (seq all-results)
                      (double (/ (reduce + 0 (map :gta/loc all-results)) (count all-results)))
                      0.0)
            avg-src-loc (if (seq all-results)
                          (double (/ (reduce + 0 (map #(or (:gta/src-loc %) 0) all-results))
                                     (count all-results)))
                          0.0)
            avg-loc-norm (if (seq all-results)
                           (double (/ (reduce + 0 (map #(or (:gta/loc-norm %) 0.0) all-results))
                                      (count all-results)))
                           0.0)
            avg-assertions (if (seq all-results)
                             (double (/ (reduce + 0 (map :gta/assertions all-results)) (count all-results)))
                             0.0)]
        (println (format "    平均 LOC: %.1f, 平均 Assertion: %.1f" avg-loc avg-assertions))
        (println (format "    平均 Src LOC: %.1f, 平均 正規化 LOC: %.2f" avg-src-loc avg-loc-norm))
        (println (format "    コンパイル可能: %d / %d (%.1f%%)" compiles-count (count all-results) compiles-pct))))))

(defmethod run-phase! :query/gen-tests-a-rank [trial _]
  (let [trial-id (:trial/id trial)]
    (println "  :A ランク対象確認...")
    (let [results (core/gen-tests :trial trial-id :rank :A)]
      (if (empty? results)
        (println "    A ランク: なし")
        (doseq [r (sort-by :gta/class-name results)]
          (println (format "    %-50s | LOC:%3d | Src:%4s | Norm:%7.2f | Assert:%2d | Compiles:%s"
                           (:gta/class-name r)
                           (:gta/loc r)
                           (if-let [src-loc (:gta/src-loc r)]
                             (str src-loc)
                             "-")
                           (double (or (:gta/loc-norm r) 0.0))
                           (:gta/assertions r)
                           (if (:gta/compiles? r) "✓" "✗"))))))))

(defmethod run-phase! :regenerate/b-rank-tests [trial phase-spec]
  (let [trial-id (:trial/id trial)
        params   (:params phase-spec)
        rank     (:rank params)
        results  (core/gen-tests :trial trial-id :rank rank)]
    (println (str "  " rank " ランク対象: " (count results) " テスト"))
    (doseq [r (sort-by :gta/class-name results)]
      (println (format "    %-50s | LOC:%3d | Src:%4s | Norm:%7.2f | Assert:%2d | Compiles:%s"
                       (:gta/class-name r)
                       (:gta/loc r)
                       (if-let [src-loc (:gta/src-loc r)]
                         (str src-loc)
                         "-")
                       (double (or (:gta/loc-norm r) 0.0))
                       (:gta/assertions r)
                       (if (:gta/compiles? r) "✓" "✗"))))))

(defmethod run-phase! :regenerate/c-rank-tests [trial phase-spec]
  (let [trial-id (:trial/id trial)
        params   (:params phase-spec)
        rank     (:rank params)
        results  (core/gen-tests :trial trial-id :rank rank)]
    (println (str "  " rank " ランク対象: " (count results) " テスト"))
    (doseq [r (sort-by :gta/class-name results)]
      (println (format "    %-50s | LOC:%3d | Src:%4s | Norm:%7.2f | Assert:%2d | Compiles:%s"
                       (:gta/class-name r)
                       (:gta/loc r)
                       (if-let [src-loc (:gta/src-loc r)]
                         (str src-loc)
                         "-")
                       (double (or (:gta/loc-norm r) 0.0))
                       (:gta/assertions r)
                       (if (:gta/compiles? r) "✓" "✗"))))))

(defmethod run-phase! :regenerate/d-rank-tests [trial _]
  (let [trial-id (:trial/id trial)
        results  (core/gen-tests :trial trial-id :rank :D)
        get-error-info
        (fn [class-name]
          (let [docs (core/q '(from :java-compile-errors
                                [{:xt/id id
                                  :java/compile-errors errs
                                  :file/path fpath}]))
                matching (filter #(str/includes? (:fpath %) (str class-name "Test.java")) docs)]
            (if-let [doc (first matching)]
              (let [errs   (or (:errs doc) [])
                    errors (filter #(not= (str (:kind %)) "NOTE") errs)
                    kinds  (sort (map str (set (map :kind errors))))]
                {:count (count errors)
                 :kinds (str/join ", " kinds)})
              {:count 0
               :kinds ""})))]
    (println (str "  :D ランク対象: " (count results) " テスト"))
    (if (empty? results)
      (println "    D ランク: なし")
      (doseq [r (sort-by :gta/class-name results)]
        (let [info     (get-error-info (:gta/class-name r))
              kind-str (if (empty? (:kinds info))
                         ""
                         (str " [" (:kinds info) "]"))]
          (println (format "    %-50s | LOC:%3d | Src:%4s | Norm:%7.2f | Assert:%2d | Compiles:%s Errors:%d%s"
                           (:gta/class-name r)
                           (:gta/loc r)
                           (if-let [src-loc (:gta/src-loc r)]
                             (str src-loc)
                             "-")
                           (double (or (:gta/loc-norm r) 0.0))
                           (:gta/assertions r)
                           (if (:gta/compiles? r) "✓" "✗")
                           (:count info)
                           kind-str)))))))

;; テスト修正フェーズ
(defmethod run-phase! :testfix/fix-bucket [trial phase-spec]
  (let [{:keys [java-path class-name src-root bucket-index classpath]} (:params phase-spec)
        trial-id (:trial/id trial)
        cp (or classpath (resolve-classpath (:params phase-spec)))
        result (core/fix-bucket! java-path
                                :trial trial-id
                                :class-name class-name
                                :src-root src-root
                                :bucket-index bucket-index
                                :classpath cp)]
    (when-let [req-summary (get-in result [:request :summary])]
      (println (str "  Request: " req-summary)))
    (when-let [patch-summary (get-in result [:patch :summary])]
      (println (str "  Patch: " patch-summary)))
    (when-let [recheck (get-in result [:recheck])]
      (println (str "  Recheck: " (:error-count recheck) " errors, compile-ok=" (:compile-ok? recheck))))))

(defmethod run-phase! :default [_ phase-spec]
  (throw (ex-info (str "Unknown phase: " (:phase phase-spec))
                  {:phase-spec phase-spec})))

;; -------------------------
;; パイプライン実行
;; -------------------------

(defn- track-phase?
  "ingest/* と generate/* の完了状態を XTDB に記録する。
   analyze/* と query/* は常に再実行（クエリ結果は変わりうるため）。"
  [phase]
  (not (#{:analyze :query} (keyword (namespace phase)))))

(defn execute-pipeline! [trial]
  (let [trial-id (:trial/id trial)
        phases   (:phases trial [])]
    (when (empty? phases)
      (println "  (no :phases defined in trial.edn)"))
    (doseq [phase-spec phases]
      (let [phase    (:phase phase-spec)
            tracked? (track-phase? phase)]
        (if (and tracked? (core/phase-done? trial-id phase))
          (println (str "[" phase "] skip (already done)"))
          (do
            (println (str "[" phase "] running..."))
            (try
              (run-phase! trial phase-spec)
              (when tracked?
                (core/mark-phase! trial-id phase :done))
              (println (str "[" phase "] done"))
              (catch Exception ex
                (when tracked?
                  (core/mark-phase! trial-id phase :failed))
                (println (str "[" phase "] FAILED: " (ex-message ex)))
                ;; throwせず次のフェーズへ進む
              ))))))))

;; -------------------------
;; エントリポイント
;; -------------------------

(defn -main [& args]
  (when (empty? args)
    (println "Usage: workbench.runner <trial.edn>")
    (System/exit 1))
  (let [trial-file (first args)
        trial      (-> trial-file slurp edn/read-string (assoc :trial/file trial-file))]
    (println (str "=== trial: " (:trial/id trial) " ==="))
    (core/start!)
    (try
      (execute-pipeline! trial)
      (println "\n=== 完了 ===")
      (finally
        (core/stop!)))))
