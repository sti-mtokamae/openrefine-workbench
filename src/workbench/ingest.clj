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
        put-txs (mapv (fn [r] [:put-docs :refs (ref->doc r trial)]) refs)]
    (when (seq del-txs) (xt/execute-tx node del-txs))
    (when (seq put-txs) (xt/execute-tx node put-txs))
    (count put-txs)))
