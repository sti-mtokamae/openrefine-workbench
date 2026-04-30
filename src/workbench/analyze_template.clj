#!/usr/bin/env clojure
;; =============================================================================
;; analyze_template.clj — Spring Boot モノリシックアプリ分析テンプレート
;;
;; 使い方:
;;   1. 下記「★ プロジェクト設定」を書き換える
;;   2. guix shell -m manifest.scm -- clojure -A:xtdb -J-Xmx768m -M <このファイル>
;;
;; 初回実行時は jref!/cochange! が XTDB に書き込む（数分かかる）。
;; 再実行時は jref!/cochange! をコメントアウトするとスキップできる。
;; XTDB データを作り直す場合は事前に rm -rf .xtdb/ を実行すること。
;;
;; 注意: bash の ! はヒストリ展開されるため、このファイルを -M で直接渡すこと。
;;       REPL で評価する場合も同様にファイル経由を推奨。
;; =============================================================================
(ns workbench.analyze-template
  (:require [workbench.core :as core]
            [clojure.string :as str]))

(core/start!)

;; =============================================================================
;; ★ プロジェクト設定（ここを書き換える）
;; =============================================================================

;; trial 名（XTDB の名前空間キー）
(def trial-name "my-project")

;; jref! の対象ディレクトリ（複数可）
(def src-dirs
  ["trials/experiments/YYYY-MM-DD-xxx/repo/common-lib"
   "trials/experiments/YYYY-MM-DD-xxx/repo/common-app"])

;; cochange! の対象リポジトリルート
(def repo-dir "trials/experiments/YYYY-MM-DD-xxx/repo")

;; git 履歴フィルタ（Java の場合は "src/main/java"）
(def git-filter-path "src/main/java")

;; パッケージパスからモジュール名を抽出する正規表現
;; 例: "com/example/web/([^/]+)/" → "document" "project" など
(def module-re #"com/example/web/([^/]+)/")

;; ノイズとして除外するクラス名プレフィックス（冷凍コード・自動生成など）
(def noise-cls-patterns
  [;; 例: #"^Acl" #"^ACL" #"^Ida" #"^IDA"
   ])

;; ノイズとして除外するモジュール名（完全一致）
(def noise-modules
  #{;; 例: "acl" "ida" "idaTask"
    })

;; ピンポイント分析の対象クラス（neighborhood/impact/deps で使う）
(def target-class "MyTargetServiceImpl")

;; =============================================================================
;; ノイズフィルタ（設定から自動構築）
;; =============================================================================

