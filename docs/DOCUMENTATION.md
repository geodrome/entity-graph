# EntityGraph Documentation

# Table of Contents
* [Overview](#overview) - Basic concepts and capabilities.
* [Transactions](#transactions) - How to use `transact` to add/remove/update entities.
* [Schema](#schema) - Explains various attribute properties that can be defined in the schema. 
* [Indexes](#indexes) - How indexes are constructed and how entities are represented in indexes.
* [Pull](#pull) - Declarative data retrieval.
* [Read Directly from Index](#read-directly-from-index) - How to read data directly from the indexes. 

## Basic Overview

To use EntityGraph:
* Create an initial database with an optional schema using the function `create-db`.
  * The schema cannot be updated, once created.
* Add, remove, update entities using the function `transact`.
  * Each call to `transact` produces a new immutable database value.
  * `transact` enforces certain database constraints and takes care of indexing.
* Read data in a declarative way, use the function `pull` to make hierarchical (and possibly nested) selections of information about entities.
  * Alternatively, read from the indexes directly with custom data retrieval functions.

### Quick Example

> **To see these concepts in action check out the [Hands-on Tutorial](TUTORIAL.md).**

This is just to give you taste:

```clojure
;; Create a database
;; This schema has just one attribute, which is to be indexed in the AVE index
(def db-empty
  (create-db {:person/last-name
              {:db/index {:db/map-type :db.map-type/hash-map}}}))
=> #'user/db-empty

;; Add and entity to the empty database and capture result of the transaction in tx-result
;; NOTE: :person/first-name attribute didn't need to be defined in the schema
(def tx-result (transact db-empty [{:person/first-name "Jim" :person/last-name "Morrison"}]))
=> #'user/tx-result

;; Examine the EAV and AVE indexes after the transaction above
(select-keys (:db-after tx-result) [:db/eav :db/ave])
=>
{:db/eav {1 {:person/first-name "Jim", :person/last-name "Morrison", :db/id 1}},
 :db/ave {:person/last-name {"Morrison" #{1}}}}

;; Retrieve some data with a pull query
;; pull the attribute :person/last-name for the entity with id 1
(pull (:db-after tx-result) [:person/last-name] 1)
=> {:person/last-name "Morrison"}

;; Read some data with a pull query - pull all attributes for the entity with id 1
(pull (:db-after tx-result) '[*] 1)
=> {:person/first-name "Jim", :person/last-name "Morrison", :db/id 1}
```

## Entities

The database is organized around entities. An entity is a map of attribute/value pairs. Each entity contains a special `:db/id` key signifying its entity id or the internal key in the database. Entities are indexed by `:db/id` in the EAV index and by select attributes in the AVE index.

Example entity:

```clojure
{:db/id 1 
 :person/name "John"
 :person/email "john@johnny.net"}
``` 

## Entity IDs

Entity ids are usually auto-assigned, but they can also be user-specified keywords. Keyword entity ids provide a convenient programmatic name for an entity.

Here's an entity with a keyword entity id `:ui/chat-window` and one attribute `:chat-window/text` with the string value `"Type here..."`.

```clojure
{:db/id :ui/chat-window 
 :chat-window/text "Type here..."}
```

NOTE: To be encoded in `value` attribute for HTML inputs, keyword ids must be converted to strings.

## Attributes

Entities consist of attribute/value pairs. Attributes are analogous to columns in SQL databases, but entities are not required to have predefined sets of attributes. Any and all attributes may be added or retracted to entities freely.

Attributes only need to be defined in the schema when specific attribute properties need to be declared. Otherwise, attribute names may be used freely without declaration in the schema. See the [schema section](#schema) for more details.

###  Attribute Name Constraints

* Attribute names must be keywords. Though not enforced, things will break if you don't use keywords.
* Attribute names must not begin with an underscore. This is not enforced, but it will break reverse navigation in `pull`.
* Attribute name keywords may be namespaced. 
  * The `:<namespace>/<name>` lexical form is preferred to avoid naming collisions.
* The `:db` namespace is reserved for the database by convention.

### Attribute Cardinality

By default, attributes contain just one value. These are known as cardinality one attributes. But cardinality many attributes are also supported. These attributes may contain multiple values. Cardinality many attributes are represented as sets of values:

```clojure
{:db/id 1 
 :person/name "John" 
 :person/nicknames #{"Johnny" "Versaci"}}
```

The attribute `:person/nicknames` above is cardinality many, and it is represented as a set of values. Cardinality many attributes must be defined as such in the schema when the database is created.

Several other attribute properties may be defined in the schema. See the [schema section](#schema) for more details.

## Values

* Attributes can hold values of any type, including collections.
* Attributes are not typed and no data type declarations for attributes are required in the schema. 
  * Any and all data types are allowed, including collections.
  * Different entities can hold values of different type under the same attribute name.
  * Cardinality many attributes may contain heterogeneous value types for the same entity.
  * The one **exception is the reference type**, which must be declared in the schema. 
    * Reference attributes point to other entities in the database, thus creating a graph.
    * Except for reference values, no data type declarations are required in the schema.
* Nil values are illegal. This is enforced.
  * To indicate the absence of value for a given attribute, simply avoid adding it to the entity. 
  * To remove an existing value for a given attribute, simply remove the value; don't attempt to set it to `nil`.

## Time/History

EntityGraph does not keep a history (unlike Datomic, for example). There is no transaction log and no timestamps are recorded. However, since each successive db value, produced by `transact`, is an immutable Clojure map, any number of past db values can be preserved by holding references to those values. A `:db/tx-count` value is kept and incremented after each transaction.

## Storage

Storage is not supported. If you're considering implementing it, see the section [Writing to Storage](TECHNOTES.md#writing-to-storage) in Technical Notes for some considerations.

# Transactions

> **To see these concepts in action check out the [Hands-on Tutorial](TUTORIAL.md).**

All database operations are performed with the `transact` function, which updates the database and enforces database constraints.

There are three operations:
* `:db/add` - assertions add data to the database.
* `:db/retract` - retractions remove data from the database.
* `:db/retractEntity` - retracts an entity, all of its component entities, and all references to the entity and component entities.

All operations can be expressed in list form. Assertions can also be expressed in map form. Map form assertions are more convenient and particularly performant when adding new entities to the database.

As a further convenience:
* The attribute keys in the map may be either keywords or strings.
* Nested maps are supported. 
  * Map values for reference attributes are interpreted as nested entities. 

To assert new entities, use tempids or maps with no `:db/id` key. To update an existing entity use the existing entity id.

## Temporary ids

New entities may be identified by a temporary id. Tempids in transaction data are represented by a string in the entity id position. When the transaction is processed, temporary ids are resolved to actual entity ids.

If a temporary id is used more than once, all instances of the tempid are mapped to the same entity id. There is an exception for `:db.unique/identity` attributes, which support upsert behavior:
* The tempid of a `:db.unique/identity` attribute will map to an existing entity if one exists with the same attribute and value (update)
* Or it will make a new entity if one does not exist (insert)
* All further adds in the transaction that apply to that same temporary id are applied to the "upserted" entity

# Schema

> **To see these concepts in action check out the [Hands-on Tutorial](TUTORIAL.md).**  

An example schema may be seen in the namespace [entity-graph.core-test](../test/entity_graph/core_test.cljc).

Defining attributes in the schema is only required for certain attribute behaviors. Though not required, you may wish to list the full schema anyway.

The following properties may be specified for attributes in the schema:

## :db/doc

An optional documentation string can be specified in the definition of each attribute. It might be used to document the data type, data shape, or anything else about the attribute. The docstring is meant for the programmer reading the code and isn't programmatically leveraged by the database in any way.

## :db/valueType

Attribute values can be of any type, including collections. No data type declarations are required in the schema, except for reference values.

Adding `{:db/valueType :db.type/ref}` to the schema signifies a reference attribute that refers to other entities in the database by entity id.

## :db/isComponent

Reference attributes can optionally specify sub-component entities with `{:db/isComponent true}`.

Component entities have the following properties:
* When the parent entity is retracted with `:db/retractEntity`, component entities are also retracted.
* If a component attribute is pulled with `pull`, a map containing all the attributes of the referenced entity will be returned.
* If multiple entities attempt to claim an entity as their component, the transaction will fail with `:db.error/component-conflict`.
* If an entity attempts to hold another entity as a component under different attributes, the transaction will fail with `:db.error/component-conflict`.
* By default reference attributes are non-component.

### Pseudo Entities

An alternative to creating component entities is to **not use a reference attribute** and store map values under the attribute as component "pseudo-entities". This would still offer the two main behaviors of component entities: 
* Retracting the parent with `:db/retractEntity` would still retract the component pseudo-entities.
* Pulling the attribute, would return the map containing all the attributes of the component pseudo-entity.

However, pseudo-entities have no independent existence in the database. They only exist as a map value under some attribute of a parent entity. Thus, the keys of pseudo-entities would not be interpreted as database attributes, resulting in the following trade-offs:
* It is not possible to index the values under individual keys (pseudo attributes) of pseudo-entities in the AVE index. 
  * The entire map value representing the component pseudo-entity must be indexed.
  * However, a sorted AVE index can be used with a comparator that sorts the pseudo-entities by values of individual keys or combinations of keys.
* `pull` would not be able to join/navigate via keys of pseudo-entities.
* The concept of one parent per component becomes meaningless.

## :db/cardinality

By default, attributes contain just one value. These are known as cardinality one attributes. Cardinality many attributes are also supported.

Adding `{:db/cardinality :db.cardinality/many}` to the schema signifies a cardinality many attribute. Cardinality many attributes may contain more than one value and are represented as sets of values.

If no `:db/cardinality` is specified, `:db.cardinality/one` is the default.

## :db/unique

A uniqueness constraint can be specified under the `:db/unique` key.
* Only `:db.cardinality/one` attributes can have a uniqueness constraint.
* Unique attributes are always indexed in AVE index to support fast uniqueness checks.
* An entity may contain multiple unique attributes, but anomalies may arise.
* By default attributes are non-unique.

### :db.unique/identity and :db.unique/value

* Adding `{:db/unique :db.unique/identity}` to the schema asserts a database-wide unique identifier for an entity with upsert support. 
  * A unique identity attribute can be used for a globally unique identifier (e.g. `:global-id`). This identifier might link an entity across different databases. 
* Adding `{:db/unique :db.unique/value}` to the schema represents an attribute-wide value that can be asserted only once, with no upsert support.

> To see the `:db/unique` attributes in action check out the [Hands-on Tutorial](TUTORIAL.md).

## :db/sort

By default, cardinality many attributes are stored as unsorted sets in the EAV index. Cardinality many **non-reference** attributes can optionally specify sorting parameters under the `:db/sort` key. Under some circumstances, this is a convenient solution that amortizes sorting costs and eliminates the need to repeatedly sort the same data.

> **NOTE:** When using sorted sets, all values must be intercomparable among themselves, else adding to a sorted set will fail.

### :db/set-type

The `:db/set-type` key specifies the type of sorted set. Two types of sorted sets may be used: 
* Clojure's sorted sets
* AVL sets from `clojure.data.avl` 
  * AVL sets support the full `clojure.core` sorted collections API, but also offer logarithmic time operations: rank queries, "nearest key" lookups, splits by index or key, subsets. Learn more here: https://github.com/clojure/data.avl

To use Clojure's sorted sets:

```clojure
{:db/sort {:db/set-type :db.set-type/sorted-set}} 
``` 

To use sorted AVL sets from `clojure.data.avl`:

```clojure
{:db/sort {:db/set-type :db.set-type/avl-set}}
``` 

### :db/comparator

An optional `:db/comparator` key specifies a comparator function. Custom comparators can be used with both Clojure's sorted sets and AVL sets.

The following sorted sets will compare the values with the function `>=` (greater or equal to):

```clojure
;; Clojure's sorted set with comparator
{:db/sort {:db.set-type   :db.set-type/sorted-set
           :db/comparator >=}}

;; AVL set with comparator
{:db/sort {:db.set-type   :db.set-type/avl-set
           :db/comparator >=}}
```

If no `:db/comparator` is specified, the default comparator `compare` will be used. Learn more about comparators here: https://clojure.org/guides/comparators

If sorting with multiple comparators is desired, different attributes can be used. For example, `:salary-asc` and :`salary-desc` to sort salaries in ascending and descending order. Each of these attributes would be independent of the other, so the user would need to take care to keep the two attribute values consistent.

### Sorted Reference Values

Sorting reference values doesn't make much sense since it entails sorting internal database keys. To sort the entities pointed to by a reference attribute, pull the entities and then sort them. 

Alternatively, don't use a reference attribute. Instead, store the entities as maps effectively creating component "pseudo-entities". This makes it possible to use sorted sets for these the pseudo-entities, but see [Pseudo Entities](#pseudo-entities) for an explanation of tradeoffs. 

## :db/index

Unique and reference attributes (`:db/unique` and `:db.type/ref`) are always indexed in the AVE index. For all other attributes it must be specified in the schema.

The `:db/index` key specifies that an attribute should be indexed in the AVE index.

Three types of maps may be used for indexing: 
* Clojure's (unsorted) map
* Clojure's sorted map
* Sorted map from `clojure.data.avl`. 
  * AVL maps support the full `clojure.core` sorted collections API, but also support transients and offer logarithmic time operations: rank queries, "nearest key" lookups, splits by index or key, subsets. Learn more here: https://github.com/clojure/data.avl

While unsorted maps are good for fast lookups of specific single values, sorted maps enable fast lookups for range queries.

### :db/map-type

To use the standard (unsorted) Clojure map:

```clojure
{:db/index {:db/map-type :db.map-type/hash-map}}
```

To use a sorted Clojure map:

```clojure
{:db/index {:db/map-type :db.map-type/sorted-map}}
```

To use a sorted AVL map:

```clojure
{:db/index {:db/map-type :db.map-type/avl-map}}
```

### :db/comparator

Both types of sorted map also support custom comparators.

To index in a sorted map and compare with `>` (greater than):

```clojure
{:db/index {:db/map-type :db.map-type/sorted-map 
            :db/comparator >}}
```

To index in a sorted AVL map and compare with `>` (greater than):

```clojure
{:db/index {:db/map-type :db.map-type/avl-map 
            :db/comparator >}}
```

If no `:db/comparator` is specified, the default comparator `compare` will be used. Learn more about comparators here: https://clojure.org/guides/comparators

# Indexes

> **To see these concepts in action check out the [Hands-on Tutorial](TUTORIAL.md).**

EntityGraph contains two indexes: 
* entity-attribute-value (EAV)
* attribute-value-entity (AVE)

These two indexes are sufficient to support all data retrieval operations. Each transaction updates the indexes and produces a new immutable database value.

## EAV Index

The EAV index contains all entities in a nested map. Entries have two distinct forms: 
* Form `{e {a v}}`, with only one value allowed in the `v` position
* Form `{e {a #{v1 v2 ...}}}`, with many values allowed in the `v` position

### EAV Form `{e {a v}}`

For `:db.cardinality/one` attributes EAV entries are in the form `{e {a v}}`, with only one value allowed in the `v` position.

Here's an example EAV index that contains just one entity:

```clojure
{1 {:db/id 1
    :person/name "Tina Turner"
    :person/ssn "111-22-3344"}}
```

The keys of the outer map are entity ids. The values of the outer map are the entities stored as maps with keys representing database attributes, and values representing database values. Each entity contains the special `:db/id` key representing the entity id.

### EAV Form `{e {a #{v1 v2 ...}}}`

For `:db.cardinality/many` attributes EAV entries are in the form `{e {a #{v1 v2 ...}}}`, where `v1`, `v2`, etc. are distinct values enclosed in a set.

Here's an example EAV index that contains just one entity:

```clojure
{1 {:db/id 1
    :person/name "Tina Turner"
    :person/ssn "111-22-3344"
    :person/aliases #{"Queen of Rock" "The Queen of Rock'n'Roll"}}}
```

The attribute `:person/aliases` is cardinality many and is represented by a set of values `#{"Queen of Rock" "The Queen of Rock'n'Roll"}`. Thus, the attribute `:person/aliases` contains the values `"Queen of Rock"` and `"The Queen of Rock'n'Roll"`.

### Entity Retraction

* If a transaction results in an entity with no remaining attributes, the entity is completely removed from the EAV index.
* If an entity is retracted with `:db/retractEntity` then the entity, all of its component entities, and all references to the entity and component entities will be retracted as well.

## AVE Index

> **To see these concepts in action check out the [Hands-on Tutorial](TUTORIAL.md).**

The AVE index significantly improves the speed of data retrieval operations involving the indexed attribute at the expense of additional memory space. The AVE index is non-covering, meaning that only entity id is stored in the entity position, not the full entity.

While every entity is contained in the EAV index in full, the AVE index only contains select attributes:

* All reference attributes, to support quick lookups of all entities pointing to a target entity. This speeds up reverse navigation in pull queries and is also used for `:db/retractEntity` operations.
* All unique attributes, declared with `:db/unique` property for fast uniqueness checks. 
* Attributes where the `:db/index` key specifies that it should be indexed in the AVE index.

Entries in the AVE index have two distinct forms: 
* `{a {v e}}` with only one entity id allowed in the `e` position
* `{a {v #{e1 e2 ...}}}` with many entity ids allowed in the `e` position

### AVE Form `{a {v e}}`

Unique attributes and component attributes are stored in `{a {v e}}` form, where only one entity id in the `e` position is allowed. This is because only one entity id is logically possible for unique and component attributes.

The following AVE index contains the `:db.unique/identity` attribute `:person/ssn` with two values: 
* `"123-45-6789"` belonging to the person with entity id `1`,
* `"987-65-4321"` belonging to the person with entity id `2`:

```clojure
{:person/ssn {"123-45-6789" 1, "987-65-4321" 2}}
```

The keys of the outer map are the indexed attributes (just `:person/ssn` in the example above). The values of the outer map are maps of values mapped to entity ids.

For `{a {v e}}` form attributes:
* If a transaction results in a removal of an entity id in the `e` position, then the entire `[v e]` entry is removed from the AVE index.
* If a transaction results in more than one entity id in the `e` position for a unique or component attribute, it fails with an error.

### AVE Form `{a {v #{e1 e2 ...}}}`

All non-unique and non-component indexed attributes are stored in `{a {v #{e1 e2 ...}}}` form, where multiple entity ids in the `e` position are possible. The set `#{e1 e2 ...}` contains those entity ids.

The following AVE index contains the non-unique and non-component indexed attribute `:person/last-name` with two values: `"Brown"` belonging to entity id `1`, and `"Smith"` belonging to entity ids `2` and `3`:

```clojure
{:person/last-name {"Brown" #{1}, "Smith" #{2,3}}}
```

For `{a {v #{e1 e2 ...}}}` form attributes:
* If a transaction results in an empty set in the `e` position, then the entire `{v #{e1 e2 ...}}` entry is removed from the AVE index.
* More than one entity id in the `e` position is supported as it logically makes sense.

## Collections in Indexes

> **NOTE:** Your application logic must correctly interpret cardinality one and cardinality many values retrieved from EntityGraph indexes. The following section clarifies this point, especially as it pretains to collection values.

### Cardinality One Attribute Values in EAV and AVE Indexes

Collection values are valid database values, which may cause some confusion when examining the indexes. Since collection values are valid, a set may appear in the `v` position in the EAV index, representing a single value that is a set.

For example, let's assume `:person/aliases` is a `:db.cardinality/one` attribute, and we have the following in the EAV index:

```clojure
{1 {:db/id 1
    :person/name "Tina Turner"
    :person/aliases #{"Queen of Rock" "The Queen of Rock'n'Roll"}}}
```

Then `#{"Queen of Rock" "The Queen of Rock'n'Roll"}` represents a single value. Again, this because `:person/aliases` is a cardinality one attribute. 

This means `#{"Queen of Rock" "The Queen of Rock'n'Roll"}` will be indexed as a single value in the AVE index:

```clojure
{:person/aliases {#{"Queen of Rock" "The Queen of Rock'n'Roll"} 1}}
```

### Cardinality Many Attribute Values in EAV and AVE Indexes

In contrast with the cardinality one example above, here's how this would change if `:person/aliases` were a `:db.cardinality/many` attribute. The EAV index would look identical, but the interpretation would be different:

```clojure
{1 {:db/id 1
    :person/name "Tina Turner"
    :person/aliases #{"Queen of Rock" "The Queen of Rock'n'Roll"}}}
```

The set `#{"Queen of Rock" "The Queen of Rock'n'Roll"}` in the EAV index now represents two distinct values: `"Queen of Rock"` and `"The Queen of Rock'n'Roll"`. 

Consequently, `"Queen of Rock"` and `"The Queen of Rock'n'Roll"` will be indexed as separate values in the AVE index:

```clojure
{:person/aliases {"Queen of Rock" 1, "The Queen of Rock'n'Roll" 1}}
```

### Collection Values for Cardinality Many Attributes

Now let's examine what happens when we have collection values for a cardinality many attribute. 

Since collection values are valid it is possible to see something like this:

```clojure
{1 {:db/id 1
    :person/name "Tina Turner"
    :person/favorite-food-combos #{#{:burger :fries} #{:pasta :shrimp}}}}
```

Assuming the attribute `:person/favorite-food-combos` is `:db.cardinality/many`, what is the correct interpretation? 

The set `#{#{:burger :fries} #{:pasta :shrimp}}` must be treated as two distinct values `#{:burger :fries}` and `#{:pasta :shrimp}`, and not as a single value `#{#{:burger :fries} #{:pasta :shrimp}}`. 

Consequently, `#{:burger :fries}` and `#{:pasta :shrimp}` will be indexed as separate values in the AVE index:

```clojure
{:person/aliases {#{:burger :fries} 1, #{:pasta :shrimp} 1}}
```

# Pull

Data retrieval is primarily accomplished with the `pull` function. Pull is a declarative way to make hierarchical (and possibly nested) selections of information about entities.

> **Pull examples appear throughout the [Hands-on Tutorial](TUTORIAL.md)**.

## Pull Features

EntityGraph's `pull` is a subset of Datomic's pull.

The following features are **supported**:
* Wildcarding
* Nesting
* Combining wildcard and map specifications
* Joins
* Forward and backward attribute navigation
* Recursive pulls
  * Recursive select is safe in the presence of cycles. 
    * When a recursive subselect encounters an entity that it has already seen only the `:db/id` of the entity is returned.
  * Unlimited depth on recursion not specifically supported, but a large recursion limit can be specified.

The following features are **NOT supported**:
* Naming control
* Defaults
* Transformations
* Limits on the returned results

## Pull Results

### Empty Results

* Pull returns `{}` when nothing in `pattern` matches.
  * Except for wildcard pattern or if `:db/id` is requested in pattern, in which cases a map of form `{:db/id id}` is returned.

### Reference Attribute Values

* For reference attributes a map of form `{:db/id id}` will be returned for each value.
* If a reference attribute is a component, a map containing all the attributes of the referenced entity will be returned.

### Multiple Results

Multiple results are returned in the following cases:
* For all forward cardinality-many references.
* Reverse references for non-unique/non-component attributes.

### Finding All Reverse References

In a `pull` pattern reverse navigation is possible by using an underscore in the local name segment of the attribute keyword (e.g. `:person/_friend`). However, this requires the user to know which reference attributes might be pointing to the target entity. 

We may wish to identify all references to a given entity, from any and all attributes. The function `find-reverse-refs` takes a database value and a target entity id, and returns a set of `[attribute entity-id]` vectors representing reverse references to the target entity id.

# Read Directly from Index

When `pull` is not sufficient, the indexes can be accessed directly with custom data retrieval functions. This approach is meant to replace Datalog/SQL type queries. Declarative queries are sacrificed for (hopefully) performance. Custom data retrieval functions are not expected to be used frequently.

The user must understand the data model to write custom data retrieval functions successfully. It's particularly important to understand the semantics of different attribute properties that can be declared in the schema, how the EAV and AVE indexes are constructed, and how data is represented in the indexes.

>**All of this is described above and further elucidated in the [Hands-on Tutorial](TUTORIAL.md).**

## Helper Function `check-attr`

When writing custom data retrieval functions it becomes important to know the properties of attributes being retrieved. The function `check-attr` is provided to assist with this. It takes a database value, an attribute name, an attribute property, and returns a value for that attribute name/property combination.

Here are some sample calls of `check-attr`:

```clojure
(check-attr db :person/name :db/cardinality)
=> :db.cardinality/one

(check-attr db :person/aliases :db/cardinality)
=> :db.cardinality/many

(check-attr db :person/ssn :db/unique)
=> :db.unique/identity
```

### Return Values of `check-attr` for Different Properties

The following are all the possible return values for different attribute properties:

| Property            |                                                                                                      Possible Return Values |
|:--------------------|----------------------------------------------------------------------------------------------------------------------------:|
| `:db/isRef`         |                                                                                                             `true`, `false` |
| `:db/isComponent`   |                                                                                                             `true`, `false` |
| `:db/cardinality`   |                                                                               `:db.cardinality/one`, `:db.cardinality/many` |
| `:db/unique`        |                                                          `:db.unique/identity`, `:db.unique/value`, `:db.unique/false` |
| `:db/sort`          |                                                             `:db.sort/sorted-set` `:db.sort/avl-set`, `:db.sort/false` |
| `:db/index`         |                             `:db.index/hash-map`, `:db.index/sorted-map`, `:db.index/avl-map`, `:db.index/false` |
| `:db/ave-form`      |                                               `:db.ave-form/single-e`, `:db.ave-form/eset`, `:db.ave-form/false` |

Note especially the property `:db/ave-form`. It tells us how the attribute is represented in the AVE index:
* `:db.ave-form/single-e` refers to form **[AVE Form {a {v e}}](#ave-form-a-v-e)**
* `:db.ave-form/eset` refers to form **[AVE Form {a {v #{e1 e2 ...}}}](#ave-form-a-v-e1-e2-)**

The rest of the properties are described in the **[Schema Section](#schema)**.

## Schema Predicates

The following schema predicates are an alternative to `check-attr`. 

Each predicate takes an encoded schema and attribute. An encoded schema may be obtained from a database value like this `(:db/schema db)`.

The following schema predicates are available:

* `ref-type?`
* `component?`
* `cardinality-many?`
* `unique-identity?`
* `unique?`
* `ave-form-single-e?`
* `ave-form-eset?`

## ID Predicates

The following id predicates can help identify the type of id that a value in the id position represents:
* `entity-id?`
* `tempid?`
* `lookup-ref?`