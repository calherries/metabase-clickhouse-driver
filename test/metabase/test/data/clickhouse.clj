(ns metabase.test.data.clickhouse
  "Code for creating / destroying a ClickHouse database from a `DatabaseDefinition`."
  (:require
   [clojure.java.jdbc :as jdbc]
   [clojure.string :as str]
   [clojure.test :refer :all]
   [metabase.driver.ddl.interface :as ddl.i]
   [metabase.driver.sql-jdbc.connection :as sql-jdbc.conn]
   [metabase.driver.sql.util :as sql.u]
   [metabase.models [database :refer [Database]]]
   [metabase.query-processor.test-util :as qp.test]
   [metabase.sync.sync-metadata :as sync-metadata]
   [metabase.test.data
    [interface :as tx]
    [sql-jdbc :as sql-jdbc.tx]]
   [metabase.test.data.sql :as sql.tx]
   [metabase.test.data.sql-jdbc
    [execute :as execute]
    [load-data :as load-data]]
   [toucan2.tools.with-temp :as t2.with-temp]))

(sql-jdbc.tx/add-test-extensions! :clickhouse)

(def default-connection-params
  {:classname "com.clickhouse.jdbc.ClickHouseDriver"
   :subprotocol "clickhouse"
   :subname "//localhost:8123/default"
   :user "default"
   :password ""
   :ssl false
   :use_no_proxy false
   :use_server_time_zone_for_dates true
   :product_name "metabase/1.4.1"})

(defmethod sql.tx/field-base-type->sql-type [:clickhouse :type/Boolean]    [_ _] "Boolean")
(defmethod sql.tx/field-base-type->sql-type [:clickhouse :type/BigInteger] [_ _] "Int64")
(defmethod sql.tx/field-base-type->sql-type [:clickhouse :type/Char]       [_ _] "String")
(defmethod sql.tx/field-base-type->sql-type [:clickhouse :type/Date]       [_ _] "Date")
(defmethod sql.tx/field-base-type->sql-type [:clickhouse :type/DateTime]   [_ _] "DateTime64")
(defmethod sql.tx/field-base-type->sql-type [:clickhouse :type/Float]      [_ _] "Float64")
(defmethod sql.tx/field-base-type->sql-type [:clickhouse :type/Integer]    [_ _] "Int32")
(defmethod sql.tx/field-base-type->sql-type [:clickhouse :type/IPAddress]  [_ _] "IPv4")
(defmethod sql.tx/field-base-type->sql-type [:clickhouse :type/Text]       [_ _] "String")
(defmethod sql.tx/field-base-type->sql-type [:clickhouse :type/UUID]       [_ _] "UUID")
(defmethod sql.tx/field-base-type->sql-type [:clickhouse :type/Time]       [_ _] "DateTime64")

(defmethod tx/sorts-nil-first? :clickhouse [_ _] false)

(defmethod tx/dbdef->connection-details :clickhouse [_ context {:keys [database-name]}]
  (merge
   {:host     (tx/db-test-env-var-or-throw :clickhouse :host "localhost")
    :port     (tx/db-test-env-var-or-throw :clickhouse :port 8123)
    :timezone :America/Los_Angeles}
   (when-let [user (tx/db-test-env-var :clickhouse :user)]
     {:user user})
   (when-let [password (tx/db-test-env-var :clickhouse :password)]
     {:password password})
   (when (= context :db)
     {:db database-name})))

(defmethod sql.tx/qualified-name-components :clickhouse
  ([_ db-name]                       [db-name])
  ([_ db-name table-name]            [db-name table-name])
  ([_ db-name table-name field-name] [db-name table-name field-name]))

(defn- quote-name
  [name]
  (sql.u/quote-name :clickhouse :field (ddl.i/format-name :clickhouse name)))

(def ^:private non-nullable-types ["Array" "Map" "Tuple"])
(defn- disallowed-as-nullable?
  [ch-type]
  (boolean (some #(str/starts-with? ch-type %) non-nullable-types)))

(defn- field->clickhouse-column
  [field]
  (let [{:keys [field-name base-type pk?]} field
        ch-type  (if (map? base-type)
                      (:native base-type)
                      (sql.tx/field-base-type->sql-type :clickhouse base-type))
        col-name (quote-name field-name)
        fmt      (if (or pk? (disallowed-as-nullable? ch-type)) "%s %s" "%s Nullable(%s)")]
    (format fmt col-name ch-type)))

(defn- ->comma-separated-str
  [coll]
  (->> coll
       (interpose ", ")
       (apply str)))

(defmethod sql.tx/create-table-sql :clickhouse
  [_ {:keys [database-name]} {:keys [table-name field-definitions]}]
  (let [table-name     (sql.tx/qualify-and-quote :clickhouse database-name table-name)
        pk-fields      (filter (fn [{:keys [pk?]}] pk?) field-definitions)
        pk-field-names (map #(quote-name (:field-name %)) pk-fields)
        fields         (->> field-definitions
                            (map field->clickhouse-column)
                            (->comma-separated-str))
        order-by       (->comma-separated-str pk-field-names)]
    (format "CREATE TABLE %s (%s)
             ENGINE = MergeTree
             ORDER BY (%s)
             SETTINGS allow_nullable_key=1"
            table-name fields order-by)))

(defmethod execute/execute-sql! :clickhouse [& args]
  (apply execute/sequentially-execute-sql! args))

(defmethod load-data/load-data! :clickhouse [& args]
  (apply load-data/load-data-maybe-add-ids-chunked! args))

(defmethod sql.tx/pk-sql-type :clickhouse [_] "Int32")

(defmethod sql.tx/add-fk-sql :clickhouse [& _] nil) ; TODO - fix me

(defmethod sql.tx/session-schema :clickhouse [_] "default")

(defmethod tx/supports-time-type? :clickhouse [_driver] false)

(defn rows-without-index
  "Remove the Metabase index which is the first column in the result set"
  [query-result]
  (map #(drop 1 %) (qp.test/rows query-result)))

(def ^:private test-db-initialized? (atom false))
(defn create-test-db!
  "Create a ClickHouse database called `metabase_test` and initialize some test data"
  [f]
  (when (not @test-db-initialized?)
    (let [details (tx/dbdef->connection-details :clickhouse :db {:database-name "metabase_test"})]
      (jdbc/with-db-connection
        [conn (sql-jdbc.conn/connection-details->spec :clickhouse (merge {:engine :clickhouse} details))]
        (let [statements (as-> (slurp "modules/drivers/clickhouse/test/metabase/test/data/datasets.sql") s
                           (str/split s #";")
                           (map str/trim s)
                           (filter seq s))]
          (jdbc/db-do-commands conn statements)
          (reset! test-db-initialized? true)))))
  (f))

(defn do-with-test-db
  "Execute a test function using the test dataset"
  {:style/indent 0}
  [f]
  (t2.with-temp/with-temp
    [Database database
     {:engine :clickhouse
      :details (tx/dbdef->connection-details :clickhouse :db {:database-name "metabase_test"})}]
    (sync-metadata/sync-db-metadata! database)
    (f database)))
