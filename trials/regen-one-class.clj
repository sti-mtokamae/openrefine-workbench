(require 'workbench.core)
(workbench.core/start!)
(def trial "tradehub")
(def out-dir "trials/experiments/2026-04-28-tradehub/exports/gen-tests")
(def src-root "trials/experiments/2026-04-28-tradehub/repo/common-lib/src/main/java")
(def class-name "ActivityRecordServiceImpl")

(doseq [method ["sendOcrCompleteNotificationEmail" "sendOcrErrorNotificationEmail"]]
  (println (str "=== 生成中: " class-name "/" method " ==="))
  (let [dir  (str out-dir "/" class-name)
        path (str dir "/" method ".md")
        code (workbench.core/gen-test class-name
               :trial trial :method method :src-root src-root)]
    (clojure.java.io/make-parents (clojure.java.io/file path))
    (spit path (str "# " class-name "/" method "\n\n```java\n" code "\n```\n"))
    (println (str "  保存: " path))))
(println "完了")
(workbench.core/stop!)
(System/exit 0)
