(ns workbench.codegen
  "GitHub Models API (models.github.ai) を使ったテストコード生成支援。

   認証:
     環境変数 GITHUB_TOKEN があればそれを使う。
     なければ `gh auth token` コマンドで取得する。

   エンドポイント:
     POST https://models.github.ai/inference/chat/completions
     OpenAI chat completions 互換フォーマット。

   使い方:
     (chat-complete [{:role \"user\" :content \"Hello\"}])
     (chat-complete [{:role \"user\" :content \"...\"}] :model \"openai/gpt-4.1-mini\")"
  (:require
   [cheshire.core  :as json]
   [clojure.string :as str])
  (:import
   [java.net URI]
   [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
   [java.time Duration]))

;; -------------------------
;; 設定
;; -------------------------

(def ^:private endpoint
  "https://models.github.ai/inference/chat/completions")

;; -------------------------
;; 認証トークン取得
;; -------------------------

(defn- gh-token
  "GitHub 認証トークンを返す。
   GITHUB_TOKEN 環境変数を優先し、なければ `gh auth token` で取得する。"
  []
  (or (System/getenv "GITHUB_TOKEN")
      (let [proc (-> (ProcessBuilder. ^java.util.List ["gh" "auth" "token"])
                     (.redirectErrorStream true)
                     .start)]
        (with-open [r (-> proc .getInputStream java.io.InputStreamReader. java.io.BufferedReader.)]
          (str/trim (slurp r))))))

;; -------------------------
;; HTTP クライアント（シングルトン・遅延初期化）
;; -------------------------

(def ^:private http-client
  (delay
    (-> (HttpClient/newBuilder)
        (.connectTimeout (Duration/ofSeconds 30))
        .build)))

;; -------------------------
;; API 呼び出し
;; -------------------------

(defn chat-complete
  "GitHub Models API に chat-completion リクエストを送り、
   アシスタントの応答テキスト（文字列）を返す。

   messages: [{:role \"system\" :content \"...\"} {:role \"user\" :content \"...\"}]
   opts:
     :model       — モデルID (default: \"openai/gpt-4.1\")
     :max-tokens  — 最大トークン数 (default: 2048)
     :temperature — 温度 (default: 0.2)

   例:
     (chat-complete [{:role \"user\" :content \"What is JUnit 5?\"}])
     (chat-complete [{:role \"user\" :content \"...\"}] :model \"openai/gpt-4.1-mini\")"
  [messages & {:keys [model max-tokens temperature]
               :or   {model       "openai/gpt-4.1"
                      max-tokens  2048
                      temperature 0.2}}]
  (let [body   (json/generate-string
                 {:model       model
                  :messages    messages
                  :max_tokens  max-tokens
                  :temperature temperature})
        req    (-> (HttpRequest/newBuilder)
                   (.uri (URI/create endpoint))
                   (.timeout (Duration/ofSeconds 120))
                   (.header "Authorization" (str "Bearer " (gh-token)))
                   (.header "Content-Type"  "application/json")
                   (.header "Accept"        "application/vnd.github+json")
                   (.header "X-GitHub-Api-Version" "2026-03-10")
                   (.POST (HttpRequest$BodyPublishers/ofString body))
                   .build)
        resp   (.send @http-client req (HttpResponse$BodyHandlers/ofString))
        code   (.statusCode resp)
        result (json/parse-string (.body resp) true)]
    (if (= 200 code)
      (-> result :choices first :message :content)
      (throw (ex-info "GitHub Models API error"
                      {:status code :body result})))))