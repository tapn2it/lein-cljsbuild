(ns leiningen.cljsbuild
  "Compile ClojureScript source into a JavaScript file."
  (:require
    [clojure.java.io :as io]
    [clojure.pprint :as pprint]
    [clojure.string :as s]
    [fs.core :as fs]
    [leiningen.clean :as lclean]
    [leiningen.compile :as lcompile]
    [leiningen.jar :as ljar]
    [leiningen.test :as ltest]
    [robert.hooke :as hooke]))

(def cljsbuild-dependencies
  '[[cljsbuild "0.1.0"]])

(def repl-output-path ".lein-cljsbuild-repl")
(def crossover-path ".lein-cljsbuild-crossover")
(def compiler-output-dir-base ".lein-cljsbuild-compiler-")

(def default-global-options
  {:repl-launch-commands {}
   :repl-listen-port 9000
   :test-commands {}
   :crossover-path "crossover-cljs"
   :crossovers []})

; TODO Add a :crossover-jar boolean option?
; TODO Write a "migrating from 0.0.x to 0.1.x" doc... :(

(def default-compiler-options
  {:output-to "main.js"
   :optimizations :whitespace
   :pretty-print true})

(def default-build-options
  {:source-path "src-cljs"
   :compiler default-compiler-options})

(def exit-success 0)
(def exit-failure 1)

(defn- printerr [& args]
  (binding [*out* *err*]
    (apply println args)))  

(defn- warn [& args]
  (apply printerr "WARNING:" args))

; TODO Dedupliclate this with the docstring below?
(defn- usage []
  (printerr "Usage: lein cljsbuild [once|auto|clean|test|repl-listen|repl-launch|repl-rhino]"))

(declare deep-merge-item)

(defn- deep-merge [& ms]
  (apply merge-with deep-merge-item ms))

(defn- deep-merge-item [a b]
  (if (and (map? a) (map? b))
    (deep-merge a b)
    b))

(defn- merge-dependencies [project-dependencies]
  (let [dependency-map #(into {} (map (juxt first rest) %))
        project (dependency-map project-dependencies)
        cljsbuild (dependency-map cljsbuild-dependencies)]
    (map (fn [[k v]] (vec (cons k v)))
      (merge project cljsbuild))))

(defn- run-local-project [project crossover-path builds requires form]
  (lcompile/eval-in-project
    {:local-repo-classpath true
     :source-path (:source-path project)
     :extra-classpath-dirs (concat
                             (:extra-classpath-dirs project)
                             (map :source-path builds)
                             [crossover-path])
     :dependencies (merge-dependencies (:dependencies project))
     :dev-dependencies (:dev-dependencies project)}
     `(do
       ~form
       (shutdown-agents))
    nil
    nil
    requires)
  exit-success)

(defn- run-compiler [project {:keys [crossover-path crossovers builds]} watch?]
  (println "Compiling ClojureScript.")
  ; If crossover-path does not exist before eval-in-project is called,
  ; the files it contains won't be classloadable, for some reason.
  (fs/mkdirs crossover-path)
  (run-local-project project crossover-path builds
    '(require 'cljsbuild.compiler 'cljsbuild.crossover 'cljsbuild.util)
    `(do
      (letfn [(copy-crossovers# []
                (cljsbuild.crossover/copy-crossovers
                  ~crossover-path
                  '~crossovers))]
        (copy-crossovers#)
        (when ~watch?
          (cljsbuild.util/once-every 1000 "copying crossovers" copy-crossovers#))
        (cljsbuild.util/in-threads
          (fn [opts#]
            (cljsbuild.compiler/run-compiler
              (:source-path opts#)
              ~crossover-path
              (:compiler opts#)
              ~watch?))
          '~builds)))))

(defn- cleanup-files [project {:keys [crossover-path builds]}]
  (println "Deleting ClojureScript-related generated files.")
  (fs/delete-dir repl-output-path)
  (fs/delete-dir crossover-path)
  (run-local-project project crossover-path builds
    '(require 'cljsbuild.clean 'cljsbuild.util)
    `(cljsbuild.util/in-threads
      (fn [opts#]
        (cljsbuild.clean/cleanup-files
          (:compiler opts#)))
      '~builds)))

(defn- run-tests [project {:keys [test-commands builds]} args]
  (when (> (count args) 1)
    (throw (Exception. "Only expected zero or one arguments.")))
  (let [selected-tests (if (empty? args)
                         (do
                           (println "Running all ClojureScript tests.")
                           (vec (vals test-commands)))
                         (do
                           (println "Running ClojureScript test:" (first args))
                           [(test-commands (first args))]))]
  (run-local-project project crossover-path builds
    '(require 'cljsbuild.test)
    `(cljsbuild.test/run-tests ~selected-tests))))

(defn- run-compiler-and-tests [project options args]
  (let [compile-result (run-compiler project options false)]
    (if (not= compile-result exit-success)
      compile-result
      (run-tests project options args))))

(defn- run-repl-listen [project {:keys [crossover-path builds repl-listen-port]}]
  (println (str "Running ClojureScript REPL, listening on port " repl-listen-port "."))
  (run-local-project project crossover-path builds
    '(require 'cljsbuild.repl.listen)
    `(cljsbuild.repl.listen/run-repl-listen
      ~repl-listen-port
      ~repl-output-path)))

(defn- run-repl-launch [project {:keys [crossover-path builds repl-listen-port repl-launch-commands]} args]
  (when (< (count args) 1)
    (throw (Exception. "Must supply a launch command identifier.")))
  (let [launch-name (first args)
        command-args (rest args)
        command-base (repl-launch-commands launch-name)]
    (when (nil? command-base)
      (throw (Exception. (str "Unknown REPL launch command: " launch-name))))
    (let [command (concat command-base command-args)]
      (println "Running ClojureScript REPL and launching command:" command)
      (run-local-project project crossover-path builds
        '(require 'cljsbuild.repl.listen)
        `(cljsbuild.repl.listen/run-repl-launch
            ~repl-listen-port
            ~repl-output-path
            '~command)))))

(defn- run-repl-rhino [project {:keys [crossover-path builds]}]
  (println "Running Rhino-based ClojureScript REPL.")
  (run-local-project project crossover-path builds 
    '(require 'cljsbuild.repl.rhino)
    `(cljsbuild.repl.rhino/run-repl-rhino)))

(defn- set-default-build-options [options]
  (deep-merge default-build-options options))

(defn- set-default-output-dirs [options]
  (let [output-dir-key [:compiler :output-dir]
        builds
         (for [[build counter] (map vector (:builds options) (range))]
           (if (get-in build output-dir-key)
             build
             (assoc-in build output-dir-key
               (str compiler-output-dir-base counter))))]
    (if (apply distinct? (map #(get-in % output-dir-key) builds))
      (assoc options :builds builds)
      (throw (Exception. (str "All " output-dir-key " options must be distinct."))))))

(defn- set-default-global-options [options]
  (deep-merge default-global-options
    (assoc options :builds
      (map set-default-build-options (:builds options)))))

(defn- warn-deprecated [options]
  (warn "your deprecated :cljsbuild config was interpreted as:")
  (pprint/pprint options *err*)
  (printerr
    "See https://github.com/emezeske/lein-cljsbuild/blob/master/README.md"
    "for details on the new format.")
  options)

(defn- normalize-options
  "Sets default options and accounts for backwards compatibility."
  [options]
  (set-default-output-dirs
    (cond
      (and (map? options) (nil? (:builds options)))
        (warn-deprecated
          [{:builds (set-default-build-options options)}])
      (seq? options)
        (warn-deprecated
          [{:builds (map set-default-build-options options)}])
      :else
        (set-default-global-options options))))

(defn- extract-options
  "Given a project, returns a seq of cljsbuild option maps."
  [project]
  (when (nil? (:cljsbuild project))
    (warn "no :cljsbuild entry found in project definition."))
  (let [raw-options (:cljsbuild project)]
    (normalize-options raw-options)))

(defn cljsbuild
  "Run the cljsbuild plugin.

Usage: lein cljsbuild <command>

Available commands:

  once          Compile the ClojureScript project once.
  auto          Automatically recompile when files are modified.
  clean         Remove automatically generated files.
  test          Run ClojureScript tests.

  repl-listen   Run a REPL that will listen for incoming connections.
  repl-launch   Run a REPL and launch a custom command to connect to it.
  repl-rhino    Run a Rhino-based REPL."
  ([project]
    (usage)
    exit-failure)
  ([project mode & args]
     (let [options (extract-options project)]
       (case mode
         "once" (run-compiler project options false)
         "auto" (run-compiler project options true)
         "clean" (cleanup-files project options)
         "test" (run-compiler-and-tests project options args)
         "repl-listen" (run-repl-listen project options)
         "repl-launch" (run-repl-launch project options args)
         "repl-rhino" (run-repl-rhino project options)
         (do
           (usage)
           exit-failure)))))

(defn- file-bytes
  "Reads a file into a byte array"
  [file]
  (with-open [fis (java.io.FileInputStream. file)]
    (let [ba (byte-array (.length file))]
      (.read fis ba)
      ba)))

(defn- relative-path
  "Given two normalized path strings, returns a path string of the second relative to the first."
  [parent child]
  (s/replace (s/replace child parent "") #"^[\\/]" ""))

;; The reason we return a :bytes filespec is that it's the only way of
;; specifying a file's destination path inside the jar and is contents
;; independently. Obviously this presents issues if there are any very
;; large files - this should be fixable in leiningen 2.0.
(defn- path-filespecs
  "Given a path, returns a seq of filespecs representing files on the path."
  [path]
  (let [dir (io/file path)
        files (file-seq dir)]
    (for [file (filter #(not (.isDirectory %)) files)]
      {:type :bytes
       :path (relative-path (.getCanonicalPath dir) (.getCanonicalPath file))
       :bytes (file-bytes file)})))

(defn- get-filespecs
  "Returns a seq of filespecs for cljs dirs (as passed to leiningen.jar/write-jar)"
  [project]
  (let [builds (extract-options project)
        paths (map :source-path (filter :jar builds))]
    (mapcat path-filespecs paths)))

(defn compile-hook [task & args]
  (let [compile-result (apply task args)]
    (if (not= compile-result exit-success)
      compile-result
      (run-compiler (first args) (extract-options (first args)) false))))

(defn test-hook [task & args]
  (let [test-results [(apply task args) 
                      (run-tests (first args) (extract-options (first args)) [])]]
    (if (every? #(= % exit-success) test-results)
      exit-success
      exit-failure)))

(defn clean-hook [task & args]
  (apply task args)
  (cleanup-files (first args) (extract-options (first args))))

(defn jar-hook [task & [project out-file filespecs]]
  (apply task [project out-file (concat filespecs (get-filespecs project))]))

(hooke/add-hook #'lcompile/compile compile-hook)
(hooke/add-hook #'ltest/test test-hook)
(hooke/add-hook #'lclean/clean clean-hook)
(hooke/add-hook #'ljar/write-jar jar-hook)