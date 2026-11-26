# COSH benchmark — raw output of 3 consecutive runs

Machine: Apple M5 (10 cores), 16 GB RAM, macOS, OpenJDK 27.0.1. Load average during runs: ~7–9 (other processes were active), so treat throughput as noisy; hit rates are deterministic.

Reproduce: `mvn -q -o compile && java -cp target/classes com.cosh.benchmark.BenchmarkRunner`

---

<!-- run 1 -->
# Run 1
JVM: OpenJDK 64-Bit Server VM 27.0.1.0.3 | OS: Mac OS X aarch64 | CPUs: 10 | max heap: 4096 MB
Config: 100,000 measured requests per run | key space 10,000 | capacity 1,000 (10% of keys) | warm prefix 20,000 | trials 5 (median) | seed 42
System.nanoTime() granularity on this machine: 41 ns (latencies below are quantized to this step)

## Uniform

| Policy | Hit rate | Throughput (ops/s) | P50 (ns) | P95 (ns) | P99 (ns) | Notes |
|---|---:|---:|---:|---:|---:|---|
| LRU | 9.87% | 32,959,789 | 42 | 42 | 84 |  |
| LFU | 9.86% | 22,713,294 | 42 | 84 | 84 |  |
| SlidingWindow (fixed 4C) | 9.75% | 11,252,866 | 83 | 166 | 209 | window=4,000, 0 climb steps, 105,306 second chances |
| SlidingWindowAdaptive | 9.78% | 11,991,607 | 83 | 166 | 208 | window=3,543, 60 climb steps, 77,645 second chances |

## Zipfian (s=0.99)

| Policy | Hit rate | Throughput (ops/s) | P50 (ns) | P95 (ns) | P99 (ns) | Notes |
|---|---:|---:|---:|---:|---:|---|
| LRU | 66.49% | 42,405,080 | 41 | 42 | 84 |  |
| LFU | 72.39% | 22,132,463 | 42 | 167 | 292 |  |
| SlidingWindow (fixed 4C) | 68.04% | 20,503,181 | 42 | 125 | 250 | window=4,000, 0 climb steps, 8,849 second chances |
| SlidingWindowAdaptive | 69.64% | 17,574,433 | 42 | 125 | 167 | window=8,529, 60 climb steps, 59,897 second chances |

## Shifting Zipfian (4 phases)

| Policy | Hit rate | Throughput (ops/s) | P50 (ns) | P95 (ns) | P99 (ns) | Notes |
|---|---:|---:|---:|---:|---:|---|
| LRU | 65.90% | 47,382,137 | 41 | 42 | 84 |  |
| LFU | 43.59% | 18,244,150 | 42 | 84 | 125 |  |
| SlidingWindow (fixed 4C) | 67.15% | 22,519,140 | 42 | 125 | 125 | window=4,000, 0 climb steps, 10,035 second chances |
| SlidingWindowAdaptive | 67.65% | 19,774,410 | 42 | 125 | 167 | window=4,654, 60 climb steps, 60,115 second chances |

## Zipfian + 20% sequential scan

| Policy | Hit rate | Throughput (ops/s) | P50 (ns) | P95 (ns) | P99 (ns) | Notes |
|---|---:|---:|---:|---:|---:|---|
| LRU | 48.74% | 35,580,857 | 42 | 42 | 84 |  |
| LFU | 57.29% | 23,423,773 | 42 | 83 | 125 |  |
| SlidingWindow (fixed 4C) | 51.48% | 18,372,785 | 42 | 125 | 125 | window=4,000, 0 climb steps, 12,983 second chances |
| SlidingWindowAdaptive | 53.50% | 16,370,184 | 42 | 125 | 167 | window=6,399, 60 climb steps, 34,805 second chances |

## Routing overhead (Zipfian): 1 shard x 1,000 vs ShardedCache 4 shards x 250 (150 vnodes per shard)

