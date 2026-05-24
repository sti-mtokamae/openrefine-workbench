;; trials/regen-unadjusted.clj
;;
;; 未調整の .java ファイルを改善済みプロンプトで再生成する。
;; タイムスタンプが 2026-05-17 01:53 の 63 本を対象とし、
;; 手動調整済みの 4 本 (preserve セット) を保護する。
;;
;; 実行方法:
;;   guix shell -m manifest.scm -- clojure -A:xtdb -M trials/regen-unadjusted.clj
;;
;; 注意: XTDB に tradehub の jsigs / sqlrefs / jacocos が投入済みであること。
;;       必要なら事前に analyze.clj を実行して再 ingest すること。

(require 'workbench.core)
(workbench.core/start!)

(def gen-dir  "trials/experiments/2026-04-28-tradehub/exports/gen-tests")
(def src-root "trials/experiments/2026-04-28-tradehub/repo/common-lib/src/main/java")
(def trial    "tradehub")

;; 手動調整済み → 保持するクラス名（.java を上書きしない）
(def preserve #{"ActivityRecordServiceImpl"
                "DocumentsServiceImpl"
                "DocumentsVerificationDataServiceImpl"
                "ProjectServiceImpl"})

;; =========================================================
;; ① XTDB 状態チェック
;; =========================================================
(println "\n=== ① XTDB 状態チェック ===")
(let [jsig-cnt   (count (workbench.core/jsigs   :trial trial))
      sqlref-cnt (count (workbench.core/sqlrefs :trial trial))
      jacoco-cnt (count (workbench.core/jacocos :trial trial))]
  (println (str "  jsigs:   " jsig-cnt))
  (println (str "  sqlrefs: " sqlref-cnt))
  (println (str "  jacocos: " jacoco-cnt))
  (when (or (zero? jsig-cnt) (zero? sqlref-cnt) (zero? jacoco-cnt))
    (println "\n  [WARNING] 一部データが空。gen-tests-uncovered の候補が減る可能性があります。")
    (println "  必要なら事前に analyze.clj で jref! / jsig! / sqlref! / jacoco! を再実行してください。")))

;; =========================================================
;; ② 未調整 .java を削除
;;    削除対象: uncovered-sql-methods に含まれるクラス（.md が再生成される）
;;             かつ preserve に含まれないクラス
;;    非対象:  uncovered に含まれないクラス（.md が再生成されないので .java は保持）
;; =========================================================
(println "\n=== ② 未調整 .java を削除（uncovered 対象・preserve 除外）===")
(let [candidates   (workbench.core/uncovered-sql-methods :trial trial)
      uncov-cls    (into #{} (map :class candidates))
      deleted      (atom 0)
      kept-adj     (atom 0)
      kept-not-unc (atom 0)]
  (doseq [d (->> (file-seq (java.io.File. gen-dir))
                 (filter #(and (.isDirectory ^java.io.File %)
                               (not= (.getPath ^java.io.File %) gen-dir)))
                 sort)]
    (let [cls (.getName ^java.io.File d)
          f   (java.io.File. (str (.getPath ^java.io.File d) "/" cls "Test.java"))]
      (cond
        (preserve cls)
        (do (swap! kept-adj inc)
            (println (str "  保持（調整済み）: " cls)))

        (not (uncov-cls cls))
        (do (swap! kept-not-unc inc)
            (println (str "  保持（uncovered 対象外）: " cls)))

        (.exists f)
        (do (.delete f)
            (swap! deleted inc)
            (println (str "  削除: " cls "Test.java")))

        :else
        (println (str "  スキップ（.java なし）: " cls)))))
  (println (str "\n  削除: " @deleted " 件、保持（調整済み）: " @kept-adj " 件、保持（対象外）: " @kept-not-unc " 件")))

;; =========================================================
;; ③ .md を新プロンプトで再生成（:force true で既存を上書き）
;; =========================================================
(println "\n=== ③ .md を再生成（改善済みプロンプト、全メソッド上書き）===")
(workbench.core/gen-tests-uncovered
  :trial    trial
  :out-dir  gen-dir
  :src-root src-root
  :force    true)

;; =========================================================
;; ④ .md → .java マージ（preserve の .java は残っているのでスキップされる）
;; =========================================================
(println "\n=== ④ .md → .java マージ（保持対象は自動スキップ）===")
(workbench.core/merge-all-test-mds
  :gen-dir gen-dir
  :force   false)

(println "\n=== 完了 ===")
(workbench.core/stop!)
(System/exit 0)
