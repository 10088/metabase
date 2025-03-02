(ns metabase.test.data.impl
  "Internal implementation of various helper functions in `metabase.test.data`."
  (:require
   [clojure.string :as str]
   [clojure.tools.reader.edn :as edn]
   [metabase.api.common :as api]
   [metabase.db.connection :as mdb.connection]
   [metabase.db.query :as mdb.query]
   [metabase.driver :as driver]
   [metabase.driver.util :as driver.u]
   [metabase.models :refer [Database Field FieldValues Secret Table]]
   [metabase.models.secret :as secret]
   [metabase.plugins.classloader :as classloader]
   [metabase.sync :as sync]
   [metabase.sync.util :as sync-util]
   [metabase.test.data.dataset-definitions :as defs]
   [metabase.test.data.impl.verify :as verify]
   [metabase.test.data.interface :as tx]
   [metabase.test.initialize :as initialize]
   [metabase.test.util.timezone :as test.tz]
   [metabase.util :as u]
   [metabase.util.log :as log]
   [potemkin :as p]
   [toucan.db :as db]))

(comment verify/keep-me)

(p/import-vars
 [verify verify-data-loaded-correctly])

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                          get-or-create-database!; db                                           |
;;; +----------------------------------------------------------------------------------------------------------------+


(defonce ^:private ^{:arglists '([driver]), :doc "We'll have a very bad time if any sort of test runs that calls
  `data/db` for the first time calls it multiple times in parallel -- for example my Oracle test that runs 30 sync
  calls at the same time to make sure nothing explodes and cursors aren't leaked. To make sure this doesn't happen
  we'll keep a map of driver->lock and only allow a given driver to create one Database at a time. Because each DB has
  its own lock we can still create different DBs for different drivers at the same time."}
  driver->create-database-lock
  (let [locks (atom {})]
    (fn [driver]
      (let [driver (driver/the-driver driver)]
        (or
         (@locks driver)
         (locking driver->create-database-lock
           (or
            (@locks driver)
            (do
              (swap! locks update driver #(or % (Object.)))
              (@locks driver)))))))))

(defmulti get-or-create-database!
  "Create DBMS database associated with `database-definition`, create corresponding Metabase Databases/Tables/Fields,
  and sync the Database. `driver` is a keyword name of a driver that implements test extension methods (as defined in
  the `metabase.test.data.interface` namespace); `driver` defaults to `driver/*driver*` if bound, or `:h2` if not.
  `database-definition` is anything that implements the `tx/get-database-definition` method."
  {:arglists '([driver database-definition])}
  tx/dispatch-on-driver-with-test-extensions
  :hierarchy #'driver/hierarchy)

(defn- add-extra-metadata!
  "Add extra metadata like Field base-type, etc."
  [{:keys [table-definitions], :as _database-definition} db]
  {:pre [(seq table-definitions)]}
  (doseq [{:keys [table-name], :as table-definition} table-definitions]
    (let [table (delay (or (tx/metabase-instance table-definition db)
                           (throw (Exception. (format "Table '%s' not loaded from definition:\n%s\nFound:\n%s"
                                                      table-name
                                                      (u/pprint-to-str (dissoc table-definition :rows))
                                                      (u/pprint-to-str (db/select [Table :schema :name], :db_id (:id db))))))))]
      (doseq [{:keys [field-name], :as field-definition} (:field-definitions table-definition)]
        (let [field (delay (or (tx/metabase-instance field-definition @table)
                               (throw (Exception. (format "Field '%s' not loaded from definition:\n%s"
                                                          field-name
                                                          (u/pprint-to-str field-definition))))))]
          (doseq [property [:visibility-type :semantic-type :effective-type :coercion-strategy]]
            (when-let [v (get field-definition property)]
              (log/debugf "SET %s %s.%s -> %s" property table-name field-name v)
              (db/update! Field (:id @field) (keyword (str/replace (name property) #"-" "_")) (u/qualified-name v)))))))))

(def ^:private create-database-timeout-ms
  "Max amount of time to wait for driver text extensions to create a DB and load test data."
  (u/minutes->ms 30)) ; Redshift is slow

(def ^:private sync-timeout-ms
  "Max amount of time to wait for sync to complete."
  (u/minutes->ms 15))

(defonce ^:private reference-sync-durations
  (delay (edn/read-string (slurp "test_resources/sync-durations.edn"))))

(defn- create-database! [driver {:keys [database-name], :as database-definition}]
  {:pre [(seq database-name)]}
  (try
    ;; Create the database and load its data
    ;; ALWAYS CREATE DATABASE AND LOAD DATA AS UTC! Unless you like broken tests
    (u/with-timeout create-database-timeout-ms
      (test.tz/with-system-timezone-id "UTC"
        (tx/create-db! driver database-definition)))
    ;; Add DB object to Metabase DB
    (let [connection-details (tx/dbdef->connection-details driver :db database-definition)
          db                 (db/insert! Database
                               :name    database-name
                               :engine  (u/qualified-name driver)
                               :details connection-details)]
      (try
        ;; sync newly added DB
        (u/with-timeout sync-timeout-ms
          (let [reference-duration (or (some-> (get @reference-sync-durations database-name) u/format-nanoseconds)
                                       "NONE")
                full-sync? (#{"test-data" "sample-dataset"} database-name)]
            (u/profile (format "%s %s Database %s (reference H2 duration: %s)"
                               (if full-sync? "Sync" "QUICK sync") driver database-name reference-duration)
              ;; only do "quick sync" for non `test-data` datasets, because it can take literally MINUTES on CI.
              (binding [sync-util/*log-exceptions-and-continue?* false]
                (sync/sync-database! db {:scan (if full-sync? :full :schema)}))
              ;; add extra metadata for fields
              (try
                (add-extra-metadata! database-definition db)
                (catch Throwable e
                  (log/error e "Error adding extra metadata"))))))
        ;; make sure we're returing an up-to-date copy of the DB
        (db/select-one Database :id (u/the-id db))
        (catch Throwable e
          (let [e (ex-info (format "Failed to create test database: %s" (ex-message e))
                           {:driver             driver
                            :database-name      database-name
                            :connection-details connection-details}
                           e)]
            (log/error e "Failed to create test database")
            (db/delete! Database :id (u/the-id db))
            (throw e)))))
    (catch Throwable e
      (log/errorf e "create-database! failed; destroying %s database %s" driver (pr-str database-name))
      (tx/destroy-db! driver database-definition)
      (throw (ex-info (format "Failed to create %s '%s' test database: %s" driver database-name (ex-message e))
                      {:driver        driver
                       :database-name database-name}
                      e)))))

(defmethod get-or-create-database! :default
  [driver dbdef]
  (initialize/initialize-if-needed! :plugins :db)
  (let [dbdef (tx/get-dataset-definition dbdef)]
    (or
     (tx/metabase-instance dbdef driver)
     (locking (driver->create-database-lock driver)
       (or
        (tx/metabase-instance dbdef driver)
        ;; make sure report timezone isn't bound, possibly causing weird things to happen when data is loaded -- this
        ;; code may run inside of some other block that sets report timezone
        ;;
        ;; require/resolve used here to avoid circular refs
        (letfn [(thunk []
                  (binding [api/*current-user-id*              nil
                            api/*current-user-permissions-set* nil]
                    (create-database! driver dbdef)))]
          (if (driver/report-timezone)
            ((requiring-resolve 'metabase.test.util/do-with-temporary-setting-value)
             :report-timezone nil
             thunk)
            (thunk))))))))

(defn- get-or-create-test-data-db!
  "Get or create the Test Data database for `driver`, which defaults to `driver/*driver*`, or `:h2` if that is unbound."
  ([]       (get-or-create-test-data-db! (tx/driver)))
  ([driver] (get-or-create-database! driver defs/test-data)))

(def ^:dynamic *get-db*
  "Implementation of `db` function that should return the current working test database when called, always with no
  arguments. By default, this is `get-or-create-test-data-db!` for the current driver/`*driver*`, which does exactly
  what it suggests."
  get-or-create-test-data-db!)

(defn do-with-db
  "Internal impl of `data/with-db`."
  [db f]
  (assert (and (map? db) (integer? (:id db)))
          (format "Not a valid database: %s" (pr-str db)))
  (binding [*get-db* (constantly db)]
    (f)))


;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                                       id                                                       |
;;; +----------------------------------------------------------------------------------------------------------------+

(defn the-table-id
  "Internal impl of `(data/id table)."
  [db-id table-name]
  {:pre [(integer? db-id) ((some-fn keyword? string?) table-name)]}
  (let [table-name        (name table-name)
        table-id-for-name (partial db/select-one-id Table, :db_id db-id, :name)]
    (or (table-id-for-name table-name)
        (table-id-for-name (let [db-name (db/select-one-field :name Database :id db-id)]
                             (tx/db-qualified-table-name db-name table-name)))
        (let [{driver :engine, db-name :name} (db/select-one [Database :engine :name] :id db-id)]
          (throw
           (Exception. (format "No Table %s found for %s Database %d %s.\nFound: %s"
                               (pr-str table-name) driver db-id (pr-str db-name)
                               (u/pprint-to-str (db/select-id->field :name Table, :db_id db-id, :active true)))))))))

(defn- qualified-field-name [{parent-id :parent_id, field-name :name}]
  (if parent-id
    (str (qualified-field-name (db/select-one Field :id parent-id))
         \.
         field-name)
    field-name))

(defn- all-field-names [table-id]
  (into {} (for [field (db/select Field :active true, :table_id table-id)]
             [(u/the-id field) (qualified-field-name field)])))

(defn- the-field-id* [table-id field-name & {:keys [parent-id]}]
  (or (db/select-one-id Field, :active true, :table_id table-id, :name field-name, :parent_id parent-id)
      (let [{db-id :db_id, table-name :name} (db/select-one [Table :name :db_id] :id table-id)
            db-name                          (db/select-one-field :name Database :id db-id)
            field-name                       (qualified-field-name {:parent_id parent-id, :name field-name})
            all-field-names                  (all-field-names table-id)]
        (throw
         (ex-info (format "Couldn't find Field %s for Table %s.\nFound:\n%s"
                          (pr-str field-name) (pr-str table-name) (u/pprint-to-str all-field-names))
                  {:field-name  field-name
                   :table       table-name
                   :table-id    table-id
                   :database    db-name
                   :database-id db-id
                   :all-fields  all-field-names})))))

(defn the-field-id
  "Internal impl of `(data/id table field)`."
  [table-id field-name & nested-field-names]
  {:pre [(integer? table-id)]}
  (doseq [field-name (cons field-name nested-field-names)]
    (assert ((some-fn keyword? string?) field-name)
            (format "Expected keyword or string field name; got ^%s %s"
                    (some-> field-name class .getCanonicalName)
                    (pr-str field-name))))
  (loop [parent-id (the-field-id* table-id field-name), [nested-field-name & more] nested-field-names]
    (if-not nested-field-name
      parent-id
      (recur (the-field-id* table-id nested-field-name, :parent-id parent-id) more))))


;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                              with-temp-copy-of-db                                              |
;;; +----------------------------------------------------------------------------------------------------------------+

(defn- copy-table-fields! [old-table-id new-table-id]
  (db/insert-many! Field
    (for [field (db/select Field :table_id old-table-id {:order-by [[:id :asc]]})]
      (-> field (dissoc :id :fk_target_field_id) (assoc :table_id new-table-id))))
  ;; now copy the FieldValues as well.
  (let [old-field-id->name (db/select-id->field :name Field :table_id old-table-id)
        new-field-name->id (db/select-field->id :name Field :table_id new-table-id)
        old-field-values   (db/select FieldValues :field_id [:in (set (keys old-field-id->name))])]
    (db/insert-many! FieldValues
      (for [{old-field-id :field_id, :as field-values} old-field-values
            :let                                       [field-name (get old-field-id->name old-field-id)]]
        (-> field-values
            (dissoc :id)
            (assoc :field_id (get new-field-name->id field-name)))))))

(defn- copy-db-tables! [old-db-id new-db-id]
  (let [old-tables    (db/select Table :db_id old-db-id {:order-by [[:id :asc]]})
        new-table-ids (db/insert-many! Table
                        (for [table old-tables]
                          (-> table (dissoc :id) (assoc :db_id new-db-id))))]
    (doseq [[old-table-id new-table-id] (zipmap (map :id old-tables) new-table-ids)]
      (copy-table-fields! old-table-id new-table-id))))

(defn- copy-db-fks! [old-db-id new-db-id]
  (doseq [{:keys [source-field source-table target-field target-table]}
          (mdb.query/query {:select    [[:source-field.name :source-field]
                                        [:source-table.name :source-table]
                                        [:target-field.name   :target-field]
                                        [:target-table.name   :target-table]]
                            :from      [[:metabase_field :source-field]]
                            :left-join [[:metabase_table :source-table] [:= :source-field.table_id :source-table.id]
                                        [:metabase_field :target-field] [:= :source-field.fk_target_field_id :target-field.id]
                                        [:metabase_table :target-table] [:= :target-field.table_id :target-table.id]]
                            :where     [:and
                                        [:= :source-table.db_id old-db-id]
                                        [:= :target-table.db_id old-db-id]
                                        [:not= :source-field.fk_target_field_id nil]]})]
    (db/update! Field (the-field-id (the-table-id new-db-id source-table) source-field)
      :fk_target_field_id (the-field-id (the-table-id new-db-id target-table) target-field))))

(defn- copy-db-tables-and-fields! [old-db-id new-db-id]
  (copy-db-tables! old-db-id new-db-id)
  (copy-db-fks! old-db-id new-db-id))

(defn- get-linked-secrets
  [{:keys [details] :as database}]
  (when-let [conn-props-fn (get-method driver/connection-properties (driver.u/database->driver database))]
    (let [conn-props (conn-props-fn (driver.u/database->driver database))]
      (into {}
            (keep (fn [prop-name]
                    (let [id-prop (keyword (str prop-name "-id"))]
                      (when-let [id (get details id-prop)]
                        [id-prop id]))))
            (keys (secret/conn-props->secret-props-by-name conn-props))))))

(defn- copy-secrets [database]
  (let [prop->old-id (get-linked-secrets database)]
    (if (seq prop->old-id)
      (let [secrets (db/select [Secret :id :name :kind :source :value] :id [:in (set (vals prop->old-id))])
            new-ids (db/insert-many! Secret (map #(dissoc % :id) secrets))
            old-id->new-id (zipmap (map :id secrets) new-ids)]
        (assoc database
               :details
               (reduce (fn [details [id-prop old-id]]
                         (assoc details id-prop (get old-id->new-id old-id)))
                 (:details database)
                 prop->old-id)))
      database)))

(def ^:dynamic *db-is-temp-copy?*
  "Whether the current test database is a temp copy created with the [[metabase.test/with-temp-copy-of-db]] macro."
  false)

(defn do-with-temp-copy-of-db
  "Internal impl of [[metabase.test/with-temp-copy-of-db]]. Run `f` with a temporary Database that copies the details
  from the standard test database, and syncs it."
  [f]
  (let [{old-db-id :id, :as old-db} (*get-db*)
        original-db (-> old-db copy-secrets (select-keys [:details :engine :name]))
        {new-db-id :id, :as new-db} (db/insert! Database original-db)]
    (try
      (copy-db-tables-and-fields! old-db-id new-db-id)
      (binding [*db-is-temp-copy?* true]
        (do-with-db new-db f))
      (finally
        (db/delete! Database :id new-db-id)))))


;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                                    dataset                                                     |
;;; +----------------------------------------------------------------------------------------------------------------+

(defn resolve-dataset-definition
  "Impl for [[metabase.test/dataset]] macro. Resolve a dataset definition (e.g. `test-data` or `sad-toucan-incidents` in
  a namespace."
  [namespace-symb symb]
  @(or (ns-resolve namespace-symb symb)
       (do
         (classloader/require 'metabase.test.data.dataset-definitions)
         (ns-resolve 'metabase.test.data.dataset-definitions symb))
       (throw (Exception. (format "Dataset definition not found: '%s/%s' or 'metabase.test.data.dataset-definitions/%s'"
                                  namespace-symb symb symb)))))

(defn do-with-dataset
  "Impl for [[metabase.test/dataset]] macro."
  [dataset-definition f]
  (let [dbdef             (tx/get-dataset-definition dataset-definition)
        get-db-for-driver (mdb.connection/memoize-for-application-db
                           (fn [driver]
                             (binding [db/*disable-db-logging* true]
                               (let [db (get-or-create-database! driver dbdef)]
                                 (assert db)
                                 (assert (db/exists? Database :id (u/the-id db)))
                                 db))))]
    (binding [*get-db* (fn []
                         (locking do-with-dataset
                           (get-db-for-driver (tx/driver))))]
      (f))))
