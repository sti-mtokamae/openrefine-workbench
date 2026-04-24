#!/usr/bin/env clojure
;;
;; smoke_test.clj — Issue #2 動作確認スクリプト
;;
;; 実行方法:
;;   clojure -A:xtdb -M test/smoke_test.clj trials/samples/repo

(require '[clojure.java.io :as io]
         '[clojure.string :as str])

;; xtdb は JVM クラスパスにある前提
(require '[xtdb.api :as xt])
(require '[xtdb.node :as xtn])

(require '[workbench.ingest :as ingest])
(require '[workbench.visualize :as visualize])

;; --------------- main ---------------

(let [root (or (first *command-line-args*) "trials/samples/repo")]
  (println (str "=== ingest: " root " ==="))
  (with-open [node (xtn/start-node {})]
    (let [n (ingest/dir! node root)]
      (println (str "ingested: " n " entries"))
      (println)
      (println "=== query: all files ===")
      (let [result (xt/q node '(from :files [*]))]
        (println (str "rows: " (count result)))
        (println)
        (println "=== visualize: tree ===")
        (visualize/tree result)))))
