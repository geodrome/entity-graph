# EntityGraph

EntityGraph is an in memory immutable data store designed for web applications, with likely other use cases. It is available for Clojure and ClojureScript.

Based on the triple store concept (entity, attribute, value), data is stored in the form of entities in the EAV index (entity, attribute, value). Select attributes are also indexed in the AVE index (attribute, value, entity). Only indexing select attributes in the AVE index gives the option to economize on memory.

The indexes are implemented as nested Clojure maps, accessible with Clojure's functions. Attributes can be of any type (including collections) and don't need to be declared in the schema unless special behavior is needed.

For data retrieval EntityGraph offers pull-style graph query support, which satisfies most use cases. There is no datalog, sql or any other query language support, but since the indexes are Clojure maps, any number of querying solutions could be implemented on top of the indexes. The user may also write custom functions to retrieve data from indexes without the need to parse queries. This is expected to be a rare use case.

## Features

* Tempids, Keyword entity ids for nice programmatic names
* Cardinality one and many attributes
  * Sorting within entity for cardinality many attributes (using default or custom comparator)
* Reference attributes and component entities
* Unique identities, unique values, and lookup refs
* Nested entities in assertions
* AVE index sorted by custom comparator
* Pull queries with: wildcarding, nesting, joins, forward/backward attribute nav, recursive pulls

## Non-Goals

**The following are not in scope of the project:**
* EntityGraph makes no effort to synchronize data between client and server and considers this an orthogonal concern.
* The word "database" is used throughout, but keep in mind that there is no storage layer.
* Reactive queries are not supported.

## Dependency Information

[deps.edn](https://clojure.org/guides/deps_and_cli) dependency information:

`entity-graph/entity-graph {:mvn/version "0.1.0-SNAPSHOT"}`

[Leiningen](https://github.com/technomancy/leiningen) dependency information:

`[entity-graph/entity-graph "0.1.0-SNAPSHOT"]`

## Status

* The feature set is complete, though additional features and enhancements are possible in the future. 
* In case of unexpected issues, every effort will be made to avoid breaking changes by moving to new names rather than by breaking existing names. 
* The code is reasonably well tested, but there has been minimal production use, so some issues may arise.

## Documentation And Tutorial

> **Depending on your preference, you may either start with the tutorial or read the documentation first to learn about concepts and features.**

The **[Hands-on Tutorial](docs/TUTORIAL.md)** introduces the majority of the features in as succinct a manner as possible.

**[Documentation](docs/DOCUMENTATION.md)** describes the concepts and features of EntityGraph. **[Schema](docs/DOCUMENTATION.md#schema)** and **[Indexes](docs/DOCUMENTATION.md#indexes)** sections might be especially useful.

The thought process behind many of the technical design decisions is captured in **[TECHNOTES.md](docs/TECHNOTES.md)**.

## Quick Example

This is just to give you a feel:

```clojure
;; Create a database
;; This schema has just one attribute, which is to be indexed in the AVE index in a standard Clojure hashmap
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

## Performance vs. DataScript and ASAMI

Performance was measured vs. DataScript and ASAMI. Those two databases are roughly in the same category. For the most part performance as compared with DataScript and ASAMI is favorable. The benchmarks were performed on my M2 MacbookPro. 

The exact code is here: [entity-graph.benchmakr-vs](test/entity_graph/benchmark_vs.cljc).

It must be acknowledged that these benchmarks, like all benchmarks, are imperfect. These are just a few quick and dirty benchmarks in an attempt to get a rough idea of how performance compares among the three databases.

The schema used for EntityGraph indexed all attributes in a sorted AVE index, so performance for asserting entities in the database can be compared fairly vs. the other databases.

The tests were run in both Clojure and ClojureScript and the results largely mirrored each other. EntityGraph was notably faster when asserting new entities into the database and when using pull to retrieve data from the database. 

When querying data without pull (with datalog for DataScript and ASAMI, with custom data retrieval functions for EntityGraph), EntityGraph outperformed ASAMI for a simple query, but ASAMI outperformed EntityGraph for a more complex query. Both ASAMI and EntityGraph outperformed DataScript by an astronomical margin. Either something is seriously wrong with the query benchmark or DataScript queries are particularly slow.

### Clojure Results

* When asserting 20,000 entities into an empty database, **EntityGraph is twice as fast as DataScript and ASAMI**.
* Pull performance for wildcard pattern is **~6x faster** compared to DataScript. For pulling a single attribute EntityGraph is **~2x faster** than DataScript. ASAMI doesn't support pull.
  * Pulls were done from a database of 20,000 entities.
* For a simple query EntityGraph is **~100x faster than ASAMI and ~5,000x(!) faster than DataScript**
  * Queries were done from a database of 20,000 entities.
* For a more complex query EntityGraph is **~3x slower than ASAMI**, but still **more than 10x faster than DataScript**.
  * Queries were done from a database of 20,000 entities
* For EntityGraph the queries were written as custom data retrieval functions since no declarative query language is supported by EntityGraph.

### ClojureScript Results

* When asserting 20,000 entities into an empty database, **EntityGraph is twice as fast as DataScript and ASAM**. 
* Pull performance for wildcard pattern is **~4x faster** compared to DataScript. For pulling a single attribute EntityGraph is **~2x faster**. ASAMI doesn't support pull.
  * Pulls were done from a database of 20,000 entities.
* For simple query **EntityGraph is ~100x faster than ASAMI and a few thousand times faster than DataScript**.
  * Queries were done from a database of 20,000 entities
* For a more complex query EntityGraph is ~3.5x slower than ASAMI, but still more than 10x faster than DataScript
  * Queries were done from a database of 20,000 entities
* For EntityGraph the queries were written as custom data retrieval functions since no declarative query language is supported by EntityGraph.

# License
Copyright © 2021–2023 Georgiy Grigoryan

Licensed under Eclipse Public License (see LICENSE).

