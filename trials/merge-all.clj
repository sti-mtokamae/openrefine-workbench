;;; merge-all.clj
;;; gen-tests/ 配下の全クラスの .md を統合して *Test.java を生成する
;;; 実行: guix shell -m manifest.scm -- clojure -A:xtdb -M trials/merge-all.clj

(require 'workbench.core)
(workbench.core/start!)

(println "=== merge-all-test-mds :force true 開始 ===")
(let [results (workbench.core/merge-all-test-mds
                :gen-dir "trials/experiments/2026-04-28-tradehub/exports/gen-tests"
                :force   true)
      merged  (filter #(= :merged  (:status %)) results)
      skipped (filter #(= :skipped (:status %)) results)
      no-mds  (filter #(= :no-mds  (:status %)) results)]
  (println (str "\n=== 完了 ==="))
  (println (str "  merged : " (count merged)))
  (println (str "  skipped: " (count skipped)))
  (println (str "  no-mds : " (count no-mds))))

(workbench.core/stop!)
(System/exit 0)
