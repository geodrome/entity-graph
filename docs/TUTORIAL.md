# EntityGraph Tutorial

## Basic Functionality
* [Jump forward to Advanced Features](#advanced-features)

In this tutorial concepts and features of EntityGraph will be introduced step by step. 

**It's best to follow this tutorial by executing the forms in your own REPL. Make sure you have EntityGraph as a dependency in your project and require it in your namespace. See the **[Dependency Infromation in Readme](../README.md#dependency-information)** for up-to-date dependency information.**

```clojure
(require '[entity-graph.core :refer :all])
(set! *print-namespace-maps* false)
```

## Create DB

The first step is to create a database (with an empty schema for now).

**NOTE: Non-essential REPL return values will be omitted throughout this tutorial.**

```clojure
(def db (create-db {}))

db
=>
{:db/schema {:db/sorted-attributes {},
             :db.unique/identity #{},
             :db/index #{},
             :db/unique #{},
             :db/isRef #{},
             :db.index/avl-map #{},
             :db/isComponent #{},
             :db.unique/value #{},
             :db.index/hash-map #{},
             :db/ave-single-e #{},
             :db/ave-eset #{},
             :db.cardinality/many #{}},
 :db/next-id 1,
 :db/tx-count 0,
 :db/eav {},
 :db/ave {}}
```

The function `create-db` takes a schema definition and returns a database, which is a clojure map with the following keys:
* `:db/schema` is an encoded schema, primarily for internal use.
* `:db/next-id` is the integer id that will be assigned to the next new entity, primarily for internal use.
* `:db/tx-count` is incremented each time the database is updated via `transact`. May be useful when keeping references to past values of the database.
* `:db/eav` is the EAV index, a directly accessible map.
* `:db/ave` is the AVE index, a directly accessible map.

### Default Attribute Properties

We can use an attribute without declaring it in the schema. Any attribute that's not declared will in effect have the following default definition, the meaning of which will become clearer as we advance through the tutorial:

```clojure
{:default-attribute
 {:db/cardinality :db.cardinality/one
  :db/index       false
  :db/unique      false
  ;db/valueType not declared, so any value is fine
  :db/isComponent false
  :db/sort        false}}
```

When we want to endow and attribute with specific properties we need to declare it in the schema. We'll see this later in the tutorial.

## Assertions

All database updates are performed with the `transact` function. For instance:

```clojure
(transact db [[:db/add "rich" :person/name "Rich Hickey"]])
```

The function `transact` takes a db value and transaction data. Transaction data is a vector of transaction forms. In this case the transaction data contains just a single vector:

```clojure
[:db/add "rich" :person/name "Rich Hickey"]
```

Transaction forms have the shape `[operation & args]`, so the vector above can be understood as follows:

* `:db/add` is the operation, assertion in this case.
* `"rich"` is a tempid. Tempids tell the database to create a new entity. Tempids are assigned corresponding entity ids by the database, which serve as internal database keys.
* `:person/name` is the attribute that we wish to add. 
* `"Rich Hickey"` is the value we wish to add for this attribute.

In summary, we are creating a new entity which will get a new entity id (internal database key), and this entity has the attribute `:person/name` with the value `"Rich Hickey"`. 

The function `transact` returns a map with the following keys:
* `:db-before` - the value of the database passed to `transact`.
* `:db-after` - the updated value of the database after the transaction.
* `:tx-data` - processed transaction data grouped by the type of operation and further by the type of id. This may come in handy during debugging.
* `:tempids` - a map of tempids supplied in transaction data and their corresponding entity ids (assigned by db).

Run this at the REPL:

```clojure
(def tx-result (transact db [[:db/add "rich" :person/name "Rich Hickey"]]))

(get-in tx-result [:db-after :db/eav])
=> {1 {:db/id 1, :person/name "Rich Hickey"}}
```

**NOTE: Entity ids may be different when you run the forms in your REPL.**

A new entity has been added to the EAV index, and it's been assigned the entity id `1`. We can confirm this by checking the tempids map:

```clojure
(get tx-result :tempids)
=> {"rich" 1}
```

We can also examine the new database value in its entirety:

```clojure
(get tx-result :db-after)
=>
{:db/schema {:db/sorted-attributes {},
             :db.unique/identity #{},
             :db/index #{},
             :db/unique #{},
             :db/isRef #{},
             :db.index/avl-map #{},
             :db/isComponent #{},
             :db.unique/value #{},
             :db.index/hash-map #{},
             :db/ave-single-e #{},
             :db/ave-eset #{},
             :db.cardinality/many #{}},
 :db/next-id 2,
 :db/tx-count 1,
 :db/eav {1 {:db/id 1, :person/name "Rich Hickey"}},
 :db/ave {}}
```

* The EAV index under the key `:db/eav` has the data that we just added.
* The AVE index under the key `:db/ave` is empty because the attribute `:person/name` is not indexed in the AVE index.

Let us now read data back from the database. Our primary method for doing so is the `pull` function which takes three arguments: 
1. The database value. 
2. The pull pattern, which specifies which attributes should be pulled and where joins should occur.
3. The entity id to start with.

Let's try `pull`:

```clojure
(pull (:db-after tx-result) [:person/name] 1)
=> {:person/name "Rich Hickey"}
```

We navigated to entity id `1` (Rich Hickey) and pulled the pattern `[:person/name]`.

To pull an entire entity, use the wildcard pattern `'[*]`:

```clojure
(pull (:db-after tx-result) '[*] 1)
=> {:db/id 1, :person/name "Rich Hickey"}
```

This returns the entire entity as it appears in the EAV index. Note that we had to quote the pattern `'[*]` because it contains a symbol (*).

### ***

Next, we'll add another attribute value for Rich Hickey:

```clojure
(def tx2-result
  (transact (:db-after tx-result) 
            [[:db/add 1 :person/favorite-database "MySQL"]]))
```

* **We passed `(:db-after tx-result)` as first argument to `transact` because we want to update the database value that was produced by the previous transaction.**
* Our transaction data is just the one vector `[:db/add 1 :person/favorite-database "MySQL"]`
* We are adding the value `"MySQL"` under attribute `:person/favorite-database`.
* We use the entity id `1` because we want this attribute/value associated with the existing Rich Hickey entity.

**Note also that we are defining `tx2-result`. This allows us to keep historical values of the database. But we also could have redefed `tx-result`.**

If we examine the updated EAV index we can see that `[:person/favorite-database "MySQL"]` has been added to the Rich Hickey entity:

```clojure
(get-in tx2-result [:db-after :db/eav])
=> {1 {:db/id 1, :person/name "Rich Hickey", :person/favorite-database "MySQL"}}
```

Let's also try pulling:

```clojure
(pull (:db-after tx2-result) '[*] 1)
=> {:db/id 1, :person/name "Rich Hickey", :person/favorite-database "MySQL"}
```

### ***

Let's add another entity to the database, but now using the map transaction form `{:person/name "Nina Simone"}`. Map transaction forms are always treated as assertions:

```clojure
(def tx3-result 
  (transact (:db-after tx2-result) [{:person/name "Nina Simone"}]))

(get-in tx3-result [:db-after :db/eav])
=>
{1 {:db/id 1, :person/name "Rich Hickey", :person/favorite-database "MySQL"}, 
 2 {:person/name "Nina Simone", :db/id 2}}
```

A new entity with the entity id `2` was created. No id is required in map transaction forms to create a new entity, but an optional tempid may be supplied under the special `:db/id` key. To associate the attribute values with an existing database entity an existing entity id may be supplied under the `:db/id` key.

### ***

Let us create a new entity with a map transaction form, but this time we'll supply a tempid:

```clojure
(def tx4-result
  (transact (:db-after tx3-result)
            [{:db/id "jimi" 
              :person/name "Jimi Hendrix" 
              :person/best-instrument "Electric Guitar"}]))

(get-in tx4-result [:db-after :db/eav])
=>
{1 {:db/id 1, :person/name "Rich Hickey", :person/favorite-database "MySQL"},
 2 {:db/id 2, :person/name "Nina Simone"},
 3 {:db/id 3, :person/name "Jimi Hendrix", :person/best-instrument "Electric Guitar"}}
```

The Jimi Hendrix entity has been assigned the entity id `3` (might be different for you). Let's check the tempids map:

```clojure
(get tx4-result :tempids)
=> {"jimi" 3}
```

**With tempids we create new entities. We'll see later that tempids can be used to link to these newly created entity within a transaction.**

NOTE: as a further convenience, the attribute keys in the map may be either keywords or strings:

```clojure
(def tx4b-result
  (transact (:db-after tx3-result)
            [{"db/id" "jimi" 
              "person/name" "Jimi Hendrix" 
              "person/best-instrument" "Electric Guitar"}]))

(get-in tx4b-result [:db-after :db/eav])
 =>
 {1 {:db/id 1, :person/name "Rich Hickey", :person/favorite-database "MySQL"},
  2 {:db/id 2, :person/name "Nina Simone"},
  3 {:db/id 3, :person/name "Jimi Hendrix", :person/best-instrument "Electric Guitar"}}
```

### ***

**ALERT! I just received news that we got Rich Hickey's favorite database wrong! He actually prefers Datomic to MySQL.**

Let's bring our database inline with the new information. We'll update Rich Hickey's favorite database to Datomic. 

First using a list transaction form:

```clojure
(def tx5-result
  (transact (:db-after tx4-result) 
            [[:db/add 1 :person/favorite-database "Datomic"]]))

(pull (:db-after tx5-result) '[:person/favorite-database] 1)
=> {:person/favorite-database "Datomic"}
```

And now again using a map transaction form. Note that we're again transacting against the database from `tx4-result`:

```clojure
(def tx5b-result
  (transact (:db-after tx4-result) [{:db/id 1 :person/favorite-database "Datomic"}]))

(pull (:db-after tx5b-result) '[:person/favorite-database] 1)
=> {:person/favorite-database "Datomic"}
```

## Retractions

So far we've been adding and updating data. Now let us address retractions. Let's have a look at the present value of the database, specifically the EAV index:

```clojure
(get-in tx5-result [:db-after :db/eav])
=>
{1 {:db/id 1, :person/name "Rich Hickey", :person/favorite-database "Datomic"},
 2 {:db/id 2, :person/name "Nina Simone"},
 3 {:db/id 3, :person/name "Jimi Hendrix", :person/best-instrument "Electric Guitar"}}
```

Now let's retract `[:person/best-instrument "Electric Guitar"]` from the Jimi Hendrix entity. Retractions are always in list form:

```clojure
(def tx6-result
  (transact (:db-after tx5-result) 
            [[:db/retract 3 :person/best-instrument "Electric Guitar"]]))

(pull (:db-after tx6-result) '[*] 3)
=> {:db/id 3, :person/name "Jimi Hendrix"}
```

The transaction form `[:db/retract 3 :person/best-instrument "Electric Guitar"]` retracts the value `"Electric Guitar"` for the attribute `:person/best-instrument` for entity id `3` (Jimi Hendrix) and we're left with the entity: `{:db/id 3, :person/name "Jimi Hendrix"}`.

Had we specified `"Drums"` as the value to retract, the existing value would remain in the database:

```clojure
(def tx6b-result
  (transact (:db-after tx5-result) 
            [[:db/retract 3 :person/best-instrument "Drums"]]))

(pull (:db-after tx6b-result) '[*] 3)
=> {:db/id 3, :person/name "Jimi Hendrix", :person/best-instrument "Electric Guitar"}
```

We can omit the existing value, if we just want to remove whatever value for a given attribute is currently in the database:

```clojure
(def tx6c-result
  (transact (:db-after tx5-result) 
            [[:db/retract 3 :person/best-instrument]]))

(pull (:db-after tx6c-result) '[*] 3)
=> {:db/id 3, :person/name "Jimi Hendrix"}
```

If all attribute values are removed for an existing entity, the entire entity will be removed from the database:

```clojure
(def tx7-result
  (transact (:db-after tx6-result) 
            [[:db/retract 2 :person/name]]))

(get-in tx7-result [:db-after :db/eav])
=>
{1 {:db/id 1, :person/name "Rich Hickey", :person/favorite-database "Datomic"},
 3 {:db/id 3, :person/name "Jimi Hendrix"}}
```

Since the Nina Simone entity (entity id `2`) only had the one attribute `:person/name`, which we have retracted, the entity was left with no attributes, so the entire entity has been removed from the database.

We can also remove an entire entity with the `:db/retractEntity` operation. Let's remove the Rich Hickey entity from the database:

```clojure
(def tx8-result
  (transact (:db-after tx7-result) [[:db/retractEntity 1]]))

(get-in tx8-result [:db-after :db/eav])
=> {3 {:db/id 3, :person/name "Jimi Hendrix"}}
```

For the `:db/retractEntity` operation we only need to specify the entity id to be retracted. As we can see the Rich Hickey entity is gone from the database.

We can re-confirm this with `pull`:

```clojure
(pull (:db-after tx8-result) '[*] 1)
=> {:db/id 1}
```

Pulling a non-existent entity returns the map `{:db/id id}`.

In addition to removing the entity there are additional things the `:db/retractEntity` operation does that will make sense that later on in the tutorial:
* `:db/retractEntity` operation removes all references to the target entity
* `:db/retractEntity` operation removes all component entities of the target entity

### ***

We've now covered the basics: 
* Created a database with an empty schema definition
* Transacted against the database using three types of operations: `:db/add`, `:db/retract`, `:db/retractEntity`
* Queried with `pull`

We are now ready learn about additional features.
 
## Advanced Features

**It's best to go through these in order as they build on each other.** 

For these topics we'll need to introduce additional schema definitions.

Jump To:
* [Back to Basic Functionality](#basic-functionality)
* [Indexing in AVE Index](#indexing-in-ave-index)
  * Multiple values for an attribute in map form transaction data
  * Sorted AVE indexes
* [Uniqueness Constraints](#uniqueness-constraints)
  * AVE index forms
  * Lookup refs
* [Keyword Entity IDs](#keyword-entity-ids)
* [Cardinality Many Attributes](#cardinality-many-attributes)
  * EAV index forms
  * Checking attribute properties
  * Sorted cardinality many attributes
* [Reference Attributes](#reference-attributes)
  * Using tempds to reference other components
  * Joins in `pull` (forward and backward)
* [Component Entities](#component-entities)
  * AVE index forms
* [Nested Maps](#nested-maps)
* [Transaction Errors](#transaction-errors)

**NOTE: Each section assumes you're starting with a fresh REPL, and you've run the following:**

```clojure
(require '[entity-graph.core :refer :all])
(set! *print-namespace-maps* false)
```

## Indexing in AVE Index

The AVE index enables fast lookups of values and value ranges for a given attribute, thus speeding up any reads involving that attribute. For example, say we want to find all entities where the attribute `:person/last-name` is `"Jones"`. If we had to rely on the EAV index, we would need to scan the entire index and for each entity check the value of `:person/last-name` attribute against `"Jones"`. 

With the AVE index, we can just do a simple map lookup: 
> Jump to the attribute `:person/last-name` and the to value `"Jones"`. This gives us all the id of every entity that has the value `"Jones"` for attribute `:person/last-name`.

> You may reference the [Indexes Section in the Readme](DOCUMENTATION.md#indexes) for further details.  

While all attributes are stored in the EAV index, only select attributes are stored in the AVE (attribute-value-entity) index. All unique and reference attributes (we'll learn about these soon) are automatically added to the AVE index. 

For all other attributes use the `:db/index` key in the attribute definition to add them to the AVE index.

```clojure
(def schema 
  {:person/last-name
   {:db/index {:db/map-type :db.map-type/hash-map}}})

(def db (create-db schema))

(select-keys db [:db/eav :db/ave])

=> {:db/eav {}, :db/ave {:person/last-name {}}}
```

Our AVE index now contains an entry for the `:person/last-name` attribute. So far it's just an empty map. Specifying `{:db/map-type :db.map-type/hash-map}` tells the database to use a standard clojure map, but later we'll also see options for sorted maps.

Let's add an entity:

```clojure
(def tx-result 
  (transact db [{:person/first-name "Jane" :person/last-name "Doe"}]))

(-> (:db-after tx-result) (select-keys [:db/eav :db/ave]))
=>
{:db/eav
 {1 {:person/first-name "Jane", :person/last-name "Doe", :db/id 1}},
 :db/ave {:person/last-name {"Doe" #{1}}}}
```

Note the entry `{:person/last-name {"Doe" #{1}}}` in the AVE index (under `:db/ave` key). The value `"Doe"` for attribute `:person/last-name` has been indexed. The AVE index tells us that the value `"Doe"` is held by following set of entities ids: `#{1}`, allowing us to quickly find all entity ids with the value `"Doe"` for attribute `:person/last-name`:

```clojure
(-> (:db-after tx-result) :db/ave (get-in [:person/last-name "Doe"]))
=> #{1}
```

We could then use those entity ids (only one in this example) to quickly look up the entities in the EAV index:

```clojure
(map (fn [id]
       (get-in (:db-after tx-result) [:db/eav id]))
     (get-in (:db-after tx-result) [:db/ave :person/last-name "Doe"]))
=> ({:person/first-name "Jane", :person/last-name "Doe", :db/id 1})
```

Specifying `:db/index {:db/map-type :db.map-type/hash-map}` indexed `:person/last-name` in a standard Clojure map, which allows us to quickly lookup specific values. But to lookup value ranges efficiently we need to use a sorted map for our AVE index. 

We can use two types of sorted maps: Clojure's sorted maps or sorted AVL maps from `clojure.data.avl`. AVL maps support the full `clojure.core` sorted collections API (in particular `clojure.core/(r)?(sub)?seq)`, but also offer logarithmic time operations: rank queries, "nearest key" lookups, splits by index or key, and subsets.

We'll use an AVL map. Let's specify a sorted AVE index in the schema definition. The only difference is the change from `:db.map-type/hash-map` to `:db.map-type/avl-map`:

```clojure
(def db (create-db {:person/last-name
                    {:db/index {:db/map-type :db.map-type/avl-map}}}))
(select-keys db [:db/eav :db/ave])
=> {:db/eav {}, :db/ave {:person/last-name {}}}
```

The empty map in AVE index for `:person/last-name` is now a sorted map from `clojure.data.avl`:

```clojure
(type (get-in db [:db/ave :person/last-name]))
=> clojure.data.avl.AVLMap
```

Let's add some entities:

```clojure
(def tx-result
  (transact db [{:person/last-name "Ames"}
                {:person/last-name "Brown"}
                {:person/last-name "Cedar"}
                {:person/last-name "Doe"}]))

(-> (:db-after tx-result) :db/ave (get :person/last-name))
=> {"Ames" #{1}, "Brown" #{2}, "Cedar" #{3}, "Doe" #{4}}
```

We've added 4 entities with last names, which were indexed into an AVL sorted map. We can now quickly select a sub-range of the index as follows:

```clojure
(require '[clojure.data.avl :as avl])

(-> (:db-after tx-result) :db/ave (get :person/last-name) (avl/subrange > "Brown"))
=> {"Cedar" #{3}, "Doe" #{4}}
```

With `(avl/subrange > "Brown")` we were able to get every last name that is lexicographically greater than `"Brown"` in logarithmic time. Learn more here: https://github.com/clojure/data.avl

Alternatively, we use `clojure.core/subseq`:

```clojure
(-> (:db-after tx-result) :db/ave (get :person/last-name) (subseq > "Brown"))
=> (["Cedar" #{3}] ["Doe" #{4}])
```

## Uniqueness Constraints

The `:db/unique` key in attribute definition specifies a uniqueness constraint for an attribute. There are two possible constraints: `:db.unique/identity` or `:db.unique/value`. Unique constraints allow us to define an external key in addition to the entity id, which is the internal key. An entity may have any number of external keys.

Only cardinality one attributes can have unique constraints. (We'll learn about cardinality many attributes soon).

### :db.unique/identity

```clojure
(def schema 
  {:person/ssn
   {:db/unique :db.unique/identity}})

(def db (create-db schema))
```

We've now defined a `:db.unique/identity` constraint for the attribute `:person/ssn` (representing a person's unique social security number).

```clojure
(def tx-result 
     (transact db [{:person/first-name "Tom" :person/last-name "Brady" :person/ssn "123"}]))

(-> tx-result :db-after (select-keys [:db/eav :db/ave]))
=>
{:db/eav {1 {:person/first-name "Tom", :person/last-name "Brady", :person/ssn "123", :db/id 1}},
 :db/ave {:person/ssn {"123" 1}}}
```

We've added the person Tom with the unique attribute-value `[:person/ssn "123"]`. All `:db/unique` attributes are indexes in the AVE index. The value `"123"` in the AVE index maps to a single entity id, not a set of entity ids, because a unique a value may only be held by a single entity. (The same holds for component attribute-values that we'll learn about later). 

From now on attempts to insert a new entity containing attribute-value `[:person/ssn "123"]` will cause all attributes associated with new entity to be merged with the entity already in the database:

```clojure
(def tx2-result 
     (transact (:db-after tx-result) 
               [{:person/ssn "123" :person/salary 100 :person/first-name "Thomas"}]))

(-> tx2-result :db-after (select-keys [:db/eav :db/ave]))
=>
{:db/eav {1 {:person/first-name "Thomas", :person/last-name "Brady", :person/ssn "123", :db/id 1, :person/salary 100}},
 :db/ave {:person/ssn {"123" 1}}}
```

Rather than creating a new entity `{:person/ssn "123" :person/salary 100 :person/first-name "Thomas"}`, the asserted attributes were merged into the existing entity, updating `:person/first-name` to `"Thomas"`, and adding `[:person/salary 100]`.

Now that we've added an external key, we can use it in `pull` and other places where entity ids might be placed. If we know Tom Brady's social security number, we can use the external key `[:person/ssn "123"]` in place of the entity id:

```clojure
(pull (:db-after tx2-result) '[*] [:person/ssn "123"])
=> {:person/first-name "Thomas", :person/last-name "Brady", :person/ssn "123", :db/id 1, :person/salary 100}
```

We used the lookup ref `[:person/ssn "123"]` instead of the entity id to pull the Tom Brady entity. Lookup refs have the form `[unique-attribute value-of-unique-attribute]`.

Lookup refs can also be used in transaction data, in the entity id position of an assertion or a retraction: 

```clojure
;; Assert hair color using lookup ref

(def tx-result
  (transact (-> tx-result :db-after)
            [[:db/add [:person/ssn "123"] :person/hair-color "Red"]]))

(pull (tx-result :db-after) '[*] [:person/ssn "123"])

=> {:person/first-name "Tom", :person/last-name "Brady", :person/ssn "123", :db/id 1, :person/hair-color "Red"}

;; Retract hair color using lookup ref

(def tx-result
  (transact (-> tx-result :db-after)
            [[:db/retract [:person/ssn "123"] :person/hair-color "Red"]]))

(pull (tx-result :db-after) '[*] [:person/ssn "123"])

=> {:person/first-name "Tom", :person/last-name "Brady", :person/ssn "123", :db/id 1}
```

Lookup refs can also be used as a value for reference type attributes. You can this try this yourself when we get to reference attributes.

### :db.unique/value

A unique value represents an attribute-wide value that can be asserted only once. 

Let's add a `:db.unique/value` constraint to the attribute `:person/ssn`:

```clojure
(def schema
  {:person/ssn
   {:db/unique :db.unique/value}})

(def db (create-db schema))

(def tx-result 
     (transact db [{:person/first-name "Tom" :person/last-name "Brady" :person/ssn "123"}]))

(-> tx-result :db-after (select-keys [:db/eav :db/ave]))
=>
{:db/eav {1 {:person/first-name "Tom", :person/last-name "Brady", :person/ssn "123", :db/id 1}},
 :db/ave {:person/ssn {"123" 1}}}
```

So far, everything looks the same as with `:db.unique/identity`, but when we attempt to insert a new entity containing attribute-value `[:person/ssn "123"]`, we'll get an exception, because asserting a unique value more than once is illegal.

The following will result in a `:db.error/unique-conflict`:

```clojure
(transact (:db-after tx-result) 
          [{:person/ssn "123" :person/salary 100 :person/first-name "Thomas"}])
```

However, we can still use lookup refs with `:db.unique/value` just like we did with `:db.unique/identity`:

```clojure
(pull (:db-after tx-result) '[*] [:person/ssn "123"])
=> {:person/first-name "Tom", :person/last-name "Brady", :person/ssn "123", :db/id 1}
```

> In practice, we may designate `:person/ssn` as `:db.unique/value` or `:db.unique/identity` depending on whether we wanted the upsert (merge) behavior. Something like a reservation number is an example use case for `:db.unique/value` where the merge behavior is likely not needed/wanted.

## Keyword Entity IDs

There are two types of entity ids: auto-assigned ids and user-defined keywords. When we create a new entity with a tempid or a map form with no `:db/id`, an entity id is assigned automatically. But sometimes we want the new entity to have a nice programmatic name. That's what keyword entity ids are for.

```clojure
(def tx-result
  (transact db [{:db/id :ui/login-form
                 :login-form/username "Enter username"
                 :login-form/password "Enter password"}]))

(-> tx-result :db-after :db/eav)
=>
{:ui/login-form {:db/id :ui/login-form,
                 :login-form/username "Enter username",
                 :login-form/password "Enter password"}}
```

We started with an empty database and added an entity with the entity id `:ui/login-form`. We can now use this entity id with `pull` or to update the entity (or for any other purpose):

```clojure
(pull (:db-after tx-result) '[*] :ui/login-form)
=> {:db/id :ui/login-form, :login-form/username "Enter username", :login-form/password "Enter password"}

(def tx2-result
  (transact (:db-after tx-result) [[:db/add :ui/login-form :login-form/username "admin"]
                                   [:db/add :ui/login-form :login-form/password "123"]]))

(-> tx2-result :db-after :db/eav)
=>
{:ui/login-form {:db/id :ui/login-form,
                 :login-form/username "admin",
                 :login-form/password "123"}}
```

Hmmm... looking at username and password, security isn't too tight.

## Cardinality Many Attributes

Cardinality many attributes allow association of multiple values with an entity. Cardinality many attributes must be specified in the schema definition:

```clojure
(def schema
  {:person/aliases-one
   {:db/index {:db/map-type :db.map-type/hash-map}}
   :person/aliases-many
   {:db/cardinality :db.cardinality/many
    :db/index {:db/map-type :db.map-type/hash-map}}})

(def db (create-db schema))
```

We've created a schema with two attributes:
* No cardinality is specified for `:person/aliases-one`, so it is cardinality one by default
* `:person/aliases-many` is defined to be cardinality many

Let's see how their behavior differs:

```clojure
(def tx-result (transact db [{:person/name "Jim"
                              :person/aliases-one ["Jimmy" "Jimbo"]
                              :person/aliases-many ["Jimmy" "Jimbo"]}]))

(-> tx-result :db-after (select-keys [:db/eav :db/ave]))
=>
{:db/eav {1 {:person/name "Jim",
             :person/aliases-one ["Jimmy" "Jimbo"],
             :person/aliases-many #{"Jimmy" "Jimbo"},
             :db/id 1}},
 :db/ave {:person/aliases-one {["Jimmy" "Jimbo"] #{1}}, 
          :person/aliases-many {"Jimmy" #{1}, "Jimbo" #{1}}}}
```

The set `#{"Jimmy" "Jimbo"}` in the EAV index for `:person/aliases-many` represents the two distinct values `"Jimmy"` and `"Jimbo"`. We know to interpret the set as two distinct string values (and not one set value) because we defined the attribute `:person/aliases-many` to be cardinality many. 

This is made clearer by examining the AVE index, where we find that the two distinct values have been indexed separately as `"Jimmy"` and `"Jimbo"`. By contrast for `:person/aliases-one` the value `["Jimmy" "Jimbo"]` in the EAV index is a single value. Only that single value is indexed in the AVE index.

In our transaction data we could have specified the set `#{"Jimmy" "Jimbo"}` instead of the vector `["Jimmy" "Jimbo"]`. We would then find the set `#{"Jimmy" "Jimbo"}` in the EAV index for both `:person/aliases-one` and `:person/aliases-many`.

These seemingly identical values should be interpreted differently:

```clojure
(def tx-result (transact db [{:person/name "Jim"
                              :person/aliases-one #{"Jimmy" "Jimbo"}
                              :person/aliases-many #{"Jimmy" "Jimbo"}}]))

(-> tx-result :db-after (select-keys [:db/eav :db/ave]))
=>
{:db/eav {1 {:person/name "Jim",
             :person/aliases-one #{"Jimmy" "Jimbo"},
             :person/aliases-many #{"Jimmy" "Jimbo"},
             :db/id 1}},
 :db/ave {:person/aliases-one {#{"Jimmy" "Jimbo"} #{1}}, 
          :person/aliases-many {"Jimmy" #{1}, "Jimbo" #{1}}}}
```

Examining the AVE index we can see that:
* The set `#{"Jimmy" "Jimbo"}` for `:person/aliases-one` is interpreted as a single set value.
* The set `#{"Jimmy" "Jimbo"}` for `:person/aliases-many` is interpreted as two distinct string values.

### Cardinality Many Values in List Forms

We've been using map form transaction data to add multiple values for cardinality many attributes (i.e. the value `#{"Jimmy" "Jimbo"}` for attribute `:person/aliases-many`). Now let's turn to asserting multiple values for cardinality many attributes using list forms.

To add multiple values using list form assertions we have to assert each value in a separate form. So `"Jimmy"` and `"Jimbo"` need to be asserted separately:

```clojure
(def tx-result
  (transact db [[:db/add "new" :person/name "Jim"]
                [:db/add "new" :person/aliases-many "Jimmy"]
                [:db/add "new" :person/aliases-many "Jimbo"]]))

(-> tx-result :db-after (select-keys [:db/eav :db/ave]))
=>
{:db/eav {1 {:db/id 1, :person/name "Jim", :person/aliases-many #{"Jimmy" "Jimbo"}}},
 :db/ave {:person/aliases-one {}, :person/aliases-many {"Jimmy" #{1}, "Jimbo" #{1}}}}
```

If we tried adding the value `#{"Jimmy" "Jimbo"}` in a single list form, we would end up with this:

```clojure
(def tx-result
  (transact db [[:db/add "new" :person/name "Jim"]
                [:db/add "new" :person/aliases-many #{"Jimmy" "Jimbo"}]]))

(-> tx-result :db-after (select-keys [:db/eav :db/ave]))
=>
{:db/eav {1 {:db/id 1, :person/name "Jim", :person/aliases-many #{#{"Jimmy" "Jimbo"}}}},
 :db/ave {:person/aliases-one {}, :person/aliases-many {#{"Jimmy" "Jimbo"} #{1}}}}
```

We end up with the single set value `#{"Jimmy" "Jimbo"}` for the attribute `:person/aliases-many`. In the EAV index it gets wrapped in an enclosing set and we get `#{#{"Jimmy" "Jimbo"}}`. In the AVE index we get a single key/val pair `{#{"Jimmy" "Jimbo"} #{1}}`. This is because in list form transaction data, sets and sequentials are interpreted as single values.

### Retracting Cardinality Many Values

Retracting cardinality many values is similar to asserting cardinality many values in that we must retract one value per form.

Let's first add an entity, so we have something to retract:

```clojure
(def tx-result (transact db [{:person/name "Jim" 
                              :person/aliases-many #{"Jimmy" "Jimbo" "Jimio"}}]))

(-> tx-result :db-after (select-keys [:db/eav :db/ave]))

=>
{:db/eav {1 {:person/name "Jim", :person/aliases-many #{"Jimmy" "Jimbo" "Jimio"}, :db/id 1}},
 :db/ave {:person/aliases-one {}, :person/aliases-many {"Jimmy" #{1}, "Jimbo" #{1}, "Jimio" #{1}}}}
```

Now let us retract the single value `"Jimmy"` for `:person/aliases-many`:

```clojure
(def tx2-result 
  (transact (:db-after tx-result) [[:db/retract 1 :person/aliases-many "Jimmy"]]))

(-> tx2-result :db-after (select-keys [:db/eav :db/ave]))
=>
{:db/eav {1 {:person/name "Jim", :person/aliases-many #{"Jimbo" "Jimio"}, :db/id 1}},
 :db/ave {:person/aliases-one {}, :person/aliases-many {"Jimbo" #{1}, "Jimio" #{1}}}}
```

We've retracted the value `"Jimmy"` and we're left with the two remaining values `"Jimbo"` and `"Jimio"`.

We can also remove all values of a cardinality many attribute by omitting the value in the `:db/retract` form:

```clojure
(def tx2-result
  (transact (:db-after tx-result) [[:db/retract 1 :person/aliases-many]]))

(-> tx2-result :db-after (select-keys [:db/eav :db/ave]))
=> {:db/eav {1 {:person/name "Jim", :db/id 1}}, 
    :db/ave {:person/aliases-one {}, :person/aliases-many {}}}
```

All values for the attribute `:person/aliases-many` have been removed.

### Different Interpretation of Sets in EAV Index for Cardinality One vs Many Attributes

> Once again, when we are leveraging the EAV and AVE indexes for reading data, we should keep in mind the cardinality one/many distinction. For cardinality many attributes we must interpret all the outermost sets in the EAV index as sets of distinct values rather than a single set value.

To find out the cardinality of an attribute, we can use the function `cardinality-many?` which takes an encoded schema (the value under `:db/schema` in a database value) and an attribute:

```clojure
(cardinality-many? (:db/schema db) :person/name)
=> nil

(cardinality-many? (:db/schema db) :person/aliases-many)
=> :person/aliases-many
```

Alternatively, we can use the function `check-attr`, which takes a database value, an attribute, and a property:

```clojure
(check-attr db :person/name :db/cardinality)
=> :db.cardinality/one

(check-attr db :person/aliases-many :db/cardinality)
=> :db.cardinality/many
```

> **You can [learn more about the function`check-attr`](DOCUMENTATION.md#helper-function-check-attr) in the Readme.**

### Sorted Cardinality Many Attributes

By default, cardinality many values within an entity in the EAV index are stored in an ordinary (unordered) set, but we can also define them as sorted sets. 

We can use two types of sorted sets: Clojure's sorted sets or sorted sets from `clojure.data.avl`. AVL sorted sets support the full `clojure.core` sorted collections API, but also offer logarithmic time operations: rank queries, "nearest key" lookups, splits by index or key, and subsets. Learn more here: https://github.com/clojure/data.avl

Here's an example:

```clojure
(def schema
  {:person/past-salaries
   {:db/cardinality :db.cardinality/many
    :db/sort        {:db/set-type :db.set-type/sorted-set}}
   :person/past-salaries-avl-desc
   {:db/cardinality :db.cardinality/many
    :db/sort        {:db/set-type   :db.set-type/avl-set
                     :db/comparator >}}})

(def db (create-db schema))
```

With the schema above:
* For the attribute `:person/past-salaries`, `:db/sort {:db/set-type :db.set-type/sorted-set}` tells the database to store the attribute in clojure's sorted set (with the default comparator `compare`).
* For the attribute `:person/past-salaries-avl-desc`, `:db/set-type :db.set-type/avl-set` tells the database to store the attribute in a sorted AVL set. The optional `:db/comparator` key specifies a comparator, in this case `>` (greater than, in order to sort in descending order). 
* Custom comparators are supported for both AVL sets and clojure's sorted sets.

```clojure
(def tx-result (transact db [{:person/name "Katy"
                              :person/past-salaries [100 200 300]
                              :person/past-salaries-avl-desc [100 200 300]}]))

(-> tx-result :db-after :db/eav)
=>
{1 {:db/id 1, :person/name "Katy", 
    :person/past-salaries #{100 200 300}, 
    :person/past-salaries-avl-desc #{300 200 100}}}
```

We can now perform all the logarithmic time operation supported by `clojure.data.avl` on the value `#{300 200 100}` for the attribute `:person/past-salaries-avl-desc`.

## Reference Attributes

Reference attributes allow us to point to other entities in the database, thus creating a graph. Reference attributes must be specified in the schema definition:

```clojure
(def schema
  {:person/name
   {:db/unique :db.unique/identity}
   :person/best-friend
   {:db/valueType :db.type/ref}
   :person/friend
   {:db/cardinality :db.cardinality/many
    :db/valueType :db.type/ref}})

(def db (create-db schema))
```

We now have two reference attributes whose values will be interpreted as entity ids referencing other entities in the database:
* Cardinality one attribute `:person/best-friend` 
* Cardinality many attribute `:person/friend`

Let's add some data:

```clojure
(def tx-result
  (transact db [{:person/name "Donna" :person/best-friend "liz"}
                {:db/id "liz" :person/name "Liz"}]))
```

We've added two entities to the database and linked them to each other using the tempid `"liz"`. This is the other important function of tempids - they allow us to link entities within a transaction.

Let's examine the transaction data to see how it works. The first entity `{:person/name "Donna" :person/best-friend "liz"}` uses the tempid `"liz"` to link it to the second entity `{:db/id "liz" :person/name "Liz"}` via the `:person/best-friend` attribute. 

* `{:db/id "liz" :person/name "Liz"}` tells the database to create a new entity and assign it a new entity id. 
* Within the transaction we can use the tempid `"liz"` to refer to this entity, which is what we do in the other map form in transaction data: `{:person/name "Donna" :person/best-friend "liz"}`. 
* Since `:person/best-friend` is a reference attribute, its value is interpreted as a reference to another entity. 
* After the transaction completes, `:person/best-friend` will have whatever entity id gets assigned to the entity with tempid `"liz"`.

Let's examine the indexes to confirm:

```clojure
(-> tx-result :db-after (select-keys [:db/eav :db/ave]))
=>
{:db/eav {1 {:db/id 1, :person/name "Liz"}, 
          2 {:person/name "Donna", :person/best-friend 1, :db/id 2}},
 :db/ave {:person/name {"Liz" 1, "Donna" 2}, 
          :person/best-friend {1 #{2}}, 
          :person/friend {}}}
```

We have the two entities in the EAV index. Even though we didn't specify `:db/index`, the attributes `:person/best-friend` and `:person/friend` are in the AVE index because all reference attributes are indexed automatically to support `pull` queries and entity retractions. 

The attribute `:person/name` is also automatically indexed in the AVE index because it has a `:db/unique` constraint. As a reminder, since it has a `:db/unique` constraint, we can use `:person/name` as an external key. 

Let's try pulling everything for Donna:

```clojure
(pull (:db-after tx-result) '[*] [:person/name "Donna"])
=> {:person/name "Donna", :person/best-friend {:db/id 1}, :db/id 2}
```

Note that `:person/best-friend {:db/id 1}` tells us that Donna is linked to Liz. This may become clearer if we join on the `:person/best-friend` attribute.

### Using Joins in Pull

Joins in a pull pattern are signified by a map containing a join attribute and a pattern for the join attribute:

```clojure
(pull (:db-after tx-result) '[:person/name {:person/best-friend [*]}] [:person/name "Donna"])
=> {:person/name "Donna", :person/best-friend {:db/id 1, :person/name "Liz"}}
```

We start with the Donna entity, get the `:person/name` attribute, and also join on the `:person/best-friend` attribute and get all the attributes for the joined entity (with wildcard `'*`).

Let's add two more person entities, Frank and Hank, and link them to Liz via the `:person/friend` attribute:

```clojure
(def tx2-result 
  (transact (:db-after tx-result) 
            [{:db/id [:person/name "Liz"]
              :person/friend ["frank" "hank"]}
             {:db/id "frank" :person/name "Frank" :person/dob "11-12-1999"}
             {:db/id "hank" :person/name "Hank" :person/dob "09-05-1983"}]))

(-> tx2-result :db-after (select-keys [:db/eav :db/ave]))
=>
{:db/eav {1 {:db/id 1, :person/name "Liz", :person/friend #{4 3}},
          2 {:person/name "Donna", :person/best-friend 1, :db/id 2},
          3 {:db/id 3, :person/name "Frank", :person/dob "11-12-1999"},
          4 {:db/id 4, :person/name "Hank", :person/dob "09-05-1983"}},
 :db/ave {:person/name {"Liz" 1, "Donna" 2, "Frank" 3, "Hank" 4},
          :person/best-friend {1 #{2}},
          :person/friend {4 #{1}, 3 #{1}}}}
```

Looks like it worked, but let's re-confirm with a couple of `pull` queries:

```clojure
(pull (:db-after tx2-result) '[:person/name {:person/best-friend [*]}] [:person/name "Donna"])
=> {:person/name "Donna", :person/best-friend {:db/id 1, :person/name "Liz", :person/friend #{{:db/id 4} {:db/id 3}}}}
```

Note in particular `:person/friend #{{:db/id 4} {:db/id 3}}`, this is how Liz is joined to her friends. Once again, we can join on `:person/friend` to get the referenced entities:

```clojure
(pull (:db-after tx2-result) '[:person/name {:person/friend [*]}] [:person/name "Liz"])
=>
{:person/name "Liz",
 :person/friend ({:db/id 4, :person/name "Hank", :person/dob "09-05-1983"}
                  {:db/id 3, :person/name "Frank", :person/dob "11-12-1999"})}
```

### Nested Joins

Joins in `pull` can be nested to arbitrary depth:

```clojure
(pull (:db-after tx2-result) '[:person/name {:person/best-friend [:person/name {:person/friend [*]}]}] [:person/name "Donna"])

=>
{:person/name "Donna",
 :person/best-friend {:person/name "Liz",
                      :person/friend ({:db/id 4, :person/name "Hank", :person/dob "09-05-1983"}
                                      {:db/id 3, :person/name "Frank", :person/dob "11-12-1999"})}}
```

In the example above we start with the entity representing Donna using the lookup ref `[:person/name "Donna"]`. We get the value of the `:person/name` attribute and join on the attribute `:person/best-friend`. The entity joined on `:person/best-friend` is the entity representing Liz. For Liz, we get the attribute `:person/name` and join on the attribute `:person/friend`, which links us to Hank and Frank.

### Reverse Joins And Bidirectionality of Reference Attributes

Reference attributes are bidirectional. An underscore in the local name segment of a reference attribute specifies reverse navigation.

Even though the attribute `:person/best-friend` linking to Liz belongs to Donna, we can start with Liz and traverse the graph backwards to Donna like this:

```clojure
(pull (:db-after tx2-result) '[{:person/_best-friend [*]}] [:person/name "Liz"])
=> {:person/_best-friend ({:person/name "Donna", :person/best-friend {:db/id 1}, :db/id 2})}
```

There is no need link in the reverse direction from Liz to Donna with a separate attribute/ref-value. Instead, we can do reverse reference navigation by placing an underscore `_` before the local name of the reference attribute (i.e. `:person/_best-friend`).

Here's another example:

```clojure
(pull (:db-after tx2-result) '[{:person/_friend [*]}] [:person/name "Hank"])
=> {:person/_friends ({:db/id 1, :person/name "Liz", :person/friend #{{:db/id 4} {:db/id 3}}})}
```

We start with Hank. The join on `:person/_friend` (note the underscore) specifies that we should traverse the graph backwards from Hank to every entity that links to Hank from the attribute `:person/friend`.

### Nested Reverse Joins

Just like forward joins, backward joins in `pull` can be nested to arbitrary depth.

Here's a somewhat complex example:

```clojure
(pull (:db-after tx2-result) 
      '[:person/name {:person/_friend [:person/name :person/friend {:person/_best-friend [:person/name]}]}] 
      [:person/name "Hank"])
=>
{:person/name "Hank",
 :person/_friend ({:person/name "Liz",
                   :person/friend #{{:db/id 4} {:db/id 3}},
                   :person/_best-friend ({:person/name "Donna"})})}

```

We start with Hank, get `:person/name` and join on `:person/_friend`, which traverses the graph backwards to Liz. From Liz we get the attributes `:person/name` and `:person/friend` and also join on `:person/_best-friend`, which traverses the graph backwards to Donna.

### Recursive Joins

**TODO**

### Reference Attributes And :db/retractEntity

When we first looked at the `:db/retractEntity` operation we noted that it removes an entity **and all references to that entity**. 

Now we can see this in action: 

```clojure
;; Before
(-> tx2-result :db-after (select-keys [:db/eav :db/ave]))
=>
{:db/eav {1 {:db/id 1, :person/name "Liz", :person/friend #{4 3}},
          2 {:person/name "Donna", :person/best-friend 1, :db/id 2},
          3 {:db/id 3, :person/name "Frank", :person/dob "11-12-1999"},
          4 {:db/id 4, :person/name "Hank", :person/dob "09-05-1983"}},
 :db/ave {:person/name {"Liz" 1, "Donna" 2, "Frank" 3, "Hank" 4},
          :person/best-friend {1 #{2}},
          :person/friend {4 #{1}, 3 #{1}}}}

;; After
(def tx3-result (transact (:db-after tx2-result)
                          [[:db/retractEntity [:person/name "Liz"]]]))

(-> tx3-result :db-after (select-keys [:db/eav :db/ave]))
=>
{:db/eav {4 {:db/id 4, :person/name "Hank", :person/dob "09-05-1983"},
          2 {:person/name "Donna", :db/id 2},
          3 {:db/id 3, :person/name "Frank", :person/dob "11-12-1999"}},
 :db/ave {:person/name {"Donna" 2, "Frank" 3, "Hank" 4}, :person/best-friend {}, :person/friend {}}}
```

**Note that the Liz entity is gone and so are all references to Liz from other entities. Specifically the reference from Donna to Liz via the `:person/best-friend` attribute is gone.**

One last note: cardinality one reference attributes may have a `:db/unique` constraint just like any other cardinality one attribute. You may wish to explore this on your own.

## Component Entities

Reference attributes can specify an optional `:db/isComponent` key to declare that the attribute refers to a sub-component, which enables the following:
* `:db/retractEntity` operations will retract the sub-components of the parent entity
* `pull` will automatically pull in the sub-components of the parent entity

Let's define a component and a non-component reference attributes:

```clojure
(def schema
  {:person/drivers-license
   {:db/valueType :db.type/ref
    :db/isComponent true}
   :person/drivers-license-not-component
   {:db/valueType :db.type/ref}})

(def db (create-db schema))
```

`:person/drivers-license` is a component attribute. `:person/drivers-license-not-component` isn't a component attribute because by default reference attributes are not components.

Now, let's add some entities:

```clojure
(def tx-result 
  (transact db [{:person/name "Mark" 
                 :person/drivers-license "dl-component"
                 :person/drivers-license-not-component "dl-not-component"}
                [:db/add "dl-component" :drivers-license-number "123"]
                [:db/add "dl-not-component" :drivers-license-number-not-component "321"]]))

(-> tx-result :db-after (select-keys [:db/eav :db/ave]))
=>
{:db/eav {1 {:db/id 1, :drivers-license-number "123"},
          2 {:db/id 2, :drivers-license-number-not-component "321"},
          3 {:person/name "Mark", :person/drivers-license 1, :person/drivers-license-not-component 2, :db/id 3}},
 :db/ave {:person/drivers-license {1 3}, 
          :person/drivers-license-not-component {2 #{3}}}}
```

We've added the parent entity Mark, who is a holder of: 
* A component driver license `[:drivers-license-number "123"]`
* A non-component driver license `[:person/drivers-license-not-component "321"]`

Sub-component entities can have only one parent. That's why in the AVE index there is just one entity id mapped to the reference value for the `:person/drivers-license` attribute, while for `:person/drivers-license-not-component` it is a set of entity ids (a set containing one value). 

Let's see what happens if we pull Mark:

```clojure
(pull (:db-after tx-result) '[*] 3)
=>
{:person/name "Mark",
 :person/drivers-license {:db/id 1, :drivers-license-number "123"},
 :person/drivers-license-not-component {:db/id 2},
 :db/id 3}
```

Note that `:person/drivers-license` is pulled in its entirety, but not `:person/drivers-license-not-component`. If we want to pull in the entity under `:person/drivers-license-not-component`, we have to do a join:

```clojure
(pull (:db-after tx-result) '[{:person/drivers-license-not-component [*]}] 3)
=> {:person/drivers-license-not-component {:db/id 1, :drivers-license-number-not-component "321"}}
```

Now, let's retract the Mark entity using `:db/retractEntity`:

```clojure
(def tx2-result (transact (:db-after tx-result) [[:db/retractEntity 3]]))

(-> tx2-result :db-after (select-keys [:db/eav :db/ave]))
=>
{:db/eav {2 {:db/id 2, :drivers-license-number-not-component "321"}},
 :db/ave {:person/drivers-license {}, :person/drivers-license-not-component {}}}
```

Note that the sub-component `:person/drivers-license` has been retracted along with the parent entity Mark, while the entity for `:drivers-license-number-not-component` is still in the database.

## Nested Maps

Now that we've introduced component attributes and unique constraints, we can introduce nested maps. In map form assertions we can use nested maps as values for reference attributes to link entities. 

The nested map will be interpreted as a separate entity iff:
* The reference attribute must be a component attribute
* Or the nested map must contain a unique attribute

If neither of those conditions is met, the transaction will fail.

Let's start with this db:

```clojure
(def schema
  {:person/drivers-license
   {:db/valueType :db.type/ref
    :db/isComponent true}
   :person/friend
   {:db/cardinality :db.cardinality/many
    :db/valueType :db.type/ref}
   :person/ssn
   {:db/unique :db.unique/identity}})

(def db (create-db schema))
```

Now, let's use a nested map in our transaction data:

```clojure
(def tx-result 
  (transact db [{:person/name "Kim" :person/drivers-license {:drivers-license-number "123"}}]))

(-> tx-result :db-after (select-keys [:db/eav :db/ave]))
=>
{:db/eav {1 {:drivers-license-number "123", :db/id 1}, 
          2 {:person/name "Kim", :person/drivers-license 1, :db/id 2}},
 :db/ave {:person/drivers-license {1 2}, :person/ssn {}}}
```

In transaction data, the entity `{:drivers-license-number "123"}` was provided as a nested map for the `:person/drivers-license` attribute. `:person/drivers-license` is a component attribute, so the nested map is interpreted as a separate entity and we end up with two entities in the EAV index with one linking to the other.  

The last example worked because `:person/drivers-license` is a component attribute. The following works even though `:person/friend` is not a component attribute because the nested map `{:perons/name "Jim" :person/ssn "123"}` contains a unique attribute `:person/ssn`:

```clojure
(def tx-result 
  (transact db [{:person/name "Kim" :person/friend {:person/name "Jim" :person/ssn "123"}}]))

(-> tx-result :db-after (select-keys [:db/eav :db/ave]))
=>
{:db/eav {1 {:person/name "Jim", :person/ssn "123", :db/id 1}, 
          2 {:person/name "Kim", :person/friend #{1}, :db/id 2}},
 :db/ave {:person/drivers-license {}, :person/friend {1 #{2}}, :person/ssn {"123" 1}}}
```

The following will fail with a `:db.error/invalid-nested-entity` because neither condition is met:
* `:person/friend` is not a component attribute 
* The nested map `{:person/name "Jim"}` doesn't include any attribute with a unique constraint

```clojure
;; Will fail
(transact db [{:person/name "Kim" :person/friend {:person/name "Jim"}}])
```

You can nest the map entities as deeply as you like:

```clojure
(def tx-result
  (transact db [{:person/ssn "L1" 
                 :person/friend {:person/ssn "L2" :person/friend {:person/ssn "L3"}}}]))

(-> tx-result :db-after (select-keys [:db/eav :db/ave]))
=>
{:db/eav {1 {:person/ssn "L2", :person/friend #{2}, :db/id 1},
          2 {:person/ssn "L3", :db/id 2},
          3 {:person/ssn "L1", :person/friend #{1}, :db/id 3}},
 :db/ave {:person/drivers-license {}, :person/friend {2 #{1}, 1 #{3}}, :person/ssn {"L2" 1, "L3" 2, "L1" 3}}}
```

If we provide a map as a value for a non-reference attribute, it will not be interpreted as a separate entity, but as a map value:

```clojure
(def tx-result
  (transact db [{:non-reference-attribute {:drivers-license-number "123"}}]))

(-> tx-result :db-after (select-keys [:db/eav :db/ave]))
=>
{:db/eav {1 {:non-reference-attribute {:drivers-license-number "123"}, :db/id 1}},
 :db/ave {:person/drivers-license {}, :person/friend {}, :person/ssn {}}}
```

The nested map `{:drivers-license-number "123"}` is not interpreted as a separate entity, but as a map value. Recall that collection values are legal; anything but `nil` is legal.

## Transaction Errors

The database will enforce several constraints. If transaction data violates one of those constraints, an exception is thrown. For example, since `nil` values are illegal and this constraint is enforced, attempting to add a nil value will fail with `:db.error/nil-value`:

```clojure
;; Will fail
(transact db [[:db/add "new" :person/name nil]])
```

Several other constraints are enforced:
* Can't assert on a retracted entity
* Can't assert and retract the same `[e a v]` triple
* Can't assert multiple values for cardinality one attribute
* An entity can only be a component of one parent entity
* A unique value for a given attribute can only be held by one entity

