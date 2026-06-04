# core.clj 改善検討メモ

## 背景

`core.clj` は REPL および AI Agent から利用するための統合エントリポイントとして機能している。

そのため関数数が多いこと自体は問題ではない。

今回の目的は、単純な API 入口を削減することではなく、`core.clj` に入り込んでいる実装ロジックを整理し、責務を明確にすることである。

---

# 基本方針

## 残すもの

以下は「入口 API」として自然であり、無理に移動する必要はない。

- start!
- stop!
- node
- ingest!
- xref!
- jref!
- jsig!
- tref!
- jbody!
- sqlref!
- jacoco!
- q
- tree
- tree-str

特に

```clojure
(defn node []
  (or @state
      (throw ...)))
```

は XTDB Node 取得のためのアクセサであり、現状維持で問題ない。

また

```clojure
(start!)
(stop!)
(node)
```

の利用感は良好であり、現時点でリファクタリング対象としない。

※ atom を利用したライフサイクル管理の是非は今回の議論対象外。

---

# コメントブロック構成の評価

現在の

```clojure
;; node lifecycle
;; ingest
;; query
;; visualize
```

といった区分は妥当。

読者に対する機能マップとして機能している。

一方で以下は実質的に独立サブシステムとなっている。

```clojure
;; AI テスト生成支援
;; テスト修正支援
;; 一括テスト生成支援
;; md → java 統合変換
;; surefire 失敗テスト処理
;; バッチ増幅処理
;; @Disabled 再生成
```

コメント区分がそのまま namespace 分割候補になっている。

---

# 優先的に切り出す対象

## 1. AIテスト生成系

対象例

- test-context
- gen-test
- gen-tests-uncovered
- uncovered-sql-methods
- test-targets

候補 namespace

```text
workbench.testgen
```

理由

- プロンプト構築
- コンテキスト生成
- AI 呼び出し

が混在しているため。

---

## 2. AIテスト修正系

対象例

- fix-test
- fix-tests-dir
- fix-method-single
- compile-errors
- split-test-file
- errors-for-method
- filter-header-imports

候補 namespace

```text
workbench.testfix
```

理由

- コンパイルエラー解析
- ソース分割
- AI 修正処理

を内包しており独立性が高い。

---

## 3. テスト運用・増幅系

対象例

- amplify-class!
- amplify-all!
- regen-disabled!
- regen-disabled-all!
- disable-failing-tests
- patch-test-from-mds
- merge-test-mds
- merge-all-test-mds

候補 namespace

```text
workbench.testops
```

理由

独立したテスト生成後ワークフローになっているため。

---

# 次点候補

## SQL影響分析

対象例

- sql-impact
- sql-impact-report
- sql-impact-report-multi
- sql-cochange-check
- classify-layer

候補

```text
workbench.analysis.sql
```

---

## JaCoCo実行補助

対象例

- ingest-jacoco!

候補

```text
workbench.jacoco
workbench.maven
```

理由

Maven実行やXML探索などが含まれているため。

---

## レポート系

対象例

- disabled-report
- coverage

候補

```text
workbench.report
```

---

# 目標構成案

```text
workbench.core              ; 公開APIのみ

workbench.runtime           ; start!/stop!/node

workbench.analysis.refs     ; xref/jref/deps/impact
workbench.analysis.sql      ; sql-impact系

workbench.report            ; coverage/report

workbench.testgen           ; テスト生成
workbench.testfix           ; テスト修正
workbench.testops           ; 増幅/再生成/統合

workbench.jacoco            ; jacoco補助
workbench.maven             ; maven補助
```

---

# 結論

今回の目的は

「core.clj を小さくすること」

ではなく

「core.clj を公開 API の目次として維持すること」

である。

`start!` / `stop!` / `node` のような薄い入口は残し、

AIテスト生成・修正・増幅ワークフローのような実装ロジックを段階的に分離する方針が望ましい。
