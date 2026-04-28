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
(require '[workbench.core   :as core])
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

    (println "\n=== all smoke tests passed ==="))

  ;; ── core/ 経由のテスト（start!/stop! のライフサイクル込み） ──────
  (println "\n=== core/start! (persist? false) ===")
  (core/start! {:persist? false})

  ;; core/xref! が :trial を中継できているか
  (println "\n=== core/xref! :trial core-trial ===")
  (let [n (core/xref! ["src"] :trial "core-trial")]
    (assert-pos "core/xref! refs (raw)" n)
    (let [rows (core/q '(from :refs [{:xt/id id :ref/trial t}]))
          trial-rows (filter #(= "core-trial" (:t %)) rows)]
      (assert-pos "core-trial 件数" (count trial-rows))
      (assert= ":xt/id prefix (core)" true
               (every? #(str/starts-with? (:id %) "core-trial::") trial-rows))))

  ;; core/jref! が :trial を中継できているか
  (println "\n=== core/jref! :trial core-java ===")
  (let [root (or (first *command-line-args*) "trials/samples/repo")
        n (core/jref! [root] :trial "core-java")]
    (assert-pos "core/jref! refs (raw)" n)
    (let [rows (core/q '(from :refs [{:xt/id id :ref/trial t}]))
          trial-rows (filter #(= "core-java" (:t %)) rows)]
      (assert= "core-java 件数" n (count trial-rows))))

  ;; core/refs のノイズフィルタ確認
  (println "\n=== core/refs noise filter ===")
  (let [all-refs (core/refs)]
    (assert-pos "core/refs 件数" (count all-refs))
    (assert= "clojure.* が除外されている" true
             (every? #(not (str/starts-with? (:to %) "clojure.")) all-refs))
    (assert= "<top-level> が除外されている" true
             (every? #(not (str/ends-with? (:from %) "/<top-level>")) all-refs)))

  ;; core/refs ns-prefix フィルタ確認
  (println "\n=== core/refs ns-prefix filter ===")
  (let [filtered (core/refs "workbench.core")]
    (assert-pos "workbench.core refs 件数" (count filtered))
    (assert= "workbench.core のみ" true
             (every? #(str/starts-with? (:from %) "workbench.core") filtered)))

  ;; core/call-tree-str の基本動作
  (println "\n=== core/call-tree-str ===")
  (let [all-refs (core/refs)
        s (core/call-tree-str all-refs "workbench.core/ingest!")]
    (assert= "call-tree-str が文字列" true (string? s)))
  ;; core/fan-out
  (println "\n=== core/fan-out ===")
  (let [rs  (core/refs)
        fos (core/fan-out rs)]
    (assert-pos "fan-out 結果件数" (count fos))
    (assert= ":symbol キーあり" true (every? #(contains? % :symbol) fos))
    (assert= ":count キーあり"  true (every? #(contains? % :count)  fos))
    (assert= "降順に並んでいる" true
             (apply >= (map :count fos))))

  ;; core/fan-in
  (println "\n=== core/fan-in ===")
  (let [rs  (core/refs)
        fis (core/fan-in rs)]
    (assert-pos "fan-in 結果件数" (count fis))
    (assert= ":symbol キーあり" true (every? #(contains? % :symbol) fis))
    (assert= ":count キーあり"  true (every? #(contains? % :count)  fis))
    (assert= "降順に並んでいる" true
             (apply >= (map :count fis))))

  ;; core/hotspots
  (println "\n=== core/hotspots ===")
  (let [hs (core/hotspots 3)]
    (assert= "hotspots 件数 ≤ 3" true (<= (count hs) 3))
    (assert= ":symbol キーあり" true (every? #(contains? % :symbol) hs)))

  ;; core/impact — hotspot の先頭シンボルへの上流を探索
  (println "\n=== core/impact ===")
  (let [top (-> (core/hotspots 1) first :symbol)
        result (core/impact top :depth 1)]
    (assert= "impact が set" true (set? result))
    ;; 自分自身は含まれない
    (assert= "impact に自分自身なし" false (contains? result top)))

  ;; core/deps — 同じシンボルの下流を探索
  (println "\n=== core/deps ===")
  (let [top (-> (core/hotspots 1) first :symbol)
        result (core/deps top :depth 1)]
    (assert= "deps が set" true (set? result))
    (assert= "deps に自分自身なし" false (contains? result top)))

  ;; core/neighborhood — 上流+下流の合体
  (println "\n=== core/neighborhood ===")
  (let [top (-> (core/hotspots 1) first :symbol)
        nb  (core/neighborhood top :depth 1)]
    (assert= "neighborhood が set" true (set? nb))
    ;; 中心シンボル自身は含まれる
    (assert= "neighborhood に中心シンボルあり" true (contains? nb top)))

  ;; core/jrefs — Java refs のノイズフィルタ確認
  (println "\n=== core/jrefs ===")
  (let [root (or (first *command-line-args*) "trials/samples/repo")
        _    (core/jref! [root] :trial "smoke-java2")
        rs   (core/jrefs :trial "smoke-java2")]
    (assert-pos "jrefs 件数" (count rs))
    (assert= "assertThat が除外されている" false
             (boolean (some #(= "assertThat" (:to %)) rs)))
    (assert= "<top-level> が除外されている" false
             (boolean (some #(= "<top-level>" (:from %)) rs))))

  ;; core/jrefs :exclude-test
  (println "\n=== core/jrefs :exclude-test ===")
  (let [root (or (first *command-line-args*) "trials/samples/repo")
        rs   (core/jrefs :trial "smoke-java2" :exclude-test true)]
    (assert= "Test クラスが除外されている" false
             (boolean (some #(re-find #"(Test|Tests)/" (:from %)) rs))))

  ;; core/topo-sort — 基本動作確認
  (println "\n=== core/topo-sort ===")
  (let [root   (or (first *command-line-args*) "trials/samples/repo")
        rs     (core/jrefs :trial "smoke-java2" :exclude-test true)
        sorted (core/topo-sort :rs rs)]
    (assert= "topo-sort が vector" true (vector? sorted))
    (assert-pos "topo-sort 件数" (count sorted))
    (assert= "重複なし" (count sorted) (count (distinct sorted))))

  ;; core/cochange! — openrefine-work 自身の git 履歴で確認
  (println "\n=== core/cochange! ===")
  (let [n (core/cochange! "." :trial "smoke-git" :filter-path "src")]
    (assert-pos "cochange! 件数" n)
    (let [rows (core/cochanges :trial "smoke-git")]
      (assert-pos "cochanges 件数" (count rows))
      (assert= ":a キーあり"   true (every? #(contains? % :a)   rows))
      (assert= ":b キーあり"   true (every? #(contains? % :b)   rows))
      (assert= ":cnt キーあり" true (every? #(contains? % :cnt) rows))
      (assert= "降順に並んでいる" true (apply >= (map :cnt rows)))
      ;; 冪等性
      (core/cochange! "." :trial "smoke-git" :filter-path "src")
      (assert= "再投入後も件数が同じ（冪等性）" n (count (core/cochanges :trial "smoke-git")))
      ;; :min-count フィルタ
      (let [rows2 (core/cochanges :trial "smoke-git" :min-count 2)]
        (assert= "min-count フィルタ" true (every? #(>= (:cnt %) 2) rows2)))))

  (core/stop!)
  (println "\n=== all core/ tests passed ==="))
