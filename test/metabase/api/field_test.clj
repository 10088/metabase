(ns metabase.api.field-test
  "Tests for `/api/field` endpoints."
  (:require
   [clojure.test :refer :all]
   [medley.core :as m]
   [metabase.api.field :as api.field]
   [metabase.driver.util :as driver.u]
   [metabase.models :refer [Database Field FieldValues Table]]
   [metabase.sync :as sync]
   [metabase.sync.concurrent :as sync.concurrent]
   [metabase.test :as mt]
   [metabase.test.fixtures :as fixtures]
   [metabase.timeseries-query-processor-test.util :as tqpt]
   [metabase.util :as u]
   [ring.util.codec :as codec]
   [toucan.db :as db]
   [toucan.hydrate :refer [hydrate]]))

(use-fixtures :once (fixtures/initialize :plugins))

;; Helper Fns

(defn- db-details []
  (merge
   (select-keys (mt/db) [:id :timezone :initial_sync_status])
   (dissoc (mt/object-defaults Database) :details :initial_sync_status :dbms_version)
   {:engine        "h2"
    :name          "test-data"
    :features      (mapv u/qualified-name (driver.u/features :h2 (mt/db)))
    :timezone      "UTC"}))

(deftest get-field-test
  (testing "GET /api/field/:id"
    (is (= (-> (merge
                (mt/object-defaults Field)
                (db/select-one [Field :created_at :updated_at :last_analyzed :fingerprint :fingerprint_version :database_position :database_required]
                  :id (mt/id :users :name))
                {:table_id         (mt/id :users)
                 :table            (merge
                                    (mt/obj->json->obj (mt/object-defaults Table))
                                    (db/select-one [Table :created_at :updated_at :initial_sync_status] :id (mt/id :users))
                                    {:description             nil
                                     :entity_type             "entity/UserTable"
                                     :visibility_type         nil
                                     :db                      (db-details)
                                     :schema                  "PUBLIC"
                                     :name                    "USERS"
                                     :display_name            "Users"
                                     :active                  true
                                     :id                      (mt/id :users)
                                     :db_id                   (mt/id)
                                     :caveats                 nil
                                     :points_of_interest      nil
                                     :show_in_getting_started false})
                 :semantic_type    "type/Name"
                 :name             "NAME"
                 :display_name     "Name"
                 :position         1
                 :id               (mt/id :users :name)
                 :visibility_type  "normal"
                 :database_type    "CHARACTER VARYING"
                 :base_type        "type/Text"
                 :effective_type   "type/Text"
                 :has_field_values "list"
                 :database_required false
                 :dimensions       []
                 :name_field       nil})
               (m/dissoc-in [:table :db :updated_at] [:table :db :created_at] [:table :db :timezone] [:table :db :settings]))
           (-> (mt/user-http-request :rasta :get 200 (format "field/%d" (mt/id :users :name)))
               (update-in [:table :db] dissoc :updated_at :created_at :timezone :dbms_version))))))

(deftest get-field-summary-test
  (testing "GET /api/field/:id/summary"
    ;; TODO -- why doesn't this come back as a dictionary ?
    (is (= [["count" 75]
            ["distincts" 75]]
           (mt/user-http-request :rasta :get 200 (format "field/%d/summary" (mt/id :categories :name)))))))

(defn simple-field-details [field]
  (select-keys field [:name
                      :display_name
                      :description
                      :visibility_type
                      :semantic_type
                      :fk_target_field_id
                      :nfc_path]))

(mt/defdataset integer-coerceable
  [["t" [{:field-name "f"
          :base-type  :type/Integer}]
    [[100000] [200000] [300000]]]])

