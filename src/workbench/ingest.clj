(ns workbench.ingest
  "ディレクトリ・Git 履歴などを XTDB v2 に取り込む関数群。
   Human REPL と AI Agent が同じ呼び出し面を使う。"
  (:require
   [clojure.java.io :as io]
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
