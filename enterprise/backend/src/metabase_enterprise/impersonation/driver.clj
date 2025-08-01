(ns metabase-enterprise.impersonation.driver
  (:require
   [clojure.set :as set]
   [clojure.string :as str]
   [metabase-enterprise.sandbox.api.util :as sandbox.api.util]
   [metabase.api.common :as api]
   [metabase.driver :as driver]
   [metabase.driver.sql :as driver.sql]
   [metabase.driver.util :as driver.u]
   [metabase.premium-features.core :as premium-features :refer [defenterprise]]
   [metabase.util :as u]
   [metabase.util.i18n :refer [tru]]
   [metabase.util.log :as log]
   [metabase.warehouse-schema.models.field :as field]
   [toucan2.core :as t2])
  (:import
   (java.sql Connection)))

(set! *warn-on-reflection* true)

(defn- sandboxed?
  [database-or-id]
  (when api/*current-user-id*
    (sandbox.api.util/sandboxed-user-for-db? (u/id database-or-id))))

(defn- enforce-impersonations?
  "Given a list of Connection Impersonation policies and a list of permission group IDs that the current user is in,
  returns a Boolean indicating whether the policies should be enforced. They are not enforced if any of the *other*
  groups the user is in provide unrestricted data access to the DB."
  [db-or-id impersonations group-ids]
  (let [non-impersonated-group-ids (set/difference (set group-ids)
                                                   (set (map :group_id impersonations)))
        perm-values                (when (seq non-impersonated-group-ids)
                                     (t2/select-fn-set :perm_value
                                                       :model/DataPermissions
                                                       {:where
                                                        [:and
                                                         [:= :db_id (u/the-id db-or-id)]
                                                         [:= :table_id nil]
                                                         [:= :perm_type (u/qualified-name :perms/view-data)]
                                                         [:in :group_id non-impersonated-group-ids]]}))]
    ;; Just check if any other non-impersonated groups have unrestricted access to the DB. We don't need to worry
    ;; about block permissions here because it would have been enforced earlier in the QP middleware stack.
    (not (contains? perm-values :unrestricted))))

(defn impersonation-enabled-for-db?
  "Is impersonation enabled for the given database, for any groups?"
  [db-or-id]
  (boolean
   (when (and db-or-id (premium-features/enable-advanced-permissions?))
     (t2/exists? :model/ConnectionImpersonation :db_id (u/id db-or-id)))))

(defn enforced-impersonations-for-db
  "Returns the connection impersonation policies which should be enforced for the provided DB for the current user, if
  one exists. Returns `nil` if no policies exist, or none should be enforced for the current user.

  Throws if an enforced sandbox conflicts with any impersonations.

  Note: this returns a list of policies. Typically a user should only be in one group with an impersonation policy at a time,
  but there may be policies in multiple groups if they use the same user attribute."
  [db-or-id]
  (let [group-ids           (t2/select-fn-set :group_id :model/PermissionsGroupMembership :user_id api/*current-user-id*)
        conn-impersonations (when (seq group-ids)
                              (t2/select :model/ConnectionImpersonation
                                         :group_id [:in group-ids]
                                         :db_id (u/the-id db-or-id)))]
    (when (and (seq conn-impersonations) (sandboxed? db-or-id))
      (throw (ex-info (tru "Conflicting sandboxing and impersonation policies found.")
                      {:user-id api/*current-user-id*
                       :database-id (u/id db-or-id)})))
    (when (and (seq conn-impersonations)
               (enforce-impersonations? db-or-id conn-impersonations group-ids))
      conn-impersonations)))

(defn connection-impersonation-role
  "Fetches the database role that should be used for the current user, if connection impersonation is in effect.
  Returns `nil` if connection impersonation should not be used for the current user. Throws an exception if multiple
  conflicting connection impersonation policies are found, or the role is not a single string."
  [database-or-id]
  (when (and database-or-id (not api/*is-superuser?*))
    (let [conn-impersonations  (enforced-impersonations-for-db database-or-id)
          role-attributes      (set (map :attribute conn-impersonations))]
      (when conn-impersonations
        (when (> (count role-attributes) 1)
          (throw (ex-info (tru "Multiple conflicting connection impersonation policies found for current user")
                          {:user-id api/*current-user-id*
                           :conn-impersonations conn-impersonations})))
        (when (not-empty role-attributes)
          (let [conn-impersonation (first conn-impersonations)
                role-attribute     (:attribute conn-impersonation)
                user-attributes    (api/current-user-attributes)
                role               (get user-attributes role-attribute)]
            (cond
              (nil? role)
              (throw (ex-info (tru "User does not have attribute required for connection impersonation.")
                              {:user-id api/*current-user-id*
                               :conn-impersonations conn-impersonations}))

              (or (not (string? role))
                  (str/blank? role))
              (throw (ex-info (tru "Connection impersonation attribute is invalid: role must be a single non-empty string.")
                              {:user-id api/*current-user-id*
                               :conn-impersonations conn-impersonations}))
              :else
              role)))))))

(defenterprise hash-input-for-impersonation
  "Returns a hash-key for FieldValues if the current user uses impersonation for the database."
  :feature :advanced-permissions
  [field]
  ;; Include the role in the hash key, so that we can cache the results of the query for each role.
  (let [db-id (field/field-id->database-id (u/the-id field))]
    (when-let [role (and api/*current-user-id* (connection-impersonation-role db-id))]
      {:impersonation-role role})))

(def ^:dynamic *impersonation-role*
  "Set by Impersonation middleware, via the query processor, to define the role that should be used by
  `set-role-if-supported!`. If not set (for example, when we're not in the context of a query) we'll compute it
  ourselves with `connection-impersonation-role`."
  nil)

(defenterprise set-role-if-supported!
  "Executes a `USE ROLE` or similar statement on the given connection, if connection impersonation is enabled for the
  given driver. For these drivers, the role is set to either the default role, or to a specific role configured for
  the current user, depending on the connection impersonation settings. This is a no-op for databases that do not
  support connection impersonation, or for non-EE instances."
  :feature :advanced-permissions
  [driver ^Connection conn database]
  (when (driver.u/supports? driver :connection-impersonation database)
    (try
      (let [enabled?           (impersonation-enabled-for-db? database)
            default-role       (driver.sql/default-database-role driver database)
            ;; *impersonation-role* is bound by middleware in the context of a query - otherwise, we can calculate it ourselves.
            impersonation-role (or *impersonation-role*
                                   (connection-impersonation-role database))]
        (when (and enabled? (not default-role))
          (throw (ex-info (tru "Connection impersonation is enabled for this database, but no default role is found")
                          {:user-id api/*current-user-id*
                           :database-id (u/id database)})))
        (when-let [role (or impersonation-role default-role)]
          ;; If impersonation is not enabled for any groups but we have a default role, we should still set it, just
          ;; in case impersonation used to be enabled and the connection still uses an impersonated role.
          (driver/set-role! driver conn role)))
      (catch Throwable e
        (log/debug e "Error setting role on connection")
        (throw e)))))