(deftest update-field-test
  (testing "PUT /api/field/:id"
    (testing "test that we can do basic field update work, including unsetting some fields such as semantic-type"
      (mt/with-temp Field [{field-id :id} {:name "Field Test"}]
        (let [original-val (simple-field-details (db/select-one Field :id field-id))]
          (testing "orignal value"
            (is (= {:name               "Field Test"
                    :display_name       "Field Test"
                    :description        nil
                    :semantic_type      nil
                    :visibility_type    :normal
                    :fk_target_field_id nil
                    :nfc_path           nil}
                   original-val)))
          ;; set it
          (mt/user-http-request :crowberto :put 200 (format "field/%d" field-id) {:name            "something else"
                                                                                  :display_name    "yay"
                                                                                  :description     "foobar"
                                                                                  :semantic_type   :type/Name
                                                                                  :visibility_type :sensitive
                                                                                  :nfc_path        ["bob" "dobbs"]})
          (let [updated-val (simple-field-details (db/select-one Field :id field-id))]
            (testing "updated value"
              (is (= {:name               "Field Test"
                      :display_name       "yay"
                      :description        "foobar"
                      :semantic_type      :type/Name
                      :visibility_type    :sensitive
                      :fk_target_field_id nil
                      :nfc_path           ["bob" "dobbs"]}
                     updated-val)))
            ;; unset it
            (mt/user-http-request :crowberto :put 200 (format "field/%d" field-id) {:description   nil
                                                                                    :semantic_type nil
                                                                                    :nfc_path      nil})
            (testing "response"
              (is (= {:name               "Field Test"
                      :display_name       "yay"
                      :description        nil
                      :semantic_type      nil
                      :visibility_type    :sensitive
                      :fk_target_field_id nil
                      :nfc_path           nil}
                     (simple-field-details (db/select-one Field :id field-id)))))))))
    (testing "updating coercion strategies"
      (mt/with-temp Field [{field-id :id} {:name "Field Test"}]
        (testing "When valid, updates coercion strategy and effective type"
          (is (= ["type/DateTime" "Coercion/YYYYMMDDHHMMSSString->Temporal"]
                 ((juxt :effective_type :coercion_strategy)
                  (mt/user-http-request :crowberto :put 200 (format "field/%d" field-id)
                                        {:coercion_strategy :Coercion/YYYYMMDDHHMMSSString->Temporal})))))
        (testing "Sending a nil coercion_strategy restores the effective type"
          (is (= ["type/Text" nil]
                 ((juxt :effective_type :coercion_strategy)
                  (mt/user-http-request :crowberto :put 200 (format "field/%d" field-id)
                                        {:coercion_strategy nil}))))))
      (mt/with-temp Field [{field-id :id} {:name "Field Test"}]
        (testing "When not a valid strategy does not change the coercion or effective type"
          (is (= ["type/Text" nil]
                 ((juxt :effective_type :coercion_strategy)
                  (mt/user-http-request :crowberto :put 200 (format "field/%d" field-id)
                                        ;; unix is an integer->Temporal conversion
                                        {:coercion_strategy :Coercion/UNIXMicroSeconds->DateTime}))))))
      (testing "Refingerprints field when updated"
        (with-redefs [sync.concurrent/submit-task (fn [task] (task))]
          (mt/dataset integer-coerceable
            (sync/sync-database! (db/select-one Database :id (mt/id)))
            (let [field-id      (mt/id :t :f)
                  set-strategy! (fn [strategy]
                                  (mt/user-http-request :crowberto :put 200 (format "field/%d" field-id)
                                                        {:coercion_strategy strategy}))]
              ;; ensure that there is no coercion strategy from previous tests
              (set-strategy! nil)
              (let [field (db/select-one Field :id field-id)]
                (is (= :type/Integer (:effective_type field)))
                (is (contains? (get-in field [:fingerprint :type]) :type/Number)))
              (set-strategy! :Coercion/UNIXSeconds->DateTime)
              (let [field (db/select-one Field :id field-id)]
                (is (= :type/Instant (:effective_type field)))
                (is (contains? (get-in field [:fingerprint :type]) :type/DateTime))))))))

    (testing "A field can only be updated by a superuser"
      (mt/with-temp Field [{field-id :id} {:name "Field Test"}]
        (mt/user-http-request :rasta :put 403 (format "field/%d" field-id) {:name "Field Test 2"})))))

(deftest remove-fk-semantic-type-test
  (testing "PUT /api/field/:id"
    (testing "when we set the semantic-type from `:type/FK` to something else, make sure `:fk_target_field_id` is set to nil"
      (mt/with-temp* [Field [{fk-field-id :id}]
                      Field [{field-id :id} {:semantic_type :type/FK, :fk_target_field_id fk-field-id}]]
        (let [original-val (boolean (db/select-one-field :fk_target_field_id Field, :id field-id))]
          (testing "before API call"
            (is (= true
                   original-val)))
          ;; unset the :type/FK semantic-type
          (mt/user-http-request :crowberto :put 200 (format "field/%d" field-id) {:semantic_type :type/Name})
          (testing "after API call"
            (is (= nil
                   (db/select-one-field :fk_target_field_id Field, :id field-id)))))))))

