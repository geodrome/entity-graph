(ns entity-graph.core
  (:require
    [clojure.data.avl :as avl]
    [clojure.set :refer [intersection difference union]]
    [clojure.pprint :refer [pprint]]))

;; =========
;; Create DB

(defn- create-ave-index
  "Creates AVE index with correct map types for indexed attributes."
  [schema]
  (reduce-kv
    (fn [ave attr attr-props]
      (let [{:keys [db/index db/unique db/valueType]} attr-props
            {:keys [db/map-type db/comparator]} index]
        (case map-type
          :db.map-type/hash-map
          (assoc ave attr (hash-map))
          :db.map-type/sorted-map
          (if comparator
            (assoc ave attr (sorted-map-by comparator))
            (assoc ave attr (sorted-map)))
          :db.map-type/avl-map
          (if comparator
            (assoc ave attr (avl/sorted-map-by comparator))
            (assoc ave attr (avl/sorted-map)))
          ;; default case
          (if (or (= :db.type/ref valueType) unique)
            (assoc ave attr (hash-map))
            ;; else not in ave index
            ave))))
    {} schema))

(defn- encode-schema
  "Transforms `schema` into sets (or maps) of attributes with various properties."
  [schema]
  (let [schema
        (reduce-kv
          (fn [schema attr-name attr-props]
            (cond-> schema
                    ;; Cardinality
                    (= :db.cardinality/many (:db/cardinality attr-props))
                    (update :db.cardinality/many conj attr-name)
                    ;; Ref attribute
                    (= :db.type/ref (:db/valueType attr-props))
                    (update :db/isRef conj attr-name)
                    ;; Component
                    (:db/isComponent attr-props)
                    (update :db/isComponent conj attr-name)
                    ;; Unique
                    (= :db.unique/identity (:db/unique attr-props))
                    (update :db.unique/identity conj attr-name)
                    (= :db.unique/value (:db/unique attr-props))
                    (update :db.unique/value conj attr-name)
                    ;; Index
                    ;; `attr-props` for indexed attributes keys: :db/map-type, :db/comparator (optional)
                    (= :db.map-type/hash-map (get-in attr-props [:db/index :db/map-type]))
                    (update :db.index/hash-map conj attr-name)
                    (= :db.map-type/sorted-map (get-in attr-props [:db/index :db/map-type]))
                    (update :db.index/sorted-map conj attr-name)
                    (= :db.map-type/avl-map (get-in attr-props [:db/index :db/map-type]))
                    (update :db.index/avl-map conj attr-name)
                    ;; Sort
                    ;; `attr-props` for sorted sets keys: :db/set-type, :db/comparator (optional)
                    (or (= :db.set-type/sorted-set (get-in attr-props [:db/sort :db/set-type]))
                        (= :db.set-type/avl-set (get-in attr-props [:db/sort :db/set-type])))
                    (update :db/sorted-attributes assoc attr-name (get attr-props :db/sort))))
          {:db.cardinality/many        #{}
           :db/isRef                   #{}
           :db/isComponent             #{}
           :db.unique/identity         #{}
           :db.unique/value            #{}
           :db.index/hash-map          #{}
           :db.index/sorted-map        #{}
           :db.index/avl-map           #{}
           :db/sorted-attributes        {}} schema)]
    (as-> schema $
          (assoc $ :db/index (union (:db.index/hash-map schema) (:db.index/sorted-map schema) (:db.index/avl-map schema)))
          (assoc $ :db/unique (union (:db.unique/identity schema) (:db.unique/value schema)))
          (assoc $ :db.ave-form/single-e (union (:db/unique $) (:db/isComponent $)))
          (assoc $ :db.ave-form/eset (difference (union (:db/isRef $) (:db/index $)) (:db.ave-form/single-e $))))))

