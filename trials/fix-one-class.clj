(require 'workbench.core)
(workbench.core/start!)

(def trial    "tradehub")
(def src-root "trials/experiments/2026-04-28-tradehub/repo/common-lib/src/main/java")
(def mvn-root "trials/experiments/2026-04-28-tradehub/repo")
(def module   "common-lib")
(def class-name "DocumentsVerificationDataServiceImpl")
(def java-path
  "trials/experiments/2026-04-28-tradehub/exports/gen-tests/DocumentsVerificationDataServiceImpl/DocumentsVerificationDataServiceImplTest.java")
(def test-dir
  "trials/experiments/2026-04-28-tradehub/repo/common-lib/src/test/java/com/tradehub/web/document/service/impl")

;; test-dir にコピーしてからコンパイルエラーを取得する
(println "== コピー → テストディレクトリ ==")
(clojure.java.io/make-parents (str test-dir "/DocumentsVerificationDataServiceImplTest.java"))
(clojure.java.io/copy (clojure.java.io/file java-path)
                      (clojure.java.io/file (str test-dir "/DocumentsVerificationDataServiceImplTest.java")))

(println (str "=== fix-test 実行中: " class-name " ==="))
(let [fixed (workbench.core/fix-test class-name
              :trial    trial
              :src-root src-root
              :java-path java-path
              :mvn-root  mvn-root
              :module    module)]
  (spit java-path fixed)
  ;; 修正済みを test-dir にも反映
  (spit (str test-dir "/DocumentsServiceImplTest.java") fixed)
  (println (str "  保存: " java-path))
  (println (str "  行数: " (count (clojure.string/split-lines fixed)))))

(println "完了")
(workbench.core/stop!)
(System/exit 0)
