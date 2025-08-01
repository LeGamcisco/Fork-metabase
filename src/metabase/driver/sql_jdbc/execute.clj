(ns metabase.driver.sql-jdbc.execute
  "Code related to actually running a SQL query against a JDBC database and for properly encoding/decoding types going
  in and out of the database. Old, non-reducible implementation can be found in
  `metabase.driver.sql-jdbc.execute.old-impl`, which will be removed in a future release; implementations of methods
  for JDBC drivers that do not support `java.time` classes can be found in
  `metabase.driver.sql-jdbc.execute.legacy-impl`. "
  #_{:clj-kondo/ignore [:metabase/modules]}
  (:require
   [clojure.core.async :as a]
   [clojure.java.jdbc :as jdbc]
   [clojure.string :as str]
   [java-time.api :as t]
   [medley.core :as m]
   [metabase.driver :as driver]
   [metabase.driver-api.core :as driver-api]
   [metabase.driver.settings :as driver.settings]
   [metabase.driver.sql-jdbc.connection :as sql-jdbc.conn]
   [metabase.driver.sql-jdbc.execute.diagnostic :as sql-jdbc.execute.diagnostic]
   [metabase.driver.sql-jdbc.execute.old-impl :as sql-jdbc.execute.old]
   [metabase.driver.sql-jdbc.sync.interface :as sql-jdbc.sync.interface]
   [metabase.lib.schema.info :as lib.schema.info]
   [metabase.premium-features.core :refer [defenterprise]]
   [metabase.util :as u]
   [metabase.util.i18n :refer [tru]]
   [metabase.util.log :as log]
   [metabase.util.malli :as mu]
   [metabase.util.performance :as perf]
   [potemkin :as p])
  (:import
   (java.sql
    Connection
    JDBCType
    PreparedStatement
    ResultSet
    ResultSetMetaData
    SQLFeatureNotSupportedException
    Statement
    Types)
   (java.time
    Instant
    LocalDate
    LocalDateTime
    LocalTime
    OffsetDateTime
    OffsetTime
    ZonedDateTime)
   (javax.sql DataSource)))

(set! *warn-on-reflection* true)

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                        SQL JDBC Reducible QP Interface                                         |
;;; +----------------------------------------------------------------------------------------------------------------+

(def ConnectionOptions
  "Malli schema for the options passed to [[do-with-connection-with-options]]."
  [:maybe
   [:map
    ;; a string like 'US/Pacific' or something like that.
    [:session-timezone {:optional true} [:maybe [:ref driver-api/schema.expression.temporal.timezone-id]]]
    ;; whether this Connection should NOT be read-only, e.g. for DDL stuff or inserting data or whatever.
    [:write? {:optional true} [:maybe :boolean]]
    [:download? {:optional true} [:maybe :boolean]]
    ;; don't autoclose the connection
    [:keep-open? {:optional true} [:maybe :boolean]]]])