(defn- validate-schema
  "Returns valid `schema` or throws if `schema` violates any rules."
  [schema]
  (doseq [[attr-name {:keys [db/cardinality db/index db/unique db/valueType
                             db/isComponent db/sort db/doc] :as attr-props}] schema]
    ;; Reserve db namespace
    (let [valid #{:db/cardinality :db/index :db/unique :db/valueType :db/isComponent :db/sort :db/doc}]
      (doseq [prop (keys attr-props)]
        (assert (or (not= (namespace prop) "db") (contains? valid prop))
                (str "Invalid attribute property: " prop " for " attr-name "."
                     " Namespace 'db' is reserved. Valid properties: " valid))))
    (when-let [attr-ns (namespace attr-name)]
      (assert (not= "db" (clojure.string/lower-case attr-ns))
              (str "Invalid attribute name: " attr-name ". 'db' namespace is reserved.")))
    ;; Validate attribute
    (when (not (nil? unique))
      (assert (not= cardinality :db.cardinality/many)
              (str "Unique attr " attr-name " cannot be cardinality many."))
      (let [valid #{:db.unique/identity :db.unique/value false}]
        (assert (contains? valid unique)
                (str "Invalid value " unique " under :db/unique key for " attr-name ". Valid values: " valid))))
    (when (not (nil? index))
      (let [{:keys [db/map-type db/comparator] :as index-spec} index
            valid-map-type #{:db.map-type/hash-map :db.map-type/sorted-map :db.map-type/avl-map}]
        (when-not (false? index-spec)
          (assert (map? index-spec)
                  (str "Invalid specification under :db/index key for " attr-name ". Must be a map containing :db/map-type key or false."))
          (assert (contains? valid-map-type map-type)
                  (str "Invalid map type under :db/index key for " attr-name ". Valid map types:" valid-map-type))
          (assert (or (nil? comparator) (fn? comparator))
                  (str "Invalid comparator under :db/index key for " attr-name ". If specified, must be a function.")))))
    (when cardinality
      (let [valid #{:db.cardinality/one :db.cardinality/many}]
        (assert (contains? valid cardinality)
                (str "Invalid value " cardinality " under :db/cardinality key for " attr-name ". Valid values: " valid))))
    (when valueType
      (let [valid #{:db.type/ref}]
        (assert (contains? valid valueType)
                (str "Invalid value " valueType " under :db/valueType key for " attr-name ". Valid values: " valid))))
    (when (not (nil? sort))
      (when-not (false? sort)
        (assert (map? sort)
                (str "Invalid specification under :db/sort key for " attr-name ". Must be a map containing :db/set-type key or false."))
        (assert (= :db.cardinality/many cardinality)
                (str "Sorted attribute " attr-name " must be cardinality many."))
        (assert (not (= :db.type/ref valueType))
                (str "Sorted attribute " attr-name " cannot be a reference attribute."))
        (let [valid #{:db.set-type/sorted-set :db.set-type/avl-set}]
          (assert (contains? valid (:db/set-type sort))
                  (str "Invalid set type under :db/sort key for " attr-name ". Valid set types: " valid)))
        (when (:db/comparator sort)
          (assert (fn? (:db/comparator sort))
                  (str "Invalid comparator under :db/sort key for " attr-name ". If specified, must be a function.")))))
    (when isComponent
      (assert (= :db.type/ref valueType)
              (str attr-name " is not a reference attribute and therefore cannot have :db/isComponent set to true."))
      (let [valid #{true false}]
        (assert (contains? valid isComponent)
                (str "Invalid value " isComponent " under :db/isComponent key for " attr-name ". Valid values: " valid))))
    (when (and (= :db.type/ref valueType) index)
      (println "WARNING: Are you sure you want the reference attribute " attr-name " to be indexed in " index "?"))
    (when doc
      (assert (string? doc)
              (str "Invalid value " doc " under :db/doc key for " attr-name "."
                   "The optional :db/doc specifies a documentation string, and must be a string value."))))
  ;; return valid schema:
  schema)

(defn create-db
  "Returns an empty database with `schema`."
  [schema]
  {:db/schema   (-> schema validate-schema encode-schema)
   :db/next-id  1
   :db/tx-count 0
   :db/eav      {}
   :db/ave      (create-ave-index schema)})

;; =========
;; Predicates

(defn ref-type? [schema attr]
  ((schema :db/isRef) attr))

(defn component? [schema attr]
  ((schema :db/isComponent) attr))

(defn cardinality-many? [schema attr]
  ((schema :db.cardinality/many) attr))

(defn unique-identity? [schema attr]
  ((schema :db.unique/identity) attr))

(defn unique? [schema attr]
  ((schema :db/unique) attr))

(defn ave-form-single-e? [schema attr]
  ((schema :db.ave-form/single-e) attr))

(defn ave-form-eset? [schema attr]
  ((schema :db.ave-form/eset) attr))

(defn check-attr
  "Returns a property `prop` value for attribute `attr`."
  [{:keys [db/schema]} attr prop]
  (case prop
    :db/isRef (or (contains? (schema :db/isRef) attr) false)
    :db/isComponent (or (contains? (schema :db/isComponent) attr) false)
    :db/cardinality (if (contains? (schema :db.cardinality/many) attr)
                      :db.cardinality/many
                      :db.cardinality/one)
    :db/unique (cond
                 (contains? (schema :db.unique/identity) attr) :db.unique/identity
                 (contains? (schema :db.unique/value) attr) :db.unique/value
                 :else :db.unique/false)
    :db/sort (if-let [{:keys [db/set-type]} (get (schema :db/sorted-attributes) attr)]
               (case set-type
                 :db.set-type/sorted-set :db.sort/sorted-set
                 :db.set-type/avl-set    :db.sort/avl-set)
               :db.sort/false)
    :db/index (cond
                (contains? (schema :db.index/hash-map) attr) :db.index/hash-map
                (contains? (schema :db.index/sorted-map) attr) :db.index/sorted-map
                (contains? (schema :db.index/avl-map) attr) :db.index/avl-map
                ;; these are indexed even if not explicitly specified:
                (contains? (schema :db/isRef) attr) :db.index/hash-map
                (contains? (schema :db/unique) attr) :db.index/hash-map
                :else :db.index/false)
    :db/ave-form (cond
                   (contains? (schema :db.ave-form/single-e) attr) :db.ave-form/single-e
                   (contains? (schema :db.ave-form/eset) attr) :db.ave-form/eset
                   :else :db.ave-form/false)))

(defn entity-id? [id]
  (or (pos-int? id) (keyword? id)))

(defn tempid? [id]
  (string? id))

(defn lookup-ref? [id]
  (vector? id))

;; =========
;; Transaction Data Preparation

;; Expand Nested Maps

(defn- random-tempid []
  (str "db.temp-" (random-uuid)))

(defn- maybe-assign-tempid
  "Returns `map-form` as is or with random tempid assigned when :db/id key not present."
  [map-form]
  (if (contains? map-form :db/id)
    map-form
    (assoc map-form :db/id (random-tempid))))

(defn- contains-unique?
  "Returns true if some attribute in `map-form` is :db/unique, returns nil otherwise."
  [schema map-form]
  (some (:db/unique schema) (keys map-form)))

(defn- assert-component-or-unique!
  "Throws if no attribute in `map-form` is either :db/isComponent or :db/unique. Else returns nil."
  [schema parent-attr map-form]
  (assert (or (component? schema parent-attr) (contains-unique? schema map-form))
          (str ":db.error/invalid-nested-entity " map-form ". Must either be a component
                or must contain a :db/unique attribute.")))

(defn- cardinality-many-values-to-sets
  "Returns `map-form` with all :db.cardinality/many values converted to sets if needed. Wraps non-set/non-sequential
   single values in a set, converts sequential values to a set, removes empty set/sequential values."
  [schema map-form]
  (reduce (fn [map-form attr-many]
            (let [v (get map-form attr-many)]
              (cond
                ;; if empty values not removed, can end up with empty values in EAV when adding new entities
                (set? v) (if (empty? v)
                           (dissoc map-form attr-many)
                           map-form)
                (sequential? v) (if (empty? v)
                                  (dissoc map-form attr-many)
                                  (update map-form attr-many set))
                :not-set-not-sequential (update map-form attr-many hash-set))))
          map-form (intersection (set (keys map-form)) (schema :db.cardinality/many))))

(defn- expand-map-form1
  "Expands `map-form` by one level. Returns [expanded-form unnested-entities] where:
   -`expanded-form` is `map-form` with nested maps for reference attributes replaced with an id (either supplied in
    nested map or a generated tempid linking to nested map).
   -`unnested-entities` is a seq of entities that were unnested from `map-form` linked to `expanded-form` by :db/id
    (to be processed in turn)."
  [schema map-form]
  (reduce-kv
    (fn [[expanded-form unnested-entities] a v]
      (cond
        (ref-type? schema a)
        (cond
          (cardinality-many? schema a)
          (let [[ref-vals unnested-entities]
                (reduce
                  (fn [[ref-vals unnested-entities] ref-v]
                    (if (map? ref-v)
                      (do (assert-component-or-unique! schema a ref-v)
                          ;; If `cardinality-many-values-to-sets` not called on `ref-v`,
                          ;; may encounter non-set values when map-form `ref-v` is processed in turn
                          (let [unnested-entity (-> (cardinality-many-values-to-sets schema ref-v)
                                                    (maybe-assign-tempid))]
                            [(conj ref-vals (:db/id unnested-entity)) (conj unnested-entities unnested-entity)]))
                      [(conj ref-vals ref-v) unnested-entities]))
                  [[] unnested-entities] v)]
            [(assoc expanded-form a ref-vals) unnested-entities])
          :cardinality/one
          (if (map? v)
            (do (assert-component-or-unique! schema a v)
                (let [unnested-entity (maybe-assign-tempid v)]
                  [(assoc expanded-form a (:db/id unnested-entity)) (conj unnested-entities unnested-entity)]))
            [expanded-form unnested-entities]))
        :not-ref-attribute
        [expanded-form unnested-entities]))
    [map-form []] map-form))

(defn- expand-map-form
  "Expands nested entities in `map-form` into equivalent flat expansion. Recursively expands any nested entities
   in the flat expansion. Returns equivalent flat expansion."
  [schema map-form]
  (loop [expanded-forms []
         queue [map-form]]
    (let [[expanded-form unnested-entities] (expand-map-form1 schema (first queue))
          expanded-forms (conj expanded-forms expanded-form)
          queue (reduce conj (rest queue) unnested-entities)]
      (if (empty? queue)
        expanded-forms
        (recur expanded-forms queue)))))

(defn- expand-nested-entities
  "Returns `map-forms` with nested map forms expanded. Expands nested maps for reference attributes in `map-forms` into
   separate entities and conjs them to `map-forms`. If nested entity does not contain a :db/id, assigns it a tempid.
   Along the way ensures all :db.cardinality/many values are sets."
  [schema map-forms]
  (mapcat (fn [map-form]
  ;; If `cardinality-many-values-to-sets` not called, `expand-map-form1` may encounter cardinality/many single value
            (->> (cardinality-many-values-to-sets schema map-form)
                 (expand-map-form schema)))
          map-forms))

(defn- remove-map-forms-with-no-attributes
  "Returns `map-form-tx-data` with map-forms with no attributes removed."
  [map-form-tx-data]
  (-> map-form-tx-data
      (update :tempid (fn [map-forms] (remove (fn [map-form] (= 1 (count map-form))) map-forms)))
      (update :entity-id (fn [map-forms] (remove (fn [map-form] (= 1 (count map-form))) map-forms)))
      (update :no-id (fn [map-forms] (remove (fn [map-form] (zero? (count map-form))) map-forms)))))

;; only converts attribute keys, not keys in map values
(defn- string-attrs->keyword-attrs
  [map-forms]
  (reduce (fn [map-forms map-form]
            (conj map-forms
                  (into {} (map (fn [[k v]] (if (string? k) [(keyword k) v] [k v])) map-form))))
          [] map-forms))

(defn- convert-string-attrs-to-keyword-attrs
  [map-form-tx-data]
  (-> map-form-tx-data
      (update :tempid string-attrs->keyword-attrs)
      (update :entity-id string-attrs->keyword-attrs)
      (update :no-id string-attrs->keyword-attrs)))

;; =========
;; Group

;; Part of pre-processing tx-data is to group transaction data by operation and further by entity identifier type

(defn- group-by-operation
  "Returns `tx-data` grouped by operation like {:list-add [tx-form1, tx-form2, ...], ...}."
  [tx-data]
  (group-by (fn [tx-form]
              (if (map? tx-form)
                :map-add
                (case (first tx-form)
                  :db/add :list-add
                  :db/retract :retract
                  :db/retractEntity :retractEntity
                  :invalid-op)))
            tx-data))

(defn- group-by-id-type
  "Expects `tx-data` grouped by operation. Further groups each group in `tx-data` by id type.
   Returns grouped `tx-data` like {:list-add {:tempid [tx-form1, tx-form2, ...], ...}, ...}."
  [tx-data]
  (-> tx-data
      (update :map-add (partial group-by
                                (fn [{:keys [db/id]}]
                                  (cond
                                    (nil? id) :no-id
                                    (tempid? id) :tempid
                                    (entity-id? id) :entity-id
                                    (lookup-ref? id) :lookup-ref
                                    :default :invalid-id))))
      (update :list-add (partial group-by
                                 (fn [[_ id]]
                                   (cond
                                     (tempid? id) :tempid
                                     (entity-id? id) :entity-id
                                     (lookup-ref? id) :lookup-ref
                                     :default :invalid-id))))
      (update :retract (partial group-by
                                (fn [[_ id]]
                                  (cond
                                    (tempid? id) :tempid
                                    (entity-id? id) :entity-id
                                    (lookup-ref? id) :lookup-ref
                                    :default :invalid-id))))
      (update :retractEntity (partial group-by
                                (fn [[_ id]]
                                  (cond
                                    (entity-id? id) :entity-id
                                    (lookup-ref? id) :lookup-ref
                                    :default :invalid-id))))))

(defn- resolve-lookup-ref-or-throw
  "Returns entity id for lookup ref `[a v]` or nil if it's not in the db. Throws if invalid lookup ref supplied."
  [{:keys [db/ave db/schema]} [a v]]
  (assert (unique? schema a)
          (str "Invalid lookup ref " [a v] " attribute is not :db/unique"))
  (get-in ave [a v]))

(defn- replace-lookup-refs
  "Expects `tx-data` grouped by [operation id-type]. Returns `tx-data` with lookup refs in :lookup-ref groups replaced
   with corresponding entity ids and moved to [op :entity-id] group."
  [db tx-data]
  (as-> tx-data $
        ;; resolve lookup refs in [:map-add :lookup-ref] group and move to [:map-add :entity-id] group
        (reduce (fn [tx-data {:keys [db/id] :as tx-form}]
                  (if-let [resolved-id (resolve-lookup-ref-or-throw db id)]
                    (update-in tx-data [:map-add :entity-id] conj (assoc tx-form :db/id resolved-id))
                    (assert false (str ":db.error/ref-resolution-error Could not resolve lookup ref in tx-form: " tx-form))))
                $ (-> tx-data :map-add :lookup-ref))
        ;; remove [:map-add :lookup-ref] group after all the refs have been replaced
        (update $ :map-add dissoc :lookup-ref)
        ;; resolve lookup refs in [:list-add :lookup-ref] group and move to [:list-add :entity-id] group
        (reduce (fn [tx-data [op id a v :as tx-form]]
                  (if-let [resolved-id (resolve-lookup-ref-or-throw db id)]
                    (update-in tx-data [:list-add :entity-id] conj [op resolved-id a v])
                    (assert false (str ":db.error/ref-resolution-error Could not resolve lookup ref in tx-form: " tx-form))))
                $ (-> tx-data :list-add :lookup-ref))
        ;; remove [:list-add :lookup-ref] group after all the refs have been replaced
        (update $ :list-add dissoc :lookup-ref)
        ;; resolve lookup refs in [:retract :lookup-ref] group and move to [:retract :entity-id] group
        (reduce (fn [tx-data [op id a v :as tx-form]]
                  (if-let [resolved-id (resolve-lookup-ref-or-throw db id)]
                    (update-in tx-data [:retract :entity-id] conj [op resolved-id a v])
                    (assert false (str ":db.error/ref-resolution-error Could not resolve lookup ref in tx-form: " tx-form))))
                $ (-> tx-data :retract :lookup-ref))
        ;; remove [:retract :lookup-ref] group after all the refs have been replaced
        (update $ :retract dissoc :lookup-ref)
        ;; resolve lookup refs in [:retractEntity :lookup-ref] group and move to [:retractEntity :entity-id] group
        (reduce (fn [tx-data [op id :as tx-form]]
                  (if-let [resolved-id (resolve-lookup-ref-or-throw db id)]
                    (update-in tx-data [:retractEntity :entity-id] conj [op resolved-id])
                    (assert false (str ":db.error/ref-resolution-error Could not resolve lookup ref in tx-form: " tx-form))))
                $ (-> tx-data :retractEntity :lookup-ref))
        ;; remove [:retract :lookup-ref] group after all the refs have been replaced
        (update $ :retractEntity dissoc :lookup-ref)))

;; =========
;; Resolve Tempids

(defn- resolve-tempid1-db
  [{:keys [db/ave db/schema]} tempids tempid a v]
  (if (unique-identity? schema a)
    (if-let [id (get-in ave [a v])]
      (assoc tempids tempid id)
      tempids)
    tempids))

(defn- resolve-tempids-db
  "Resolves tempids in `tx-data` from pre-tx `db` via :db.unique/identity attributes.
   Returns map with [tempid entity-id] entry for each tempid that was successfully resolved."
  [db tx-data]
  (as-> {} tempids
        ;; When more than one :db.unique/identity attribute has a matching value in AVE, resolves to LAST value found
        ;; Since we are processing assertions after retractions, "conflicting" tempids in assertions will win
        (reduce (fn [tempids [_ e a v]]
                  (resolve-tempid1-db db tempids e a v))
                tempids (get-in tx-data [:retract :tempid]))
        (reduce (fn [tempids [_ e a v]]
                  (resolve-tempid1-db db tempids e a v))
                tempids (get-in tx-data [:list-add :tempid]))
        (reduce (fn [tempids {:keys [db/id] :as tx-form}]
                  (reduce-kv
                    (fn [tempids a v]
                      (resolve-tempid1-db db tempids id a v))
                    tempids (dissoc tx-form :db/id)))
                tempids (get-in tx-data [:map-add :tempid]))))

(defn- resolve-tempid1-tx
  [schema tempids next-id tx-ave tempid a v]
  ;; Any [tempid a v] with db.unique/identity attribute where tempid was resolved by `resolve-tempids-db`
  ;; is added to tx-ave
  ;; Any [tempid a v] with db.unique/identity attribute not resolved by `resolve-tempids-db` is resolved via tx-ave,
  ;; if that fails the tempid is assigned a new entity id and this new entity id is added to tx-ave
  (if (unique-identity? schema a)
    (if-let [id (get tempids tempid)]
      [tempids (assoc-in tx-ave [a v] id) next-id]
      (if-let [id (get-in tx-ave [a v])]
        ;; same :db/unique/identity [a v] will get the same entity-id regardless of tempid
        [(assoc tempids tempid id) tx-ave next-id]
        ;; it's okay to add new entity id to tx-ave for retractions too
        ;; if no assertions w/ same tempid - no prob, just retract non-existing entity,
        ;; for assertion w/ same [tempid a v] -> Assert failed: Can't assert and retract the same value,
        ;; for assertion w/ same tempid, but different [a v] -> gets the newly assigned entity id.
        [(assoc tempids tempid next-id) (assoc-in tx-ave [a v] next-id) (inc next-id)]))
    (if (get tempids tempid)
      [tempids tx-ave next-id]
      [(assoc tempids tempid :needs-assignment) tx-ave next-id])))

(defn- resolve-tempids-tx
  "Resolves tempid from `tx-data` via db.unique/identity attributes.
  For every tempid:
   -If tempid resolved via db, assigns any :db.unique/identity [a v] the entity id that was resolved via db.
   -If tempid did not resolve via db, assigns a new entity id and any subsequent :db.unique/identity [a v] is assigned
    the same entity id.
   Returns [tempids tx-ave next-id] where:
   `tempids` is a map of [tempid entity-id] entries with an id value for every tempid that was resolved,
     and the keyword :needs-assignment for tempids that were not resolved
   `tx-ave` is an AVE index of :db.unique/identity [a v] built on `tx-data`,
   `next-id` is the next available entity id available for assignment."
  [{:keys [db/next-id db/schema]} tx-data tempids]
  (as-> [tempids {} next-id] $
        (reduce (fn [[tempids tx-ave next-id] [op e a v]]
                  (resolve-tempid1-tx schema tempids next-id tx-ave e a v))
                $ (get-in tx-data [:retract :tempid]))
        (reduce (fn [[tempids tx-ave next-id] [op e a v]]
                  (resolve-tempid1-tx schema tempids next-id tx-ave e a v))
                $ (get-in tx-data [:list-add :tempid]))
        (reduce (fn [[tempids tx-ave next-id] {:keys [db/id] :as tx-form}]
                  (reduce-kv
                    (fn [[tempids tx-ave next-id] a v]
                      (resolve-tempid1-tx schema tempids next-id tx-ave id a v))
                    [tempids tx-ave next-id] (dissoc tx-form :db/id)))
                $ (get-in tx-data [:map-add :tempid]))))

(defn- assign-unresolved-tempids
  "Returns [tempids next-id] where:
    `tempids` is a map of [tempid entity-id] entries with any unresolved tempid receiving a new entity id,
    `next-id` is the next entity id available for assignment."
  [tempids next-id]
  (reduce
    (fn [[tempids next-id] [tid tid-val]]
      (if (= :needs-assignment tid-val)
        [(assoc tempids tid next-id) (inc next-id)]
        [tempids next-id]))
    [tempids next-id] tempids))

(defn- resolve-tempids
  "Returns [tempids tx-ave next-id] where:
    `tempids` is a map of [tempid entity-id] entries where entity-id is resolved via :db.unique/identity attribute
     or assigned a new db/id when it cannot be resolved via :db.unique/identity attribute,
    `tx-ave` is an AVE index of :db.unique/identity [a v] built on `tx-data`,
    `next-id` is the next entity id available for assignment."
  [db tx-data]
  (let [tempids (resolve-tempids-db db tx-data)
        [tempids tx-ave next-id] (resolve-tempids-tx db tx-data tempids)
        [tempids next-id] (assign-unresolved-tempids tempids next-id)]
    [tempids tx-ave next-id]))

;; =========
;; Replace tempids in transaction data

(defn- replace-tempids-list
  "Returns `list-forms` with tempids in e position replaced based on `tempids` map."
  [list-forms tempids]
  (reduce
    (fn [replaced [op e a v]]
      (conj replaced [op (get tempids e) a v]))
    [] list-forms))

(defn- replace-tempids-map
  "Returns `map-forms` with tempids under :db/id key replaced based on `tempids` map."
  [map-forms tempids]
  (reduce
    (fn [replaced {:keys [db/id] :as map-form}]
      (conj replaced (assoc map-form :db/id (get tempids id))))
    [] map-forms))

;; =========
;; Assign ids in map-forms with no :db/id

(defn- resolve-unique-identity-map-ave
  "Returns the first [id av] that resolves via :db.unique/identity attribute or nil if none found."
  [ave unique-identity-av]
  (reduce
    (fn [_ av]
      (when-let [id (get-in ave av)]
        (reduced [id av])))
    nil unique-identity-av))

(defn- resolve-unique-identity-map
  "Resolves :db/id of `map-form` via :db.unique/identity attributes and updates `tx-ave`.
   First attempts to resolve from current AVE index `ave`, if not found, attempts to resolve from `tx-ave`.
   Returns [id tx-ave] where:
   `id` is the resolved :db/id or nil if unresolved.
   `tx-ave` is an updated AVE index of :db.unique/identity [a v] built on `tx-data`."
  [{:keys [db/ave db/schema]} tx-ave map-form]
  (let [unique-identity-av (set (select-keys map-form (schema :db.unique/identity)))]
    (if (empty? unique-identity-av)
      [nil tx-ave]
      (if-let [[id av] (resolve-unique-identity-map-ave ave unique-identity-av)]
        [id (reduce #(assoc-in %1 %2 id) tx-ave (disj unique-identity-av av))]
        (if-let [[id av] (resolve-unique-identity-map-ave tx-ave unique-identity-av)]
          [id (reduce #(assoc-in %1 %2 id) tx-ave (disj unique-identity-av av))]
          [nil tx-ave])))))

(defn- replace-nil-ids-map
  "Replaces missing :db/id in `tx-forms` with id resolved via :db.unique/identity and updates `tx-ave`. When id cannot
   be resolved assigns a new entity id. Expects `tx-forms` to contain map forms with no :db/id.
   Returns [assigned tx-ave next-id] where:
   `assigned` is a vector of map form tx-forms with :db/id assigned,
   `tx-ave` is an updated AVE index of :db.unique/identity [a v] built on `tx-forms`,
   `next-id` is the next entity id available for assignment."
  [db tx-forms tx-ave next-id]
  (reduce
    (fn [[assigned tx-ave next-id] m]
      (let [[resolved-id tx-ave] (resolve-unique-identity-map db tx-ave m)]
        (if resolved-id
          [(conj assigned (assoc m :db/id resolved-id)) tx-ave next-id]
          [(conj assigned (assoc m :db/id next-id)) tx-ave (inc next-id)])))
    [[] tx-ave next-id] tx-forms))

;; =========
;; Resolve and replace ref ids

(defn- resolve-ref-id
  "Resolves id values for reference attributes. For tempids, returns corresponding entity id or throws; for
   lookup refs resolves the lookup ref or throws; for entity ids returns the id."
  [db tempids id]
  (cond
    (entity-id? id)
    id
    (tempid? id)
    (or (get tempids id)
        (assert false (str ":db.error/ref-resolution-error Tempid " id " used only as value in transaction")))
    (lookup-ref? id)
    (if-let [resolved-lookup-ref (resolve-lookup-ref-or-throw db id)]
      resolved-lookup-ref
      (assert false
              (str ":db.error/ref-resolution-error Could not resolve lookup ref: " id " used as value in transaction")))
    :invalid-id
    (assert false
            (str ":db.error/ref-resolution-error A reference attribute contains invalid id type: " id))))

(defn- assert-no-nil-values
  "Returns `list-forms` unchanged or throws if any list-form contains explicit nil in the v position."
  [list-forms]
  (doseq [[_ _ _ v :as list-form] list-forms]
    (when (nil? v)
      (assert (= 3 (count list-form))
              (str ":db.error/nil-value Nil value not allowed in retraction form: " list-form))))
  list-forms)

(defn- validate-retract-tx-data
  [retract-tx-data]
  (-> retract-tx-data
      (update :tempid assert-no-nil-values)
      (update :entity-id assert-no-nil-values)
      (update :lookup-ref assert-no-nil-values)))

(defn- replace-ref-ids-list1
  [{:keys [db/schema] :as db} tempids [op e a v :as list-form]]
  (cond
    ;; must check (nil? v) because for :db/retract op v can be omitted
    (nil? v) list-form
    (ref-type? schema a) [op e a (resolve-ref-id db tempids v)]
    :else list-form))

(defn- replace-ref-ids-list
  "Returns `list-forms` with tempid and lookup-ref values for reference attributes replaced with correct entity id."
  [list-forms db tempids]
  (map (partial replace-ref-ids-list1 db tempids) list-forms))

(defn- replace-ref-ids-map1
  [{:keys [db/schema] :as db} tempids tx-form]
  (reduce-kv
    (fn [entity a v]
      (if (ref-type? schema a)
        (if (cardinality-many? schema a)
          (assoc entity a (set (map #(resolve-ref-id db tempids %) v)))
          (assoc entity a (resolve-ref-id db tempids v)))
        (assoc entity a v)))
    {} tx-form))

(defn- replace-ref-ids-map
  "Returns `map-forms` with tempid and lookup-ref values for reference attributes replaced with correct entity id."
  [map-forms db tempids]
  (map (partial replace-ref-ids-map1 db tempids) map-forms))

;; =========
;; Prepare

(defn- get-component-ids
  "Returns a set of all component entity ids for entity with `id`. Follows each component entity and
   recursively includes the ids of all of its component entities in the returned set."
  [eav schema id]
  (reduce-kv (fn [component-ids a v]
               (if (component? schema a)
                 (if (cardinality-many? schema a)
                   (reduce (fn [component-ids id]
                             (if (contains? component-ids id)
                               component-ids
                               (union (conj component-ids id) (get-component-ids eav schema id))))
                           component-ids v)
                   (if (contains? component-ids v)
                     component-ids
                     (union (conj component-ids v) (get-component-ids eav schema v))))
                 component-ids))
             #{} (eav id)))

(defn- get-entity-retraction-ids
  "Returns a set of entity ids from `retract-entity-forms` to be retracted. Recursively follows and adds all
   component entity ids in `retract-entity-forms`."
  [{:keys [db/eav db/schema]} retract-entity-forms]
  (reduce (fn [entity-retraction-ids [_ id]]
            (if (contains? entity-retraction-ids id)
              entity-retraction-ids
              (union (conj entity-retraction-ids id)
                     (get-component-ids eav schema id))))
          #{} retract-entity-forms))

(defn- mk-retraction-set
  "Returns a set of [e a v] tuples for every :db/retract op tx-form in `tx-data`. When v in [op e a v] is nil/omitted,
   replaces it with current value from db (for cardinality/many attributes this requires producing multiple
   [e a v] tuples)."
  [{:keys [db/schema db/eav]} tx-data]
  (reduce (fn [retraction-set [_ e a v]]
            ;; nil v means the value was omitted, explicit nil v is not permitted
            (if (nil? v)
              (if (cardinality-many? schema a)
                (reduce (fn [retraction-set v]
                          (conj retraction-set [e a (get-in eav [e a v])]))
                        retraction-set (get-in eav [e a]))
                (conj retraction-set [e a (get-in eav [e a])]))
              (conj retraction-set [e a v])))
          #{} (concat (get-in tx-data [:retract :entity-id])
                      (get-in tx-data [:retract :tempid]))))

(defn- validate-tx-ops
  "Returns `tx-data` unchanged if all ops are valid or throws if any invalid op are found."
  [tx-data]
  (assert (nil? (:invalid-op tx-data))
          (str ":db.error/invalid-op Transaction data contains forms with invalid op: " (:invalid-op tx-data)))
  tx-data)

(defn- validate-ids
  "Returns `tx-data` unchanged if all entity ids are valid or throws if any invalid entity ids are found."
  [tx-data]
  (assert (nil? (-> tx-data :map-add :invalid-id))
          (str ":db.error/invalid-entity-id tx-data contains invalid entity id: " (-> tx-data :map-add :invalid-id)))
  (assert (nil? (-> tx-data :list-add :invalid-id))
          (str ":db.error/invalid-entity-id tx-data contains invalid entity id: " (-> tx-data :list-add :invalid-id)))
  (assert (nil? (-> tx-data :map-add :invalid-id))
          (str ":db.error/invalid-entity-id tx-data contains invalid entity id: " (-> tx-data :retract :invalid-id)))
  tx-data)

(defn- prepare-tx-data
  "Returns [tx-data tempids next-id entity-retractions retraction-set] where:

   `tx-data` is grouped by operation and further by id type within each operation,
    -all map-forms containing nested entities are expanded; all lookup refs are replaced with entity ids;
    -all tempids are resolved via :db.unique/identity attributes or assigned new entity ids;
    -all tempids and lookup-refs in the value position of reference attributes replaced with corresponding entity ids;
    -all map forms with no :db/id are resolved via :db.unique/identity attributes or assigned new entity ids;

   `tempids` is a map of [tempid entity-id] entries
   `next-id` is the next entity id available for assignment
   `entity-retraction-ids` is a set of entity ids to be retracted
   `retraction-set` is a set of [e a v] tuples for every :db/retract op tx-form in `tx-data`"
  [{:keys [db/schema] :as db} tx-data]
  (let [tx-data (as-> tx-data $
                      (group-by-operation $)
                      (update $ :map-add (partial expand-nested-entities schema))
                      (group-by-id-type $)
                      (update $ :map-add remove-map-forms-with-no-attributes)
                      (update $ :map-add convert-string-attrs-to-keyword-attrs)
                      ;; this must happen before replace-lookup-refs to avoid masking nils in v position
                      (update $ :retract validate-retract-tx-data)
                      (replace-lookup-refs db $)
                      (validate-tx-ops $)
                      (validate-ids $))
        [tempids tx-ave next-id] (resolve-tempids db tx-data)
        entity-retraction-ids (get-entity-retraction-ids db (get-in tx-data [:retractEntity :entity-id]))
        tx-data (-> tx-data
                    ;; Process retractions
                    ;; for retractions must assert-no-nil-values first
                    (update-in [:retract :tempid] replace-tempids-list tempids)
                    (update-in [:retract :tempid] replace-ref-ids-list db tempids)
                    (update-in [:retract :entity-id] replace-ref-ids-list db tempids)
                    ;; Process list form assertions
                    (update-in [:list-add :tempid] replace-tempids-list tempids)
                    (update-in [:list-add :tempid] replace-ref-ids-list db tempids)
                    (update-in [:list-add :entity-id] replace-ref-ids-list db tempids)
                    ;; Process map form assertions
                    (update-in [:map-add :tempid] replace-tempids-map tempids)
                    (update-in [:map-add :tempid] replace-ref-ids-map db tempids)
                    (update-in [:map-add :no-id] replace-ref-ids-map db tempids)
                    (update-in [:map-add :entity-id] replace-ref-ids-map db tempids))
        [map-add-no-id _ next-id] (replace-nil-ids-map db (get-in tx-data [:map-add :no-id]) tx-ave next-id)
        tx-data (assoc-in tx-data [:map-add :no-id] map-add-no-id)
        retraction-set (mk-retraction-set db tx-data)]
    [tx-data tempids next-id entity-retraction-ids retraction-set]))

;; =========
;; Updating Indexes by Transactions

;; The following functions operate on the EAV and AVE indexes
;; The functions expect the EAV/AVE indexes to be passed in as a transient maps and return updated transient maps
;; By convention eav' and ave' are the argument names for the transient versions of the EAV/AVE indexes

;; =========
;; AVE Indexing helpers for eset form like {a {v #{e1 e2 ...}}} (the E position is a set of entity ids)
;; NOTE: AVE index is non-covering (only the entity id is in the E position, not the entire entity)

(defn- index-ave-eset
  "Adds [e a v] to AVE index in {a {v #{e1 e2 ...}}} form."
  [ave' e a v]
  (let [ave-a (get ave' a)
        e-set (get ave-a v #{})
        ave-a (assoc ave-a v (conj e-set e))]
    (assoc! ave' a ave-a)))

(defn- index-vset-ave-eset
  "For every v in `vset` adds [e a v] to AVE index in {a {v #{e1 e2 ...}}} form."
  [ave' e a vset]
  (assoc! ave' a (reduce (fn [ave-a v]
                           (assoc ave-a v (conj (or (get ave-a v) #{}) e)))
                         (ave' a) vset)))

(defn- unindex-ave-eset
  "Removes [e a v] from AVE index in {a {v #{e1 e2 ...}}} form.
   If there are no remaining entity ids for `v`, the value and the empty set are removed."
  [ave' e a v]
  (let [ave-a (get ave' a)]
    (if-let [e-set (get ave-a v)]
      (let [e-set (disj e-set e)
            ave-a (if (empty? e-set)
                    (dissoc ave-a v)
                    (assoc ave-a v e-set))]
        (assoc! ave' a ave-a))
      ave')))

(defn- unindex-vset-ave-eset
  "For every v in `vset` removes [e a v] from AVE index in {a {v #{e1 e2 ...}}} form.
   If there are no remaining entity ids for a value in `vset`, the value and the empty set are removed."
  [ave' e a vset]
  (assoc! ave' a (reduce (fn [ave-a v]
                           (if-let [e-set (get ave-a v)]
                             ;; NOTE: could first check (contains? e-set e),
                             ;; but this only makes sense if expecting lots of "misses", which is unlikely
                             (let [e-set (disj e-set e)]
                               (if (empty? e-set)
                                 (dissoc ave-a v)
                                 (assoc ave-a v e-set)))
                             ave-a))
                         (ave' a) vset)))

(defn- replace-ave-eset
  "Replaces `old-v` with `v` in AVE index in {a {v #{e1 e2 ...}}} form.
   If there are no remaining entity ids for `old-v`, the value and the empty set are removed."
  [ave' e a v old-v]
  (let [ave-a (get ave' a)
        e-set-old-v (disj (get ave-a old-v) e)
        ave-a (if (empty? e-set-old-v)
                (dissoc ave-a old-v)
                (assoc ave-a old-v e-set-old-v))
        e-set-v (conj (get ave-a v #{}) v)
        ave-a (assoc ave-a v e-set-v)]
    (assoc! ave' a ave-a)))

;; =========
;; AVE Indexing helpers for single e form like {a {v e}} (the E position is a single entity id):

(defn- index-ave-single-e
  "Adds [e a v] to AVE index in {a {v e}} form."
  [ave' e a v]
  (let [ave-a (assoc (ave' a) v e)]
    (assoc! ave' a ave-a)))

(defn- index-vset-ave-single-e
  "For every v in `vset` adds [e a v] to AVE index in {a {v e}} form."
  [ave' e a vset]
  (assoc! ave' a (reduce #(assoc %1 %2 e) (ave' a) vset)))

(defn- unindex-ave-single-e
  "Removes [a v] from AVE index in {a {v e}} form."
  [ave' a v]
  (let [ave-a (dissoc (ave' a) v)]
    (assoc! ave' a ave-a)))

(defn- unindex-vset-ave-single-e
  "For every v in `vset` removes [a v] from AVE index in {a {v e}} form."
  [ave' a vset]
  (assoc! ave' a (reduce #(dissoc %1 %2) (ave' a) vset)))

(defn- replace-ave-single-e
  "Replaces `old-v` with `v` in AVE index in {a {v e}} form."
  [ave' e a v old-v]
  (as-> (get ave' a) ave-a
        (dissoc ave-a old-v)
        (assoc ave-a v e)
        (assoc! ave' a ave-a)))

;; =========
;; Retract Entity

(defn- rm-refs-to-entity
  "Removes all references pointing to `target-id`. Expects `eav'`, `ave'` as transients.
   Returns updated [eav' ave'] as transients."
  [schema eav' ave' target-id]
  (reduce
    (fn [[eav' ave'] pointing-attr]
      (let [ave-a (get ave' pointing-attr)
            pointing-id-or-idset (get ave-a target-id)]
        (if (nil? pointing-id-or-idset)
          [eav' ave']
          [;; update eav
           (cond
             (cardinality-many? schema pointing-attr)
             (cond
               (ave-form-single-e? schema pointing-attr) ;; pointing-id-or-idset is a single id
               (let [pointing-entity (get eav' pointing-id-or-idset)
                     v-set (disj (get pointing-entity pointing-attr) target-id)
                     pointing-entity (if (empty? v-set)
                                       (dissoc pointing-entity pointing-attr)
                                       (assoc pointing-entity pointing-attr v-set))]
                 (assoc! eav' pointing-id-or-idset pointing-entity))
               :pointing-id-or-idset-is-a-set
               (reduce (fn [eav' pointing-id]
                         (let [pointing-entity (get eav' pointing-id)
                               v-set (disj (get pointing-entity pointing-attr) target-id)
                               pointing-entity (if (empty? v-set)
                                                 (dissoc (eav' pointing-id) pointing-attr)
                                                 (assoc (eav' pointing-id) pointing-attr v-set))]
                           (assoc! eav' pointing-id pointing-entity)))
                       eav' pointing-id-or-idset))
             :cardinality-one
             (cond
               (ave-form-single-e? schema pointing-attr) ;; pointing-id-or-idset is a single id
               (assoc! eav' pointing-id-or-idset (dissoc (eav' pointing-id-or-idset) pointing-attr))
               :pointing-id-or-idset-is-a-set
               (reduce (fn [eav' pointing-id]
                         (assoc! eav' pointing-id (dissoc (eav' pointing-id) pointing-attr)))
                       eav' pointing-id-or-idset)))
           ;; update ave
           (assoc! ave' pointing-attr (dissoc ave-a target-id))])))
    [eav' ave'] (:db/isRef schema)))

(defn- retract-entity
  [schema [eav' ave'] id]
  "Retracts entity with `id` and removes all references from entities pointing to `id`.
   Expects `[eav' ave']` as transients. Returns updated [eav' ave']."
  (let [entity-eav' (get eav' id)
        eav' (dissoc! eav' id)
        ave' (reduce-kv
               (fn [ave' a v]
                 (cond
                   (cardinality-many? schema a)
                   (cond
                     (ave-form-single-e? schema a) (unindex-vset-ave-single-e ave' a v)
                     (ave-form-eset? schema a) (unindex-vset-ave-eset ave' id a v)
                     :not-in-ave-index ave')
                   :cardinality-one
                   (cond
                     (ave-form-single-e? schema a) (unindex-ave-single-e ave' a v)
                     (ave-form-eset? schema a) (unindex-ave-eset ave' id a v)
                     :not-in-ave-index ave')))
               ave' (dissoc entity-eav' :db/id))]
    (rm-refs-to-entity schema eav' ave' id)))

;; =========
;; Retract

(defn- retract1
  "Returns [eav' ave'] that reflects retractions of [e a v]."
  [schema eav' ave' e a v]
  (let [entity-eav' (eav' e)
        value-eav' (get-in eav' [e a])]
    (cond
      (or (nil? entity-eav') (nil? value-eav'))
      [eav' ave']
      :entity-and-attribute-exist
      (cond
        (cardinality-many? schema a)
        (cond
          (contains? value-eav' v)
          [;; update EAV
           (let [new-value-eav' (disj (get entity-eav' a) v)
                 entity-eav' (if (empty? new-value-eav')
                               (dissoc entity-eav' a)
                               (assoc entity-eav' a new-value-eav'))]
             (if (= {:db/id e} entity-eav')
               (dissoc! eav' e)
               (assoc! eav' e entity-eav')))
           ;; update AVE
           (cond
             (ave-form-single-e? schema a) (unindex-ave-single-e ave' a v)
             (ave-form-eset? schema a) (unindex-ave-eset ave' e a v)
             :not-in-ave-index ave')]
          :v-not-in-value-eav' [eav' ave'])
        :cardinality-one
        (cond
          (= v value-eav')
          [;; update EAV
           (let [new-entity-eav' (dissoc entity-eav' a)]
             (if (= {:db/id e} new-entity-eav')
               (dissoc! eav' e)
               (assoc! eav' e (dissoc new-entity-eav' a))))
           ;; update AVE
           (cond
             (ave-form-single-e? schema a) (unindex-ave-single-e ave' a v)
             (ave-form-eset? schema a) (unindex-ave-eset ave' e a v)
             :not-in-ave-index ave')]
          :v-not-equal-value-eav' [eav' ave'])))))

(defn- retract
  "Returns [eav' ave'] that reflects retractions of each [e a v] in `retraction-set`."
  [{:keys [db/schema]} eav' ave' retraction-set]
  (reduce (fn [[eav' ave'] [e a v]]
            (retract1 schema eav' ave' e a v))
          [eav' ave'] retraction-set))

;; =========
;; Assert

;; NOTE: nil values are illegal, but false values are legal, therefore
;; when checking for existence of value use some?/if-some, NOT nil?/if-let

(defn- check-db-constraints-many
  "Returns nil or throws if the [e a v] tuple with cardinality many attribute violates any DB constraints."
  [schema ave' retraction-set entity-retractions e a v]
  (assert (not (nil? v))
          (str ":db.error/nil-value Nil values are illegal. Attempting to assert " [e a v]))
  (assert (not (contains? retraction-set [e a v]))
          (str ":db.error/assertion-retraction-conflict Can't assert and retract the same triple: " [e a v]))
  (assert (not (contains? entity-retractions e))
          (str ":db.error/retracted-entity-conflict Can't assert on a retracted entity.
                Attempting to assert " [e a v]))
  (when (component? schema a)
    (doseq [attr (:db/isComponent schema)]
      (when-let [held-by-id (get-in ave' [attr v])]
        (assert (and (= e held-by-id) (= a attr)) ;; DISALLOW entity to hold same component under different attrs
                (str ":db.error/component-conflict Component conflict: "
                     "Entity with id: " v " already component of: " held-by-id " under attribute " attr
                     " asserted for: " e " under attribute " a))))))

(defn- check-db-constraints-one
  "Returns nil or throws if the [e a v] tuple with cardinality one attribute violates any DB constraints."
  [schema value-eav value-eav' ave' reasserted-eav retraction-set entity-retractions e a v]
  (assert (not (contains? entity-retractions e))
          (str ":db.error/retracted-entity-conflict Can't assert on a retracted entity.
                Attempting to assert " [e a v]))
  (assert (not (nil? v))
          (str ":db.error/nil-value Nil values are illegal. Attempting to assert " [e a v]))
  (assert (not (contains? retraction-set [e a v]))
          (str ":db.error/assertion-retraction-conflict Can't assert and retract the same triple: " [e a v]))
  (assert (or ;; either still unchanged by this tx so far...
            (and (= value-eav value-eav') (not (contains? reasserted-eav [e a])))
            ;; ... or previously changed by this tx, but being changed back to original value in the same tx
            (= v value-eav'))
          (str ":db.error/cardinality-one-conflict Can't assert multiple values for :db.cardinality/one attribute
                 in the same transaction. Attempting to assert " [e a v]))
  (when (component? schema a)
    (doseq [attr (:db/isComponent schema)]
      (when-let [held-by-id (get-in ave' [attr v])]
        (assert (and (= e held-by-id) (= a attr)) ;; DISALLOW entity to hold same component under different attrs
                (str ":db.error/component-conflict Component conflict: "
                     "Entity with id: " v " already component of: " held-by-id " under attribute " attr
                     ", asserted for: " e " under attribute " a)))))
  (when (unique? schema a)
    (when-let [held-by-id (get-in ave' [a v])]
      (assert (= e held-by-id)
              (str ":db.error/unique-conflict Unique conflict: " a ", value: " v " already held by: " held-by-id
                   " asserted for: " e)))))

(defn- maybe-sorted-empty-set
  "Returns correct sorted or unsorted empty set for the attribute `attr`."
  [schema attr]
  (let [{:keys [db/set-type db/comparator]} (get-in schema [:db/sorted-attributes attr])]
    (case set-type
      nil #{}
      :db.set-type/sorted-set (if comparator (sorted-set-by comparator) (sorted-set))
      :db.set-type/avl-set (if comparator (avl/sorted-set-by comparator) (avl/sorted-set)))))

(defn- conj-to-maybe-sorted-empty-set
  "Returns a correct sorted or unsorted set for the attribute `a`, with `v` conjoined to it."
  [schema a v]
  (let [{:keys [db/set-type db/comparator]} (get-in schema [:db/sorted-attributes a])]
    (case set-type
      nil #{v}
      :db.set-type/sorted-set (if comparator (sorted-set-by comparator v) (sorted-set v))
      :db.set-type/avl-set (if comparator (avl/sorted-set-by comparator v) (avl/sorted-set v)))))

(defn- maybe-convert-to-sorted-sets
  "Returns `map-form` with set values of sorted attributes converted to correct type of sorted set."
  [schema map-form]
  (reduce-kv
    (fn [map-form sorted-attr {:keys [db/set-type db/comparator]}]
      (if-let [v-set (get map-form sorted-attr)]
        (assoc map-form sorted-attr
                        (case set-type
                          :db.set-type/sorted-set (if comparator (apply sorted-set-by comparator v-set)
                                                                 (apply sorted-set v-set))
                          :db.set-type/avl-set (if comparator (apply avl/sorted-set-by comparator v-set)
                                                              (apply avl/sorted-set v-set))))
        map-form))
    map-form (get schema :db/sorted-attributes)))

(defn- add1
  "Returns [eav' ave' reasserted-eav] that reflect assertion of [e a v] tuple."
  [{:keys [db/schema db/eav]} eav' ave' reasserted-eav retraction-set entity-retractions e a v]
  (let [entity-eav' (get eav' e {:db/id e})
        value-eav' (get entity-eav' a)]
    (cond
      (cardinality-many? schema a)
      (do
        (check-db-constraints-many schema ave' retraction-set entity-retractions e a v)
        (if (contains? value-eav' v)
          [eav' ave' reasserted-eav]
          [(assoc! eav' e (assoc entity-eav' a (if (nil? value-eav')
                                                 (conj-to-maybe-sorted-empty-set schema a v)
                                                 (conj value-eav' v))))
           (cond
             (ave-form-single-e? schema a) (index-ave-single-e ave' e a v)
             (ave-form-eset? schema a) (index-ave-eset ave' e a v)
             :not-in-ave-index ave')
           reasserted-eav]))
      :cardinality-one
      (do
        (check-db-constraints-one schema (get-in eav [e a]) value-eav' ave'
                                  reasserted-eav retraction-set entity-retractions e a v)
        (if (= v value-eav')
          ;; Update `reasserted-eav` to indicate that [e a v] was in db previously
          [eav' ave' (conj reasserted-eav [e a])]
          [(assoc! eav' e (assoc entity-eav' a v))
           (cond
             (ave-form-single-e? schema a)
             (if (some? value-eav')
               (replace-ave-single-e ave' e a v value-eav')
               (index-ave-single-e ave' e a v))
             (ave-form-eset? schema a)
             (if (some? value-eav')
               (replace-ave-eset ave' e a v value-eav')
               (index-ave-eset ave' e a v))
             :not-in-ave-index ave')
           reasserted-eav])))))

(defn- add-map1
  "Returns [eav' ave' reasserted-eav] that reflect assertion of `map-form`."
  [{:keys [db/schema db/eav]} eav' ave' reasserted-eav retraction-set entity-retractions {:keys [db/id] :as map-form}]
  (let [entity-eav' (get eav' id)]
    (cond
      (nil? entity-eav') ;; OPTIMIZATION: for non-existent-entity can assoc `map-form` in `eav'` directly
      [(assoc! eav' id (maybe-convert-to-sorted-sets schema map-form))
       (reduce-kv (fn [ave' a v]
                    (cond
                      (cardinality-many? schema a)
                      (do
                        (doseq [v-item v]
                          (check-db-constraints-many schema ave' retraction-set entity-retractions id a v-item))
                        (cond
                          (ave-form-single-e? schema a) (index-vset-ave-single-e ave' id a v)
                          (ave-form-eset? schema a) (index-vset-ave-eset ave' id a v)
                          :not-in-ave-index ave'))
                      :cardinality/one
                      (let [value-eav (get-in eav [id a])
                            value-eav' (get entity-eav' a)]
                        (check-db-constraints-one schema value-eav value-eav' ave'
                                                  reasserted-eav retraction-set entity-retractions id a v)
                        (cond
                          (ave-form-single-e? schema a) (index-ave-single-e ave' id a v)
                          (ave-form-eset? schema a) (index-ave-eset ave' id a v)
                          :not-in-ave-index ave'))))
                  ave' (dissoc map-form :db/id))
       reasserted-eav]
      :existing-entity
      (let [[entity-eav' ave' updated-ea]
            (reduce-kv
              (fn [[entity-eav' ave' reasserted-eav] a v]
                (cond
                  (cardinality-many? schema a)
                  (let [value-eav' (or (get entity-eav' a) (maybe-sorted-empty-set schema a))]
                    (doseq [v-item v]
                      (check-db-constraints-many schema ave' retraction-set entity-retractions id a v-item))
                    [;; `union` returns sorted-set if at least one set is a sorted-set
                     ;; (unless the sorted set is empty and comes second)
                     ;; since `value-eav'` is always of correct type, can't end up with wrong type of set
                     (assoc entity-eav' a (union value-eav' v))
                     (cond
                       (ave-form-single-e? schema a) (index-vset-ave-single-e ave' id a v)
                       (ave-form-eset? schema a) (index-vset-ave-eset ave' id a v)
                       :not-in-ave-index ave')
                     reasserted-eav])
                  :cardinality-one
                  (let [value-eav (get-in eav [id a])
                        value-eav' (get entity-eav' a)]
                    (check-db-constraints-one schema value-eav value-eav' ave'
                                              reasserted-eav retraction-set entity-retractions id a v)
                    (if (= v value-eav')
                      ;; Update `reasserted-eav` to indicate that [e a v] was in db previously
                      [entity-eav' ave' (conj reasserted-eav [id a])]
                      [(assoc entity-eav' a v)
                       (cond
                         (ave-form-single-e? schema a)
                         (if (some? value-eav')
                           (replace-ave-single-e ave' id a v value-eav')
                           (index-ave-single-e ave' id a v))
                         (ave-form-eset? schema a)
                         (if (some? value-eav')
                           (replace-ave-eset ave' id a v value-eav')
                           (index-ave-eset ave' id a v))
                         :not-in-ave-index ave')
                       reasserted-eav]))))
              [entity-eav' ave' reasserted-eav] (dissoc map-form :db/id))]
        [(assoc! eav' id entity-eav') ave' updated-ea]))))

(defn- add
  "Returns [eav' ave'] that reflect all assertions in `tx-data`."
  [db eav' ave' retraction-set entity-retraction tx-data]
  (as-> [eav' ave' #{}] $
        (reduce (fn [[eav' ave' reasserted-eav] [_ e a v]]
                  (add1 db eav' ave' reasserted-eav retraction-set entity-retraction e a v))
                $ (get-in tx-data [:list-add :entity-id]))
        (reduce (fn [[eav' ave' reasserted-eav] [_ e a v]]
                  (add1 db eav' ave' reasserted-eav retraction-set entity-retraction e a v))
                $ (get-in tx-data [:list-add :tempid]))
        (reduce (fn [[eav' ave' reasserted-eav] map-tx-form]
                  (add-map1 db eav' ave' reasserted-eav retraction-set entity-retraction map-tx-form))
                $ (get-in tx-data [:map-add :entity-id]))
        (reduce (fn [[eav' ave' reasserted-eav] map-tx-form]
                  (add-map1 db eav' ave' reasserted-eav retraction-set entity-retraction map-tx-form))
                $ (get-in tx-data [:map-add :tempid]))
        (reduce (fn [[eav' ave' reasserted-eav] map-tx-form]
                  (add-map1 db eav' ave' reasserted-eav retraction-set entity-retraction map-tx-form))
                $ (get-in tx-data [:map-add :no-id]))
        ;; omit `reasserted-eav` from return value:
        (take 2 $)))

  ;; =========
;; Transact

(defn transact
  [{:keys [db/eav db/ave db/schema db/tx-count] :as db} tx-data]
  (let [[tx-data tempids next-id entity-retraction-ids retraction-set] (prepare-tx-data db tx-data)
        eav' (transient eav)
        ave' (transient ave)
        ;; Order of retractions and assertions matters for:
        ;; - uniqueness constraint checks
        ;; - one parent per component checks
        [eav' ave'] (reduce (partial retract-entity schema) [eav' ave'] entity-retraction-ids)
        [eav' ave'] (retract db eav' ave' retraction-set)
        [eav' ave'] (add db eav' ave' retraction-set entity-retraction-ids tx-data)]
    {:db-before db
     :db-after  (assoc db
                  :db/eav (persistent! eav')
                  :db/ave (persistent! ave')
                  :db/next-id next-id
                  :db/tx-count (inc tx-count))
     :tx-data tx-data
     :tempids tempids}))

;; =========
;; Pull

(defn name-begins-with-underscore?
  [attr-name]
  ;; NOTE: the underscore must be placed before the name segment of keyword!
  (= \_ (first (name attr-name))))

(defn reverse->attr-name
  "Returns `reverse-attr-name` as a keyword without the initial underscore in the name segment of keyword."
  [reverse-attr-name]
  (keyword (namespace reverse-attr-name) (subs (name reverse-attr-name) 1)))

(defn attr-name->reverse
  "Returns reverse reference version of `attr-name` by adding an underscore to the name segment of keyword."
  [attr-name]
  (keyword (namespace attr-name) (str "_" (name attr-name))))

(defn- wrap-id [id]
  {:db/id id})

(defn- wrap-ids [ids]
  ;; keep the value a set
  (reduce (fn [r id] (conj r {:db/id id})) #{} ids))

;; =========
;; The following functions are mutually recursive with `pull`

(declare pull* pull-many*)

(defn- pull-wildcard
  "Returns entity map for entity with `id` (or {:db/id id}, if `id` is not in `db`):
   - For all component attributes, corresponding entities are recursively pulled in
   - For all non-component reference attributes, values are wrapped in {:db/id id}
   - For all non-reference attributes values are returned unchanged."
  [{:keys [db/schema db/eav] :as db} id]
  (if-let [entity (get eav id)]
    (reduce-kv (fn [entity a v]
                 (cond
                   (component? schema a)
                   (if (cardinality-many? schema a)
                     (assoc entity a (pull-many* db '[*] v))
                     (assoc entity a (pull* db '[*] v)))
                   (ref-type? schema a)
                   (if (cardinality-many? schema a)
                     (assoc entity a (wrap-ids v))
                     (assoc entity a (wrap-id v)))
                   :not-component-not-ref entity))
               entity entity)
    {:db/id id}))

(defn- pull-recursive
  "Returns updated `result` of starting with `id` and following entities forward via `join-attr` attribute `depth` times
   and applying `pattern` to each entity traversed. If a recursive subselect encounters a previously seen entity,
   instead of applying `pattern` selects the :db/id of the entity."
  [{:keys [db/schema db/eav] :as db} result pattern join-attr depth seen id]
  (cond
    (cardinality-many? schema join-attr)
    (let [ref-ids (get-in eav [id join-attr])]
      (if (or (< depth 1) (nil? ref-ids))
        result
        (let [join-val
              (reduce (fn [join-val ref-id]
                        (cond
                          (contains? seen ref-id)
                          (conj join-val (wrap-id ref-id))
                          :not-previously-seen-ref-id
                          (cond
                            (contains? eav ref-id)
                            (let [r (merge (pull* db pattern ref-id)
                                           (pull-recursive db {} pattern join-attr (dec depth)
                                                           (conj seen ref-id) ref-id))]
                              (if (empty? r)
                                join-val
                                (conj join-val r)))
                            :referenced-entity-not-in-db
                            (if (or (contains? pattern '*) (contains? pattern :db/id))
                              (conj join-val (wrap-id ref-id))
                              join-val))))
                      ;; NOTE: don't accumulate into a set because duplicates (etc) are possible
                      [] ref-ids)]
          (if (empty? join-val)
            result
            (assoc result join-attr join-val)))))
    :cardinality-one
    (let [ref-id (get-in eav [id join-attr])]
      (if (or (< depth 1) (nil? ref-id))
        result
        (cond
          (contains? seen ref-id)
          (assoc result join-attr (wrap-id ref-id))
          :not-previously-seen-ref-id
          (cond
            (contains? eav ref-id)
            (let [join-val (merge (pull* db pattern ref-id)
                                  (pull-recursive db {} pattern join-attr (dec depth) (conj seen ref-id) ref-id))]
              (if (empty? join-val)
                result
                (assoc result join-attr join-val)))
            :referenced-entity-not-in-db
            (if (or (contains? pattern '*) (contains? pattern :db/id))
              (assoc result join-attr {:db/id ref-id})
              result)))))))

(defn- pull-recursive-reverse
  "Returns the result of starting with `entity` and following entities in reverse via `pointing-attr` attribute
   `depth` time and selecting attributes `pattern` from each entity traversed. If a recursive subselect encounters a
    previously seen entity, instead of selecting attributes `pattern` selects the :db/id of the entity."
  [{:keys [db/schema db/ave] :as db} result pattern join-attr pointing-attr depth seen id]
  (let [pointing-ids (get-in ave [pointing-attr id])]
    (cond
      (nil? pointing-ids)
      result
      (ave-form-single-e? schema pointing-attr)
      (if (< depth 1)
        result
        (if (contains? seen pointing-ids)
          (assoc result join-attr (wrap-id pointing-ids))
          (let [join-val (merge (pull* db pattern pointing-ids)
                                (pull-recursive-reverse db {} pattern join-attr pointing-attr (dec depth)
                                                        (conj seen pointing-ids) pointing-ids))]
            (if (empty? join-val)
              result
              (assoc result join-attr join-val)))))
      :ave-form-eset
      (if (< depth 1)
        result
        (let [join-v
              (reduce (fn [join-v pointing-id]
                        (if (contains? seen pointing-id)
                          (conj join-v (wrap-id pointing-id))
                          (conj join-v (merge (pull* db pattern pointing-id)
                                              (pull-recursive-reverse db {} pattern join-attr pointing-attr (dec depth)
                                                                      (conj seen pointing-id) pointing-id)))))
                      ;; NOTE: don't accumulate into a set because duplicates (etc) are possible
                      [] pointing-ids)]
          (if (empty? join-v)
            result
            (assoc result join-attr join-v)))))))

(defn- pull*
  [{:keys [db/schema db/eav db/ave] :as db} pattern id]
  (reduce
    (fn [result attr-spec]
      (cond
        ;; ===== WILDCARD =====
        (= '* attr-spec)
        (merge (pull-wildcard db id) result)  ;; `result` is second to preserve any previous joins
        ;; ===== KEYWORD ATTRIBUTE =====
        (keyword? attr-spec)
        ;; ===== Reverse Reference Attribute =====
        (if (name-begins-with-underscore? attr-spec)
          (let [pointing-attr (reverse->attr-name attr-spec)]
            (if (ref-type? schema pointing-attr)
              (if-let [pointing-ids (get-in ave [pointing-attr id])]
                (assoc result attr-spec (if (ave-form-single-e? schema pointing-attr)
                                          (wrap-id pointing-ids)
                                          (wrap-ids pointing-ids)))
                ;; no reverse references
                result)
              ;; non-reference reverse attribute - IGNORE
              result))
          ;; ===== Regular (Non-Reverse) Attribute =====
          (if (= :db/id attr-spec)
            (assoc result :db/id id) ;; always include :db/id
            (if-let [v (get-in eav [id attr-spec])]
              (cond
                (component? schema attr-spec)
                ;; NOTE: Will return {:db/id id} for component entities whose :db/id not in db
                (if (cardinality-many? schema attr-spec)
                  (assoc result attr-spec (pull-many* db '[*] v)) ;; returns seq (can have multiple identical components)
                  (assoc result attr-spec (pull* db '[*] v)))
                (ref-type? schema attr-spec)
                (assoc result attr-spec (if (cardinality-many? schema attr-spec)
                                          (wrap-ids v)      ;; returns set
                                          (wrap-id v)))
                :non-reference-attribute
                (assoc result attr-spec v))
              ;; entity does not contain the attribute
              result)))
        ;; ===== JOIN =====
        (map? attr-spec) ;; recursive or non-recursive join
        (let [[join-attr join-pattern] (first attr-spec)]
          (if (int? join-pattern)
            ;; ===== Recursive Join =====
            (cond
              (name-begins-with-underscore? join-attr)
              (let [pointing-attr (reverse->attr-name join-attr)
                    recur-pattern (disj (set pattern) attr-spec)]
                (if (ref-type? schema pointing-attr)
                  (pull-recursive-reverse db result recur-pattern join-attr pointing-attr join-pattern #{id} id)
                  result))
              ;; join-recursive-regular-attribute
              (ref-type? schema join-attr)
              (let [recur-pattern (disj (set pattern) attr-spec)]
                (pull-recursive db result recur-pattern join-attr join-pattern #{id} id))
              :non-reference-attribute
              result)
            ;; ===== Non-Recursive Join =====
            (cond
              (name-begins-with-underscore? join-attr)
              (let [pointing-attr (reverse->attr-name join-attr)]
                (if (ref-type? schema pointing-attr)
                  (if-let [pointing-ids (get-in ave [pointing-attr id])]
                    (if (ave-form-single-e? schema pointing-attr)
                      (let [r (pull* db join-pattern pointing-ids)]
                        (if (empty? r)
                          result
                          (assoc result join-attr r)))
                      (let [r (remove empty? (pull-many* db join-pattern pointing-ids))]
                        (if (empty? r)
                          result
                          ;; Don't convert (set r) because while ref-ids are unique, after applying join-pattern, not guaranteed unique
                          (assoc result join-attr r))))
                    ;; no pointing-ids
                    result)
                  ;; `pointing-attr` not reference type
                  result))
              (ref-type? schema join-attr)
              (if-let [ref-ids (get-in eav [id join-attr])]
                (if (cardinality-many? schema join-attr)
                  (let [r (remove empty? (pull-many* db join-pattern ref-ids))]
                    ;; Don't convert (set r) because while ref-ids are unique, after applying join-pattern, not guaranteed unique
                    (if (empty? r)
                      result
                      (assoc result join-attr r)))
                  ;; cardinality/one
                  (let [r (pull* db join-pattern ref-ids)]
                    (if (empty? r)
                      result
                      (assoc result join-attr r))))
                ;; no ref-ids
                result)
              :non-reference-attribute
              result)))))
    {} pattern))

(defn- pull-many*
  [db pattern ids]
  (map #(pull* db pattern %) ids))

(defn pull
  [db pattern id]
  (let [id (if (lookup-ref? id)
             (resolve-lookup-ref-or-throw db id)
             id)]
    (pull* db pattern id)))

(defn pull-many
  [db pattern ids]
  (map #(pull db pattern %) ids))

(defn find-reverse-refs
  "Returns a set of [attr eid] vectors representing reverse refs to `target-id`."
  [{:keys [db/schema db/ave]} target-id]
  (reduce
    (fn [reverse-refs pointing-attr]
      (let [ave-a (get ave pointing-attr)
            pointing-id-or-idset (get ave-a target-id)]
        (if (nil? pointing-id-or-idset)
          reverse-refs
          (if (ave-form-single-e? schema pointing-attr)
            (conj reverse-refs [pointing-attr pointing-id-or-idset])
            (reduce (fn [reverse-refs pointing-id]
                      (conj reverse-refs [pointing-attr pointing-id]))
                    reverse-refs pointing-id-or-idset)))))
    #{} (:db/isRef schema)))

