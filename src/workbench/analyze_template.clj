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
;; フェーズ 8: SQL 変更影響レポート（複数パターン + cochange 照合）
;;
;; SQL 縛りパターンを複数まとめて分析し、fan-in + cochange 履歴で変更コストを可視化。
;; cochange-cnt=0 のクラスは「静的解析で検出されたが変更履歴なし（未テスト経路の疑い）」。
;;
;; ★ bind-pats にプロジェクトのカラム名パターンを追加する。
;; =============================================================================

(let [rs          (core/jrefs :trial trial-name :exclude-test true)
      bind-pats   [;; ★ [ラベル 正規表現] の形式で追加する
                   ["source_process_id" #"source_process_id"]
                   ;; ["work_process_id"   #"work_process_id"]
                   ]
      layer-label {:controller "Controller" :service "Service"
                   :mapper "Mapper" :infra "Infra" :other "Other"}
      reports     (->> (core/sql-impact-report-multi bind-pats
                                                      :trial trial-name
                                                      :rs rs
                                                      :noise-cls? noise-cls?)
                       (map #(core/sql-cochange-check % :trial trial-name)))]

  (doseq [{:keys [label mappers all-classes]} reports]
    (println (format "\n=== SQL 変更影響レポート: \"%s\" ===" label))
    (println (format "  マッチ Mapper: %d 件 / 上流ユニーククラス: %d 件"
                     (count mappers) (count all-classes)))

    ;; --- サマリ: 全上流クラスを fan-in 降順、cochange-cnt 付き ---
    (println "\n  --- 上流クラス一覧（fan-in 降順）---")
    (println (format "  %-42s %-12s %6s  %s" "class" "layer" "fan-in" "cochange"))
    (println (str "  " (apply str (repeat 70 "-"))))
    (doseq [{:keys [class layer fan-in cochange-cnt]} all-classes]
      (let [warn (when (zero? cochange-cnt) " ← 履歴なし")]
        (println (format "  %-42s %-12s %6d  %3d%s"
                         class (layer-label layer) fan-in cochange-cnt warn))))

    ;; --- レイヤー別集計 ---
    (println "\n  --- レイヤー別クラス数 ---")
    (doseq [[layer cnt] (->> all-classes
                             (group-by :layer)
                             (map (fn [[l xs]] [(layer-label l) (count xs)]))
                             (sort-by second >))]
      (println (format "  %-12s %d 件" layer cnt)))

    ;; --- cochange なし（要注意）クラス ---
    (let [no-cc (filter #(zero? (:cochange-cnt %)) all-classes)]
      (when (seq no-cc)
        (println (format "\n  --- 変更履歴なし（git 未確認）: %d 件 ---" (count no-cc)))
        (doseq [{:keys [class layer]} no-cc]
          (println (format "  %-42s [%s]" class (layer-label layer))))))

    ;; --- Mapper 別詳細 ---
    (println "\n  --- Mapper 別詳細 ---")
    (doseq [{:keys [mapper-sym col-binds upstream]} mappers]
      (println (str "\n  [Mapper] " mapper-sym))
      (doseq [b col-binds]
        (println (str "    縛り: " (:lhs b) " = " (:rhs b))))
      (doseq [{:keys [class layer fan-in cochange-cnt]} upstream]
        (println (format "    %-42s [%s] fan-in=%d cochange=%d"
                         class (layer-label layer) fan-in cochange-cnt))))))

;; =============================================================================
;; フェーズ 9: 注目クラス深堀り分析
;;
;; cochange 上位 / fan-in 上位などから「気になるクラス」を選び、
;; impact（呼び出し元）/ deps（呼び出し先）/ cochange 上位ペアを精査する。
;;
;; ★ targets にプロジェクトの注目クラス名を列挙する（クラス名のみ、/ は不要）。
;; ★ module-re はプロジェクト設定の値がそのまま使われる。
;; =============================================================================

(println "\n=== フェーズ 9: 注目クラス深堀り ===")
(try
  (let [rs        (core/jrefs :trial trial-name :exclude-test true)

        ;; ★ 注目クラスをここに列挙する
        targets   ["ServiceImplA"
                   "ControllerB"
                   "EntityC"]

        cc-rows   (core/cochanges :trial trial-name :min-count 3)

        ;; クラス名 → cochange 上位ペア（相手クラス名 + モジュール + カウント）
        cls-top-cc
        (fn [cls]
          (->> cc-rows
               (filter #(or (= cls (path->cls (:a %)))
                            (= cls (path->cls (:b %)))))
               (map (fn [{:keys [a b cnt]}]
                      {:partner (if (= cls (path->cls a)) (path->cls b) (path->cls a))
                       :mod     (if (= cls (path->cls a)) (path->mod b) (path->mod a))
                       :cnt cnt}))
               (remove #(noise-cls? (:partner %)))
               (sort-by :cnt >)
               (take 8)))]

    (doseq [cls targets]
      (println (format "\n=== %s ===" cls))

      ;; 上流（呼び出し元）: cls を呼んでいるクラス 1ホップ
      ;; impact にはクラス名のみを渡す（"ClassName" — "/" サフィックス不要）
      (let [up (->> (core/impact cls :depth 1 :rs rs)
                    (map #(first (str/split % #"/")))
                    (remove noise-cls?)
                    (remove #{cls})
                    distinct sort)]
        (println (format "  呼び出し元クラス (1hop): %d" (count up)))
        (doseq [s (take 10 up)] (println (str "    " s))))

      ;; 下流（呼び出し先）: cls が呼んでいるクラス 1ホップ
      ;; deps にはクラス名のみを渡す（"ClassName" — "/" サフィックス不要）
      (let [dn (->> (core/deps cls :depth 1 :rs rs)
                    (map #(first (str/split % #"/")))
                    (remove noise-cls?)
                    (remove #{cls})
                    distinct sort)]
        (println (format "  呼び出し先クラス (1hop): %d" (count dn)))
        (doseq [s (take 10 dn)] (println (str "    " s))))

      ;; cochange 上位ペア（min-count 3 = 3回以上同時変更）
      (let [pairs (cls-top-cc cls)]
        (println "  cochange 上位 (min-count 3):")
        (doseq [{:keys [partner mod cnt]} pairs]
          (println (format "    %3d  %-45s [%s]" cnt partner (or mod "?")))))))

  (catch Exception ex
    (println (str "  [WARN] フェーズ 9 failed: " (ex-message ex)))))

;; =============================================================================
;; フェーズ 10: テスト生成候補の洗い出し + AI テスト生成
;;
;; JaCoCo 未カバー（covered=0）× SQL 縛りありのメソッドを全件洗い出し、
;; GitHub Models API 経由で JUnit 5 + Mockito テストコードを生成する。
;;
;; 前提: 以下がすでに XTDB に投入済みであること
;;   - jrefs  (フェーズ 1)
;;   - sqlrefs (フェーズ 6)
;;   - jacocos — (core/jacoco! "/path/to/jacoco.xml" :trial trial-name) で投入済み
;;   - jsigs  — (core/jsig! src-dirs :trial trial-name) で投入済み
;;
;; ★ STEP A: シグネチャ投入（初回のみ・冪等）
;;   (core/jsig! src-dirs :trial trial-name)
;;
;; ★ STEP B: JaCoCo 投入（初回のみ・冪等）
;;   (core/jacoco! "/path/to/jacoco.xml" :trial trial-name)
;;
;; ★ STEP C: 候補確認（dry-run=true → API 呼び出しなし）
;; ★ STEP D: 実際に生成（:out-dir 指定でファイル書き出し）
;; =============================================================================

(println "\n=== フェーズ 10: テスト生成候補 ===")
(try
  ;; --- STEP A: jsig! でメソッドシグネチャを投入 ---
  (println "\n--- jsig! (メソッドシグネチャ投入) ---")
  (let [n (core/jsig! src-dirs :trial trial-name)]
    (println (str "  jsigs: " n)))

  ;; --- STEP B: 候補一覧（干実行） ---
  (println "\n--- uncovered-sql-methods (dry-run) ---")
  (let [candidates (core/uncovered-sql-methods :trial trial-name)]
    (println (str "  候補メソッド数: " (count candidates)))
    (println (str "  対象クラス数:   "
                  (count (distinct (map :class candidates)))))

    ;; クラス別サマリ
    (println "\n  --- クラス別内訳 ---")
    (doseq [[cls ms] (->> candidates
                           (group-by :class)
                           (sort-by (comp count second) >))]
      (println (format "  %-50s %2d メソッド" cls (count ms)))))

  ;; --- STEP C: 実際に生成する場合 ---
  ;; ★ 以下のコメントを外して実行する（API が呼ばれるため時間がかかる）
  ;; ★ :out-dir を指定するとクラス別ディレクトリにファイルが書き出される
  ;;
  ;; (println "\n--- gen-tests-uncovered ---")
  ;; (let [results (core/gen-tests-uncovered
  ;;                :trial   trial-name
  ;;                ;; :model "openai/gpt-4.1"
  ;;                :out-dir (str "trials/experiments/"
  ;;                              trial-name "/gen-tests"))]
  ;;   (println (str "  生成完了: " (count results) " メソッド"))
  ;;   (doseq [{:keys [class method]} results]
  ;;     (println (str "    " class "/" method))))

  (catch Exception ex
    (println (str "  [WARN] フェーズ 10 failed: " (ex-message ex)))))

(core/stop!)

(core/stop!)