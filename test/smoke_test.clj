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

(defn count-by-trial [node trial]
  (->> (xt/q node '(from :refs [{:xt/id id :ref/trial t}]))
       (filter #(= trial (:t %)))
       count))

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

    ;; ── xref! trial なし ─────────────────────
    (println "\n=== xref! (trial なし) ===")
    (let [n1 (ingest/xref! node ["src"])]
      (assert-pos "xref refs (raw)" n1)
      (let [cnt1 (count-by-trial node nil)]
        (assert-pos ":ref/trial=nil 件数" cnt1)
        ;; 冪等性: 同じ内容を再投入しても件数が変わらないこと
        (ingest/xref! node ["src"])
        (assert= "再投入後も件数が同じ（冪等性）" cnt1 (count-by-trial node nil))))

    ;; ── xref! trial あり ─────────────────────
    (println "\n=== xref! :trial smoke-clj ===")
    (let [n (ingest/xref! node ["src"] :trial "smoke-clj")]
      (assert-pos "xref refs (trial, raw)" n)
      (let [cnt (count-by-trial node "smoke-clj")]
        (assert-pos ":ref/trial=smoke-clj 件数" cnt)
        ;; :xt/id が "smoke-clj::" で始まること
        (let [rows (->> (xt/q node '(from :refs [{:xt/id id :ref/trial t}]))
                        (filter #(= "smoke-clj" (:t %))))]
          (assert= ":xt/id prefix" true
                   (every? #(str/starts-with? (:id %) "smoke-clj::") rows)))
        ;; 冪等性: 再投入しても件数が変わらないこと
        (ingest/xref! node ["src"] :trial "smoke-clj")
        (assert= "再投入後も件数が同じ（冪等性）" cnt (count-by-trial node "smoke-clj"))))

    ;; ── 差分同期: 古い refs が削除されること ──
    (println "\n=== 差分同期テスト ===")
    ;; 存在しないはずの偽 ref を "smoke-sync" trial に手動挿入
    (xt/execute-tx node [[:put-docs :refs {:xt/id    "smoke-sync::fake/fn->fake/other"
                                           :ref/trial "smoke-sync"
                                           :ref/from  "fake/fn"
                                           :ref/to    "fake/other"}]])
    (assert= "偽 ref 挿入後の件数" 1 (count-by-trial node "smoke-sync"))
    ;; xref! を実行すると偽 ref は削除され、実際の refs に置き換わる
    (ingest/xref! node ["src"] :trial "smoke-sync")
    (let [cnt-after (count-by-trial node "smoke-sync")]
      (assert-pos "同期後の件数 >0" cnt-after)
      ;; 偽 ref が消えていること
      (let [ids (->> (xt/q node '(from :refs [{:xt/id id :ref/trial t}]))
                     (filter #(= "smoke-sync" (:t %)))
                     (map :id)
                     set)]
        (assert= "偽 ref が削除された" false (contains? ids "smoke-sync::fake/fn->fake/other"))))

    ;; ── jref! trial なし ─────────────────────
    (println "\n=== jref! (trial なし) ===")
    (let [n (jref/jref! node [root])]
      (assert-pos "jref refs (trial なし, raw)" n)
      (assert-pos ":ref/trial=nil (jref)" (count-by-trial node nil)))

    ;; ── jref! trial あり ─────────────────────
    (println "\n=== jref! :trial smoke-java ===")
    (let [n (jref/jref! node [root] :trial "smoke-java")]
      (assert-pos "jref refs (trial)" n)
      (assert= ":ref/trial=smoke-java 件数" n (count-by-trial node "smoke-java"))
      ;; 冪等性
      (jref/jref! node [root] :trial "smoke-java")
      (assert= "再投入後も件数が同じ（冪等性）" n (count-by-trial node "smoke-java")))

    (println "\n=== all smoke tests passed ===")))
