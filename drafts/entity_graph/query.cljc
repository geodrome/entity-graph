(ns entity-graph.query
  (:require
    [clojure.data.avl :as avl]
    [clojure.set :refer [intersection difference union]]
    [entity-graph.core :refer [pull-many cardinality-many?] :refer :all]))

;; How to implement sorted to-many relationships in Datomic?
;; https://stackoverflow.com/questions/44645938/how-to-implement-sorted-to-many-relationships-in-datomic
;; https://stackoverflow.com/questions/33682064/properties-on-datomic-ref-relationships/61406767#61406767

;; NOTE: Query system can be a separate lib, much like pull...
;; should we even have :db.index/sorted? the only advantage is i.e. sorted :manufacturer/name
;;   - easy to offer, but even sorting manufacturers in re-frame only happens when data changes
;; (map eav id-seq) to retain (potentially sorted) order - MUST be seq, or (select-keys eav id-set) to get eav subset
;; RANGE QUERIES
;; avl trees: https://github.com/clojure/data.avl
;; hash-map: linear filter of v's, linear select from eav, results unsorted
;; sorted-map: linear filter of v's (can't do log(n) because subvec and nth are O(n)), linear select from eav, results sorted
;; avl/sorted-map: logarithmic filter of v's, linear select from eav, results sorted
;; covering ave index would eliminate linear selects from eav
;; but for unions/intersections would have to operate on entities or would need to (map :db/id)

;; todo: sort order lost on set union/intersection
;; -> start with [> 35000] (desired sorting seq), `intersection-seq` with #{"Berlin"} set
;; ... but it may be faster to start with #{"Berlin"}...
;; -> only makes sense when desired sorting is also optimal way to start query
;; datomic: tuple for each cardinality/many [> 35000], we just identify id, trim later if needed
;; keeping sorted entity order during query doesn't really help on cardinality/many attrs -> may be duplicate ids in seq...
;; only helps to start with sorted cardinality/one, then use `intersection-seq` for additional constraints
;; then can sort-entities or sort-tuples (after pulling and trimming, if desired)

;; linear in size of s1-seq
;; force s1 to be the sequential or permit either s1 or s2 to be the sequential?
(defn intersection-seq
  "Returns a seq that is the intersection of `s1-seq` and `s2-set`. Preserves order of `s1-seq`."
  [s1-seq s2-set]
  (reduce (fn [result seq-item]
            (if (contains? s2-set seq-item)
              (conj result seq-item)
              result))
          [] s1-seq))

;; tuples can be sorted in any way desired, including for individual cardinality/many attr/vals
;; can start with trimmed entity, then convert to tuple or can convert pulled-entity to tuples and filter tuples
;; todo: support :db/id (nested entities can have db/id? - PULL)
(defn pulled-entity->tuple
  "Returns a tuple consisting of `paths` into `pulled-entity`.
  `paths` may contain underscored keywords for reverse navigation."
  [schema paths pulled-entity]
  (reduce (fn [result path]
            (let [attr (last path)
                  v (if (keyword? path)
                      (get pulled-entity path)
                      (get-in pulled-entity path))]
              ;; todo: should return multiple tuples for cardinality/many
              ;; should work for reverse attr
              (if (cardinality-many? schema attr)
                (reduce conj result v)
                (conj result v))))
          [] paths))

