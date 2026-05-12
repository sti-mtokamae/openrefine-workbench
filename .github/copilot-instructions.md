# openrefine-workbench — Copilot 常時コンテキスト

## このリポジトリの目的

JavaParser + XTDB v2 による Java コードベースの静的解析ワークベンチ。
GitHub Models API 経由で JUnit 5 + Mockito テストスケルトンを生成し、カバレッジを増幅する。

## 実行環境

- **Clojure**: `bb` (Babashka) または `clj` で `src/` を読み込む
- **JDK**: `guix shell 'openjdk@21:jdk' maven` — `openjdk@21`（JRE のみ）では `javac` がない
- **Maven テスト実行**: 必ず `MAVEN_OPTS="--add-opens java.base/java.lang=ALL-UNNAMED"` を付ける（cglib × Java 21 対策）
- **XTDB**: `.xtdb/` にローカル永続化。`core/start!` で起動

## ディレクトリ構成

| パス | 役割 |
|---|---|
| `src/workbench/` | コア実装（core.clj / jref.clj / query.clj 等） |
| `trials/experiments/<name>/` | 試行単位の作業ディレクトリ |
| `trials/experiments/<name>/repo/` | 分析対象 Java リポジトリ（clone 先） |
| `trials/experiments/<name>/exports/` | 分析結果・生成物（グラフ / テストスケルトン） |
| `trials/experiments/<name>/notes.md` | 作業ログ（非公開・私的記録） |
| `docs/` | 公開ドキュメント（api.md / codegen.md） |

## 主要 API（core.clj）

| 関数 | 用途 |
|---|---|
| `jref!` | Java 呼び出しグラフを XTDB へ投入 |
| `jsig!` | メソッドシグネチャを XTDB へ投入 |
| `sqlref!` | MyBatis SQL アノテーションを XTDB へ投入 |
| `jacoco!` | JaCoCo XML を XTDB へ投入 |
| `test-context` | AI プロンプト用コンテキストを統合して返す |
| `gen-test` | テストスケルトンを生成して文字列で返す |
| `uncovered-sql-methods` | 未カバー × SQL 縛りのメソッド一覧 |
| `gen-tests-uncovered` | 一括生成して exports/ へ書き出す |

## 注意事項

- `notes.md` はプライベートな作業ログ。コミット対象だが公開ドキュメントではない
- `exports/gen-tests/` は AI 生成物のアーカイブ（正本）
- データ投入関数（`jref!` `jsig!` 等）はすべて冪等・差分同期。2 回実行しても壊れない
