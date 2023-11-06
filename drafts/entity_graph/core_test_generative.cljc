(ns entity-graph.core-test-generative
  (:require
    #?(:clj [clojure.pprint :refer [pprint]]
       :cljs [cljs.pprint :refer [pprint]])
    [clojure.set :refer [intersection difference union rename-keys subset?]]
    #?(:clj  [clojure.test :as t :refer        [is are deftest testing]]
       :cljs [cljs.test    :as t :refer-macros [is are deftest testing]])
    [entity-graph.core :refer [create-db transact cardinality-many? unique? ref-type? index?
                               pull pull-many get-ids get-eav-tuples] :as eg]
    ;; Spec
    #?(:clj  [clojure.spec.alpha :as s]
       :cljs [cljs.spec.alpha :as s])
    ;; need to require clojure.test.check.generators for cljs generators work
    ;; https://clojure.atlassian.net/browse/CLJS-1792
    ;;https://stackoverflow.com/questions/57877004/how-to-fix-clojure-test-check-generators-never-required-when-exercising-a-func
    #?(:cljs [clojure.test.check.generators])
    #?(:clj [clojure.spec.gen.alpha :as gen]
       :cljs [cljs.spec.gen.alpha :as gen]))
  #?(:cljs (:require-macros [entity-graph.macros :refer [assert-fail? assert-fail-with-msg?]])
     :clj (:require [entity-graph.macros :refer [assert-fail? assert-fail-with-msg?]])))

;; =========
;; Schema Specs

