# COSH: Engineering Deep Dive

This document records an audit of COSH, what it found, what was fixed, and what the code does now. Every number here was measured on this repository. Raw output is in [`benchmarks/RESULTS.md`](benchmarks/RESULTS.md) and test output comes from `mvn test`.

**Contents**

0. [Verdict on the four resume claims](#0-verdict-on-the-four-resume-claims)
1. [Audit findings: bugs found and fixed](#1-audit-findings-bugs-found-and-fixed)
2. [How a `get()` and a `put()` flow through the system](#2-how-a-get-and-a-put-flow-through-the-system)
3. [Directory and file atlas](#3-directory-and-file-atlas)
4. [Architecture and data structures](#4-architecture-and-data-structures)
5. [Design decisions, with the alternatives considered](#5-design-decisions-with-the-alternatives-considered)
6. [COSH vs Redis / Valkey: an honest comparison](#6-cosh-vs-redis--valkey-an-honest-comparison)
7. [Verified benchmark results](#7-verified-benchmark-results)
8. [Known limitations and roadmap](#8-known-limitations-and-roadmap)

---

## 0. Verdict on the four resume claims

| # | Claim | Initial commit (`5737cc1`) | Current tree |
|---|---|---|---|
| 1 | "Modular distributed in-memory cache implementing consistent hashing for request routing across cache nodes" | **Partly true.** The consistent-hash ring with virtual nodes existed. Nothing routed requests through it: it was only exercised in a unit test. The nodes could not evict (`LocalCache` threw `IllegalStateException` at capacity). **"Distributed" was false**: there was, and still is, no networking. Every node is an object in one JVM. | Accurate if worded as an **in-process, sharded (multi-node) cache**. `ShardedCache` routes every request through the ring and migrates keys when nodes join or leave. Each node has its own bounded cache, lock and policy. Still not networked. |
| 2 | "Cache eviction using doubly linked lists and ConcurrentHashMap to support efficient node reordering, O(1) removal, and thread-safe concurrent cache access" | **O(1) true. Thread safety false.** `get()` reordered the list outside the cache lock. Under contention, evicted nodes were put back in the list, and a capacity-100 cache grew to 400 entries. The repo's own concurrency test failed on this commit. | **True.** One lock domain per cache, a liveness check, and volatile values. 21 parameterized stress tests (16 threads each) pass 10/10 reruns and check list invariants. 300,000 evictions at 100K capacity take 13–29 ms. |
| 3 | "Pluggable eviction framework with sliding-window adaptive eviction and concurrent access handling across cache workloads" | **Overstated.** The strategy interface existed, but with a single implementation. The "sliding window" was a fixed-length queue with an O(window) `contains()` scan on every eviction. Nothing adapted. | **True.** Three O(1) policies (LRU, LFU, SlidingWindowAdaptive) behind one interface. The window length tunes itself by hill-climbing on the observed hit rate (60 adjustments per 120K-request run including the warm prefix, shown in benchmark output). |
| 4 | "Analyzed 100K-operation benchmark workloads using cache hit rate and P50/P95 latency to evaluate system performance and stability" | **Weakly true.** One JUnit test, one workload (a two-bucket 80/20 mix, not Zipf), one policy, no baseline, no throughput, no P99. The reported P50 of 42 ns is exactly one tick of the macOS timer. | **True.** A CLI runner replays 100K measured requests × 4 workloads × 4 policies, plus routing-overhead and 1–8-thread throughput sections. It reports hit rate, ops/s, P50/P95/P99, and the timer granularity. Seeded and deterministic. |

---

## 1. Audit findings: bugs found and fixed

| # | Severity | Where (original) | Defect | Evidence | Fix |
|---|---|---|---|---|---|
| 1 | **Critical** | `ConcurrentLRUCache.get`, `PluggableCache.get` | **Lost update between a read's reorder and an eviction.** `get()` called `list.moveToFirst(node)` without the cache's write lock. If `put()` evicted that node at the same moment (`removeLast()` unlinks it, then `map.remove`), `moveToFirst` saw a node with null links, skipped the remove, and **`addFirst` put the evicted node back in the list**. The orphan was evicted later and its `map.remove(key)` deleted the *current* entry for that key, or nothing at all. List and map diverged, and `map.size()` grew without bound. | Scratch reproduction with 16 threads on capacity 100: `map.size()=400, list=407`. The repo's own `ConcurrentWorkloadTest` **failed** on the initial commit ("Cache size exceeded capacity limit"). | Every map/policy mutation, including a read's reorder, now runs under one per-cache `ReentrantLock` ([`PluggableCache.java:92-123`](src/main/java/com/cosh/core/PluggableCache.java)). A `live` flag is checked under the lock, so a reader that raced an eviction records a miss and never touches the dead node's links. |
| 2 | High | `CacheEntry.value` | A plain field was written under the lock on update and read with no lock in `get()`. That is a Java Memory Model data race, and a reader could see a stale value indefinitely. | Code inspection (JLS §17.4). | `private volatile V value` ([`CacheEntry.java:20`](src/main/java/com/cosh/eviction/CacheEntry.java)). `noStaleOrTornValuesForOwnedKeys` checks read-your-writes under 16 threads. |
| 3 | High | `SlidingWindowAdaptivePolicy.evict` | `recentAccessWindow.contains(key)` on a `ConcurrentLinkedQueue` is an **O(window) linear scan per eviction** (5,000 nodes in the original benchmark), which contradicts the O(1) claim. | Code inspection. The new `evictionIsConstantTimeNotLinear` test would need more than 10¹⁰ node visits with a linear scan. | Per-key counts in a `HashMap<K,int[]>` give an O(1) lookup ([`SlidingWindowAdaptivePolicy.java:45-46`](src/main/java/com/cosh/eviction/SlidingWindowAdaptivePolicy.java)). |
| 4 | High | `SlidingWindowAdaptivePolicy` | **Nothing adapted, and the protection was degenerate.** `onPut` recorded an access, so every new key was "recent". With window 5,000 ≫ capacity 200, almost every LRU tail was in the window, so eviction usually just evicted the second-to-last entry. The window counter's `offer`/`increment`/`poll` sequence was also not atomic. | Code inspection. | A frequency threshold (≥2 requests within the window), bounded probes, a fill that does not count as a request, and a real **hill-climbing window size** (§4.4). |
| 5 | High | `CacheNode` → `LocalCache` | Cluster nodes wrapped `LocalCache`, a placeholder ("until Phase 4/5") that **threw `IllegalStateException` at capacity**. A sharded deployment would have crashed on the first full node. | `LocalCache.java:19-21` in `5737cc1`. | `CacheNode` wraps a `PluggableCache`. `LocalCache` and its test were deleted. |
| 6 | Medium | `ConsistentHashingRouter`, `MD5HashFunction` | (a) `TreeMap` mutated during concurrent routing: not thread-safe. (b) `tailMap()` + `get()` is two lookups, and a race between them could return null. (c) `ring.put` silently **overwrote** another node's colliding virtual-node point, and `removeNode` **deleted points owned by other nodes**. (d) A 32-bit ring. (e) `getBytes()` used the platform charset, so routing could differ across machines. (f) `MessageDigest.getInstance` on every call. | Code inspection. `hashCollisionNeverStealsOrDeletesAnotherNodesPoint` uses a constant hash to force collisions. | A copy-on-write sorted `long[]` ring with a volatile snapshot (§5, D7). `putIfAbsent` / `remove(key, owner)` semantics. 64-bit positions, explicit UTF-8, a thread-local digest, and an optional fast hash. |
| 7 | High | (missing) | **No client path and no rebalancing.** Nothing connected the router to caches. A naive ring without migration also **brings stale values back**: put v1 → add node B (key now owned by B) → put v2 → remove B → get returns **v1**. | `staleValueIsNotResurrectedAfterTopologyChanges`. | `ShardedCache` migrates exactly the re-owned keys on add and remove ([`ShardedCache.java:43-95`](src/main/java/com/cosh/core/ShardedCache.java)). The test asserts that the number moved equals the number of keys that changed owner. |
| 8 | Medium (introduced, then fixed, in this audit) | `ShardedCache` (first fix) | The first version of the rebalancing fix guarded data operations with a `ReentrantReadWriteLock`. Its read side CASes one shared word and updates per-thread hold counters on every request, so the line ping-pongs between cores. | Experiment: 8 threads, 8 shards: **2.4M ops/s with the RW lock vs 5.7M with it removed**. | `TopologyLock`: a striped, 128-byte-padded big-reader lock ([`TopologyLock.java`](src/main/java/com/cosh/core/TopologyLock.java)), with its own mutual-exclusion test. |
| 9 | Low | `ConcurrentLRUCache`, all caches | Capacity ≤ 0 was accepted (`ConcurrentLRUCache`). Null keys and values surfaced as NPEs from deep inside `ConcurrentHashMap` internals. | `PluggableCacheTest`. | Explicit validation and documented null semantics (`null` from `get` always means miss). |
| 10 | Low | `DoublyLinkedList` | It had its own internal `ReentrantLock` while callers also held the cache lock. There was no deadlock (the lock order was consistent and the lock reentrant), but the inner lock gave a false sense of safety: it made each pointer update atomic, not the map+list update together, which is what bug #1 broke. | Code inspection. | The list is documented as not thread-safe and is guarded by the owning cache's lock. One lock domain. |
| 11 | Low | `ConcurrentLRUCache` | A copy of `PluggableCache`'s logic, carrying the same race. | Diff. | Now `PluggableCache` + `LruPolicy` ([`ConcurrentLRUCache.java`](src/main/java/com/cosh/core/ConcurrentLRUCache.java)). |
| 12 | Medium | tests / benchmark | `InitialSetupTest` asserted `assertTrue(true)`. `LocalCacheTest` tested the stub's exception. There were no distribution, key-movement, LFU or O(1) tests. The benchmark ran inside `mvn test`, covered one policy, and reported P50 at timer granularity without saying so. | — | 75 tests in 9 classes (§3). A standalone CLI benchmark (§7). |

**Deadlock analysis of the original.** `put` took `writeLock → listLock`, and `get` took only `listLock`. The `DoublyLinkedList` methods re-entered `listLock` (it is reentrant), so the lock order was consistent and there was no deadlock. The defect was atomicity (bug #1), not deadlock. The current code has exactly one lock per cache, plus the topology lock, which is always taken *before* a shard lock and never while holding one, so no lock-order cycle exists.

---

## 2. How a `get()` and a `put()` flow through the system

### 2.1 Through the cluster (`ShardedCache`)

```text
client.get(k)
 │
 ├─ ShardedCache.get(k)                                   core/ShardedCache.java
 │    token = topologyLock.readLock()   ← increments this thread's padded stripe; no shared CAS
 │    node  = router.routeNode(k)       ← routing/ConsistentHashingRouter.java
 │    │        r = ring                 (one volatile read: an immutable snapshot)
 │    │        h = hash(String.valueOf(k))   MD5 (first 8 bytes) or FNV-1a+fmix64
 │    │        i = Arrays.binarySearch(r.points, h); i<0 → -i-1; i==n → 0   (clockwise successor, wrap)
 │    │        return r.owners[i]
 │    v = node.getCache().get(k)        ← PluggableCache, see 2.2
 │    topologyLock.readUnlock(token)
 └─ return v                            (null = miss; caller loads from the source of truth and put()s)
```

`put(k, v)` and `remove(k)` take the identical route. `addNode` / `removeNode` take the topology **write** side. That waits for every reader stripe to drain, updates the ring, migrates the re-owned keys, and releases.

### 2.2 Inside one node (`PluggableCache`)

```text
get(k)                                              core/PluggableCache.java:92
  requireNonNull(k)
  e = map.get(k)                     ← ConcurrentHashMap, lock-free
  STRICT mode:
    lock.lock()
      if e != null && e.live:        ← live is false if evicted after our lookup
          policy.onGet(e)            ← LRU: moveToFirst · LFU: move to freq+1 bucket
          return e.value             ← SlidingWindow: moveToFirst + window++ + hit sample
      else:
          policy.onMiss(k)           ← SlidingWindow: window++ + miss sample
          return null
    lock.unlock()
  BEST_EFFORT mode:
    if lock.tryLock(): record as above, unlock       (skipped under contention: lossy recency)
    return e == null ? null : e.value                 (the value read itself never waits)

put(k, v)                                           core/PluggableCache.java:62
  requireNonNull(k, v)
  lock.lock()
    e = map.get(k)
    if e exists:  e.value = v (volatile);  policy.onUpdate(e);  return
    if map.size() >= capacity:                        ← evict BEFORE insert: size never exceeds capacity
        victim = policy.evict()                       ← O(1): list tail / min-freq bucket tail / ≤4 probes
        map.remove(victim.key, victim)                ← conditional remove: only that exact entry
        victim.live = false
    e = new CacheEntry(k, v); e.live = true
    map.put(k, e); policy.onPut(e)                    ← addFirst (LRU / SW) or into the freq-1 bucket (LFU)
  lock.unlock()
```

---

## 3. Directory and file atlas

```text
cosh/
├── pom.xml                         Maven build: Java 17 (--release 17), JUnit 5.10.2 (api, engine, params), Surefire 3.1.2. No runtime dependencies.
├── README.md                       30-second overview: what it is, how to build, test and benchmark, headline numbers.
├── ENGINEERING_DEEP_DIVE.md        This document.
├── .gitignore                      Ignores target/, *.class, .idea/, *.iml, .DS_Store.
├── benchmarks/
│   └── RESULTS.md                  Raw, unedited output of 3 consecutive BenchmarkRunner runs plus machine context.
└── src/
    ├── main/java/com/cosh/
    │   ├── core/                   Storage, concurrency, cluster.
    │   │   ├── Cache.java              The 5-method contract (put/get/remove/size/clear). Rejects nulls, so a null return always means miss.
    │   │   ├── PluggableCache.java     The engine: ConcurrentHashMap storage + one ReentrantLock + an EvictionPolicy. STRICT / BEST_EFFORT read modes. verifyIntegrity() for tests.
    │   │   ├── ConcurrentLRUCache.java PluggableCache preconfigured with LruPolicy (kept for API compatibility).
    │   │   ├── CacheNode.java          One shard: a stable nodeId (hashed onto the ring) + its own PluggableCache.
    │   │   ├── ShardedCache.java       Cluster facade: routes each request via the ring; add/removeNode migrate re-owned keys.
    │   │   └── TopologyLock.java       Striped, cache-line-padded reader lock guarding topology vs data operations.
    │   ├── eviction/               The strategy layer: decides victim order, never touches storage.
    │   │   ├── EvictionPolicy.java     Strategy interface: onPut/onGet/onUpdate/onMiss/onRemove/evict/clear/size/checkInvariants.
    │   │   ├── CacheEntry.java         Map value and intrusive list node at once: key, volatile value, prev/next, frequency, live.
    │   │   ├── DoublyLinkedList.java   Intrusive DLL with sentinels; O(1) addFirst/remove/moveToFirst/removeLast; validate().
    │   │   ├── LruPolicy.java          Head = most recent, tail = victim.
    │   │   ├── LfuPolicy.java          O(1) LFU: frequency buckets in a sorted DLL, an LRU list per bucket (Shah, Mitra and Matani 2010).
    │   │   └── SlidingWindowAdaptivePolicy.java  LRU + sliding-window frequency second chance + hill-climbed window size.
    │   ├── routing/                Key → node mapping.
    │   │   ├── HashFunction.java       String → 64-bit ring position (functional interface).
    │   │   ├── MD5HashFunction.java    First 8 MD5 bytes, UTF-8, thread-local digest. ketama-compatible style.
    │   │   ├── Fnv1aMixHashFunction.java  FNV-1a 64 + Murmur3 fmix64 finalizer; allocation-free, about 2.5× cheaper per request than MD5 in the benchmark (P50 84 ns vs 208 ns).
    │   │   └── ConsistentHashingRouter.java  Copy-on-write sorted long[] ring of virtual nodes; binary-search lookup.
    │   └── benchmark/              Reproducible CLI benchmark (shipped in main so it runs via plain java).
    │       ├── Workloads.java          Seeded trace generators: Uniform, Zipfian(0.99), Shifting Zipfian (4 disjoint hot sets), Zipfian + 20% scan.
    │       └── BenchmarkRunner.java    Runs workloads × policies, routing overhead, and 1–8-thread throughput; prints Markdown tables.
    └── test/java/com/cosh/
        ├── routing/ConsistentHashingRouterTest.java   11 tests: determinism, empty ring, balance (CV), ~1/(N+1) movement on add, only-victim movement on remove, re-add restores, collision safety, concurrent churn.
        ├── eviction/EvictionPolicyTest.java           16 tests: shared contract × 3 policies (capacity, capacity=1, empty/clear, O(1) timeout guard) + LRU/LFU ordering, LFU min-bucket removal, update semantics.
        ├── eviction/SlidingWindowAdaptivePolicyTest.java  7 tests: second chance, expiry from window, bounded probes, fills not counted, hill-climber moves within bounds, fixed mode, factory bounds.
        ├── core/PluggableCacheTest.java               6 tests: argument validation, nulls, remove return values, no resurrection, read-mode parity, clear.
        ├── core/ConcurrentLRUCacheTest.java           3 tests: LRU order, reorder on get, 50-thread inserts.
        ├── core/ConcurrencyStressTest.java            21 tests: 16-thread mixed workload × {LRU,LFU,Adaptive} × {STRICT,BEST_EFFORT} × capacity {1,64}; sharded cluster under live topology churn; read-your-writes.
        ├── core/ShardedCacheTest.java                 6 tests: single-owner routing, migration on add (count = re-owned keys), handoff on remove, stale-value regression, validation, per-shard capacity.
        ├── core/TopologyLockTest.java                 2 tests: writer/reader and writer/writer exclusion under 14 threads; reentrancy.
        └── benchmark/BenchmarkRunnerSmokeTest.java    3 tests: every workload × policy gives consistent, deterministic metrics; trace shapes; nearest-rank percentiles.
```

Untracked local directories: `target/` (Maven output) and `.idea/` (IntelliJ project files). Both are git-ignored.

---

## 4. Architecture and data structures

### 4.1 Component diagram

```text
                         ┌──────────────────────────── one JVM ───────────────────────────────┐
                         │                                                                     │
  client thread ──get──▶ │  ShardedCache ─── TopologyLock (read side: per-thread padded stripe)│
                         │       │                                                             │
                         │       ▼                                                             │
                         │  ConsistentHashingRouter   volatile Ring{ long[] points (sorted),   │
                         │       │   binarySearch       CacheNode[] owners }                   │
                         │       │                      N nodes × 150 virtual nodes            │
                         │       ▼                                                             │
                         │  ┌──────────── CacheNode "node-2" ────────────┐   (node-0, node-1,…)│
                         │  │ PluggableCache                             │                     │
                         │  │  ConcurrentHashMap<K, CacheEntry>  ◀─────┐ │   lock-free lookup  │
                         │  │  ReentrantLock (one per shard)           │ │   guards map+policy │
                         │  │  EvictionPolicy ─┬─ LruPolicy            │ │                     │
                         │  │                  ├─ LfuPolicy            │ │                     │
                         │  │                  └─ SlidingWindowAdaptive│ │                     │
                         │  │        intrusive DoublyLinkedList ───────┘ │   same objects:     │
                         │  │   head ⇄ e7 ⇄ e3 ⇄ … ⇄ e1 ⇄ tail (victim)  │   map value = node  │
                         │  └────────────────────────────────────────────┘                     │
                         └─────────────────────────────────────────────────────────────────────┘
```

### 4.2 Lock granularity

| Lock | Scope | Held for | Taken by |
|---|---|---|---|
| `TopologyLock` read side | whole cluster | one request | every `get` / `put` / `remove` on `ShardedCache`. Uncontended: each thread bumps its own 128-byte-padded counter. |
| `TopologyLock` write side | whole cluster | one add/remove-node plus migration, O(keys on affected nodes) | `addNode`, `removeNode`, `verifyIntegrity` |
| `PluggableCache.lock` (`ReentrantLock`) | one shard | O(1) pointer work: reorder, ≤4 probes, unlink | `put`, `remove`, `clear`, and `get` (STRICT always; BEST_EFFORT only if `tryLock` succeeds) |
| `ConcurrentHashMap` internals | one bin | a single CAS or bin lock | lookups (lock-free), inserts/removes (under the shard lock anyway) |
| Router `mutationLock` | ring rebuild | O(P log P) rebuild, P = N·v points | `addNode` / `removeNode` only. Lookups never lock. |

Lock order is always `TopologyLock → shard lock`, and nothing takes a topology lock while holding a shard lock, so no deadlock is possible.

### 4.3 Virtual-node math

* **Points:** P = N·v. With N = 5 and v = 150, the ring holds 750 `long`s plus 750 references, about 12 KB, and a lookup costs ⌈log₂ 750⌉ = 10 comparisons on contiguous memory.
* **Balance:** a node's share of the ring is the sum of v arc lengths. For random points the relative standard deviation of a node's load is about 1/√v. With v = 150 that predicts ≈ 8%. **Measured:** CV = **0.068** (MD5) and **0.050** (FNV-1a+fmix64) at v = 150, vs **0.643** at v = 1 (100,000 keys, 5 nodes; `virtualNodesBalanceLoad`).
* **Movement on add:** the new node takes over the arcs just counter-clockwise of its v points, an expected 1/(N+1) of the keys, **all moving to the new node**. Measured: **19.27%** moved when adding a 5th node (ideal 20.00%), and every moved key landed on the new node.
* **Movement on remove:** only the removed node's keys move (to each arc's clockwise successor). Measured: **20.17%** moved when removing 1 of 5, and no key owned by a surviving node changed owner.
* **Collisions:** with 64-bit positions, P(any collision among 750 points) ≈ P²/2⁶⁵ ≈ 1.5×10⁻¹⁴. Collisions are still handled deterministically: the first point placed wins, and removal is ownership-checked.

### 4.4 Sliding-window adaptation

State, all guarded by the shard lock:

```text
window       : ArrayDeque<K>          the last W requested keys (hits + misses + updates; fills excluded)
windowCounts : HashMap<K,int[]>       how many times each key appears in the window   (O(1) lookup)
W            : int ∈ [Wmin, Wmax]     current window length;  forCapacity(C): Wmin=C, W0=4C, Wmax=16C
```

* **Request:** append the key, `count[key]++`, then expire from the head until `|window| ≤ W` (amortized O(1)).
* **Evict:** for at most `maxProbes = 4` rounds, if `count[tail.key] ≥ 2`, move the tail to the head (a second chance). Then evict the tail. Cost is bounded by 4, so O(1).
* **Why fills don't count:** a miss followed by a fill is one logical request. Counting both would give every one-hit wonder a count of 2 and protect it.
* **Why Wmin = C:** an LRU tail entry was last touched at least C requests ago, so a window shorter than C can never protect it. The policy is pure LRU at the bottom of the range and increasingly frequency-biased (LFU-like) toward 16C.
* **Hill climbing**, every `samplePeriod = max(1000, 2C)` reads:

```text
hr = hits / (hits + misses)                     over the last sample period
if hr < hr_prev:  direction = -direction        last move hurt → reverse
step = |hr - hr_prev| ≥ 5% ? step0              workload shifted → search widely again
                           : max(1, ⌊0.9·step⌋)  otherwise settle (geometric decay)
W = clamp(W + direction·step, Wmin, Wmax);  bounce the direction off the clamps;  trim the window
```

`step0 = (Wmax − Wmin)/16`. This is the same simple hill-climber Caffeine uses to size its W-TinyLFU admission window. Evidence that it moves: on Zipfian traffic the window ends at **8,529** (it grew toward LFU behaviour), on Shifting Zipfian at **4,654**, and on Uniform at **3,543**, each after 60 adjustments.

---

## 5. Design decisions, with the alternatives considered

| # | Decision (file:line) | Chosen | Instead of | Why |
|---|---|---|---|---|
| D1 | `CacheEntry` is both the map value and the list node ([`CacheEntry.java:18-31`](src/main/java/com/cosh/eviction/CacheEntry.java)) | Intrusive DLL | `java.util.LinkedList`, or a separate node type | A `map.get` hands you the list node directly, so unlinking is O(1) with no second lookup and no per-access allocation. `LinkedList.remove(Object)` is O(n). |
| D2 | Custom `DoublyLinkedList` + `ConcurrentHashMap` ([`DoublyLinkedList.java`](src/main/java/com/cosh/eviction/DoublyLinkedList.java)) | Hand-rolled LRU | `LinkedHashMap(accessOrder=true)` + `removeEldestEntry` | `LinkedHashMap` is not thread-safe: wrapping it in `Collections.synchronizedMap` serializes even lookups and misses. It is also LRU-only: LFU buckets and the sliding-window second chance need direct access to node links, which `LinkedHashMap` hides. |
| D3 | Sentinel head/tail nodes ([`DoublyLinkedList.java:17-24`](src/main/java/com/cosh/eviction/DoublyLinkedList.java)) | Sentinels | `null` head/tail checks | No edge cases for empty, single-element, first or last. Four pointer writes, no branches. `moveToFirst` also short-circuits when the node is already first (line 53), skipping four writes on the hottest path. |
| D4 | One `ReentrantLock` per cache guarding map and policy ([`PluggableCache.java:46`](src/main/java/com/cosh/core/PluggableCache.java)) | Coarse per-shard lock + lock-free CHM lookups | `synchronized` methods · a lock inside the list · `ReadWriteLock` | Bug #1 shows that two lock domains, or reads outside the lock, break the map/list invariant. A read *mutates* LRU order, so an RW lock's read side cannot be used for it. `ReentrantLock` over `synchronized` gives `tryLock()` for BEST_EFFORT mode and `getHoldCount` for diagnostics. Scaling comes from sharding the lock (D11), not from making one lock finer. |
| D5 | Liveness flag checked under the lock ([`PluggableCache.java:114-123`](src/main/java/com/cosh/core/PluggableCache.java)) | `live` boolean | Re-checking `map.get(key) == e` | The lookup happens outside the lock and can race an eviction. The flag is set and cleared only under the lock, so checking it costs one field read with no second hash lookup, and it is never stale while the lock is held. |
| D6 | Evict *before* insert, conditional `map.remove(victim.key, victim)` ([`PluggableCache.java:73-80`](src/main/java/com/cosh/core/PluggableCache.java)) | Evict-then-insert | Insert-then-trim | `size()` is read lock-free by monitors. Evicting first means it is **never** above capacity, even briefly (the stress test samples it continuously). The two-argument remove can only delete that exact entry object. |
| D7 | Ring = copy-on-write sorted `long[]` + `binarySearch` ([`ConsistentHashingRouter.java:51, 87-99`](src/main/java/com/cosh/routing/ConsistentHashingRouter.java)) | Immutable array snapshot behind a `volatile` | `TreeMap` (original) · `ConcurrentSkipListMap` (first fix) | Lookups run on every request, while mutations are rare. An array lookup is one volatile read plus about 10 comparisons in contiguous memory, with zero allocation. `TreeMap` is unsafe under concurrent mutation. `ConcurrentSkipListMap.ceilingEntry` chases about 10 pointers and allocates an entry object per call. A rebuild costs O(P log P) about once a minute. |
| D8 | Virtual nodes, v = 150 ([`ConsistentHashingRouterTest.virtualNodesBalanceLoad`](src/test/java/com/cosh/routing/ConsistentHashingRouterTest.java)) | 150 points per node | Raw node hashing (v = 1) · rendezvous hashing · jump hash | Measured CV 0.643 → 0.068. Removing a node spreads its keys across *all* survivors instead of dumping them on one neighbour. Jump hash needs numbered buckets and cannot remove an arbitrary node. Rendezvous hashing is O(N) per lookup. v = 150 follows ketama practice (100–200), at about 2.4 KB of ring per node. |
| D9 | MD5 truncated to 64 bits, thread-local digest, UTF-8 ([`MD5HashFunction.java:21-37`](src/main/java/com/cosh/routing/MD5HashFunction.java)) | MD5 (default) + FNV-1a+fmix64 (fast option) | `String.hashCode()` · MD5 via `getInstance` per call | `String.hashCode` is 32-bit with poor avalanche on similar labels (`node-1#0`, `node-1#1`…). MD5 matches the ketama ecosystem and is well distributed. Per-call `getInstance` is a provider lookup plus allocations. Explicit UTF-8 makes routing identical on every OS. The benchmark shows MD5 dominates routing cost (P50 208 ns vs 84 ns for FNV-1a+fmix64), so both options ship behind `HashFunction`. |
| D10 | Ring and node mutations guarded by `putIfAbsent` / `remove(k, owner)` ([`ConsistentHashingRouter.java:67, 81`](src/main/java/com/cosh/routing/ConsistentHashingRouter.java)) | Ownership-checked mutations | `put` / `remove(k)` | The original could silently steal or delete another node's point on a collision. That is rare with 64-bit positions, but a correctness hole that is cheap to close. |
| D11 | Sharding doubles as lock striping ([`ShardedCache.java`](src/main/java/com/cosh/core/ShardedCache.java)) | N independent caches, each with its own lock | One cache with internal segments (Guava style) | The same mechanism gives partitioned capacity, per-shard policies and isolation, and a natural seam for a future network boundary. The honest limit, measured in §7: in-process the routing cost (≈ 40–120 ns per request, from the routing-overhead table) is comparable to the cache operation itself, so more shards help only under heavy write/miss contention. |
| D12 | `TopologyLock`: striped, 128-byte-padded reader counters ([`TopologyLock.java:35-62`](src/main/java/com/cosh/core/TopologyLock.java)) | Big-reader lock | `ReentrantReadWriteLock` · `StampedLock` · no lock | Without a lock, a migration races client writes (stale resurrection, bug #7). RRWL's shared reader CAS cost **2.4× throughput at 8 threads** (bug #8). `StampedLock`'s read side still CASes one word. Per-thread padded slots make reads core-local. Padding is 128 bytes because Apple M-series cache lines are 128 bytes. Correctness rests on the seq-cst volatile handshake (javadoc). |
| D13 | Sliding-window counts in `HashMap<K, int[]>` + `ArrayDeque` ([`SlidingWindowAdaptivePolicy.java:45-46`](src/main/java/com/cosh/eviction/SlidingWindowAdaptivePolicy.java)) | Exact window, mutable boxed counter | `ConcurrentLinkedQueue.contains` (original) · Count-Min sketch | O(1) exact counts. `int[1]` avoids re-boxing an `Integer` on every increment. A Count-Min sketch (TinyLFU) would cap memory independent of W, but adds approximation error and is overkill when W ≤ 16C. The policy runs under the shard lock, so concurrent collections would add cost and buy nothing. |
| D14 | Bounded second chances, `maxProbes = 4` ([`SlidingWindowAdaptivePolicy.java:150-159`](src/main/java/com/cosh/eviction/SlidingWindowAdaptivePolicy.java)) | Bounded CLOCK-like scan | Unbounded scan until an unprotected entry is found | When everything is hot, an unbounded scan degrades to O(n). Four probes keep eviction O(1) worst case (asserted by `evictionCostIsBoundedEvenWhenEveryEntryIsProtected`). |
| D15 | Hill-climbing on hit rate ([`SlidingWindowAdaptivePolicy.java:233-253`](src/main/java/com/cosh/eviction/SlidingWindowAdaptivePolicy.java)) | Online feedback control | A fixed window · ARC-style ghost lists | Fixed windows are tuned for one workload. ARC needs ghost lists (extra memory, more pointers). The climber needs two counters and is shown to help: adaptive beats the fixed 4C window by **+1.60 pp** (Zipf), **+0.50 pp** (shifting), **+2.02 pp** (scan), and ties on uniform (+0.03 pp). |
| D16 | O(1) LFU with frequency-bucket list ([`LfuPolicy.java:22-35, 53-67`](src/main/java/com/cosh/eviction/LfuPolicy.java)) | Bucket DLL (Shah et al.) | `PriorityQueue` by frequency (O(log n)) · `minFreq` integer | A heap is O(log n) per access. A plain `minFreq` counter breaks on arbitrary `remove()`: if the minimum bucket empties, the next minimum is unknown without a scan. A linked bucket list always knows the next one (`lfuRemovalOfMinimumBucketKeepsEvictionCorrect`). |

---

## 6. COSH vs Redis / Valkey: an honest comparison

**Short answer:** COSH is not a Redis replacement, and pretending otherwise would fail any interview. It is a different category: an **embedded, in-process** cache library, closer to Caffeine, Guava Cache or Ehcache's heap tier than to a cache *server*. Its consistent-hash ring partitions memory and locks inside one JVM, and it is the same algorithm a client library would use to spread keys across remote servers.

| Dimension | COSH (embedded) | Redis / Valkey (server) |
|---|---|---|
| Architecture | Library objects in the application's JVM. Shards are `CacheNode` objects. | A separate process (single-threaded event loop; Valkey 8 adds I/O threads). Cluster mode spreads 16,384 hash slots across servers. |
| Access latency | No network, no serialization. Measured single-threaded: P50 42–83 ns (1–2 ticks of the 41 ns timer), P99 ≤ 250 ns for every policy. Through the sharded path, P50 84–208 ns. | A network round-trip plus RESP encoding and decoding: typically tens to hundreds of **µs** on a LAN, ~3 orders of magnitude slower per op. |
| Memory model | Java heap objects (entry + map node + key/value objects). GC-managed, so a large cache raises GC work. Each JVM has its own copy, so N app instances means N caches. | Off-heap, compact encodings (listpacks, intsets), a jemalloc allocator, `maxmemory` with approximate LRU/LFU sampling. One shared copy for all clients. |
| Consistency across app instances | None: each JVM sees only its own cache. Invalidations do not propagate. | One shared copy: every client sees the same value (replicas are async). |
| Persistence and durability | None. A restart is a cold cache. | RDB snapshots, AOF, replication, Sentinel / Cluster failover. |
| Data model | Opaque key → value objects. | Strings, hashes, lists, sets, sorted sets, streams, TTLs, pub/sub, Lua, transactions. |
| Eviction | Exact LRU, exact O(1) LFU, adaptive sliding window. Pluggable per shard. | Sampled approximate LRU/LFU (`maxmemory-samples`), TTL-based expiry. |
| Scaling | Bounded by one JVM's heap and cores. Measured: in-process sharding gives 1.1–1.3× from 1→8 threads with 64 shards. | Horizontal: add servers, reshard slots. |
| Operations | Nothing to deploy or monitor separately. | A separate fleet: provisioning, monitoring, failover, upgrades, security. |

**Where an embedded sharded cache wins**

* Hot read paths where a network hop costs more than the work: feature flags, config, permission checks, rendered fragments, a reference-data lookup done 10⁴ times per request.
* Per-instance data that need not be shared (a session-affine or tenant-local cache), or as an **L1 in front of Redis** (near cache), which absorbs hot keys and cuts Redis QPS and tail latency.
* Workloads where object deserialization would dominate. COSH hands back the live object.

**Where Redis / Valkey wins**

* Many application instances must see one consistent value (cache-aside for a fleet), or the data must survive deploys and restarts.
* Data larger than one heap, rich data structures, TTLs, pub/sub invalidation, rate limiters and leaderboards (sorted sets), distributed locks.
* Multi-language clients, and operational tooling (persistence, replication, failover).

**Interview Q&A talking points**

* *"Is it distributed?"* "No. It's in-process and sharded. The consistent-hash ring with virtual nodes is the same algorithm memcached/ketama clients use to spread keys over servers, and I measured its properties: 6.8% load CV, 19.3% key movement on adding a 5th node against an ideal of 20%. Making it distributed would need an RPC transport, a membership and failure detector (gossip or a coordinator), replication, and a strategy for migrating keys over the wire."
* *"What was the hardest bug?"* "A read reordering the LRU list outside the write lock. When a read raced an eviction, it put the evicted node back in the list, the map and list diverged, and a capacity-100 cache grew to 400 entries. The fix was one lock domain plus a liveness flag. I proved it with 16-thread stress tests that validate both list directions and sample the size continuously."
* *"Why not `LinkedHashMap`?"* "It isn't thread-safe, it only does LRU, and it hides the node links I need for LFU buckets and second chance."
* *"Why does a read take a lock?"* "LRU is a write on every read. You either serialize the reorder (STRICT), skip it under contention (BEST_EFFORT, Caffeine's lossy trade-off), or buffer reads and replay them in batches (Caffeine's ring buffers, on my roadmap)."
* *"Is your adaptive policy better than LRU?"* "Better than LRU on every skewed workload I tested (+3.15 pp Zipf, +1.75 pp phase-shift, +4.76 pp scan), worse than LFU on static Zipf and scan. Its strength is robustness. Its worst-case gap to the best policy across the three skewed workloads is 3.8 pp, against 8.6 pp for LRU and 24.1 pp for LFU. The cost is about 2× lower single-thread throughput than LRU."
* *"How would you scale it across machines?"* "Keep the ring in the client, put a binary protocol behind `CacheNode` (gRPC or Netty), add replicas on the next k clockwise nodes, and use hinted handoff for availability. That's the Dynamo pattern."

---

## 7. Verified benchmark results

**Setup.** Apple M5 (10 cores), 16 GB, macOS, OpenJDK 27.0.1, default JVM flags. 100,000 measured requests per run, 10,000 keys, capacity 1,000 (10%), 20,000-request warm prefix, 3 JIT warm-up replays, 5 trials per run (median), seed 42. The **read-through** model: `get`, and on a miss `put`. The whole benchmark was run **3 times back to back**. Hit rates were identical in all 3 runs (deterministic). Latency is the median of the 3 runs' medians. Throughput is shown as min–max across the runs because the machine was not idle (load average ≈ 7–9). Raw output: [`benchmarks/RESULTS.md`](benchmarks/RESULTS.md).

> **Timer caveat:** `System.nanoTime()` on this machine has a granularity of **41 ns**, so every latency is a multiple of about 41.7 ns. "42 ns" means "completed within one tick". It does not mean exactly 42 ns.

#### Uniform (theoretical hit rate = capacity / keys = 10%)

| Policy | Hit rate | Throughput, Mops/s | P50 ns | P95 ns | P99 ns |
|---|---:|---:|---:|---:|---:|
| LRU | 9.87% | 20.4–34.3 | 42 | 42 | 84 |
| LFU | 9.86% | 14.3–22.9 | 42 | 84 | 84 |
| SlidingWindow (fixed 4C) | 9.75% | 8.6–11.3 | 83 | 167 | 250 |
| SlidingWindowAdaptive | 9.78% | 10.6–12.2 | 83 | 166 | 208 |

#### Zipfian (s = 0.99)

| Policy | Hit rate | Throughput, Mops/s | P50 ns | P95 ns | P99 ns |
|---|---:|---:|---:|---:|---:|
| LRU | 66.49% | 36.8–42.4 | 41 | 83 | 84 |
| **LFU** | **72.39%** | 19.9–23.9 | 42 | 84 | 125 |
| SlidingWindow (fixed 4C) | 68.04% | 20.3–22.4 | 42 | 125 | 125 |
| SlidingWindowAdaptive | 69.64% | 16.9–19.4 | 42 | 125 | 167 |

#### Shifting Zipfian (4 phases, disjoint hot sets)

| Policy | Hit rate | Throughput, Mops/s | P50 ns | P95 ns | P99 ns |
|---|---:|---:|---:|---:|---:|
| LRU | 65.90% | 44.3–47.4 | 41 | 42 | 84 |
| LFU | 43.59% | 18.2–21.1 | 42 | 84 | 125 |
| SlidingWindow (fixed 4C) | 67.15% | 20.9–23.5 | 42 | 125 | 125 |
| **SlidingWindowAdaptive** | **67.65%** | 18.6–19.8 | 42 | 125 | 167 |

#### Zipfian + 20% sequential scan (scan loop = 5× capacity)

| Policy | Hit rate | Throughput, Mops/s | P50 ns | P95 ns | P99 ns |
|---|---:|---:|---:|---:|---:|
| LRU | 48.74% | 34.0–37.7 | 42 | 42 | 84 |
| **LFU** | **57.29%** | 20.2–23.4 | 42 | 84 | 125 |
| SlidingWindow (fixed 4C) | 51.48% | 14.7–18.4 | 42 | 125 | 167 |
| SlidingWindowAdaptive | 53.50% | 13.3–16.4 | 42 | 166 | 208 |

#### Routing overhead (Zipfian): one cache vs 4 shards × 250

| Configuration | Hit rate | Throughput, Mops/s | P50 ns | P95 ns | P99 ns |
|---|---:|---:|---:|---:|---:|
| PluggableCache LRU (no routing) | 66.49% | 24.2–38.1 | 41 | 83 | 84 |
| ShardedCache LRU, MD5 ring hash | 66.45% | 3.9–5.0 | 208 | 417 | 458 |
| ShardedCache LRU, FNV-1a+fmix64 ring hash | 66.42% | 6.1–11.1 | 84 | 208 | 250 |
| ShardedCache SlidingWindowAdaptive, FNV-1a+fmix64 | 68.99% | 3.9–7.1 | 125 | 292 | 334 |

#### Multi-threaded throughput (Zipfian, 200,000 requests per thread, LRU, total capacity 1,000), Mops/s, min–max of 3 runs

| Configuration | 1 thread | 2 threads | 4 threads | 8 threads | Hit rate @8 |
|---|---:|---:|---:|---:|---:|
| 1 shard, STRICT reads (one lock) | 17.4–36.6 | 4.4–11.3 | 7.6–12.8 | 10.7–12.9 | 66.43% |
| 1 shard, BEST_EFFORT reads (tryLock) | 26.3–33.1 | 6.9–9.3 | 5.5–9.3 | 8.5–9.4 | 66.35% |
| ShardedCache 8 shards, STRICT | 9.0–10.3 | 2.7–6.5 | 4.7–5.2 | 3.9–4.2 | 66.27% |
| ShardedCache 8 shards, BEST_EFFORT | 7.1–8.4 | 6.9–8.5 | 7.3–7.5 | 5.5–6.1 | 65.97% |
| ShardedCache 64 shards, STRICT | 6.7–7.8 | 8.7–9.9 | 6.7–11.0 | 8.4–9.0 | 65.56% |

**What the numbers say**

1. **Hit rate vs LRU:** the adaptive policy beats LRU on all three skewed workloads: **+3.15 pp** Zipfian (misses −9.4%), **+1.75 pp** shifting, and **+4.76 pp** scan (misses −9.3%). On uniform traffic no policy can beat capacity/keys, and all land at about 9.8%.
2. **It is not the best everywhere.** Exact LFU wins on static Zipfian (+2.75 pp over adaptive) and scan (+3.79 pp), but LFU **collapses on phase shifts (43.59% vs 67.65%)** because stale frequencies pin the old hot set. The adaptive policy has the **smallest worst-case gap to the best policy** across the skewed workloads: **3.8 pp**, vs 8.6 pp (LRU) and 24.1 pp (LFU).
3. **Adaptation is worth something:** compared with the same policy with a fixed 4C window, it gains +1.60 / +0.50 / +2.02 pp (Zipf / shift / scan).
4. **The cost of being smarter:** adaptive throughput is about 2× lower than LRU single-threaded (window bookkeeping + probes), with P99 167–208 ns vs 84 ns.
5. **The routing hash matters:** MD5 is the most expensive part of the sharded path (P50 208 ns). FNV-1a+fmix64 cuts it to 84 ns at the same balance quality (CV 0.050).
6. **Concurrency, honestly:** all configurations are **correct** under 8 threads. Throughput does **not** scale linearly in-process. Each operation costs only about 20–40 ns, so lock hand-offs and per-request routing dominate. Going from 1 to 8 threads, the single-lock cache *drops* from its uncontended peak. 64 shards *rise* 1.12–1.33× over their own 1-thread figure in each run (8.4–9.0 vs 6.7–7.8 Mops/s). At 8 threads the single-lock cache is still the fastest absolute configuration here. Linear scaling would need lock-free reads with buffered policy updates (§8).

---

## 8. Known limitations and roadmap

* **Not networked.** Next steps toward a real distributed cache: a `RemoteCacheNode` over gRPC or Netty, membership with failure detection, replication to the next k ring successors, and streaming migration.
* **Read scalability.** Replace the STRICT/BEST_EFFORT split with Caffeine-style striped, lossy read buffers drained under `tryLock`, so hits are lock-free while the policy still sees most accesses.
* **No TTL / expiry, no size-in-bytes weighing, no statistics API, no async loading.** These would be the next features for production use.
* **Migration is synchronous** under the topology write lock, costing O(keys on affected nodes). A pathological stream of topology changes starves readers (the writer-preference trade-off documented in `TopologyLock`).
* **Window memory.** `SlidingWindowAdaptivePolicy` keeps up to 16C keys in its window. A Count-Min sketch would cap this at a fixed few KB.
* **Benchmark hygiene.** These are single-machine microbenchmarks with a 41 ns timer on a non-idle laptop. JMH with forks and pinned frequencies would tighten throughput error bars. Hit rates are exact.
