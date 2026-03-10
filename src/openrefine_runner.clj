(ns openrefine-runner
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str])
  (:import
   [java.net URI URLEncoder]
   [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
   [java.nio.charset StandardCharsets]
   [java.nio.file Files]
   [java.time Duration]
   [java.util UUID]))

;; -------------------------
;; basic io
;; -------------------------

(defn read-trial [path]
  (-> path slurp edn/read-string))

(defn trial-dir [trial-file]
  (.getParentFile (io/file trial-file)))

(defn resolve-path [base-dir path]
  (let [f (io/file path)]
    (if (.isAbsolute f)
      f
      (io/file base-dir path))))

(defn ensure-file-exists! [path]
  (when-not (.exists (io/file path))
    (throw (ex-info "missing file" {:file path})))
  path)

;; -------------------------
;; trial accessors
;; new keys first, old keys fallback
;; -------------------------

(defn trial-id [trial]
  (:trial/id trial))

(defn trial-tool [trial]
  (:trial/tool trial))

(defn goal [trial]
  (:goal trial))

(defn openrefine-url [trial]
  (:openrefine/url trial))

(defn project-name [trial]
  (or (:openrefine/project-name trial)
      (:project/name trial)))

(defn input-files [trial]
  (:input/files trial))

(defn seed-files [trial]
  (or (:seed/files trial)
      (some-> (:history/seed trial) vector)))

(defn output-dir [trial]
  (or (:output/dir trial)
      (:export/dir trial)))

(defn notes-file [trial]
  (:notes/file trial))

(defn open-browser? [trial]
  (or (:openrefine/open-browser? trial)
      (:runner/open-browser? trial)))

(defn import-format [trial]
  (or (:openrefine/import-format trial)
      "text/line-based"))

(defn export-format [trial]
  (or (:openrefine/export-format trial)
      "xlsx"))

;; -------------------------
;; export format mapping
;; -------------------------

(defn format-to-openrefine-format [fmt]
  "Convert user-friendly format name to OpenRefine export format parameter"
  (case fmt
    "xlsx" "xlsx"
    "xls"  "xls"
    "ods"  "ods"
    "csv"  "csv"
    "tsv"  "tsv"
    "html" "html"
    "json" "json"
    fmt))  ; default: pass through as-is

(defn format-to-file-extension [fmt]
  "Convert format name to file extension"
  (case fmt
    "xlsx" "xlsx"
    "xls"  "xls"
    "ods"  "ods"
    "csv"  "csv"
    "tsv"  "tsv"
    "html" "html"
    "json" "json"
    "jsonl" "jsonl"
    fmt))  ; default: pass through as-is

;; -------------------------
;; http helpers
;; -------------------------

(defn http-client []
  (-> (HttpClient/newBuilder)
      (.connectTimeout (Duration/ofSeconds 10))
      .build))

;; -------------------------
;; debug support
;; -------------------------

(def ^:dynamic *debug* true)

(defn debug [& xs]
  (when *debug*
    (apply println "[DEBUG]" xs)))

(defn http-get-text [url]
  (slurp url))

