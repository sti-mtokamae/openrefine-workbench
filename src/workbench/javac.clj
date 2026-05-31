
;; --------------------------------------------------
;; DiagnosticCollectorでJavaファイルをコンパイルし、エラー情報をJSON形式で返すPoC
;; --------------------------------------------------

(ns workbench.javac
  "DiagnosticCollectorでJavaファイルをコンパイルし、エラー情報をJSON形式で返すPoC"
  (:import
    [javax.tools ToolProvider DiagnosticCollector JavaFileObject SimpleJavaFileObject JavaFileObject$Kind]
    [java.net URI]
    [java.nio.file Files Paths]))

;; Javaファイルパス→JavaFileObject変換
(defn- file->javafileobject [^String path]
  (let [p (Paths/get path (make-array String 0))
        uri (.toUri p)]
    (proxy [SimpleJavaFileObject] [uri JavaFileObject$Kind/SOURCE]
      (getCharContent [_] (String. (Files/readAllBytes p))))))

;; DiagnosticCollectorでコンパイルし、エラー情報をマップで返す
(defn compile-with-diagnostics
  "指定JavaファイルをDiagnosticCollector付きでコンパイルし、エラー情報をマップで返す"
  [^String java-file-path]
  (let [compiler (ToolProvider/getSystemJavaCompiler)
        diagnostics (DiagnosticCollector.)
        file-obj (file->javafileobject java-file-path)
        task (.getTask compiler nil nil diagnostics nil nil [file-obj])]
    (.call task)
    (map (fn [diag]
           {:kind (str (.getKind diag))
            :msg (.getMessage diag nil)
            :line (.getLineNumber diag)
            :col (.getColumnNumber diag)
            :file (str (.getSource diag))})
         (.getDiagnostics diagnostics))))

;; DiagnosticCollectorのエラー情報をXTDBに格納する
(defn compile-errors-to-xtdb!
  "指定Javaファイルをコンパイルし、エラー情報をXTDBノードに格納する。
  :node にはxtdbノード、:java-file-path にはファイルパスを指定。"
  [node java-file-path]
  (let [errors (compile-with-diagnostics java-file-path)
        doc {:xt/id java-file-path
             :java/compile-errors errors
             :file/path java-file-path
             :java/compile-error? (not (empty? errors))}]
    (xtdb.api/submit-tx node [[:put-docs :java-compile-errors doc]])
    doc))