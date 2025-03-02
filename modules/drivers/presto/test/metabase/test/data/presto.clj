(ns metabase.test.data.presto
  "Presto driver test extensions."
  (:require [clojure.string :as str]
            [honeysql.core :as hsql]
            [honeysql.helpers :as hh]
            [metabase.config :as config]
            [metabase.driver :as driver]
            [metabase.driver.ddl.interface :as ddl.i]
            [metabase.driver.presto :as presto]
            [metabase.driver.presto-common :as presto-common]
            [metabase.driver.sql.util :as sql.u]
            [metabase.driver.sql.util.unprepare :as unprepare]
            [metabase.test.data.interface :as tx]
            [metabase.test.data.sql :as sql.tx]
            [metabase.util :as u]
            [metabase.util.log :as log]))

(sql.tx/add-test-extensions! :presto)

(defmethod tx/sorts-nil-first? :presto [_ _] false)

;; during unit tests don't treat presto as having FK support
(defmethod driver/supports? [:presto :foreign-keys] [_ _] (not config/is-test?))

;;; driver test extensions implementation

;; in the past, we had to manually update our Docker image and add a new catalog for every new dataset definition we
;; added. That's insane. Just use the `test-data` catalog and put everything in that, and use
;; `db-qualified-table-name` like everyone else.
(def ^:private test-catalog-name "test-data")

(defmethod tx/dbdef->connection-details :presto
  [_driver _context _dbdef]
  {:host    (tx/db-test-env-var-or-throw :presto :host "localhost")
   :port    (tx/db-test-env-var-or-throw :presto :port "8080")
   :user    (tx/db-test-env-var-or-throw :presto :user "metabase")
   :ssl     false
   :catalog test-catalog-name})

(defmethod sql.tx/qualified-name-components :presto
  ;; use the default schema from the in-memory connector
  ([_driver _db-name]                      [test-catalog-name "default"])
  ([_driver db-name table-name]            [test-catalog-name "default" (tx/db-qualified-table-name db-name table-name)])
  ([_driver db-name table-name field-name] [test-catalog-name "default" (tx/db-qualified-table-name db-name table-name) field-name]))

(defn- field-base-type->dummy-value [field-type]
  ;; we need a dummy value for every base-type to make a properly typed SELECT statement
  (if (keyword? field-type)
    (case field-type
      :type/Boolean                "TRUE"
      :type/Integer                "1"
      :type/BigInteger             "cast(1 AS bigint)"
      :type/Float                  "1.0"
      :type/Decimal                "DECIMAL '1.0'"
      :type/Text                   "cast('' AS VARCHAR)"
      :type/Date                   "current_timestamp" ; this should be a date type, but the test data begs to differ
      :type/DateTime               "current_timestamp"
      :type/DateTimeWithTZ         "current_timestamp"
      :type/DateTimeWithZoneOffset "current_timestamp" ; needed for office-checkins
      :type/Time                   "cast(current_time as TIME)"
      "from_hex('00')") ; this might not be the best default ever
    ;; we were given a native type, map it back to a base-type and try again
    (field-base-type->dummy-value (presto-common/presto-type->base-type field-type))))

(defmethod sql.tx/create-table-sql :presto
  [driver {:keys [database-name]} {:keys [table-name], :as tabledef}]
  (let [field-definitions (cons {:field-name "id", :base-type  :type/Integer} (:field-definitions tabledef))
        dummy-values      (map (comp field-base-type->dummy-value :base-type) field-definitions)
        columns           (map :field-name field-definitions)]
    ;; Presto won't let us use the `CREATE TABLE (...)` form, but we can still do it creatively if we select the right
    ;; types out of thin air
    (format "CREATE TABLE %s AS SELECT * FROM (VALUES (%s)) AS t (%s) WHERE 1 = 0"
            (sql.tx/qualify-and-quote driver database-name table-name)
            (str/join \, dummy-values)
            (str/join \, (for [column columns]
                           (sql.u/quote-name driver :field (ddl.i/format-name driver column)))))))

(defmethod sql.tx/drop-table-if-exists-sql :presto
  [driver {:keys [database-name]} {:keys [table-name]}]
  (str "DROP TABLE IF EXISTS " (sql.tx/qualify-and-quote driver database-name table-name)))

(defn- insert-sql [driver {:keys [database-name]} {:keys [table-name], :as tabledef} rows]
  (let [field-definitions (cons {:field-name "id"} (:field-definitions tabledef))
        columns           (map (comp keyword :field-name) field-definitions)
        [query & params]  (-> (apply hh/columns columns)
                              (hh/insert-into (apply hsql/qualify
                                                     (sql.tx/qualified-name-components driver database-name table-name)))
                              (hh/values rows)
                              (hsql/format :allow-dashed-names? true, :quoting :ansi))]
    (log/tracef "Inserting Presto rows")
    (doseq [row rows]
      (log/trace (str/join ", " (map #(format "^%s %s" (.getName (class %)) (pr-str %)) row))))
    (if (nil? params)
      query
      (unprepare/unprepare :presto (cons query params)))))

(defmethod tx/create-db! :presto
  [driver {:keys [table-definitions] :as dbdef} & {:keys [skip-drop-db?]}]
  (let [details  (tx/dbdef->connection-details driver :db dbdef)
        execute! (partial #'presto/execute-query-for-sync details)]
    (doseq [tabledef table-definitions
            :let     [rows       (:rows tabledef)
                      ;; generate an ID for each row because we don't have auto increments
                      keyed-rows (map-indexed (fn [i row] (cons (inc i) row)) rows)
                      ;; make 100 rows batches since we have to inline everything
                      batches    (partition 100 100 nil keyed-rows)]]
      (when-not skip-drop-db?
        (execute! (sql.tx/drop-table-if-exists-sql driver dbdef tabledef)))
      (try
        (execute! (sql.tx/create-table-sql driver dbdef tabledef))
        (catch Throwable e
          (throw (ex-info (format "Error creating Presto table %s: %s" (:table-name tabledef) (ex-message e))
                          {:tabledef (dissoc tabledef :rows)}
                          e))))
      (doseq [batch batches]
        (try
          (execute! (insert-sql driver dbdef tabledef batch))
          (catch Throwable e
            (throw (ex-info (format "Error inserting rows into Presto table %s: %s"
                                    (:table-name tabledef) (ex-message e))
                            {:tabledef (dissoc tabledef :rows)
                             :batch    batch}
                            e))))))))

(defmethod tx/destroy-db! :presto
  [driver {:keys [database-name table-definitions], :as dbdef}]
  (let [details  (tx/dbdef->connection-details driver :db dbdef)
        execute! (partial #'presto/execute-query-for-sync details)]
    (doseq [{:keys [table-name], :as tabledef} table-definitions]
      (log/infof "[Presto] destroying %s.%s" (pr-str database-name) (pr-str table-name))
      (execute! (sql.tx/drop-table-if-exists-sql driver dbdef tabledef))
      (log/info "[Presto] [ok]"))))

(defmethod ddl.i/format-name :presto
  [_ s]
  (u/lower-case-en s))

;; FIXME Presto actually has very good timezone support
(defmethod tx/has-questionable-timezone-support? :presto [_] true)