(deftest update-fk-target-field-id-test
  (testing "PUT /api/field/:id"
    (testing "check that you *can* set `:fk_target_field_id` if it *is* the proper base type"
      (mt/with-temp Field [{field-id :id} {:base_type :type/Integer}]
        (mt/user-http-request :crowberto :put 200 (str "field/" field-id)
                              {:semantic_type :type/Quantity})
        (is (= :type/Quantity
               (db/select-one-field :semantic_type Field, :id field-id)))))))

(defn- field->field-values
  "Fetch the `FieldValues` object that corresponds to a given `Field`."
  [table-kw field-kw]
  (db/select-one FieldValues :field_id (mt/id table-kw field-kw)))

(defn- field-values-id [table-key field-key]
  (:id (field->field-values table-key field-key)))

(deftest field-values-test
  (testing "GET /api/field/:id/values"
    (testing "Should return something useful for a field whose `has_field_values` is `list`"
      (mt/with-temp-copy-of-db
        ;; clear out existing human_readable_values in case they're set
        (when-let [id (field-values-id :venues :price)]
          (db/update! FieldValues id :human_readable_values nil))
        (db/update! Field (mt/id :venues :price) :has_field_values "list")
        ;; now update the values via the API
        (is (= {:values [[1] [2] [3] [4]], :field_id (mt/id :venues :price), :has_more_values false}
               (mt/user-http-request :rasta :get 200 (format "field/%d/values" (mt/id :venues :price)))))))

    (testing "Should return nothing for a field whose `has_field_values` is not `list`"
      (is (= {:values [], :field_id (mt/id :venues :id), :has_more_values false}
             (mt/user-http-request :rasta :get 200 (format "field/%d/values" (mt/id :venues :id))))))

    (testing "Sensitive fields do not have field values and should return empty"
      (is (= {:values [], :field_id (mt/id :users :password), :has_more_values false}
             (mt/user-http-request :rasta :get 200 (format "field/%d/values" (mt/id :users :password))))))

    (testing "External remapping"
      (mt/with-column-remappings [venues.category_id categories.name]
        (mt/with-temp-vals-in-db Field (mt/id :venues :category_id) {:has_field_values "list"}
          (is (partial= {:field_id (mt/id :venues :category_id)
                         :values   [[1 "African"]
                                    [2 "American"]
                                    [3 "Artisan"]]}
                 (mt/user-http-request :rasta :get 200 (format "field/%d/values" (mt/id :venues :category_id))))))))))

(def ^:private list-field {:name "Field Test", :base_type :type/Integer, :has_field_values "list"})

(deftest update-field-values-no-human-readable-values-test
  (testing "POST /api/field/:id/values"
    (testing "Human readable values are optional"
      (mt/with-temp* [Field       [{field-id :id} list-field]
                      FieldValues [_              {:values (range 5 10), :field_id field-id}]]
        (testing "fetch initial values"
          (is (= {:values [[5] [6] [7] [8] [9]], :field_id true, :has_more_values false}
                 (mt/boolean-ids-and-timestamps
                  (mt/user-http-request :crowberto :get 200 (format "field/%d/values" field-id))))))
        (testing "update values"
          (is (= {:status "success"}
                 (mt/boolean-ids-and-timestamps
                  (mt/user-http-request :crowberto :post 200 (format "field/%d/values" field-id)
                   {:values (map vector (range 1 5))})))))
        (testing "fetch updated values"
          (is (= {:values [[1] [2] [3] [4]], :field_id true, :has_more_values false}
                 (mt/boolean-ids-and-timestamps
                  (mt/user-http-request :crowberto :get 200 (format "field/%d/values" field-id))))))))))

(deftest update-field-values-with-human-readable-values-test
  (testing "POST /api/field/:id/values"
    (testing "Existing field values can be updated (with their human readable values)"
      (mt/with-temp* [Field [{field-id :id} list-field]
                      FieldValues [_ {:values (conj (range 1 5) nil), :field_id field-id}]]
        (testing "fetch initial values"
          (is (= {:values [[nil] [1] [2] [3] [4]], :field_id true, :has_more_values false}
                 (mt/boolean-ids-and-timestamps
                  (mt/user-http-request :crowberto :get 200 (format "field/%d/values" field-id))))))
        (testing "update values"
          (is (= {:status "success"}
                 (mt/boolean-ids-and-timestamps
                  (mt/user-http-request :crowberto :post 200 (format "field/%d/values" field-id)
                   {:values [[nil "no $"] [1 "$"] [2 "$$"] [3 "$$$"] [4 "$$$$"]], :has_more_values false})))))
        (testing "fetch updated values"
          (is (= {:values [[nil "no $"] [1 "$"] [2 "$$"] [3 "$$$"] [4 "$$$$"]], :field_id true, :has_more_values false}
                 (mt/boolean-ids-and-timestamps
                  (mt/user-http-request :crowberto :get 200 (format "field/%d/values" field-id))))))))))

