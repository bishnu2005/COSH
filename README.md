# COSH: Sharded In-Memory Cache for the JVM

COSH is an embedded, thread-safe Java cache. Requests are spread over multiple **in-process cache nodes** by a **consistent-hash ring with virtual nodes**. Each node has its own bounded store and a **pluggable O(1) eviction policy**: LRU, LFU, or a **sliding-window adaptive policy** that tunes itself by hill-climbing on the observed hit rate.

> **Scope:** the nodes are shards inside one JVM, not servers on a network. COSH is a library, comparable to Caffeine or Guava Cache, not a Redis-style server. See the [deep dive](ENGINEERING_DEEP_DIVE.md#6-cosh-vs-redis--valkey-an-honest-comparison) for when each is the right tool.

```text
 get/put(k)
     │
     ▼
 ShardedCache ──► ConsistentHashingRouter ──► CacheNode "node-i"
 (TopologyLock:     sorted long[] ring,          PluggableCache
  padded per-thread  150 vnodes/node,            ├─ ConcurrentHashMap<K, CacheEntry>  (lock-free lookup)
  read stripes)      binarySearch → owner        ├─ ReentrantLock (one per shard)
                                                 └─ EvictionPolicy ── LRU | LFU | SlidingWindowAdaptive
                                                     intrusive doubly linked list: head ⇄ … ⇄ tail (victim)
```

## Verify it in 30 seconds

Requires Java 17+ and Maven 3.6+.

```bash
mvn test
```

```bash
mvn -q compile && java -cp target/classes com.cosh.benchmark.BenchmarkRunner
```

`mvn test` runs **75 tests, all passing (about 10 s)**:

- consistent-hash balance and minimal key movement
- O(1) eviction guards for every policy
- LFU and adaptive ordering semantics
- capacity 1, null and invalid inputs
- 16-thread `ExecutorService` + `CountDownLatch` stress tests that check zero exceptions, no deadlock (`ThreadMXBean`), size never above capacity, and intact list invariants
- the sharded cluster under live node add/remove

The benchmark takes about 30 s. It accepts `--ops`, `--keys`, `--capacity`, `--trials`, `--seed`, `--threads 1,2,4,8` and `--skip-concurrent`.

## Results (measured on this repo)

Apple M5, OpenJDK 27. Each run measures 100,000 requests (read-through: `get`, and `put` on a miss) over 10,000 keys with capacity 1,000, median of 5 trials. Hit rates are deterministic and were identical across 3 full runs. Raw output is in [`benchmarks/RESULTS.md`](benchmarks/RESULTS.md).

| Workload | LRU | LFU | SlidingWindow (fixed) | **SlidingWindowAdaptive** |
|---|---:|---:|---:|---:|
| Uniform (ceiling = 10%) | 9.87% | 9.86% | 9.75% | 9.78% |
| Zipfian s=0.99 | 66.49% | **72.39%** | 68.04% | 69.64% |
| Shifting Zipfian (4 phases) | 65.90% | 43.59% | 67.15% | **67.65%** |
| Zipfian + 20% scan | 48.74% | **57.29%** | 51.48% | 53.50% |
| *Worst-case gap to best policy, skewed workloads* | 8.6 pp | 24.1 pp | 5.8 pp | **3.8 pp** |

- **What the adaptive policy does well:** it beats LRU on every skewed workload, cutting misses by about 9% on Zipfian and scan traffic. Unlike LFU, it does not collapse when the hot set shifts.
- **What it costs:** single-threaded throughput on skewed workloads is 13–20M ops/s, against 34–47M for LRU.
- **Latency:** single-threaded P50 is at most 83 ns (2 ticks of the 41 ns timer; 42 ns on every skewed workload). P99 is at most 250 ns for every policy, and at most 125 ns for LRU and LFU.
- **Routing:** routing through the ring adds 40–170 ns at P50, depending on the hash function (MD5 vs FNV-1a+fmix64).
- **Consistent hashing:** with 150 virtual nodes, per-node load CV is 0.068, against 0.643 with one node point. Adding a 5th node moves 19.27% of keys (ideal 20%), all of them onto the new node.

Full tables (throughput ranges, P95/P99, multi-threaded scaling) and caveats are in the [deep dive §7](ENGINEERING_DEEP_DIVE.md#7-verified-benchmark-results).

## Layout

| Package | Contents |
|---|---|
| `com.cosh.core` | `Cache`, `PluggableCache` (map + lock + policy), `ConcurrentLRUCache`, `CacheNode`, `ShardedCache` (routing + key migration), `TopologyLock` |
| `com.cosh.eviction` | `EvictionPolicy`, `CacheEntry` (map value and list node), `DoublyLinkedList`, `LruPolicy`, `LfuPolicy` (O(1) frequency buckets), `SlidingWindowAdaptivePolicy` |
| `com.cosh.routing` | `HashFunction`, `MD5HashFunction`, `Fnv1aMixHashFunction`, `ConsistentHashingRouter` |
| `com.cosh.benchmark` | `Workloads` (seeded traces), `BenchmarkRunner` (CLI) |

## Usage

```java
ShardedCache<String, User> cache = new ShardedCache<>(new Fnv1aMixHashFunction(), 150);
for (int i = 0; i < 4; i++) {
    cache.addNode(new CacheNode<>("node-" + i,
            new PluggableCache<>(10_000, SlidingWindowAdaptivePolicy.forCapacity(10_000))));
}
User u = cache.get("user:42");
if (u == null) cache.put("user:42", u = db.load(42));
```

## Further reading

[`ENGINEERING_DEEP_DIVE.md`](ENGINEERING_DEEP_DIVE.md) covers:

- the audit, and the 12 defects found and fixed (including a lost-update race that let a capacity-100 cache grow to 400 entries)
- the request flow end to end
- an atlas of every file
- lock granularity, virtual-node math and the adaptation mechanics
- 16 "why this instead of that" design decisions
- an honest comparison with Redis and Valkey