(def noise-cls?
  (if (seq noise-cls-patterns)
    (fn [cls] (boolean (some #(re-find % cls) noise-cls-patterns)))
    (constantly false)))

(def noise-mod? noise-modules)

(def path->cls
  (fn [path] (-> path (str/replace #".*/" "") (str/replace #"\.java$" ""))))

(def path->mod
  (fn [path]
    (when-let [m (re-find module-re path)]
      (second m))))

;; =============================================================================
;; フェーズ 1: jrefs 投入（初回のみ）
;; =============================================================================

(println "--- jref! ---")
(let [n (core/jref! src-dirs :trial trial-name)]
  (println (str "  refs: " n)))

;; =============================================================================
;; フェーズ 2: 静的依存 overview（fan-in / fan-out / topo-sort）
;; =============================================================================

(let [rs (core/jrefs :trial trial-name :exclude-test true)
      cls-of (fn [sym] (first (str/split sym #"/")))]

  (println "\n--- fan-in top 20 (hotspots, ノイズ除外) ---")
  (doseq [h (->> (core/fan-in rs)
                 (remove #(noise-cls? (cls-of (:symbol %))))
                 (take 20))]
    (println (format "  %3d  %s" (:count h) (:symbol h))))

  (println "\n--- fan-out top 20 (モンスターメソッド候補, ノイズ除外) ---")
  (doseq [h (->> (core/fan-out rs)
                 (remove #(noise-cls? (cls-of (:symbol %))))
                 (take 20))]
    (println (format "  %3d  %s" (:count h) (:symbol h))))

  (println "\n--- topo-sort 上位 40 (先に切り出せる順, ノイズ除外) ---")
  (let [sorted (->> (core/topo-sort :rs rs) (remove noise-cls?))]
    (println (str "  total classes (除外後): " (count sorted)))
    (doseq [[i c] (map-indexed vector (take 40 sorted))]
      (println (format "  %3d  %s" (inc i) c)))))

;; =============================================================================
;; フェーズ 3: cochange 投入（初回のみ）
;; =============================================================================

(println "\n--- cochange! ---")
(let [n (core/cochange! repo-dir :trial trial-name :filter-path git-filter-path)]
  (println (str "  co-change pairs: " n)))

;; =============================================================================
;; フェーズ 4: cochange 分析（全体 top + クロスモジュール top）
;; =============================================================================

(println "\n--- cochanges top 30 (ノイズ除外) ---")
(let [rows (->> (core/cochanges :trial trial-name :min-count 3)
                (remove #(or (noise-cls? (path->cls (:a %)))
                             (noise-cls? (path->cls (:b %)))
                             (noise-mod? (path->mod (:a %)))
                             (noise-mod? (path->mod (:b %))))))]
  (doseq [r (take 30 rows)]
    (println (format "  %3d  %s  <->  %s" (:cnt r) (:a r) (:b r)))))

(println "\n--- クロスモジュール cochange top 20 (ノイズ除外) ---")
(let [impl-noise? (fn [{:keys [ca cb]}]
                    ;; FooService <-> FooServiceImpl は同一クラスの揺れとして除外
                    (or (= ca (str cb "Impl"))
                        (= cb (str ca "Impl"))))
      cc-pairs    (->> (core/cochanges :trial trial-name :min-count 3)
                       (map (fn [r] {:cnt (:cnt r)
                                     :ca  (path->cls (:a r))
                                     :cb  (path->cls (:b r))
                                     :ma  (path->mod (:a r))
                                     :mb  (path->mod (:b r))})))
      cross       (->> cc-pairs
                       (remove impl-noise?)
                       (remove #(noise-cls? (:ca %)))
                       (remove #(noise-cls? (:cb %)))
                       (remove #(noise-mod? (:ma %)))
                       (remove #(noise-mod? (:mb %)))
                       (filter #(some? (:ma %)))
                       (filter #(some? (:mb %)))
                       (filter #(not= (:ma %) (:mb %))))]
  (println (format "  クロスモジュールペア総数 (除外後): %d" (count cross)))
  (doseq [r (take 20 cross)]
    (println (format "  %3d  [%s] %s  <->  [%s] %s"
                     (:cnt r) (:ma r) (:ca r) (:mb r) (:cb r)))))

;; =============================================================================
;; フェーズ 5: ピンポイント分析（target-class を変えて繰り返す）
;;
;; ヒント: クラス名のみ（/ なし）で渡すと core/impact・core/deps が
;;         "ClassName/" 前方一致で自動展開するので、メソッド名不要。
;; =============================================================================

(let [rs       (core/jrefs :trial trial-name :exclude-test true)
      cls-only (fn [sym] (first (str/split sym #"/")))
      filtered (fn [syms] (->> syms (map cls-only) (remove noise-cls?) distinct sort))]

  (println (format "\n--- impact: %s (1ホップ, 直接呼び出し元) ---" target-class))
  (doseq [c (filtered (core/impact target-class :depth 1 :rs rs))]
    (println (str "    " c)))

  (println (format "\n--- deps: %s (1ホップ, 直接呼び出し先) ---" target-class))
  (doseq [c (filtered (core/deps target-class :depth 1 :rs rs))]
    (println (str "    " c)))

  (println (format "\n--- neighborhood: %s (2ホップ) ---" target-class))
  (let [nb-cls (filtered (core/neighborhood target-class :depth 2 :rs rs))]
    (println (format "  size (クラス重複除去): %d" (count nb-cls)))
    (doseq [c nb-cls]
      (println (str "    " c)))))

(core/stop!)