(deftest create-field-values-when-not-present-test
  (testing "POST /api/field/:id/values"
    (testing "Field values should be created when not present"
      ;; this will print an error message because it will try to fetch the FieldValues, but the Field doesn't
      ;; exist; we can ignore that
      (mt/with-temp Field [{field-id :id} list-field]
        (is (= {:values [], :field_id true, :has_more_values false}
               (mt/boolean-ids-and-timestamps
                (mt/user-http-request :crowberto :get 200 (format "field/%d/values" field-id)))))

        (is (= {:status "success"}
               (mt/user-http-request :crowberto :post 200 (format "field/%d/values" field-id)
                                     {:values [[1 "$"] [2 "$$"] [3 "$$$"] [4 "$$$$"]]})))

        (is (= {:values [1 2 3 4], :human_readable_values ["$" "$$" "$$$" "$$$$"], :has_more_values false}
               (into {} (db/select-one [FieldValues :values :human_readable_values, :has_more_values] :field_id field-id))))

        (is (= {:values [[1 "$"] [2 "$$"] [3 "$$$"] [4 "$$$$"]], :field_id true, :has_more_values false}
               (mt/boolean-ids-and-timestamps
                (mt/user-http-request :crowberto :get 200 (format "field/%d/values" field-id)))))))))

(deftest remove-field-values-test
  (testing "POST /api/field/:id/values"
    (mt/with-temp Field [{field-id :id} list-field]
      (testing "should be able to unset FieldValues"
        (mt/with-temp FieldValues [_ {:values (range 1 5), :field_id field-id}]
          (testing "before updating values"
            (is (= {:values [[1] [2] [3] [4]], :field_id true, :has_more_values false}
                   (mt/boolean-ids-and-timestamps (mt/user-http-request :crowberto :get 200 (format "field/%d/values" field-id))))))
          (testing "API response"
            (is (= {:status "success"}
                   (mt/user-http-request :crowberto :post 200 (format "field/%d/values" field-id) {:values [], :field_id true}))))
          (testing "after updating values"
            (is (= {:values [], :field_id true, :has_more_values false}
                   (mt/boolean-ids-and-timestamps (mt/user-http-request :crowberto :get 200 (format "field/%d/values" field-id))))))[]))

      (testing "should be able to unset just the human-readable values"
        (mt/with-temp FieldValues [_ {:values                (range 1 5)
                                      :field_id              field-id
                                      :human_readable_values ["$" "$$" "$$$" "$$$$"]}]
          (testing "before updating values"
            (is (= {:values [[1 "$"] [2 "$$"] [3 "$$$"] [4 "$$$$"]], :field_id true, :has_more_values false}
                   (mt/boolean-ids-and-timestamps (mt/user-http-request :crowberto :get 200 (format "field/%d/values" field-id))))))
          (testing "API response"
            (is (= {:status "success"}
                   (mt/user-http-request :crowberto :post 200 (format "field/%d/values" field-id) {:values [[1] [2] [3] [4]]}))))
          (testing "after updating values"
            (is (= {:values [[1] [2] [3] [4]], :field_id true, :has_more_values false}
                   (mt/boolean-ids-and-timestamps (mt/user-http-request :crowberto :get 200 (format "field/%d/values" field-id)))))))))

    (testing "attempting to updated values should throw when human readable values are present but not for every value"
      (mt/with-temp Field [{field-id :id} {:name "Field Test", :base_type :type/Integer, :has_field_values "list"}]
        (is (= "If remapped values are specified, they must be specified for all field values"
               (mt/user-http-request :crowberto :post 400 (format "field/%d/values" field-id)
                                     {:values [[1 "$"] [2 "$$"] [3] [4]]})))))))

(defn- dimension-for-field [field-id]
  (-> (db/select-one Field :id field-id)
      (hydrate :dimensions)
      :dimensions
      first))

(defn- create-dimension-via-API!
  [field-id map-to-post & {:keys [expected-status-code]
                           :or   {expected-status-code 200}}]
  (mt/user-http-request :crowberto :post expected-status-code (format "field/%d/dimension" field-id) map-to-post))

