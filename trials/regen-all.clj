(require 'workbench.core)
(workbench.core/start!)
(println "=== gen-tests-uncovered :force true 開始 ===")
(workbench.core/gen-tests-uncovered
  :trial    "tradehub"
  :out-dir  "trials/experiments/2026-04-28-tradehub/exports/gen-tests"
  :src-root "trials/experiments/2026-04-28-tradehub/repo/common-lib/src/main/java"
  :force    true)
(println "=== 完了 ===")
