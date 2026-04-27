#!/usr/bin/env clojure
;;
;; smoke_test.clj — 動作確認スクリプト
;;
;; 実行方法:
;;   clojure -A:xtdb -M test/smoke_test.clj trials/samples/repo

(require '[clojure.java.io :as io]
         '[clojure.string :as str])

;; xtdb は JVM クラスパスにある前提
(require '[xtdb.api :as xt])
(require '[xtdb.node :as xtn])

(require '[workbench.ingest :as ingest])
(require '[workbench.jref   :as jref])
(require '[workbench.visualize :as visualize])

;; --------------- helpers ---------------

(defn assert-pos [label n]
  (if (pos? n)
    (println (str "  ✓ " label ": " n))
    (do (println (str "  ✗ " label " は 0 件（期待: >0）"))
        (System/exit 1))))

(defn assert= [label expected actual]
  (if (= expected actual)
    (println (str "  ✓ " label ": " actual))
    (do (println (str "  ✗ " label " expected=" expected " actual=" actual))
        (System/exit 1))))

;; --------------- main ---------------

(let [root (or (first *command-line-args*) "trials/samples/repo")]
  (with-open [node (xtn/start-node {})]

    ;; ── ingest! ──────────────────────────────
    (println "\n=== ingest! ===")
    (let [n (ingest/dir! node root)]
      (assert-pos "files ingested" n))

    ;; ── query: all files ─────────────────────
    (println "\n=== query: all files ===")
    (let [result (xt/q node '(from :files [*]))]
      (assert-pos "files in DB" (count result)))

    ;; ── visualize: tree ──────────────────────
    (println "\n=== visualize: tree ===")
    (visualize/tree (xt/q node '(from :files [*])))

    ;; ── xref! (trial なし) ────────────────────
    (println "\n=== xref! (trial なし) ===")
    (let [n (ingest/xref! node ["src"])]
      (assert-pos "xref refs (raw)" n)
      ;; :ref/trial が nil であること（重複排除後の件数は n 以下になりうる）
      (let [rows (xt/q node '(from :refs [{:xt/id id :ref/trial t}]))]
        (assert-pos ":ref/trial nil 件数" (count (filter #(nil? (:t %)) rows)))))

    ;; ── xref! (trial あり) ────────────────────
    (println "\n=== xref! :trial smoke-clj ===")
    (let [n (ingest/xref! node ["src"] :trial "smoke-clj")]
      (assert-pos "xref refs (trial, raw)" n)
      ;; :xt/id が "smoke-clj::" で始まること
      (let [rows (xt/q node '(from :refs [{:xt/id id :ref/trial t}]))]
        (let [trial-rows (filter #(= "smoke-clj" (:t %)) rows)]
          (assert-pos ":ref/trial=smoke-clj 件数" (count trial-rows))
          (assert= ":xt/id prefix" true
                   (every? #(str/starts-with? (:id %) "smoke-clj::") trial-rows)))))

    ;; ── jref! (trial あり) ────────────────────
    (println "\n=== jref! :trial smoke-java ===")
    (let [n (jref/jref! node [root] :trial "smoke-java")]
      (assert-pos "jref refs (trial)" n)
      (let [rows (xt/q node '(from :refs [{:xt/id id :ref/trial t}]))]
        (let [trial-rows (filter #(= "smoke-java" (:t %)) rows)]
          (assert= ":ref/trial=smoke-java 件数" n (count trial-rows)))))

    (println "\n=== all smoke tests passed ===")))
