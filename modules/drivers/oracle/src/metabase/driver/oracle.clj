(ns metabase.driver.oracle
  (:require
   [clojure.java.jdbc :as jdbc]
   [clojure.string :as str]
   [honeysql.format :as hformat]
   [java-time :as t]
   [metabase.config :as config]
   [metabase.driver :as driver]
   [metabase.driver.common :as driver.common]
   [metabase.driver.impl :as driver.impl]
   [metabase.driver.sql :as driver.sql]
   [metabase.driver.sql-jdbc.common :as sql-jdbc.common]
   [metabase.driver.sql-jdbc.connection :as sql-jdbc.conn]
   [metabase.driver.sql-jdbc.execute :as sql-jdbc.execute]
   [metabase.driver.sql-jdbc.sync :as sql-jdbc.sync]
   [metabase.driver.sql-jdbc.sync.common :as sql-jdbc.sync.common]
   [metabase.driver.sql-jdbc.sync.describe-table
    :as sql-jdbc.describe-table]
   [metabase.driver.sql.query-processor :as sql.qp]
   [metabase.driver.sql.query-processor.empty-string-is-null
    :as sql.qp.empty-string-is-null]
   [metabase.driver.sql.util :as sql.u]
   [metabase.driver.sql.util.unprepare :as unprepare]
   [metabase.models.secret :as secret]
   [metabase.query-processor.timezone :as qp.timezone]
   [metabase.util :as u]
   [metabase.util.honeysql-extensions :as hx]
   [metabase.util.i18n :refer [trs]]
   [metabase.util.log :as log]
   [metabase.util.ssh :as ssh])
  (:import
   (com.mchange.v2.c3p0 C3P0ProxyConnection)
   (java.security KeyStore)
   (java.sql Connection DatabaseMetaData ResultSet Types)
   (java.time Instant OffsetDateTime ZonedDateTime)
   (oracle.jdbc OracleConnection OracleTypes)
   (oracle.sql TIMESTAMPTZ)))

(defmethod driver/database-supports? [:oracle :now] [_driver _feat _db] true)