| Configuration | Hit rate | Throughput (ops/s) | P50 (ns) | P95 (ns) | P99 (ns) | Notes |
|---|---:|---:|---:|---:|---:|---|
| PluggableCache LRU (no routing) | 66.49% | 24,188,424 | 42 | 125 | 250 |  |
| ShardedCache LRU, 4 nodes, MD5 ring hash | 66.45% | 4,574,975 | 208 | 417 | 458 |  |
| ShardedCache LRU, 4 nodes, FNV-1a+fmix64 ring hash | 66.42% | 6,101,421 | 125 | 333 | 417 |  |
| ShardedCache SlidingWindowAdaptive, 4 nodes, FNV-1a+fmix64 | 68.99% | 3,918,873 | 208 | 541 | 750 |  |

## Multi-threaded throughput (Zipfian, 200,000 requests per thread, LRU, total capacity 1,000)

| Configuration | 1 thr (ops/s) | 2 thr (ops/s) | 4 thr (ops/s) | 8 thr (ops/s) | Hit rate @max thr |
|---|---:|---:|---:|---:|---:|
| 1 shard, STRICT reads (one lock) | 17,365,697 | 4,384,072 | 7,626,353 | 11,060,663 | 66.43% |
| 1 shard, BEST_EFFORT reads (tryLock) | 33,091,583 | 9,331,296 | 9,259,130 | 8,505,997 | 66.35% |
| ShardedCache 8 shards, STRICT, FNV-1a+fmix64 | 8,986,930 | 6,536,424 | 5,152,433 | 4,246,226 | 66.27% |
| ShardedCache 8 shards, BEST_EFFORT, FNV-1a+fmix64 | 8,420,240 | 8,471,308 | 7,351,139 | 6,084,396 | 65.97% |
| ShardedCache 64 shards, STRICT, FNV-1a+fmix64 | 7,820,443 | 9,625,865 | 10,997,647 | 8,758,057 | 65.56% |


---

<!-- run 2 -->
# Run 2
JVM: OpenJDK 64-Bit Server VM 27.0.1.0.3 | OS: Mac OS X aarch64 | CPUs: 10 | max heap: 4096 MB
Config: 100,000 measured requests per run | key space 10,000 | capacity 1,000 (10% of keys) | warm prefix 20,000 | trials 5 (median) | seed 42
System.nanoTime() granularity on this machine: 41 ns (latencies below are quantized to this step)

## Uniform

| Policy | Hit rate | Throughput (ops/s) | P50 (ns) | P95 (ns) | P99 (ns) | Notes |
|---|---:|---:|---:|---:|---:|---|
| LRU | 9.87% | 34,346,557 | 42 | 42 | 84 |  |
| LFU | 9.86% | 22,855,398 | 42 | 83 | 84 |  |
| SlidingWindow (fixed 4C) | 9.75% | 11,275,600 | 83 | 167 | 250 | window=4,000, 0 climb steps, 105,306 second chances |
| SlidingWindowAdaptive | 9.78% | 12,208,086 | 83 | 125 | 208 | window=3,543, 60 climb steps, 77,645 second chances |

## Zipfian (s=0.99)

| Policy | Hit rate | Throughput (ops/s) | P50 (ns) | P95 (ns) | P99 (ns) | Notes |
|---|---:|---:|---:|---:|---:|---|
| LRU | 66.49% | 40,184,172 | 41 | 83 | 84 |  |
| LFU | 72.39% | 23,940,862 | 42 | 83 | 125 |  |
| SlidingWindow (fixed 4C) | 68.04% | 22,413,986 | 42 | 84 | 125 | window=4,000, 0 climb steps, 8,849 second chances |
| SlidingWindowAdaptive | 69.64% | 19,368,273 | 42 | 125 | 167 | window=8,529, 60 climb steps, 59,897 second chances |

## Shifting Zipfian (4 phases)

| Policy | Hit rate | Throughput (ops/s) | P50 (ns) | P95 (ns) | P99 (ns) | Notes |
|---|---:|---:|---:|---:|---:|---|
| LRU | 65.90% | 47,168,877 | 41 | 42 | 84 |  |
| LFU | 43.59% | 21,050,784 | 42 | 84 | 125 |  |
| SlidingWindow (fixed 4C) | 67.15% | 23,471,655 | 42 | 84 | 125 | window=4,000, 0 climb steps, 10,035 second chances |
| SlidingWindowAdaptive | 67.65% | 19,227,223 | 42 | 125 | 167 | window=4,654, 60 climb steps, 60,115 second chances |

