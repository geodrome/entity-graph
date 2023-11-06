(ns entity-graph.benchmark-vs
  "Benchmark entity-graph against datascript and asami."
  (:require #?(:clj [clojure.pprint :refer [pprint]]
               :cljs [cljs.pprint :refer [pprint]])
            [asami.core :as asami]
            [datascript.core :as ds]
            [entity-graph.core :as eg]
            [entity-graph.benchmark :refer [db-sorted people20k-map-noid]]))

;; =========
;; Transact

;; NOTE: All attributes in Asami are multi-cardinality

;; NOTE: The schema for `db-sorted` indexes all attributes in AVE,
;; so that it `transact` performance can be compared fairly

(defn tx
  [db tx-data]
  (:db-after (eg/transact db tx-data)))

(comment
  ;; 2970 msecs
  (simple-benchmark []
                    (eg/transact db-sorted people20k-map-noid) 5)
  ;; 4370 msecs
  (simple-benchmark [conn (atom db-sorted)]
                    (swap! conn tx people20k-map-noid) 5)
  ;; 8710 msecs
  (simple-benchmark [ds-conn (ds/conn-from-db (ds/empty-db {:person/alias {:db/cardinality :db.cardinality/many}}))]
                    (ds/transact ds-conn people20k-map-noid) 5)
  ;; 10683 msecs
  (simple-benchmark [asami-conn (asami/connect (str "asami:mem://" (random-uuid)))]
                    (asami/transact asami-conn {:tx-data people20k-map-noid}) 5)
  )

;; CLJ
(comment
  ;; 949 msecs
  (let [conn (atom db-sorted)]
    (time (dotimes [_ 5] (swap! conn tx people20k-map-noid))))
  ;; 1831 msecs
  (let [ds-conn (ds/conn-from-db (ds/empty-db {:person/alias {:db/cardinality :db.cardinality/many}}))]
    (time (dotimes [_ 5] (ds/transact ds-conn people20k-map-noid))))
  ;; 1810 msecs
  (let [asami-conn (asami/connect (str "asami:mem://" (random-uuid)))]
    (time (dotimes [_ 5] (asami/transact asami-conn {:tx-data people20k-map-noid}))))
  )

;; =========
;; Pull

;; NOTE: asami doesn't support pull

