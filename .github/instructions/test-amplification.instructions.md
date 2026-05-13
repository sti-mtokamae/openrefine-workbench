---
description: "Use when fixing or placing generated test skeletons or running mvn test. Covers the full flow from .md skeleton to passing Maven tests."
applyTo: "trials/experiments/**/src/test/**/*.java"
---

# テストスケルトン修正・mvn test パスまでの手順

`gen-test` / `gen-tests-uncovered` が出力した `.md` を Maven テストとして動作させる作業手順。

## 役割分担

| 作業 | AI | 人間 |
|---|---|---|
| パッケージ宣言の修正 | ✅ 実ソース参照 | |
| `import` 文の補完 | ✅ 実ソース参照 | |
| Mock の型・Mapper 戻り値型の修正 | ✅ 実 Mapper インターフェース照合 | |
| `UnnecessaryStubbingException` の修正 | ✅ エラー行のスタブを削除 | |
| `expected` 値の決定 | | ✅ 仕様確認（AI はコメントで候補を提示するが値の確定は人間が行う） |
| `private` メソッドのテスト方針 | | ✅ 設計判断（AI は public メソッド経由の間接テストを提案するが採否は人間が決める） |

## 修正手順

**概要**:

1. 手順 1〜3 でファイルを整えてから手順 4 で `mvn test` を実行する。
2. エラーが出たら「典型エラーと対処」を参照して原因を特定し修正する。
3. 修正後に再実行する。最大 3 回まで試行する。
4. 3 回すべて失敗した場合は作業を中止し、エラーメッセージ・試行内容・進捗状況をユーザーに報告する。

### 1. パッケージ宣言
テスト対象クラスと **同じパッケージ** にする（`@InjectMocks` でパッケージプライベートへのアクセスが効くため）。

```java
// 修正前（生成コードの仮置き）
package your.package;

// 修正後（実ソースのパッケージに合わせる）
package com.tradehub.web.subtotal.service.impl;
```

### 2. Mapper 戻り値型の照合
Mapper インターフェースの実際の戻り値型を確認して修正する。

```java
// AI の推測（よくある不一致）
when(mapper.findAll()).thenReturn(List.of(Map.of("key", "val")));

// 実際（実 Mapper を参照して修正）
when(mapper.findAll()).thenReturn(List.of(new DocumentAggregateEntity()));
```

### 3. 配置先
テスト対象と同じパッケージパスの `src/test/java/` 以下に置く。

### 4. mvn test 実行
```bash
MAVEN_OPTS="--add-opens java.base/java.lang=ALL-UNNAMED" \
  guix shell 'openjdk@21:jdk' maven -- \
  mvn test -pl <module> -Dtest=<TestClassName>
```

エラーがあれば修正して再実行する（最大 3 回まで試行し、それでも解消しない場合はエラーメッセージと該当箇所をユーザーに報告して作業を止める）。  
外部接続・環境固有のエラー（DB 接続失敗等）はユニットテストの範囲外のため、発生した場合も同様にユーザーへ報告する。  
`expected` が `/* expected */` のままのテストは残したままで OK。

## 典型エラーと対処

### UnnecessaryStubbingException
早期リターンするテストで呼ばれないスタブが残っている。エラー行のスタブ行を削除する。

```java
// 削除対象（このテストでは早期リターンするため呼ばれない）
when(doc.getProjectId()).thenReturn(projectId);
```

### InaccessibleObjectException（cglib × Java 21）
```bash
export MAVEN_OPTS="--add-opens java.base/java.lang=ALL-UNNAMED"
```

### javac: command not found（guix の JRE のみ問題）
```bash
# NG
guix shell openjdk@21 maven
# OK（JDK 込み）
guix shell 'openjdk@21:jdk' maven
```

## ファイルの置き場

| パス | 内容 |
|---|---|
| `exports/gen-tests/<クラス名>/<メソッド名>.md` | AI 生成物のアーカイブ（素材・正本） |
| `repo/.../src/test/java/.../<クラス名>Test.java` | Maven が実行する修正済みコード |