(deftest create-update-dimension-test
  (mt/with-temp* [Field [{field-id :id} {:name "Field Test"}]]
    (testing "no dimension should exist for a new Field"
      (is (= nil
             (dimension-for-field field-id))))
    (testing "Create a dimension"
      (create-dimension-via-API! field-id {:name "some dimension name", :type "internal"})
      (let [new-dim (dimension-for-field field-id)]
        (is (= {:id                      true
                :entity_id               true
                :created_at              true
                :updated_at              true
                :type                    :internal
                :name                    "some dimension name"
                :human_readable_field_id false
                :field_id                true}
               (mt/boolean-ids-and-timestamps new-dim)))
        (testing "Update a Dimension"
          (create-dimension-via-API! field-id {:name "different dimension name", :type "internal"})
          (let [updated-dim (dimension-for-field field-id)]
            (is (= {:id                      true
                    :entity_id               true
                    :created_at              true
                    :updated_at              true
                    :type                    :internal
                    :name                    "different dimension name"
                    :human_readable_field_id false
                    :field_id                true}
                   (mt/boolean-ids-and-timestamps updated-dim)))
            (testing "attempting to create a dimension when one already exists should update the existing"
              (is (= (u/the-id new-dim)
                     (u/the-id updated-dim))))))))))

(deftest virtual-field-values-test
  (testing "Check that trying to get values for a 'virtual' field just returns a blank values map"
    (is (= {:values []}
           (mt/user-http-request :rasta :get 200 (format "field/%s/values" (codec/url-encode "field,created_at,{base-type,type/Datetime}")))))))

(deftest create-dimension-with-human-readable-field-id-test
  (testing "POST /api/field/:id/dimension"
    (mt/with-temp* [Field [{field-id-1 :id} {:name "Field Test 1"}]
                    Field [{field-id-2 :id} {:name "Field Test 2"}]]
      (testing "before creation"
        (is (= nil
               (dimension-for-field field-id-1))))
      (create-dimension-via-API! field-id-1
        {:name "some dimension name", :type "external" :human_readable_field_id field-id-2})
      (testing "after creation"
        (is (= {:id                      true
                :entity_id               true
                :created_at              true
                :updated_at              true
                :type                    :external
                :name                    "some dimension name"
                :human_readable_field_id true
                :field_id                true}
               (mt/boolean-ids-and-timestamps (dimension-for-field field-id-1))))))))

(deftest create-dimension-validation-test
  (testing "POST /api/field/:id/dimension"
    (testing "External remappings require a human readable field id"
      (mt/with-temp Field [{field-id :id} {:name "Field Test 1"}]
        (is (= "Foreign key based remappings require a human readable field id"
               (create-dimension-via-API! field-id
                 {:name "some dimension name", :type "external"}
                 :expected-status-code 400)))))

    (testing "Non-admin users can't update dimension"
      (mt/with-temp Field [{field-id :id} {:name "Field Test 1"}]
        (is (= "You don't have permissions to do that."
               (mt/user-http-request :rasta :post 403 (format "field/%d/dimension" field-id)
                {:name "some dimension name", :type "external"})))))))

(deftest delete-dimension-test
  (testing "DELETE /api/field/:id/dimension"
    (testing "Ensure we can delete a dimension"
      (mt/with-temp Field [{field-id :id} {:name "Field Test"}]
        (create-dimension-via-API! field-id {:name "some dimension name", :type "internal"})
        (testing "before deletion"
          (is (= {:id                      true
                  :entity_id               true
                  :created_at              true
                  :updated_at              true
                  :type                    :internal
                  :name                    "some dimension name"
                  :human_readable_field_id false
                  :field_id                true}
                 (mt/boolean-ids-and-timestamps (dimension-for-field field-id)))))
        (mt/user-http-request :crowberto :delete 204 (format "field/%d/dimension" field-id))
        (testing "after deletion"
          (is (= nil
                 (dimension-for-field field-id))))))))

(deftest delete-dimension-permissions-test
  (testing "DELETE /api/field/:id/dimension"
    (testing "Non-admin users can't delete a dimension"
      (mt/with-temp Field [{field-id :id} {:name "Field Test 1"}]
        (is (= "You don't have permissions to do that."
               (mt/user-http-request :rasta :delete 403 (format "field/%d/dimension" field-id))))))))