(defmulti do-with-connection-with-options
  "Fetch a [[java.sql.Connection]] from a `driver`/`db-or-id-or-spec`, and invoke

    (f connection)

  If `db-or-id-or-spec` is a Database or Database ID, the default implementation fetches a pooled connection spec for
  that Database using [[datasource]].

  If `db-or-id-or-spec` is a `clojure.java.jdbc` spec, it fetches a Connection
  using [[clojure.java..jdbc/get-connection]]. Note that this will not be a pooled connection unless your spec is for
  a pooled DataSource.

  `options` matches the [[ConnectionOptions]] schema above.

  * If `:session-timezone` is passed, it should be used to set the Session timezone for the Connection. If not passed,
    leave as-is

  * If `:write?` is NOT passed or otherwise falsey, make the connection read-only if possible; if it is truthy, make
    the connection read-write. Note that this current does not run things inside a transaction automatically; you'll
    have to do that yourself if you want it

  * If `:keep-open?` is passed, the connection will NOT be closed after `(f connection)`. The caller is responsible for
    closing it.

  The normal 'happy path' is more or less

    (with-open [conn (.getConnection (datasource driver db-or-id-or-spec))]
      (set-best-transaction-level! driver conn)
      (set-time-zone-if-supported! driver conn session-timezone)
      (.setReadOnly conn true)
      (.setAutoCommit conn true) ; so the query(s) are not ran inside a transaction
      (.setHoldability conn ResultSet/CLOSE_CURSORS_AT_COMMIT)
      (f conn))

  This default implementation is abstracted out into two functions, [[do-with-resolved-connection]]
  and [[set-default-connection-options!]], that you can use as needed in custom implementations. See various driver
  implementations for examples. You should only set connection options on top-level calls
  to [[do-with-connection-with-options]]; check whether this is a [[recursive-connection?]] before setting options.

  There are two usual ways to set the session timezone if your driver supports them:

  1. Specifying the session timezone based on the value of [[metabase.driver/report-timezone]] as a JDBC connection
     parameter in the JDBC connection spec returned by [[metabase.driver.sql-jdbc.connection/connection-details->spec]].
     If the spec returned by this method changes, connection pools associated with it will be flushed automatically.
     This is the preferred way to set session timezones; if you set them this way, you DO NOT need to implement this
     method unless you need to do something special with regards to setting the transaction level.

  2. Setting the session timezone manually on the [[java.sql.Connection]] returned by [[datasource]] based on the
     value of `session-timezone`.

    2a. The default implementation will do this for you by executing SQL if you implement
        [[set-timezone-sql]].

    2b. You can implement this method, [[do-with-connection-with-options]], yourself and set the timezone however you
        wish. Only set it if `session-timezone` is not `nil`!

   Custom implementations should set transaction isolation to the least-locking level supported by the driver, and make
   connections read-only (*after* setting timezone, if needed)."
  {:added    "0.47.0"
   :arglists '([driver db-or-id-or-spec options f])}
  driver/dispatch-on-initialized-driver
  :hierarchy #'driver/hierarchy)

(defmulti set-parameter
  "Set the `PreparedStatement` parameter at index `i` to `object`. Dispatches on driver and class of `object`. By
  default, this calls `.setObject`, but drivers can override this method to convert the object to a different class or
  set it with a different intended JDBC type as needed."
  {:added "0.34.0" :arglists '([driver prepared-statement i object])}
  (fn [driver _ _ object]
    [(driver/dispatch-on-initialized-driver driver) (class object)])
  :hierarchy #'driver/hierarchy)

;; TODO -- maybe like [[do-with-connection-with-options]] we should replace [[prepared-statment]] and [[statement]]
;; with `do-with-prepared-statement` and `do-with-statement` methods -- that way you can't accidentally forget to wrap
;; things in a `try-catch` and call `.close` (metabase#40010)

(defmulti ^PreparedStatement prepared-statement
  "Create a PreparedStatement with `sql` query, and set any `params`. You shouldn't need to override the default
  implementation for this method; if you do, take care to set options to maximize result set read performance (e.g.
  `ResultSet/TYPE_FORWARD_ONLY`); refer to the default implementation."
  {:added "0.35.0",
   :arglists '(^java.sql.PreparedStatement [driver ^java.sql.Connection connection ^String sql params])}
  driver/dispatch-on-initialized-driver
  :hierarchy #'driver/hierarchy)

;;; TODO -- we should just make this a FEATURE!!!!!1
(defmulti ^Statement statement-supported?
  "Indicates whether the given driver supports creating a java.sql.Statement, via the Connection. By default, this is
  true for all :sql-jdbc drivers.  If the underlying driver does not support Statement creation, override this as
  false."
  {:added "0.39.0", :arglists '([driver])}
  driver/dispatch-on-initialized-driver
  :hierarchy #'driver/hierarchy)

(defmulti ^Statement statement
  "Create a Statement object using the given connection. Only called if statement-supported? above returns true. This
  is to be used to execute native queries, which implies there are no parameters. As with prepared-statement, you
  shouldn't need to override the default implementation for this method; if you do, take care to set options to maximize
  result set read performance (e.g. `ResultSet/TYPE_FORWARD_ONLY`); refer to the default implementation."
  {:added "0.39.0", :arglists '(^java.sql.Statement [driver ^java.sql.Connection connection])}
  driver/dispatch-on-initialized-driver
  :hierarchy #'driver/hierarchy)

(defmulti execute-prepared-statement!
  "Execute a `PreparedStatement`, returning a `ResultSet`. Default implementation simply calls `.executeQuery()`. It is
  unlikely you will need to override this. Prior to 0.39, this was named execute-query!"
  {:added "0.39.0", :arglists '(^java.sql.ResultSet [driver ^java.sql.PreparedStatement stmt])}
  driver/dispatch-on-initialized-driver
  :hierarchy #'driver/hierarchy)

(defmulti execute-statement!
  "Runs a SQL select query with a given `Statement`, returning a `ResultSet`. Default implementation simply calls
  `.execute()` for the given sql on the given statement, and then `.getResultSet()` if that returns true (throwing an
  exception if not). It is unlikely you will need to override this."
  {:added "0.39.0", :arglists '(^java.sql.ResultSet [driver ^java.sql.Statement stmt ^String sql])}
  driver/dispatch-on-initialized-driver
  :hierarchy #'driver/hierarchy)

(defmulti column-metadata
  "Return a sequence of maps containing information about the corresponding columns in query results. The default
  implementation fetches this information via the result set metadata. It is unlikely you will need to override this."
  {:added "0.35.0", :arglists '([driver ^java.sql.ResultSetMetaData rsmeta])}
  driver/dispatch-on-initialized-driver
  :hierarchy #'driver/hierarchy)

(defmulti read-column-thunk
  "Return a zero-arg function that, when called, will fetch the value of the column from the current row. This also
  supports defaults for the entire driver:

    ;; default method for Postgres not covered by any [driver jdbc-type] methods
    (defmethod read-column-thunk :postgres
      ...)"
  {:added "0.35.0", :arglists '([driver ^java.sql.ResultSet rs ^java.sql.ResultSetMetaData rsmeta i])}
  (fn [driver _rs ^ResultSetMetaData rsmeta ^Long col-idx]
    [(driver/dispatch-on-initialized-driver driver) (.getColumnType rsmeta col-idx)])
  :hierarchy #'driver/hierarchy)

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                                  Default Impl                                                  |
;;; +----------------------------------------------------------------------------------------------------------------+

(defn datasource
  "Fetch the connection pool `DataSource` associated with `db-or-id-or-spec`."
  {:added "0.35.0"}
  ^DataSource [db-or-id-or-spec]
  (:datasource (sql-jdbc.conn/db->pooled-connection-spec db-or-id-or-spec)))

(defn datasource-with-diagnostic-info!
  "Fetch the connection pool `DataSource` associated with `database`, while also recording diagnostic info for the
  pool. To be used in conjunction with `sql-jdbc.execute.diagnostic/capturing-diagnostic-info`."
  {:added "0.40.0"}
  ^DataSource [driver db-or-id]
  (let [ds (datasource db-or-id)]
    (sql-jdbc.execute.diagnostic/record-diagnostic-info-for-pool! driver (u/the-id db-or-id) ds)
    ds))

(defn set-time-zone-if-supported!
  "Execute `set-timezone-sql`, if implemented by driver, to set the session time zone. This way of setting the time zone
  should be considered deprecated in favor of implementing `connection-with-timezone` directly."
  {:deprecated "0.35.0"}
  [driver ^Connection conn ^String timezone-id]
  (when timezone-id
    (when-let [format-string (sql-jdbc.execute.old/set-timezone-sql driver)]
      (try
        (let [sql (format format-string (str \' timezone-id \'))]
          (log/debugf "Setting %s database timezone with statement: %s" driver (pr-str sql))
          (try
            (.setReadOnly conn false)
            (catch Throwable e
              (log/debug e "Error setting connection to readwrite")))
          (with-open [stmt (.createStatement conn)]
            (.execute stmt sql)
            (log/tracef "Successfully set timezone for %s database to %s" driver timezone-id)))
        (catch Throwable e
          (log/errorf e "Failed to set timezone '%s' for %s database" timezone-id driver))))))

(defenterprise set-role-if-supported!
  "OSS no-op implementation of `set-role-if-supported!`."
  metabase-enterprise.impersonation.driver
  [_ _ _])

;; TODO - since we're not running the queries in a transaction, does this make any difference at all? (metabase#40012)
(defn set-best-transaction-level!
  "Set the connection transaction isolation level to the least-locking level supported by the DB. See
  https://docs.oracle.com/cd/E19830-01/819-4721/beamv/index.html for an explanation of these levels."
  {:added "0.35.0"}
  [driver ^Connection conn]
  (let [dbmeta (.getMetaData conn)]
    (loop [[[level-name ^Integer level] & more] [[:read-uncommitted Connection/TRANSACTION_READ_UNCOMMITTED]
                                                 [:read-committed   Connection/TRANSACTION_READ_COMMITTED]
                                                 [:repeatable-read  Connection/TRANSACTION_REPEATABLE_READ]]]
      (cond
        (.supportsTransactionIsolationLevel dbmeta level)
        (do
          (log/tracef "Set transaction isolation level for %s database to %s" (name driver) level-name)
          (try
            (.setTransactionIsolation conn level)
            (catch Throwable e
              (log/debugf e "Error setting transaction isolation level for %s database to %s" (name driver) level-name))))

        (seq more)
        (recur more)))))

(def ^:private DbOrIdOrSpec
  [:and
   [:or :int :map]
   [:fn
    ;; can't wrap a java.sql.Connection here because we're not
    ;; responsible for its lifecycle and that means you can't use
    ;; `with-open` on the Connection you'd get from the DataSource
    {:error/message "Cannot be a JDBC spec wrapping a java.sql.Connection"}
    (complement :connection)]])

(mu/defn do-with-resolved-connection-data-source :- (driver-api/instance-of-class DataSource)
  "Part of the default implementation for [[do-with-connection-with-options]]: get an appropriate `java.sql.DataSource`
  for `db-or-id-or-spec`. Not for use with a JDBC spec wrapping a `java.sql.Connection` (a spec with the key
  `:connection`), since we do not have control over its lifecycle and would thus not be able to use [[with-open]] with
  Connections provided by this DataSource."
  {:added "0.47.0", :arglists '(^javax.sql.DataSource [driver db-or-id-or-spec options])}
  [driver           :- :keyword
   db-or-id-or-spec :- DbOrIdOrSpec
   {:keys [^String session-timezone], :as _options} :- ConnectionOptions]
  (if-not (u/id db-or-id-or-spec)
    ;; not a Database or Database ID... this is a raw `clojure.java.jdbc` spec, use that
    ;; directly.
    (reify DataSource
      (getConnection [_this]
        #_{:clj-kondo/ignore [:discouraged-var]}
        (jdbc/get-connection db-or-id-or-spec)))
    ;; otherwise this is either a Database or Database ID.
    (if-let [old-method-impl (get-method
                              #_{:clj-kondo/ignore [:deprecated-var]} sql-jdbc.execute.old/connection-with-timezone
                              driver)]
      ;; use the deprecated impl for `connection-with-timezone` if one exists.
      (do
        (log/warnf "%s is deprecated in Metabase 0.47.0. Implement %s instead."
                   #_{:clj-kondo/ignore [:deprecated-var]}
                   'connection-with-timezone
                   'do-with-connection-with-options)
        ;; for compatibility, make sure we pass it an actual Database instance.
        (let [database (if (integer? db-or-id-or-spec)
                         (driver-api/with-metadata-provider db-or-id-or-spec
                           (driver-api/database (driver-api/metadata-provider)))
                         db-or-id-or-spec)]
          (reify DataSource
            (getConnection [_this]
              (old-method-impl driver database session-timezone)))))
      (datasource-with-diagnostic-info! driver db-or-id-or-spec))))

(def ^:private ^:dynamic ^{:added "0.47.0"} *connection-recursion-depth*
  "In recursive calls to [[do-with-connection-with-options]] we don't want to set options AGAIN, because this might
  break things. For example in a top-level `:write?` call, we might disable auto-commit and run things in a
  transaction; a read-only call inside of this transaction block should not go in and change the connection to be
  auto-commit. So only set options at the top-level call, and use this to keep track of whether we're at the top level
  or not.

  This gets incremented inside [[do-with-resolved-connection]], so the top level call with have a depth of `0`, a
  nested call will get `1`, and so forth. This is done this way and inside [[do-with-resolved-connection]]
  and [[set-default-connection-options!]] so drivers that implement "
  -1)

(defn recursive-connection?
  "Whether or not we are in a recursive call to [[do-with-connection-with-options]]. If we are, you shouldn't set
  Connection options AGAIN, as that may override previous options that we don't want to override."
  {:added "0.47.0"}
  []
  (pos? *connection-recursion-depth*))

(mu/defn do-with-resolved-connection
  "Execute

    (f ^java.sql.Connection conn)

  with a resolved JDBC connection. Part of the default implementation for [[do-with-connection-with-options]].
  Generally does not set any `options`, but may set session-timezone if `driver` implements the
  deprecated [[sql-jdbc.execute.old/connection-with-timezone]] method."
  {:added "0.47.0"}
  [driver           :- :keyword
   db-or-id-or-spec :- [:or :int :map]
   options          :- ConnectionOptions
   f                :- fn?]
  (binding [*connection-recursion-depth* (inc *connection-recursion-depth*)]
    (if-let [conn (:connection db-or-id-or-spec)]
      (f conn)
      (let [get-conn (^:once fn* [] (.getConnection (do-with-resolved-connection-data-source driver db-or-id-or-spec options)))]
        (if (:keep-open? options)
          (f (get-conn))
          (with-open [conn ^Connection (get-conn)]
            (f conn)))))))

(mu/defn set-default-connection-options!
  "Part of the default implementation of [[do-with-connection-with-options]]: set options for a newly fetched
  Connection."
  {:added "0.47.0"}
  [driver                                                 :- :keyword
   db-or-id-or-spec
   ^Connection conn                                       :- (driver-api/instance-of-class Connection)
   {:keys [^String session-timezone write?], :as options} :- ConnectionOptions]
  (when-let [db (cond
                  ;; id?
                  (integer? db-or-id-or-spec)
                  (driver-api/with-metadata-provider db-or-id-or-spec
                    (driver-api/database (driver-api/metadata-provider)))
                  ;; db?
                  (u/id db-or-id-or-spec)     db-or-id-or-spec
                  ;; otherwise it's a spec and we can't get the db
                  :else nil)]
    (set-role-if-supported! driver conn db)
    (driver/set-database-used! driver conn db))
  (when-not (recursive-connection?)
    (log/tracef "Setting default connection options with options %s" (pr-str options))
    (set-best-transaction-level! driver conn)
    (set-time-zone-if-supported! driver conn session-timezone)
    (let [read-only? (not write?)]
      (try
        ;; Setting the connection to read-only does not prevent writes on some databases, and is meant
        ;; to be a hint to the driver to enable database optimizations
        ;; See https://docs.oracle.com/javase/8/docs/api/java/sql/Connection.html#setReadOnly-boolean-
        (log/trace (pr-str (list '.setReadOnly 'conn read-only?)))
        (.setReadOnly conn read-only?)
        (catch Throwable e
          (log/debugf e "Error setting connection readOnly to %s" (pr-str read-only?)))))
    ;; If this is (supposedly) a read-only connection, we would prefer enable auto-commit
    ;; so this IS NOT ran inside of a transaction, but without transaction the read-only
    ;; flag has no effect for most of the drivers.
    ;;
    ;; TODO -- for `write?` connections, we should probably disable autoCommit and then manually call `.commit` at after
    ;; `f`... we need to check and make sure that won't mess anything up, since some existing code is already doing it
    ;; manually. (metabase#40014)
    (cond (not (or write?
                   (and (-> options :download?) (= driver :postgres))))
          (try
            (log/trace (pr-str '(.setAutoCommit conn true)))
            (.setAutoCommit conn true)
            (catch Throwable e
              (log/debug e "Error enabling connection autoCommit")))

          ;; todo (dan 7/11/25): fixing straightforward postgres oom on downloads in #60733, but seems like write? is
          ;; not set here. Note this is explicitly silent when `write?`. Lots of tests fail with autocommit false
          ;; there.
          (and (-> options :download?) (isa? driver/hierarchy driver :postgres))
          (try
            (log/trace (pr-str '(.setAutoCommit conn false)))
            (.setAutoCommit conn false)
            (catch Throwable e
              (log/debug e "Error setting connection autoCommit to false"))))
    (try
      (log/trace (pr-str '(.setHoldability conn ResultSet/CLOSE_CURSORS_AT_COMMIT)))
      (.setHoldability conn ResultSet/CLOSE_CURSORS_AT_COMMIT)
      (catch Throwable e
        (log/debug e "Error setting default holdability for connection")))))

(defmethod do-with-connection-with-options :sql-jdbc
  [driver db-or-id-or-spec options f]
  (do-with-resolved-connection
   driver
   db-or-id-or-spec
   options
   (fn [^Connection conn]
     (set-default-connection-options! driver db-or-id-or-spec conn options)
     (f conn))))

;; TODO - would a more general method to convert a parameter to the desired class (and maybe JDBC type) be more
;; useful? Then we can actually do things like log what transformations are taking place

(defn- set-object
  ([^PreparedStatement prepared-statement, ^Integer index, object]
   (log/tracef "(set-object prepared-statement %d ^%s %s)" index (some-> object class .getName) (pr-str object))
   (.setObject prepared-statement index object))

  ([^PreparedStatement prepared-statement, ^Integer index, object, ^Integer target-sql-type]
   (log/tracef "(set-object prepared-statement %d ^%s %s java.sql.Types/%s)" index (some-> object class .getName)
               (pr-str object) (.getName (JDBCType/valueOf target-sql-type)))
   (.setObject prepared-statement index object target-sql-type)))

(defmethod set-parameter :default
  [_ prepared-statement i object]
  (set-object prepared-statement i object))

(defmethod set-parameter [::driver/driver LocalDate]
  [_ prepared-statement i t]
  (set-object prepared-statement i t Types/DATE))

(defmethod set-parameter [::driver/driver LocalTime]
  [_ prepared-statement i t]
  (set-object prepared-statement i t Types/TIME))

(defmethod set-parameter [::driver/driver LocalDateTime]
  [_ prepared-statement i t]
  (set-object prepared-statement i t Types/TIMESTAMP))

(defmethod set-parameter [::driver/driver OffsetTime]
  [_ prepared-statement i t]
  (set-object prepared-statement i t Types/TIME_WITH_TIMEZONE))

(defmethod set-parameter [::driver/driver OffsetDateTime]
  [_ prepared-statement i t]
  (set-object prepared-statement i t Types/TIMESTAMP_WITH_TIMEZONE))

(defmethod set-parameter [::driver/driver ZonedDateTime]
  [_ prepared-statement i t]
  (set-object prepared-statement i t Types/TIMESTAMP_WITH_TIMEZONE))

(defmethod set-parameter [::driver/driver Instant]
  [driver prepared-statement i t]
  (set-parameter driver prepared-statement i (t/offset-date-time t (t/zone-offset 0))))

;; TODO - this might not be needed for all drivers. It is at least needed for H2 and Postgres. Not sure which, if any
;; JDBC drivers support `ZonedDateTime`.
(defmethod set-parameter [::driver/driver ZonedDateTime]
  [driver prepared-statement i t]
  (set-parameter driver prepared-statement i (t/offset-date-time t)))

(defn set-parameters!
  "Set parameters for the prepared statement by calling `set-parameter` for each parameter."
  {:added "0.35.0"}
  [driver stmt params]
  (when (< (try (.. ^PreparedStatement stmt getParameterMetaData getParameterCount)
                (catch Throwable _ (count params)))
           (count params))
    (throw (ex-info (tru "It looks like we got more parameters than we can handle, remember that parameters cannot be used in comments or as identifiers.")
                    {:driver driver
                     :type   driver-api/qp.error-type.driver
                     :statement (str/split-lines (str stmt))
                     :params params})))
  (dorun
   (map-indexed
    (fn [i param]
      (log/tracef "Set param %d -> %s" (inc i) (pr-str param))
      (set-parameter driver stmt (inc i) param))
    params)))

(defmethod prepared-statement :sql-jdbc
  [driver ^Connection conn ^String sql params]
  (let [stmt (.prepareStatement conn
                                sql
                                ResultSet/TYPE_FORWARD_ONLY
                                ResultSet/CONCUR_READ_ONLY
                                ResultSet/CLOSE_CURSORS_AT_COMMIT)]
    (try
      (try
        (.setFetchDirection stmt ResultSet/FETCH_FORWARD)
        (catch Throwable e
          (log/debug e "Error setting prepared statement fetch direction to FETCH_FORWARD")))
      (try
        (when (zero? (.getFetchSize stmt))
          (.setFetchSize stmt (driver.settings/sql-jdbc-fetch-size)))
        (catch Throwable e
          (log/debug e "Error setting prepared statement fetch size to fetch-size")))
      (set-parameters! driver stmt params)
      stmt
      (catch Throwable e
        (.close stmt)
        (throw e)))))

;; by default, drivers support .createStatement
(defmethod statement-supported? :sql-jdbc
  [_]
  true)

(defmethod statement :sql-jdbc
  [_ ^Connection conn]
  (let [stmt (.createStatement conn
                               ResultSet/TYPE_FORWARD_ONLY
                               ResultSet/CONCUR_READ_ONLY
                               ResultSet/CLOSE_CURSORS_AT_COMMIT)]
    (try
      (try
        (.setFetchDirection stmt ResultSet/FETCH_FORWARD)
        (catch Throwable e
          (log/debug e "Error setting statement fetch direction to FETCH_FORWARD")))
      (try
        (when (zero? (.getFetchSize stmt))
          (.setFetchSize stmt (driver.settings/sql-jdbc-fetch-size)))
        (catch Throwable e
          (log/debug e "Error setting statement fetch size to fetch-size")))
      stmt
      (catch Throwable e
        (.close stmt)
        (throw e)))))

(defn- wire-up-canceled-chan-to-cancel-Statement!
  "If `canceled-chan` gets a message, cancel the Statement `stmt`."
  [^Statement stmt canceled-chan]
  (when canceled-chan
    (a/go
      (when (a/<! canceled-chan)
        (when-not (.isClosed stmt)
          (log/debug "Query canceled, calling Statement.cancel()")
          (.cancel stmt))))))

(defn- prepared-statement*
  ^PreparedStatement [driver conn sql params canceled-chan]
  ;; sometimes preparing the statement fails, usually if the SQL syntax is invalid.
  (doto (try
          (prepared-statement driver conn sql params)
          (catch Throwable e
            (throw (ex-info (tru "Error preparing statement: {0}" (ex-message e))
                            {:driver driver
                             :type   driver-api/qp.error-type.driver
                             :sql    (str/split-lines (driver/prettify-native-form driver sql))
                             :params params}
                            e))))
    (wire-up-canceled-chan-to-cancel-Statement! canceled-chan)))

(defn- use-statement? [driver params]
  (and (statement-supported? driver) (empty? params)))

(defn- statement* ^Statement [driver conn canceled-chan]
  (doto (statement driver conn)
    (wire-up-canceled-chan-to-cancel-Statement! canceled-chan)))

(defn statement-or-prepared-statement
  "Create a statement or a prepared statement. Should be called from [[with-open]]."
  ^Statement [driver conn sql params canceled-chan]
  (if (use-statement? driver params)
    (statement* driver conn canceled-chan)
    (prepared-statement* driver conn sql params canceled-chan)))

(defmethod execute-prepared-statement! :sql-jdbc
  [_ ^PreparedStatement stmt]
  (.executeQuery stmt))

(defmethod execute-statement! :sql-jdbc
  [driver ^Statement stmt ^String sql]
  (if (.execute stmt sql)
    (.getResultSet stmt)
    (throw (ex-info (str (tru "Select statement did not produce a ResultSet for native query"))
                    {:sql sql :driver driver}))))

(defn- execute-statement-or-prepared-statement! ^ResultSet [driver ^Statement stmt max-rows params sql]
  (let [st (doto stmt (.setMaxRows max-rows))]
    (if (use-statement? driver params)
      (execute-statement! driver st sql)
      (execute-prepared-statement! driver st))))

(defmethod read-column-thunk :default
  [driver ^ResultSet rs rsmeta ^long i]
  (let [driver-default-method (get-method read-column-thunk driver)]
    (if-not (= driver-default-method (get-method read-column-thunk :default))
      ^{:name (format "(read-column-thunk %s)" driver)} (driver-default-method driver rs rsmeta i)
      ^{:name (format "(.getObject rs %d)" i)} (fn []
                                                 (.getObject rs i)))))

(defn- get-object-of-class-thunk [^ResultSet rs, ^long i, ^Class klass]
  ^{:name (format "(.getObject rs %d %s)" i (.getCanonicalName klass))}
  (fn []
    (.getObject rs i klass)))

(defmethod read-column-thunk [:sql-jdbc Types/TIMESTAMP]
  [_ rs _ i]
  (get-object-of-class-thunk rs i java.time.LocalDateTime))

(defmethod read-column-thunk [:sql-jdbc Types/ARRAY]
  [_driver ^java.sql.ResultSet rs _rsmeta ^Integer i]
  (fn []
    (when-let [obj (.getObject rs i)]
      (vec (.getArray ^java.sql.Array obj)))))

(defmethod read-column-thunk [:sql-jdbc Types/TIMESTAMP_WITH_TIMEZONE]
  [_ rs _ i]
  (get-object-of-class-thunk rs i java.time.OffsetDateTime))

(defmethod read-column-thunk [:sql-jdbc Types/DATE]
  [_ rs _ i]
  (get-object-of-class-thunk rs i java.time.LocalDate))

(defmethod read-column-thunk [:sql-jdbc Types/TIME]
  [_ rs _ i]
  (get-object-of-class-thunk rs i java.time.LocalTime))

(defmethod read-column-thunk [:sql-jdbc Types/TIME_WITH_TIMEZONE]
  [_ rs _ i]
  (get-object-of-class-thunk rs i java.time.OffsetTime))

(defn- column-range [^ResultSetMetaData rsmeta]
  (range 1 (inc (.getColumnCount rsmeta))))

(defn- log-readers [driver ^ResultSetMetaData rsmeta fns]
  (log/trace
   (str/join
    "\n"
    (for [^Integer i (column-range rsmeta)]
      (format "Reading %s column %d %s (JDBC type: %s, DB type: %s) with %s"
              driver
              i
              (pr-str (.getColumnName rsmeta i))
              (or (u/ignore-exceptions
                    (.getName (JDBCType/valueOf (.getColumnType rsmeta i))))
                  (.getColumnType rsmeta i))
              (.getColumnTypeName rsmeta i)
              (let [f (nth fns (dec i))]
                (or (:name (meta f))
                    f)))))))

(defn row-thunk
  "Returns a thunk that can be called repeatedly to get the next row in the result set, using appropriate methods to
  fetch each value in the row. Returns `nil` when the result set has no more rows."
  [driver ^ResultSet rs ^ResultSetMetaData rsmeta]
  (let [fns (mapv #(read-column-thunk driver rs rsmeta (long %))
                  (column-range rsmeta))]
    (log-readers driver rsmeta fns)
    (let [thunk (if (seq fns)
                  (perf/juxt* fns)
                  (constantly []))]
      (fn row-thunk* []
        (when (.next rs)
          (thunk))))))

(defn- resolve-missing-base-types
  [driver metadatas]
  (if (driver-api/initialized?)
    (let [missing (keep (fn [{:keys [database_type base_type]}]
                          (when-not base_type
                            database_type))
                        metadatas)
          lookup (driver/dynamic-database-types-lookup
                  driver (driver-api/database (driver-api/metadata-provider)) missing)]
      (if (seq lookup)
        (mapv (fn [{:keys [database_type base_type] :as metadata}]
                (if-not base_type
                  (m/assoc-some metadata :base_type (lookup database_type))
                  metadata))
              metadatas)
        metadatas))
    metadatas))

(defmethod column-metadata :sql-jdbc
  [driver ^ResultSetMetaData rsmeta]
  (->> (mapv
        (fn [^Long i]
          (let [col-name     (.getColumnLabel rsmeta i)
                db-type-name (.getColumnTypeName rsmeta i)
                base-type    (sql-jdbc.sync.interface/database-type->base-type driver (keyword db-type-name))]
            (log/tracef "Column %d '%s' is a %s which is mapped to base type %s for driver %s\n"
                        i col-name db-type-name base-type driver)
            {:name      col-name
             ;; TODO - disabled for now since it breaks a lot of tests. We can re-enable it when the tests are in a better
             ;; state
             #_:original_name #_(.getColumnName rsmeta i)
             #_:jdbc_type #_(u/ignore-exceptions
                              (.getName (JDBCType/valueOf (.getColumnType rsmeta i))))
             :base_type     base-type
             :database_type db-type-name}))
        (column-range rsmeta))
       (resolve-missing-base-types driver)
       (mapv (fn [{:keys [base_type] :as metadata}]
               (if (nil? base_type)
                 (assoc metadata :base_type :type/*)
                 metadata)))))

(defn reducible-rows
  "Returns an object that can be reduced to fetch the rows and columns in a `ResultSet` in a driver-specific way (e.g.
  by using `read-column-thunk` to fetch values).

  The three-arity was added in 0.48.0"
  {:added "0.35.0"}
  ([driver ^ResultSet rs ^ResultSetMetaData rsmeta]
   (let [row-thunk (row-thunk driver rs rsmeta)]
     (driver-api/reducible-rows row-thunk)))

  ([driver ^ResultSet rs ^ResultSetMetaData rsmeta canceled-chan]
   (let [row-thunk (row-thunk driver rs rsmeta)]
     (driver-api/reducible-rows row-thunk canceled-chan))))

(defmulti inject-remark
  "Injects the remark into the SQL query text."
  {:added "0.48.0", :arglists '([driver sql remark])}
  driver/dispatch-on-initialized-driver
  :hierarchy #'driver/hierarchy)

;  Combines the original SQL query with query remarks. Most databases using sql-jdbc based drivers support prepending the
;  remark to the SQL statement, so we have it as a default. However, some drivers do not support it, so we allow it to
;  be overriden.
(defmethod inject-remark :default
  [_ sql remark]
  (str "-- " remark "\n" sql))

(mu/defn- download? :- :boolean
  [context :- [:maybe ::lib.schema.info/context]]
  (let [download-contexts #{:csv-download :xlsx-download :json-download
                            :public-csv-download :public-xlsx-download :public-json-download
                            :embedded-csv-download :embedded-xlsx-download :embedded-json-download}]
    (boolean (download-contexts context))))

(defn execute-reducible-query
  "Default impl of [[metabase.driver/execute-reducible-query]] for sql-jdbc drivers."
  {:added "0.35.0", :arglists '([driver query context respond])}
  [driver {{sql :query, params :params} :native, :as outer-query} _context respond]
  {:pre [(string? sql) (seq sql)]}
  (let [database (driver-api/database (driver-api/metadata-provider))
        sql      (if (get-in database [:details :include-user-id-and-hash] true)
                   (->> (driver-api/query->remark driver outer-query)
                        (inject-remark driver sql))
                   sql)
        max-rows (driver-api/determine-query-max-rows outer-query)]
    (do-with-connection-with-options
     driver
     (driver-api/database (driver-api/metadata-provider))
     {:session-timezone (driver-api/report-timezone-id-if-supported driver (driver-api/database (driver-api/metadata-provider)))
      :download? (download? (-> outer-query :info :context))}
     (fn [^Connection conn]
       (with-open [stmt          (statement-or-prepared-statement driver conn sql params (driver-api/canceled-chan))
                   ^ResultSet rs (try
                                   (execute-statement-or-prepared-statement! driver stmt max-rows params sql)
                                   (catch Throwable e
                                     (throw (ex-info (tru "Error executing query: {0}" (ex-message e))
                                                     {:driver driver
                                                      :sql    (str/split-lines (driver/prettify-native-form driver sql))
                                                      :params params
                                                      :type   driver-api/qp.error-type.invalid-query}
                                                     e))))]
         (let [rsmeta           (.getMetaData rs)
               results-metadata {:cols (column-metadata driver rsmeta)}]
           (try (respond results-metadata (reducible-rows driver rs rsmeta (driver-api/canceled-chan)))
                ;; Following cancels the statment on the dbms side.
                ;; It avoids blocking `.close` call, in case we reduced the results subset eg. by means of
                ;; [[metabase.query-processor.middleware.limit/limit-xform]] middleware, while statment is still
                ;; in progress. This problem was encountered on Redshift. For details see the issue #39018.
                ;; It also handles situation where query is canceled through [[driver-api/canceled-chan]] (#41448).
                (finally
                  ;; TODO: Following `when` is in place just to find out if vertica is flaking because of cancelations.
                  ;;       It should be removed afterwards!
                  (when-not (= :vertica driver)
                    (try (.cancel stmt)
                         (catch SQLFeatureNotSupportedException _
                           (log/warnf "Statemet's `.cancel` method is not supported by the `%s` driver."
                                      (name driver)))
                         (catch Throwable _
                           (log/warn "Statement cancelation failed."))))))))))))

(defn reducible-query
  "Returns a reducible collection of rows as maps from `db` and a given SQL query. This is similar to [[jdbc/reducible-query]] but reuses the
  driver-specific configuration for the Connection and Statement/PreparedStatement. This is slightly different from [[execute-reducible-query]]
  in that it is not intended to be used as part of middleware. Keywordizes column names. "
  {:added "0.49.0", :arglists '([db [sql & params]])}
  [db [sql & params]]
  (let [driver (:engine db)]
    (reify clojure.lang.IReduceInit
      (reduce [_ rf init]
        (do-with-connection-with-options
         driver
         db
         nil
         (fn [^Connection conn]
           (with-open [stmt          (statement-or-prepared-statement driver conn sql params nil)
                       ^ResultSet rs (try
                                       (let [max-rows 0] ; 0 means no limit
                                         (execute-statement-or-prepared-statement! driver stmt max-rows params sql))
                                       (catch Throwable e
                                         (throw (ex-info (tru "Error executing query: {0}" (ex-message e))
                                                         {:driver driver
                                                          :sql    (str/split-lines (driver/prettify-native-form driver sql))
                                                          :params params}
                                                         e))))]
             ;; TODO - we should probably be using [[reducible-rows]] instead to convert to the correct types
             (reduce rf init (jdbc/reducible-result-set rs {})))))))))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                                 Actions Stuff                                                  |
;;; +----------------------------------------------------------------------------------------------------------------+

(defmethod driver/execute-write-query! :sql-jdbc
  [driver {{sql :query, :keys [params]} :native}]
  {:pre [(string? sql)]}
  (try
    (do-with-connection-with-options
     driver
     (driver-api/database (driver-api/metadata-provider))
     {:write? true
      :session-timezone (driver-api/report-timezone-id-if-supported driver (driver-api/database (driver-api/metadata-provider)))}
     (fn [^Connection conn]
       (with-open [stmt (statement-or-prepared-statement driver conn sql params nil)]
         {:rows-affected (if (instance? PreparedStatement stmt)
                           (.executeUpdate ^PreparedStatement stmt)
                           (.executeUpdate stmt sql))})))
    (catch Throwable e
      (throw (ex-info (tru "Error executing write query: {0}" (ex-message e))
                      {:sql sql, :params params, :type driver-api/qp.error-type.invalid-query}
                      e)))))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                       Convenience Imports from Old Impl                                        |
;;; +----------------------------------------------------------------------------------------------------------------+

#_{:clj-kondo/ignore [:deprecated-var]}
(p/import-vars
 [sql-jdbc.execute.old
  connection-with-timezone
  set-timezone-sql])

(def ^:private ^:dynamic
  ^{:doc "Dynamic context for resilient connection management. Contains a map with:
          - :db - the database instance, used to create new connections
          - :conn - the current resilient connection (if any)
          Used to enable connection recovery and reuse within [[driver/do-with-resilient-connection]]"}
  *resilient-connection-ctx*
  nil)

(defmethod driver/do-with-resilient-connection :sql-jdbc
  [driver db f]
  (binding [*resilient-connection-ctx* {:db db}]
    (try
      (f driver db)
      (finally
        (when-let [conn ^Connection (:conn *resilient-connection-ctx*)]
          (try (.close conn)
               (catch Throwable _)))))))

(defn is-conn-open?
  "Checks if the conn is open.
   If `:check-valid?` is passed, ensures the connection is actually usable. If it isn't, closes it"
  [^Connection conn & {:keys [check-valid?]}]
  (let [is-open (not (.isClosed conn))]
    (if (and is-open check-valid?)
      (try
        (if (.isValid conn 5)
          is-open
          ;; if the connection is not valid anymore but hasn't been closed by the driver,
          ;; we close it so that [[sql-jdbc.execute/try-ensure-open-conn!]] can attempt to reopen it.
          ;; we've observed the snowflake driver hit this case
          (do (.close conn) false))
        (catch Throwable _ is-open))
      is-open)))

(defn try-ensure-open-conn!
  "Ensure that a connection is open and usable, reconnecting if necessary and possible.

  If the given connection is already open, just returns it. If the connection
  is closed and we're in [[driver/do-with-resilient-connection]] context,
  attempts to reuse an existing reconnection or establish a new one.
  The connection will automatically be closed when exiting the [[driver/do-with-resilient-connection]]
  context.

  The `:force-context-local?` option forces creation of a new context-local
  connection even if the outer connection is still open.

  ConnectionOptions can be passed as `opts`, but `:keep-open?` will always be
  overridden to `true`.

  Not thread-safe."

  ^Connection [driver ^Connection connection & {:keys [force-context-local?] :as opts}]
  (cond
    (and (not force-context-local?) (is-conn-open? connection))
    connection

    (not (thread-bound? #'*resilient-connection-ctx*))
    (do
      (log/warn "Requesting a resilient connection, but we're not in a resilient context")
      connection)

    (some-> *resilient-connection-ctx* :conn is-conn-open?)
    (:conn *resilient-connection-ctx*)

    :else
    ;; we locally reset `*connection-recursion-depth*` so that the new connection
    ;; gets all the options set as we'd expect, else we may be obtaining a badly
    ;; configured connection (see: https://github.com/metabase/metabase/pull/59999)
    (binding [*connection-recursion-depth* -1]
      (try
        (log/info "Obtaining a fresh resilient connection")
        (let [{:keys [db]} *resilient-connection-ctx*
              conn (do-with-connection-with-options driver db (merge opts {:keep-open? true}) identity)]
          (set! *resilient-connection-ctx* {:db db :conn conn})
          conn)
        (catch Throwable e
          (log/warn e "Failed obtaining a new resilient connection")
          connection)))))
