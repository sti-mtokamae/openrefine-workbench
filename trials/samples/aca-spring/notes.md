# aca-spring

## goal

`aca-learning/spring-hello` の Java ソース（Spring Boot + JWT 認証）を `jref!` で解析し、
Controller / Service / Security 層の呼び出しグラフを確認する。

ソース: https://github.com/sti-mtokamae/aca-learning/tree/main/spring-hello

## 構成

```
src/main/java/com/example/acahello/
  AcaHelloApplication.java      # エントリポイント
  config/
    SecurityConfig.java         # Spring Security 設定
  controller/
    AuthController.java         # ログイン API (/api/auth/login)
    HelloController.java        # Hello API (/api/hello)
  security/
    JwtAuthenticationFilter.java # JWT 認証フィルタ
    JwtProvider.java            # JWT 発行・検証
  service/
    ClojureHelloService.java    # Java → Clojure 呼び出しブリッジ
```

## 実行コマンド

```clojure
(core/start!)
(core/ingest! "trials/samples/aca-spring/src/main/java")
(core/jref!   ["trials/samples/aca-spring/src/main/java"])
(let [refs (core/q '(from :refs [{:ref/from from :ref/to to}]))]
  (core/call-tree refs "HelloController/hello")
  (core/call-tree refs "AuthController/login")
  (core/call-tree refs "JwtAuthenticationFilter/doFilterInternal"))
(core/stop!)
```

## observation

### jref! 件数

68件（method chaining が中間 chain ごとに 1 エントリとなるため多め）

### call-tree: HelloController/hello

```
HelloController/hello
  clojureHelloService.hello     ← Java→Clojure ブリッジ呼び出し（葉で止まる）
```

`xref!` を組み合わせると Clojure 側（`hello-payload`）まで追える。

### call-tree: JwtAuthenticationFilter/doFilterInternal

```
JwtAuthenticationFilter/doFilterInternal
  List.of
  SecurityContextHolder.getContext
  SecurityContextHolder.getContext().setAuthentication
  authHeader.startsWith
  authHeader.substring
  filterChain.doFilter
  jwtProvider.extractUsername   ← @Autowired フィールド経由でも取れる
  jwtProvider.isTokenValid      ← 同上
  request.getHeader
```

`@Autowired` の注入自体は取れないが、フィールド経由の**明示的メソッド呼び出しは取れる**。

### call-tree: AuthController/login

```
AuthController/login
  Map.of
  Map.of [...]
  ResponseEntity.ok
  ResponseEntity.status
  ResponseEntity.status(HttpStatus.UNAUTHORIZED).body
  credentials.get
  credentials.get [...]
  jwtProvider.generateToken
  passwordEncoder.matches
  userDetails.getPassword
  userDetailsService.loadUserByUsername
```

fanout 11。ビジネスロジックを直接担っており、認証の主要フローを 1 関数で処理している。

### ファンイン TOP

| to | fanin |
|---|---|
| `Clojure.var` | 4 |
| `extractClaims` | 2 |
| `Map.of` | 2 |
| `credentials.get` | 2 |
| `clojureHelloService.hello` | 2 |

- `Clojure.var` fanin 4 → `ClojureHelloService` の static ブロックで4回のバインド
- `extractClaims` fanin 2 → `extractUsername` と `isTokenValid` から参照
- `clojureHelloService.hello` fanin 2 → `hello()` と `helloName()` から呼ばれる

## 発見・限界

### 取れるもの
- フィールド経由の明示的メソッド呼び出し（`jwtProvider.isTokenValid` 等）
- Java→Clojure ブリッジ（`clojureHelloService.hello`）の Java 側呼び出し

### 取れないもの
- `@Autowired` / DI の注入関係（`SecurityConfig → JwtAuthenticationFilter` 等）
- method chaining の最終形（`Jwts.builder()...compact()` が中間ノードごとに分解される）
- Clojure 側の実装（`ClojureHelloService` の葉で止まる）→ `xref!` を組み合わせる必要がある

### method chaining ノイズの例

`SecurityConfig/securityFilterChain` では `http.csrf(...).authorizeHttpRequests(...)...` の
各 chain 中間点が個別エントリとして68件中の多くを占める。
実質的な呼び出し件数はより少ない。
