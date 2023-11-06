(ns build.core
  (:require [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd]))

(def lib 'com.github.geodrome/entity-graph)
;(def version (format "0.0.%s" (b/git-count-revs nil)))
;; *** NOTE: Change version number manually! ***
(def version "0.1.0-SNAPSHOT")
(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn"}))
(def jar-file (format "target/%s-%s.jar" (name lib) version))

(defn clean [_]
  (b/delete {:path "target"}))

(defn jar [_]
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version version
                :basis basis
                :src-dirs ["src"]})
  (b/copy-dir {:src-dirs   ["src"]
               :target-dir class-dir})
  (b/jar {:class-dir class-dir
          :jar-file jar-file}))

(defn install
  "Install JAR to local maven repo."
  [_]
  (b/install {:basis      basis
              :lib        lib
              :version    version
              :jar-file   jar-file
              :class-dir  class-dir}))

(defn deploy
  "Install JAR to Clojars."
  [_]
  (dd/deploy {:installer :remote
              :artifact jar-file
              :pom-file (b/pom-path {:lib lib :class-dir class-dir})}))

;; - From the command line
;; -- To create new jar:
; $ clj -T:build clean
; $ clj -T:build jar
;; -- To install in local Maven:
; $ clj -T:build install
;; -- To deploy to Clojars:
;; Expects CLOJARS_USERNAME and CLOJARS_PASSWORD env variables
; $ clj -T:build deploy