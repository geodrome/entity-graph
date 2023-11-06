(ns entity-graph.scratch)

;; =========
;; Indexing Helpers

;; EAV index - currently unused

(defn index-eav-one
  "Adds [e a v] to eav index. `a` must be a `:db.cardinatliy/one` attribute."
  [eav e a v]
  (let [eav-e (get eav e {:db/id e})
        eav-e (assoc eav-e a v)]
       (assoc! eav e eav-e)))

;; existence of (eav e) must be (is) checked up the stack, else end up (assoc! eav e eav-e[=nil])
(defn unindex-eav-one
  "Removes [e a] from eav index. `a` must be a `:db.cardinatliy/one` attribute."
  [eav e a]
  (let [eav-e (dissoc (eav e) a)]
       (assoc! eav e eav-e)))

(defn index-eav-many
  "Adds [e a v] to eav index. `a` must be a :db.cardinatliy/many attribute."
  [eav e a v]
  (let [eav-e (get eav e {:db/id e})
        v-set (conj (get eav-e a #{}) v)
        eav-e (assoc eav-e a v-set)]
       (assoc! eav e eav-e)))

;; existence of (eav e) must be (is) checked up the stack, else end up (assoc! eav e eav-e[=nil])
(defn unindex-eav-many
  "Removes [e a v] from eav index. `a` must be a `:db.cardinaltiy/many` attribute.
   If an empty set of values remains after unindexing, removes the attribute."
  [eav e a v]
  (let [v-set (disj (get-in eav [e a]) v)
        eav-e (if (empty? v-set)
                (dissoc (eav e) a)
                (assoc (eav e) a v-set))]
       (assoc! eav e eav-e)))

;; =========
;; Alternative random-tempid

;; Probability of NO collisions  with 1mm int assigned 1000 times
;; (Math/pow (/ 999999 1000000) (reduce + (range 1000)))
;; Probability of no collisions with 4 1000000 nums:
#_(Math/pow (/ (- (* 1000000 1000000 1000000 1000000) 1) (* 1000000 1000000 1000000 1000000))
            (reduce + (range 1000)))
;; could just use negative integer counter? yes if disallow negative integer tempids
;; datomic cloud reference says only string is accepted
;; datomic-dev-local accepts negative integers, but doesn't report them in :tempids key after transaction
(defn random-tempid
      []
      (str "db.temp-" (clojure.string/join "-" (take 4 (repeatedly #(str (rand-int 1000000)))))))

;; =========
;; EAV Map Form Ops

;; The following functions support adding/retracting to indexes with map form tx-forms
;; The indexes are expected to be passed in as transients and the functions return transients

(defn merge-by-key
      "Like `merge-with`, but `f` takes the key `k` as first arg
       (presumably to allow `f` to merge differently based on `k`)."
      [f & maps]
      (when (some identity maps)
            (let [merge-entry (fn [m e]
                                  (let [k (key e) v (val e)]
                                       (if (contains? m k)
                                         (assoc m k (f k (get m k) v))
                                         (assoc m k v))))
                  merge2 (fn [m1 m2]
                             (reduce merge-entry (or m1 {}) (seq m2)))]
                 (reduce merge2 maps))))

(defn merge-entity-vals
      "Merges entity values based on cardinality of `attr`.
      For `cardinality/many` `attr` treats `v1` and `v2` as sets."
      [schema attr v1 v2]
      ;; works for :db/id `attr` since it's not :db.cardinality/many
      (if (cardinality-many? schema attr)
        (union v1 v2)
        v2))

(defn add-map-eav
      "Adds `tx-form` to eav index. Treats values as sets for `:db.cardinality/many` attributes."
      [schema eav {:keys [db/id] :as tx-form} ex-entity]
      ;; :db/id in tx-form is fine; :db/op is dissoced
      (if ex-entity
        (assoc! eav id (merge-by-key (partial merge-entity-vals schema) ex-entity tx-form))
        (assoc! eav id tx-form)))

(defn entity-diff
      "Returns the \"difference\" between `ex-entity` and `tx-form`."
      [schema ex-entity tx-form]
      (reduce-kv
        (fn [ex-entity a v]
            (if (nil? v)
              (dissoc ex-entity a)
              (if (cardinality-many? schema a)
                (let [new-v (difference (ex-entity a) v)]
                     (if (empty? new-v)
                       (dissoc ex-entity a)
                       (assoc ex-entity a new-v)))
                (if (= (ex-entity a) v)
                  (dissoc ex-entity a)
                  ex-entity))))
        ex-entity (dissoc tx-form :db/id :db/op)))

;; existence of ex-entity => checked up the stack
;; existence of attribute value in ex-entity -> entity-diff
;; nil value of attribute in tx-form -> entity-diff
(defn retract-map-eav
      "Retracts `tx-form` from eav index given existing entity with :db/id `ex-entity`.
       Treats values as sets for `:db.cardinality/many` attributes.
       When nil value specified for an attribute in `tx-form`, entire attribute is removed regardless of cardinality.
       Potentially leaves \"empty entry\" in eav index: {id {:db/id id}}"
      [schema eav {:keys [db/id] :as tx-form} ex-entity]
      (let [new-entity (entity-diff schema ex-entity tx-form)]
           (assoc! eav id new-entity)))

;; =========
;; Transaction Functions

;; note: handle-tx-fns shouldn't return any more :db/fn-call ops
(defn handle-tx-fns
      [db tx-data]
      ;; `f` is function that takes db as first arg and any number of additional arguments...
      ;; `f` should return a seq of tx-forms.
      (reduce (fn [new-tx-data [op f & args :as tx-form]]
                  (if (= op :db.fn/call)
                    (let [fn-tx-data (remove nil? (apply f db args))]
                         (reduce conj new-tx-data fn-tx-data))
                    (conj new-tx-data tx-form)))
              [] tx-data))

;; =========
;; Checking that entity id exists in db

(defn check-id-existence-list
    "Checks list form `tx-forms` to ensure :db/id exists in the database. Returns `tx-forms` unchanged.
     Throws when non-existent :db/id found."
    [tx-forms eav]
    (doseq [[_ id _ _] tx-forms]
      (when (int? id) ;; don't check keyword ids
        (assert (contains? eav id) (str ":db.error/invalid-entity-id Invalid entity id: " id))))
    tx-forms)

(defn check-id-existence-map
    "Checks map form `tx-forms` to ensure :db/id exists in the database. Returns `tx-forms` unchanged.
     Throws when non-existent :db/id found."
    [tx-forms eav]
    (doseq [{:keys [db/id]} tx-forms]
      (when (int? id) ;; don't check keyword ids
        (assert (contains? eav id) (str ":db.error/invalid-entity-id Invalid entity id: " id))))
    tx-forms)

;; =========
;; Leverage `retraction-set` for constraint checks

;; this version does not rely on indexes reflecting all retractions in tx, leverages retraction-set instead
(when (component? schema a)
  (doseq [attr (:db/isComponent schema)]
    (when-let [held-by-id (get-in ave' [attr v])]
      ;; DISALLOW entity to hold same component under different attrs
      (assert (or (and (= e held-by-id) (= a attr))
                  (contains? retraction-set [held-by-id attr v]) (contains? entity-retractions held-by-id))
              (str ":db.error/component-conflict Component conflict: "
                   "Entity with id: " v " already component of: " held-by-id " under attribute " attr
                   ", asserted for: " e " under attribute " a)))))
;; TODO: this version does not rely on indexes reflecting all retractions in tx, leverages retraction-set instead
(when (unique? schema a)
  (when-let [held-by-id (get-in ave' [a v])]
    (assert (or (= e held-by-id)
                (contains? retraction-set [held-by-id a v]) (contains? entity-retractions held-by-id))
            (str ":db.error/unique-conflict Unique conflict: " a ", value: " v " already held by: " held-by-id
                 " asserted for: " e))))

;; REMOVED FROM: `check-db-constraints-many`, since dandling refs are ok and this is not the only way to end up with dangling refs
(when (ref-type? schema a)
  (assert (not (contains? entity-retractions v))
          (str ":db.error/retracted-entity-conflict Can't point to a retracted entity.
                  Attempting to assert " [e a v])))

;; REMOVED FROM: `check-db-constraints-one`, since dandling refs are ok and this is not the only way to end up with dangling refs
(when (ref-type? schema a)
  (assert (not (contains? entity-retractions v))
          (str ":db.error/retracted-entity-conflict Can't point to a retracted entity.
                  Attempting to assert " [e a v])))

;; =========
;; Checking for dangling refs after transaction completed

(defn check-for-dangling-refs1
  [schema eav e a v]
  (when (ref-type? schema a)
    (assert (contains? eav v)
            (str ":db.error/dangling-ref A reference attribute points to a non-existent entity: " [e a v]))))

(defn check-for-dangling-refs-list
  [schema eav list-assertion-forms]
  (doseq [[_ e a v] list-assertion-forms]
    (check-for-dangling-refs1 schema eav e a v)))

(defn check-for-dangling-refs-map
  [schema eav map-assertion-forms]
  (doseq [{:keys [db/id] :as map-form} map-assertion-forms
          [a v] (dissoc map-form :db/id)]
    (if (cardinality-many? schema a)
      (doseq [single-v v]
        (check-for-dangling-refs1 schema eav id a single-v))
      (check-for-dangling-refs1 schema eav id a v))))

;; this misses the cases where `retract` has left an entity with no attributes, and it was therefore removed from EAV index
(defn check-for-dangling-refs
  "Throws if any reference attributes points to non-existent entities. Must wait until tx completes to do this check."
  [schema eav tx-data]
  (check-for-dangling-refs-list schema eav (concat (get-in tx-data [:list-add :entity-id])
                                                   (get-in tx-data [:list-add :tempid])))
  (check-for-dangling-refs-map schema eav (concat (get-in tx-data [:map-add :entity-id])
                                                  (get-in tx-data [:map-add :tempid])
                                                  (get-in tx-data [:map-add :no-id]))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TODO: include these?
;; TODO: these should take tx-data from transact:
;; - check only entities

(defn find-dangling-refs
  "Returns a seq of all [e a v] tuples in db where attribute `a` is a reference attribute pointing to an
   entity that does not exist in db."
  [{:keys [schema db/eav db/ave]}]
  (flatten
    (for [ref-attr (-> schema :db/isRef)
          :let [[target-id pointing-id] (get ave ref-attr)]
          :when (and pointing-id (not (contains? eav target-id)))]
      [pointing-id ref-attr target-id])))

(defn check-for-dangling-refs [db]
  (let [dangling-refs (find-dangling-refs db)]
    (assert (empty? dangling-refs)
            (str ":db.error/dangling-refs Database contains dangling refs: " dangling-refs))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Support for string version of wildcard (["*"]) in pull to return string keys in pull results
;; what about non-wildcard patterns? Also want string attributes?

(defn ->string-attrs
  [entity]
  (into {} (map (fn [[k v]] (if (string? k) [(keyword k) v] [k v])) entity)))

;; NOTE: would need to ensure (non-entity) map values are not converted
(defn stringify-keys-namespaced
  "Recursively transforms all map keys from keywords to strings."
  [schema pull-tree]
  (let [f (fn [[k v]] (if (keyword? k) [(str k) v] [k v]))]
    ;; only apply to maps
    (clojure.walk/postwalk (fn [[k v :as x]] (if (map? x) (->string-attrs x) x)) pull-tree)))

;; =========
;; Integrate `find-reverse-refs` into pull?

;; In datomic if you remove all attributes from an entity, pull wildcard (or [:db/id] pattern)
;; returns {:db/id 101155069755575, :shoe-owned/_shoe ...}
;; EntityDB has the function `find-reverse-refs`, which could be integrated in pull
;; option 1: with '_* pull result can include :db/reverse-refs key which returns the result of this fn - must also apply to components
;; option 2: special :db/reverse-refs attribute that will call this fn