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
  (let [n (core/jacoco! (get-in phase-spec [:params :jacoco-xml]) :trial (:trial/id trial))]
    (println (str "  jacocos: " n))))

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

(defmethod run-phase! :default [_ phase-spec]
  (throw (ex-info (str "Unknown phase: " (:phase phase-spec))
                  {:phase-spec phase-spec})))

;; -------------------------
;; パイプライン実行
;; -------------------------

(defn- track-phase?
  "ingest/* と generate/* の完了状態を XTDB に記録する。
   analyze/* は常に再実行（クエリ結果は変わりうるため）。"
  [phase]
  (not= "analyze" (namespace phase)))

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
                (throw ex)))))))))

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