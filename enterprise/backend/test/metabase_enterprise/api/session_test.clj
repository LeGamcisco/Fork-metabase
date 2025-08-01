(ns metabase-enterprise.api.session-test
  (:require
   [clojure.test :refer :all]
   [metabase.test :as mt]
   [metabase.test.fixtures :as fixtures]))

(use-fixtures :once (fixtures/initialize :db))

(deftest properties-token-features-test
  (mt/with-premium-features #{:advanced-permissions
                              :attached-dwh
                              :audit-app
                              :cache-granular-controls
                              :cache-preemptive
                              :config-text-file
                              :content-translation
                              :content-verification
                              :dashboard-subscription-filters
                              :disable-password-login
                              :database-auth-providers
                              :development-mode
                              :email-allow-list
                              :email-restrict-recipients
                              :embedding
                              :embedding-sdk
                              :embedding-simple
                              :hosting
                              :llm-autodescription
                              :metabot-v3
                              :ai-entity-analysis
                              :ai-sql-fixer
                              :ai-sql-generation
                              :no-upsell
                              :official-collections
                              :query-reference-validation
                              :sandboxes
                              :scim
                              :serialization
                              :session-timeout-config
                              :snippet-collections
                              :sso-google
                              :sso-jwt
                              :sso-ldap
                              :sso-saml
                              :upload-management
                              :whitelabel
                              :collection-cleanup
                              :database-routing
                              :cloud-custom-smtp}
    (is (= {:advanced_permissions           true
            :attached_dwh                   true
            :audit_app                      true
            :cache_granular_controls        true
            :cache_preemptive               true
            :config_text_file               true
            :content_translation            true
            :content_verification           true
            :dashboard_subscription_filters true
            :disable_password_login         true
            :database_auth_providers        true
            :development_mode               true
            :email_allow_list               true
            :email_restrict_recipients      true
            :embedding                      true
            :embedding_sdk                  true
            :embedding_simple               true
            :hosting                        true
            :llm_autodescription            true
            :metabot_v3                     true
            :ai_entity_analysis             true
            :ai_sql_fixer                   true
            :ai_sql_generation              true
            :official_collections           true
            :query_reference_validation     true
            :sandboxes                      true
            :scim                           true
            :serialization                  true
            :session_timeout_config         true
            :snippet_collections            true
            :sso_google                     true
            :sso_jwt                        true
            :sso_ldap                       true
            :sso_saml                       true
            :table_data_editing             false
            :upload_management              true
            :whitelabel                     true
            :collection_cleanup             true
            :database_routing               true
            :cloud_custom_smtp              true
            :etl_connections                false
            :etl_connections_pg             false}
           (:token-features (mt/user-http-request :crowberto :get 200 "session/properties"))))))
