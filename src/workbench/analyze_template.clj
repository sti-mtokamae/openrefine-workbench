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

;; =============================================================================
;; フェーズ 6: sqlref! — MyBatis @Select 等の SQL アノテーション解析
;;
;; :sql-refs テーブルに以下を格納する:
;;   :sqlref/param-binds — col = #{param} 形式のバインディング
;;   :sqlref/col-binds   — d.col = alias.col 形式の結合/CTE 縛り条件
;;
;; 利用シーン:
;;   - SQL の WHERE 縛りを静的に把握して修正漏れを検出する
;;   - :refs（呼び出しグラフ）と結合して「呼び出しチェーン上の SQL 縛り」を列挙する
;; =============================================================================

(println "\n--- sqlref! (MyBatis SQL アノテーション解析) ---")
(let [n (core/sqlref! src-dirs :trial trial-name)]
  (println (str "  sql-refs: " n)))

;; =============================================================================
;; フェーズ 7: refs × sql-refs — 呼び出しチェーン上の SQL 縛り列挙
;;
;; target-class から N ホップで到達できる Mapper メソッドのうち
;; 特定の SQL 縛りパターンを持つものを抽出する。
;;
;; ★ 縛り検出パターン（プロジェクトに合わせて書き換える）
;;   例: "source_process_id" — CTE の config_params.source_process_id との結合縛り
;;   例: "process_id"        — 任意のプロセスID縛り
;; =============================================================================

(let [rs        (core/jrefs :trial trial-name :exclude-test true)
      sqlrefs   (core/sqlrefs :trial trial-name)
      sym->sql  (into {} (map (juxt :sqlref/symbol identity) sqlrefs))
      cls-of    (fn [sym] (first (str/split sym #"/")))
      ;; target-class から2ホップで到達できるシンボルセット
      dep1      (->> rs (filter #(= (cls-of (:from %)) target-class)) (map :to) (into #{}))
      dep2      (->> rs (filter #(dep1 (:from %)))                     (map :to) (into #{}))
      reach     (into dep1 dep2)
      ;; ★ ここでフィルタするキーワードを設定する
      bind-pat  #"source_process_id"  ; ← プロジェクトに合わせて変更
      hits      (->> reach
                     (keep #(sym->sql %))
                     (filter (fn [r]
                               (some #(re-find bind-pat (str (:lhs %) (:rhs %)))
                                     (:sqlref/col-binds r))))
                     (sort-by :sqlref/symbol))]
  (println (format "\n--- refs × sql-refs: %s の 2ホップ圏内で \"%s\" 縛りを持つ Mapper ---"
                   target-class (str bind-pat)))
  (println (format "  該当: %d 件" (count hits)))
  (doseq [r hits]
    (println (str "  " (:sqlref/symbol r)))
    (doseq [b (filter #(re-find bind-pat (str (:lhs %) (:rhs %)))
                      (:sqlref/col-binds r))]
      (println (str "      " (:lhs b) " = " (:rhs b))))))

;; =============================================================================
;; フェーズ 8: SQL 変更影響レポート（sql-impact-report）
;;
;; SQL 縛りパターンにマッチする Mapper から全上流クラスを列挙し、
;; fan-in スコア・レイヤー分類を付与して変更コストを可視化する。
;;
;; ★ bind-pat をプロジェクトのカラム名パターンに合わせて変更する。
;; ★ 複数パターンを分析する場合はこのブロックを繰り返す。
;; =============================================================================

(let [rs       (core/jrefs :trial trial-name :exclude-test true)
      bind-pat #"source_process_id"  ; ← プロジェクトに合わせて変更
      report   (core/sql-impact-report bind-pat
                                        :trial trial-name
                                        :rs rs
                                        :noise-cls? noise-cls?)
      {:keys [mappers all-classes]} report
      layer-label {:controller "Controller" :service "Service"
                   :mapper "Mapper" :infra "Infra" :other "Other"}]

  (println (format "\n=== SQL 変更影響レポート: \"%s\" ===" (str bind-pat)))
  (println (format "  マッチ Mapper: %d 件  / 上流ユニーククラス: %d 件"
                   (count mappers) (count all-classes)))

  ;; --- サマリ: 全上流クラスを fan-in 降順 ---
  (println "\n--- 上流クラス一覧（fan-in 降順・変更コスト高い順）---")
  (println (format "  %-45s %-12s %s" "class" "layer" "fan-in"))
  (println (str "  " (apply str (repeat 65 "-"))))
  (doseq [{:keys [class layer fan-in]} all-classes]
    (println (format "  %-45s %-12s %d" class (layer-label layer) fan-in)))

  ;; --- モジュール別集計 ---
  (println "\n--- レイヤー別クラス数 ---")
  (doseq [[layer cnt] (->> all-classes
                           (group-by :layer)
                           (map (fn [[l xs]] [(layer-label l) (count xs)]))
                           (sort-by second >))]
    (println (format "  %-12s %d 件" layer cnt)))

  ;; --- Mapper 別詳細 ---
  (println "\n--- Mapper 別詳細 ---")
  (doseq [{:keys [mapper-sym col-binds upstream]} mappers]
    (println (str "\n  [Mapper] " mapper-sym))
    (doseq [b col-binds]
      (println (str "    縛り: " (:lhs b) " = " (:rhs b))))
    (println (format "    上流 %d クラス:" (count upstream)))
    (doseq [{:keys [class layer fan-in]} upstream]
      (println (format "      %-45s [%s] fan-in=%d"
                       class (layer-label layer) fan-in)))))

(core/stop!)