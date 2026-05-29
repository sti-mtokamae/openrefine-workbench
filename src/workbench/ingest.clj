(ns workbench.ingest
  "ディレクトリ・Git 履歴などを XTDB v2 に取り込む関数群。
   Human REPL と AI Agent が同じ呼び出し面を使う。"
  (:require
   [clojure.java.io :as io]
   [clojure.java.shell :refer [sh]]
   [clojure.set :as set]
   [clojure.string :as str]
   [clj-xref.core :as xref]
   [xtdb.api :as xt]))

;; -------------------------
;; helpers
;; -------------------------

(defn- file->doc [root-path ^java.io.File f]
  (let [abs      (.getAbsolutePath f)
        rel      (-> (io/file root-path) .getAbsolutePath
                     (as-> rp (subs abs (inc (count rp)))))
        ext      (let [n (.getName f)]
                   (when (.contains n ".") (last (clojure.string/split n #"\."))))
        parent   (.getParent (io/file rel))]
    {:xt/id      rel
     :file/path  rel
     :file/name  (.getName f)
     :file/ext   ext
     :file/size  (.length f)
     :file/dir?  (.isDirectory f)
     :file/parent parent}))

;; -------------------------
;; public API
;; -------------------------

(defn dir
  "root 以下のファイル・ディレクトリを file-seq でスキャンし、
   ドキュメントのシーケンスとして返す。
   XTDB ノードへの put は呼び出し側が行う（あるいは ingest/dir! を使う）。

   例:
     (ingest/dir \"trials/repo\")"
  [root]
  (let [root-file (io/file root)]
    (->> (file-seq root-file)
         (remove #(= % root-file))
         (map #(file->doc root %)))))

(defn dir!
  "root 以下をスキャンして node に一括 put する。

   例:
     (with-open [node (xtn/start-node {})]
       (ingest/dir! node \"trials/repo\"))"
  [node root]
  (let [docs (dir root)
        txs  (mapv (fn [doc] [:put-docs :files doc]) docs)]
    (xt/execute-tx node txs)
    (count docs)))

(defn git
  "Git リポジトリの commit ログを取り込む（将来実装）。"
  [_repo]
  (throw (ex-info "not implemented yet" {:fn 'ingest/git})))

(defn files
  "任意ファイルパスのリストをドキュメントとして返す（将来実装）。"
  [_paths]
  (throw (ex-info "not implemented yet" {:fn 'ingest/files})))

;; -------------------------
;; clj-xref (Clojure cross-reference)
;; -------------------------

(defn- ref->doc [{:keys [kind from to file line col arity] :as _ref} trial]
  (let [id-prefix (if trial (str trial "::") "")]
    {:xt/id        (str id-prefix from "->" to)
     :ref/trial    trial
     :ref/kind     (str kind)
     :ref/from     (str from)
     :ref/to       (str to)
     :ref/file     (str file)
     :ref/line     line
     :ref/col      col
     :ref/arity    arity}))

(defn xref!
  "Clojure ソースの cross-reference 解析結果を XTDB :refs テーブルに put する。
   clj-xref (clj-kondo ベース) を使う。

   paths: 解析対象パスのベクタ（例: [\"src\" \"test\"]）
   opts:
     :trial - トライアル識別子（文字列）。省略可。

   例:
     (ingest/xref! node [\"src\"])
     (ingest/xref! node [\"src\"] :trial \"my-trial\")"
  [node paths & {:keys [trial]}]
  (let [db      (xref/analyze paths)
        refs    (get db :refs [])
        new-ids (set (map #(:xt/id (ref->doc % trial)) refs))
        old-ids (->> (xt/q node '(from :refs [{:xt/id id :ref/trial t}]))
                     (filter #(= (:t %) trial))
                     (map :id)
                     set)
        del-txs (mapv (fn [id] [:delete-docs :refs id]) (set/difference old-ids new-ids))
        put-docs (mapv (fn [r] (ref->doc r trial)) refs)]
    (when (seq del-txs) (xt/execute-tx node del-txs))
    (doseq [batch (partition-all 2000 put-docs)]
      (xt/execute-tx node (mapv (fn [doc] [:put-docs :refs doc]) batch)))
    (count put-docs)))

;; -------------------------
;; co-change (git history)
;; -------------------------

(defn- git-log-commits
  "git log --name-only をストリーム処理し、コミットごとのファイルリストを lazy seq で返す。"
  [repo-path extra-args]
  (let [cmd  (into ["git" "-C" repo-path "log" "--name-only" "--format=COMMIT:%H"] extra-args)
        proc (-> (ProcessBuilder. ^java.util.List cmd)
                 (.redirectErrorStream true)
                 .start)
        rdr  (java.io.BufferedReader.
               (java.io.InputStreamReader. (.getInputStream proc)))
        lines (line-seq rdr)]
    ;; COMMIT: 行でグループ化し、各グループのファイル名リストを返す
    (->> (partition-by #(str/starts-with? % "COMMIT:") lines)
         (remove (fn [g] (str/starts-with? (first g) "COMMIT:")))
         (map (fn [g] (->> g (map str/trim) (remove str/blank?) vec)))
         (remove empty?))))

(defn- commit-pairs
  "ファイルリスト → ペア列（a < b）"
  [files]
  (let [fs (vec (sort files))]
    (for [i (range (count fs))
          j (range (inc i) (count fs))]
      [(fs i) (fs j)])))

(defn cochange!
  "Git 履歴から共変更（co-change）ペアを集計して XTDB :cochanges テーブルに put する。
   差分同期（削除＋追加）を行うので冪等。

   repo-path: git リポジトリルート（文字列）
   opts:
     :trial       - トライアル識別子（文字列）
     :filter-path - 解析対象パス（git log -- <path> で絞り込み）

   例:
     (ingest/cochange! node \"/path/to/repo\")
     (ingest/cochange! node \"/path/to/tradehub\" :trial \"tradehub\" :filter-path \"src/main/java\")"
  [node repo-path & {:keys [trial filter-path]}]
  (let [extra-args (when filter-path ["--" filter-path])
        counts   (->> (git-log-commits repo-path extra-args)
                      (mapcat commit-pairs)
                      frequencies)
        id-prefix (if trial (str trial "::") "")
        new-docs (mapv (fn [[[a b] cnt]]
                         {:xt/id    (str id-prefix a "::" b)
                          :cc/trial trial
                          :cc/a     a
                          :cc/b     b
                          :cc/count cnt})
                       counts)
        new-ids  (set (map :xt/id new-docs))
        old-ids  (->> (xt/q node '(from :cochanges [{:xt/id id :cc/trial t}]))
                      (filter #(= (:t %) trial))
                      (map :id)
                      set)
        del-txs  (mapv (fn [id] [:delete-docs :cochanges id]) (set/difference old-ids new-ids))]
    (when (seq del-txs) (xt/execute-tx node del-txs))
    ;; 大量 docs を一括送信すると Arrow が heap を使い切るため 2000 件ずつ投入
    (doseq [batch (partition-all 2000 new-docs)]
      (xt/execute-tx node (mapv (fn [doc] [:put-docs :cochanges doc]) batch)))
    (count new-docs)))

;; -------------------------
;; test-compile errors (DEPRECATED, DO NOT USE)
;; -------------------------
;;
;; (defn test-compile-errors!
;;   "【非推奨】mvnw test-compile を実行してテストコンパイルエラーを XTDB :test-compile-errors に記録する。
;;   ...（省略）...
;;   完全非推奨。DiagnosticCollectorベースの新実装に置き換える予定。"
;;   ...)

;; -------------------------
;; gen-test analysis (jref/jsig based ranking)
;; -------------------------

(defn- extract-imports
  "Java ソースコードから import 文を抽出する。
   例：'import com.example.Mapper;' → 'com.example.Mapper'"
  [source-code]
  (->> (re-seq #"(?:^|\n)\s*import\s+(?:static\s+)?([a-zA-Z0-9._*]+)" source-code)
       (map second)
       (filter #(not (str/ends-with? % ".*")))  ;; ワイルドカード import は除く
       set))

(defn- count-lines
  "Java ソースの有効行数をカウント（コメント・空白行除外）"
  [source-code]
  (->> (str/split source-code #"\n")
       (map str/trim)
       (remove #(or (empty? %) (str/starts-with? % "//") (str/starts-with? % "*")))
       count))

(defn- count-assertions
  "Java ソースから assertion 的なパターンをカウント
   - assertTrue, assertFalse, assertEquals, assertNull など"
  [source-code]
  (count (re-seq #"assert[A-Za-z]*\s*\(" source-code)))

(defn- count-method-calls
  "Java ソースから メソッド呼び出し数をカウント（簡易版）"
  [source-code]
  (count (re-seq #"\.\s*[a-z_][a-zA-Z0-9]*\s*\(" source-code)))


;;
;; can-compile? は「静的な括弧・コメント対応のみの超簡易構文チェック」関数です。
;; - 実際のjavacやmvn test-compileの結果とは異なり、
;;   本当の意味での「コンパイル可能性」を保証しません。
;; - DiagnosticCollector等による本格的なエラー検出実装後は、
;;   そちらに置き換えるか、fallback用途に限定することを推奨します。
(defn- can-compile?
  "【簡易構文チェック】Java ファイルが javac でコンパイル可能かを超簡易的に判定。
   ※実際のjavac実行はせず、括弧やコメントの対応のみをチェック。
   本格的なエラー検出にはDiagnosticCollector等の利用を推奨。"
  [source-code]
  (try
    ;; 基本的な Java 構文チェック
    ;; - コメント未閉鎖: /* ... */ が対になっているか
    ;; - 括弧の対応: { } ( ) [ ] が対になっているか
    (let [comment-opens (count (re-seq #"/\*" source-code))
          comment-closes (count (re-seq #"\*/" source-code))
          comment-ok? (= comment-opens comment-closes)
          brace-opens (count (re-seq #"\{" source-code))
          brace-closes (count (re-seq #"\}" source-code))
          brace-ok? (= brace-opens brace-closes)
          paren-opens (count (re-seq #"\(" source-code))
          paren-closes (count (re-seq #"\)" source-code))
          paren-ok? (= paren-opens paren-closes)]
      (and comment-ok? brace-ok? paren-ok?))
    (catch Exception _
      false)))

(defn analyze-gen-test
  "生成されたテストファイル（Test.java）を品質スコアに基づいてランク分けする（A/B/C/D）。
   コンパイル不可なテストは自動的に D ランクに格下げ。

   node: XTDB ノード
   test-file-path: Test.java のファイルパス
   trial: トライアル ID
   opts:
     :class-name - テスト対象のクラス名

   返値：
     {:class-name \"ActivityRecord\",
      :rank :A,
      :loc 150,
      :assertions 12,
      :method-calls 25,
      :compiles? true,
      :coverage 0.0}

   ランク判定（テスト品質スコアベース）：
     コンパイル不可 → D（優先）
     A: LOC >= 100 + Assertion >= 10
     B: LOC >= 50 + Assertion >= 3
     C: LOC >= 20
     D: LOC < 20
  "
  [node test-file-path trial & {:keys [class-name]}]
  (try
    (let [source (slurp test-file-path)
          loc (count-lines source)
          assertions (count-assertions source)
          method-calls (count-method-calls source)
          compiles? (can-compile? source)
          
          ;; ランク判定（品質スコアベース）
          ;; コンパイル不可 → 自動 D ランク
          rank (if-not compiles?
                 :D
                 (cond
                   (and (>= loc 100) (>= assertions 10)) :A
                   (and (>= loc 50) (>= assertions 3)) :B
                   (>= loc 20) :C
                   :else :D))]
      
      {:class-name class-name
       :rank rank
       :loc loc
       :assertions assertions
       :method-calls method-calls
       :compiles? compiles?
       :coverage 0.0
       :undefined-classes []
       :undefined-methods []
       :defined-count 0
       :total-count 0})
    
    (catch Exception e
      ;; ファイル読み込み失敗時は :D ランク
      {:class-name class-name
       :rank :D
       :error (str e)
       :loc 0
       :assertions 0
       :method-calls 0
       :compiles? false
       :coverage 0.0
       :undefined-classes []
       :undefined-methods []
       :defined-count 0
       :total-count 0})))

;; gen-tests/ ディレクトリ配下のすべての Test.java ファイルを分析し、
;; XTDB :gen-tests テーブルに格納する（差分同期）。
;;
;; opts:
;;   :gen-tests-dir - gen-tests ディレクトリのパス
;;   :trial - トライアル ID
;;   :src-roots - プロダクションソースのルートディレクトリ群
;;
;; 例:
;;   (analyze-gen-tests-dir! node :gen-tests-dir "trials/experiments/2026-04-28-tradehub/exports/gen-tests" :trial "tradehub" :src-roots ["src/main/java"])
(defn analyze-gen-tests-dir!
  [node & {:keys [gen-tests-dir trial src-roots]}]
  (let [;; gen-tests-dir 直下のサブディレクトリ（クラス名）を列挙
        gen-tests-path (io/file gen-tests-dir)
        class-dirs (when (.isDirectory gen-tests-path)
                     (->> (.listFiles gen-tests-path)
                          (filter #(.isDirectory ^java.io.File %))
                          (map #(.getName ^java.io.File %))
                          sort))
        
        ;; 各クラスディレクトリの Test.java を分析
        ;; helper: find production source java file for given class name
        find-src-file (fn [class-name]
                        (when (seq src-roots)
                          (some (fn [root]
                                  (let [files (file-seq (io/file root))]
                                    (some (fn [^java.io.File f]
                                            (when (and (.isFile f)
                                                       (= (.getName f) (str class-name ".java")))
                                              (.getAbsolutePath f)))
                                          files))) src-roots)))

        docs (mapv (fn [class-dir]
                    (let [test-java-path (str gen-tests-dir "/" class-dir "/" class-dir "Test.java")
                          test-file (io/file test-java-path)]
                      (if (.exists test-file)
                        (let [analysis (analyze-gen-test node test-java-path trial :class-name class-dir)
                              src-file (find-src-file class-dir)
                              src-loc (when src-file
                                        (try
                                          (let [s (slurp src-file)]
                                            (count-lines s))
                                          (catch Exception _ 0)))
                              doc {:xt/id (str trial "::" class-dir)
                                   :gta/trial trial
                                   :gta/class-name class-dir
                                   :gta/rank (:rank analysis)
                                   :gta/loc (:loc analysis)
                                   :gta/assertions (:assertions analysis)
                                   :gta/method-calls (:method-calls analysis)
                                   :gta/compiles? (:compiles? analysis)
                                   :gta/coverage (:coverage analysis)
                                   :gta/src-loc src-loc
                                   :gta/analyzed-at (java.time.Instant/now)}]
                          doc)
                        ;; Test.java が見つからない場合はスキップ
                        nil)))
                  class-dirs)
        docs (filterv some? docs)
        
        ;; 差分同期
        new-ids (set (map :xt/id docs))
        old-ids (->> (xt/q node '(from :gen-tests [{:xt/id id :gta/trial t}]))
                     (filter #(= (:t %) trial))
                     (map :id)
                     set)
        del-txs (mapv (fn [id] [:delete-docs :gen-tests id]) (set/difference old-ids new-ids))]
    
    (when (seq del-txs) (xt/execute-tx node del-txs))
    (when (seq docs)
      (xt/execute-tx node (mapv (fn [doc] [:put-docs :gen-tests doc]) docs)))
    
    {:analyzed (count docs)
     :deleted (count del-txs)
     :results docs}))
