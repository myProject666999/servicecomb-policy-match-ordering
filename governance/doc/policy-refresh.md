# Policy matching, configuration refresh and processor cache

This note records what the code did before the change, what the change does now, and the
trade-offs that were taken. Every claim about the previous behavior was checked against the code
and pinned down by a failing test before the fix, see "Verification" below.

## 1. Policy precedence

### Before

`AbstractPolicy.compareTo` was `this.order - o.order`. `MatchersManager.match` copied
`policies.values()` into a list, sorted it with that comparison and returned the first policy whose
`checkMatch` succeeded.

Consequences, all reproduced by tests on the old code:

* **Overflow.** `Integer.MIN_VALUE - 1` overflows to a positive value, so a policy with order
  `Integer.MIN_VALUE` sorted *after* a policy with order `1`. Selecting `rule-min` over `rule-one`
  failed.
* **Ties depend on the map iteration order.** With equal orders the subtraction returns 0 and
  `List.sort` is stable, so the winner was whichever policy happened to come first in the
  `HashMap`, i.e. it depended on hashing. With `rule-a` and `rule-b` both order 0 the result was
  `rule-b` for one insertion order.
* **Intransitivity.** For orders `1`, `Integer.MIN_VALUE`, `0` the pairwise comparison is not
  transitive, so the sort result was ill-defined and again depended on the input order.

### Now

* `Integer.compare(this.order, o.order)` removes the overflow. `Integer.MIN_VALUE` sorts first and
  `Integer.MAX_VALUE` sorts last.
* Equal orders are ordered by policy name (`StringUtils.compare`), which makes the comparison total
  and the winner deterministic and independent of `HashMap` iteration order.
* `MatchersManager` reports each group of equal-order policies once with a warn log; repeated
  requests for the same group do not log again, and a changed group is reported again.

### Trade-off and compatibility

* A configuration that relied on the implicit iteration order for tied policies now gets a fixed
  result, which may differ from what it got before. Configure distinct `order` values when a
  specific precedence matters. Policies without `order` still default to 0.
* `rule-a` wins over `rule-b` for equal order, because names are compared lexicographically.

## 2. Configuration refresh

### Before

`GovernanceProperties.parsedEntity` was a plain `HashMap` published once. On
`onConfigurationChangedEvent` each changed key was removed and re-parsed in place, on the event
thread, while request threads read `values()` from the same map.

Two problems, both reproduced:

* **A reader can observe the gap between remove and put.** With a refresh frozen inside
  `parseEntityItem`, a request sees a map without `rule-a`. With `rule-z` configured as a fallback
  the request is then served by `rule-z` even though `rule-a` was always valid.
* **A failed parse deletes a working policy.** `parseEntityItem` returns `null` for an illegal yaml,
  but the old code had already removed the entry, so a typo removed `rule-a` entirely and, through
  the handler refresh below, its processor too.

### Now

The change is built on a copy and published as a whole (`parsedEntity = updated`, field is
`volatile`). A request thread therefore sees either the whole previous snapshot or the whole new
one. Within one event all keys become visible together.

Failure handling is decided per key:

| Situation | Behavior |
| --- | --- |
| Value removed or blanked | entity removed (previous behavior) |
| Illegal yaml (cannot be parsed) | **previous valid entity kept**, warn log names the key |
| Entity present but `isValid()` false | entity removed (previous behavior) |
| `services` does not match this service | entity removed (previous behavior) |

For a new key with no previous value even an illegal yaml cannot keep anything; it warns and is
skipped.

### Trade-off: copy-and-replace vs. concurrent container

Copy-and-replace was chosen. A `ConcurrentHashMap` would keep per-key visibility and avoid
allocating a copy, but each changed key is published separately, so the remove/put gap remains —
this is exactly what makes the `rule-a`/`rule-z` example above choose `rule-z`. Copy-and-replace
is the only one of the two that satisfies that acceptance example.

### Trade-off: atomicity across `matchGroup` and policy updates

One event that changes both `matchGroup` and a policy is applied by different properties objects;
there is no cross-object transaction. A request can therefore observe a new matching rule together
with an old policy (or vice versa) between the two subscriptions. This is **not** made atomic: the
requirement is not required by the examples and would need an event-scoped commit across all
properties. Treated as acceptable pending confirmation.

## 3. Processor cache alignment

### Before

Each handler cached a `Disposable` keyed by `createKey(...)`. On a configuration event it removed
the entry through `processors.remove`, but that removal was not synchronized with `getActuator`,
which created and cached processors under `lock`. The interleaving from the task description
therefore stayed possible:

1. request thread matches `rule-a` with rate 10;
2. event thread replaces the policy and removes the cached processor;
3. request thread finds no processor and caches one built from the rate-10 policy it still holds.

After that every request used the stale limiter until the next change or the 10 minute expiry.
Reproduced by a test that fails on the old code (`rate` stayed 10 after a refresh to 20).

### Now

Two complementary mechanisms:

* **Version stamp.** Every successfully parsed entity gets a monotonic version
  (`Configurable.version`), and a `Disposable` remembers the version it was created from. A cached
  processor is only reused when its version equals the version of the currently matched policy.
* **Re-decision under the creation lock.** In `getActuator`, when no current processor is found, the
  policy is matched again inside the same lock that guards removal. Version stamping alone is not
  enough: an outdated policy can have the same `order`/name and therefore the same cache key, so a
  request could republish a processor built from the stale policy after the refresh created a fresh
  one. Re-reading the properties under the lock removes that window. `onConfigurationChanged`
  removes processors under the same lock.

The "applying new policy" log now includes `(policy version N)`, so the log shows which version the
processor was built from.

The request that overlaps a refresh may use the old or the new parameters; every subsequent request
uses the new processor. A processor that was replaced stays usable by calls that already hold it.
