(ns metabase-enterprise.sso.integrations.sso-utils
  "Functions shared by the various SSO implementations"
  (:require
   [metabase-enterprise.sso.settings :as sso-settings]
   [metabase.api.common :as api]
   [metabase.appearance.core :as appearance]
   [metabase.channel.email.messages :as messages]
   [metabase.events.core :as events]
   [metabase.notification.core :as notification]
   [metabase.sso.core :as sso]
   [metabase.system.core :as system]
   [metabase.util :as u]
   [metabase.util.i18n :refer [trs tru]]
   [metabase.util.log :as log]
   [metabase.util.malli :as mu]
   [metabase.util.malli.schema :as ms]
   [toucan2.core :as t2])
  (:import
   (clojure.lang ExceptionInfo)
   (java.net URI URISyntaxException)))

(set! *warn-on-reflection* true)

(def ^:private UserAttributes
  [:map {:closed true}
   [:first_name       [:maybe ms/NonBlankString]]
   [:last_name        [:maybe ms/NonBlankString]]
   [:email            ms/Email]
   ;; TODO - we should avoid hardcoding this to make it easier to add new integrations. Maybe look at something like
   ;; the keys of `(methods sso/sso-get)`
   [:sso_source       [:enum :saml :jwt]]
   [:login_attributes [:maybe :map]]
   [:jwt_attributes   {:optional true} [:maybe :map]]])

(defn- maybe-throw-user-provisioning
  [user-provisioning-type]
  (when (not user-provisioning-type)
    (throw (ex-info (trs "Sorry, but you''ll need a {0} account to view this page. Please contact your administrator."
                         (u/slugify (appearance/site-name))) {}))))

(defmulti check-user-provisioning
  "If `user-provisioning-enabled?` is false, then we should throw an error when attempting to create a new user."
  {:arglists '([model])}
  keyword)

(defmethod check-user-provisioning :saml
  [_]
  (maybe-throw-user-provisioning (sso-settings/saml-user-provisioning-enabled?)))

(defmethod check-user-provisioning :ldap
  [_]
  (maybe-throw-user-provisioning (sso-settings/ldap-user-provisioning-enabled?)))

(defmethod check-user-provisioning :jwt
  [_]
  (maybe-throw-user-provisioning (sso-settings/jwt-user-provisioning-enabled?)))

(mu/defn create-new-sso-user!
  "This function is basically the same thing as the `create-new-google-auth-user` from `metabase.users.models.user`. We need
  to refactor the `core_user` table structure and the function used to populate it so that the enterprise product can
  reuse it."
  [user :- UserAttributes]
  (try
    (u/prog1 (t2/insert-returning-instance! :model/User (merge user {:password (str (random-uuid))}))
      (log/infof "New SSO user created: %s (%s)" (:common_name <>) (:email <>))
      ;; publish user-invited event for audit logging
      ;; skip sending user invited emails for sso users
      (notification/with-skip-sending-notification true
        (events/publish-event! :event/user-invited {:object (assoc <> :sso_source (:sso_source user))}))
      ;; send an email to everyone including the site admin if that's set
      (when (sso/send-new-sso-user-admin-email?)
        (messages/send-user-joined-admin-notification-email! <>, :google-auth? true)))
    (catch ExceptionInfo e
      (log/error e "Error creating new SSO user")
      (throw (ex-info (trs "Error creating new SSO user")
                      {:user user})))))

(mu/defn fetch-and-update-login-attributes!
  "Updates `UserAttributes` for the user at `email`, if they exist, returning the user afterwards.
  Only updates if the `UserAttributes` are unequal to the current values.

  If a user exists but `is_active` is `false`, will return the user only if `reactivate?` is `true`. Otherwise it will
  be as if this user does not exist."
  [{:keys [email] :as user-from-sso} :- UserAttributes
   reactivate? :- ms/BooleanValue]
  (let [;; if the user is not active, we will want to mark them as active if they are actually reactivated.
        new-user-data (merge user-from-sso {:is_active true})
        user-keys (keys new-user-data)]
    (when-let [{:keys [id] :as user} (t2/select-one (into [:model/User :id :last_login] user-keys)
                                                    :%lower.email (u/lower-case-en email))]
      (when (or (:is_active user)
                reactivate?)
        (let [;; remove keys with `nil` values
              user-data (into {} (filter second new-user-data))]
          (if (= (select-keys user user-keys) user-data)
            user
            (do
              (t2/update! :model/User id user-data)
              (t2/select-one :model/User :id id))))))))

(defn relative-uri?
  "Checks that given `uri` is not an absolute (so no scheme and no host)."
  [uri]
  (let [^URI uri (if (string? uri)
                   (try
                     (URI. uri)
                     (catch URISyntaxException _
                       nil))
                   uri)]
    (or (nil? uri)
        (and (nil? (.getHost uri))
             (nil? (.getScheme uri))))))

(defn check-sso-redirect
  "Check if open redirect is being exploited in SSO. If so, or if the redirect-url is invalid, throw a 400."
  [redirect-url]
  (try
    (let [redirect (some-> redirect-url (URI.))
          our-host (some-> (system/site-url) (URI.) (.getHost))]
      (api/check-400 (or (nil? redirect-url)
                         (relative-uri? redirect)
                         (= (.getHost redirect) our-host))))
    (catch Exception e
      (log/error e "Invalid redirect URL")
      (throw (ex-info (tru "Invalid redirect URL")
                      {:status-code  400
                       :redirect-url redirect-url})))))

(defn is-react-sdk-header?
  "Check if the client has indicated it is from the react embedding sdk"
  [request]
  (= (get-in request [:headers "x-metabase-client"]) "embedding-sdk-react"))

(defn is-simple-embed-header?
  "Check if the client has indicated it is from simple embedding"
  [request]
  (= (get-in request [:headers "x-metabase-client"]) "embedding-simple"))

(defn filter-non-stringable-attributes
  "Removes vectors and map json attribute values that cannot be turned into strings."
  [attrs]
  (->> attrs
       (keep (fn [[key value]]
               (if (or (vector? value) (map? value) (nil? value))
                 (log/warnf "Dropping attribute '%s' with non-stringable value: %s" (name key) value)
                 [key value])))
       (into {})))