(defn reachable? [base-url]
  (try
    (let [s (http-get-text (str (str/replace base-url #"/$" "")
                                "/command/core/get-all-project-metadata"))]
      (string? s))
    (catch Exception _
      false)))

(defn extract-csrf-token [html]
  ;; OpenRefine page HTML から csrf token を拾う雑な方法
  ;; 必要になれば後で改善
  (or
   (some->> (re-find #"(?s)name=\"csrf_token\"\s+value=\"([^\"]+)\"" html)
            second)
   (some->> (re-find #"(?s)csrf_token=([A-Za-z0-9._-]+)" html)
            second)))

(defn fetch-csrf-token [base-url]
  (try
    ;; 方法1: API から直接取得（OpenRefine 3.9.5+）
    (let [response (http-get-text (str (str/replace base-url #"/$" "") "/command/core/get-csrf-token"))
          ;; 応答が JSON: {"token":"..."}
          csrf (some->> (re-find #"\"token\"\s*:\s*\"([^\"]+)\"" response)
                        second)]
      (if csrf
        csrf
        (throw (ex-info "csrf token not found in API response" {:response response}))))
    (catch Exception _
      ;; フォールバック: HTML ページから抽出
      (try
        (let [html (http-get-text (str (str/replace base-url #"/$" "") "/"))]
          (or (extract-csrf-token html)
              (throw (ex-info "csrf token not found" {:url base-url}))))
        (catch Exception e2
          ;; ログして再スロー
          (println "[ERROR] Failed to get CSRF token:" (.getMessage e2))
          (throw e2))))))

;; -------------------------
;; multipart body
;; -------------------------

(defn utf8-bytes [s]
  (.getBytes (str s) StandardCharsets/UTF_8))

(defn random-boundary []
  (str "----openrefine-runner-" (UUID/randomUUID)))

(defn multipart-body
  [{:keys [boundary project-name file-paths format options]}]
  (let [file-paths (if (string? file-paths) [file-paths] file-paths)
        parts (concat
               ;; project-name フィールド
               [(utf8-bytes
                 (str "--" boundary "\r\n"
                      "Content-Disposition: form-data; name=\"project-name\"\r\n\r\n"
                      project-name "\r\n"))]

               ;; format フィールド (オプション)
               (when format
                 [(utf8-bytes
                   (str "--" boundary "\r\n"
                        "Content-Disposition: form-data; name=\"format\"\r\n\r\n"
                        format "\r\n"))])

               ;; options フィールド (オプション)
               (when options
                 [(utf8-bytes
                   (str "--" boundary "\r\n"
                        "Content-Disposition: form-data; name=\"options\"\r\n\r\n"
                        options "\r\n"))])

               ;; 各ファイルを project-file として追加
               (mapcat (fn [file-path]
                         (let [file (io/file file-path)
                               filename (.getName file)
                               file-bytes (Files/readAllBytes (.toPath file))]
                           [(utf8-bytes
                             (str "--" boundary "\r\n"
                                  "Content-Disposition: form-data; name=\"project-file\"; filename=\"" filename "\"\r\n"
                                  "Content-Type: text/plain\r\n\r\n"))
                            file-bytes
                            (utf8-bytes "\r\n")]))
                       file-paths)

               ;; 終端 boundary
               [(utf8-bytes (str "--" boundary "--\r\n"))])]
    (remove nil? parts)))

;; -------------------------
;; openrefine operations
;; -------------------------

(defn create-project!
  [{:keys [base-url project-name file-paths format options csrf-token]}]
  (let [boundary (random-boundary)
        client   (http-client)
        url (str (str/replace base-url #"/$" "")
                 "/command/core/create-project-from-upload?csrf_token=" csrf-token)
        req      (-> (HttpRequest/newBuilder
                      (URI/create url))
                     (.timeout (Duration/ofMinutes 2))
                     (.header "Content-Type"
                              (str "multipart/form-data; boundary=" boundary))
                     (.POST
                      (HttpRequest$BodyPublishers/ofByteArrays
                       (multipart-body {:boundary boundary
                                        :project-name project-name
                                        :file-paths file-paths
                                        :format format
                                        :options options})))
                     .build)
        resp     (.send client req (HttpResponse$BodyHandlers/ofString))
        status   (.statusCode resp)
        body     (.body resp)
        location (some-> (.headers resp)
                         (.firstValue "location")
                         (.orElse nil))]
    (when-not (<= 200 status 399)
      (throw (ex-info "failed to create project"
                      {:status status
                       :body body})))
    (or (some->> location
                 (re-find #"project=([0-9]+)")
                 second)
        (some->> body
                 (re-find #"project=([0-9]+)")
                 second)
        (throw (ex-info "project id not found in response"
                        {:status status
                         :body body
                         :location location})))))

(defn open-browser! [url]
  (try
    (let [pb (ProcessBuilder. ["wslview" url])
          process (.start pb)
          exit-code (.waitFor process)]
      (if (zero? exit-code)
        (debug "Browser opened successfully")
        (println "[WARNING] wslview exited with code:" exit-code)))
    (catch Exception e
      (println "[WARNING] Failed to open browser:" (.getMessage e))
      (println "[INFO] Open manually:" url))))

;; -------------------------
;; orcli operations (Phase 2)
;; -------------------------

(defn apply-operations!
  [{:keys [openrefine-url project-id seed-file]}]
  (try
    (let [pb (ProcessBuilder. ["./bin/orcli" "transform" (str project-id) seed-file])
          env (.environment pb)]
      (.put env "OPENREFINE_URL" openrefine-url)
      (let [process (.start pb)
            exit-code (.waitFor process)
            stderr (slurp (.getErrorStream process))]
        (println "[DEBUG] orcli transform stderr:" stderr)
        (if (zero? exit-code)
          {:success? true}
          (throw (ex-info "orcli transform failed" {:stderr stderr})))))
    (catch Exception e
      (println "[ERROR] Exception in apply-operations!:" (.getMessage e))
      (throw (ex-info "Failed to apply operations" {:error (.getMessage e)})))))

(defn export-results!
  [{:keys [openrefine-url project-id output-file format]}]
  (try
    (let [fmt (format-to-openrefine-format (or format "xlsx"))
          ;; OpenRefine API を直接呼び出す: export-rows エンドポイント
          ;; xlsx フォーマットではトリムされない（ユーザーの発見）
          base-url (str/replace openrefine-url #"/$" "")
          csrf-token (fetch-csrf-token base-url)
          
          ;; フォーマットによってオプションを変更
          options-json (case fmt
                         ("csv" "tsv") 
                         (let [separator (if (= fmt "csv") "," "\t")]
                           ;; CSV/TSV はトリムされるが、オプションを試す
                           (str "{\"separator\":\"" separator "\",\"trimStrings\":false}"))
                         
                         ("xlsx" "xls" "ods" "html" "json")
                         ;; Excel/ODS/HTML/JSON はオプション不要またはシンプル
                         "{}")
          
          ;; POST データの構築（CSRF token を含める）
          post-data (str "project=" project-id
                        "&format=" fmt
                        "&options=" (URLEncoder/encode options-json "UTF-8")
                        "&csrf_token=" csrf-token)
          
          client (http-client)
          ;; バイナリレスポンスハンドラーを使用（xlsx/xls は binary）
          req (-> (HttpRequest/newBuilder (URI/create (str base-url "/command/core/export-rows")))
                  (.timeout (Duration/ofMinutes 2))
                  (.header "Content-Type" "application/x-www-form-urlencoded")
                  (.POST (HttpRequest$BodyPublishers/ofString post-data))
                  .build)
          resp (.send client req (HttpResponse$BodyHandlers/ofByteArray))
          status (.statusCode resp)
          body-bytes (.body resp)]
      
      (when-not (<= 200 status 399)
        (throw (ex-info "export-rows API failed"
                        {:status status :body (String. body-bytes)})))
      
      ;; バイナリデータとしてファイルに保存
      (when-not (.exists (io/file output-file))
        (io/make-parents output-file))
      (with-open [w (io/output-stream output-file)]
        (.write w body-bytes))
      
      {:success? true :output-file output-file})
    (catch Exception e
      (throw (ex-info "Failed to export results" {:error (.getMessage e)})))))

;; -------------------------
;; validation / normalize
;; -------------------------

(defn normalize-trial [trial-file]
  (let [trial         (read-trial trial-file)
        base-dir      (trial-dir trial-file)
        files         (mapv #(.getPath (resolve-path base-dir %))
                            (input-files trial))
        seeds         (mapv #(.getPath (resolve-path base-dir %))
                            (or (seed-files trial) []))
        out-dir       (some-> (output-dir trial)
                              (#(.getPath (resolve-path base-dir %))))
        notes         (some-> (notes-file trial)
                              (#(.getPath (resolve-path base-dir %))))]
    {:trial/id                (trial-id trial)
     :trial/tool              (trial-tool trial)
     :goal                    (goal trial)

     :openrefine/url          (openrefine-url trial)
     :openrefine/project-name (project-name trial)
     :openrefine/open-browser? (boolean (open-browser? trial))
     :openrefine/import-format (import-format trial)
     :openrefine/export-format (export-format trial)

     :input/files             files
     :seed/files              seeds
     :output/dir              out-dir
     :notes/file              notes

     :raw                     trial}))

(defn validate-trial! [trial]
  (when-not (:trial/id trial)
    (throw (ex-info "missing :trial/id" {})))

  (when-not (:openrefine/url trial)
    (throw (ex-info "missing OpenRefine URL" {})))

  (when-not (:openrefine/project-name trial)
    (throw (ex-info "missing project name" {})))

  (when-not (seq (:input/files trial))
    (throw (ex-info "missing input files" {})))

  ;; 今は先頭1ファイルだけ使うが、存在確認は全部しておく
  (doseq [f (:input/files trial)]
    (ensure-file-exists! f))

  (doseq [f (:seed/files trial)]
    (ensure-file-exists! f))

  true)

;; -------------------------
;; main workflow
;; -------------------------

(defn run-trial [trial-file]
  (let [trial      (normalize-trial trial-file)
        base-url   (:openrefine/url trial)
        project    (:openrefine/project-name trial)
        input-files (:input/files trial)
        seed-files (:seed/files trial)
        output-dir (:output/dir trial)
        ok?        (reachable? base-url)]

    (validate-trial! trial)

    (println "trial:" (:trial/id trial))
    (when-let [tool (:trial/tool trial)]
      (println "tool:" tool))
    (when-let [g (:goal trial)]
      (println "goal:" g))
    (println "project:" project)
    (println "openrefine:" base-url)
    (when (seq input-files)
      (println "input-files:")
      (doseq [f input-files]
        (println " -" f)))

    (when-let [notes (:notes/file trial)]
      (println "notes:" notes))
    (when-let [out output-dir]
      (println "output-dir:" out))
    (when (seq seed-files)
      (println "seed-files:")
      (doseq [f seed-files]
        (println " -" f)))

    (if ok?
      (try
        ;; Phase 1: Create project
        (let [csrf-token (fetch-csrf-token base-url)
              ;; Import オプション: 複数ファイルの場合はファイル名を最初のカラムに追加
              import-options (if (> (count (:input/files trial)) 1)
                               "{\"trimStrings\": false, \"includeFileSources\": true}"
                               "{\"trimStrings\": false}")
              project-id (create-project!
                          {:base-url base-url
                           :project-name project
                           :file-paths (:input/files trial)
                           :format (:openrefine/import-format trial)
                           :options import-options
                           :csrf-token csrf-token})
              project-url (str (str/replace base-url #"/$" "")
                               "/project?project=" project-id)]

          (println)
          (println "Phase 1: Project created")
          (println "project-id:" project-id)
          (println "project-url:" project-url)

          (when (:openrefine/open-browser? trial)
            (println "opening browser via wslview ...")
            (open-browser! project-url))

          ;; Phase 2: Apply operations
          (when (seq seed-files)
            (println)
            (println "Phase 2: Applying operations...")
            (doseq [seed-file seed-files]
              (println " applying:" seed-file)
              (apply-operations! {:openrefine-url base-url
                                 :project-id project-id
                                 :seed-file seed-file})))

          ;; Phase 3: Export results
          (when output-dir
            (println)
            (println "Phase 3: Exporting results...")
            ;; 出力ディレクトリを確認・作成
            (let [out-path (io/file output-dir)
                  fmt (:openrefine/export-format trial)
                  ext (format-to-file-extension fmt)]
              (when-not (.exists out-path)
                (.mkdirs out-path))
              (let [output-file (io/file out-path (str project "." ext))]
                (println " exporting to:" (.getPath output-file))
                (export-results! {:openrefine-url base-url
                                 :project-id project-id
                                 :output-file (.getPath output-file)
                                 :format fmt}))))

          (println)
          (println "✓ Trial completed successfully"))

        (catch Exception e
          (println)
          (println "[ERROR]" (.getMessage e))
          (throw e)))

      (do
        (println)
        (println "[WARNING] OpenRefine is not reachable at" base-url)
        (println "[INFO] Please start openrefine.exe on Windows and retry.")))

    :ok))

(defn -main [& args]
  (let [trial-file (first args)]
    (when-not trial-file
      (binding [*out* *err*]
        (println "usage: clojure -M -m openrefine-runner path/to/trial.edn"))
      (System/exit 1))
    (run-trial trial-file)))