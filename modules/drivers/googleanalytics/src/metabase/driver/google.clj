(ns metabase.driver.google
  "Shared logic for various Google drivers, including BigQuery and Google Analytics."
  (:require [metabase.config :as config]
            [metabase.models.database :refer [Database]]
            [metabase.query-processor.error-type :as qp.error-type]
            [metabase.util :as u]
            [metabase.util.i18n :refer [trs]]
            [metabase.util.log :as log]
            [ring.util.codec :as codec]
            [toucan.db :as db])
  (:import [com.google.api.client.googleapis.auth.oauth2 GoogleAuthorizationCodeFlow GoogleAuthorizationCodeFlow$Builder GoogleCredential GoogleCredential$Builder GoogleTokenResponse]
           com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
           [com.google.api.client.googleapis.json GoogleJsonError GoogleJsonResponseException]
           com.google.api.client.googleapis.services.AbstractGoogleClientRequest
           com.google.api.client.http.HttpTransport
           com.google.api.client.json.jackson2.JacksonFactory
           com.google.api.client.json.JsonFactory
           java.io.ByteArrayInputStream))

(def ^HttpTransport http-transport
  "`HttpTransport` for use with Google drivers."
  (GoogleNetHttpTransport/newTrustedTransport))

(def ^JsonFactory json-factory
  "`JsonFactory` for use with Google drivers."
  (JacksonFactory/getDefaultInstance))

(def ^:private ^:const ^String redirect-uri "urn:ietf:wg:oauth:2.0:oob")

(defn execute-no-auto-retry
  "Execute `request`, and catch any `GoogleJsonResponseException` is throws, converting them to `ExceptionInfo` and
  rethrowing them."
  [^AbstractGoogleClientRequest request]
  (try
    (.execute request)
    (catch GoogleJsonResponseException e
      (let [^GoogleJsonError error (.getDetails e)]
        (throw (ex-info (or (.getMessage error)
                            (.getStatusMessage e))
                        (into {} error)))))))

(defn execute
  "Execute `request`, and catch any `GoogleJsonResponseException` is throws, converting them to `ExceptionInfo` and
  rethrowing them.

  This automatically retries any failed requests."
  [^AbstractGoogleClientRequest request]
  (try
    (execute-no-auto-retry request)
    (catch Throwable e
      (when-not (qp.error-type/client-error? (:type (u/all-ex-data e)))
        (execute-no-auto-retry request)))))

(defn- create-application-name
  "Creates the application name string, separated out from the `def` below so it's testable with different values"
  [{:keys [tag ^String hash branch]}]
  (let [encoded-hash (some-> hash (.getBytes "UTF-8") codec/base64-encode)]
    (format "Metabase/%s (GPN:Metabase; %s %s)"
            (or tag "?")
            (or encoded-hash "?")
            (or branch "?"))))

(def ^:const ^String application-name
  "The application name we should use for Google drivers. Requested by Google themselves -- see #2627"
  (create-application-name config/mb-version-info))

(defn- fetch-access-and-refresh-tokens* [scopes, ^String client-id, ^String client-secret, ^String auth-code]
  {:pre  [(seq client-id) (seq client-secret) (seq auth-code)]
   :post [(seq (:access-token %)) (seq (:refresh-token %))]}

  (log/info (u/format-color 'magenta (trs "Fetching Google access/refresh tokens with auth-code {0}..." (pr-str auth-code))))
  (let [^GoogleAuthorizationCodeFlow flow
        (.build (doto (GoogleAuthorizationCodeFlow$Builder. http-transport json-factory client-id client-secret scopes)
                  (.setAccessType "offline")))

            ;; don't use `execute` here because this is a *different* type of Google request
        ^GoogleTokenResponse response
        (.execute (doto (.newTokenRequest flow auth-code)
                    (.setRedirectUri redirect-uri)))]
    {:access-token (.getAccessToken response), :refresh-token (.getRefreshToken response)}))

(def ^{:arglists '([scopes client-id client-secret auth-code])} fetch-access-and-refresh-tokens
  "Fetch Google access and refresh tokens. This function is memoized because you're only allowed to redeem an
  auth-code once. This way we can redeem it the first time when `can-connect?` checks to see if the DB details are
  viable; then the second time we go to redeem it we can save the access token and refresh token with the newly
  created `Database` <3"
  (memoize fetch-access-and-refresh-tokens*))

(defn- database->credential*
  [scopes {{:keys [^String client-id, ^String client-secret, ^String auth-code, ^String access-token, ^String refresh-token
                   ^String service-account-json]
            :as   details} :details
           id              :id
           :as             db}]
  {:pre [(map? db) (or (and (seq client-id) (seq client-secret) (or (seq auth-code)
                                                                    (and (seq access-token) (seq refresh-token))))
                       (seq service-account-json))]}

  (if (seq service-account-json)
    (let [creds (GoogleCredential/fromStream (ByteArrayInputStream. (.getBytes service-account-json))
                                             http-transport
                                             json-factory)
          details (-> (merge details {:project-id (.getServiceAccountProjectId creds)})
                      (dissoc :auth-code))]
      (when id
        (db/update! Database id, :details details))
      (.createScoped creds scopes))

    (if-not (and (seq access-token)
                 (seq refresh-token))
      ;; If Database doesn't have access/refresh tokens fetch them and try again
      (let [details (-> (merge details (fetch-access-and-refresh-tokens scopes client-id client-secret auth-code))
                        (dissoc :auth-code))]
        (when id
          (db/update! Database id, :details details))
        (recur scopes (assoc db :details details)))
      ;; Otherwise return credential as normal
      (doto (.build (doto (GoogleCredential$Builder.)
                      (.setClientSecrets client-id client-secret)
                      (.setJsonFactory json-factory)
                      (.setTransport http-transport)))
        (.setAccessToken  access-token)
        (.setRefreshToken refresh-token)))))

(defn database->credential
  "Get a `GoogleCredential` for a `DatabaseInstance`."
  ^com.google.api.client.googleapis.auth.oauth2.GoogleCredential [scopes database-or-id]
  (database->credential*
   scopes
   (if (integer? database-or-id)
     (db/select-one [Database :id :details], :id database-or-id)
     database-or-id)))
