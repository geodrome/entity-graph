(ns entity-graph.benchmark
  (:require
    #?(:clj [clojure.pprint :refer [pprint]]
       :cljs [cljs.pprint :refer [pprint]])
    [entity-graph.core :refer [create-db transact cardinality-many? pull] :as eg]))

(def cardinality-many-values-to-sets #'entity-graph.core/cardinality-many-values-to-sets)
(def prepare-tx-data #'entity-graph.core/prepare-tx-data)

;; =========
;; Schema

(def bench-schema
  {:person/name
   {:db/index {:db/map-type :db.map-type/hash-map}}
   :person/last-name
   {:db/index {:db/map-type :db.map-type/hash-map}}
   :person/alias
   {:db/cardinality :db.cardinality/many
    :db/index       {:db/map-type :db.map-type/hash-map}}
   :person/sex
   {:db/index {:db/map-type :db.map-type/hash-map}}
   :person/age
   {:db/index {:db/map-type :db.map-type/hash-map}}
   :person/salary
   {:db/index {:db/map-type :db.map-type/hash-map}}})

(def bench-schema-sorted-ave
  (reduce (fn [new-schema attr]
            (assoc-in new-schema [attr :db/index :db/map-type] :db.map-type/sorted-map))
          bench-schema (keys bench-schema)))

(def bench-schema-avl-ave
  (reduce (fn [new-schema attr]
            (assoc-in new-schema [attr :db/index :db/map-type] :db.map-type/avl-map))
          bench-schema (keys bench-schema)))

(def db-empty (create-db bench-schema))
(def db-sorted (create-db bench-schema-sorted-ave))
(def db-avl (create-db bench-schema-avl-ave))

;; same schema can be reused for both dbs above
(def schema (:db/schema db-empty))

(comment
  (let [bench-schema (assoc bench-schema :nums {:db/cardinality :db.cardinality/many
                                                :db/sort        {:db/set-type :db.set-type/sorted-set
                                                                 :db/comparator <}})
        db-empty (create-db bench-schema)
        people10-map-tempid (map (fn [m] (assoc m :nums (take 3 (repeatedly #(rand-int 100))))) people10-map-tempid)
       {:keys [db-after]} (transact db-empty people10-map-tempid)
       ]
    ;(pprint people10-map-tempid)
   (pprint (take 3 (:db/eav db-after)))
   ))

;; =========
;; Benchmark Data Functions

(let [id (atom 0)]
  (defn gen-id [] (swap! id inc) @id))

(defn random-person []
  {:db/id            (str (gen-id))
   :person/name      (rand-nth ["Ivan" "Petr" "Sergei" "Oleg" "Yuri" "Dmitry" "Fedor" "Denis"])
   :person/last-name (rand-nth ["Ivanov" "Petrov" "Sidorov" "Kovalev" "Kuznetsov" "Voronoi"])
   :person/alias     (set (repeatedly (rand-int 10) #(rand-nth ["A. C. Q. W." "A. J. Finn" "A.A. Fair" "Aapeli"
                                                                "Aaron Wolfe" "Abigail Van Buren" "Jeanne Phillips"
                                                                "Abram Tertz" "Abu Nuwas" "Acton Bell" "Adunis"])))
   :person/sex       (rand-nth [:sex/male :sex/female])
   :person/age       (rand-int 100)
   :person/salary    (rand-int 100000)})

(def random-persons (repeatedly random-person))

(defn map->list1
  "Returns a seq of list form tx data equivalent of `map-form`."
  [schema map-form]
  (reduce-kv
    (fn [list-forms a v]
      (if (cardinality-many? schema a)
        (apply conj list-forms (map #(vector :db/add (:db/id map-form) a %) v))
        (conj list-forms [:db/add (:db/id map-form) a v])))
    [] (cardinality-many-values-to-sets schema (dissoc map-form :db/id))))

(defn map->list
  "Returns a seq of list form tx data equivalent of `map-forms` tx data."
  [schema map-forms]
  (mapcat #(map->list1 schema %) map-forms))

(defn list-assertions->retractions
  [tempids list-assertions]
  (map (fn [[_ e a v]] [:db/retract (get tempids e) a v]) list-assertions))

(defn list-assertions->entity-retractions
  [tempids list-assertions]
  (let [ids (set (map second list-assertions))]
    (map (fn [id] [:db/retractEntity (get tempids id)]) ids)))

;; =========
;; Benchmark Data

(def people20k-map-tempid (shuffle (take 20000 random-persons)))
(def people10-map-tempid (shuffle (take 10 random-persons)))

(def people10-map-noid (map #(dissoc % :db/id) (shuffle (take 10 random-persons))))
(def people20k-map-noid (map #(dissoc % :db/id) people20k-map-tempid))

(def people10-list-tempid (map->list schema people10-map-tempid))
(def people20k-list-tempid (map->list schema people20k-map-tempid))

;; =========
;; Benchmarks CLJS

(comment
  ;;;;;; Prepare Data

  ;; Map Form Assertions Prepare Data
  (simple-benchmark [] (prepare-tx-data db-empty people10-map-noid) 10000)
  (simple-benchmark [] (prepare-tx-data db-empty people20k-map-tempid) 5)
  (simple-benchmark [] (prepare-tx-data db-sorted people20k-map-tempid) 5)
  (simple-benchmark [] (prepare-tx-data db-avl people20k-map-tempid) 5)

  ;;;;;; Transact

  ;; Map Form Assertions

  ;; people10-map-noid
  (simple-benchmark [] (transact db-empty people10-map-noid) 10000)
  (simple-benchmark [] (transact db-sorted people10-map-noid) 10000)
  (simple-benchmark [] (transact db-avl people10-map-noid) 10000)

  ;; people10-map-tempid
  (simple-benchmark [] (transact db-empty people10-map-tempid) 10000)

  ;; people20k-map-noid
  (simple-benchmark [] (transact db-empty people20k-map-noid) 5)
  (simple-benchmark [] (transact db-sorted people20k-map-noid) 5)
  (simple-benchmark [] (transact db-avl people20k-map-noid) 5)

  ;; people20k-map-tempid
  (simple-benchmark [] (transact db-empty people20k-map-tempid) 5)

  ;; Map Form Assertions - Overwrite - faster than writing to empty db
  (simple-benchmark [{:keys [db-after tempids]} (transact db-empty people10-map-tempid)
                     people10-map-entity-id (eg/replace-tempids-map people10-map-tempid tempids)]
                    (transact db-after people10-map-entity-id) 10000)
  (simple-benchmark [{:keys [db-after tempids]} (transact db-empty people20k-map-tempid)
                     people20k-map-entity-id (eg/replace-tempids-map people20k-map-tempid tempids)]
                    (transact db-after people20k-map-entity-id) 5)

  ;; Map Form Assertions for component checks:
  (simple-benchmark [schema (assoc schema :component1 {:db/valueType :db.type/ref}
                                          :component2 {:db/valueType :db.type/ref}
                                          :component3 {:db/valueType :db.type/ref})
                     people10-map-tempid (map #(assoc % :component1 (str (inc (js/parseInt (:db/id %)))))
                                              (butlast people10-map-tempid))]
                    (transact db-empty people10-map-tempid) 10000)
  (simple-benchmark [schema (assoc schema :component1 {:db/valueType :db.type/ref}
                                          :component2 {:db/valueType :db.type/ref}
                                          :component3 {:db/valueType :db.type/ref})
                     people20k-map-tempid (map #(assoc % :component1 (str (inc (js/parseInt (:db/id %)))))
                                               (butlast people20k-map-tempid))]
                    (transact db-empty people20k-map-tempid) 5)

  ;; List Form Assertions
  (simple-benchmark [] (transact db-empty people10-list-tempid) 10000)
  (simple-benchmark [] (transact db-empty people20k-list-tempid) 5)

  ;; List Form Assertions - Overwrite
  (simple-benchmark [{:keys [db-after tempids]} (transact db-empty people10-list-tempid)
                     people10-list-entity-id (eg/replace-tempids-list people10-list-tempid tempids)]
                    (transact db-after people10-list-entity-id) 10000)
  (simple-benchmark [{:keys [db-after tempids]} (transact db-empty people20k-list-tempid)
                     people20k-list-entity-id (eg/replace-tempids-list people20k-list-tempid tempids)]
                    (transact db-after people20k-list-entity-id) 5)

  ;; Retractions
  (simple-benchmark [{:keys [db-after tempids]} (transact db-empty people10-list-tempid)
                     people10-retract (list-assertions->retractions tempids people10-list-tempid)]
                    (transact db-after people10-retract) 10000)
  (simple-benchmark [{:keys [db-after tempids]} (transact db-empty people20k-list-tempid)
                     people20k-retract (list-assertions->retractions tempids people20k-list-tempid)]
                    (transact db-after people20k-retract) 5)

  ;; retractEntity
  (simple-benchmark [{:keys [db-after tempids]} (transact db-empty people10-list-tempid)
                     people10-retract-entity (list-assertions->entity-retractions tempids people10-list-tempid)]
                    (transact db-after people10-retract-entity) 10000)
  (simple-benchmark [{:keys [db-after tempids]} (transact db-empty people20k-list-tempid)
                     people20k-retract-entity (list-assertions->entity-retractions tempids people20k-list-tempid)]
                    (transact db-after people20k-retract-entity) 5)

  ;;;;;; Misc

  ;; pull
  (simple-benchmark [{:keys [db-after]} (transact db-empty people10-map-noid)]
                    (pull db-after '[*] 1) 10000)

  ;; expand-nested-entities
  ;; no nested entities
  (simple-benchmark [] (eg/expand-nested-entities (:db/schema db-empty) people20k-map-tempid) 1)

  ;; resolve-temp-ids-unique-identity
  ;; no :db.unique/identity attrs in schema
  (simple-benchmark [{:keys [db-after]} (transact db-empty people20k-map-noid)]
                    (eg/resolve-tempids db-after people20k-map-tempid) 1)
  )

;; =========
;; Benchmarks CLJ

(comment
  ;;;;;; Prepare Data

  ;; Map Form Assertions Prepare Data
  (time (dotimes [_ 40000] (prepare-tx-data db-empty people10-map-noid)))
  (time (dotimes [_ 40000] (prepare-tx-data db-empty people10-map-tempid)))
  (time (dotimes [_ 20] (prepare-tx-data db-empty people20k-map-noid)))
  (time (dotimes [_ 20] (prepare-tx-data db-empty people20k-map-tempid)))

  ;; List Form Assertions Prepare Data
  (time (dotimes [_ 40000] (prepare-tx-data db-empty people10-list-tempid)))
  (time (dotimes [_ 20] (prepare-tx-data db-empty people20k-list-tempid)))

  ;;;;;; Transact

  ;; Map Form Assertions
  (time (dotimes [_ 40000] (transact db-empty people10-map-noid)))
  (time (dotimes [_ 40000] (transact db-empty people10-map-tempid)))
  (time (dotimes [_ 20] (transact db-empty people20k-map-noid)))
  (time (dotimes [_ 20] (transact db-empty people20k-map-tempid)))

  ;; List Form Assertions
  (time (dotimes [_ 40000] (transact db-empty people10-list-tempid)))
  (time (dotimes [_ 20] (transact db-empty people20k-list-tempid)))

  ;; Retractions
  (let [{:keys [db-after tempids]} (transact db-empty people10-list-tempid)
        people10-retract (list-assertions->retractions tempids people10-list-tempid)]
    (time (dotimes [_ 40000] (transact db-after people10-retract))))
  (let [{:keys [db-after tempids]} (transact db-empty people20k-list-tempid)
        people20k-retract (list-assertions->retractions tempids people20k-list-tempid)]
    (time (dotimes [_ 40000] (transact db-after people20k-retract))))
  )