(comment
  ;; 32 msecs
  (simple-benchmark [db-after (tx db-sorted people20k-map-noid)
                     _ (pprint (eg/pull db-after '[*] 1))]
                    (eg/pull db-after '[*] 1) 10000)

  ;; 118 msecs
  (simple-benchmark [ds-conn (ds/conn-from-db (ds/empty-db {:person/alias {:db/cardinality :db.cardinality/many}}))
                     _ (ds/transact ds-conn people20k-map-noid)
                     _ (pprint (ds/pull (ds/db ds-conn) '[*] 1))]
                    (ds/pull (ds/db ds-conn) '[*] 1) 10000)

  ;; 15 msecs
  (simple-benchmark [db-after (tx db-sorted people20k-map-noid)]
                    (eg/pull db-after '[:person/name] 1) 10000)
  ;; 34 msecs
  (simple-benchmark [ds-conn (ds/conn-from-db (ds/empty-db {:person/alias {:db/cardinality :db.cardinality/many}}))
                     _ (ds/transact ds-conn people20k-map-noid)]
                    (ds/pull (ds/db ds-conn) '[:person/name] 1) 10000)
  )

;; CLJ
(comment
  ;; 9 msecs
  (let [db-after (tx db-sorted people20k-map-noid)]
    (time (dotimes [_ 10000] (eg/pull db-after '[*] 1))))
  ;; 66 msecs
  (let [ds-conn (ds/conn-from-db (ds/empty-db {:person/alias {:db/cardinality :db.cardinality/many}}))
        _ (ds/transact ds-conn people20k-map-noid)]
    (time (dotimes [_ 10000] (ds/pull (ds/db ds-conn) '[*] 1))))

  ;; 3.45 msecs
  (let [db-after (tx db-sorted people20k-map-noid)]
    (time (dotimes [_ 10000] (eg/pull db-after '[:person/name] 1))))
  ;; 17.3 msecs
  (let [ds-conn (ds/conn-from-db (ds/empty-db {:person/alias {:db/cardinality :db.cardinality/many}}))
        _ (ds/transact ds-conn people20k-map-noid)]
    (time (dotimes [_ 10000] (ds/pull (ds/db ds-conn) '[:person/name] 1))))
  )

;; =========
;; Query

(def q1 '[:find ?e
          :where [?e :person/name "Ivan"]])

(def q2 '[:find ?e ?l ?a
          :where
          [?e :person/name "Ivan"]
          [?e :person/last-name ?l]
          [?e :person/age ?a]
          [?e :person/sex :sex/male]])

;; NOTE: entity-graph doesn't support datalog style queries
;; these are entity db analogs of the same queries:

(defn q1-edb
  [db]
  (get-in db [:db/ave :person/name "Ivan"]))

(defn q2-edb
  [db]
  (let [c1-ids (get-in db [:db/ave :person/name "Ivan"])
        c2-ids (get-in db [:db/ave :person/sex :sex/male])
        r-ids (clojure.set/intersection c1-ids c2-ids)]
    (map (fn [id] (select-keys (get-in db [:db/eav id]) [:db/id :person/last-name :person/age])) r-ids)))

(comment
  ;; q1: single where clause, single item in tuple
  ;; 1 msecs
  (simple-benchmark [db-after (tx db-sorted people20k-map-noid)
                     _ (println (count (q1-edb db-after)))]
                    (get-in db-after [:db/ave :person/name "Ivan"]) 1000)
  ;; 2999 msecs
  (simple-benchmark [ds-conn (ds/conn-from-db (ds/empty-db {:person/alias {:db/cardinality :db.cardinality/many}}))
                     _ (ds/transact ds-conn people20k-map-noid)
                     _ (println (count (ds/q q1 (ds/db ds-conn))))]
                    (ds/q q1 (ds/db ds-conn)) 1000)
  ;; 425 msecs
  (simple-benchmark [asami-conn (asami/connect (str "asami:mem://" (random-uuid)))
                     _ (asami/transact asami-conn {:tx-data people20k-map-noid})
                     _ (println (count (asami/q q1 (asami/db asami-conn))))]
                    (asami/q q1 (asami/db asami-conn)) 1000)

  ;; q2: multiple where clauses, multiple items in tuple
  ;; 873 msecs
  (simple-benchmark [db-after (tx db-sorted people20k-map-noid)
                     _ (println (count (q2-edb db-after)))]
                    (q2-edb db-after) 1000)
  ;; 14029 msecs
  (simple-benchmark [ds-conn (ds/conn-from-db (ds/empty-db {:person/alias {:db/cardinality :db.cardinality/many}}))
                     _ (ds/transact ds-conn people20k-map-noid)
                     _ (println (count (ds/q q2 (ds/db ds-conn))))]
                    (ds/q q2 (ds/db ds-conn)) 1000)
  ;; 286 msecs
  (simple-benchmark [asami-conn (asami/connect (str "asami:mem://" (random-uuid)))
                     _ (asami/transact asami-conn {:tx-data people20k-map-noid})
                     _ (println (count (asami/q q2 (asami/db asami-conn))))]
                    (asami/q q2 (asami/db asami-conn)) 1000)
  )

;; CLJ
(comment
  ;; q1: single where clause, single item in tuple
  ;; 1.21 msecs
  (let [db-after (tx db-sorted people20k-map-noid)
        _ (println (count (q1-edb db-after)))]
    (time (dotimes [_ 10000] (get-in db-after [:db/ave :person/name "Ivan"]))))
  ;; 6269 msecs
  (let [ds-conn (ds/conn-from-db (ds/empty-db {:person/alias {:db/cardinality :db.cardinality/many}}))
        _ (ds/transact ds-conn people20k-map-noid)
        _ (println (count (ds/q q1 (ds/db ds-conn))))]
    (time (dotimes [_ 10000] (ds/q q1 (ds/db ds-conn)))))
  ;; 293 msecs
  (let [asami-conn (asami/connect (str "asami:mem://" (random-uuid)))
        _ (asami/transact asami-conn {:tx-data people20k-map-noid})
        _ (println (count (asami/q q1 (asami/db asami-conn))))]
    (time (dotimes [_ 10000] (asami/q q1 (asami/db asami-conn)))))

  ;; q2: multiple where clauses, multiple items in tuple
  ;; 2532 msecs
  (let [db-after (tx db-sorted people20k-map-noid)
        _ (println (count (q2-edb db-after)))]
    (time (dotimes [_ 10000] (q2-edb db-after))))
  ;; 32046 msecs
  (let [ds-conn (ds/conn-from-db (ds/empty-db {:person/alias {:db/cardinality :db.cardinality/many}}))
        _ (ds/transact ds-conn people20k-map-noid)
        _ (println (count (ds/q q2 (ds/db ds-conn))))]
    (time (dotimes [_ 10000] (ds/q q2 (ds/db ds-conn)))))
  ;; 734 msecs
  (let [asami-conn (asami/connect (str "asami:mem://" (random-uuid)))
        _ (asami/transact asami-conn {:tx-data people20k-map-noid})
        _ (println (count (asami/q q2 (asami/db asami-conn))))]
    (time (dotimes [_ 10000] (asami/q q2 (asami/db asami-conn)))))
  )


