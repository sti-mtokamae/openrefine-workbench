(ns workbench.core
  "REPL / AI Agent 向けの統合エントリポイント。
   node のライフサイクルを管理し、ingest / query / visualize を一枚の操作面として提供する。

   基本的な使い方:
     (start!)
     (ingest! \"trials/samples/repo\")
     (xref! [\"src\"])
     (tree)
     (q '(from :files [*]))
     (stop!)"
  (:require
   [workbench.ingest    :as ingest]
   [workbench.query     :as query]
   [workbench.visualize :as visualize]
   [xtdb.node           :as xtn]))

;; -------------------------
;; node lifecycle
;; -------------------------

(defonce ^:private state (atom nil))

(defn start!
  "XTDB ノードを起動する。すでに起動済みなら何もしない。"
  []
  (when-not @state
    (reset! state (xtn/start-node {})))
  :started)

(defn stop!
  "XTDB ノードを停止する。"
  []
  (when-let [node @state]
    (.close node)
    (reset! state nil))
  :stopped)

(defn node
  "現在の XTDB ノードを返す。未起動なら start! を呼ぶこと。"
  []
  (or @state (throw (ex-info "node not started — call (start!) first" {}))))

;; -------------------------
;; ingest
;; -------------------------

(defn ingest!
  "root 以下のファイル・ディレクトリを XTDB :files テーブルに取り込む。

   例:
     (ingest! \"trials/samples/repo\")"
  [root]
  (ingest/dir! (node) root))

(defn xref!
  "Clojure ソースを cross-reference 解析して XTDB :refs テーブルに取り込む。

   例:
     (xref! [\"src\"])
     (xref! [\"src\" \"test\"])"
  [paths]
  (ingest/xref! (node) paths))

;; -------------------------
;; query
;; -------------------------

(defn q
  "XTQL または SQL でクエリを送る。

   例:
     (q '(from :files [*]))
     (q '(from :refs [*]))"
  [query]
  (query/q (node) query))

;; -------------------------
;; visualize
;; -------------------------

(defn tree
  "files テーブルの全内容をツリー表示する（stdout）。"
  []
  (visualize/tree (q '(from :files [*]))))

(defn tree-str
  "tree と同じだが文字列として返す（AI Agent / テスト向け）。"
  []
  (visualize/tree-str (q '(from :files [*]))))