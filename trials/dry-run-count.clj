(require 'workbench.core)
(workbench.core/start!)
(let [candidates (workbench.core/uncovered-sql-methods :trial "tradehub")]
  (println (str "対象メソッド数: " (count candidates)))
  (doseq [c (take 10 candidates)]
    (println (str "  " (:class c) "/" (:method c)))))
