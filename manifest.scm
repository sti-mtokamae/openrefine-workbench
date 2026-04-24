;; manifest.scm - Guix environment definition for openrefine-workbench
;; Usage: guix shell -m manifest.scm
;;
;; Provides reproducible development environment for XTDB v2 + ingest/query/visualize loop

(specifications->manifest
 '(;; Clojure + Java environment for XTDB v2
   "openjdk@21"              ; Java 21 LTS
   "clojure-tools"           ; Clojure CLI for REPL and deps.edn
   "rlwrap"                  ; Readline wrapper for REPL

   ;; Development tools
   "git"                     ; Version control
   ))
