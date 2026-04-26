# セットアップ

## 1. 事前要件

推奨：**Guix**（環境を完全に固定したい場合）

```bash
guix shell -m manifest.scm
```

代替（手動セットアップ）：

| ツール | 用途 | URL |
|---|---|---|
| Clojure | 言語ランタイム | https://clojure.org/guides/install_clojure |
| OpenJDK 21+ | Java ランタイム | https://openjdk.org/ |
| Babashka [任意] | JVM 起動コスト削減（ローカル開発用） | https://babashka.org/ |
| GitHub CLI [任意] | GitHub 連携 | https://cli.github.com/ |

---

## 2. orcli のダウンロード

OpenRefine 自動化に必要な `orcli` を `bin/` に配置：

```bash
cd openrefine-workbench
curl -sSfL https://raw.githubusercontent.com/opencultureconsulting/orcli/main/orcli -o bin/orcli
chmod +x bin/orcli
```

---

## 3. OpenRefine の起動（Windows 側）

### PowerShell スクリプト使用（推奨）

`bin/start-openrefine.ps1` を OpenRefine インストールフォルダにコピーして実行：

```powershell
Copy-Item bin\start-openrefine.ps1 C:\Users\mtoka\usrapp\openrefine-3.9.5\
cd C:\Users\mtoka\usrapp\openrefine-3.9.5
.\start-openrefine.ps1
```

スクリプトが自動で行うこと：
- PATH から Java を自動検出・`JAVA_HOME` を設定
- OpenRefine を `0.0.0.0:3333` で起動（WSL からアクセス可能に）
- 接続先 URL を表示

**初回実行時（ファイルロック解除）：**

```powershell
Unblock-File -Path .\start-openrefine.ps1
```

**実行ポリシーエラーが出た場合：**

```powershell
Set-ExecutionPolicy -ExecutionPolicy RemoteSigned -Scope CurrentUser
```

### 手動起動（参考）

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-25.0.1.8-hotspot"
cd C:\Users\mtoka\usrapp\openrefine-3.9.5
.\refine.bat /i 0.0.0.0 run
```

> **よくあるミス：** `openrefine.exe` ではなく `refine.bat` を使うこと。`/i 0.0.0.0` オプションは WSL 接続に必須。

---

## 4. WSL からの接続確認

```bash
# WSL のゲートウェイアドレス確認
ip route show | grep default
# 例: default via 172.27.160.1 dev eth0

# 接続テスト
curl http://172.27.160.1:3333/
```

---

## 5. XTDB ワークベンチの起動確認

```bash
# Guix 環境で nREPL 起動
guix shell -m manifest.scm -- clojure -A:xtdb:repl
```

```bash
# smoke test
guix shell -m manifest.scm -- clojure -A:xtdb -M test/smoke_test.clj trials/samples/repo
```
