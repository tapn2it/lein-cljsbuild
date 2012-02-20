# Sharing Code Between Clojure and ClojureScript

**TODO** Fix docs W.R.T. the :crossovers option moving around.

Sharing code with lein-cljsbuild is accomplished via the configuration
of "crossovers".  A crossover specifies a Clojure namespace, the content
of which should be copied into your ClojureScript project.  This can be any
namespace that is available via the Java CLASSPATH, which includes your
project's main :source-path by default.

When a crossover namespace is provided by your current project (either via the
main `:source-dir` or one of the `:extra-classpath-dirs` in your project.clj file),
the files that make up that namespace (recursively) will be monitored for changes,
and will be copied to the ClojureScript project whenever modified.

Crossover namespaces provided by jar files cannot be searched recursively, and
thus must be specified on a per-file basis.  They are copied over once, when
lein-cljsbuild begins compilation, and are not monitored for changes.

Of course, remember that since the namespace will be used by both Clojure
and ClojureScript, it will need to only use the subset of features provided by
both languages.

Assuming that your top-level directory structure looks something like this:

<pre>
├── src-clj
│   └── example
│       ├── core.clj
│       ├── something.clj
│       └── crossover
│           ├── some_stuff.clj
│           └── some_other_stuff.clj
└── src-cljs
    └── example
        ├── core.cljs
        ├── whatever.cljs
        └── util.cljs
</pre>

And your `project.clj` file looks like this:

```clj
(defproject lein-cljsbuild-example "1.2.3"
  :plugins [[lein-cljsbuild "0.1.0"]]
  :source-path "src-clj"
  :cljsbuild {
    :builds [{
      :source-path "src-cljs"
      ; Each entry in the :crossovers vector describes a Clojure namespace
      ; that is meant to be used with the ClojureScript code as well.
      ; The files that make up this namespace will be automatically copied
      ; into the ClojureScript source path whenever they are modified.
      :crossovers [example.crossover]
      :compiler {
        :output-to "war/javascripts/main.js"  ; default: main.js in current directory
        :optimizations :whitespace
        :pretty-print true}}]})
```

Then lein-cljsbuild would copy files from `src-clj/example/crossover`
to `src-cljs/example/crossover`, and you'd end up with this:

<pre>
├── src-clj
│   └── example
│       ├── a_file.clj
│       ├── core.clj
│       └── crossover
│           ├── some_stuff.clj
│           └── some_other_stuff.clj
└── src-cljs
    └── example
        ├── a_different_file.cljs
        ├── crossover
        │   ├── some_stuff.cljs
        │   └── some_other_stuff.cljs
        ├── whatever.cljs
        └── util.cljs
</pre>

With this setup, you would probably want to add `src-cljs/example/crossover`
to your `.gitignore` file (or equivalent), as its contents are updated automatically
by lein-cljsbuild.

# Sharing Macros Between Clojure and ClojureScript

In ClojureScript, macros are still written in Clojure, and can not be written
in the same file as actual ClojureScript code.  Also, to use them in a ClojureScript
namespace, they must be required via `:require-macros` rather than the usual `:require`.

This makes using the crossover feature to share macros between Clojure and ClojureScript
a bit difficult, but lein-cljsbuild has some special constructs to make it possible.

Three things need to be done to use lein-cljsbuild to share macros.

## 1. Keep Macros in Separate Files

These examples assume that your project uses the  `src-clj/example/crossover`
directory, and that all of the macros are in a file called
`src-clj/example/crossover/macros.clj`.

## 2. Tell lein-cljsbuild Which Files Contain Macros

Add this magical comment to any crossover files that contain macros:

```clj
;*CLJSBUILD-MACRO-FILE*;
```

This tells lein-cljsbuild to refrain from copying the `.clj` files
into the ClojureScript directory.  This step can be skipped if the
macro file is not included in any of the crossover namespaces.

## 3. Use Black Magic to Require Macros Specially

In any crossover Clojure file, lein-cljsbuild will automatically erase the
following string (if it appears):

```clj
;*CLJSBUILD-REMOVE*;
```

This magic can be used to generate a `ns` statement that will work in both
Clojure and ClojureScript:

```clj
(ns example.crossover.some_stuff
  (:require;*CLJSBUILD-REMOVE*;-macros
    [example.crossover.macros :as macros]))
```

Thus, after removing comments, Clojure will see:

```clj
(ns example.crossover.some_stuff
  (:require
    [example.crossover.macros :as macros]))
```

However, lein-cljsbuild will remove the `;*CLJSBUILD-REMOVE*;` string entirely,
before copying the file.  Thus, ClojureScript will see:

```clj
(ns example.crossover.some_stuff
  (:require-macros
    [example.crossover.macros :as macros]))
```

And thus the macros can be shared.