(deftest clear-external-dimension-when-fk-semantic-type-is-removed-test
  (testing "PUT /api/field/:id"
    (testing "When an FK field gets it's semantic_type removed, we should clear the external dimension"
      (mt/with-temp* [Field [{field-id-1 :id} {:name          "Field Test 1"
                                               :semantic_type :type/FK}]
                      Field [{field-id-2 :id} {:name "Field Test 2"}]]
        (create-dimension-via-API! field-id-1
          {:name "fk-remove-dimension", :type "external" :human_readable_field_id field-id-2})
        (testing "before update"
          (is (= {:id                      true
                  :entity_id               true
                  :created_at              true
                  :updated_at              true
                  :type                    :external
                  :name                    "fk-remove-dimension"
                  :human_readable_field_id true
                  :field_id                true}
                 (mt/boolean-ids-and-timestamps (dimension-for-field field-id-1)))))
        (mt/user-http-request :crowberto :put 200 (format "field/%d" field-id-1) {:semantic_type nil})
        (testing "after update"
          (is (= nil
                 (mt/boolean-ids-and-timestamps (dimension-for-field field-id-1)))))))))

(deftest update-field-should-not-affect-dimensions-test
  (testing "PUT /api/field/:id"
    (testing "Updating unrelated properties should not affect a Field's `:dimensions`"
      (mt/with-temp* [Field [{field-id-1 :id} {:name          "Field Test 1"
                                               :semantic_type :type/FK}]
                      Field [{field-id-2 :id} {:name "Field Test 2"}]]
        ;; create the Dimension
        (create-dimension-via-API! field-id-1
          {:name "fk-remove-dimension", :type "external" :human_readable_field_id field-id-2})
        (let [expected {:id                      true
                        :entity_id               true
                        :created_at              true
                        :updated_at              true
                        :type                    :external
                        :name                    "fk-remove-dimension"
                        :human_readable_field_id true
                        :field_id                true}]
          (testing "before API request"
            (is (= expected
                   (mt/boolean-ids-and-timestamps (dimension-for-field field-id-1)))))
          ;; now change something unrelated: description
          (mt/user-http-request :crowberto :put 200 (format "field/%d" field-id-1)
           {:description "something diffrent"})
          (testing "after API request"
            (is (= expected
                   (mt/boolean-ids-and-timestamps (dimension-for-field field-id-1))))))))))

(deftest remove-fk-semantic-type-test-2
  (testing "When removing the FK semantic type, the fk_target_field_id should be cleared as well"
    (mt/with-temp* [Field [{field-id-1 :id} {:name "Field Test 1"}]
                    Field [{field-id-2 :id} {:name               "Field Test 2"
                                             :semantic_type      :type/FK
                                             :fk_target_field_id field-id-1}]]
      (testing "before change"
        (is (= {:name               "Field Test 2"
                :display_name       "Field Test 2"
                :description        nil
                :visibility_type    :normal
                :semantic_type      :type/FK
                :fk_target_field_id true
                :nfc_path           nil}
               (mt/boolean-ids-and-timestamps (simple-field-details (db/select-one Field :id field-id-2))))))
      (mt/user-http-request :crowberto :put 200 (format "field/%d" field-id-2) {:semantic_type nil})
      (testing "after change"
        (is (= {:name               "Field Test 2"
                :display_name       "Field Test 2"
                :description        nil
                :visibility_type    :normal
                :semantic_type      nil
                :fk_target_field_id false
                :nfc_path           nil}
               (mt/boolean-ids-and-timestamps (simple-field-details (db/select-one Field :id field-id-2)))))))))

(deftest update-fk-target-field-id-test-2
  (testing "Checking update of the fk_target_field_id"
    (mt/with-temp* [Field [{field-id-1 :id} {:name "Field Test 1"}]
                    Field [{field-id-2 :id} {:name "Field Test 2"}]
                    Field [{field-id-3 :id} {:name               "Field Test 3"
                                             :semantic_type      :type/FK
                                             :fk_target_field_id field-id-1}]]
      (let [before-change (simple-field-details (db/select-one Field :id field-id-3))]
        (testing "before change"
          (is (= {:name               "Field Test 3"
                  :display_name       "Field Test 3"
                  :description        nil
                  :visibility_type    :normal
                  :semantic_type      :type/FK
                  :fk_target_field_id true
                  :nfc_path           nil}
                 (mt/boolean-ids-and-timestamps before-change))))
        (mt/user-http-request :crowberto :put 200 (format "field/%d" field-id-3) {:fk_target_field_id field-id-2})
        (testing "after change"
          (let [after-change (simple-field-details (db/select-one Field :id field-id-3))]
            (is (= {:name               "Field Test 3"
                    :display_name       "Field Test 3"
                    :description        nil
                    :visibility_type    :normal
                    :semantic_type      :type/FK
                    :fk_target_field_id true
                    :nfc_path           nil}
                   (mt/boolean-ids-and-timestamps after-change)))
            (is (not= (:fk_target_field_id before-change)
                      (:fk_target_field_id after-change)))))))))

