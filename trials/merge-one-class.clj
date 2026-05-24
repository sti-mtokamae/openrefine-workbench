(require 'workbench.core)
(workbench.core/start!)
(workbench.core/merge-test-mds
  :gen-dir "trials/experiments/2026-04-28-tradehub/exports/gen-tests"
  :class   "DocumentsVerificationDataServiceImpl"
  :force   true)
(workbench.core/stop!)
(System/exit 0)
