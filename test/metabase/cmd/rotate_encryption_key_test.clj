(ns metabase.cmd.rotate-encryption-key-test
  (:require
   [clojure.java.jdbc :as jdbc]
   [clojure.test :refer :all]
   [metabase.cmd :as cmd]
   [metabase.cmd.dump-to-h2-test :as dump-to-h2-test]
   [metabase.cmd.load-from-h2 :as load-from-h2]
   [metabase.cmd.rotate-encryption-key :refer [rotate-encryption-key!]]
   [metabase.cmd.test-util :as cmd.test-util]
   [metabase.db.connection :as mdb.connection]
   [metabase.driver :as driver]
   [metabase.models :refer [Database Secret Setting User]]
   [metabase.models.interface :as mi]
   [metabase.models.setting :as setting]
   [metabase.test :as mt]
   [metabase.test.data.interface :as tx]
   [metabase.test.fixtures :as fixtures]
   [metabase.util :as u]
   [metabase.util.encryption :as encryption]
   [metabase.util.encryption-test :as encryption-test]
   [metabase.util.i18n :as i18n]
   [methodical.core :as methodical]
   [toucan.db :as db]
   [toucan.models :as models])
  (:import
   (java.nio.charset StandardCharsets)))

(use-fixtures :once (fixtures/initialize :db))

