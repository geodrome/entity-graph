# Technical Design Notes

These are a collection of notes about the reasoning behind some of the technical design decisions. Generally, choices were biased to be more restrictive rather than permissive, as relaxing the restrictions is less likely to lead to breaking changes than tightening restrictions.

## Schema

* Map schema definition, with keys as attribute names, was chosen rather than copying datomic's vector of maps.
  * Map schema eliminates duplicate attribute names.
* Initial instinct was to match Datomic style to support schema reuse, but this idea was dismissed as the systems are too different.

## Failed Optimization Attempts

The following optimizations seemed promising, but failed to deliver.

* Use transients/reducers in `prepare-tx-data` was attempted (e.g. combine `replace-tempids-list` and `replace-ref-ids-list`), but didn't yield a significant performance benefit.
* Updating EAV and AVE indexes in parallel might offer a real perfromance gain, but:
  * Javascript environment is single-threaded 
  * Introduces complexity of webworkers

## Indexing

Datomic was the inspiration for EntityGraph indexes, but with considerable modifications. 

Datomic has the following indexes:
* EAV, AEV for all datoms.
* AVE for unique and index attrs.
* VAE for reference attributes

In EntityGraph, the main distinctions are that indexes only exist in-memory and the index data structure is a nested map.

EntityGraph indexes:
* EAV index for all attributes and an AVE index for reference and unique attributes, plus any other attributes that are specified by the schema.
  * Since we wish to economize on memory use, we don't automatically index everything in the AVE index.
* The original design had a VAE index, but AVE index proved sufficient
  * Because the index data structure is a nested map, don't have to scan Vs; just go to A and then to desired V.
* AEV index was not considered because EAV/AVE indexes are sufficient:
  * AEV index in datomic helps scans in E order for fixed A. Map indexes don't require scans of EAV index.
  * To get all entity ids that contain `:attribute`, we call `(vals (get (:db/ave db) :attribute))`.
  * To get all values for `:attribute`, we call `(keys (get (:db/ave db) :attribute))`.

## Nested Map Restrictions

In Datomic, reference to the nested map must be a component attribute or the nested map must include a unique attribute. Of note, the unique attribute can subsequently be retracted.

Datomic documentation offers this justification: "This constraint prevents the accidental creation of easily-orphaned entities that have no identity or relation to other entities."
https://docs.datomic.com/cloud/transactions/transaction-processing.html#nested-maps-in-transactions

This constraint was copied, despite occasionally causing some inconvenience, as it can be relaxed in the future without breaking any code.

## NanoIDs for entity ids

