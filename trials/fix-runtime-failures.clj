;;; fix-runtime-failures.clj
;;; surefire レポートを元に runtime 失敗テストを fix-test で一括修正する
;;; 実行: guix shell -m manifest.scm -- clojure -M trials/fix-runtime-failures.clj

(require '[workbench.core :as c]
         '[clojure.java.io :as io]
         '[clojure.string :as str])

(println "=== XTDB 起動 ===")
(c/start!)
(println "起動完了")

(def trial "tradehub")
(def repo-base "trials/experiments/2026-04-28-tradehub/repo")
(def src-root  (str repo-base "/common-lib/src/main/java"))
(def test-base (str repo-base "/common-lib/src/test/java"))
(def report-dir (str repo-base "/common-lib/target/surefire-reports"))
(def model "openai/gpt-4.1")

;; class名 → 情報マップ
(def targets
  [{:class "AclServiceImpl"
    :pkg   "com/tradehub/web/acl/service/impl"
    :report "com.tradehub.web.acl.service.impl.AclServiceImplTest"}
   {:class "ExportInfoServiceImpl"
    :pkg   "com/tradehub/web/acl/service/impl"
    :report "com.tradehub.web.acl.service.impl.ExportInfoServiceImplTest"}
   {:class "PermissionServiceImpl"
    :pkg   "com/tradehub/web/common/service/impl"
    :report "com.tradehub.web.common.service.impl.PermissionServiceImplTest"}
   {:class "DocumentImportDebuggerController"
    :pkg   "com/tradehub/web/debugger/documentImportDebugger"
    :report "com.tradehub.web.debugger.documentImportDebugger.DocumentImportDebuggerControllerTest"}
   {:class "DocumentsServiceImpl"
    :pkg   "com/tradehub/web/document/service/impl"
    :report "com.tradehub.web.document.service.impl.DocumentsServiceImplTest"}
   {:class "IdaController"
    :pkg   "com/tradehub/web/ida/controller"
    :report "com.tradehub.web.ida.controller.IdaControllerTest"}
   {:class "UserDetailsServiceImpl"
    :pkg   "com/tradehub/web/identity/service/impl"
    :report "com.tradehub.web.identity.service.impl.UserDetailsServiceImplTest"}
   {:class "MasterStatusServiceImpl"
    :pkg   "com/tradehub/web/master/service/impl"
    :report "com.tradehub.web.master.service.impl.MasterStatusServiceImplTest"}
   {:class "MasterSupplementaryItemServiceImpl"
    :pkg   "com/tradehub/web/master/service/impl"
    :report "com.tradehub.web.master.service.impl.MasterSupplementaryItemServiceImplTest"}])

(doseq [{:keys [class pkg report]} targets]
  (let [test-file (str test-base "/" pkg "/" class "Test.java")
        report-file (str report-dir "/" report ".txt")]
    (if-not (.exists (io/file test-file))
      (println (str "SKIP (ファイルなし): " test-file))
      (if-not (.exists (io/file report-file))
        (println (str "SKIP (レポートなし): " report-file))
        (let [errors (slurp report-file)
              ;; 失敗がない場合はスキップ
              has-failure? (re-find #"<<< FAILURE!|<<< ERROR!" errors)]
          (if-not has-failure?
            (println (str "PASS (失敗なし): " class))
            (do
              (println (str "\n=== 修正中: " class " ==="))
              (try
                (let [fixed (c/fix-test class
                                        :trial trial
                                        :src-root src-root
                                        :java-path test-file
                                        :errors errors
                                        :model model)]
                  (spit test-file fixed)
                  (println (str "  → 書き込み完了: " test-file)))
                (catch Exception e
                  (println (str "  [ERROR] " (.getMessage e))))))))))))

(println "\n=== 全クラス処理完了 ===")
(System/exit 0)
