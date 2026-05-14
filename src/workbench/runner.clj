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

(defmethod run-phase! :default [_ phase-spec]
  (throw (ex-info (str "Unknown phase: " (:phase phase-spec))
                  {:phase-spec phase-spec})))

;; -------------------------
;; パイプライン実行
;; -------------------------

(defn execute-pipeline! [trial]
  (let [trial-id (:trial/id trial)
        phases   (:phases trial [])]
    (when (empty? phases)
      (println "  (no :phases defined in trial.edn)"))
    (doseq [phase-spec phases]
      (let [phase (:phase phase-spec)]
        (if (core/phase-done? trial-id phase)
          (println (str "[" phase "] skip (already done)"))
          (do
            (println (str "[" phase "] running..."))
            (try
              (run-phase! trial phase-spec)
              (core/mark-phase! trial-id phase :done)
              (println (str "[" phase "] done"))
              (catch Exception ex
                (core/mark-phase! trial-id phase :failed)
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