(deftest update-fk-target-field-id-with-fk-test
  (testing "Checking update of the fk_target_field_id along with an FK change"
    (mt/with-temp* [Field [{field-id-1 :id} {:name "Field Test 1"}]
                    Field [{field-id-2 :id} {:name "Field Test 2"}]]

      (testing "before change"
        (is (= {:name               "Field Test 2"
                :display_name       "Field Test 2"
                :description        nil
                :visibility_type    :normal
                :semantic_type      nil
                :fk_target_field_id false
                :nfc_path           nil}
               (mt/boolean-ids-and-timestamps (simple-field-details (db/select-one Field :id field-id-2))))))
      (mt/user-http-request :crowberto :put 200 (format "field/%d" field-id-2) {:semantic_type      :type/FK
                                                                                :fk_target_field_id field-id-1})
      (testing "after change"
        (is (= {:name               "Field Test 2"
                :display_name       "Field Test 2"
                :description        nil
                :visibility_type    :normal
                :semantic_type      :type/FK
                :fk_target_field_id true
                :nfc_path           nil}
               (mt/boolean-ids-and-timestamps (simple-field-details (db/select-one Field :id field-id-2)))))))))

(deftest fk-target-field-id-shouldnt-change-test
  (testing "PUT /api/field/:id"
    (testing "fk_target_field_id and FK should remain unchanged on updates of other fields"
      (mt/with-temp* [Field [{field-id-1 :id} {:name "Field Test 1"}]
                      Field [{field-id-2 :id} {:name               "Field Test 2"
                                               :semantic_type      :type/FK
                                               :fk_target_field_id field-id-1}]]
        (testing "before change"
          (is (= {:name               "Field Test 2"
                  :display_name       "Field Test 2"
                  :description        nil
                  :visibility_type    :normal
                  :semantic_type      :type/FK
                  :fk_target_field_id true
                  :nfc_path           nil}
                 (mt/boolean-ids-and-timestamps (simple-field-details (db/select-one Field :id field-id-2))))))
        (mt/user-http-request :crowberto :put 200 (format "field/%d" field-id-2) {:description "foo"})
        (testing "after change"
          (is (= {:name               "Field Test 2"
                  :display_name       "Field Test 2"
                  :description        "foo"
                  :visibility_type    :normal
                  :semantic_type      :type/FK
                  :fk_target_field_id true
                  :nfc_path           nil}
                 (mt/boolean-ids-and-timestamps (simple-field-details (db/select-one Field :id field-id-2))))))))))

(deftest update-field-type-dimension-test
  (testing "PUT /api/field/:id"
    (testing "Changing a remapped field's type to something that can't be remapped will clear the dimension"
      (mt/with-temp Field [{field-id :id} {:name      "Field Test"
                                           :base_type "type/Integer"}]
        (create-dimension-via-API! field-id {:name "some dimension name", :type "internal"})
        (testing "before API request"
          (is (= {:id                      true
                  :entity_id               true
                  :created_at              true
                  :updated_at              true
                  :type                    :internal
                  :name                    "some dimension name"
                  :human_readable_field_id false
                  :field_id                true}
                 (mt/boolean-ids-and-timestamps (dimension-for-field field-id)))))
        (mt/user-http-request :crowberto :put 200 (format "field/%d" field-id) {:semantic_type "type/AvatarURL"})
        (testing "after API request"
          (is (= nil
                 (dimension-for-field field-id))))))

    (testing "Change from supported type to supported type will leave the dimension"
      (mt/with-temp Field [{field-id :id} {:name      "Field Test"
                                           :base_type "type/Integer"}]
        (create-dimension-via-API! field-id {:name "some dimension name", :type "internal"})
        (let [expected {:id                      true
                        :entity_id               true
                        :created_at              true
                        :updated_at              true
                        :type                    :internal
                        :name                    "some dimension name"
                        :human_readable_field_id false
                        :field_id                true}]
          (testing "before API request"
            (is (= expected
                   (mt/boolean-ids-and-timestamps (dimension-for-field field-id)))))
          (mt/user-http-request :crowberto :put 200 (format "field/%d" field-id) {:has_field_values "list"})
          (testing "after API request"
            (is (= expected
                   (mt/boolean-ids-and-timestamps (dimension-for-field field-id))))))))))

