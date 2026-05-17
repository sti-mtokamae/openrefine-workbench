(require 'workbench.core)
(workbench.core/start!)
(println "=== APIを呼び出して生成コードを確認 ===")
(let [code (workbench.core/gen-test "ActivityRecordServiceImpl"
              :trial "tradehub"
              :method "sendOcrErrorNotificationEmail"
              :src-root "trials/experiments/2026-04-28-tradehub/repo/common-lib/src/main/java")]
  (println code))
