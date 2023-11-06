(ns entity-graph.core-test
  (:require
    #?(:clj [clojure.pprint :refer [pprint]]
       :cljs [cljs.pprint :refer [pprint]])
    #?(:clj  [clojure.test :as t :refer        [is are deftest testing]]
       :cljs [cljs.test    :as t :refer-macros [is are deftest testing]])
    [entity-graph.core :refer [create-db check-attr transact pull pull-many cardinality-many?] :as eg]))

(comment
  (clojure.test/run-tests)
  (cljs.test/run-tests))

;; For Clojure Cursive plugin to be able to resolve these symbols
(declare thrown? thrown-with-msg?)

;; Gain access to non-public functions
(def prepare-tx-data #'entity-graph.core/prepare-tx-data)
(def resolve-lookup-ref-or-throw #'entity-graph.core/resolve-lookup-ref-or-throw)
(def wrap-id #'entity-graph.core/wrap-id)
(def wrap-ids #'entity-graph.core/wrap-ids)

;; =========
;; Test Database Schema

(defn by-global-id [db id]
  ((:db/eav db) (resolve-lookup-ref-or-throw db [:global-id id])))

(defn by-tempid [db tempids tempid]
  (get (:db/eav db) (tempids tempid)))

;; Schema for testing the code of entity-graph.
;; It contains attributes with various properties covering all or almost all possibilities
(def test-schema
  {:global-id {:db/doc "Used for easily fetching entities by lookup refs."
               :db/unique :db.unique/identity}
   ;; no AVE index
   :person/name
   {:db/cardinality :db.cardinality/one}
   :person/aliases
   {:db/cardinality :db.cardinality/many}
   ;; AVE index
   :person/email
   {:db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}
   :person/ssn
   {:db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}
   :person/city
   {:db/cardinality :db.cardinality/one
    :db/index       {:db/map-type :db.map-type/sorted-map}}
   :person/past-cities
   {:db/cardinality :db.cardinality/many
    :db/index       {:db/map-type :db.map-type/hash-map}}
   :person/salary
   {:db/cardinality :db.cardinality/one
    :db/index       {:db/map-type :db.map-type/avl-map}}
   :person/past-salaries
   {:db/cardinality :db.cardinality/many
    :db/index       {:db/map-type :db.map-type/avl-map}}

   ;; For sorted set test START
   :person/past-salaries-sorted
   {:db/cardinality :db.cardinality/many
    :db/index       {:db/map-type :db.map-type/avl-map}
    :db/sort        {:db/set-type :db.set-type/sorted-set :db/comparator <}}
   :person/past-salaries-avl
   {:db/cardinality :db.cardinality/many
    :db/index       {:db/map-type :db.map-type/avl-map}
    :db/sort        {:db/set-type :db.set-type/avl-set :db/comparator <}}
   :person/past-salaries-sorted-no-comparator
   {:db/cardinality :db.cardinality/many
    :db/index       {:db/map-type :db.map-type/avl-map}
    :db/sort        {:db/set-type :db.set-type/sorted-set}}
   :person/past-salaries-avl-no-comparator
   {:db/cardinality :db.cardinality/many
    :db/index       {:db/map-type :db.map-type/avl-map}
    :db/sort        {:db/set-type :db.set-type/avl-set}}
   ;; For sorted set test END

   :person/friend
   {:db/cardinality :db.cardinality/many
    :db/valueType   :db.type/ref}
   :person/best-friend
   {:db/cardinality :db.cardinality/one
    :db/valueType   :db.type/ref}
   :person/drivers-license
   {:db/cardinality :db.cardinality/one
    :db/valueType   :db.type/ref
    :db/isComponent true}
   ;; Component attribute cardinality many
   :person/passports
   {:db/cardinality :db.cardinality/many
    :db/valueType   :db.type/ref
    :db/isComponent true}
   ;; drivers license
   :drivers-license/number
   {:db/cardinality :db.cardinality/one
    :db/unique      :db.unique/value}
   :drivers-license/state
   {:db/cardinality :db.cardinality/one}
   :drivers-license/organ-donor
   {:db/cardinality :db.cardinality/one}
   ;; passport
   :passport/number
   {:db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}
   :passport/date-issued
   {:db/cardinality :db.cardinality/one}
   ;; boolean value
   :passport/annulled
   {:db/cardinality :db.cardinality/one}
   ;; :db.unique/value
   :reservation/code
   {:db/cardinality :db.cardinality/one
    :db/unique      :db.unique/value}
   :person/soulmate
   {:db/cardinality :db.cardinality/one
    :db/valueType   :db.type/ref
    :db/unique      :db.unique/identity}})

(def db-empty (create-db test-schema))

(def schema (:db/schema db-empty))

(deftest test-ave-index-maps
  (let [extra-schema
        {:person/city-comparator
         {:db/cardinality :db.cardinality/one
          :db/index       {:db/map-type :db.map-type/sorted-map :db/comparator #(pos? (compare %1 %2))}}
         :person/city-avl-comparator
         {:db/cardinality :db.cardinality/one
          :db/index       {:db/map-type :db.map-type/avl-map :db/comparator #(pos? (compare %1 %2))}}}
        db-empty (create-db (merge test-schema extra-schema))
        ave-city (get-in db-empty [:db/ave :person/city])
        ave-past-cities (get-in db-empty [:db/ave :person/past-cities])
        ave-salary (get-in db-empty [:db/ave :person/salary])
        ave-city-comparator (get-in db-empty [:db/ave :person/city-comparator])
        ave-city-avl-comparator (get-in db-empty [:db/ave :person/city-avl-comparator])]
    ;; :person/city
    (is (map? ave-city))
    (is (sorted? ave-city))
    (is (not= (type ave-city) #?(:clj clojure.data.avl.AVLMap :cljs clojure.data.avl.AVLMap)))
    ;; :person/past-cities
    (is (map? ave-past-cities))
    (is (not (sorted? ave-past-cities)))
    ;; :person/salary
    (is (map? ave-salary))
    (is (sorted? ave-salary))
    (is (= (type ave-salary) #?(:clj clojure.data.avl.AVLMap :cljs clojure.data.avl.AVLMap)))
    ;; :person/city-comparator
    (is (map? ave-city-comparator))
    (is (sorted? ave-city-comparator))
    (is (not= (type ave-city-comparator) #?(:clj clojure.data.avl.AVLMap :cljs clojure.data.avl.AVLMap)))
    (let [{:keys [db-after]} (transact db-empty [{:person/city-comparator "Atlanta"}
                                                 {:person/city-comparator "Zaragoza"}])]
      (is (= ["Zaragoza" "Atlanta"] (-> db-after :db/ave :person/city-comparator keys))))
    ;; :person/city-avl-comparator
    (is (map? ave-city-avl-comparator))
    (is (sorted? ave-city-avl-comparator))
    (is (= (type ave-city-avl-comparator) #?(:clj clojure.data.avl.AVLMap :cljs clojure.data.avl.AVLMap)))
    (let [{:keys [db-after]} (transact db-empty [{:person/city-avl-comparator "Atlanta"}
                                                 {:person/city-avl-comparator "Zaragoza"}])]
      (is (= ["Zaragoza" "Atlanta"] (-> db-after :db/ave :person/city-avl-comparator keys))))))

(deftest test-check-attr
  (is (= (map #(check-attr db-empty % :db/isRef) [:person/name :person/friend :person/drivers-license])
         [false true true]))
  (is (= (map #(check-attr db-empty % :db/isComponent) [:person/name :person/friend :person/drivers-license])
         [false false true]))
  (is (= (map #(check-attr db-empty % :db/cardinality) [:global-id :person/name :person/friend])
         [:db.cardinality/one :db.cardinality/one :db.cardinality/many]))
  (is (= (map #(check-attr db-empty % :db/unique) [:person/name :global-id :reservation/code])
         [:db.unique/false :db.unique/identity :db.unique/value]))
  (is (= (map #(check-attr db-empty % :db/sort) [:person/name :person/past-salaries :person/past-salaries-sorted :person/past-salaries-avl])
         [:db.sort/false :db.sort/false :db.sort/sorted-set :db.sort/avl-set]))
  (is (= (map #(check-attr db-empty % :db/index)
              [:person/name :person/email :person/friend :person/city :person/past-cities :person/past-salaries])
         [:db.index/false :db.index/hash-map :db.index/hash-map
          :db.index/sorted-map :db.index/hash-map :db.index/avl-map]))
  (is (= (map #(check-attr db-empty % :db/ave-form) [:person/name :person/email :person/city])
         [:db.ave-form/false :db.ave-form/single-e :db.ave-form/eset])))

;; =========
;; Basic Testing

(deftest simple-add-retract
  (let [tx-data [{:db/id "mary" :person/name "Mary Todd"}
                 {:person/name            "Abraham Lincoln"
                  :person/aliases         ["Abe"]
                  :person/email           "abe@whitehouse.gov"
                  :person/city            "Washington, D.C."
                  :person/past-cities     ["New York" "Chicago"]
                  :person/salary          777
                  :person/past-salaries   [111 333 555]
                  :person/soulmate        "mary"
                  :person/drivers-license {:db/id                       "license"
                                           :drivers-license/number "123"
                                           :drivers-license/state :NY
                                           :drivers-license/organ-donor false}
                  :person/passports [{:db/id "passport1"
                                      :passport/number "234"
                                      :passport/date-issued "11-15-2019"}]}
                 [:db/add "tom" :person/name "Thomas Jefferson"]
                 [:db/add "tom" :person/aliases "Tommy"]
                 [:db/add "tom" :person/aliases "TJ"]
                 [:db/add "tom" :person/email "tom@whitehouse.gov"]
                 [:db/add "tom" :person/city "Washington, D.C."]
                 [:db/add "tom" :person/past-cities "Shadwell Plantation"]
                 [:db/add "tom" :person/past-cities "Gordonsville"]
                 [:db/add "tom" :person/salary 666]
                 [:db/add "tom" :person/past-salaries 222]
                 [:db/add "tom" :person/past-salaries 444]]
        {:keys [tx-data db-after tempids]} (transact db-empty tx-data)
        abe-id (-> tx-data :map-add :no-id first :db/id)
        tom-id (get tempids "tom")
        mary-id (get tempids "mary")
        license-id (get tempids "license")
        passport1-id (get tempids "passport1")]
    (is (= (db-after :db/eav)
           {tom-id
            {:db/id tom-id,
             :person/name "Thomas Jefferson",
             :person/aliases #{"TJ" "Tommy"},
             :person/email "tom@whitehouse.gov",
             :person/city "Washington, D.C.",
             :person/past-cities #{"Gordonsville" "Shadwell Plantation"},
             :person/salary 666,
             :person/past-salaries #{222 444}}
            mary-id {:db/id       mary-id
                     :person/name "Mary Todd"}
            abe-id
            {:db/id abe-id
             :person/name "Abraham Lincoln",
             :person/aliases #{"Abe"},
             :person/email "abe@whitehouse.gov",
             :person/city "Washington, D.C.",
             :person/past-cities #{"New York" "Chicago"},
             :person/salary 777,
             :person/past-salaries #{111 333 555}
             :person/soulmate      mary-id
             :person/drivers-license license-id
             :person/passports       #{passport1-id}}
            license-id
            {:db/id                       license-id
             :drivers-license/number      "123"
             :drivers-license/organ-donor false
             :drivers-license/state       :NY}
            passport1-id
            {:db/id               passport1-id
             :passport/date-issued "11-15-2019"
             :passport/number      "234"}}))
    (is (= (db-after :db/ave)
           {:person/drivers-license {license-id abe-id},
            :drivers-license/number                    {"123" 4}
            :person/past-salaries
            {111 #{abe-id}, 222 #{tom-id}, 333 #{abe-id}, 444 #{tom-id}, 555 #{abe-id}},
            :person/past-salaries-avl-no-comparator {},
            :person/past-salaries-avl {},
            :person/salary {666 #{tom-id}, 777 #{abe-id}},
            :person/city {"Washington, D.C." #{abe-id tom-id}},
            :person/past-salaries-sorted-no-comparator {},
            :person/past-salaries-sorted {},
            :person/soulmate {mary-id abe-id},
            :passport/number {"234" passport1-id},
            :person/best-friend {},
            :reservation/code {},
            :person/passports {passport1-id abe-id},
            :global-id {},
            :person/past-cities
            {"Shadwell Plantation" #{tom-id},
             "Gordonsville" #{tom-id},
             "Chicago" #{abe-id},
             "New York" #{abe-id}},
            :person/friend {},
            :person/email {"tom@whitehouse.gov" tom-id, "abe@whitehouse.gov" abe-id},
            :person/ssn {}}))
    ;; Retract
    (let [tx-data
          [[:db/retract abe-id :person/name "Abraham Lincoln"]
           [:db/retract abe-id :person/aliases "Abe"]
           [:db/retract abe-id :person/email "abe@whitehouse.gov"]
           [:db/retract abe-id :person/city "Washington, D.C."]
           [:db/retract abe-id :person/past-cities "New York"]
           [:db/retract abe-id :person/past-cities "Chicago"]
           [:db/retract abe-id :person/salary 777]
           ;; retracting only two of three previously added past salaries
           [:db/retract abe-id :person/past-salaries 111]
           [:db/retract abe-id :person/past-salaries 333]
           ;; non-existent past-salary
           [:db/retract abe-id :person/past-salaries 3000]
           ;; elided value
           [:db/retract abe-id :person/soulmate]
           ;; component entity with elided value
           [:db/retract abe-id :person/drivers-license]
           [:db/retract abe-id :person/passports]
           ;; retract passport and license
           [:db/retract license-id :drivers-license/number "123"]
           [:db/retract license-id :drivers-license/state :NY]
           [:db/retract license-id :drivers-license/organ-donor false]
           [:db/retract passport1-id :passport/number "234"]
           [:db/retract passport1-id :passport/date-issued "11-15-2019"]
           ;; retract Thomas Jefferson
           [:db/retract tom-id :person/name "Thomas Jefferson"]
           [:db/retract tom-id :person/aliases "Tommy"]
           [:db/retract tom-id :person/aliases "TJ"]
           [:db/retract tom-id :person/email "tom@whitehouse.gov"]
           [:db/retract tom-id :person/city "Washington, D.C."]
           [:db/retract tom-id :person/past-cities "Shadwell Plantation"]
           [:db/retract tom-id :person/past-cities "Gordonsville"]
           [:db/retract tom-id :person/salary 666]
           [:db/retract tom-id :person/past-salaries 222]
           [:db/retract tom-id :person/past-salaries 444]
           [:db/retract mary-id :person/name]]
          {:keys [db-after]} (transact db-after tx-data)]
      (is (= (db-after :db/eav)
             {abe-id {:db/id                abe-id
                      :person/past-salaries #{555}}}))
      (is (= (db-after :db/ave)
             {:global-id                                 {}
              :drivers-license/number                    {}
              :passport/number                           {}
              :person/best-friend                        {}
              :person/city                               {}
              :person/drivers-license                    {}
              :person/email                              {}
              :person/friend                            {}
              :person/passports                          {}
              :person/past-cities                        {}
              :person/past-salaries                      {555 #{5}}
              :person/past-salaries-avl                  {}
              :person/past-salaries-avl-no-comparator    {}
              :person/past-salaries-sorted               {}
              :person/past-salaries-sorted-no-comparator {}
              :person/salary                             {}
              :person/soulmate                           {}
              :person/ssn                                {}
              :reservation/code                          {}})))))

;; test to ensure if-some is used in the code, not if-let, since if-let fails for 'false' values
(deftest boolean-value
  (let [tx-data [[:db/add "1" :passport/annulled true]
                 [:db/add "2" :passport/annulled false]
                 {:person/email "email3" :passport/annulled true}
                 {:person/email "email4" :passport/annulled false}]
        {:keys [db-after tempids]} (transact db-empty tx-data)
        id1 (get tempids "1")
        id2 (get tempids "2")
        id3 (get-in (:db/ave db-after) [:person/email "email3"])
        id4 (get-in (:db/ave db-after) [:person/email "email4"])]
    (is (= (:db/eav db-after)
           {id1 {:db/id id1, :passport/annulled true},
            id2 {:db/id id2, :passport/annulled false},
            id3 {:person/email "email3" :passport/annulled true, :db/id id3},
            id4 {:person/email "email4" :passport/annulled false, :db/id id4}}))
    (let [tx-data [[:db/add id1 :passport/annulled false]
                   [:db/add id2 :passport/annulled true]
                   {:db/id id3 :passport/annulled false}
                   {:db/id id4 :passport/annulled true}]
          {:keys [db-after tempids]} (transact db-after tx-data)]
      (is (= (:db/eav db-after)
             {id1 {:db/id id1, :passport/annulled false},
              id2 {:db/id id2, :passport/annulled true},
              id3 {:person/email "email3" :passport/annulled false , :db/id id3},
              id4 {:person/email "email4" :passport/annulled true, :db/id id4}})))))

(deftest lookup-ref-txs
  (let [tx-data [{:db/id "person-id"
                  :person/ssn "111-22-3344"
                  :person/name "name1"}]
        {:keys [db-after tempids]} (transact db-empty tx-data)
        person-id (get tempids "person-id")]
    (is (= person-id (resolve-lookup-ref-or-throw db-after [:person/ssn "111-22-3344"])))
    (let [{:keys [db-after]} (transact db-after [{:db/id [:person/ssn "111-22-3344"] :person/name "name2"}])]
      (is (= "name2" (-> db-after :db/eav (get person-id) :person/name))))
    (let [{:keys [db-after]} (transact db-after [[:db/add [:person/ssn "111-22-3344"] :person/name "name2"]])]
      (is (= "name2" (-> db-after :db/eav (get person-id) :person/name))))
    (let [{:keys [db-after]} (transact db-after [[:db/retract [:person/ssn "111-22-3344"] :person/name "name1"]])]
      (is (nil? (-> db-after :db/eav (get person-id) :person/name))))
    (let [{:keys [db-after]} (transact db-after [[:db/retract [:person/ssn "111-22-3344"] :person/name]])]
      (is (nil? (-> db-after :db/eav (get person-id) :person/name))))
    (is (thrown-with-msg? #?(:clj java.lang.AssertionError :cljs js/Error.) #":db.error/nil-value"
          (transact db-after [[:db/retract [:person/ssn "111-22-3344"] :person/name nil]])))
    (let [{:keys [db-after]} (transact db-after [[:db/retractEntity [:person/ssn "111-22-3344"]]])]
      (is (nil? (-> db-after :db/eav (get person-id)))))))

(deftest empty-coll-values-map-form
  (let [tx-data [{:db/id "empty-set" :person/past-salaries #{}}
                 {:db/id "empty-sequential" :person/past-salaries []}
                 {:person/past-salaries #{}}
                 {:person/past-salaries []}
                 {:db/id :empty-set :person/past-salaries #{}}
                 {:db/id :empty-sequential :person/past-salaries []}]
        {:keys [db-after tempids tx-data]} (transact db-empty tx-data)]
    (is (= {} tempids))
    (is (empty? (-> tx-data :map-add :tempids)))
    (is (empty? (-> tx-data :map-add :no-ids)))
    (is (empty? (-> tx-data :map-add :entity-id)))
    (is (empty? (:db/eav db-after)))))

(deftest test-sorted-sets
  (let [tx-data [{:global-id :not-sorted :person/past-salaries 100}
                 {:global-id :sorted :person/past-salaries-sorted 100}
                 {:global-id :sorted-nocomp :person/past-salaries-sorted-no-comparator 100}
                 {:global-id :avl :person/past-salaries-avl 100}
                 {:global-id :avl-nocomp :person/past-salaries-avl-no-comparator 100}]
        {:keys [db-after]} (transact db-empty tx-data)]
    ;; Not sorted
    (is (= #?(:clj clojure.lang.PersistentHashSet
              :cljs cljs.core/PersistentHashSet)
           (type (get (by-global-id db-after :not-sorted) :person/past-salaries))))
    ;; Sorted
    (is (= #?(:clj clojure.lang.PersistentTreeSet
              :cljs cljs.core/PersistentTreeSet)
           (type (get (by-global-id db-after :sorted) :person/past-salaries-sorted))))
    (is (= #?(:clj clojure.lang.PersistentTreeSet
              :cljs cljs.core/PersistentTreeSet)
           (type (get (by-global-id db-after :sorted-nocomp) :person/past-salaries-sorted-no-comparator))))
    ;; Sorted avl
    (is (= clojure.data.avl.AVLSet
           (type (get (by-global-id db-after :avl) :person/past-salaries-avl))))
    (is (= clojure.data.avl.AVLSet
           (type (get (by-global-id db-after :avl-nocomp) :person/past-salaries-avl-no-comparator))))
    ;; Test adding data to sorted attributes
    (let [tx-data [{:global-id :not-sorted :person/past-salaries 200}
                   {:global-id :sorted :person/past-salaries-sorted 200}
                   {:global-id :sorted-nocomp :person/past-salaries-sorted-no-comparator 200}
                   {:global-id :avl :person/past-salaries-avl 200}
                   {:global-id :avl-nocomp :person/past-salaries-avl-no-comparator 200}]
          {:keys [db-after]} (transact db-after tx-data)]
      ;; Not sorted
      (is (= #?(:clj clojure.lang.PersistentHashSet
                :cljs cljs.core/PersistentHashSet)
             (type (get (by-global-id db-after :not-sorted) :person/past-salaries))))
      ;; Sorted
      (is (= #?(:clj clojure.lang.PersistentTreeSet
                :cljs cljs.core/PersistentTreeSet)
             (type (get (by-global-id db-after :sorted)
                        :person/past-salaries-sorted))))
      (is (= #?(:clj clojure.lang.PersistentTreeSet
                :cljs cljs.core/PersistentTreeSet)
             (type (get (by-global-id db-after :sorted-nocomp) :person/past-salaries-sorted-no-comparator))))
      ;; Sorted avl
      (is (= clojure.data.avl.AVLSet
             (type (get (by-global-id db-after :avl) :person/past-salaries-avl))))
      (is (= clojure.data.avl.AVLSet
             (type (get (by-global-id db-after :avl-nocomp) :person/past-salaries-avl-no-comparator)))))))

;; =========
;; Cardinality Many Values

(deftest cardinality-many-values-in-map-form
  "Tests handling of different forms of :db.cardinality/many values in map form tx data"
  (let [tx-data [{:db/id "int" :person/past-salaries 5}
                 {:db/id "string" :person/past-cities "Paris"}
                 {:db/id "vector" :person/past-cities ["Paris"]}
                 {:db/id "set" :person/past-cities #{"Paris"}}
                 {:db/id "vec-vec" :person/past-cities [["Paris"]]}
                 {:db/id "map" :person/past-cities {:a 1}}]
        {:keys [db-after tempids]} (transact db-empty tx-data)
        int-id (tempids "int")
        string-id (tempids "string")
        vector-id (tempids "vector")
        set-id (tempids "set")
        vec-vec-id (tempids "vec-vec")
        map-id (tempids "map")]
    (is (= #{5} (-> (:db/eav db-after) (get int-id) :person/past-salaries)))
    (is (= #{"Paris"} (-> (:db/eav db-after) (get string-id) :person/past-cities)))
    (is (= #{"Paris"} (-> (:db/eav db-after) (get vector-id) :person/past-cities)))
    (is (= #{"Paris"} (-> (:db/eav db-after) (get set-id) :person/past-cities)))
    (is (= #{["Paris"]} (-> (:db/eav db-after) (get vec-vec-id) :person/past-cities)))
    (is (= #{{:a 1}} (-> (:db/eav db-after) (get map-id) :person/past-cities)))
    (is (= (:db/ave db-after)
           {:global-id                                 {}
            :drivers-license/number                    {}
            :passport/number                           {}
            :person/best-friend                        {}
            :person/city                               {}
            :person/drivers-license                    {}
            :person/email                              {}
            :person/friend                            {}
            :person/passports                          {}
            :person/past-cities                        {"Paris"   #{2
                                                                    3
                                                                    4}
                                                        ["Paris"] #{5}
                                                        {:a 1}    #{6}}
            :person/past-salaries                      {5 #{1}}
            :person/past-salaries-avl                  {}
            :person/past-salaries-avl-no-comparator    {}
            :person/past-salaries-sorted               {}
            :person/past-salaries-sorted-no-comparator {}
            :person/salary                             {}
            :person/soulmate                           {}
            :person/ssn                                {}
            :reservation/code                          {}}))
    ;; test union, when there are existing values already
    (let [tx-data
          [{:db/id (tempids "int") :person/past-salaries 6}
           {:db/id (tempids "string") :person/past-cities "London"}
           {:db/id (tempids "vector") :person/past-cities ["London"]}
           {:db/id (tempids "set") :person/past-cities #{"London"}}
           {:db/id (tempids "vec-vec") :person/past-cities [["London"]]}
           {:db/id (tempids "map") :person/past-cities {:b 2}}
           ]
          {:keys [db-after]} (transact db-after tx-data)]
      (is (= #{5 6} (-> (:db/eav db-after) (get int-id) :person/past-salaries)))
      (is (= #{"Paris" "London"} (-> (:db/eav db-after) (get string-id) :person/past-cities)))
      (is (= #{"Paris" "London"} (-> (:db/eav db-after) (get vector-id) :person/past-cities)))
      (is (= #{"Paris" "London"} (-> (:db/eav db-after) (get set-id) :person/past-cities)))
      (is (= #{["Paris"] ["London"]} (-> (:db/eav db-after) (get vec-vec-id) :person/past-cities)))
      (is (= #{{:a 1} {:b 2}} (-> (:db/eav db-after) (get map-id) :person/past-cities))))
    (let [tx-data
          [[:db/retract (tempids "int") :person/past-salaries 5]
           [:db/retract (tempids "string") :person/past-cities "Paris"]
           [:db/retract (tempids "vector") :person/past-cities "Paris"]
           [:db/retract (tempids "set") :person/past-cities "Paris"]
           [:db/retract (tempids "vec-vec") :person/past-cities ["Paris"]]
           [:db/retract (tempids "map") :person/past-cities {:a 1}]]
          {:keys [tx-data db-before db-after tempids]} (transact db-after tx-data)]
      (is (= (:db/eav db-after) {}))
      (is (= (:db/ave db-after)
             {:global-id                                 {}
              :drivers-license/number                    {}
              :passport/number                           {}
              :person/best-friend                        {}
              :person/city                               {}
              :person/drivers-license                    {}
              :person/email                              {}
              :person/friend                            {}
              :person/passports                          {}
              :person/past-cities                        {}
              :person/past-salaries                      {}
              :person/past-salaries-avl                  {}
              :person/past-salaries-avl-no-comparator    {}
              :person/past-salaries-sorted               {}
              :person/past-salaries-sorted-no-comparator {}
              :person/salary                             {}
              :person/soulmate                           {}
              :person/ssn                                {}
              :reservation/code                          {}})))))

(deftest cardinality-many-values-in-list-form
  "Tests handling of different forms of :db.cardinality/many values in list form tx data"
  (let [tx-data [[:db/add "int" :person/past-salaries 5]
                 [:db/add "string" :person/past-cities "Paris"]
                 [:db/add "string-vec" :person/past-cities ["Paris"]]
                 [:db/add "string-set" :person/past-cities #{"Paris"}]
                 [:db/add "vec-vec" :person/past-cities [["Paris"]]]
                 [:db/add "map-single-value" :person/past-cities {:a 1}]]
        {:keys [db-after tempids]} (transact db-empty tx-data)]
    (is (= #{5} (-> (:db/eav db-after) (get (tempids "int")) :person/past-salaries)))
    (is (= #{"Paris"} (-> (:db/eav db-after) (get (tempids "string")) :person/past-cities)))
    ;; datomic map form accepts "multivals", list form does NOT
    (is (= #{["Paris"]} (-> (:db/eav db-after) (get (tempids "string-vec")) :person/past-cities)))
    (is (= #{#{"Paris"}} (-> (:db/eav db-after) (get (tempids "string-set")) :person/past-cities)))
    (is (= #{[["Paris"]]} (-> (:db/eav db-after) (get (tempids "vec-vec")) :person/past-cities)))
    (is (= #{{:a 1}} (-> (:db/eav db-after) (get (tempids "map-single-value")) :person/past-cities)))
    (is (= (:db/ave db-after)
           {:global-id                                 {}
            :drivers-license/number                    {}
            :passport/number                           {}
            :person/best-friend                        {}
            :person/city                               {}
            :person/drivers-license                    {}
            :person/email                              {}
            :person/friend                            {}
            :person/passports                          {}
            :person/past-cities                        {"Paris"     #{2}
                                                        #{"Paris"}  #{4}
                                                        ["Paris"]   #{3}
                                                        [["Paris"]] #{5}
                                                        {:a 1}      #{6}}
            :person/past-salaries                      {5 #{1}}
            :person/past-salaries-avl                  {}
            :person/past-salaries-avl-no-comparator    {}
            :person/past-salaries-sorted               {}
            :person/past-salaries-sorted-no-comparator {}
            :person/salary                             {}
            :person/soulmate                           {}
            :person/ssn                                {}
            :reservation/code                          {}}))
    (let [tx-data
          [[:db/retract (tempids "int") :person/past-salaries 5]
           [:db/retract (tempids "string") :person/past-cities "Paris"]
           [:db/retract (tempids "string-vec") :person/past-cities ["Paris"]]
           [:db/retract (tempids "string-set") :person/past-cities #{"Paris"}]
           [:db/retract (tempids "vec-vec") :person/past-cities [["Paris"]]]
           [:db/retract (tempids "map-single-value") :person/past-cities {:a 1}]]
          {:keys [db-after]} (transact db-after tx-data)]
      (is (= (:db/eav db-after) {}))
      (is (= (:db/ave db-after)
             {:global-id                                 {}
              :drivers-license/number                    {}
              :passport/number                           {}
              :person/best-friend                        {}
              :person/city                               {}
              :person/drivers-license                    {}
              :person/email                              {}
              :person/friend                            {}
              :person/passports                          {}
              :person/past-cities                        {}
              :person/past-salaries                      {}
              :person/past-salaries-avl                  {}
              :person/past-salaries-avl-no-comparator    {}
              :person/past-salaries-sorted               {}
              :person/past-salaries-sorted-no-comparator {}
              :person/salary                             {}
              :person/soulmate                           {}
              :person/ssn                                {}
              :reservation/code                          {}})))))

;; =========
;; Expanding Nested Entities

(deftest test-nested-entities
  (let [db-empty (create-db {:global-id {:db/unique :db.unique/identity}
                             :user/favorite-color
                             {:db/valueType :db.type/ref
                              :db/isComponent true}
                             :user/friends
                             {:db/cardinality :db.cardinality/many
                              :db/valueType :db.type/ref
                              :db/isComponent true}})
        nested-maps
        [{:db/id               "pat"
          :global-id           "pat"
          :user/name           "Pat"
          :user/favorite-color {:db/id     "purple" :global-id "purple"
                                :color/hex "9C27B0" :color/name "Purple"}
          :user/friends        [{:db/id               "reese"
                                 :global-id           "reese"
                                 :user/name           "Reese"
                                 :user/favorite-color {:db/id     "red" :global-id "red"
                                                       :color/hex "D50000" :color/name "Red"}}
                                ;; assign id if not provided
                                {:global-id           "joe"
                                 :user/name           "Joe"
                                 :user/favorite-color {:global-id "white"
                                                       :color/hex "FFFFFF" :color/name "White"}}]}]
        {:keys [db-after tempids]} (transact db-empty nested-maps)]
    (is (= 6 (count (:db/eav db-after))))
    (is (= (by-global-id db-after "pat")
           {:db/id (tempids "pat"),
            :global-id "pat",
            :user/name "Pat",
            :user/favorite-color (:db/id (by-global-id db-after "purple")),
            :user/friends #{(:db/id (by-global-id db-after "reese"))
                            (:db/id (by-global-id db-after "joe"))}}))
    (is (= (by-global-id db-after "reese")
           {:db/id (tempids "reese"),
            :global-id "reese",
            :user/name "Reese",
            :user/favorite-color (:db/id (by-global-id db-after "red"))}))
    (is (= (by-global-id db-after "red")
           {:db/id (tempids "red"), :global-id "red", :color/hex "D50000", :color/name "Red"}))
    (is (= (dissoc (by-global-id db-after "joe") :db/id)
           {:global-id "joe",
            :user/name "Joe",
            :user/favorite-color (:db/id (by-global-id db-after "white"))}))
    (is (= (dissoc (by-global-id db-after "white") :db/id)
           {:global-id "white",
            :color/hex "FFFFFF",
            :color/name "White"}))
    (is (= (by-global-id db-after "purple")
           {:db/id (tempids "purple"),
            :global-id "purple",
            :color/hex "9C27B0",
            :color/name "Purple"}))))

;; =========
;; Tempid Resolution

(deftest test-retract-tempid
  (let [tx-data
        [{:person/name  "Abraham Lincoln"
          :person/email "abe@whitehouse.gov"}
         {:person/name  "John Adams"
          :person/email "jonnyadamas@whitehouse.gov"}
         {:person/name  "Thomas Jefferson"
          :person/email "tommy@whitehouse.gov"}]
        {:keys [db-after]} (transact db-empty tx-data)
        abe-id (resolve-lookup-ref-or-throw db-after [:person/email "abe@whitehouse.gov"])
        jonny-id (resolve-lookup-ref-or-throw db-after [:person/email "jonnyadamas@whitehouse.gov"])
        tommy-id (resolve-lookup-ref-or-throw db-after [:person/email "tommy@whitehouse.gov"])]
    (is (= 3 (count (pull-many db-after [:db/id] [[:person/email "abe@whitehouse.gov"]
                                                  [:person/email "jonnyadamas@whitehouse.gov"]
                                                  [:person/email "tommy@whitehouse.gov"]]))))
    (let [tx-data
          [;; resolve tempid via :person/email
           [:db/retract "abe" :person/name "Abraham Lincoln"]
           [:db/retract "abe" :person/email "abe@whitehouse.gov"]
           ;; resolve id via :person/email, includes tempid
           [:db/retract "jonny-adams" :person/email "jonnyadamas@whitehouse.gov"]]
          {:keys [tx-data db-before db-after tempids]} (transact db-after tx-data)]
      (is (= (get tempids "abe") abe-id))
      (is (= (get tempids "jonny-adams") jonny-id))
      (is (= (db-after :db/eav)
             {jonny-id {:person/name "John Adams", :db/id jonny-id},
              tommy-id
              {:person/name "Thomas Jefferson",
               :person/email "tommy@whitehouse.gov",
               :db/id tommy-id}}))
      (is (= (db-after :db/ave)
             {:global-id                                 {}
              :drivers-license/number                    {}
              :passport/number                           {}
              :person/best-friend                        {}
              :person/city                               {}
              :person/drivers-license                    {}
              :person/email                              {"tommy@whitehouse.gov" 3}
              :person/friend                            {}
              :person/passports                          {}
              :person/past-cities                        {}
              :person/past-salaries                      {}
              :person/past-salaries-avl                  {}
              :person/past-salaries-avl-no-comparator    {}
              :person/past-salaries-sorted               {}
              :person/past-salaries-sorted-no-comparator {}
              :person/salary                             {}
              :person/soulmate                           {}
              :person/ssn                                {}
              :reservation/code                          {}})))))

(deftest test-unique-identity
  (let [tx-data
        [{:db/id        "unique-identity"
          :person/email "unique-identity@mail.com"
          :person/name "unique-identity"}
         {:db/id       "non-unique"
          :person/name "non-unique"}
         ;; :db.unique/identity comes before non-unique attribute
         [:db/add "unq-id-within-tx" :person/email "unq-id-within-tx@mail.com"]
         [:db/add "unq-id-within-tx" :person/name "unq-id-within-tx"]
         ;; :db.unique/identity comes after non-unique attribute
         [:db/add "unq-id-within-tx2" :person/name "unq-id-within-tx2"]
         [:db/add "unq-id-within-tx2" :person/email "unq-id-within-tx2@mail.com"]]
        {:keys [db-after tempids]} (transact db-empty tx-data)
        id-unique-identity (get tempids "unique-identity")
        id-non-unique (get tempids "non-unique")
        id-unq-id-within-tx (get tempids "unq-id-within-tx")
        id-unq-id-within-tx2 (get tempids "unq-id-within-tx2")]
    (is (= (:db/eav db-after)
           {id-unique-identity
            {:db/id id-unique-identity, :person/email "unique-identity@mail.com", :person/name "unique-identity"},
            id-non-unique
            {:db/id id-non-unique, :person/name "non-unique"}
            id-unq-id-within-tx
            {:db/id        id-unq-id-within-tx,
             :person/email "unq-id-within-tx@mail.com",
             :person/name  "unq-id-within-tx"}
            id-unq-id-within-tx2
            {:db/id        id-unq-id-within-tx2,
             :person/email "unq-id-within-tx2@mail.com",
             :person/name  "unq-id-within-tx2"}}))
    (is (= "unique-identity@mail.com" (-> (:db/eav db-after) (get id-unique-identity) :person/email)))
    (is (= "non-unique" (-> (:db/eav db-after) (get id-non-unique) :person/name)))
    ;; Test additional assertions
    (let [tx-data
          [[:db/add "unq-id-within-tx@mail.com" :person/email "unq-id-within-tx@mail.com"]
           [:db/add "unq-id-within-tx@mail.com" :person/name "unq-id-within-tx-CHANGED"]
           [:db/add "unq-id-within-tx2@mail.com" :person/name "unq-id-within-tx2-CHANGED"]
           [:db/add "unq-id-within-tx2@mail.com" :person/email "unq-id-within-tx2@mail.com"]]
          {:keys [db-after]} (transact db-after tx-data)]
      (is (= "unq-id-within-tx-CHANGED" (-> (:db/eav db-after) (get id-unq-id-within-tx) :person/name)))
      (is (= "unq-id-within-tx2-CHANGED" (-> (:db/eav db-after) (get id-unq-id-within-tx2) :person/name))))
    ;; Test retractions
    (let [tx-data
          [[:db/retract "unique-identity" :person/email "unique-identity@mail.com"]
           [:db/retract "unique-identity" :person/name "unique-identity"]
           [:db/retract "non-unique" :person/name "non-unique"]]
          {:keys [db-after tempids]} (transact db-after tx-data)]
      ;; check removal of all unq/id attrs
      (is (nil? (-> (:db/eav db-after) (get (tempids "unique-identity")) :person/email)))
      (is (nil? (-> (:db/eav db-after) (get (tempids "unique-identity")) :person/name)))
      ;; check that attr remains
      (is (= "non-unique" (-> (:db/eav db-after) (get id-non-unique) :person/name)))
      (is (not= id-non-unique (-> (:db/eav db-after) (get (tempids "non-unique")))))))
  ;; Trickier cases
  (let [tx-data
        [{:db/id                  "temp"
          :person/ssn             "111-22-3344"
          :person/name            "John"}
         {:db/id                  "temp2"
          :person/email           "email"
          :person/name            "Tom"}]
        {:keys [db-after]} (transact db-empty tx-data)]
    ;; same tempid resolves to different entities based on different unq/id attrs
    (let [tx-data
          [[:db/add "1" :person/ssn "111-22-3344"]
           [:db/add "1" :person/name "Tom"]
           [:db/add "1" :person/email "email"]]]
      (is (thrown-with-msg? #?(:clj java.lang.AssertionError :cljs js/Error.) #":db.error/unique-conflict" (transact db-after tx-data)))))
  ;; "transaction matched" case, two tempids resolve to the same - newly created - entity id
  (let [tx-data
        [[:db/add "1" :person/email "email"]
         [:db/add "2" :person/email "email"]]
        {:keys [db-after tempids]} (transact db-empty tx-data)
        id1 (get tempids "1")
        id2 (get tempids "2")]
    (is (= id1 id2))
    (is (= 1 (count (:db/eav db-after))))))

(deftest simple-retraction-assertion-tempid-1
  (let [tx-data [[:db/retract "t" :person/email "email"]
                 [:db/add "t" :person/email "email"]]]
    (is (thrown-with-msg? #?(:clj java.lang.AssertionError :cljs js/Error.)
                          #":db.error/assertion-retraction-conflict" (transact db-empty tx-data)))))

(deftest simple-retraction-assertion-tempid-2
  (let [tx-data [[:db/retract "t" :person/email "email"]
                 [:db/add "t" :person/ssn "ssn"]]
        {:keys [db-after tempids]} (transact db-empty tx-data)]
    (is (= 1 (count (:db/eav db-after))))
    (is (contains? ((:db/eav db-after) (tempids "t")) :person/ssn))
    (is (not (contains? ((:db/eav db-after) (tempids "t")) :person/email)))))

(deftest add-retract-order-tempid-resolution
  (let [tx-data
        [[:db/add "p1" :person/email "email"]
         [:db/add "p1" :person/name "name"]
         [:db/add "p2" :person/ssn "111-22-3344"]
         [:db/add "p2" :person/name "name2"]]
        {:keys [db-after tempids]} (transact db-empty tx-data)
        id1 (get tempids "p1")
        id2 (get tempids "p2")
        tx-data
        [[:db/retract "t" :person/email "email"]
         [:db/add "t" :person/ssn "111-22-3344"]
         [:db/retract "t2" :person/email "email-new"]
         [:db/add "t2" :person/ssn "ssn-new"]]
        {:keys [db-after tempids]} (transact db-after tx-data)]
    ;; "t" resolves to last value found in AVE (via :person/ssn "111-22-3344", not via :person/email "email")
    (is (= id2 (get tempids "t")))
    ;; "t2" creates a new eid in db
    (is (and (not= id1 id2 (get tempids "t2"))))))

;; simple case
(deftest tempid-resolution-1
  (let [tx-data
        [[:db/add "p1" :person/email "email"]
         [:db/add "p2" :person/email "email"]
         [:db/add "p3" :person/email "email2"]]
        {:keys [db-after tempids]} (transact db-empty tx-data)
        id1 (get tempids "p1")
        id2 (get tempids "p2")
        id3 (get tempids "p3")]
    (is (= id1 id2))
    (is (not= id1 id3))
    (is (= 2 (count (:db/eav db-after)))))
  ;; similar to above, but split into 2 txs
  (let [tx-data
        [[:db/add "p1" :person/email "email"]]
        {:keys [db-after tempids]} (transact db-empty tx-data)
        id1 (get tempids "p1")]
    (let [tx-data
          [[:db/add "p2" :person/email "email"]
           [:db/add "p2" :person/name "name"]
           [:db/add "p3" :person/email "email2"]]
          {:keys [db-after tempids]} (transact db-after tx-data)]
      (is (= id1 (tempids "p2")))
      (is (not= id1 (tempids "p3")))
      (is (= 2 (count (:db/eav db-after)))))))

;; introduce conflicting tempid resolutions - result depends on order of forms
(deftest tempid-resolution-2
  (let [tx-data
        [[:db/add "p3" :person/email "email"]
         [:db/add "p3" :person/ssn "111-22-3344"]
         [:db/add "p1" :person/ssn "111-22-3344"]
         [:db/add "p2" :person/email "email"]]
        {:keys [db-after tempids]} (transact db-empty tx-data)]
    (is (= (tempids "p1") (tempids "p2") (tempids "p3")))
    (is (= 1 (count (:db/eav db-after)))))
  ;; similar to above, but split into 2 txs
  (let [tx-data
        [[:db/add "p3" :person/email "email"]
         [:db/add "p3" :person/ssn "111-22-3344"]]
        {:keys [db-after tempids]} (transact db-empty tx-data)
        p3-id (get tempids "p3")]
    (let [tx-data
          [[:db/add "p1" :person/ssn "111-22-3344"]
           [:db/add "p2" :person/email "email"]]
          {:keys [db-after tempids]} (transact db-after tx-data)]
      (is (= (tempids "p1") (tempids "p2") p3-id))
      (is (= 1 (count (:db/eav db-after))))))
  ;; Now change up the order
  (let [tx-data
        [[:db/add "p1" :person/ssn "111-22-3344"]
         [:db/add "p2" :person/email "email"]
         [:db/add "p3" :person/email "email"]
         [:db/add "p3" :person/ssn "111-22-3344"]]
        [_ prep-tempids] (prepare-tx-data db-empty tx-data)
        id1 (get prep-tempids "p1")
        id2 (get prep-tempids "p2")
        id3 (get prep-tempids "p3")]
    (is (thrown-with-msg? #?(:clj java.lang.AssertionError :cljs js/Error.)
                          #":db.error/unique-conflict" (transact db-empty tx-data)))
    (is (not= id1 id2))
    (is (= id2 id3)))
  ;; similar to above, but split into 2 txs
  (let [tx-data
        [[:db/add "p1" :person/ssn "111-22-3344"]
         [:db/add "p2" :person/email "email"]]
        {:keys [db-after tempids]} (transact db-empty tx-data)
        id1 (get tempids "p1")
        id2 (get tempids "p2")]
    (is (not= id1 id2))
    (let [tx-data
          [[:db/add "p3" :person/email "email"]
           [:db/add "p3" :person/ssn "111-22-3344"]
           [:db/add "p3" :person/name "p3-name"]]
          [prep-tx-data prep-tempids] (prepare-tx-data db-after tx-data)
          id3 (get prep-tempids "p3")]
      ;; java.lang.AssertionError: Assert failed: :db.error/unique-conflict Unique conflict:
      ;; :person/email, value: email already held by: 2 asserted for: 1 (= e held-by)
      (is (thrown-with-msg? #?(:clj java.lang.AssertionError :cljs js/Error.)
                            #":db.error/unique-conflict" (transact db-after tx-data)))
      (is (= id3 id1))
      (is (not= id3 id2)))))

(deftest tempid-resolution-map-no-id
  (let [tx-data [{:person/name "name"}]
        {:keys [db-after]} (transact db-empty tx-data)]
    (is (= 1 (count (:db/eav db-after))))
    (let [[id entity] (first (:db/eav db-after))]
      (is (contains? entity :person/name))
      (is (contains? entity :db/id))
      (is (= id (:db/id entity)))))
  (let [tx-data [[:db/add "p1" :person/ssn "1"]
                 {:person/ssn "1" :person/name "name"}]
        {:keys [db-after tempids]} (transact db-empty tx-data)]
    (is (= (get tempids "p1") (get-in (:db/ave db-after) [:person/ssn "1"])))
    (is (= 1 (count (:db/eav db-after)))))
  ;; same as above, but different order, which shouldn't make a difference
  (let [tx-data [{:person/ssn "1" :person/name "name"}
                 [:db/add "p1" :person/ssn "1"]]
        {:keys [db-after tempids]} (transact db-empty tx-data)]
    (is (= (get tempids "p1") (get-in (:db/ave db-after) [:person/ssn "1"])))
    (is (= 1 (count (:db/eav db-after)))))
  ;; same as above, but matching with existing db entities
  (let [tx-data [[:db/add "p1" :person/ssn "1" :person/name "name"]]
        {:keys [db-after]} (transact db-empty tx-data)]
    (let [tx-data [{:person/ssn "1" :person/name "name-change"}]
          {:keys [db-after]} (transact db-after tx-data)]
      (is (= 1 (count (:db/eav db-after))))
      (let [[id entity] (first (:db/eav db-after))]
        (is (= "name-change" (get entity :person/name)))
        (is (= "1" (get entity :person/ssn)))
        (is (contains? entity :db/id))
        (is (= id (:db/id entity)))))))

;; =========
;; Conflict Detection ... and Errors/Validation

(deftest test-detect-conflicts
  (let [tx-data [[:db/add "geoid" :person/name "Geo"]
                 [:db/add "geoid" :person/name "NewName"]]]
    (is (thrown-with-msg? #?(:clj java.lang.AssertionError :cljs js/Error.) #":db.error/cardinality-one-conflict" (transact db-empty tx-data)))))

(deftest test-validations
  (let [tx-data [[:db/bad-op "t" :person/email "email1"]]]
    (is (thrown-with-msg? #?(:clj java.lang.AssertionError :cljs js/Error.) #":db.error/invalid-op"
                          (transact db-empty tx-data))))
  (is (thrown-with-msg? #?(:clj java.lang.AssertionError :cljs js/Error.) #":db.error/nil-value"
                        (transact db-empty [[:db/retract :irrelevant :irrelevant nil]]))))

;; test assertions of cardinality/one attr
(deftest test-assertions-add-one
  ;; nil values illegal
  (let [tx-data
        [[:db/add "1" :person/name nil]]]
    (is (thrown-with-msg? #?(:clj java.lang.AssertionError :cljs js/Error.) #":db.error/nil-value"
                          (transact db-empty tx-data))))
  ;; can't assert and retract the same value
  (let [tx-data
        [[:db/add "1" :person/name "name1"]
         [:db/retract "1" :person/name "name1"]]]
    (is (thrown-with-msg? #?(:clj java.lang.AssertionError :cljs js/Error.) #":db.error/assertion-retraction-conflict"
                          (transact db-empty tx-data))))
  ;; asserting different values for same :db/cardinality/one [e a] should throw
  ;; previously no value in db for [e a]
  (let [tx-data
        [[:db/add "1" :person/name "name1"]
         [:db/add "1" :person/name "name2"]]]
    (is (thrown-with-msg? #?(:clj java.lang.AssertionError :cljs js/Error.) #":db.error/cardinality-one-conflict"
                          (transact db-empty tx-data))))
  ;; asserting different values for same :db.cardinality/one [e a] should throw
  ;; previously existing value in db for [e a] (different from asserted values)
  (let [tx-data
        [[:db/add "1" :person/name "name1"]]
        {:keys [db-after tempids]} (transact db-empty tx-data)
        person-id (get tempids "1")
        tx-data
        [[:db/add person-id :person/name "name2"]
         [:db/add person-id :person/name "name3"]]]
    (is (thrown-with-msg? #?(:clj java.lang.AssertionError :cljs js/Error.) #":db.error/cardinality-one-conflict"
                          (transact db-after tx-data))))
  ;; asserting different values for same :db/cardinality/one [e a] should throw
  ;; previously existing value in db for [e a] (existing value same as one of asserted values)
  (let [tx-data [[:db/add "1" :person/name "name1"]]
        {:keys [db-after tempids]} (transact db-empty tx-data)
        person-id (get tempids "1")
        tx-data
        [;; first assert the same value that existed before
         [:db/add person-id :person/name "name1"]
         ;; then attempt asserting another value
         [:db/add person-id :person/name "name2"]]]
    (is (thrown-with-msg? #?(:clj java.lang.AssertionError :cljs js/Error.) #":db.error/cardinality-one-conflict"
                          (transact db-after tx-data))))
  ;; same as above, but map-form
  (let [tx-data
        [{:db/id "1" :person/name "name1"}
         {:db/id "1" :person/name "name2"}]]
    (is (thrown-with-msg? #?(:clj java.lang.AssertionError :cljs js/Error.) #":db.error/cardinality-one-conflict"
                          (transact db-empty tx-data))))
  (let [tx-data [{:db/id "1" :person/name "name1"}]
        {:keys [db-after tempids]} (transact db-empty tx-data)
        person-id (get tempids "1")
        tx-data
        [{:db/id person-id :person/name "name2"}
         {:db/id person-id :person/name "name3"}]]
    (is (thrown-with-msg? #?(:clj java.lang.AssertionError :cljs js/Error.) #":db.error/cardinality-one-conflict"
                          (transact db-after tx-data))))
  (let [tx-data [{:db/id "1" :person/name "name1"}]
        {:keys [db-after tempids]} (transact db-empty tx-data)
        person-id (get tempids "1")
        tx-data
        [;; first assert the same value that existed before
         {:db/id person-id :person/name "name1"}
         ;; then attempt asserting another value
         {:db/id person-id :person/name "name2"}]]
    (is (thrown-with-msg? #?(:clj java.lang.AssertionError :cljs js/Error.) #":db.error/cardinality-one-conflict"
                          (transact db-after tx-data))))
  ;; same as above but for unique identity - shouldn't really make a difference
  (let [tx-data [[:db/add "1" :person/email "unique1@mail.com"]
                 [:db/add "1" :person/email "unique2@mail.com"]]]
    (is (thrown-with-msg? #?(:clj java.lang.AssertionError :cljs js/Error.) #":db.error/cardinality-one-conflict"
                          (transact db-empty tx-data))))
  (let [tx-data [[:db/add "1" :person/email "unique1@mail.com"]]
        {:keys [db-after tempids]} (transact db-empty tx-data)
        person-id (get tempids "1")
        tx-data
        [[:db/add person-id :person/name "unique1@mail.com"]
         [:db/add person-id :person/name "unique2@mail.com"]]]
    (is (thrown-with-msg? #?(:clj java.lang.AssertionError :cljs js/Error.) #":db.error/cardinality-one-conflict"
                          (transact db-after tx-data))))
  ;; unique identity conflict possible for entity id
  (let [tx-data [[:db/add "1" :person/email "unique1@mail.com"]
                 [:db/add "2" :person/email "unique2@mail.com"]]
        {:keys [db-after tempids]} (transact db-empty tx-data)
        id1 (get tempids "1")]
    (is (= 2 (count (:db/eav db-after))))
    (is (thrown-with-msg? #?(:clj java.lang.AssertionError :cljs js/Error.) #":db.error/unique-conflict"
                       (transact db-after [[:db/add id1 :person/email "unique2@mail.com"]]))))
  (let [tx-data [[:db/add "1" :reservation/code "AA"]
                 [:db/add "2" :reservation/code "AA"]]]
    (is (thrown-with-msg? #?(:clj java.lang.AssertionError :cljs js/Error.) #":db.error/unique-conflict"
                          (transact db-empty tx-data))))
  ;; asserting same cardinality one [e a v] multiple times should not throw
  (let [tx-data
        [[:db/add "1" :person/name "name"]
         [:db/add "1" :person/name "name"]]
        {:keys [db-after]} (transact db-empty tx-data)]
    (is (= 1 (count (:db/eav db-after)))))
  (let [tx-data [[:db/add "1" :person/name "name1"]]
        {:keys [db-after tempids]} (transact db-empty tx-data)
        tx-data [[:db/retractEntity (get tempids "1")]
                 [:db/add (get tempids "1") :person/name "name2"]]]
    (is (thrown-with-msg? #?(:clj java.lang.AssertionError :cljs js/Error.) #"Can't assert on a retracted entity."
                          (transact db-after tx-data))))
  (let [tx-data [{:person/name  "name" :reservation/code "ABCD"}]
        {:keys [db-after]} (transact db-empty tx-data)
        tx-data [[:db/add "1" :reservation/code "ABCD"]]]
    (is (is (thrown-with-msg? #?(:clj java.lang.AssertionError :cljs js/Error.) #":db.error/unique-conflict" (transact db-after tx-data)))))
  ;; sub-component can only have one parent
  (let [tx-data [{:db/id "ivan" :person/name "Ivan" :person/drivers-license {:db/id                  "license"
                                                                             :drivers-license/number "111"}}]
        {:keys [db-after tempids]} (transact db-empty tx-data)
        license-id (get tempids "license")]
    ;; attempting to add existing component entity under different attribute throws
    (is (thrown-with-msg? #?(:clj java.lang.AssertionError :cljs js/Error.) #":db.error/component-conflict"
                          (transact db-after [[:db/add (get tempids "ivan") :person/passports license-id]])))
    ;; testing that transaction didn't throw :db.error/component-conflict when it shouldn't
    (is (= (-> db-after :db/eav)
           (-> (transact db-after [[:db/add (get tempids "ivan") :person/drivers-license license-id]])
               :db-after :db/eav))))
  ;; test reassigning component to another parent within single tx
  (let [tx-data [{:db/id "person"
                  :person/name "name"
                  :person/drivers-license {:db/id "dl"
                                           :drivers-license/number 123
                                           :drivers-license/state "NY"}}]
        {:keys [db-after tempids]} (transact db-empty tx-data)
        person-id (tempids "person")
        license-id (tempids "dl")]
    ;; reassign driver's license from one person to another person
    (let [tx-data [{:db/id "person2" :person/name "another name" :person/drivers-license license-id}
                   [:db/retract person-id :person/drivers-license license-id]]
          {:keys [db-after tempids]} (transact db-after tx-data)
          person2-id (tempids "person2")]
      (is (nil? (-> db-after :db/eav (get person-id) :person/drivers-license)))
      (is (= license-id (-> db-after :db/eav (get person2-id) :person/drivers-license))))))

(deftest test-assertions-add-many
  (let [tx-data
        [[:db/add "1" :person/aliases nil]]]
    (is (thrown-with-msg? #?(:clj java.lang.AssertionError :cljs js/Error.) #":db.error/nil-value"
                          (transact db-empty tx-data))))
  ;; add/retract same [e a v] - new entity
  (let [tx-data [[:db/retract "ivan" :person/aliases "Ivan"]
                 [:db/add "ivan" :person/aliases "Ivan"]]]
    (is (thrown-with-msg? #?(:clj java.lang.AssertionError :cljs js/Error.) #":db.error/assertion-retraction-conflict"
                          (transact db-empty tx-data))))
  ;; add/retract same [e a v] - existing entity
  (let [tx-data [[:db/add "ivan" :person/aliases "Ivan"]]
        {:keys [db-after tempids]} (transact db-empty tx-data)
        ivan-id (get tempids "ivan")
        tx-data [[:db/retract ivan-id :person/aliases "Ivan"]
                 [:db/add ivan-id :person/aliases "Ivan"]]]
    (is (thrown-with-msg? #?(:clj java.lang.AssertionError :cljs js/Error.) #":db.error/assertion-retraction-conflict"
                          (transact db-after tx-data)))
    (let [tx-data [[:db/add "ivan" :person/aliases "Ivan"]]
          {:keys [db-after tempids]} (transact db-empty tx-data)
          ivan-id (get tempids "ivan")
          tx-data [[:db/add ivan-id :person/name "Ivan"]
                   [:db/retractEntity ivan-id]]]
      (is (thrown-with-msg? #?(:clj java.lang.AssertionError :cljs js/Error.) #":db.error/retracted-entity-conflict"
                            (transact db-after tx-data)))
      (is (thrown-with-msg? #?(:clj java.lang.AssertionError :cljs js/Error.) #":db.error/retracted-entity-conflict"
                            (transact db-after tx-data)))))
  ;; Sub-component can only have one parent
  (let [tx-data [{:db/id            "ivan"
                  :person/name      "Ivan"
                  :person/passports {:db/id                "passport"
                                     :passport/number      "111"
                                     :passport/date-issued "11-15-1982"
                                     :passport/annulled    false}}]
        {:keys [db-after tempids]} (transact db-empty tx-data)
        eav1 (:db/eav db-after)
        passport-id (get tempids "passport")
        ;; re-asserting same component value for same entity-attribute should succeed:
        {:keys [db-after]} (transact db-after [[:db/add (get tempids "ivan") :person/passports passport-id]])
        eav2 (:db/eav db-after)]
    (is (= eav1 eav2)))
  ;; asserting multiple parents for a component entity in a single transaction
  (let [tx-data [{:db/id "ivan" :person/name "Ivan" :person/passports {:db/id                "passport"
                                                                       :passport/number      "111"
                                                                       :passport/date-issued "11-15-1982"
                                                                       :passport/annulled    false}}
                 {:db/id "katya" :person/name "Katya" :person/passports {:db/id                "passport"
                                                                         :passport/number      "111"
                                                                         :passport/date-issued "11-15-1982"
                                                                         :passport/annulled    false}}]]
    (is (thrown-with-msg? #?(:clj java.lang.AssertionError :cljs js/Error.) #":db.error/component-conflict"
                          (transact db-empty tx-data))))
  ;; asserting a second parents for a component entity in a subsequent transaction
  (let [tx-data [{:db/id "ivan" :person/name "Ivan" :person/passports {:db/id                "passport"
                                                                         :passport/number      "111"
                                                                         :passport/date-issued "11-15-1982"
                                                                         :passport/annulled    false}}
                   {:db/id "katya" :person/name "Katya"}]
          {:keys [db-after tempids]} (transact db-empty tx-data)
          passport-id (get tempids "passport")
          katya-id (get tempids "katya")]
      (is (thrown-with-msg? #?(:clj java.lang.AssertionError :cljs js/Error.) #":db.error/component-conflict"
                            (transact db-after [[:db/add katya-id :person/passports passport-id]])))))

(deftest test-retract-entity
  (let [tx-data [
                 ;; this is the key entity to retract
                 {:db/id            "person-id"
                  :person/ssn       "111-22-3344"
                  :person/name      "name1"
                  :person/passports [{:db/id                "passport-id"
                                      :passport/number      "111222333"
                                      :passport/date-issued "01-01-2020"}]
                  :person/drivers-license {:db/id "license-id"
                                           :drivers-license/number "123"}}
                 ;; this entity points to the key entity from card one ref
                 {:db/id       "bff-id"
                  :person/name "bff name"
                  :person/best-friend "person-id"}
                 ;; this entity points to the key entity from card many ref
                 {:db/id       "friend-id"
                  :person/name "friend name"
                  :person/friend ["person-id"]}]
        {:keys [db-after tempids]} (transact db-empty tx-data)
        person-id (get tempids "person-id")
        bff-id (get tempids "bff-id")
        friend-id (get tempids "friend-id")
        passport-id (get tempids "passport-id")
        license-id (get tempids "license-id")]
    (is (= (:db/eav db-after)
           {person-id {:db/id            person-id,
                       :person/name      "name1",
                       :person/ssn       "111-22-3344",
                       :person/passports #{passport-id}
                       :person/drivers-license license-id},
            passport-id
            {:passport/number      "111222333",
             :passport/date-issued "01-01-2020",
             :db/id                passport-id}
            license-id
            {:db/id license-id
             :drivers-license/number "123"}
            ;; referencing from :person/best-friend
            bff-id
            {:db/id        bff-id
             :person/best-friend person-id
             :person/name  "bff name"}
            ;; referencing from :person/friend
            friend-id
            {:db/id          friend-id
             :person/friend #{1}
             :person/name    "friend name"}}))
    ;; Retract parent, make sure component also retracted
    (let [tx-data [[:db/retractEntity person-id]]
          {:keys [db-after]} (transact db-after tx-data)]
      ;; referring entities remain but the refs are removed
      (is (= (:db/eav db-after)
             {bff-id
              {:db/id        bff-id
               :person/name  "bff name"}
              friend-id
              {:db/id          friend-id
               :person/name    "friend name"}}))
      (is (= (:db/ave db-after)
             {:global-id                                 {}
              :drivers-license/number                    {}
              :person/drivers-license                    {},
              :person/past-salaries                      {},
              :person/past-salaries-avl                  {}
              :person/past-salaries-avl-no-comparator    {}
              :person/past-salaries-sorted               {}
              :person/past-salaries-sorted-no-comparator {}
              :person/salary                             {},
              :person/city                               {},
              :person/soulmate                           {},
              :passport/number                           {},
              :person/best-friend                        {},
              :reservation/code                          {},
              :person/passports                          {},
              :person/past-cities                        {},
              :person/friend                            {},
              :person/email                              {},
              :person/ssn                                {}})))
    ;; Retract components, make sure reference to components is gone from the parent
    (let [tx-data [[:db/retractEntity passport-id]
                   [:db/retractEntity license-id]]
          {:keys [db-after]} (transact db-after tx-data)]
      (is (= (get (:db/eav db-after) person-id)
             {:db/id       person-id,
              :person/name "name1"
              :person/ssn  "111-22-3344"})))))

(deftest unique-value
  (let [tx-data
        [{:db/id       "unique-value"
          :person/name "unique-value"
          :person/drivers-license
          {:db/id        "unique-value-dl"
           ;; ref type unique value
           :drivers-license-number 111}}
         {:db/id       "unique-value2"
          :person/email "unique-value2@mail.com"
          :person/name "unique-value2"
          :person/drivers-license
          {:db/id        "unique-value-dl2"
           ;; ref type unique value
           :drivers-license-number 222}}]
        {:keys [db-after tempids]} (transact db-empty tx-data)]
    (let [id-unique-value (get tempids "unique-value")
          id-unique-value-dl (get tempids "unique-value-dl")
          id-unique-value2 (get tempids "unique-value2")
          id-unique-value-dl2 (get tempids "unique-value-dl2")]
      (is (= (:db/eav db-after)
             {id-unique-value
              {:db/id                  id-unique-value, :person/name "unique-value"
               :person/drivers-license id-unique-value-dl},
              id-unique-value-dl
              {:db/id id-unique-value-dl, :drivers-license-number 111}
              id-unique-value2
              {:db/id                  id-unique-value2, :person/name "unique-value2"
               :person/email "unique-value2@mail.com"
               :person/drivers-license id-unique-value-dl2},
              id-unique-value-dl2
              {:db/id id-unique-value-dl2, :drivers-license-number 222}}))
      ;; FAILS: trying to assign unq/val to new entity, already held by existing entity
      (let [tx-data
            [[:db/add "temp" :person/drivers-license id-unique-value-dl2]
             [:db/add "temp" :person/name "NEW_NAME"]]
            tx-data-map
            [{:db/id "temp"
              :person/drivers-license id-unique-value-dl2
              :person/name "NEW_NAME"}]]
        (is (thrown-with-msg? #?(:clj java.lang.AssertionError :cljs js/Error.) #":db.error/component-conflict" (transact db-after tx-data)))
        (is (thrown-with-msg? #?(:clj java.lang.AssertionError :cljs js/Error.) #":db.error/component-conflict" (transact db-after tx-data-map))))
      ;; FAILS: :person/drivers-license value id-unique-value-dl already exists
      ;; and belongs to [:person/email "unique-value@mail.com"]
      (let [tx-data
            [[:db/add "temp" :person/drivers-license id-unique-value-dl]
             [:db/add "temp" :person/email "unique-value2@mail.com"]
             [:db/add "temp" :person/name "NEW_NAME"]]
            tx-data-map
            [{:person/drivers-license id-unique-value-dl
              :person/email           "unique-value2@mail.com"
              :person/name            "NEW_NAME"}]]
        (is (thrown-with-msg? #?(:clj java.lang.AssertionError :cljs js/Error.) #":db.error/component-conflict" (transact db-after tx-data)))
        (is (thrown-with-msg? #?(:clj java.lang.AssertionError :cljs js/Error.) #":db.error/component-conflict" (transact db-after tx-data-map))))
      ;; SUCCEEDS: [:person/email "unique-value2@mail.com"] already has
      ;; :person/drivers-license = id-unique-value-dl2
      (let [tx-data
            [[:db/add "temp" :person/drivers-license id-unique-value-dl2]
             [:db/add "temp" :person/email "unique-value2@mail.com"]
             [:db/add "temp" :person/name "NEW_NAME"]]
            {:keys [db-after tempids]} (transact db-after tx-data)
            id (tempids "temp")]
        (is (= "NEW_NAME" (-> db-after :db/eav (get id) :person/name)))
        (is (= "unique-value2@mail.com" (-> db-after :db/eav (get id) :person/email)))
        (is (= id-unique-value-dl2 (-> db-after :db/eav (get id) :person/drivers-license))))
      ;; same as above, but map form tx
      (let [tx-data-map
            [{:db/id                  "temp"
              :person/drivers-license id-unique-value-dl2
              :person/email           "unique-value2@mail.com"
              :person/name            "NEW_NAME"}]
            {:keys [db-after tempids]} (transact db-after tx-data-map)
            id (tempids "temp")]
        (is (= "NEW_NAME" (-> db-after :db/eav (get id) :person/name)))
        (is (= "unique-value2@mail.com" (-> db-after :db/eav (get id) :person/email)))
        (is (= id-unique-value-dl2 (-> db-after :db/eav (get id) :person/drivers-license)))))))

(deftest test-ref-values
  (let [tx-data [[:db/add "1" :person/name "John"]
                 [:db/add "1" :person/drivers-license "license"]]]
    (is (thrown-with-msg? #?(:clj java.lang.AssertionError :cljs js/Error.)
                          #":db.error/ref-resolution-error.*used only as value in transaction"
                          (transact db-empty tx-data))))
  (let [tx-data [[:db/add "1" :person/name "John"]
                 [:db/add "1" :person/best-friend [:person/email "a@a.com"]]]]
    (is (thrown-with-msg? #?(:clj java.lang.AssertionError :cljs js/Error.)
                          #":db.error/ref-resolution-error Could not resolve lookup ref"
                          (transact db-empty tx-data))))
  (let [tx-data [[:db/add "1" :person/name "John"]
                 [:db/add "1" :person/drivers-license "license"]
                 [:db/add "license" :drivers-license/number 111]]]
    (is (transact db-empty tx-data)))
  ;; this is no longer checked
  #_(let [tx-data [[:db/add "" :person/drivers-license 1]]]
      (thrown-with-msg? #?(:clj java.lang.AssertionError :cljs js/Error.)
                        #":db.error/ref-resolution-error.*:db/isRef attribute points to a non-existent entity"
                             (transact db-empty tx-data)))
  (let [tx-data [[:db/add "" :person/drivers-license -1]]]
    (is (thrown-with-msg? #?(:clj java.lang.AssertionError :cljs js/Error.)
                         #":db.error/ref-resolution-error A reference attribute contains invalid id type"
                         (transact db-empty tx-data)))))

(deftest test-nested-map-forms
  ;; map value for non-ref type attribute :person/salary treated as just a map value
  (let [tx-data [{:person/name "name" :person/salary {:person/name "nested-name"}}]
        {:keys [db-after]} (transact db-empty tx-data)]
    (is (= (:db/eav db-after)
           {1
            {:person/name "name",
             :person/salary #:person{:name "nested-name"},
             :db/id 1}})))
  ;; ref-type non sub-component, non-unique - FAIL
  (let [tx-data [{:person/name "name" :person/friend [{:person/name "friend1"}]}]]
    (is (thrown-with-msg? #?(:clj java.lang.AssertionError :cljs js/Error.) #":db.error/invalid-nested-entity" (transact db-empty tx-data))))
  ;; ref-type non sub-component, unique - NO FAIL
  (let [tx-data [{:person/name "name" :person/friend [{:person/name "friend1" :person/email "name@name.com"}]}]
        {:keys [db-after]} (transact db-empty tx-data)]
    (is (= (:db/eav db-after)
           {1 {:person/name "friend1", :person/email "name@name.com", :db/id 1},
            2 {:person/name "name", :person/friend #{1}, :db/id 2}})))
  ;; ref-type sub-component, non-unique - NO FAIL
  (let [tx-data [{:person/name "name" :person/drivers-license {:drivers-license/number 111}}]
        {:keys [db-after]} (transact db-empty tx-data)]
    (is (= (:db/eav db-after)
           {1 {:drivers-license/number 111, :db/id 1},
            2 {:person/name "name", :person/drivers-license 1, :db/id 2}})))
  ;; ref-type sub-component and unique - NO FAIL
  (let [tx-data [{:person/name "name" :person/passports [{:passport/number 111}]}]
        {:keys [db-after]} (transact db-empty tx-data)]
    (is (= (:db/eav db-after)
           {1 {:passport/number 111, :db/id 1},
            2 {:person/name "name", :person/passports #{1}, :db/id 2}}))))

;; =========
;; pull

(deftest pull-basic
  (let [tx-data
        [{:db/id "johnsmith"
          :person/name          "John Smith"
          :person/aliases       ["Jonny"]
          :person/email         "jsmith@jsmith.com"
          :person/city          "Washington, D.C."
          :person/past-cities   ["Houston" "Atlanta"]
          :person/salary        4000
          :person/past-salaries [1000 2000 3000]
          :person/drivers-license {:db/id "license"
                                   :drivers-license/number 123456
                                   :drivers-license/organ-donor true}
          :person/best-friend "jane"
          :person/friend ["tom" "josealdo"]}
         {:db/id "jane" :person/name "Jane"}
         {:db/id "tom" :person/name "Tom"}
         {:db/id "josealdo" :person/name "Jose Aldo"}]
        {:keys [db-after tempids]} (transact db-empty tx-data)
        johnsmith-id (tempids "johnsmith")
        jane-id (tempids "jane")
        tom-id (tempids "tom")
        jose-id (tempids "josealdo")
        license-id (tempids "license")]

    ;; test empty non-existent entity or attr
    (is (= {:db/id :does-not-exist} (pull db-after '[*] :does-not-exist)))
    (is (= {:db/id :does-not-exist} (pull db-after [:db/id] :does-not-exist)))
    (is (= {} (pull db-after [:any-attr] :does-not-exist)))
    (is (= {} (pull db-after '[:non-existent-attr] johnsmith-id)))

    ;; test wildcard
    (let [r (pull db-after '[*] johnsmith-id)]
      (is (= r
             {:person/drivers-license
              {:db/id license-id,
               :drivers-license/number 123456,
               :drivers-license/organ-donor true},
              :person/aliases #{"Jonny"},
              :person/past-salaries #{3000 1000 2000},
              :person/salary 4000,
              :person/city "Washington, D.C.",
              :person/name "John Smith",
              :db/id 1,
              :person/past-cities #{"Houston" "Atlanta"},
              :person/best-friend {:db/id jane-id},
              :person/friend #{{:db/id tom-id} {:db/id jose-id}},
              :person/email "jsmith@jsmith.com"})))

    ;; test attribute(s)
    (is (= {:person/name "John Smith"} (pull db-after '[:person/name] johnsmith-id)))

    (let [r (pull db-after '[:person/name :person/aliases :person/drivers-license] johnsmith-id)]
      (is (= r #:person{:name "John Smith",
                        :aliases #{"Jonny"},
                        :drivers-license
                        {:db/id license-id,
                         :drivers-license/number 123456,
                         :drivers-license/organ-donor true}})))

    ;; test non-recursive join
    (let [r (pull db-after '[:person/name {:person/drivers-license [:drivers-license/number]}]
                   johnsmith-id)]
      (is (= r #:person{:name            "John Smith",
                        :drivers-license #:drivers-license{:number 123456}})))

    ;; test join on attribute, include non-existent attribute
    (let [r (pull db-after '[:person/name {:person/drivers-license [:drivers-license/number :non-existant-attr]}]
                   johnsmith-id)]
      (is (= r
             {:person/name "John Smith",
              :person/drivers-license {:drivers-license/number 123456}})))

    ;; test join with nothing returned via join-attr
    (let [r (pull db-after '[:person/name {:person/drivers-license [:non-existant-attr]}]
                   johnsmith-id)]
      (is (= r #:person{:name "John Smith"})))

    ;; test join with nothing returned via join-attr and no non-join attributes
    (let [r (pull db-after '[{:person/drivers-license [:non-existant-attr]}] johnsmith-id)]
      (is (= {} r)))

    ;; test wildcard with join-attr
    (let [r (pull db-after '[{:person/drivers-license [*]}] johnsmith-id)]
      (is (= r #:person{:drivers-license
                        {:db/id 2,
                         :drivers-license/number 123456,
                         :drivers-license/organ-donor true}})))

    ;; test combining wildcard with non-recursive joins: wildcard first and wildcard last
    (let [r1 (pull db-after '[*
                              {:person/drivers-license [:drivers-license/number]}
                              {:person/best-friend [:person/name]}]
                    johnsmith-id)
          r2 (pull db-after '[{:person/drivers-license [:drivers-license/number]}
                              {:person/best-friend [:person/name]}
                              *]
                    johnsmith-id)]
      ;; multi->freqs needed? only if * might pull in duplicates for component cardinality many values
      ;; with no guaranteed order (because based on set with no order)
      ;; SO actually not needed because cardinality/many attrs are not components and no reverse references
      (is (= r1 r2
             {:person/drivers-license #:drivers-license{:number 123456},
              :person/aliases #{"Jonny"},
              :person/past-salaries #{3000 1000 2000},
              :person/salary 4000,
              :person/city "Washington, D.C.",
              :person/name "John Smith",
              :db/id johnsmith-id,
              :person/best-friend #:person{:name "Jane"},
              :person/past-cities #{"Houston" "Atlanta"},
              :person/email "jsmith@jsmith.com"
              :person/friend #{{:db/id tom-id} {:db/id jose-id}}})))

    ;; test combining wildcard with non-recursive reverse joins: wildcard first and wildcard last
    (let [r1 (pull db-after '[* :person/_drivers-license] license-id)
          r2 (pull db-after '[:person/_drivers-license *] license-id)]
      (is (= r1 r2))
      (is (= r1
             {:db/id license-id,
              :drivers-license/number 123456,
              :drivers-license/organ-donor true,
              :person/_drivers-license {:db/id johnsmith-id}})))

    ;; test combining wildcard with non-recursive reverse joins cardinality many: wildcard first and wildcard last
    (let [{:keys [db-after tempids]}
          (transact db-after [{:db/id "passport1" :passport/number 1}
                              {:db/id "passport2" :passport/number 2}
                              {:db/id johnsmith-id :person/passports ["passport1" "passport2"]}])
          passport1-id (get tempids "passport1")
          r1 (pull db-after '[:person/_passports *] passport1-id)
          r2 (pull db-after '[* :person/_passports] passport1-id)]
      (is (= r1 r2))
      (is (= r1 {:db/id passport1-id, :passport/number 1, :person/_passports {:db/id johnsmith-id}})))

    ;; Retract
    (let [tx-data
          [[:db/retract license-id :drivers-license/number]
           [:db/retract license-id :drivers-license/organ-donor]]
          {:keys [db-after]} (transact db-after tx-data)
          r (pull db-after '[{:person/drivers-license [*]}] johnsmith-id)]
      (is (= r {:person/drivers-license {:db/id 2}})))

    ;; Retract entity
    (let [{:keys [db-after]} (transact db-after [[:db/retractEntity license-id]])
          r (pull db-after '[{:person/drivers-license [*]}] johnsmith-id)]
      (is (= {} r)))))

(deftest test-pull-components
  (let [tx-data
        [{:db/id "johnsmith"
          :person/name          "John Smith"
          :person/aliases       ["Jonny"]
          :person/email         "jsmith@jsmith.com"
          :person/city          "Washington, D.C."
          :person/past-cities   ["Houston" "Atlanta"]
          :person/salary        4000
          :person/past-salaries [1000 2000 3000]
          :person/drivers-license {:db/id "license"
                                   :drivers-license/number 123456
                                   :drivers-license/organ-donor true}
          :person/best-friend "jane"
          :person/friend ["tom"]}
         {:db/id "jane" :person/name "Jane"}
         {:db/id "tom" :person/name "Tom"}]
        {:keys [db-after tempids]} (transact db-empty tx-data)
        johnsmith-id (tempids "johnsmith")
        jane-id (tempids "jane")
        tom-id (tempids "tom")
        license-id (tempids "license")]

    ;; test {:db/id id} returned with wildcard and attr pattern when component entity not in db
    (let [{:keys [db-after]} (transact db-after [[:db/retract license-id :drivers-license/number]
                                                 [:db/retract license-id :drivers-license/organ-donor]])]

      (is (= (pull db-after [:person/drivers-license] johnsmith-id)
             {:person/drivers-license {:db/id license-id}}))
      (is (= (pull db-after '[*] johnsmith-id)
             {:person/drivers-license {:db/id license-id},
              :person/aliases         #{"Jonny"},
              :person/past-salaries   #{3000 1000 2000},
              :person/salary          4000,
              :person/city            "Washington, D.C.",
              :person/name            "John Smith",
              :db/id                  1,
              :person/past-cities     #{"Houston" "Atlanta"},
              :person/best-friend     {:db/id 3}
              :person/friend         #{{:db/id 4}}
              :person/email           "jsmith@jsmith.com"})))

    ;; test existing component attribute recursively pulled with wildcard
    (is (= (pull db-after '[*] johnsmith-id)
           {:person/drivers-license
            {:db/id license-id,
             :drivers-license/number 123456,
             :drivers-license/organ-donor true},
            :person/aliases #{"Jonny"},
            :person/past-salaries #{3000 1000 2000},
            :person/salary 4000,
            :person/city "Washington, D.C.",
            :person/name "John Smith",
            :db/id johnsmith-id,
            :person/past-cities #{"Houston" "Atlanta"},
            :person/best-friend     {:db/id jane-id}
            :person/friend #{{:db/id tom-id}}
            :person/email "jsmith@jsmith.com"}))

    ;; test recursively pull components of components
    (let [{:keys [db-after tempids]}
          (transact db-after [{:db/id                  license-id
                               ;; Add cardinality/one component entity to existing component entity
                               :person/drivers-license {:db/id                       "license-recur"
                                                        :drivers-license/number      1234567
                                                        :drivers-license/organ-donor false}
                               ;; Add cardinality/many component entity to existing component entity
                               :person/passports       [{:db/id "passport1" :passport/number 1}
                                                        {:db/id "passport2" :passport/number 2}]}])
          license-id-recur (get tempids "license-recur")
          passport1-id (get tempids "passport1")
          passport2-id (get tempids "passport2")]

      ;; test wildcard recursive pull components
      (is (= (pull db-after '[*] johnsmith-id)
             {:person/drivers-license
              {:db/id license-id,
               :drivers-license/number 123456,
               :drivers-license/organ-donor true,
               :person/drivers-license
               {:db/id license-id-recur,
                :drivers-license/number 1234567,
                :drivers-license/organ-donor false},
               :person/passports
               [{:db/id passport1-id, :passport/number 1}
                {:db/id passport2-id, :passport/number 2}]},
              :person/aliases       #{"Jonny"},
              :person/past-salaries #{3000 1000 2000},
              :person/salary        4000,
              :person/city          "Washington, D.C.",
              :person/name          "John Smith",
              :db/id                johnsmith-id,
              :person/past-cities   #{"Houston" "Atlanta"},
              :person/best-friend     {:db/id jane-id}
              :person/friend #{{:db/id tom-id}}
              :person/email         "jsmith@jsmith.com"}))
      ;; test attr name in pattern recursive pull components
      (is (= (pull db-after '[:person/drivers-license] johnsmith-id)
             {:person/drivers-license
              {:db/id license-id,
               :drivers-license/number 123456,
               :drivers-license/organ-donor true,
               :person/drivers-license
               {:db/id license-id-recur,
                :drivers-license/number 1234567,
                :drivers-license/organ-donor false},
               :person/passports
               [{:db/id passport1-id, :passport/number 1} {:db/id passport2-id, :passport/number 2}]}})))))

(defn multi->freqs
  "Returns `pull-result` with following modifications:
   - Any vectors for any multi-value attributes in `attrs` are converted to a frequencies map
     - Applies to all forward cardinality many references and reverse references for non-unique/non-component attributes
   - Any multi-value attributes in `attrs` with empty coll values are removed (generated data may contain them)"
  [pull-result]
  ;; NOTE: Relies on the global `schema`
  (clojure.walk/postwalk
    (fn [x]
      (if (map-entry? x)
        (let [[a v] x]
          (if (or (and (eg/ref-type? schema a) (cardinality-many? schema a))
                  (and (eg/name-begins-with-underscore? a) (eg/ave-form-eset? schema (eg/reverse->attr-name a))))
            [a (frequencies v)]
            x))
        x))
    pull-result))

(defn pull-with-recursion* [min-friends]
  (let [;; Extra friends
        tx-data
        [{:db/id "abelincoln" :person/name "Aberaham Lincoln"}
         {:db/id "mmonroe" :person/name "Marilyn Monroe"}
         {:db/id "messi" :person/name "Lionel Messi"}
         {:db/id "amy" :person/name "Amy Winehouse"}
         {:db/id "jordan" :person/name "Michael Jordan"}
         {:db/id "johansson" :person/name "Scarlett Johansson"}]
        {db-after :db-after extra-friend-tempids :tempids} (transact db-empty tx-data)
        mandatory-friends (take min-friends (vals extra-friend-tempids))
        [johnsmith-friends-ids janegoodall-friends-ids tomjones-friends-ids hellenkeller-friends-ids]
        (take 4 (repeatedly #(set (concat (random-sample 0.5 (drop min-friends (vals extra-friend-tempids)))
                                          mandatory-friends))))
        tx-data
        [{:db/id              "johnsmith"
          :person/name        "John Smith"
          :person/best-friend "janegoodall"
          :person/friend     johnsmith-friends-ids}
         {:db/id              "janegoodall"
          :person/name        "Jane Goodall"
          :person/best-friend "tomjones"
          :person/friend     janegoodall-friends-ids}
         {:db/id              "tomjones"
          :person/name        "Tom Jones"
          :person/best-friend "hellenkeller"
          :person/friend     tomjones-friends-ids}
         {:db/id          "hellenkeller"
          :person/name    "Hellen Keller"
          :person/friend hellenkeller-friends-ids}]
        {:keys [db-after tempids]} (transact db-after tx-data)
        johnsmith-id (tempids "johnsmith")
        janegoodall-id (tempids "janegoodall")
        tomjones-id (tempids "tomjones")
        hellenkeller-id (tempids "hellenkeller")]

    ;; basic recursive joins, varying order and depth
    (is (= (pull db-after [:person/name {:person/best-friend 1}] johnsmith-id)
           #:person{:name "John Smith", :best-friend #:person{:name "Jane Goodall"}}))
    (is (= (pull db-after [{:person/best-friend 1} :person/name] johnsmith-id)
           #:person{:name "John Smith", :best-friend #:person{:name "Jane Goodall"}}))
    (is (= (pull db-after [:person/name {:person/best-friend 2}] johnsmith-id)
           #:person{:name "John Smith",
                    :best-friend
                    #:person{:name        "Jane Goodall",
                             :best-friend #:person{:name "Tom Jones"}}}))

    ;; pattern includes join-attr, cardinality one
    (is (= (pull db-after [:person/name :person/best-friend {:person/best-friend 2}] johnsmith-id)
           (assoc-in (pull db-after [:person/name {:person/best-friend 2}] johnsmith-id)
                     ;; NOTE: :db/id is included at final leaf of the tree under :person/best-friend
                     [:person/best-friend :person/best-friend :person/best-friend] (wrap-id hellenkeller-id))
           {:person/name "John Smith",
            :person/best-friend
            {:person/name "Jane Goodall",
             :person/best-friend
             ;; NOTE: :db/id is included at final leaf of the tree under :person/best-friend
             {:person/name "Tom Jones", :person/best-friend (wrap-id hellenkeller-id)}}}))

    ;; pattern includes join-attr, cardinality many (depth 0)
    (is (= (multi->freqs (pull db-after [:person/name :person/friend {:person/friend 0}] johnsmith-id))
           (multi->freqs
             (assoc-in (pull db-after [:person/name {:person/friend 0}] johnsmith-id)
                       ;; NOTE: :db/id is included at final leaf of the tree under :person/friend
                       [:person/friend] (wrap-ids johnsmith-friends-ids)))
           (multi->freqs
             ;; NOTE: :db/id is included at final leaf of the tree under :person/friend
             {:person/name "John Smith", :person/friend (wrap-ids johnsmith-friends-ids)})))

    ;; depth 2 with db/id
    (is (= (pull db-after [:db/id :person/name {:person/best-friend 2}] johnsmith-id)
           {:db/id       johnsmith-id,
            :person/name "John Smith",
            :person/best-friend
            {:person/name        "Jane Goodall",
             :db/id              janegoodall-id,
             :person/best-friend {:person/name "Tom Jones", :db/id tomjones-id}}}))
    (is (= (pull db-after [:person/name {:person/best-friend 3}] johnsmith-id)
           #:person{:name "John Smith",
                    :best-friend
                    #:person{:name "Jane Goodall",
                             :best-friend
                             #:person{:name "Tom Jones",
                                      :best-friend
                                      #:person{:name "Hellen Keller"}}}}))

    ;; early termination due to lack of best friends
    (is (= (pull db-after [:person/name {:person/best-friend 3}] hellenkeller-id)
           #:person{:name "Hellen Keller"}))
    (is (= (pull db-after [:person/name {:person/best-friend 3}] tomjones-id)
           #:person{:name        "Tom Jones",
                    :best-friend #:person{:name "Hellen Keller"}}))

    ;; =========
    ;; Combining recursive joins with wildcard

    ;; combining wildcard in recursive join
    (let [r1 (pull db-after ['* {:person/best-friend 2}] johnsmith-id)
          r2 (pull db-after [{:person/best-friend 2} '*] johnsmith-id)]
      (is (= (multi->freqs r1)
             (multi->freqs r2)
             (multi->freqs
               {:db/id          johnsmith-id,
                :person/name    "John Smith",
                :person/best-friend
                {:db/id          janegoodall-id,
                 :person/name    "Jane Goodall",
                 :person/best-friend
                 {:db/id              tomjones-id,
                  :person/name        "Tom Jones",
                  :person/best-friend (wrap-id hellenkeller-id),
                  :person/friend     (wrap-ids tomjones-friends-ids)},
                 :person/friend (wrap-ids janegoodall-friends-ids)},
                :person/friend (wrap-ids johnsmith-friends-ids)})))

      ;; combining wildcard in recursive join with dangling ref
      (let [{:keys [db-after]} (transact db-after [;; remove all attrs to create a dangling ref
                                                   [:db/retract tomjones-id :person/name]
                                                   [:db/retract tomjones-id :person/best-friend]
                                                   [:db/retract tomjones-id :person/friend]])
            r1 (pull db-after ['* {:person/best-friend 2}] johnsmith-id)
            r2 (pull db-after [{:person/best-friend 2} '*] johnsmith-id)]
        (is (= (multi->freqs r1) (multi->freqs r2)))
        (is (= (multi->freqs r1)
               (multi->freqs
                 {:db/id          johnsmith-id,
                  :person/name    "John Smith",
                  :person/best-friend
                  {:db/id          janegoodall-id,
                   :person/name    "Jane Goodall",
                   :person/best-friend (wrap-id tomjones-id),
                   :person/friend (wrap-ids janegoodall-friends-ids)},
                  :person/friend (wrap-ids johnsmith-friends-ids)})))))

    ;; test combining wildcard in recursive join cardinality many
    (let [r1 (pull db-after ['* {:person/friend 2}] johnsmith-id)
          r2 (pull db-after [{:person/friend 2} '*] johnsmith-id)]
      (is (= (multi->freqs r1) (multi->freqs r2)))
      (is (= (multi->freqs r1)
             (multi->freqs
               {:db/id johnsmith-id,
                :person/name "John Smith",
                :person/best-friend (wrap-id janegoodall-id),
                :person/friend (map #(get (:db/eav db-after) %) johnsmith-friends-ids)})))

      ;; combining wildcard in recursive join cardinality many with dangling ref
      (let [dangling-friend-id (first johnsmith-friends-ids)
            {:keys [db-after]} (transact db-after [;; remove all attrs to create a dangling ref
                                                   [:db/retract dangling-friend-id :person/name]
                                                   [:db/retract dangling-friend-id :person/best-friend]
                                                   [:db/retract dangling-friend-id :person/friend]])
            r1 (pull db-after ['* {:person/friend 2}] johnsmith-id)
            r2 (pull db-after [{:person/friend 2} '*] johnsmith-id)]
        (is (= (multi->freqs r1) (multi->freqs r2)))
        (is (= (multi->freqs r1)
               (multi->freqs
                 {:db/id          johnsmith-id,
                  :person/name    "John Smith",
                  :person/best-friend (wrap-id janegoodall-id),
                  ;; NOTE: mapping over (rest johnsmith-friends-ids) because one friend has been removed from db
                  :person/friend (map (fn [id]
                                        (if-let [entity (get (:db/eav db-after) id)]
                                          entity
                                          (wrap-id id)))
                                      johnsmith-friends-ids)})))))

    ;; =========
    ;; Multiple recursive clauses

    ;; multiple recursive clauses
    (let [;; Add friends of friends of johnsmith
          tx-data (reduce (fn [r id]
                            (conj r
                                  [:db/add (str id "f1") :person/name (str id " friend1")]
                                  {:db/id id :person/friend [(str id "f1")]}))
                          [] johnsmith-friends-ids)
          {:keys [db-after]} (transact db-after tx-data)
          ;; NOTE: the friends of "main" people have no :person/best-friend
          [johnsmith-friends janegoodall-friends tomjones-friends hellenkeller-friends]
          (map (fn [ids]
                 ;; NOTE: reduced depth from 2 to 1 in {:person/friend 1}
                 (map #(pull db-after [:person/name {:person/friend 1} {:person/best-friend 3}] %) ids))
               [johnsmith-friends-ids janegoodall-friends-ids tomjones-friends-ids hellenkeller-friends-ids])]
      (let [r1 (pull db-after [:person/name {:person/friend 2} {:person/best-friend 3}] johnsmith-id)
            r2 (pull db-after [:person/name {:person/best-friend 3} {:person/friend 2}] johnsmith-id)]
        (is (= r1 r2))
        (is (= (multi->freqs r1)
               (multi->freqs
                 {:person/name "John Smith",
                  :person/friend johnsmith-friends,
                  :person/best-friend
                  {:person/name "Jane Goodall",
                   :person/friend janegoodall-friends,
                   :person/best-friend
                   {:person/name "Tom Jones",
                    :person/friend tomjones-friends,
                    :person/best-friend
                    {:person/name "Hellen Keller",
                     :person/friend hellenkeller-friends}}}})))))

    ;; combining wildcard with multiple recursive joins
    (let [;; Add friends of friends of johnsmith
          tx-data (reduce (fn [r id]
                            (conj r
                                  [:db/add (str id "f1") :person/name (str id " friend1")]
                                  {:db/id id :person/friend [(str id "f1")]}))
                          [] johnsmith-friends-ids)
          {:keys [db-after]} (transact db-after tx-data)
          [johnsmith-friends janegoodall-friends tomjones-friends hellenkeller-friends]
          (map (fn [ids]
                 ;; NOTE: reduced depth from 2 to 1 in {:person/friend 1}
                 (map #(pull db-after '[* {:person/friend 1} {:person/best-friend 3}] %) ids))
               [johnsmith-friends-ids janegoodall-friends-ids tomjones-friends-ids hellenkeller-friends-ids])]
      ;; NOTE: test could be better, but good enough for now
      (let [r1 (pull db-after '[* {:person/friend 2} {:person/best-friend 3}] johnsmith-id)
            r2 (pull db-after '[{:person/best-friend 3} {:person/friend 2} *] johnsmith-id)]
        (is (= r1 r2))
        (is (= (multi->freqs r1)
               (multi->freqs
                 {:db/id johnsmith-id
                  :person/name "John Smith",
                  :person/friend johnsmith-friends,
                  :person/best-friend
                  {:db/id janegoodall-id
                   :person/name "Jane Goodall",
                   :person/friend janegoodall-friends,
                   :person/best-friend
                   {:db/id tomjones-id
                    :person/name "Tom Jones",
                    :person/friend tomjones-friends,
                    :person/best-friend
                    {:db/id hellenkeller-id
                     :person/name "Hellen Keller",
                     :person/friend hellenkeller-friends}}}}))))
      ;; -- Follow the :person/best-friend line of joins
      (let [[johnsmith-bff janegoodall-bff tomjones-bff hellenkeller-bff]
            (map (fn [id n]
                   (pull db-after ['* {:person/best-friend n} {:person/friend 2}] id))
                 [johnsmith-id janegoodall-id tomjones-id hellenkeller-id] [3 2 1 0])]
        (is (= (multi->freqs hellenkeller-bff)
               (multi->freqs
                 {:db/id          hellenkeller-id
                  :person/name    "Hellen Keller",
                  :person/friend hellenkeller-friends})))
        (is (= (multi->freqs tomjones-bff)
               (multi->freqs
                 {:db/id          tomjones-id
                  :person/name    "Tom Jones",
                  :person/friend tomjones-friends,
                  :person/best-friend
                  {:db/id          hellenkeller-id
                   :person/name    "Hellen Keller",
                   :person/friend hellenkeller-friends}})))
        (is (= (multi->freqs janegoodall-bff)
               (multi->freqs
                 {:db/id          janegoodall-id
                  :person/name    "Jane Goodall",
                  :person/friend janegoodall-friends,
                  :person/best-friend
                  {:db/id          tomjones-id
                   :person/name    "Tom Jones",
                   :person/friend tomjones-friends,
                   :person/best-friend
                   {:db/id          hellenkeller-id
                    :person/name    "Hellen Keller",
                    :person/friend hellenkeller-friends}}})))
        (is (= (multi->freqs johnsmith-bff)
               (multi->freqs
                 {:db/id          johnsmith-id
                  :person/name    "John Smith",
                  :person/friend johnsmith-friends,
                  :person/best-friend
                  {:db/id          janegoodall-id
                   :person/name    "Jane Goodall",
                   :person/friend janegoodall-friends,
                   :person/best-friend
                   {:db/id          tomjones-id
                    :person/name    "Tom Jones",
                    :person/friend tomjones-friends,
                    :person/best-friend
                    {:db/id          hellenkeller-id
                     :person/name    "Hellen Keller",
                     :person/friend hellenkeller-friends}}}})))))

    ;; =========
    ;; Reverse joins

    ;; reverse join cardinality one
    (is (= (pull db-after [:person/name {:person/_best-friend 2}] hellenkeller-id)
           #:person{:name "Hellen Keller",
                    :_best-friend
                    [#:person{:name         "Tom Jones",
                              :_best-friend [#:person{:name "Jane Goodall"}]}]}))
    (is (= (pull db-after [:person/name {:person/_best-friend 3}] hellenkeller-id)
           #:person{:name "Hellen Keller",
                    :_best-friend
                    [#:person{:name "Tom Jones",
                              :_best-friend
                              [#:person{:name "Jane Goodall",
                                        :_best-friend
                                        [#:person{:name "John Smith"}]}]}]}))

    ;; reverse join cardinality many
    ;; NOTE: start with db-empty
    (let [;; Add friends and friends of friends of johnsmith
          tx-data [;; -- friends of friends
                   {:db/id "messi" :person/name "Lionel Messi"}
                   {:db/id "amy" :person/name "Amy Winehouse"}
                   {:db/id "jordan" :person/name "Michael Jordan"}
                   {:db/id "johansson" :person/name "Scarlett Johansson"}
                   ;; -- friends
                   {:db/id "abelincoln" :person/name "Aberaham Lincoln" :person/friend ["messi" "amy"]}
                   {:db/id "mmonroe" :person/name "Marilyn Monroe" :person/friend ["jordan" "johansson"]}
                   ;; -- final boss
                   {:db/id "johnsmith" :person/name "John Smith"
                    :person/friend ["abelincoln" "mmonroe"]}]
          {:keys [db-after tempids]} (transact db-empty tx-data)
          johnsmith-id (get tempids "johnsmith")
          messi-id (get tempids "messi")
          lincoln-id (get tempids "abelincoln")
          r (pull db-after [:db/id :person/name {:person/_friend 2}] messi-id)]
      (is (= (multi->freqs r)
             (multi->freqs
               {:db/id       messi-id,
               :person/name "Lionel Messi",
               :person/_friend
               [{:person/name     "Aberaham Lincoln",
                 :db/id           lincoln-id,
                 :person/_friend [{:person/name "John Smith", :db/id johnsmith-id}]}]}))))

    ;; test combining wildcard in recursive join reverse
    (let [r1 (pull db-after '[* {:person/_best-friend 2}] tomjones-id)
          r2 (pull db-after '[{:person/_best-friend 2} *] tomjones-id)]
      (is (= (multi->freqs r1) (multi->freqs r2)))
      (is (multi->freqs r1)
          (multi->freqs
            {:db/id              tomjones-id,
             :person/name        "Tom Jones",
             :person/best-friend (wrap-id hellenkeller-id),
             :person/friend     (wrap-ids tomjones-friends-ids),
             :person/_best-friend
             [{:db/id              janegoodall-id,
               :person/name        "Jane Goodall",
               :person/best-friend (wrap-id tomjones-id),
               :person/friend     (wrap-ids janegoodall-friends-ids),
               :person/_best-friend
               [{:db/id              johnsmith-id,
                 :person/name        "John Smith",
                 :person/best-friend (wrap-id janegoodall-id),
                 :person/friend     (wrap-ids johnsmith-friends-ids)}]}]}))

      ;; combining wildcard in recursive join reverse with dangling ref
      (let [{:keys [db-after]} (transact db-after [;; remove references to create a dangling ref
                                                   ;; (entity is pointed to, but has no attributes)
                                                   [:db/retract tomjones-id :person/name]
                                                   [:db/retract tomjones-id :person/best-friend]
                                                   [:db/retract tomjones-id :person/friend]])
            r1 (pull db-after '[* {:person/_best-friend 2}] tomjones-id)
            r2 (pull db-after '[{:person/_best-friend 2} *] tomjones-id)]
        (is (= (multi->freqs r1) (multi->freqs r2)))
        (is (= (multi->freqs r1)
               (multi->freqs
                 {:db/id tomjones-id,
                  :person/_best-friend
                  [{:db/id              janegoodall-id,
                    :person/name        "Jane Goodall",
                    :person/best-friend (wrap-id tomjones-id),
                    :person/friend     (wrap-ids janegoodall-friends-ids),
                    :person/_best-friend
                    [{:db/id              johnsmith-id,
                      :person/name        "John Smith",
                      :person/best-friend (wrap-id janegoodall-id),
                      :person/friend     (wrap-ids johnsmith-friends-ids)}]}]})))))))

(deftest pull-with-recursion
  (pull-with-recursion* 0)
  (pull-with-recursion* 1)
  (pull-with-recursion* 2))

(deftest test-pull-with-recursion-cycles
  (let [;; Extra friends
        tx-data
        [{:db/id "abelincoln" :person/name "Aberaham Lincoln"}
         {:db/id "mmonroe" :person/name "Marilyn Monroe"}
         {:db/id "messi" :person/name "Lionel Messi" :person/friend ["abelincoln" "mmonroe"]}
         ;; Here's the cardinality/many :person/friend cycle: jordan -> amy -> jordan
         {:db/id "amy" :person/name "Amy Winehouse" :person/friend ["jordan" "johansson"]}
         {:db/id "jordan" :person/name "Michael Jordan" :person/friend ["messi" "amy"]}
         {:db/id "johansson" :person/name "Scarlett Johansson"}]
        {db-after             :db-after
         extra-friend-tempids :tempids} (transact db-empty tx-data)
        tx-data
        [{:db/id              "johnsmith"
          :person/name        "John Smith"
          :person/best-friend "janegoodall"
          :person/friend     (map extra-friend-tempids ["jordan" "johansson"])}
         {:db/id              "janegoodall"
          :person/name        "Jane Goodall"
          :person/best-friend "tomjones"}
         {:db/id              "tomjones"
          :person/name        "Tom Jones"
          ;; Here is the :person/best-friend cycle: johnsmith -> janegoodall -> tomjones -> johnsmith
          :person/best-friend "johnsmith"}]
        {:keys [db-after tempids]} (transact db-after tx-data)
        johnsmith-id (tempids "johnsmith")
        janegoodall-id (tempids "janegoodall")
        tomjones-id (tempids "tomjones")]

    ;; test cardinality/one cycles
    (let [r (pull db-after [:person/name {:person/best-friend 3}] johnsmith-id)]
      (is (= r
             {:person/name "John Smith",
              :person/best-friend
              {:person/name "Jane Goodall",
               :person/best-friend
               {:person/name "Tom Jones", :person/best-friend {:db/id johnsmith-id}}}})))

    ;; test cardinality/many cycles
    (let [r (pull db-after [:person/name {:person/friend 2}] (get extra-friend-tempids "jordan"))]
      (is (= (multi->freqs r)
             (multi->freqs
               {:person/name "Michael Jordan",
                :person/friend
                [{:person/name    "Amy Winehouse",
                  :person/friend [{:person/name "Scarlett Johansson"} {:db/id (get extra-friend-tempids "jordan")}]}
                 {:person/name "Lionel Messi",
                  :person/friend
                  [{:person/name "Aberaham Lincoln"}
                   {:person/name "Marilyn Monroe"}]}]}))))

    ;; testing reverse attributes cycle cardinality/one
    (let [r (pull db-after [:person/name {:person/_best-friend 3}] johnsmith-id)]
      (is (= r
             {:person/name "John Smith",
              :person/_best-friend
              [{:person/name "Tom Jones",
                :person/_best-friend
                [{:person/name "Jane Goodall", :person/_best-friend [{:db/id johnsmith-id}]}]}]}))
      ;; ensure there is no cycle
      (is (not (contains? (-> r :person/_best-friend :person/_best-friend) :person/best-friend))))

    ;; testing reverse attributes cycle cardinality/many
    (let [r (pull db-after [:person/name {:person/_friend 3}] (get extra-friend-tempids "messi"))]
      (is (= (multi->freqs r)
             (multi->freqs
               {:person/name "Lionel Messi",
                :person/_friend
                [{:person/name "Michael Jordan",
                  :person/_friend
                  [{:person/name "John Smith"}
                   {:person/name "Amy Winehouse", :person/_friend [{:db/id (get extra-friend-tempids "jordan")}]}]}]}))))

    ;; test unique attr for reverse cycle
    (let [tx-data [[:db/add johnsmith-id :person/soulmate janegoodall-id]
                   [:db/add janegoodall-id :person/soulmate tomjones-id]
                   ;; cycle: johnsmith-id -> janegoodall-id -> tomjones-id => johnsmith-id
                   [:db/add tomjones-id :person/soulmate johnsmith-id]]
          {:keys [db-after]} (transact db-after tx-data)]

      ;; FORWARD
      ;; no cycle
      (is (= (pull db-after [:person/name {:person/soulmate 2}] johnsmith-id)
             #:person{:name "John Smith",
                      :soulmate
                      #:person{:name     "Jane Goodall",
                               :soulmate #:person{:name "Tom Jones"}}}))
      ;; cycle
      (is (= (pull db-after [:person/name {:person/soulmate 3}] johnsmith-id)
             {:person/name "John Smith",
              :person/soulmate
              {:person/name "Jane Goodall",
               :person/soulmate
               {:person/name "Tom Jones", :person/soulmate {:db/id johnsmith-id}}}}))
      ;; REVERSE
      ;; no cycle
      (is (= (pull db-after [:person/name {:person/_soulmate 2}] johnsmith-id)
             #:person{:name "John Smith",
                      :_soulmate
                      #:person{:name      "Tom Jones",
                               :_soulmate #:person{:name "Jane Goodall"}}}))
      ;; cycle
      (is (= (pull db-after [:person/name {:person/_soulmate 3}] johnsmith-id)
             {:person/name "John Smith",
              :person/_soulmate
              {:person/name "Tom Jones",
               :person/_soulmate
               {:person/name "Jane Goodall", :person/_soulmate {:db/id johnsmith-id}}}})))))

(deftest test-pull-recursive-edge-cases
  (let [;; Extra friends
        tx-data
        [{:db/id "abelincoln" :person/name "Aberaham Lincoln"
          :person/best-friend :dangling-ref :person/friend [:dangling-ref1 :dangling-ref2]}
         {:db/id "mmonroe" :person/name "Marilyn Monroe" :person/age 20
          :person/best-friend "messi" :person/friend ["messi" "amy"]}
         {:db/id "messi" :person/name "Lionel Messi"
          :person/best-friend :dangling-ref :person/friend [:dangling-ref1 :dangling-ref2]}
         {:db/id "amy" :person/name "Amy Winehouse"
          :person/best-friend :dangling-ref :person/friend [:dangling-ref1 :dangling-ref2]}
         {:db/id "jordan" :person/name "Michael Jordan" :person/age 40
          :person/best-friend "johansson"}
         {:db/id "johansson" :person/name "Scarlett Johansson"}]
        {db-after :db-after extra-friend-tempids :tempids} (transact db-empty tx-data)
        abelincoln-id (get extra-friend-tempids "abelincoln")
        mmonroe-id (get extra-friend-tempids "mmonroe")
        jordan-id (extra-friend-tempids "jordan")
        tx-data
        [{:db/id              "johnsmith"
          :person/name        "John Smith"
          :person/best-friend :dangling-ref
          :person/friend     [:dangling-ref1 :dangling-ref2]}
         {:db/id              "janegoodall"
          :person/name        "Jane Goodall"
          :person/best-friend abelincoln-id
          :person/friend     [abelincoln-id mmonroe-id]}
         {:db/id              "tomjones"
          :person/name        "Tom Jones"
          :person/age         30
          :person/best-friend jordan-id
          :person/friend     [abelincoln-id]}]
        {:keys [db-after tempids]} (transact db-after tx-data)
        johnsmith-id (tempids "johnsmith")
        janegoodall-id (tempids "janegoodall")
        tomjones-id (tempids "tomjones")]

    ;; TEST dangling refs

    ;; test dangling ref cardinality-one
    (is (= (pull db-after [:person/name {:person/best-friend 2}] johnsmith-id)
           {:person/name "John Smith"}))
    ;; case with :db/id, but :person/best-friend is a dangling ref
    (is (= (pull db-after [:db/id :person/name {:person/best-friend 2}] johnsmith-id)
           {:db/id johnsmith-id,
            :person/name "John Smith",
            :person/best-friend #:db{:id :dangling-ref}}))
    ;; case with '*', but :person/best-friend is a dangling ref
    (is (= (pull db-after '[* :person/name {:person/best-friend 2}] johnsmith-id)
           {:db/id johnsmith-id,
            :person/name "John Smith",
            :person/best-friend #:db{:id :dangling-ref},
            :person/friend #{#:db{:id :dangling-ref2} #:db{:id :dangling-ref1}}}))
    (is (= (pull db-after [:person/name {:person/best-friend 3}] janegoodall-id)
           #:person{:name "Jane Goodall", :best-friend #:person{:name "Aberaham Lincoln"}}))
    ;; case with :db/id, but :person/best-friend is a dangling ref
    (is (= (pull db-after [:db/id :person/name {:person/best-friend 3}] janegoodall-id)
           {:db/id janegoodall-id,
            :person/name "Jane Goodall",
            :person/best-friend
            {:person/name "Aberaham Lincoln",
             :db/id abelincoln-id,
             :person/best-friend {:db/id :dangling-ref}}}))
    ;; case with '*, but :person/best-friend is a dangling ref
    (is (= (pull db-after '[* :person/name {:person/best-friend 3}] janegoodall-id)
           {:db/id janegoodall-id,
            :person/name "Jane Goodall",
            :person/best-friend
            {:db/id abelincoln-id,
             :person/name "Aberaham Lincoln",
             :person/best-friend {:db/id :dangling-ref},
             :person/friend #{{:db/id :dangling-ref2} {:db/id :dangling-ref1}}},
            :person/friend #{{:db/id 1} {:db/id 2}}}))
    ;; test dangling ref cardinality-many
    (is (= (pull db-after [:person/name {:person/friend 2}] johnsmith-id)
           {:person/name "John Smith"}))
    ;; case with :db/id, but :person/friend contains dangling refs
    (is (= (multi->freqs (pull db-after [:db/id :person/name {:person/friend 2}] johnsmith-id))
           (multi->freqs
             {:db/id         johnsmith-id
              ;; NOTE: :person/friend items come technical come in varied order - multi->freqs to the rescue
              :person/friend [{:db/id :dangling-ref2} {:db/id :dangling-ref1}]
              :person/name   "John Smith"})))
    ;; case with '*, but :person/friend contains dangling refs
    (is (= (multi->freqs (pull db-after '[* :person/name {:person/friend 2}] johnsmith-id))
           (multi->freqs
             {:db/id              johnsmith-id,
              :person/name        "John Smith",
              :person/best-friend {:db/id :dangling-ref},
              ;; NOTE: :person/friend items come technical come in varied order - multi->freqs to the rescue
              :person/friend      [{:db/id :dangling-ref2} {:db/id :dangling-ref1}]})))
    (is (= (multi->freqs (pull db-after [:person/name {:person/friend 4}] janegoodall-id))
           (multi->freqs
             {:person/name "Jane Goodall",
             :person/friend
             [{:person/name "Aberaham Lincoln"}
              {:person/name "Marilyn Monroe",
               :person/friend
               ;; NOTE: :person/friend items come technical come in varied order - multi->freqs to the rescue
               [{:person/name "Amy Winehouse"} {:person/name "Lionel Messi"}]}]})))

    ;; TEST empty maps

    ;; test empty maps cardinality-one

    ;; has no :person/best-friend
    ;(is (nil? (pull db-after [:not-there {:person/best-friend 2}] johnsmith-id)))
    (is (= {} (pull db-after [:not-there {:person/best-friend 2}] johnsmith-id)))
    ;; has :person/best-friend, but without :person/age
    ;(is (nil? (pull db-after [:person/age {:person/best-friend 2}] janegoodall-id)))
    (is (= {} (pull db-after [:person/age {:person/best-friend 2}] janegoodall-id)))
    ;; has :person/best-friend, with :person/age
    (is (= (pull db-after [:person/age {:person/best-friend 2}] tomjones-id)
           #:person{:age 30, :best-friend #:person{:age 40}}))

    ;; test empty maps cardinality-many

    ;; case 1: empty? join-val
    (is (= (pull db-after [:person/age {:person/friend 3}] tomjones-id)
           #:person{:age 30}))
    ;; case 2: fewer values in join-val
    (is (= (pull db-after [:person/age {:person/friend 3}] janegoodall-id)
           {:person/friend [{:person/age 20}]}))))