(driver/register! :oracle, :parent #{:sql-jdbc ::sql.qp.empty-string-is-null/empty-string-is-null})

(defmethod driver/database-supports? [:oracle :datetime-diff] [_driver _feat _db] true)

(defmethod driver/database-supports? [:oracle :convert-timezone]
  [_driver _feat _db]
  true)

(def ^:private database-type->base-type
  (sql-jdbc.sync/pattern-based-database-type->base-type
   [;; Any types -- see http://docs.oracle.com/cd/B28359_01/server.111/b28286/sql_elements001.htm#i107578
    [#"ANYDATA"     :type/*]  ; Instance of a given type with data plus a description of the type (?)
    [#"ANYTYPE"     :type/*]  ; Can be any named SQL type or an unnamed transient type
    [#"ARRAY"       :type/*]
    [#"BFILE"       :type/*]
    [#"BLOB"        :type/*]
    [#"RAW"         :type/*]
    [#"CHAR"        :type/Text]
    [#"CLOB"        :type/Text]
    [#"DATE"        :type/Date]
    [#"DOUBLE"      :type/Float]
    ;; Expression filter type
    [#"^EXPRESSION" :type/*]
    [#"FLOAT"       :type/Float]
    ;; Does this make sense?
    [#"INTERVAL"    :type/DateTime]
    [#"LONG RAW"    :type/*]
    [#"LONG"        :type/Text]
    ;; Media types -- http://docs.oracle.com/cd/B28359_01/server.111/b28286/sql_elements001.htm#i121058
    [#"^ORD"        :type/*]
    [#"NUMBER"      :type/Decimal]
    [#"REAL"        :type/Float]
    [#"REF"         :type/*]
    [#"ROWID"       :type/*]
    ;; Spatial types -- see http://docs.oracle.com/cd/B28359_01/server.111/b28286/sql_elements001.htm#i107588
    [#"^SDO_"       :type/*]
    [#"STRUCT"      :type/*]
    [#"TIMESTAMP(\(\d\))? WITH TIME ZONE" :type/DateTimeWithTZ]
    [#"TIMESTAMP"   :type/DateTime]
    [#"URI"         :type/Text]
    [#"XML"         :type/*]]))

(defmethod sql-jdbc.sync/database-type->base-type :oracle
  [_ column-type]
  (database-type->base-type column-type))

(defn- non-ssl-spec [_details spec host port sid service-name]
  (assoc spec :subname (str "@" host
                            ":" port
                            (when sid
                              (str ":" sid))
                            (when service-name
                              (str "/" service-name)))))

(defn- ssl-spec [details spec host port sid service-name]
  (-> (assoc spec :subname
                  (format "@(DESCRIPTION=(ADDRESS=(PROTOCOL=tcps)(HOST=%s)(PORT=%d))(CONNECT_DATA=%s%s))"
                          host
                          port
                          (if sid (str "(SID=" sid ")") "")
                          (if service-name (str "(SERVICE_NAME=" service-name ")") "")))
      (sql-jdbc.common/handle-additional-options details)))

(def ^:private ^:const prog-name-property
  "The connection property used by the Oracle JDBC Thin Driver to control the program name."
  "v$session.program")

(defn- guess-keystore-type [^java.io.File keystore-file ^String password]
  (try
    (.getType (KeyStore/getInstance keystore-file (.toCharArray password)))
    (catch Exception _
      ;; Before the introduction of type recognition we always used "JKS",
      ;; so this ensures backwards compatibility.
      "JKS")))

(defn- handle-keystore-options [details]
  (let [keystore (-> (secret/db-details-prop->secret-map details "ssl-keystore")
                     (secret/value->file! :oracle))
        password (or (-> (secret/db-details-prop->secret-map details "ssl-keystore-password")
                         secret/value->string)
                     (secret/get-secret-string details "ssl-keystore-password"))]
    (-> details
        (assoc :javax.net.ssl.keyStoreType (guess-keystore-type keystore password)
               :javax.net.ssl.keyStore keystore
               :javax.net.ssl.keyStorePassword password)
        (dissoc :ssl-use-keystore :ssl-keystore-value :ssl-keystore-path :ssl-keystore-password-value
                :ssl-keystore-created-at :ssl-keystore-password-created-at))))

(defn- handle-truststore-options [details]
  (let [truststore (-> (secret/db-details-prop->secret-map details "ssl-truststore")
                       (secret/value->file! :oracle))
        password (or (-> (secret/db-details-prop->secret-map details "ssl-truststore-password")
                         secret/value->string)
                     (secret/get-secret-string details "ssl-truststore-password"))]
    (-> details
        (assoc :javax.net.ssl.trustStoreType (guess-keystore-type truststore password)
               :javax.net.ssl.trustStore truststore
               :javax.net.ssl.trustStorePassword password)
        (dissoc :ssl-use-truststore :ssl-truststore-value :ssl-truststore-path :ssl-truststore-password-value
                :ssl-truststore-created-at :ssl-truststore-password-created-at))))

(defn- handle-ssl-options [{:keys [password ssl ssl-use-keystore ssl-use-truststore] :as details}]
  (if ssl
    (cond-> details
      ssl-use-keystore handle-keystore-options
      (and ssl-use-keystore (nil? password)) (assoc :oracle.net.authentication_services "(TCPS)")
      ssl-use-truststore handle-truststore-options
      true (dissoc :ssl))
    details))

(defmethod sql-jdbc.conn/connection-details->spec :oracle
  [_ {:keys [host port sid service-name]
      :or   {host "localhost", port 1521}
      :as   details}]
  (assert (or sid service-name))
  (let [spec      {:classname "oracle.jdbc.OracleDriver", :subprotocol "oracle:thin"}
        finish-fn (partial (if (:ssl details) ssl-spec non-ssl-spec) details)
        ;; the v$session.program value has a max length of 48 (see T4Connection), so we have to make it more terse than
        ;; the usual config/mb-version-and-process-identifier string and ensure we truncate to a length of 48
        prog-nm   (as-> (format "MB %s %s" (config/mb-version-info :tag) config/local-process-uuid) s
                    (subs s 0 (min 48 (count s))))]
    (-> (merge spec details)
        (assoc prog-name-property prog-nm)
        handle-ssl-options
        (dissoc :host :port :sid :service-name :ssl)
        (finish-fn host port sid service-name))))

(defmethod driver/can-connect? :oracle
  [driver details]
  (let [connection (sql-jdbc.conn/connection-details->spec driver (ssh/include-ssh-tunnel! details))]
    (= 1M (first (vals (first (jdbc/query connection ["SELECT 1 FROM dual"])))))))

(defmethod driver/db-start-of-week :oracle
  [_]
  :sunday)

;; Oracle mod is a function like mod(x, y) rather than an operator like x mod y
(defmethod hformat/fn-handler (u/qualified-name ::mod)
  [_ x y]
  (format "mod(%s, %s)" (hformat/to-sql x) (hformat/to-sql y)))

(defn- trunc
  "Truncate a date. See also this [table of format
  templates](http://docs.oracle.com/cd/B28359_01/olap.111/b28126/dml_functions_2071.htm#CJAEFAIA)

      (trunc :day v) -> TRUNC(v, 'day')"
  [format-template v]
  (-> (hx/call :trunc v (hx/literal format-template))
      ;; trunc() returns a date -- see https://docs.oracle.com/cd/E11882_01/server.112/e10729/ch4datetime.htm#NLSPG253
      (hx/with-database-type-info "date")))

(defmethod sql.qp/date [:oracle :second-of-minute] [_ _ v] (->> v
                                                                hx/->timestamp
                                                                (hx/call :extract :second)
                                                                (hx/call :floor)
                                                                hx/->integer))

(defmethod sql.qp/date [:oracle :minute]           [_ _ v] (trunc :mi v))
;; you can only extract minute + hour from TIMESTAMPs, even though DATEs still have them (WTF), so cast first
(defmethod sql.qp/date [:oracle :minute-of-hour]   [_ _ v] (hx/call :extract :minute (hx/->timestamp v)))
(defmethod sql.qp/date [:oracle :hour]             [_ _ v] (trunc :hh v))
(defmethod sql.qp/date [:oracle :hour-of-day]      [_ _ v] (hx/call :extract :hour (hx/->timestamp v)))
(defmethod sql.qp/date [:oracle :day]              [_ _ v] (trunc :dd v))
(defmethod sql.qp/date [:oracle :day-of-month]     [_ _ v] (hx/call :extract :day v))
;; [SIC] The format template for truncating to start of week is 'day' in Oracle #WTF
(defmethod sql.qp/date [:oracle :month]            [_ _ v] (trunc :month v))
(defmethod sql.qp/date [:oracle :month-of-year]    [_ _ v] (hx/call :extract :month v))
(defmethod sql.qp/date [:oracle :quarter]          [_ _ v] (trunc :q v))
(defmethod sql.qp/date [:oracle :year]             [_ _ v] (trunc :year v))
(defmethod sql.qp/date [:oracle :year-of-era]      [_ _ v] (hx/call :extract :year v))

(defmethod sql.qp/date [:oracle :week]
  [driver _ v]
  (sql.qp/adjust-start-of-week driver (partial trunc :day) v))

(defmethod sql.qp/date [:oracle :week-of-year-iso]
  [_ _ v]
  ;; the full list of format elements is in https://docs.oracle.com/cd/B19306_01/server.102/b14200/sql_elements004.htm#i34924
  (hx/->integer (hx/call :to_char v (hx/literal :iw))))

(defmethod sql.qp/date [:oracle :day-of-year]
  [driver _ v]
  (hx/inc (hx/- (sql.qp/date driver :day v) (trunc :year v))))

(defmethod sql.qp/date [:oracle :quarter-of-year]
  [driver _ v]
  (hx// (hx/+ (sql.qp/date driver :month-of-year (sql.qp/date driver :quarter v))
              2)
        3))

;; subtract number of days between today and first day of week, then add one since first day of week = 1
(defmethod sql.qp/date [:oracle :day-of-week]
  [driver _ v]
  (sql.qp/adjust-day-of-week
   driver
   (hx/->integer (hx/call :to_char v (hx/literal :d)))
   (driver.common/start-of-week-offset driver)
   (partial hx/call (u/qualified-name ::mod))))

(defmethod sql.qp/current-datetime-honeysql-form :oracle
  [_]
  (-> (hx/raw "CURRENT_TIMESTAMP")
      (hx/with-database-type-info "timestamp with time zone")))

(defmethod sql.qp/->honeysql [:oracle :convert-timezone]
  [driver [_ arg target-timezone source-timezone]]
  (let [expr          (sql.qp/->honeysql driver arg)
        has-timezone? (hx/is-of-type? expr #"timestamp(\(\d\))? with time zone")]
    (sql.u/validate-convert-timezone-args has-timezone? target-timezone source-timezone)
    (-> (if has-timezone?
          expr
          (hx/call :from_tz expr (or source-timezone (qp.timezone/results-timezone-id))))
        (hx/at-time-zone target-timezone)
        hx/->timestamp)))

(defn- num-to-ds-interval [unit v] (hx/call :numtodsinterval v (hx/literal unit)))
(defn- num-to-ym-interval [unit v] (hx/call :numtoyminterval v (hx/literal unit)))

(def ^:private legacy-max-identifier-length
  "Maximal identifier length for Oracle < 12.2"
  30)

(defmethod driver/escape-alias :oracle
  [_driver s]
  ;; Oracle identifiers are not allowed to contain double quote marks or the NULL character; just strip them out.
  ;; (https://docs.oracle.com/cd/B19306_01/server.102/b14200/sql_elements008.htm)
  (let [s (str/replace s #"[\"\u0000]" "_")]
    (driver.impl/truncate-alias s legacy-max-identifier-length)))

(defmethod sql.qp/->honeysql [:oracle :substring]
  [driver [_ arg start length]]
  (if length
    (hx/call :substr (sql.qp/->honeysql driver arg) (sql.qp/->honeysql driver start) (sql.qp/->honeysql driver length))
    (hx/call :substr (sql.qp/->honeysql driver arg) (sql.qp/->honeysql driver start))))

(defmethod sql.qp/->honeysql [:oracle :concat]
  [driver [_ & args]]
  (->> args
       (map (partial sql.qp/->honeysql driver))
       (reduce (partial hx/call :concat))))

(defmethod sql.qp/->honeysql [:oracle :regex-match-first]
  [driver [_ arg pattern]]
  (hx/call :regexp_substr (sql.qp/->honeysql driver arg) (sql.qp/->honeysql driver pattern)))

(def ^:private timestamp-types
  #{"timestamp" "timestamp with time zone" "timestamp with local time zone"})

(defn- cast-to-timestamp-if-needed
  "If `hsql-form` isn't already one of the [[timestamp-types]], cast it to `timestamp`."
  [hsql-form]
  (hx/cast-unless-type-in "timestamp" timestamp-types hsql-form))

(defn- cast-to-date-if-needed
  "If `hsql-form` isn't already one of the [[timestamp-types]] *or* `date`, cast it to `date`."
  [hsql-form]
  (hx/cast-unless-type-in "date" (conj timestamp-types "date") hsql-form))

(defn- add-months [hsql-form amount]
  (-> (hx/call :add_months (cast-to-date-if-needed hsql-form) amount)
      (hx/with-database-type-info "date")))

(defmethod sql.qp/add-interval-honeysql-form :oracle
  [driver hsql-form amount unit]
  ;; use add_months() for months since Oracle will barf if you try to do something like 2022-03-31 + 3 months since
  ;; June 31st doesn't exist. add_months() can figure it out tho.
  (case unit
    :quarter (recur driver hsql-form (* 3 amount) :month)
    :month   (add-months hsql-form amount)
    :second  (hx/+ (cast-to-timestamp-if-needed hsql-form) (num-to-ds-interval :second amount))
    :minute  (hx/+ (cast-to-timestamp-if-needed hsql-form) (num-to-ds-interval :minute amount))
    :hour    (hx/+ (cast-to-timestamp-if-needed hsql-form) (num-to-ds-interval :hour   amount))
    :day     (hx/+ (cast-to-date-if-needed hsql-form)      (num-to-ds-interval :day    amount))
    :week    (hx/+ (cast-to-date-if-needed hsql-form)      (num-to-ds-interval :day    (hx/* amount (hx/raw 7))))
    :year    (hx/+ (cast-to-date-if-needed hsql-form)      (num-to-ym-interval :year   amount))))

(defmethod sql.qp/unix-timestamp->honeysql [:oracle :seconds]
  [_ _ field-or-value]
  (hx/+ (hx/raw "timestamp '1970-01-01 00:00:00 UTC'")
        (num-to-ds-interval :second field-or-value)))

(defmethod sql.qp/cast-temporal-string [:oracle :Coercion/ISO8601->DateTime]
  [_driver _coercion-strategy expr]
  (hx/call :to_timestamp expr "YYYY-MM-DD HH:mi:SS"))

(defmethod sql.qp/cast-temporal-string [:oracle :Coercion/ISO8601->Date]
  [_driver _coercion-strategy expr]
  (hx/call :to_date expr "YYYY-MM-DD"))

(defmethod sql.qp/cast-temporal-string [:oracle :Coercion/YYYYMMDDHHMMSSString->Temporal]
  [_driver _coercion-strategy expr]
  (hx/call :to_timestamp expr "YYYYMMDDHH24miSS"))

(defmethod sql.qp/unix-timestamp->honeysql [:oracle :milliseconds]
  [driver _ field-or-value]
  (sql.qp/unix-timestamp->honeysql driver :seconds (hx// field-or-value (hx/raw 1000))))

(defmethod sql.qp/unix-timestamp->honeysql [:oracle :microseconds]
  [driver _ field-or-value]
  (sql.qp/unix-timestamp->honeysql driver :seconds (hx// field-or-value (hx/raw 1000000))))

(defn- time-zoned-trunc
  "Same as [[trunc]], but truncates `x` to `unit` in the results timezone
   if `x` is a timestamp with time zone."
  [unit x]
  (let [x (cond-> x
             (hx/is-of-type? x #"(?i)timestamp(\(\d\))? with time zone")
             (hx/at-time-zone (qp.timezone/results-timezone-id)))]
    (trunc unit x)))

(defmethod sql.qp/datetime-diff [:oracle :year]
  [driver _unit x y]
  (hx// (sql.qp/datetime-diff driver :month x y) 12))

(defmethod sql.qp/datetime-diff [:oracle :quarter]
  [driver _unit x y]
  (hx// (sql.qp/datetime-diff driver :month x y) 3))

(defmethod sql.qp/datetime-diff [:oracle :month]
  [_driver _unit x y]
  (hx/call :MONTHS_BETWEEN (time-zoned-trunc :dd y) (time-zoned-trunc :dd x)))

(defmethod sql.qp/datetime-diff [:oracle :week]
  [driver _unit x y]
  (hx// (sql.qp/datetime-diff driver :day x y) 7))

(defmethod sql.qp/datetime-diff [:oracle :day]
  [_driver _unit x y]
  (hx/- (time-zoned-trunc :dd y) (time-zoned-trunc :dd x)))

(defn- utc-days-diff
  "Calculates the number of fractional days between `x` and `y`, converting to UTC if the
   args are timestamps with time zones. This is needed because some time zones don't have
   24 hours in every day, which can cause incorrect results if we calculate the number of
   hours, minutes, or seconds between two timestamps with naive subtraction."
  [x y]
  (let [x (cond-> x
            (hx/is-of-type? x #"(?i)timestamp(\(\d\))? with time zone")
            (hx/at-time-zone "UTC"))
        y (cond-> y
            (hx/is-of-type? y #"(?i)timestamp(\(\d\))? with time zone")
            (hx/at-time-zone "UTC"))]
    (hx/- (hx/->date y) (hx/->date x))))

(defmethod sql.qp/datetime-diff [:oracle :hour] [_driver _unit x y] (hx/* (utc-days-diff x y) 24))
(defmethod sql.qp/datetime-diff [:oracle :minute] [_driver _unit x y] (hx/* (utc-days-diff x y) 1440))
(defmethod sql.qp/datetime-diff [:oracle :second] [_driver _unit x y] (hx/* (utc-days-diff x y) 86400))

;; Oracle doesn't support `LIMIT n` syntax. Instead we have to use `WHERE ROWNUM <= n` (`NEXT n ROWS ONLY` isn't
;; supported on Oracle versions older than 12). This has to wrap the actual query, e.g.
;;
;; SELECT *
;; FROM (
;;     SELECT *
;;     FROM employees
;;     ORDER BY employee_id
;; )
;; WHERE ROWNUM < 10;
;;
;; This wrapping can cause problems if there is an ambiguous column reference in the nested query (i.e. two columns
;; with the same alias name). To ensure that doesn't happen, those column references need to be disambiguated first
;;
;; To do an offset we have to do something like:
;;
;; SELECT *
;; FROM (
;;     SELECT __table__.*, ROWNUM AS __rownum__
;;     FROM (
;;         SELECT *
;;         FROM employees
;;         ORDER BY employee_id
;;     ) __table__
;;     WHERE ROWNUM <= 150
;; )
;; WHERE __rownum__ >= 100;
;;
;; See metabase#3568 and the Oracle documentation for more details:
;; http://docs.oracle.com/cd/B19306_01/server.102/b14200/pseudocolumns009.htm
(defmethod sql.qp/apply-top-level-clause [:oracle :limit]
  [_ _ honeysql-query {value :limit}]
  {:select [:*]
   ;; if `honeysql-query` doesn't have a `SELECT` clause yet (which might be the case when using a source query) fall
   ;; back to including a `SELECT *` just to make sure a valid query is produced
   :from   [(-> (merge {:select [:*]}
                       honeysql-query)
                (update :select sql.u/select-clause-deduplicate-aliases))]
   :where  [:<= (hx/raw "rownum") value]})

(defmethod sql.qp/apply-top-level-clause [:oracle :page]
  [driver _ honeysql-query {{:keys [items page]} :page}]
  (let [offset (* (dec page) items)]
    (if (zero? offset)
      ;; if there's no offset we can use use the single-nesting implementation for `apply-limit`
      (sql.qp/apply-top-level-clause driver :limit honeysql-query {:limit items})
      ;; if we need to do an offset we have to do double-nesting
      {:select [:*]
       :from   [{:select [:__table__.* [(hx/raw "rownum") :__rownum__]]
                 :from   [[(merge {:select [:*]}
                                  honeysql-query)
                           :__table__]]
                 :where  [:<= (hx/raw "rownum") (+ offset items)]}]
       :where  [:> :__rownum__ offset]})))


;; Oracle doesn't support `TRUE`/`FALSE`; use `1`/`0`, respectively; convert these booleans to numbers.
(defmethod sql.qp/->honeysql [:oracle Boolean]
  [_ bool]
  (if bool 1 0))

(defmethod driver/humanize-connection-error-message :oracle
  [_ message]
  ;; if the connection error message is caused by the assertion above checking whether sid or service-name is set,
  ;; return a slightly nicer looking version. Otherwise just return message as-is
  (if (str/includes? message "(or sid service-name)")
    "You must specify the SID and/or the Service Name."
    message))

(defn- remove-rownum-column
  "Remove the `:__rownum__` column from results, if present."
  [respond {:keys [cols], :as metadata} rows]
  (if-not (contains? (set (map :name cols)) "__rownum__")
    (respond metadata rows)
    ;; if we added __rownum__ it will always be the last column and value so we can just remove that
    (respond (update metadata :cols butlast)
             (eduction
              (fn [rf]
                (fn
                  ([]        (rf))
                  ([acc]     (rf acc))
                  ([acc row] (rf acc (butlast row)))))
              rows))))

(defmethod driver/execute-reducible-query :oracle
  [driver query context respond]
  ((get-method driver/execute-reducible-query :sql-jdbc) driver query context (partial remove-rownum-column respond)))

(defmethod driver.common/current-db-time-date-formatters :oracle
  [_]
  (driver.common/create-db-time-formatters "yyyy-MM-dd HH:mm:ss.SSS zzz"))

(defmethod driver.common/current-db-time-native-query :oracle
  [_]
  "select to_char(current_timestamp, 'YYYY-MM-DD HH24:MI:SS.FF3 TZD') FROM DUAL")

(defmethod driver/current-db-time :oracle [& args]
  (apply driver.common/current-db-time args))

;; don't redef if already definied -- test extensions override this impl
(when-not (get (methods sql-jdbc.sync/excluded-schemas) :oracle)
  (defmethod sql-jdbc.sync/excluded-schemas :oracle
    [_]
    #{"ANONYMOUS"
      ;; TODO - are there othere APEX tables we want to skip? Maybe we should make this a pattern instead? (#"^APEX_")
      "APEX_040200"
      "APPQOSSYS"
      "AUDSYS"
      "CTXSYS"
      "DBSNMP"
      "DIP"
      "GSMADMIN_INTERNAL"
      "GSMCATUSER"
      "GSMUSER"
      "LBACSYS"
      "MDSYS"
      "OLAPSYS"
      "ORDDATA"
      "ORDSYS"
      "OUTLN"
      "RDSADMIN"
      "SYS"
      "SYSBACKUP"
      "SYSDG"
      "SYSKM"
      "SYSTEM"
      "WMSYS"
      "XDB"
      "XS$NULL"}))

(defmethod driver/escape-entity-name-for-metadata :oracle
  [_ entity-name]
  (str/replace entity-name "/" "//"))

(defmethod sql-jdbc.describe-table/get-table-pks :oracle
  [_driver ^Connection conn _ table]
  (let [^DatabaseMetaData metadata (.getMetaData conn)]
    (into #{} (sql-jdbc.sync.common/reducible-results
               #(.getPrimaryKeys metadata nil nil (:name table))
               (fn [^ResultSet rs] #(.getString rs "COLUMN_NAME"))))))

(defmethod sql-jdbc.execute/set-timezone-sql :oracle
  [_]
  "ALTER session SET time_zone = %s")

;; Oracle doesn't support `CLOSE_CURSORS_AT_COMMIT`. Otherwise this method is basically the same as the default impl
(defmethod sql-jdbc.execute/prepared-statement :oracle
  [driver ^Connection conn ^String sql params]
  (let [stmt (.prepareStatement conn sql
                                ResultSet/TYPE_FORWARD_ONLY
                                ResultSet/CONCUR_READ_ONLY)]
    (try
      (try
        (.setFetchDirection stmt ResultSet/FETCH_FORWARD)
        (catch Throwable e
          (log/debug e (trs "Error setting result set fetch direction to FETCH_FORWARD"))))
      (sql-jdbc.execute/set-parameters! driver stmt params)
      stmt
      (catch Throwable e
        (.close stmt)
        (throw e)))))

;; similar rationale to prepared-statement above
(defmethod sql-jdbc.execute/statement :oracle
   [_ ^Connection conn]
   (let [stmt (.createStatement conn
                                ResultSet/TYPE_FORWARD_ONLY
                                ResultSet/CONCUR_READ_ONLY)]
        (try
          (try
            (.setFetchDirection stmt ResultSet/FETCH_FORWARD)
            (catch Throwable e
              (log/debug e (trs "Error setting result set fetch direction to FETCH_FORWARD"))))
          stmt
          (catch Throwable e
            (.close stmt)
            (throw e)))))

;; instead of returning a CLOB object, return the String. (#9026)
(defmethod sql-jdbc.execute/read-column-thunk [:oracle Types/CLOB]
  [_ ^ResultSet rs _ ^Integer i]
  (fn []
    (.getString rs i)))

(defmethod sql-jdbc.execute/read-column-thunk [:oracle OracleTypes/TIMESTAMPTZ]
  [_driver ^ResultSet rs _ ^Integer i]
  ;; Oracle `TIMESTAMPTZ` types can have either a zone offset *or* a zone ID; you could fetch either `OffsetDateTime`
  ;; or `ZonedDateTime` using `.getObject`, but fetching the wrong type will result in an Exception, meaning we have
  ;; try both and wrap the first in a try-catch. As far as I know there's now way to tell whether the value has a zone
  ;; offset or ID without first fetching a `TIMESTAMPTZ` object. So to avoid the try-catch we can fetch the
  ;; `TIMESTAMPTZ` and use `.offsetDateTimeValue` instead.
  (fn []
    (when-let [^TIMESTAMPTZ t (.getObject rs i TIMESTAMPTZ)]
      (let [^C3P0ProxyConnection proxy-conn (.. rs getStatement getConnection)
            conn                            (.unwrap proxy-conn OracleConnection)]
        ;; TIMEZONE FIXME - we need to warn if the Oracle JDBC driver is `ojdbc7.jar`, which probably won't have this
        ;; method
        ;;
        ;; I think we can call `(oracle.jdbc.OracleDriver/getJDBCVersion)` and check whether it returns 4.2+
        (.offsetDateTimeValue t conn)))))

(defmethod unprepare/unprepare-value [:oracle OffsetDateTime]
  [_ t]
  (let [s (-> (t/format "yyyy-MM-dd HH:mm:ss.SSS ZZZZZ" t)
              ;; Oracle doesn't like `Z` to mean UTC
              (str/replace #"Z$" "UTC"))]
    (format "timestamp '%s'" s)))

(defmethod unprepare/unprepare-value [:oracle ZonedDateTime]
  [_ t]
  (format "timestamp '%s'" (t/format "yyyy-MM-dd HH:mm:ss.SSS VV" t)))

(defmethod unprepare/unprepare-value [:oracle Instant]
  [driver t]
  (unprepare/unprepare-value driver (t/zoned-date-time t (t/zone-id "UTC"))))

;; Oracle doesn't really support boolean types so use bits instead (See #11592, similar issue for SQL Server)
(defmethod driver.sql/->prepared-substitution [:oracle Boolean]
  [driver bool]
  (driver.sql/->prepared-substitution driver (if bool 1 0)))