## Zipfian + 20% sequential scan

| Policy | Hit rate | Throughput (ops/s) | P50 (ns) | P95 (ns) | P99 (ns) | Notes |
|---|---:|---:|---:|---:|---:|---|
| LRU | 48.74% | 37,651,184 | 41 | 42 | 84 |  |
| LFU | 57.29% | 20,246,673 | 42 | 84 | 125 |  |
| SlidingWindow (fixed 4C) | 51.48% | 14,725,552 | 42 | 125 | 208 | window=4,000, 0 climb steps, 12,983 second chances |
| SlidingWindowAdaptive | 53.50% | 13,262,232 | 42 | 167 | 209 | window=6,399, 60 climb steps, 34,805 second chances |

## Routing overhead (Zipfian): 1 shard x 1,000 vs ShardedCache 4 shards x 250 (150 vnodes per shard)

| Configuration | Hit rate | Throughput (ops/s) | P50 (ns) | P95 (ns) | P99 (ns) | Notes |
|---|---:|---:|---:|---:|---:|---|
| PluggableCache LRU (no routing) | 66.49% | 35,140,121 | 41 | 83 | 84 |  |
| ShardedCache LRU, 4 nodes, MD5 ring hash | 66.45% | 5,000,229 | 167 | 375 | 458 |  |
| ShardedCache LRU, 4 nodes, FNV-1a+fmix64 ring hash | 66.42% | 11,121,254 | 83 | 167 | 209 |  |
| ShardedCache SlidingWindowAdaptive, 4 nodes, FNV-1a+fmix64 | 68.99% | 7,111,870 | 84 | 250 | 333 |  |

## Multi-threaded throughput (Zipfian, 200,000 requests per thread, LRU, total capacity 1,000)

| Configuration | 1 thr (ops/s) | 2 thr (ops/s) | 4 thr (ops/s) | 8 thr (ops/s) | Hit rate @max thr |
|---|---:|---:|---:|---:|---:|
| 1 shard, STRICT reads (one lock) | 36,585,083 | 11,318,659 | 12,842,079 | 12,943,300 | 66.41% |
| 1 shard, BEST_EFFORT reads (tryLock) | 31,684,423 | 6,915,121 | 5,491,978 | 8,777,273 | 66.35% |
| ShardedCache 8 shards, STRICT, FNV-1a+fmix64 | 9,013,441 | 2,685,002 | 4,749,292 | 3,902,965 | 66.28% |
| ShardedCache 8 shards, BEST_EFFORT, FNV-1a+fmix64 | 7,794,169 | 6,949,145 | 7,485,742 | 5,779,895 | 65.98% |
| ShardedCache 64 shards, STRICT, FNV-1a+fmix64 | 7,364,663 | 8,734,157 | 8,801,719 | 8,410,600 | 65.55% |


---

<!-- run 3 -->
# Run 3
JVM: OpenJDK 64-Bit Server VM 27.0.1.0.3 | OS: Mac OS X aarch64 | CPUs: 10 | max heap: 4096 MB
Config: 100,000 measured requests per run | key space 10,000 | capacity 1,000 (10% of keys) | warm prefix 20,000 | trials 5 (median) | seed 42
System.nanoTime() granularity on this machine: 41 ns (latencies below are quantized to this step)

## Uniform

| Policy | Hit rate | Throughput (ops/s) | P50 (ns) | P95 (ns) | P99 (ns) | Notes |
|---|---:|---:|---:|---:|---:|---|
| LRU | 9.87% | 20,366,943 | 42 | 125 | 125 |  |
| LFU | 9.86% | 14,334,521 | 83 | 125 | 167 |  |
| SlidingWindow (fixed 4C) | 9.75% | 8,594,419 | 125 | 209 | 292 | window=4,000, 0 climb steps, 105,306 second chances |
| SlidingWindowAdaptive | 9.78% | 10,613,833 | 84 | 167 | 250 | window=3,543, 60 climb steps, 77,645 second chances |

## Zipfian (s=0.99)

