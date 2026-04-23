(ns workbench.ingest
  "ディレクトリ・Git 履歴などを XTDB v2 に取り込む関数群。
   Human REPL と AI Agent が同じ呼び出し面を使う。"
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
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