(s/def ::person-first-name #{"John" "Mary" "Sam" "Jen"})

(s/def ::person-last-name #{"Smith" "Brown" "Doe" "Black"})

(def full-name-gen
  (gen/fmap
    (fn [[fn ln]]
      (str fn " " ln ))
    (gen/tuple
      (s/gen ::person-first-name)
      (s/gen ::person-last-name))))

(comment (gen/sample full-name-gen 5))

(s/def ::person-name (s/with-gen string? (fn [] full-name-gen)))

(s/def ::person-aliases (s/* string?))

(def non-empty-string-alphanumeric
  "Generator for non-empty alphanumeric strings"
  (gen/such-that #(not= "" %) (gen/string-alphanumeric)))

;; TODO: ensure emails are unique
(def email-gen
  "Generator for email addresses"
  (gen/fmap
    (fn [[name host ]]
      (str name "@" host ".com"))
    (gen/tuple
      non-empty-string-alphanumeric
      non-empty-string-alphanumeric)))

(s/def ::person-email (s/with-gen string? (fn [] email-gen)))

(s/def ::person-city #{"New York" "Moscow" "London" "Paris" "Munich" "Berlin" "San Francisco" "Houston"})

(s/def ::person-past-cities (s/* ::person-city))

(s/def ::person-salary (s/int-in 30000 300000))

(s/def ::person-past-salaries (s/* ::person-salary))

(s/def ::person
  (s/keys :req-un [::person-name ::person-email]
          :opt-un [::person-aliases ::person-city ::person-past-cities ::person-salary ::person-past-salaries]))

(comment
  (gen/generate (s/gen ::person)))

(s/def ::drivers-license-number
  ;uuid?
  (s/with-gen string? (fn [] non-empty-string-alphanumeric))
  )

(s/def ::drivers-license-state #{"NY" "NJ" "TX" "AR"})

(s/def ::drivers-license
  (s/keys :req-un [::drivers-license-number ::drivers-license-state]))

(comment
  (gen/generate (s/gen ::drivers-license)))

(defn to-db-attr
  [person]
  (rename-keys person {:person-name :person/name
                       :person-email :person/email
                       :person-aliases :person/aliases
                       :person-city :person/city
                       :person-past-cities :person/past-cities
                       :person-salary :person/salary
                       :person-past-salaries :person/past-salaries
                       :drivers-license-number :drivers-license/number
                       :drivers-license-state  :drivers-license/state}))

(def drivers-licenses (->> (gen/sample (s/gen ::drivers-license) 5) (map to-db-attr)))

(def persons (->> (gen/sample (s/gen ::person) 10) (map to-db-attr)))

;; TODO: can be redone with nested maps and with :db/unique email
;; TODO: ensure drivers license numbers are unique -> maybe this is why datomic wants them to have unique identifiers?
;; TODO: purposely test duplicate unique identifiers
(defn mk-drivers-licenses-tx-data
  [persons]
  (reduce (fn [tx-data [{:keys [drivers-license/number] :as dl} {:keys [db/id person/email] :as person}]]
            ;; using dl num as tempid, could also use email
            (-> tx-data
                (conj (assoc dl :db/id number))
                (conj {:db/id id :person/drivers-license number})))
          [] (map vector drivers-licenses persons)))

(defn mk-friends-tx-data
  "Makes tx-data for creating friend relationships between persons."
  [persons]
  (reduce (fn [tx-data {:keys [db/id] :as person}]
            (let [friends (random-sample 0.3 (keys (dissoc persons id)))]
              (conj tx-data {:db/id id :person/friends friends})))
          [] (vals persons)))

(defn mk-best-friend-tx-data
  [persons]
  (reduce
    (fn [tx-data id]
      (let [bestie-id (-> (dissoc persons id) vals rand-nth :db/id)]
        (conj tx-data {:db/id id :person/best-friend bestie-id})))
    [] (random-sample 0.6 (keys persons))))

;; =========
;; Map form / List form

;; if there are nil values for retractions, can produce either [:db/retract e a] or [:db/retract e a nil]
;; currently [:db/retract e a nil]
(defn map->list-form1
  "Converts `tx-form` from map form to list form. Returns a vector of list forms."
  [schema {:keys [db/id db/op] :as tx-form}]
  (let [op (or op :db/add)]
    (reduce-kv
      (fn [tx-data a v]
        (if (cardinality-many? schema a)
          (apply conj tx-data (map #(vector op id a %) v))
          (conj tx-data [op id a v])))
      [] (dissoc tx-form :db/id :db/op))))

;; only used for testing
(defn map->list-form
  [schema tx-data]
  (mapcat #(map->list-form1 schema %) tx-data))

;; =========
;; Generative tests

;; =========
;; AVE net ops

(defn ve-tuple [[op e a v]] [v e])
(defn av-tuple [[op e a v]] [a v])
(defn eav-tuple [[op e a v]] [e a v])

;; remember `tx-data` is already grouped by id and by attr
(defn one-add
  [tx-data tuple-fn]
  (let [[result last-added]
        (reduce (fn [[result last-tuple] [op e a v :as tx-form]]
                  (let [tuple (tuple-fn tx-form)]
                    (case op
                      ;; replace previous assertion
                      :db/add [(-> result (disj last-tuple) (conj tuple)) tuple]
                      :db/retract [(disj result tuple) last-tuple])))
                [#{} :last-tuple] tx-data)]
    result))

(defn many-add
  [tx-data tuple-fn]
  (reduce (fn [r [op e a v :as tx-form]]
            (let [tuple (tuple-fn tx-form)]
              (case op
                :db/add (conj r tuple)
                :db/retract (disj r tuple))))
          #{} tx-data))

;ave
(defn net-additions
  [schema [id tx-data] tuple-fn]
  (let [by-attr (group-by (fn [[op e a v]] a) tx-data)]
    (reduce-kv (fn [r attr data]
                 (if (cardinality-many? schema attr)
                   (update r attr #(reduce conj % (many-add data tuple-fn)))
                   (update r attr #(reduce conj % (one-add data tuple-fn)))))
               {} by-attr)))

(defn one-retract
  [tx-data tuple-fn]
  (reduce (fn [r [op e a v :as tx-form]]
            (let [tuple (tuple-fn tx-form)]
              (case op
                :db/add (disj r tuple)
                :db/retract (conj r tuple))))
          #{} tx-data))

(defn many-retract
  [tx-data tuple-fn]
  (reduce (fn [r [op e a v :as tx-form]]
            (let [tuple (tuple-fn tx-form)]
              (case op
                :db/add (disj r tuple)
                :db/retract (conj r tuple))))
          #{} tx-data))

(defn net-retractions
  [schema [id tx-data] tuple-fn]
  (let [by-attr (group-by (fn [[op e a v]] a) tx-data)]
    (reduce-kv (fn [r attr tx-data]
                 (if (cardinality-many? schema attr)
                   (update r attr #(reduce conj % (many-retract tx-data tuple-fn)))
                   (update r attr #(reduce conj % (one-retract tx-data tuple-fn)))))
               {} by-attr)))

(defn net-ave-updates
  "Returns sets of [v e] pairs that should update AVE index based on `tx-data`.
   Additions are under top level :db/add key, retractions under :db/retract key.
   The sets of [v e] pairs are further grouped by attribute."
  [schema tx-data]
  (let [list-form (map->list-form schema tx-data)
        by-id (group-by second list-form)
        additions (map #(net-additions schema % ve-tuple) by-id)
        retractions (map #(net-retractions schema % ve-tuple) by-id)]
    {:db/add (apply merge-with concat additions)
     :db/retract (apply merge-with concat retractions)}))

(defn ave-attr->ve-set
  "Returns `ave` index under `attr` key represented as a set of [v e] tuples."
  [ave schema attr]
  (cond
    (unique? schema attr)
    (set (get ave attr))
    (or (ref-type? schema attr) (index? schema attr))
    (reduce-kv (fn [ve-set v e-set]
                 (reduce #(conj %1 (vector v %2)) ve-set e-set))
               #{} (get ave attr))
    :default
    nil))

(defn ave->ave-set
  [ave schema]
  (reduce (fn [ave-set attr]
            (let [ve-set (ave-attr->ve-set ave schema attr)
                  ave-attr-set (map (fn [[v e]] [attr v e]) ve-set)]
              (reduce conj ave-set ave-attr-set)))
          #{} (keys ave)))

(defn ave->eav-set
  [ave schema]
  (reduce (fn [ave-set attr]
            (let [ve-set (ave-attr->ve-set ave schema attr)
                  eav-attr-set (map (fn [[v e]] [e attr v]) ve-set)]
              (reduce conj ave-set eav-attr-set)))
          #{} (keys ave)))

(defn process-ve-map
  [ve-map schema attr]
  (reduce-kv (fn [r v e]
               (cond
                 (unique? schema attr)
                 (conj r [e attr v])
                 (or (ref-type? schema attr) (index? schema attr))
                 (reduce #(conj %1 [%2 attr v]) r e)
                 :default
                 r))
             [] ve-map))

(defn ave->eav-set2
  [ave schema]
  (reduce-kv (fn [eav-set attr ve-map]
               (reduce conj eav-set (process-ve-map ve-map schema attr)))
             #{} ave))

(defn ave-updates-attr
  "Returns `ave` index under `attr` key represented as a set of [v e] pairs with `ave-updates` applied."
  [ave-before schema net-updates attr]
  (let [net-add (-> net-updates :db/add attr set)
        net-retract (-> net-updates :db/retract attr set)
        ve-set (ave-attr->ve-set ave-before schema attr)]
    ;; add/retract order doesn't matter
    (-> ve-set (union net-add) (difference net-retract))))

;; =========
;; EAV net ops

;; `last-tuples` keeps track of the last assertion for cardinality/one attrs, so they can be removed when "overwritten"
(defn eav-add1
  [[tuple-set last-tuples] schema [op e a v :as tx-form]]
  (let [tuple [e a v]]
    (if (cardinality-many? schema a)
      (case op
        :db/add [(conj tuple-set tuple) last-tuples]
        :db/retract [(disj tuple-set tuple) last-tuples])
      (case op
        ;; replace previous assertion
        :db/add [(-> tuple-set (disj (last-tuples [e a])) (conj tuple)) (assoc last-tuples [e a] tuple)]
        :db/retract [(disj tuple-set tuple) last-tuples]))))

(defn eav-retract1
  [tuple-set schema [op e a v :as tx-form]]
  (let [tuple [e a v]]
    (if (cardinality-many? schema a)
      (case op
        :db/add (disj tuple-set tuple)
        :db/retract (conj tuple-set tuple)))
    (case op
      :db/add (disj tuple-set tuple)
      :db/retract (conj tuple-set tuple))))

(defn net-additions-eav
  [schema tx-data-list]
  (let [[additions last-tuples] (reduce #(eav-add1 %1 schema %2) [#{} {}] tx-data-list)]
    additions))

(defn net-retractions-eav
  [schema list-form]
  (reduce #(eav-retract1 %1 schema %2) #{} list-form))

(defn net-eav-updates
  [schema tx-data]
  (let [list-form (map->list-form schema tx-data)]
    {:db/add (net-additions-eav schema list-form)
     :db/retract (net-retractions-eav schema list-form)}))

(defn eav->eav-set
  [eav schema]
  "Returns `eav` index represented as a set of [e a v] tuples."
  ;; turn into list form removing db/id
  (set (map rest (map->list-form schema (vals eav)))))

(defn expected-eav
  [eav-before schema tx-data]
  (let [{:keys [db/add db/retract]} (net-eav-updates schema tx-data)]
    ;; add/retract order doesn't matter
    (-> (eav->eav-set eav-before schema) (union add) (difference retract))))

;; todo: maybe `net-eav-updates` can return a seq instead of set
(defn expected-ave
  [ave-before schema tx-data]
  (let [{:keys [db/add db/retract]} (net-eav-updates schema tx-data)
        index-in-ave? (fn [[e a v]] (or (unique? schema a) (ref-type? schema a) (index? schema a)))
        add (set (filter index-in-ave? add))
        retract (set (filter index-in-ave? retract))]
    (-> (ave->eav-set ave-before schema) (union add) (difference retract))))

;;;;;;;

;; retractions (transactx db (map #(assoc % :db/op :db/retract) persons))
(deftest test1
  ;; Add some persons
  (let [{:keys [tx-data db-before db-after tempids] :as r} (transact db-empty persons)
        {eav-before :eav ave-before :ave} db-before
        {eav-after :eav ave-after :ave} db-after
        net-updates-ave (net-ave-updates schema tx-data)]

    (is (inc (:db/tx-count db-before)) (:db/tx-count db-after))

    ;; EAV/AVE general
    (is (= (expected-eav eav-before schema tx-data) (eav->eav-set eav-after schema)))
    (is (subset? (ave->eav-set ave-after schema) (eav->eav-set eav-after schema)))
    (is (= (expected-ave ave-before schema tx-data) (ave->eav-set ave-after schema)))
    (is (= (expected-ave ave-before schema tx-data) (ave->eav-set2 ave-after schema)))
    ;; todo generalize to look for all keys in tx-data
    (is (= (set (keys ave-after)) #{:person/email :person/salary :person/past-salaries
                                    :person/city :person/past-cities :person/drivers-license
                                    :person/best-friend :person/friends}))
    ;; AVE by attribute...

    ;; :person/email - :db.unique/identity
    (is (= (ave-updates-attr ave-before schema net-updates-ave :person/email)
           (ave-attr->ve-set ave-after schema :person/email)))

    ;; :person/city - :db.index/unsorted, :db.cardinality/one
    ;; make sure the non-unique ave indexes are sets
    (is (empty? (remove set? (-> ave-after :person/city vals))))
    (is (= (ave-updates-attr ave-before schema net-updates-ave :person/city)
           (ave-attr->ve-set ave-after schema :person/city)))

    ;; :person/past-cities - :db.index/unsorted, :db.cardinality/many
    (is (= (ave-updates-attr ave-before schema net-updates-ave :person/past-cities)
           (ave-attr->ve-set ave-after schema :person/past-cities)))

    ;; :person/salary - :db.index/sorted, :db.cardinality/one
    ;; ensure the map is sorted
    (is (sorted? (:person/salary ave-after)))
    ;; ensure all vals are sets
    (is (empty? (remove set? (-> ave-after :person/salary vals))))
    (is (= (ave-updates-attr ave-before schema net-updates-ave :person/salary)
           (ave-attr->ve-set ave-after schema :person/salary)))

    ;; :person/past-salaries - :db.index/sorted, :db.cardinality/many
    (is (sorted? (:person/past-salaries ave-after)))
    (is (= (ave-updates-attr ave-before schema net-updates-ave :person/past-salaries)
           (ave-attr->ve-set ave-after schema :person/past-salaries)))

    ;; Add best friend references
    (let [
          ;persons (->> (ave-after :person/email) (vals) (select-keys eav-after))
          persons (select-keys eav-after (eg/ids-by-attr-unique ave-after :person/email))
          best-friend-tx (mk-best-friend-tx-data persons)
          {:keys [tx-data db-before db-after tempids] :as r} (transact db-after best-friend-tx)
          {eav-before :eav ave-before :ave} db-before
          {eav-after :eav ave-after :ave} db-after]
      (is (= (expected-eav eav-before schema tx-data) (eav->eav-set eav-after schema)))
      (is (subset? (ave->eav-set ave-after schema) (eav->eav-set eav-after schema)))
      (is (= (expected-ave ave-before schema tx-data) (ave->eav-set ave-after schema)))
      (is (= (expected-ave ave-before schema tx-data) (ave->eav-set2 ave-after schema)))
      ;; TODO: id is arbitrary
      (pprint (eav-after 8))
      (pprint (:person/best-friend ave-after))
      (pprint (pull db-after [:person/best-friend] 8))
      (pprint (pull db-after [:person/_best-friend] 8))
      (pprint (pull db-after [{:person/_best-friend [:person/name]}] 8))
      (pprint tx-data)

      ;; eav check
      (is (= (set (pull-many db-after [:db/id :person/best-friend] (map :db/id best-friend-tx)))
             (set best-friend-tx)))

      ;; not very generic
      #_(is (= (->> (map :db/id best-friend-tx)
                    (map eav-after)
                    (map select-keys [:db/id :person/best-friend])
                    (set))
               (set best-friend-tx)))
      )
    ;; Add friends references
    (let [
          persons (select-keys eav-after (vals (ave-after :person/email)))
          ;persons (select-keys eav-after (ids-by-attr-ave db-after :person/email))
          friends-tx (mk-friends-tx-data persons)
          {:keys [tx-data db-before db-after tempids] :as r} (transact db-after friends-tx)
          {eav-before :eav ave-before :ave} db-before
          {eav-after :eav ave-after :ave} db-after]
      (is (= (expected-eav eav-before schema tx-data) (eav->eav-set eav-after schema)))
      (is (subset? (ave->eav-set ave-after schema) (eav->eav-set eav-after schema)))
      (is (= (expected-ave ave-before schema tx-data) (ave->eav-set ave-after schema)))
      (is (= (expected-ave ave-before schema tx-data) (ave->eav-set2 ave-after schema)))
      ;; TODO: testing ranges here
      (println ">>>> AVE SALARIES: " (:person/salary ave-after))
      (println ">>> RESULT: " (get-ids db-after :person/salary [[>= 30000] [< 30004]]))
      (pprint (eav-after 7))
      (pprint (:person/friends ave-after))
      (pprint (pull db-after [:person/friends] 7))
      (pprint (pull db-after [:person/_friends] 7))
      (pprint (pull db-after [{:person/_friends [:person/name]}] 7))
      (println ">>> NON 1")
      (pprint (pull db-after [:person/hui] 7))
      (println ">>> NON 2")
      (pprint (pull db-after [:person/name {:person/friends [:person/hui]}] 7))
      (println ">>> NON 3")
      (pprint (pull db-after [{:person/_friends [:person/hui]}] 7))

      ;; "chaining"
      (println "111111")
      (let [many
            (eg/get-ids-multi db-after
                              :person/past-cities #{"Moscow" "Berlin"}
                              :person/salary #(> % 30000))
            one
            (->> (get-ids db-after :person/past-cities #{"Moscow" "Berlin"})
                 (get-ids db-after :person/salary #(> % 30000)))]
        (is (= many one)))
      (pprint
        (->> (get-ids db-after :person/past-cities #{"Moscow" "Berlin"})

             ;; ids of ppl who HAVE friends
             ;(get-ids db-after :person/friends true)
             ;; ids of ppl who are friends with intersect-ids
             ;; todo: works, but loses grouping by 'original' :db/id of person entity
             ;; could keep the grouping by doing each 'original' person id separately => use pull-many!
             (get-ids db-after :person/friends)

             ;(get-ids db-after :person/salary #(> % 30000))
             (union (get-ids db-after :person/name #{"John"}))
             ;(map eav-after)
             ;(map #(select-keys % [:db/id :person/name :person/salary :person/friends :person/past-cities]))
             ;; alternative to get entityes
             (eg/get-entities-eav db-after)
             ;; or select specific keys
             ;(eg/get-entities-eav db-after [:db/id :person/name :person/salary :person/friends :person/past-cities])
             ))

      ;(println "222222")
      (pprint
        (map eav-after
             (->> (get-ids db-after :person/past-cities #{"Moscow" "Berlin"})
                  (get-ids db-after :person/salary #(> % 30000))
                  (get-ids db-after :person/friends)
                  )))

      #_(do
          (println "=== EAV TUPLES ===")
          (pprint
            (get-eav-tuples db-after :person/past-cities #{"Moscow" "Berlin"}))
          (pprint
            (get-eav-tuples db-after :person/salary #(> % 30000))))

      #_(do
          (println "=== AVE INVERT ===")
          (pprint (eg/invert-ave-a-non-unique (get-in db-after [:ave :person/past-cities]))))

      (is (= (set (pull-many db-after [:db/id :person/friends] (map :db/id friends-tx)))
             (set tx-data))))

    #_(let [persons (map eav-after (eg/ids-by-attr-unique db-after :person/email))
            drivers-licenses-tx (mk-drivers-licenses-tx-data (vals persons))
            ;persons (eav-by-attr db-after :person/email)
            ;drivers-licenses-tx (mk-drivers-licenses-tx-data (vals persons))
            {:keys [tx-data db-before db-after tempids] :as r} (transact db-after drivers-licenses-tx)
            {eav-before :eav ave-before :ave} db-before
            {eav-after :eav ave-after :ave} db-after
            ;; TODO: relying on tx-data rather than original tx with tempids
            ;; had to remove nils since not every person was "issued" a drivers license
            expected-drivers-licenses-freqs (frequencies (remove nil? (map :person/drivers-license tx-data)))
            ;; for eav:
            persons-tx-data (filter #(contains? % :person/drivers-license) tx-data)
            drivers-license-tx-data (filter #(contains? % :drivers-license/number) tx-data)]
        (is (= (expected-eav eav-before schema tx-data) (eav->eav-set eav-after schema)))
        (is (subset? (ave->eav-set ave-after schema) (eav->eav-set eav-after schema)))
        (is (= (expected-ave ave-before schema tx-data) (ave->eav-set ave-after schema)))
        (is (= (expected-ave ave-before schema tx-data) (ave->eav-set2 ave-after schema)))
        ;; TODO: id is arbitrary
        ;(pprint eav)
        (pprint (eav-after 14))
        (pprint (:person/drivers-license ave-after))
        (pprint (pull db-after [:person/drivers-license] 7))
        (pprint (pull db-after [:person/_drivers-license] 14))
        (pprint (pull db-after [{:person/_drivers-license [:person/name :db/id]}] 14))

        ;; eav check
        (is (= (set (pull-many db-after
                               [:db/id :drivers-license/number :drivers-license/state]
                               (map :db/id drivers-license-tx-data)))
               (set drivers-license-tx-data)))

        ;; TODO: returning :db/id for refs breaks this test
        (is (= (set (pull-many db-after
                               [:db/id :person/drivers-license]
                               (map :db/id persons-tx-data)))
               (set persons-tx-data))))))

(deftest test-queries
  (let [{:keys [tx-data db-before db-after tempids] :as r} (transact db-empty persons)
        {eav-before :eav ave-before :ave} db-before
        {eav-after :eav ave-after :ave} db-after]
    (pprint (:eav db-after))
    (println "=================================")
    ;(pprint (eg/get-tuples db-after :person/past-cities #{"Moscow" "Berlin"}))
    (pprint (eg/get-ids2 db-after :person/past-cities #{"Moscow" "Berlin"}))
    #_(pprint (map eav-after (intersection (get-ids db-after :person/past-cities #{"Moscow" "Berlin"})
                                           (get-ids db-after :person/salary #(> % 30000))
                                           ;(ids-by-attr db-after :person/best-friend)
                                           )))

    #_(pprint (map eav-after (union (get-ids db-after :person/past-cities #{"Moscow" "Berlin"})
                                    (get-ids db-after :person/salary #(> % 30000)))))

    ))

;; =========
;; Low Level Specs

(s/def ::kw-id #{:kw-id1 :kw-id2 :kw-id3 :kw-id4})

(s/def ::string-id #{"string-id1" "string-id2" "string-id3" "string-id4"})

(s/def ::proper-id (s/or :pos-int pos-int? :kw ::kw-id))

(s/def ::tempid (s/or :neg-int neg-int? :string ::string-id))

(s/def ::attribute #{:person/name :person/aliases})

(s/def ::value (s/or :number number? :string string?))

(s/def ::lookup-ref (s/cat :attribute ::attribute :value ::value))

(s/def ::db-id (s/or :proper-id ::proper-id
                     :tempid ::tempid
                     :lookup-ref ::lookup-ref))

;; TODO nested map form
(s/def ::map-form-tx
  (s/keys :req [] :opt-un [::db-id]))

(s/def ::op #{:db/add :db/retract})

(s/def ::list-form-tx
  (s/or :with-value (s/cat :op ::op :id ::db-id :attribute ::attribute :value ::value)
        :without-value (s/cat :op #{:db/retract} :id ::db-id :attribute ::attribute)))

(s/def ::tx-form (s/or :map-form ::map-form-tx :list-form ::list-form-tx))

(s/def ::tx-data (s/* ::tx-form))

(comment
  (gen/generate (s/gen ::db-id))
  (gen/generate (s/gen ::lookup-ref))
  (gen/generate (s/gen ::map-form-tx))
  (gen/generate (s/gen ::list-form-tx))
  (gen/generate (s/gen ::tx-form))
  (gen/generate (s/gen ::tx-data))
  )

;; =========
;; Create DB Test

(deftest test-create-db
  (create-db test-schema))

;; =========
;; Transact Test

;; To Test
;; add/retract
;; different :db/id forms: proper-id, :db/id not specified, tempid, lookup-ref
;; mix of map-forms and list-forms

;; ensure there are no uniqueness violations after txs
;; ensure indexes are properly updated
;; ensure assertions are fired when there are violations (e.g. "invalid op"
;; ensure tempids resolve properly and entities with same tempid in tx have the same db/id
;; ensure blank :db/id in tx get a db/id

;; nested maps

(deftest test-transactx
  (let [tx-d [{:person/name "Ivan"}]
        {:keys [tx-data db-before db-after tempids] :as r} (transact db-empty tx-d)]
    (is (= (set (keys r))
           #{:db-before :db-after :tx-data :tempids}))
    (is (contains? (first tx-data) :db/id))))

;; (transactx db [{:db/id -1 :person/name "Ivan"} {:db/id -1 :person/name "Vasil"}])
(deftest test-replacing-in-ave
  ;; add initial value
  (let [tx-d [{:person/name "Ivan"}]
        {:keys [tx-data db-before db-after tempids] :as r} (transact db-empty tx-d)
        id (-> tx-data first :db/id)]
    (is (= (-> db-after :ave :person/name (get "Ivan")) #{id}))
    ;; replace value
    (let [tx-d [{:db/id id :person/name "Vasil"}]
          {:keys [tx-data db-before db-after tempids] :as r} (transact db-after tx-d)]
      (is (= 1 (count (-> db-after :ave :person/name))))
      (is (= (-> db-after :ave :person/name (get "Vasil")) #{id})))
    ;; replace value list
    (let [tx-d [[:db/add id :person/name "Vasil"]]
          {:keys [tx-data db-before db-after tempids] :as r} (transact db-after tx-d)]
      (is (= 1 (count (-> db-after :ave :person/name))))
      (is (= (-> db-after :ave :person/name (get "Vasil")) #{id}))))

  ;; SAME for UNIQUE
  ;; add initial value
  (let [tx-d [{:person/email "a@a.com"}]
        {:keys [tx-data db-before db-after tempids] :as r} (transact db-empty tx-d)
        id (-> tx-data first :db/id)]
    (is (= (-> db-after :ave :person/email (get "a@a.com")) id))
    ;; replace value
    (let [tx-d [{:db/id id :person/email "b@b.com"}]
          {:keys [tx-data db-before db-after tempids] :as r} (transact db-after tx-d)]
      (is (= 1 (count (-> db-after :ave :person/email))))
      (is (= (-> db-after :ave :person/email (get "b@b.com")) id)))
    ;; replace value list
    (let [tx-d [[:db/add id :person/email "b@b.com"]]
          {:keys [tx-data db-before db-after tempids] :as r} (transact db-after tx-d)]
      (is (= 1 (count (-> db-after :ave :person/email))))
      (is (= (-> db-after :ave :person/email (get "b@b.com")) id)))))

(deftest test-retract-list
  (let [tx-d [{:person/name "Ivan" :person/aliases #{"Goga" "Gosha"}}]
        {:keys [tx-data db-before db-after tempids] :as r} (transact db-empty tx-d)
        id (-> tx-data first :db/id)]
    (transact db-after [[:db/retract id :person/aliases ["Goga" "Gosha"]]])
    (is (= (set (keys r))
           #{:db-before :db-after :tx-data :tempids}))
    (is (contains? (first tx-data) :db/id))))

#_(transact db-empty [{:db/id "ivan" :person/name "ivan"}
                      {:person/name "vasil" :person/friends ["ivan"]}])

;; Test lookup ref in value position for :db.type/ref attribute
#_(let [{:keys [tx-data db-before db-after tempids]} (transact db-empty [{:db/id "ivan" :person/name "ivan"
                                                                          :person/email "a@a.com"}])]
    (transact db-after [{:person/name "vasil" :person/friends [[:person/email "a@a.com"]]}]))
