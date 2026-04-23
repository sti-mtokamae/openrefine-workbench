(ns workbench.query
  "XTDB v2 へのクエリを送る薄いラッパー。
   SQL と XTQL の両方を受け付ける。"
  (:require
   [xtdb.api :as xt]))

;; -------------------------
;; public API
;; -------------------------

(defn q
  "node に SQL 文字列またはベクタ形式の XTQL を送り、結果を返す。

   SQL 例:
     (query/q node \"SELECT path, ext FROM files WHERE dir = false ORDER BY path\")

   XTQL 例（将来）:
     (query/q node '(from :files [{:xt/id id} path ext]))"
  [node sql-or-xtql]
  (xt/q node sql-or-xtql))