(deftest update-field-settings-test
  (testing "Can we update Field.settings, and fetch it?"
    (mt/with-temp Field [field {:name "Crissy Field"}]
      (mt/user-http-request :crowberto :put 200 (format "field/%d" (u/the-id field)) {:settings {:field_is_cool true}})
      (is (= {:field_is_cool true}
             (-> (mt/user-http-request :crowberto :get 200 (format "field/%d" (u/the-id field)))
                 :settings))))))

(deftest search-values-test
  (testing "make sure `search-values` works on with our various drivers"
    (mt/test-drivers (mt/normal-drivers)
      (is (= [[1 "Red Medicine"]
              [10 "Fred 62"]]
             (mt/format-rows-by [int str]
               (api.field/search-values (db/select-one Field :id (mt/id :venues :id))
                                        (db/select-one Field :id (mt/id :venues :name))
                                        "Red"
                                        nil)))))
    (tqpt/test-timeseries-drivers
      (is (= [["139" "Red Medicine"]
              ["148" "Fred 62"]
              ["308" "Fred 62"]
              ["375" "Red Medicine"]
              ["396" "Fred 62"]
              ["589" "Fred 62"]
              ["648" "Fred 62"]
              ["72" "Red Medicine"]
              ["977" "Fred 62"]]
             (api.field/search-values (db/select-one Field :id (mt/id :checkins :id))
                                      (db/select-one Field :id (mt/id :checkins :venue_name))
                                      "Red"
                                      nil)))))
  (testing "make sure limit works"
    (mt/test-drivers (mt/normal-drivers)
      (is (= [[1 "Red Medicine"]]
             (mt/format-rows-by [int str]
                                (api.field/search-values (db/select-one Field :id (mt/id :venues :id))
                                                         (db/select-one Field :id (mt/id :venues :name))
                                                         "Red"
                                                         1)))))))

(deftest search-values-with-field-same-as-search-field-test
  (testing "make sure it also works if you use the same Field twice"
    (mt/test-drivers (mt/normal-drivers)
      (is (= [["Fred 62" "Fred 62"] ["Red Medicine" "Red Medicine"]]
             (api.field/search-values (db/select-one Field :id (mt/id :venues :name))
                                      (db/select-one Field :id (mt/id :venues :name))
                                      "Red"
                                      nil))))
    (tqpt/test-timeseries-drivers
      (is (= [["Fred 62" "Fred 62"] ["Red Medicine" "Red Medicine"]]
             (api.field/search-values (db/select-one Field :id (mt/id :checkins :venue_name))
                                      (db/select-one Field :id (mt/id :checkins :venue_name))
                                      "Red"
                                      nil))))))

(deftest field-values-remapped-fields-test
  (testing "GET /api/field/:id/values"
    (testing "Should return tuples of [original remapped] for a remapped Field (#13235)"
      (mt/dataset sample-dataset
        (mt/with-temp-copy-of-db
          ;; create a human-readable-values remapping. Do this via the API because the crazy things may or may not be
          ;; happening
          (is (partial= {:field_id                (mt/id :orders :product_id)
                         :human_readable_field_id (mt/id :products :title)
                         :type                    "external"}
                        (mt/user-http-request :crowberto :post 200
                                              (format "field/%d/dimension" (mt/id :orders :product_id))
                                              {:human_readable_field_id (mt/id :products :title)
                                               :name                    "Product ID"
                                               :type                    :external})))
          ;; trigger a field values rescan (this API endpoint is synchronous)
          (is (= {:status "success"}
                 (mt/user-http-request :crowberto :post 200 (format "field/%d/rescan_values" (mt/id :orders :product_id)))))
          ;; mark the Field as has_field_values = list
          (mt/with-temp-vals-in-db Field (mt/id :orders :product_id) {:has_field_values "list"}
            (is (partial= {:values [[1 "Rustic Paper Wallet"]
                                    [2 "Small Marble Shoes"]
                                    [3 "Synergistic Granite Chair"]]}
                          (mt/user-http-request :crowberto :get 200 (format "field/%d/values" (mt/id :orders :product_id)))))))))))
