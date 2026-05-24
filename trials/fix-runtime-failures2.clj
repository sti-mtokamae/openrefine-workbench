;;; fix-runtime-failures2.clj
;;; 残り5クラスの runtime 失敗テストを fix-test で一括修正する
;;; 実行: guix shell -m manifest.scm -- clojure -A:xtdb -M trials/fix-runtime-failures2.clj

(require '[workbench.core :as c]
         '[clojure.java.io :as io])

(println "=== XTDB 起動 ===")
(c/start!)
(println "起動完了")

(def trial "tradehub")
(def repo-base "trials/experiments/2026-04-28-tradehub/repo")
(def src-root  (str repo-base "/common-lib/src/main/java"))
(def test-base (str repo-base "/common-lib/src/test/java"))
(def report-dir (str repo-base "/common-lib/target/surefire-reports"))
(def model "openai/gpt-4o")

(def targets
  [{:class "AclServiceImpl"
    :pkg   "com/tradehub/web/acl/service/impl"
    :report "com.tradehub.web.acl.service.impl.AclServiceImplTest"}
   {:class "ExportInfoServiceImpl"
    :pkg   "com/tradehub/web/acl/service/impl"
    :report "com.tradehub.web.acl.service.impl.ExportInfoServiceImplTest"
    ;; AclExportInfoDocFieldDefinitionsDto は Lombok @Data。
    ;; JavaParser で見えない生成メソッドを明示して AI の hallucination を防ぐ
    :extra-context (str "AclExportInfoDocFieldDefinitionsDto は boolean hasPrev, String hasDoc, boolean hasManu の3フィールドのみを持つ Lombok @Data クラスである。\n"
                        "利用可能な setter は setHasPrev(boolean), setHasDoc(String), setHasManu(boolean) の3つだけ。\n"
                        "setFieldDefinitions / setFieldNames / setLabels / FieldDefinition などは存在しない。\n"
                        "このクラスを stub するには new AclExportInfoDocFieldDefinitionsDto() のみで十分。setter を呼んではいけない。")}
   {:class "IdaController"
    :pkg   "com/tradehub/web/ida/controller"
    :report "com.tradehub.web.ida.controller.IdaControllerTest"
    :extra-context (str "IdaController.putDelineation() と putFareCalc() は内部で Objects.requireNonNull() を呼ぶため、\n"
                        "projectMapper.findAclExportInfoIdByProjectId() が Optional.empty() を返した場合は NullPointerException が発生する（IllegalArgumentException ではない）。\n"
                        "assertThrows で期待する例外型は NullPointerException を使うこと。")}
   {:class "MasterStatusServiceImpl"
    :pkg   "com/tradehub/web/master/service/impl"
    :report "com.tradehub.web.master.service.impl.MasterStatusServiceImplTest"}
   {:class "MasterSupplementaryItemServiceImpl"
    :pkg   "com/tradehub/web/master/service/impl"
    :report "com.tradehub.web.master.service.impl.MasterSupplementaryItemServiceImplTest"}])

(doseq [{:keys [class pkg report extra-context]} targets]
  (let [test-file   (str test-base "/" pkg "/" class "Test.java")
        report-file (str report-dir "/" report ".txt")]
    (cond
      (not (.exists (io/file test-file)))
      (println (str "SKIP (ファイルなし): " test-file))

      (not (.exists (io/file report-file)))
      (println (str "SKIP (レポートなし): " report-file))

      :else
      (let [errors       (slurp report-file)
            has-failure? (re-find #"<<< FAILURE!|<<< ERROR!" errors)]
        (if-not has-failure?
          (println (str "SKIP (失敗なし): " class))
          (do
            (println (str "=== 修正開始: " class " ==="))
            (try
              (let [opts (cond-> {:trial    trial
                                  :src-root src-root
                                  :java-path test-file
                                  :errors   errors
                                  :model    model}
                           extra-context (assoc :extra-context extra-context))
                    fixed (apply c/fix-test class (mapcat identity opts))]
                (spit test-file fixed)
                (println (str "完了: " class)))
              (catch Exception e
                (println (str "ERROR " class ": " (.getMessage e)))))))))))

(println "=== 全処理完了 ===")
(System/exit 0)