(defn- do-with-encrypted-json-caching-disabled
  [thunk]
  (let [mf (methodical/add-primary-method
            @#'models/type-fn
            [:encrypted-json :out]
            (fn [_next-method _type _direction]
              #'mi/encrypted-json-out))]
    (with-redefs [models/type-fn mf]
      (thunk))))

(defmacro ^:private with-encrypted-json-caching-disabled
  "Replace the Toucan `:encrypted-json` `:out` type function with `:json` `:out`. This will prevent cached values from
  being returned by [[metabase.models.interface/cached-encrypted-json-out]]. This might seem fishy -- shouldn't we be
  including the secret key in the cache key itself, if we have to swap out the Toucan type function to get this test
  to pass? But under normal usage the cache key cannot change at runtime -- only in this test do we change it -- so
  making the code in [[metabase.models.interface]] smarter is not necessary."
  {:style/indent 0}
  [& body]
  `(do-with-encrypted-json-caching-disabled (^:once fn* [] ~@body)))

(defn- raw-value [keyy]
  (:value (first (jdbc/query {:datasource (mdb.connection/data-source)}
                             [(if (= driver/*driver* :h2)
                                "select \"VALUE\" from setting where setting.\"KEY\"=?;"
                                "select value from setting where setting.key=?;") keyy]))))

(deftest cmd-rotate-encryption-key-errors-when-failed-test
  (with-redefs [rotate-encryption-key! #(throw "err")
                cmd/system-exit! identity]
    (is (= 1 (cmd/rotate-encryption-key
              "89ulvIGoiYw6mNELuOoEZphQafnF/zYe+3vT+v70D1A=")))))

(deftest rotate-encryption-key!-test
  (encryption-test/with-secret-key nil
    (let [h2-fixture-db-file @cmd.test-util/fixture-db-file-path
          db-name            (str "test_" (u/lower-case-en (mt/random-name)))
          original-timestamp "2021-02-11 18:38:56.042236+00"
          [k1 k2 k3]         ["89ulvIGoiYw6mNELuOoEZphQafnF/zYe+3vT+v70D1A="
                              "yHa/6VEQuIItMyd5CNcgV9nXvzZcX6bWmiY0oOh6pLU="
                              "BCQbKNVu6N8TQ2BwyTC0U0oCBqsvFVr2uhEM/tRgJUM="]
          user-id            (atom nil)
          secret-val         "surprise!"
          secret-id-enc      (atom nil)
          secret-id-unenc    (atom nil)]
      (mt/test-drivers #{:postgres :h2 :mysql}
        (with-encrypted-json-caching-disabled
          (let [data-source (dump-to-h2-test/persistent-data-source driver/*driver* db-name)]
            (binding [;; EXPLANATION FOR WHY THIS TEST WAS FLAKY
                      ;; at this point, all the state switching craziness that happens for
                      ;; `metabase.util.i18n.impl/site-locale-from-setting` has already taken place, so this function has
                      ;; been bootstrapped to now return the site locale from the real, actual setting function
                      ;; the trouble is, when we are swapping out the app DB, attempting to fetch the setting value WILL
                      ;; FAIL, since there is no `SETTING `table yet created
                      ;; the `load-from-h2!`, by way of invoking `copy!`, below, needs the site locale to internationalize
                      ;; its loading progress messages (ex: "Set up h2 source database and run migrations...")
                      ;; the reason this test has been flaky is that if we get "lucky" the *cached* value of the site
                      ;; locale setting is returned, instead of the setting code having to query the app DB for it, and
                      ;; hence no error occurs, but for a cache miss, then the error happens
                      ;; this dynamic rebinding will bypass the call to `i18n/site-locale` and hence avoid that whole mess
                      i18n/*site-locale-override*  "en"
                      ;; while we're at it, disable the setting cache entirely; we are effectively creating a new app DB
                      ;; so the cache itself is invalid and can only mask the real issues
                      setting/*disable-cache*         true?
                      mdb.connection/*application-db* (mdb.connection/application-db driver/*driver* data-source)]
              (when-not (= driver/*driver* :h2)
                (tx/create-db! driver/*driver* {:database-name db-name}))
              (load-from-h2/load-from-h2! h2-fixture-db-file)
              (db/insert! Setting {:key "nocrypt", :value "unencrypted value"})
              (db/insert! Setting {:key "settings-last-updated", :value original-timestamp})
              (let [u (db/insert! User {:email        "nobody@nowhere.com"
                                        :first_name   "No"
                                        :last_name    "Body"
                                        :password     "nopassword"
                                        :is_active    true
                                        :is_superuser false})]
                (reset! user-id (u/the-id u)))
              (let [secret (db/insert! Secret {:name       "My Secret (plaintext)"
                                               :kind       "password"
                                               :value      (.getBytes secret-val StandardCharsets/UTF_8)
                                               :creator_id @user-id})]
                (reset! secret-id-unenc (u/the-id secret)))
              (encryption-test/with-secret-key k1
                (db/insert! Setting {:key "k1crypted", :value "encrypted with k1"})
                (db/update! Database 1 {:details "{\"db\":\"/tmp/test.db\"}"})
                (let [secret (db/insert! Secret {:name       "My Secret (encrypted)"
                                                 :kind       "password"
                                                 :value      (.getBytes secret-val StandardCharsets/UTF_8)
                                                 :creator_id @user-id})]
                  (reset! secret-id-enc (u/the-id secret))))

              (testing "rotating with the same key is a noop"
                (encryption-test/with-secret-key k1
                  (rotate-encryption-key! k1)
                  ;; plain->newkey
                  (testing "for unencrypted values"
                    (is (not= "unencrypted value" (raw-value "nocrypt")))
                    (is (= "unencrypted value" (db/select-one-field :value Setting :key "nocrypt")))
                    (is (mt/secret-value-equals? secret-val (db/select-one-field :value Secret :id @secret-id-unenc))))
                  ;; samekey->samekey
                  (testing "for values encrypted with the same key"
                    (is (not= "encrypted with k1" (raw-value "k1crypted")))
                    (is (= "encrypted with k1" (db/select-one-field :value Setting :key "k1crypted")))
                    (is (mt/secret-value-equals? secret-val (db/select-one-field :value Secret :id @secret-id-enc))))))

              (testing "settings-last-updated is updated AND plaintext"
                (is (not= original-timestamp (raw-value "settings-last-updated")))
                (is (not (encryption/possibly-encrypted-string? (raw-value "settings-last-updated")))))

              (testing "rotating with a new key is recoverable"
                (encryption-test/with-secret-key k1 (rotate-encryption-key! k2))
                (testing "with new key"
                  (encryption-test/with-secret-key k2
                    (is (= "unencrypted value" (db/select-one-field :value Setting :key "nocrypt")))
                    (is (= {:db "/tmp/test.db"} (db/select-one-field :details Database :id 1)))
                    (is (mt/secret-value-equals? secret-val (db/select-one-field :value Secret :id @secret-id-unenc)))))
                (testing "but not with old key"
                  (encryption-test/with-secret-key k1
                    (is (not= "unencrypted value" (db/select-one-field :value Setting :key "nocrypt")))
                    (is (not= "{\"db\":\"/tmp/test.db\"}" (db/select-one-field :details Database :id 1)))
                    (is (not (mt/secret-value-equals? secret-val
                                                      (db/select-one-field :value Secret :id @secret-id-unenc)))))))

              (testing "full rollback when a database details looks encrypted with a different key than the current one"
                (encryption-test/with-secret-key k3
                  (let [db (db/insert! Database {:name "k3", :engine :mysql, :details "{\"db\":\"/tmp/k3.db\"}"})]
                    (is (=? {:name "k3"}
                            db))))
                (encryption-test/with-secret-key k2
                  (let [db (db/insert! Database {:name "k2", :engine :mysql, :details "{\"db\":\"/tmp/k2.db\"}"})]
                    (is (=? {:name "k2"}
                            db)))
                  (is (thrown-with-msg?
                       clojure.lang.ExceptionInfo
                       #"Can't decrypt app db with MB_ENCRYPTION_SECRET_KEY"
                       (rotate-encryption-key! k3))))
                (encryption-test/with-secret-key k3
                  (is (not= {:db "/tmp/k2.db"} (db/select-one-field :details Database :name "k2")))
                  (is (= {:db "/tmp/k3.db"} (db/select-one-field :details Database :name "k3")))))

              (testing "rotate-encryption-key! to nil decrypts the encrypted keys"
                (db/update! Database 1 {:details "{\"db\":\"/tmp/test.db\"}"})
                (db/update-where! Database {:name "k3"} :details "{\"db\":\"/tmp/test.db\"}")
                (encryption-test/with-secret-key k2 ; with the last key that we rotated to in the test
                  (rotate-encryption-key! nil))
                (is (= "unencrypted value" (raw-value "nocrypt")))
                ;; at this point, both the originally encrypted, and the originally unencrypted secret instances
                ;; should be decrypted
                (is (mt/secret-value-equals? secret-val (db/select-one-field :value Secret :id @secret-id-unenc)))
                (is (mt/secret-value-equals? secret-val (db/select-one-field :value Secret :id @secret-id-enc))))

              (testing "short keys fail to rotate"
                (is (thrown? Throwable (rotate-encryption-key! "short")))))))))))
