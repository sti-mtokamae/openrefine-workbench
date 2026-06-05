#!/usr/bin/env clojure
;;
;; compile_ok_smoke.clj - compile-ok-java-files の最小動作確認
;;
;; 実行方法:
;;   guix shell -m manifest.scm -- clojure -M:xtdb trials/samples/self-analysis/compile_ok_smoke.clj

(require '[clojure.java.io :as io]
         '[clojure.string :as str]
         '[workbench.core :as core])

(defn assert= [label expected actual]
  (if (= expected actual)
    (println (str "  ok  " label ": " actual))
    (do
      (println (str "  ng  " label " expected=" expected " actual=" actual))
      (System/exit 1))))

(let [sample-dir "trials/samples/self-analysis"]
  (println "\n=== compile-ok smoke ===")
  (core/start! {:persist? false})
  (try
    (let [ok-files (->> (core/compile-ok-java-files sample-dir)
                        (mapv #(-> % io/file .getName))
                        sort)
          err-docs (core/compile-errors-dir! sample-dir)
          error-map (into {}
                      (map (fn [doc]
                             [(-> (:file/path doc) io/file .getName)
                              (count (:java/compile-errors doc))]))
                      err-docs)]
      (assert= "compile-ok-java-files" ["SampleOk.java"] ok-files)
      (assert= "SampleOk errors" 0 (get error-map "SampleOk.java"))
      (assert= "SampleError errors > 0" true (pos? (get error-map "SampleError.java" 0)))
      (println "\n=== compile-ok smoke passed ==="))
    (finally
      (core/stop!))))
