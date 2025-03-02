(ns metabase.util.yaml
  (:refer-clojure :exclude [load])
  (:require
   [clojure.string :as str]
   [metabase.util :as u]
   [metabase.util.files :as u.files]
   [metabase.util.i18n :refer [trs]]
   [metabase.util.log :as log]
   [yaml.core :as yaml])
  (:import
   (java.nio.file Files Path)))

(defn load
  "Load YAML at path `f`, parse it, and (optionally) pass the result to `constructor`."
  ([f] (load identity f))
  ([constructor ^Path f]
   (try
     (->> f
          .toUri
          slurp
          yaml/parse-string
          constructor)
     (catch Exception e
       (log/error (trs "Error parsing {0}:\n{1}"
                       (.getFileName f)
                       (or (some-> e
                                   ex-data
                                   (select-keys [:error :value])
                                   u/pprint-to-str)
                           e)))
       (throw e)))))

(defn load-dir
  "Load and parse all YAMLs in `dir`. Optionally pass each resulting data structure through `constructor-fn`."
  ([dir] (load-dir dir identity))
  ([dir constructor]
   (u.files/with-open-path-to-resource [dir dir]
     (with-open [ds (Files/newDirectoryStream dir)]
       (->> ds
            (filter (comp #(str/ends-with? % ".yaml") u/lower-case-en (memfn ^Path getFileName)))
            (mapv (partial load constructor)))))))
