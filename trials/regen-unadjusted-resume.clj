;; trials/regen-unadjusted-resume.clj
;;
;; regen-unadjusted.clj が中断された場合の再開スクリプト。
;; 再生成済みの .md（regen-unadjusted.clj より新しいもの）はスキップし、
;; 古い .md（旧プロンプト製）だけを削除してから残りを生成する。
;;
;; 実行方法:
;;   guix shell -m manifest.scm -- clojure -A:xtdb -M trials/regen-unadjusted-resume.clj

(require 'workbench.core)
(workbench.core/start!)

(def gen-dir  "trials/experiments/2026-04-28-tradehub/exports/gen-tests")
(def src-root "trials/experiments/2026-04-28-tradehub/repo/common-lib/src/main/java")
(def trial    "tradehub")

;; regen-unadjusted.clj の作成時刻を cutoff として使う
;; これより古い .md = 旧プロンプトで生成されたもの（再生成が必要）
(def cutoff-file (java.io.File. "trials/regen-unadjusted.clj"))
(def cutoff-ms   (.lastModified cutoff-file))

;; =========================================================
;; ① 古い .md を削除（cutoff より古いもの = 旧プロンプト製）
;; =========================================================
(println "\n=== ① 古い .md を削除（旧プロンプト製） ===")
(println (str "  cutoff: " (java.util.Date. cutoff-ms)))
(let [deleted (atom 0)
      kept    (atom 0)]
  (doseq [f (->> (file-seq (java.io.File. gen-dir))
                 (filter #(and (.isFile ^java.io.File %)
                               (clojure.string/ends-with? (.getName ^java.io.File %) ".md"))))]
    (if (< (.lastModified ^java.io.File f) cutoff-ms)
      (do (.delete f)
          (swap! deleted inc))
      (swap! kept inc)))
  (println (str "  削除（古い）: " @deleted " 件"))
  (println (str "  保持（再生成済み）: " @kept " 件")))

;; =========================================================
;; ② 残り .md を生成（:force false で既存スキップ）
;; =========================================================
(println "\n=== ② 残り .md を生成（再生成済みはスキップ） ===")
(workbench.core/gen-tests-uncovered
  :trial    trial
  :out-dir  gen-dir
  :src-root src-root
  :model    "openai/gpt-4o-mini"
  :force    false)

;; =========================================================
;; ③ .md → .java マージ（保持対象はスキップ）
;; =========================================================
(println "\n=== ③ .md → .java マージ ===")
(workbench.core/merge-all-test-mds
  :gen-dir gen-dir
  :force   false)

(println "\n=== 完了 ===")
(workbench.core/stop!)
(System/exit 0)