| Policy | Hit rate | Throughput (ops/s) | P50 (ns) | P95 (ns) | P99 (ns) | Notes |
|---|---:|---:|---:|---:|---:|---|
| LRU | 66.49% | 36,795,143 | 41 | 83 | 84 |  |
| LFU | 72.39% | 19,861,139 | 42 | 84 | 125 |  |
| SlidingWindow (fixed 4C) | 68.04% | 20,298,386 | 42 | 125 | 125 | window=4,000, 0 climb steps, 8,849 second chances |
| SlidingWindowAdaptive | 69.64% | 16,904,027 | 42 | 125 | 167 | window=8,529, 60 climb steps, 59,897 second chances |

## Shifting Zipfian (4 phases)

| Policy | Hit rate | Throughput (ops/s) | P50 (ns) | P95 (ns) | P99 (ns) | Notes |
|---|---:|---:|---:|---:|---:|---|
| LRU | 65.90% | 44,300,046 | 41 | 42 | 84 |  |
| LFU | 43.59% | 20,234,723 | 42 | 84 | 125 |  |
| SlidingWindow (fixed 4C) | 67.15% | 20,868,114 | 42 | 125 | 166 | window=4,000, 0 climb steps, 10,035 second chances |
| SlidingWindowAdaptive | 67.65% | 18,634,841 | 42 | 125 | 167 | window=4,654, 60 climb steps, 60,115 second chances |

## Zipfian + 20% sequential scan

| Policy | Hit rate | Throughput (ops/s) | P50 (ns) | P95 (ns) | P99 (ns) | Notes |
|---|---:|---:|---:|---:|---:|---|
| LRU | 48.74% | 33,960,655 | 42 | 42 | 84 |  |
| LFU | 57.29% | 21,653,617 | 42 | 84 | 125 |  |
| SlidingWindow (fixed 4C) | 51.48% | 15,279,616 | 42 | 125 | 167 | window=4,000, 0 climb steps, 12,983 second chances |
| SlidingWindowAdaptive | 53.50% | 14,653,537 | 42 | 166 | 208 | window=6,399, 60 climb steps, 34,805 second chances |

## Routing overhead (Zipfian): 1 shard x 1,000 vs ShardedCache 4 shards x 250 (150 vnodes per shard)

| Configuration | Hit rate | Throughput (ops/s) | P50 (ns) | P95 (ns) | P99 (ns) | Notes |
|---|---:|---:|---:|---:|---:|---|
| PluggableCache LRU (no routing) | 66.49% | 38,071,670 | 41 | 83 | 84 |  |
| ShardedCache LRU, 4 nodes, MD5 ring hash | 66.45% | 3,882,685 | 209 | 459 | 583 |  |
| ShardedCache LRU, 4 nodes, FNV-1a+fmix64 ring hash | 66.42% | 9,946,537 | 84 | 208 | 250 |  |
| ShardedCache SlidingWindowAdaptive, 4 nodes, FNV-1a+fmix64 | 68.99% | 7,027,263 | 125 | 292 | 334 |  |

## Multi-threaded throughput (Zipfian, 200,000 requests per thread, LRU, total capacity 1,000)

| Configuration | 1 thr (ops/s) | 2 thr (ops/s) | 4 thr (ops/s) | 8 thr (ops/s) | Hit rate @max thr |
|---|---:|---:|---:|---:|---:|
| 1 shard, STRICT reads (one lock) | 33,478,406 | 8,998,243 | 10,782,210 | 10,669,319 | 66.41% |
| 1 shard, BEST_EFFORT reads (tryLock) | 26,312,182 | 8,549,033 | 8,575,019 | 9,370,706 | 66.33% |
| ShardedCache 8 shards, STRICT, FNV-1a+fmix64 | 10,349,824 | 6,012,141 | 4,681,466 | 3,999,427 | 66.29% |
| ShardedCache 8 shards, BEST_EFFORT, FNV-1a+fmix64 | 7,125,351 | 8,459,611 | 7,347,395 | 5,482,887 | 66.01% |
| ShardedCache 64 shards, STRICT, FNV-1a+fmix64 | 6,744,349 | 9,885,686 | 6,723,362 | 8,987,973 | 65.57% |

