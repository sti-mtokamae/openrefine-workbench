(ns workbench.query
  "XTDB v2 へのクエリを送る薄いラッパー。
   SQL と XTQL の両方を受け付ける。
   注意: SQL では :file/dir? のような ? 付き列名を返せないため、
   全フィールドを取得する場合は XTQL (from :files [*]) を使うこと。"
  (:require
   [xtdb.api :as xt]))

;; -------------------------
;; public API
;; -------------------------

(defn q
  "node に SQL 文字列またはシンボル形式の XTQL を送り、結果を返す。

   全フィールド取得（XTQL 推奨）:
     (query/q node '(from :files [*]))

   SQL 例（プレーンな列名のみ使用可）:
     (query/q node \"SELECT _id, path FROM files ORDER BY path\")"
  [node sql-or-xtql]
  (xt/q node sql-or-xtql))