Currently sequential integers are used for entity ids, but UUIDs/NanoIDs (https://github.com/zelark/nano-id) were also considered:
* Improved security - not revealing the sequential order.
  * May not be relevant for in-memory app state storage.
* UUIDs or NanoIDs can serve as globally unique identifiers.
  * For globally unique identifiers prefer to use a `:db.unique/identity` attribute (e.g. `:global-id`).
* NanoIDs are less performant than integer ids, though the ultimate difference in performance may not be important.
* Since NanoIDs and tempids are both strings, transactions would have to distinguish between them and tempids:
  * Solution: Length check
    * NanoIDs are of length 21, but tempids might be too.
  * Solution: Wrap nanoids in another class like with `deftype` or `defrecord`
    * Further performance and memory footprint penalty
* NanoIDs take up more memory per id, especially if wrapped with `deftype` or `defrecord`


## Leaving `{:db/id id}` in EAV index

When all attributes for an entity are retracted, the entity can either be removed from the EAV index entirely or a `[id {:db/id id}]` entry can remain. It was decided to remove the entity from the EAV index. The decision was guided by this discussion on invalid ids: https://groups.google.com/g/datomic/c/hnOLG-fhZOU/m/RZvLlrGajHIJ

The following points guided the decision:
* The performance penalty for checking that all attributes for an entity are retracted is negligible.
* Can end up with many "abandoned" `[id {:db/id id}]` entries in EAV, taking up space.
* Keeping `:db/id` allows us to detect if an entity id exists or has ever existed historically (call these "valid" entities):
  * Can enforce only valid entity ids in the entity position in assertions, but wouldn't expect user to use a non-existent entity id unless they meant to create a new entity with a keyword entity id for easy programmatic access.
    * Decided to "trust the user"
    * Use of non-existent integer id is considered a user error
  * Can enforce only valid entity ids in the value position (for reference attributes) in assertions, avoiding dangling refs (pointing to entities that are invalid). 
    * Dangling refs allowed in Datomic - this choice was copied - "trust the user".
    * A reference attribute pointing to a non-existent entity id is not a big problem. Not worth the hassle.
* If we decide to keep `[id {:db/id id}]` when all attributes for an entity are retracted, what to do for `:db/retractEntity` operation?
* When asserting map form entities, there is a performance optimization: for non-existent entities we can just `assoc` the map directly in the EAV index 
  * Leaving {:db/id id} negates this optimization for previously retracted entities
    * But it is most useful for loading data into an empty database.
* Removing `{:db/id id}` ensures that nobody relies on checking for `{:db/id id}` vs `nil` in EAV. However, if it's added later may still break code checking for `nil` and finding `{:db/id id}`.

## Pull

### Pull Empty Results

What should `pull` return when an entity id is not in the database? Should it return `nil`, `{}`, or `{:db/id id}`. This decision was interrelated with the decision above: whether to leave `{:db/id id}` in the EAV index when all attributes for an entity are retracted.

* When an entity id is not in the database `pull` returns `{:db/id id}` when `pattern` is wildcard `'[*]` or `:db/id` is specified in pattern.
  * Had we kept `[id {:db/id id}]` for historical entities, would have had the option to treat them differently from never existing entities, but there is no clear need to do this.

### Pull Results - Reference Values

For reference values a map of form `{:db/id id}` is returned rather than just `id`. This decision was guided by the following considerations:
* Visually it offers more clarity (easy to spot that it's a reference)
* Using `{:db/id id}` offers consistency between component and non-component reference attributes
* The performance hit is negligible: in one test wrapping 10k ids took 4 msecs, 100K ids 29 msecs

### Combining wildcard with join attribute specs in pull pattern

When combining wildcard with join attribute specs in a pull pattern, an issue arises: Should the wildcard attribute spec overwrite any result that's accumulated so far? 

For example if we have the pattern `[{:person/friends 6} '*]`, we begin with the (recursive) join `{:person/friends 6}`, but then comes the wildcard `'*`. Should we overwrite the value in the result under `:person/friends` with whatever the wildcard returns?

It was decided that the wildcard attribute spec should not overwrite any previous join attribute specs as that defeats the point of specifying any join attribute specs in the pattern to the left of the wildcard pattern.

## Checking Entity IDs in Assertions

Entity ids in assertions can be checked for their current (and possibly historical) presence in the database, but should they be? This is interrelated with the decision not to keep `{:db/id id}` in EAV index when all attributes of an entity are removed.

* Performance penalty for this check would be negligible (based on tests).
* Checking only makes sense if `[id {:db/id id}]` entries are kept for historical entities.
  * Otherwise, once all attributes are removed from an entity, transactions referencing that entity would fail.
  * Could also check `(< id (:db/next-id db))` to check the historical existence of an entity id, but this relies on entity ids being sequential integers and would no longer work if entity ids are switched to UUIDs or NanoIDs or if the integers are no longer sequential.
* Checking could prevent non-existent integer ids being specified in transaction data.
  * Does not apply to keyword entity ids because the correct behavior with keyword entity ids is to add them if they don't exist!
  * If a new integer id is used in an assertion, it will create a new entity without updating `:db/next-id` 
    * Eventually `:db/next-id` will "catch-up" and the entities will be merged 
    * The cost of this mistaken use of new integer id would be borne by the user.
    * This mistaken use not likely
  * This mistaken use is of lower likelihood if integer entity ids are switched to NanoIDs or UUIDs, but still possible in theory.

It was decided not to check entity ids in assertions. Neither for their current nor historical presence.

## One Parent Per Component Constraint

It was decided to enforce one parent per component constraint, despite Datomic not enforcing it.

* Semantically component entities can only have one parent, though Datomic does not enforce this constraint.
  * This is somewhat perplexing, but there is likely a good reason for this - possibly performance or complexity
  * Neither performance, not complexity are obstacles in EntityGraph
* While in Datomic it is possible to end up with multiple parents for the same component entity, starting with the component entity id and navigating backwards to parent via reverse component attribute seems to only return the latest asserted parent.
* The performance cost for enforcing this in EntityGraph is as follows:
  * For each assertion of a component attribute-value, a lookup of the value in AVE index under each component attribute in the schema
  * If there are no component attributes in the schema there is no performance cost

The following discussions informed this decision:
https://datomic.narkive.com/1HfrgEI5/cardinality-many-iscomponent-and-reverse-relationships
https://groups.google.com/g/datomic/c/wqMWGY39EGk/m/4DYHMYNUdXQJ
https://groups.google.com/g/datomic/c/wY7Hq2KwB2E/m/qpqRUXEeRiEJ

## Transaction Functions

Transaction functions don't make sense in EntityGraph as it is an in-memory database and Clojure's concurrency facilities can be used.

## Writing to Storage

Currently writing the database to storage is not supported. The database was designed to fully reside in-memory. If in future writing to storage is to be undertaken, the following considerations must be kept in mind: 

* All values in the database must be serializable
* When writing a sorted set or sorted map to disk, must ensure it is read back as a sorted data structure. 
  * In particular, sorted sets can be used as values in EAV index for cardinality many attributes 
  * In particular, sorted maps can be used in AVE index
  * Any other sorted collection values 
  * See Saving+reading sorted maps to a file in Clojure: https://stackoverflow.com/questions/17347836/savingreading-sorted-maps-to-a-file-in-clojure
* The schema must also be written to disk. 
* Note that currently, once created, the schema cannot be modified as it is intended to last for the duration the program
  * May need to consider carefully schema modification of storing db to disk us undertaken
    * Some schema changes would be more easily accommodated than others
    * The straightforward option is to delete and rewrite the database to disk after each schema modification

## Would creating an assertion set offer benefits?

When preparing tx-data for transaction `retraction-set` is created to avoid asserting and retracting same `[e a v]`. This is checked in `check-db-constraints-[one/many]`. 

One then wonders if creating `assertion-set` might be profitable, but it doesn't offer much gain. Relying on a set of `[e a v]` tuples and processing assertions one tuple at a time negates the benefit of directly `assoc`ing map form tx-data to EAV index (instead of processing `[e a v]` tuples one by one).

## Enable Independent Processing of Assertions And Retractions

Currently, in `transact`, processing of retractions must come before processing of assertions because the code that checks for constraint violation relies on this order of operations. Specifically uniqueness constraint checks and one parent per component checks.

It would be possible to instead rely on `retraction-set` and `entity-retraction-ids` to check these same constraints. This would enable independent assertions and retractions, thus making them parallelize. However, the primary target for EntityGraph is ClojureScript (web apps) and leveraging Web Workers may not offer sufficient performance benefit, especially considering that performance does not appear to be an issue so far.

## Post Transaction Checks

There's a tradeoff between ensuring the database is always in a valid/consistent state and the corresponding performance penalty. It is particularly undesirable to burden correct programs with the performance cost of checks. Also, what is and isn't a valid state requires careful consideration. 

Some invalid database states are more problematic than others. Some constraints (such as prohibition against nil values) are particularly problematic. Those constraints are enforced. 

Other invalid states are less problematic. Dangling references might be an example. In those instances the constraint is not enforced unless it's particularly simple to implement.

Finally, there is the option of users checking whatever constraints they want to enforce after calling `transact`. But this is liable to be costly.

## Sorted Set Values

Including the option of sorted sets for cardinality many attributes was carefully considered. The decision to include this feature was made after determining that **there is no performance penalty if this feature is not used**. In other words, if the user doesn't declare any attributes with a `:db/sort` property, there is no performance cost at all.

The following performance tests confirm this:
* Map form assertion of 10 entities and 20k entities 
* Map form assertion overwriting 10 entities and 20k entities
* List form assertions of 10 entities and 20k entities
* List form assertions overwriting 10 entities and 20k entities
* Retraction of 10 entities and 20k entities

Here are the benchmark numbers in milliseconds first vector with, second vector without support for sorted sets:
* `[4420 5334 3941 4056 7861 10935 4159 4483 6293 11934]`
* `[4387 5659 3938 4131 7791 10496 4139 4444 6269 12123]`

It's apparent at a glance there is performance difference to speak of.