;; use built-in sort to sort tuples
(defn nested-entities->tuples
  [schema entities paths]
  ;; map or reduce into single coll?
  (map #(pulled-entity->tuple schema paths %) entities))

(defn attr-comparator-seq->comparator
  "Returns a single comparator fn based on a sequence of attribute comparator pairs."
  [attr-comparator-seq]
  (let [pairs (partition 2 attr-comparator-seq)]
    (fn [entity1 entity2]
      (loop [[[attr f] pairs] pairs]
        (let [r (f (entity1 attr) (entity2 attr))]
          (if (and (zero? r) pairs)
            (recur pairs)
            r))))))

;; sorting entities on cardinality/many attrs doesn't make sense... need to reduce to single val (min, max, etc)
;; attr-comparator-seq example:
;; [:person/salary > :person/city < :person/past-salaries (fn [v-set1 v-set2] (> (max v-set1) (max v-set2)))]
(defn sort-entities
  [{:keys [db/eav] :as db} ids & attr-comparator-seq]
  (let [comparator (attr-comparator-seq->comparator attr-comparator-seq)
        entities (map eav ids)]
    (sort comparator entities)))

;;;;;

;; "RAW" preds that don't account for cardinality/many - pred has to account for it
(defn filter-entities
  [{:keys [db/eav] :as db} pred]
  (filter pred (vals eav)))

(defn filter-eav
  [{:keys [db/eav db/schema] :as db} pred]
  (reduce (fn [result [id entity]]
            (if (pred entity)
              (assoc result id entity)
              result))
          {} eav))

;; ref values are possible, but it's questionable to apply preds to them
;; ref values for reverse attrs also possible
(defn trim-entity
  "Returns `entity` with only the `attr` values that match `pred`."
  [schema attr pred entity]
  (if (cardinality-many? schema attr)
    (let [val-set (filter pred (entity attr))]
      ;; empty val-set when (entity attr) is nil or when nothing matched pred
      (if (empty? val-set)
        (dissoc entity attr)
        (assoc entity attr val-set)))
    ;; if previously applied pred during `get-ids`, don't need to apply again
    ;; unless different pred for trimming...
    (if (pred (entity attr))
      entity
      (dissoc entity attr))))

;; supports reverse paths with no extra effort
;; `entity` is a pulled-entity
(defn trim-path
  "Returns `entity` with only the `path` values that match `pred`."
  [schema [first-path rest-path :as path] pred entity]
  (if rest-path
    (let [nested-entity (entity first-path)]
      (if (map? nested-entity)
        (let [nested-entity (trim-path schema rest-path pred nested-entity)]
          (if (empty? nested-entity)
            (dissoc entity first-path)
            (assoc entity first-path nested-entity)))
        (let [nested-entities (map #(trim-path schema rest-path pred %) nested-entity)]
          (if (empty? nested-entities)
            (dissoc entity first-path)
            (assoc entity first-path nested-entities)))))
    ;; no rest-path, assume first-path is the attr
    (trim-entity schema first-path pred entity)))

;; possible: {:some-attr 'some-pred [:some-attr] 'some-pred}
;; supply [path pred] pairs as map to avoid traversing same path more than once
;; able to handle multiple preds for same path -> not reduce-kv
;; support for vector form range preds? to make reusing `attr-pred-pairs` more convenient?
(defn trim
  [schema path-pred-pairs entity]
  (reduce (fn [entity [path pred]]
            (if (keyword? path)
              (trim-entity schema path pred entity)
              (if (= 1 (count path))
                (trim-entity schema (first path) pred entity)
                (trim-path schema path pred entity))))
          entity path-pred-pairs))

;;;; TRUE PRED

;; same code in ve-map->id-set
;; returns set
(defn ids-by-attr-unique
  [ave attr]
  ;; do we have to call set? guaranteed to be unique...
  (set (vals (get ave attr))))

;; returns set
;; might use concat for returning seq: ~2x faster
(defn ids-by-attr-non-unique
  [ave attr]
  (reduce union (vals (get ave attr))))

;; returns set
(defn ids-by-attr-eav
  [eav attr]
  (->> (vals eav) (filter #(contains? % attr)) (map :db/id) (set)))

;;; SET PRED

;; linear time in the size of pred
;; returns set
(defn filter-ave-unique-set
  [ave attr pred]
  (reduce (fn [ret v]
            (if-let [e (get-in ave [attr v])]
              (conj ret e)
              ret))
          #{} pred))

;; returns set
(defn filter-ave-non-unique-set
  [ave attr pred]
  (reduce (fn [ret v]
            (if-let [e-set (get-in ave [attr v])]
              (apply conj ret e-set)
              ret))
          #{} pred))

;; unused
(defn eav-pred
  [{:keys [db/schema] :as db} attr pred entity]
  (let [v (entity attr)]
    (if (cardinality-many? schema attr)
      (first (drop-while #(or (nil? %) (false? %)) (map pred v)))
      (pred db v))))

(defn entity-attr-pred
  "Applies pred to `entity` value under `attr`. Works for :db.cardinality/one attrs or :db.cardinality/many attrs.
   For :db.cardinality/many returns first truthy value or nil if none are truthy."
  [schema attr pred entity]
  ;; don't use nil? pred since nil is not a valid db value
  (when-let [v (get entity attr)]
    (if (cardinality-many? schema attr)
      (some pred v)
      ;(first (drop-while #(or (nil? %) (false? %)) (map pred v)))
      (pred v))))

;; linear time in size of eav
;; returns seq/set
(defn filter-eav-attr-pred
  ([eav schema attr pred]
   (->> (vals eav)
        (filter #(entity-attr-pred schema attr pred %))
        (map :db/id)))
  ;; linear time in size of xids
  ([eav schema attr pred xids]
   (reduce (fn [ret id]
             (if (entity-attr-pred schema attr pred (eav id))
               (conj ret id)
               ret))
           #{} xids)))

;;; RANGE PRED

;; what other ops make sense here? ones that are more efficient with avl-map:
;; min/max -> also work with sorted-map
;; median - yes
;; rank queries, lookups of "nearest entries" = [`nth` 1], but also avl/rank-of [some-val] -> returns value
;; [:rank> 3] [:percentile 97.55] [:median] [:average] [:nearest 3]
;; rank queries -> percentile calculations -> rank/total
;; "nearest entries"
(defn avl-op
  [avl-map op val]
  (cond
    (= op <) (let [[l m r] (avl/split-key val avl-map)] l)
    (= op <=) (let [[l m r] (avl/split-key val avl-map)] (if m (apply assoc l m) l))
    (= op >) (let [[l m r] (avl/split-key val avl-map)] r)
    (= op >=) (let [[l m r] (avl/split-key val avl-map)] (if m (apply assoc r m) r))))

;; returns ave-a subset
(defn eval-range-pred
  [ave attr [a b :as pred]]
  (if (vector? a)
    (let [[op val] a
          r (avl-op (ave attr) op val)]
      (if b
        (let [[op val] b]
          (avl-op r op val))
        r))
    (let [[op val] pred]
      (avl-op (ave attr) op val))))

;; TODO: use Logarithmic time slicing for >= < etc!!!
;; change syntax from [[< 34] [> 8]] to [< 34 > 8]
;; NOTE: when passing > within vector, it is evaled by clojure
;; returns ave-a subset just like eval-range-pred
;; MAYBE should return ids
(defn eval-range-pred2
  [ave attr pred]
  (apply avl/subrange (ave attr) pred))

;;; GENERIC PRED

;; linear time in ave-a size
;; still better than traversing eav because only looking at entities that contain `attr`
;; returns seq
(defn filter-ave-unique
  [ave attr pred]
  (->> (get ave attr) (filter (fn [[v e]] (pred v))) (map second)))

;; returns seq
(defn filter-ave-non-unique
  [ave attr pred]
  (->> (get ave attr) (filter (fn [[v e-set]] (pred v))) (mapcat second)))

;;; GET IDS

;; always used with OR `ref-type?` => `ave-form-single-e?` `ave-form-eset?`
(defn index? [schema attr]
  ((schema :db/index) attr))

(defn index-avl-map? [schema attr]
  ((schema :db.index/avl-map) attr))

(defn get-ids-false-pred
  ([{:keys [db/schema db/eav db/ave]} attr]
   (cond
     ;; linear (keys eav), linear ids-with-attr, linear difference
     ;; faster to always filter eav?
     (unique? schema attr)
     (let [ids-with-attr (ids-by-attr-unique ave attr)]
       (difference (set (keys eav)) ids-with-attr))
     (or (index? schema attr) (ref-type? schema attr))
     (let [ids-with-attr (ids-by-attr-non-unique ave attr)]
       (difference (set (keys eav)) ids-with-attr))
     :not-in-ave-index
     (do
       (println "Warning! get-ids-false-pred for attr not in AVE index: " attr)
       (->> (vals eav) (remove #(contains? % attr)) (map :db/id) (set)))))
  ([{:keys [db/schema db/eav db/ave] :as db} attr xids]
   ;; don't bother relying on ave index?
   (reduce (fn [ids xid]
             (if (contains? (eav xid) attr)
               (disj ids xid)
               ids))
           xids xids)))

;; returns set
(defn get-ids-true-pred
  ([{:keys [db/schema db/eav db/ave] :as db} attr]
   (cond
     (unique? schema attr)
     (ids-by-attr-unique ave attr)
     (or (index? schema attr) (ref-type? schema attr))
     (ids-by-attr-non-unique ave attr)
     :not-in-ave-index
     (do
       (println "Warning! get-ids-true-pred for attr not in AVE index: " attr)
       (ids-by-attr-eav eav attr))))
  ([{:keys [db/schema db/eav db/ave] :as db} attr xids]
   ;; `intersection` is linear in size of smaller set
   ;; `get-ids-true-pred` is linear in size of ave-a vals concatted
   (if (< (count xids) (count (ave attr)))
     (reduce (fn [ids xid]
               (if (contains? (eav xid) attr)
                 ids
                 (disj ids xid)))
             xids xids)
     (intersection (get-ids-true-pred db attr) xids))))

;; returns set
(defn get-ids-set-pred
  ([{:keys [db/schema db/eav db/ave] :as db} attr pred]
   (cond
     (unique? schema attr)
     (filter-ave-unique-set ave attr pred)
     (or (index? schema attr) (ref-type? schema attr))
     (filter-ave-non-unique-set ave attr pred)
     :not-in-ave-index
     (do
       (println "Warning! get-ids-set-pred for attr not in AVE index: " attr)
       (set (filter-eav-attr-pred eav schema attr pred)))))
  ([{:keys [db/schema db/eav db/ave] :as db} attr pred xids]
   ;; set lookups are linear in size of pred
   ;; but filter-eav does more work per step... or maybe similar -> test
   (if (< (count xids) (count pred))
     (filter-eav-attr-pred eav schema attr pred xids)
     (intersection (get-ids-set-pred db attr pred) xids))))

(defn entity-attr-pred2
  "Returns all vals of `attr` that match `pred` or nil."
  [schema attr pred entity]
  (when-let [v (get entity attr)]
    (if (cardinality-many? schema attr)
      (seq (filter pred v))
      (when (pred v) v))))

(defn get-maps-set-pred
  [{:keys [db/schema db/eav db/ave] :as db} attr pred]
  (cond
    (unique? schema attr)
    (reduce (fn [ret v]
              (if-let [e (get-in ave [attr v])]
                (assoc-in ret [e attr] v)
                ret))
            {} pred)
    (or (index? schema attr) (ref-type? schema attr))
    (reduce (fn [ret v]
              (if-let [e-set (get-in ave [attr v])]
                (reduce (fn [ret id] (update-in ret [id attr] conj v)) ret e-set)
                ret))
            {} pred)
    :not-in-ave-index
    (do
      (println "Warning! get-maps-set-pred for attr not in AVE index: " attr)
      (reduce (fn [result entity]
                ;; here we filter the cardinality/many attr ourselves, so might as well save it for the end?
                ;; so then we shouldn't use get-maps when no ave index... defeats the purpose?
                ;; but then also shouldn't do it for cardinality-one... may lead to waste if discarded
                (if-let [v (entity-attr-pred2 schema attr pred entity)]
                  (assoc-in result [(:db/id entity) attr] v)
                  result))
              {} (vals eav)))))

;; returns seq (to preserve order; may have duplicates)
#_(defn get-ids-set-pred2
    [{:keys [db/schema db/eav db/ave] :as db} attr pred]
    (let [ave-a (get-ave-a-set-pred db attr pred)]
      (cond
        (unique? schema attr)
        (vals ave-a)
        (or (index? schema attr) (ref-type? schema attr))
        (apply concat (vals ave-a))
        :default
        (set (filter-eav-attr-pred eav schema attr pred))))
    )

(defn log32 [n]
  (/ (Math/log n) (Math/log 32)))

;; TODO: returning sets means losing order...
;; when sorted ave-a, can return seq and preserve order - maybe better to return ave-a subset
;; - duplicate ids possible for cardinality/many, distinct keeps first id (lowest sorted)
;; 1. pull [val id-set] from ave-a
;; preserve sorting order when eventual result is desired sorted by same attr in the same order as ave-a (asc/desc)
;; ... and no additional sorting (like no sorting by city after sorted by salary)
;; if sorting on attr and sorted ave-a available, can use it as the last pred as a potential optimization
;; do pred on ave-a, then rm-xids on ave-a => faster than sorting after pulling from eav? yes for larger subsets of ave-a
;; returns set
(defn get-ids-range-pred
  ([{:keys [db/schema db/eav db/ave] :as db} attr pred]
   (if (index-avl-map? schema attr)
     (let [r (eval-range-pred ave attr pred)]
       (if (unique? schema attr)
         (set (vals r))
         (apply union (vals r))))
     (println "Warning! get-ids-range-pred for attr not in AVE index or not an AVL index: " attr)))
  ([{:keys [db/schema db/eav db/ave] :as db} attr pred xids]
   ;; range q is log32(size ave-a)
   ;; for few xids might be faster to scan eav index
   ;; (if (< (count xids) (log32 (count (ave attr))))))
   ;; also need to convert pred from vector to generic to filter-eav
   (intersection (get-ids-range-pred db attr pred) xids)))

;; returns set
(defn get-ids-generic-pred
  ([{:keys [db/schema db/eav db/ave] :as db} attr pred]
   (cond
     (unique? schema attr)
     (set (filter-ave-unique ave attr pred))
     (or (index? schema attr) (ref-type? schema attr))
     ;; convert seq (with possible duplicate ids) to set
     (set (filter-ave-non-unique ave attr pred))
     :not-in-ave-index
     (do
       (println "Warning! get-ids-generic-pred for attr not in AVE index: " attr)
       (set (filter-eav-attr-pred eav schema attr pred)))))
  ([{:keys [db/schema db/eav db/ave] :as db} attr pred xids]
   (if (contains? ave attr)
     (if (< (count xids) (count (ave attr)))
       (set (filter-eav-attr-pred eav schema attr pred xids))
       (intersection xids (get-ids-generic-pred db attr pred)))
     (filter-eav-attr-pred eav schema attr pred xids))))

;; with reverse attr support
;; can modify to return all matching vals rather than just yes/no (slower, but returns more info)
(defn some-path
  "Returns logical true if some `path` satisfies `pred`, else returns nil.
   Assumes everything in `path` is ref attr or reverse ref attr except the final attr in path cannot be a reverse attr."
  [{:keys [db/schema db/eav db/ave] :as db} id [attr more-attrs :as path] pred]
  (let [entity (eav id)]
    (if more-attrs
      (if (reverse-reference? attr)
        (let [pointing-attr (reverse->attr-name attr)]
          (if (unique? schema pointing-attr)
            (when-let [pointing-id (get-in ave [pointing-attr id])]
              (some-path db pointing-id more-attrs pred))
            (when-let [pointing-ids (get-in ave [pointing-attr id])]
              (some #(some-path db % more-attrs pred) pointing-ids))))
        ;; forward attribute
        (if (cardinality-many? schema attr)
          (when-let [next-ids (entity attr)]
            (some #(some-path db % more-attrs pred) next-ids))
          (when-let [next-id (entity attr)]
            (some-path db next-id more-attrs pred))))
      (when-let [v (entity attr)]
        (if (cardinality-many? schema attr)
          (some pred v)
          (pred v))))))

(declare ve-map->id-set)

#_(let [pids (get-ids db :person/city #{"Moscow"})
        pids (get-ids db [:person/license :dl/year] #(>= % 2020))])
;; ensure everything along path is a ref (except last attr)? No, just assume
;; apply pred to ref attr? questionable, but is there a downside?
;; non-generic preds don't make sense (except sets); ranges? nope -> would have to start with range query and link back
;; and what about applying the pred to all cardinality/many links at once?
;; -> maybe an extra kw arg to signal to some-path to apply pred to entire set?
;; what about pred applied to paths? like "get the user with best-friend with largest salary"
;; returns set
(defn get-ids-path-attr
  ([{:keys [db/schema db/eav db/ave] :as db} [attr more-attrs :as path] pred]
   (if-let [ave-attr (ave attr)]
     ;; could use `get-ids-true-pred`? -> it may fallback to scanning eav index
     (let [ids (ve-map->id-set schema attr ave-attr)]
       (get-ids-path-attr db path pred ids))
     (get-ids-path-attr db path pred (keys eav))))
  ([db [attr more-attrs :as path] pred xids]
   (reduce (fn [result id]
             (if (some-path db id path pred)
               (conj result id)
               result))
           #{} xids)))

;; pred is applied to the set of cardinality/many values (not to individual values)
(defn filter-eav-many
  ([eav attr pred]
   (->> (vals eav)
        (filter (fn [entity] (pred (entity attr))))
        (map :db/id)
        (set)))
  ([eav attr pred xids]
   ;; xids don't need to be sets
   (filter-eav-many (select-keys eav xids) attr pred)))

(defn get-ids-many-pred
  "Applies pred to the entire set of values for :db.cardinality/many `attr`."
  ([{:keys [db/schema db/eav db/ave] :as db} attr pred]
   (if (cardinality-many? schema attr)
     (if (contains? ave attr)
       (filter-eav-many eav attr pred (ids-by-attr-non-unique ave attr))
       (filter-eav-many eav attr pred))
     (assert false (str "Attribute must be :db.cardinality/many: " attr))))
  ([{:keys [db/schema db/eav db/ave] :as db} attr pred xids]
   (if (cardinality-many? schema attr)
     (if (< (count xids) (count (ave attr)))
       (filter-eav-many eav attr pred xids)
       (if (contains? ave attr)
         (filter-eav-many eav attr pred (ids-by-attr-non-unique ave attr))
         (filter-eav-many eav attr pred xids)))
     (assert false (str "Attribute must be :db.cardinality/many: " attr)))))

;;;;;; GET AVE_A

(defn get-ve-map-true-pred
  [{:keys [db/schema db/eav db/ave] :as db} attr]
  (ave attr))

(defn get-ve-map-set-pred
  [{:keys [db/schema db/eav db/ave] :as db} attr pred]
  (select-keys (ave attr) pred))

(defn get-ve-map-range-pred
  [{:keys [db/schema db/eav db/ave] :as db} attr pred]
  (eval-range-pred ave attr pred))

;; may be less efficient than get-ids because of (into {})
(defn get-ve-map-generic-pred
  [{:keys [db/schema db/eav db/ave] :as db} attr pred]
  ;; what if (ave attr) is nil?
  (->> (ave attr)
       (filter (fn [[v e]] (pred v)))
       (into {})))

;; alternative approach: test performance
(defn get-ve-map-generic-pred2
  [{:keys [db/schema db/eav db/ave] :as db} attr pred]
  (reduce-kv (fn [r v e]
               (if (pred v) r (dissoc r v)))
             (ave attr) (ave attr)))

;; Aggregates like (max, count, etc) operate on ave-a, so returning just ids precludes aggregate operations
;; for aggregations ave-a can transform e.g :person/salary
;; {10000 #{2 3 9}, 20000 #{4 6 7}} => {{:val 10000 :count 3} #{2 3 9}, {:val 20000 :count 2} #{4 6}}
;; or {10000 {:ids #{2 3 9} :count 3}, 20000 {:ids #{4 6} :count 2}} => or a separate map?
;; aggregates on: ids (count), values individual (length), values aggregate (sum, average, max)
;; also supports the case where you want to preserve the sorting ave-a index
;; TODO: maybe easier to accomplish aggregation by going through EAV index?
(defn get-ve-map
  [{:keys [db/schema db/eav db/ave] :as db} attr pred]
  (cond
    ;; support false for lack of attribute
    (true? pred)
    (get-ve-map-true-pred db attr)
    (set? pred)
    ;; set pred can get v's from ave-a directly - faster
    (get-ve-map-set-pred db attr pred)
    ;; range pred: take advantage of avl index
    (vector? pred)
    (get-ve-map-range-pred db attr pred)
    :default ;; just a regular pred, have to go through all values in ave-a
    (get-ve-map-generic-pred db attr pred)))
;; for optimizations like get-ids would need:
;; 1. keep track of all ids -> need that for `intersect-ve-map`
;; TODO: don't know what to keep until you got all the ids...
;; faster to `rm-xids` from desired ave-a after you have all the ids via `get-ids`?
;; `get-ids` will rely on eav index when that's faster, so doesn't always rely on ave index...
;; lose the advantge with e.g. set preds of selecting just the v's that you want...
;; Q: when do I want ve-map? for aggregates? ever? is eav index based aggregation sufficient?
;; if you NEED ve-map, then could apply pred first, then do rm-x-ids (kind of duplicating the work for that pred)

(defn ve-map->id-set
  [schema attr ve-map]
  (if (unique? schema attr)
    (set (vals ve-map))
    (apply union (vals ve-map))))

(defn ve-map->id-seq
  [schema attr ve-map]
  (if (unique? schema attr)
    (vals ve-map)
    (apply concat (vals ve-map))))

;; removes ids from ve-map that are not in xids
;; removes v's whose id-set doesn't intersect with any xids
;; returns ve-map
(defn rm-xids
  [schema attr xids ve-map]
  (reduce-kv (fn [result v e]
               (if (unique? schema attr)
                 (if (contains? xids e)
                   (assoc result v e)
                   result)
                 (if-let [v-ids (intersection e xids)]
                   (assoc result v v-ids)
                   result)))
             {} ve-map))

;; returns ave-sub
;; todo: same attr diff preds
;; at each step, can get-ve-map based on ids or based on pred
;; when is it faster based on ids?
(defn get-ve-map-many
  [{:keys [db/schema db/eav db/ave] :as db} & attr-pred-pairs]
  (let [ave-sub
        (reduce (fn [ave-sub [attr pred]]
                  (assoc ave-sub attr (get-ve-map db attr pred)))
                {} (partition 2 attr-pred-pairs))
        id-sets (map (fn [[attr ve-map]] (ve-map->id-set schema attr ve-map)) ave-sub)
        ids (apply intersection id-sets)]
    (reduce-kv (fn [ave-sub attr ve-map]
                 (assoc ave-sub attr (rm-xids schema attr ids ve-map)))
               {} ave-sub)))

;; can also select ids from eav, but can we speed it up here or give matching vals?
;; matching vals help for aggregating count by val
;; returns ave-sub with each attr ve-map containing only x-ids
(defn intersect-ave-sub
  [schema ave-sub]
  (let [id-sets (map (fn [[attr ve-map]] (ve-map->id-set schema attr ve-map)) ave-sub)
        x-ids (apply intersection id-sets)]
    (reduce-kv (fn [result attr ve-map]
                 (assoc result attr (rm-xids schema attr x-ids ve-map)))
               {} ave-sub)))

;; returns a seq of 1 or more (for cardinality/many) [e v] tuples based on [v e] tuple
(defn invert-ave-a-entry
  [schema attr [v e]]
  (if (unique? schema attr)
    [[e v]]
    (reduce (fn [r [v single-e]]
              (conj r [single-e v]))
            [] e)))

;; returns map of {id v-set}
(defn invert-ave-a-non-unique
  [ave-a]
  (reduce (fn [a [k v]]
            (assoc a k (conj (get a k #{}) v)))
          {} (for [[k s] ave-a v s] [v k])))

;; returns [e a v] tuples in same order as ave-a
(defn ave-a->eav-tuples
  [schema attr ave-a]
  (if (unique? schema attr)
    (reduce (fn [r [v e]]
              conj r [e attr v])
            [] ave-a)
    (reduce (fn [r [v e-set]]
              (reduce (fn [r e]
                        (conj r [e attr v]))
                      r e-set))
            [] ave-a)))

(defn get-eav-tuples
  [{:keys [db/schema db/eav db/ave] :as db} attr pred]
  (let [ave-a (get-ve-map db attr pred)]
    (ave-a->eav-tuples schema attr ave-a)))

;; TODO: this is an alternative implementation of get-ids that relies on `get-ave-a` first
;; is it as efficient to get ave-a first, and then convert to id sets?
;; might be slower for set preds because constructing intermediate map with select-keys
;; NOTE: predicates support tuple bindings and collection bindings, but relations bindings need a bit extra work
;; https://docs.datomic.com/cloud/query/query-data-reference.html#relation-binding
;; todo: assumes ave exists - what about eav fallback? (if relying on this as first step for get-ids)
(defn get-ids2
  [{:keys [db/schema db/eav db/ave] :as db} attr pred]
  (let [ave-a (get-ve-map db attr pred)]
    (ve-map->id-set schema attr ave-a)))

;; OTHER
;; fns to implement: get-else, get-some
;; missing? = false pred in get-ids for lack of attr
;; not clause implemented with arbitrary pred, but maybe a better way?

;; TODO: another option is to build eav-sub as we go along
;; can select ids from eav at each step, but replacing attr with just the matching pred from ve-map
;; then for next pred, we would dissoc the difference between pred1 ids and pred2 ids from eav-sub
;; this is potentially a lot of wasted processing, since subsequent preds may shrink ids-set by a lot
;; todo: should `pull` support preds for cardinalit/many attrs? -> better as separate step

;; TUPLES allow to get pred #{Moscow}, :person/name :person/building-number (ref) => pull

;; this is supposed to support selecting from eav based on a ave-sub
;; which had been built up as preds matched in ve-maps, and "thinned" with subsequent preds -> could be a LOT of "thinning"
;; and the thinning process is costly -> more efficient if invert to ev-map? slightly; plus inverting itself costly
;; returns eav map for all ids in ve-map, excludes `next-attrs` from entities
(defn select-entities1
  [result schema attr ve-map eav next-attrs]
  (if (unique? schema attr)
    (reduce-kv (fn [result v id]
                 (assoc result id (eav id)))
               {} ve-map)
    (if (cardinality-many? schema attr)
      (reduce-kv (fn [result v e]
                   (reduce (fn [result id]
                             (if (contains? result id)
                               (update-in result [id attr] conj v)
                               (let [entity (-> (apply dissoc (eav id) next-attrs)
                                                (assoc attr #{v}))]
                                 (assoc result id entity))))
                           result e))
                 {} ve-map)
      ;; cardinality/one
      (reduce-kv (fn [result v e]
                   (reduce (fn [result id]
                             (assoc result id (eav id)))
                           result e))
                 result ve-map))))

;; todo: if there are two ve-maps for same attr... ave-sub can only have one key for each attr...
;; returns entities with pred-matching cardinality/many values only
(defn select-entities
  [schema ave-sub eav]
  (loop [result {} ave-sub ave-sub]
    (if-let [[attr ve-map] (first ave-sub)]
      (let [next-ave-sub (next ave-sub)
            next-attrs (map first next-ave-sub)
            result (select-entities1 result schema attr ve-map eav next-attrs)]
        (recur result next-ave-sub))
      result)))

;; todo: returns id if at least one cardinality/many value matches pred, but not the specific value(s)
;; how to only include desired values? return partial entities from eav (filter cardinality/many)?
;; todo: key questions is what do you want returned in the end?? think about the final step of displaying on the screen...
;; do you want them as tuples or as entity-maps with filtered cardinality/many values?
;; how about if I want ppl who have ONLY lived in #{Moscow Berlin} and nowhere else? NOT clause?
;; could re-fitler, but is there a way to do it "along the way" -> get-ids would have to return some sort of tuple
;; if returning tuples, then is it still possible to compose intersections of ids? maybe if we return distinct ids alongside
;; -> would have to filter-eav... or filter-ave and then filter-eav... or return ave-a
;; do we want preds that operate on entire set of values for attr? like "two or more past-cities in germany"
;; maybe return ave-a submap? how to intersect/union on submaps? => they are on different attrs!
;; TODO: how to express "not equal"? [!= 4], [!= #{3 4 5}], [not= 8], (
;; how to express multiple preds for one attr...
(defn get-ids
  ([db attr pred]
   (if (vector? attr)
     (get-ids-path-attr db attr pred)
     (cond
       (false? pred)
       (get-ids-false-pred db attr)
       (true? pred)
       (get-ids-true-pred db attr)
       (set? pred)
       (get-ids-set-pred db attr pred)
       (vector? pred)
       (get-ids-range-pred db attr pred)
       (fn? pred)
       (get-ids-generic-pred db attr pred)
       :default
       (assert false (str "Invalid predicate: " pred)))))
  ([db attr pred xids]
   ;; here must have sets, while above a seq might do to preserve sort order
   (if (vector? attr)
     (get-ids-path-attr db attr pred xids)
     (cond
       (false? pred)
       (get-ids-false-pred db attr xids)
       (true? pred)
       (get-ids-true-pred db attr xids)
       (set? pred)
       (get-ids-set-pred db attr pred xids)
       (vector? pred)
       (intersection (get-ids-range-pred db attr pred) xids)
       (fn? pred)
       (get-ids-generic-pred db attr pred xids)
       :default
       (assert false (str "Invalid predicate: " pred))))))

;; TODO: build up datoms/tuples instead of ids? ultimately would return... tuples or nested entities
;; in order to avoid trimming large cardinality/many attrs, especially repeatedly
;; construct id to datoms map as we go along... -> would be better if we stored datoms in ave index
;; build up entities via list-form indexing like for eav index? works for path preds too
;; build up nested-entities as you go along with cardinality/many attrs (and cardinality/one attrs?)
;; tradeoff: the work of building up nested-entities is wasted if many ids subsequently discarded
;; TODO: which is the greater waste: re-filtering cardinality-many attr or building up nested-entities and later discarding them?
;; also constraining-paths aren't necessarily returning-paths, so building up constraining-paths may be wasted
;; if building up nested-entities along the way: only do it for returning-path cardinality-many to minimize potential waste
;; -> solution: specify returning-paths upfront
;; -> end up with partial entity the satisfies query, but then need to "enrich" with additional attrs/refs
;; can figure out the "enrich-pattern" by subtracting from full pattern what is available
;; what about "if person ever made more than 100K, return all his salaries"
;; - constraint applies to set of vals, but returning all vals for cardinality-many attr - HOW TO in datomic?
;; ENRICHING: pull returning-paths that haven't been built up yet, merge with existing nested-entity built up results

;; edge case: constraint-path: ref-type attr 'return only keyword ids';
;; return-path: follow those keyword ids and get more

;; returning-paths spec
;; get-entities returns a partially built up entity
;; todo: maybe only build up nested entity when cardinality-many attr and in return-path
#_(get-ids-spec db
                ;; constraint paths
                {:person/city #{"Berlin" "moscow"}
                 [:person/dl :dl/year] [> 2009]}
                ;; if return-paths not specified, don't know when to keep and when to discard vals via get-ids/get-entities
                ;; e.g. if :person/city not in return-paths, then only get-ids, not get-entities
                ;; return paths: how to specify? `path->join-pattern` is available
                [:person/city :person/name [:person/dl :dl/year] [:person/dl :dl/city-issued]]
                ;[:person/city {:person/dl [:dl/year :dl/city-issued]} :person/name]
                )


;; xsec fns for maintining a built up result map and interoping with id sets
(defn xect-maps
  [m1 m2]
  (if (< (count m2) (count m1))
    (recur m2 m1)
    (reduce-kv (fn [result id m]
                 (if (contains? m2 id)
                   (update result id merge (m2 id))
                   (dissoc result id)))
               m1 m2)))

(defn xsect-map-set
  [m ids]
  (if (map? ids)
    (recur ids m)
    (reduce-kv (fn [result id _]
                 (if (contains? ids id)
                   result
                   (dissoc result id)))
               m m)))

;; s1 and s2 can both be sets of ids or maps built up eav entities
(defn intersection2
  [s1 s2]
  (cond
    (and (set? s1) (set? s2))
    (intersection s1 s2)
    (and (map? s1) (map? s2))
    (xect-maps s1 s2)
    :else
    (xsect-map-set s1 s2)))

(defn merge-netsted
  [schema m1 m2]
  (reduce-kv (fn [result attr v]
               (if (ref-type? schema attr)

                 ;; not ref type
                 (assoc result attr v)))
             m1 m2))

(defn get-ids-multi
  "Returns ids that satisfy all of `attr-pred-pairs`. Supports any attr pred pair that `get-ids` can handle."
  [db & attr-pred-pairs]
  (reduce (fn [ids [attr pred]]
            (get-ids db attr pred ids))
          #{} (partition 2 attr-pred-pairs)))

;; [:person/license :dl/year] => {:person/license [:dl/year]}
;; [:person/license :issuing-dmv :dmv-city] => {:person/license {:issuing-dmv [:dmv-city]}}
(defn path->join-pattern
  [path]
  (let [[final-attr reverse-path] (reverse path)]
    (reduce (fn [r reverse-path]
              (assoc {} (first reverse-path) r))
            [final-attr] reverse-path)))

;; [[>= 30000] [< 30004]] => (fn [x] (and (>= x 30000) (< x 30004)))
(defn range-pred->fn-pred
  [range-pred]
  (if (vector? (first range-pred))
    (let [[[op1 val1] [op2 val2]] range-pred]
      (fn [x] (and (op1 x val1) (op2 x val2))))
    (let [[op val] range-pred]
      (fn [x] (op x val)))))

;; can handle multiple preds for one attr? -> pattern w/ dups, no prob for trim?
;; specify whether to do the trimming step? probably not
;; convenience: reuse of attr-pred-pairs for get-ids and trim; and pull?
(defn get-pull-trim
  "For every id that satisfies all of `path-pred-pairs`.
   Pulls all attrs/paths, joining on vector paths.
   Trims cardinality/many vals of each pulled (nested) entity to match preds from `path-pred-pairs`."
  [{:keys [db/schema] :as db} & path-pred-pairs]
  (let [ids (apply get-ids-multi db path-pred-pairs)
        pattern (->> path-pred-pairs
                     (map (fn [path pred]
                            (if (vector? path)
                              (path->join-pattern path)
                              path)))
                     distinct)
        pulled-entities (pull-many db pattern ids)
        ;; keep only cardinality/many attrs for trimming
        path-pred-pairs-trim
        (filter (fn [[path pred]]
                  (if (vector? path)
                    (cardinality-many? schema (last path))
                    (cardinality-many? schema path)))
                path-pred-pairs)
        ;; convert range preds to fn; remove true and false preds
        path-pred-pairs-trim
        (reduce (fn [result [path pred :as pair]]
                  (cond
                    (vector? pred)
                    (conj result [path (range-pred->fn-pred pred)])
                    (or (true? pred) (false? pred))
                    result
                    :default
                    (conj result pair)))
                [] path-pred-pairs-trim)]
    (map (fn [entity]
           (trim schema path-pred-pairs-trim entity))
         pulled-entities)))

;; instead of filtering by pred, it fetches vals
;; this is a more streamlined/limited pull-many
(defn get-entities-eav
  ([{:keys [db/schema db/eav db/ave] :as db} id-set]
   (map eav id-set))
  ([{:keys [db/schema db/eav db/ave] :as db} ks id-set]
   (->> (map eav id-set)
        (map #(select-keys % ks)))))

;; =========
;; Reverse Txs

;; interim transactions?
;;  can we specify when updates depend on previous successes (update chains) -> compare and swap or similar?
;;  add last1 to man1, add last2 to man1, last1 add fail -> reverse last1 add, last2 add still valid => independent
;;  add 20 to acct, add 30 to acct, add 20 fail -> reverse add 20, add 30 still valid => independent
;;  add last1 to man1, add man1/last1 to shoe1, add last1 fail -> shoe1 invalid -> reverse add man1/last1 shoe1
;;     specify tx-id dependency? keep coll of tx deps? = tx level granularity; [e a] level granularity possible?
;;  types of deps: 1) ref attrs pointing to failed entities
;;   (note difference between existence/non-existence of entity and change in entity data)
;;   2) [e a] depends on past [e a]?
;; only one optimistic update at a time?
;; can't make any changes (including local) until tx completes
;; everything else gets requed until previous tx succeeds or fails...
;; if it fails, cancel?
;; maybe more granular - like [e a] level add? FAILURE are detect at tx level, so maybe better to keep it at tx level
;; CREATE: tx dependency graph, if pre-req tx fails reverse dependent tx, if pre-req tx succeeds remove dependency
;; responses can arrive out of order! can txs arrive on backend out of order?
;; need to indicate that tx in flight by :remote/id :pending, but also :remote/tx :pending for existing entities?
;; in the mean time local changes are allowed
;; (meaning stuff that doesn't touch entities w/ remote/id attrs?, doesn't require mutation)
;; three categories: UI changes (selected items etc), local data changes <--> remote data changes

;; just keep db-before reference, only makes sense "more than one transaction ahead"
;; rely only on db-before ref allows for one timeline, can't have "indepedent" tx succeed
;; example: add man1, add man2; if man1 fails go back to db-before add man1,
;;   which means add man2 also fails (but it actually succeeded on backend! so front and back out of sync)
;; TODO: order of add/retract  matters
;; [nil -> sub,add = val; add,sub = nil] ok, [nil -> add,sub = nil; sub,add = val] fail

;; seems we have to generate reverse tx on the basis of db-before because cardinality/one attrs are "overwritten"
(defn reverse-tx-list
  [{:keys [db/eav db/schema] :as db-before} [op tx-e tx-a tx-v]]
  (case op
    :db/add
    (if (cardinality-many? schema tx-a)
      (if-some [db-before-v (get-in eav [tx-e tx-a])]
        [:db/retract tx-e tx-a tx-v]
        ;; can optimize by dissoc attr directly? (no old-v means no attr existed originally)
        [:db/retract tx-e tx-a tx-v])
      (if-some [db-before-v (get-in eav [tx-e tx-a])]
        [:db/add tx-e tx-a db-before-v]
        [:db/retract tx-e tx-a tx-v]))
    :db/retract
    (when-some [db-before-v (get-in eav [tx-e tx-a])]
      (if (cardinality-many? schema tx-a)
        (if (nil? tx-v)
          ;; no `v` was specified, so all values of `a` were retracted, add them back in - optimize?
          (map #(vector :db/add tx-e tx-a %) db-before-v)
          (when (contains? db-before-v tx-v)
            [:db/add tx-e tx-a tx-v]))
        (if (nil? tx-v)
          ;; nil `v` means `a` was retracted, add it back in
          [:db/add tx-e tx-a db-before-v]
          ;; only add tx-v back in only if db-before-v=tx-v meaning it was actually retracted
          ;; -> should this be captured in tx-data?
          ;; if nothing had changed tx-data would reflect that by not containing a retract datom
          (when (= db-before-v tx-v)
            [:db/add tx-e tx-a tx-v]))))))

(defn reverse-tx-map
  [{:keys [db/schema] :as db-before} {:keys [db/id db/op] :as tx-form}]
  (reduce-kv
    (fn [tx-data a v]
      (if (cardinality-many? schema a)
        (->> v
             (map #(reverse-tx-list db-before [(or op :db/add) id a %]))
             (reduce conj tx-data))
        (conj tx-data (reverse-tx-list db-before [(or op :db/add) id a v]))))
    [] (dissoc tx-form :db/id)))

(defn reverse-tx-form
  [db-before tx-form]
  (if (map? tx-form)
    (reverse-tx-map db-before tx-form)
    (reverse-tx-list db-before tx-form)))

(defn reverse-tx-data
  "Generates a ''reverse transacation''"
  [db-before tx-data]
  (let [tx-data
        (reduce
          (fn [tx-data tx-form]
            (if (map? tx-form)
              (reduce conj tx-data (reverse-tx-map db-before tx-form))
              (conj tx-data (reverse-tx-list db-before tx-form))))
          [] tx-data)
        tx-data (remove nil? tx-data)]
    (prn :reverse-tx-data tx-data)
    tx-data))