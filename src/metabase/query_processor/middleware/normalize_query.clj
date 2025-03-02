(ns metabase.query-processor.middleware.normalize-query
  "Middleware that converts a query into a normalized, canonical form."
  (:require
   [metabase.mbql.normalize :as mbql.normalize]
   [metabase.query-processor.error-type :as qp.error-type]
   [metabase.util :as u]
   [metabase.util.log :as log]))

(defn normalize
  "Middleware that converts a query into a normalized, canonical form, including things like converting all identifiers
  into standard `lisp-case` ones, removing/rewriting legacy clauses, removing empty ones, etc. This is done to
  simplifiy the logic in the QP steps following this."
  [qp]
  (fn [query rff context]
    (let [query' (try
                   (u/prog1 (mbql.normalize/normalize query)
                     (log/tracef "Normalized query:\n%s" (u/pprint-to-str <>)))
                   (catch Throwable e
                     (throw (ex-info (.getMessage e)
                                     {:type  qp.error-type/invalid-query
                                      :query query}
                                     e))))]
      (qp query' rff context))))
