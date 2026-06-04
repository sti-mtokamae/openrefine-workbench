(ns workbench.runner
  "trial.edn のフェーズパイプラインを実行するランナー。

   使い方:
     bin/run-trial trials/experiments/MY-TRIAL/trial.edn

   フェーズは :phases キーに順番に並べる。
   完了済みフェーズは XTDB に記録されてスキップされる。
   再実行: (core/reset-phase! \"trial-id\" :ingest/jref)"
  (:require [clojure.edn :as edn]
            [workbench.core :as core]))

;; -------------------------
;; フェーズ dispatch
;; -------------------------

(defmulti run-phase!
  "フェーズを実行する。
   trial    — パース済みの trial.edn マップ
   phase-spec — {:phase :ingest/jref :params {...}} 形式"
  (fn [_trial phase-spec] (:phase phase-spec)))

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
  (let [{:keys [java-root]} (:params phase-spec)
        n (core/compile-errors-dir! java-root)]
    (println (str "  compile errors checked: " (count n) " files"))))

;; コンパイルOKなファイルだけref投入
(defmethod run-phase! :ingest/jref-gen-tests [trial phase-spec]
  (let [{:keys [java-root]} (:params phase-spec)
        ok-files (->> (core/compile-ok-java-files java-root)
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
            avg-assertions (if (seq all-results)
                             (double (/ (reduce + 0 (map :gta/assertions all-results)) (count all-results)))
                             0.0)]
        (println (format "    平均 LOC: %.1f, 平均 Assertion: %.1f" avg-loc avg-assertions))
        (println (format "    コンパイル可能: %d / %d (%.1f%%)" compiles-count (count all-results) compiles-pct))))))

(defmethod run-phase! :query/gen-tests-a-rank [trial _]
  (let [trial-id (:trial/id trial)]
    (println "  gen-tests A ランク確認...")
    (let [results (core/gen-tests :trial trial-id :rank :A)]
      (if (empty? results)
        (println "    A ランク: なし")
        (doseq [r results]
          (println (format "    A: %s (%.1f%%)" (:gta/class-name r) (:gta/coverage r))))))))

(defmethod run-phase! :regenerate/b-rank-tests [trial phase-spec]
  (let [trial-id (:trial/id trial)
        params   (:params phase-spec)
        rank     (:rank params)
        results  (core/gen-tests :trial trial-id :rank rank)]
    (println (str "  " rank " ランク対象: " (count results) " テスト"))
    (doseq [r (sort-by :gta/class-name results)]
      (println (format "    %-50s | LOC:%3d | Assert:%2d | Compiles:%s"
                       (:gta/class-name r)
                       (:gta/loc r)
                       (:gta/assertions r)
                       (if (:gta/compiles? r) "✓" "✗"))))))

(defmethod run-phase! :regenerate/c-rank-tests [trial phase-spec]
  (let [trial-id (:trial/id trial)
        params   (:params phase-spec)
        rank     (:rank params)
        results  (core/gen-tests :trial trial-id :rank rank)]
    (println (str "  " rank " ランク対象: " (count results) " テスト"))
    (doseq [r (sort-by :gta/class-name results)]
      (println (format "    %-50s | LOC:%3d | Assert:%2d | Compiles:%s"
                       (:gta/class-name r)
                       (:gta/loc r)
                       (:gta/assertions r)
                       (if (:gta/compiles? r) "✓" "✗"))))))

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
  (let [trial (-> (first args) slurp edn/read-string)]
    (println (str "=== trial: " (:trial/id trial) " ==="))
    (core/start!)
    (try
      (execute-pipeline! trial)
      (println "\n=== 完了 ===")
      (finally
        (core/stop!)))))