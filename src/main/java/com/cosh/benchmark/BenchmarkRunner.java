package com.cosh.benchmark;

import com.cosh.core.Cache;
import com.cosh.core.CacheNode;
import com.cosh.core.PluggableCache;
import com.cosh.core.ShardedCache;
import com.cosh.eviction.EvictionPolicy;
import com.cosh.eviction.LfuPolicy;
import com.cosh.eviction.LruPolicy;
import com.cosh.eviction.SlidingWindowAdaptivePolicy;
import com.cosh.routing.Fnv1aMixHashFunction;
import com.cosh.routing.HashFunction;
import com.cosh.routing.MD5HashFunction;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * Reproducible CLI benchmark.
 *
 * <pre>
 *   mvn -q compile &amp;&amp; java -cp target/classes com.cosh.benchmark.BenchmarkRunner [--ops N] [--keys N]
 *        [--capacity N] [--trials N] [--seed N] [--threads 1,2,4,8] [--skip-concurrent]
 * </pre>
 *
 * <p><b>Request model (read-through):</b> {@code v = cache.get(k); if (v == null) cache.put(k, load(k))}.
 * One request = one get plus a fill on a miss. Hit rate = hits / measured requests.
 *
 * <p><b>Method:</b> for every (workload, policy) pair, the same seeded trace is replayed on a
 * fresh cache. A prefix of {@code capacity * 20} requests warms the cache untimed. The JIT is
 * warmed by full throwaway replays first. Latency comes from per-request {@code System.nanoTime()}
 * pairs. Throughput comes from separate untimed-per-op replays (no timer overhead). Both report
 * the median over {@code --trials} runs.
 */
public final class BenchmarkRunner {

    public static final class Config {
        int ops = 100_000;
        int keys = 10_000;
        int capacity = 1_000;
        int trials = 5;
        int jitWarmups = 3;
        long seed = 42;
        int[] threads = {1, 2, 4, 8};
        boolean concurrent = true;

        int prefix() {
            return capacity * 20;
        }

        public static Config small() {
            Config c = new Config();
            c.ops = 5_000;
            c.keys = 1_000;
            c.capacity = 100;
            c.trials = 1;
            c.jitWarmups = 0;
            c.threads = new int[]{1, 2};
            return c;
        }
    }

    public record PolicySpec(String name, Supplier<Cache<Integer, Integer>> factory) {
    }

    public record Result(String workload, String policy, int requests, long hits, double opsPerSec,
                         long p50, long p95, long p99, String note) {
        public double hitRate() {
            return (double) hits / requests;
        }
    }

    private static final Integer VALUE = 1;

    // ---------------------------------------------------------------- policies under test

    public static List<PolicySpec> policies(int capacity) {
        List<PolicySpec> list = new ArrayList<>();
        list.add(new PolicySpec("LRU", () -> new PluggableCache<>(capacity, new LruPolicy<>())));
        list.add(new PolicySpec("LFU", () -> new PluggableCache<>(capacity, new LfuPolicy<>())));
        list.add(new PolicySpec("SlidingWindow (fixed 4C)",
                () -> new PluggableCache<>(capacity, new SlidingWindowAdaptivePolicy<>(4 * capacity))));
        list.add(new PolicySpec("SlidingWindowAdaptive",
                () -> new PluggableCache<>(capacity, SlidingWindowAdaptivePolicy.forCapacity(capacity))));
        return list;
    }

    static ShardedCache<Integer, Integer> sharded(int nodes, int capacityPerNode, HashFunction hash,
                                                  IntFunction<EvictionPolicy<Integer, Integer>> policy,
                                                  PluggableCache.ReadMode mode) {
        ShardedCache<Integer, Integer> c = new ShardedCache<>(hash, 150);
        for (int i = 0; i < nodes; i++) {
            c.addNode(new CacheNode<>("node-" + i, new PluggableCache<>(capacityPerNode, policy.apply(capacityPerNode), mode)));
        }
        return c;
    }

    // ---------------------------------------------------------------- single-threaded run

    public static Result runSingle(Workloads.Kind kind, PolicySpec spec, Config cfg) {
        Integer[] trace = Workloads.trace(kind, cfg.prefix(), cfg.ops, cfg.keys, cfg.capacity, cfg.seed);
        int from = cfg.prefix();

        for (int w = 0; w < cfg.jitWarmups; w++) {
            replayUntimed(spec.factory().get(), trace, 0, trace.length);
        }

        long[] p50s = new long[cfg.trials], p95s = new long[cfg.trials], p99s = new long[cfg.trials];
        double[] tputs = new double[cfg.trials];
        long hits = -1;
        String note = "";
        long[] lat = new long[cfg.ops];
        for (int t = 0; t < cfg.trials; t++) {
            // latency pass
            Cache<Integer, Integer> cache = spec.factory().get();
            replayUntimed(cache, trace, 0, from);
            long h = 0;
            for (int i = 0; i < cfg.ops; i++) {
                Integer k = trace[from + i];
                long s = System.nanoTime();
                Integer v = cache.get(k);
                if (v == null) cache.put(k, VALUE);
                else h++;
                lat[i] = System.nanoTime() - s;
            }
            if (hits >= 0 && hits != h) throw new IllegalStateException("non-deterministic hit count");
            hits = h;
            Arrays.sort(lat);
            p50s[t] = percentile(lat, 0.50);
            p95s[t] = percentile(lat, 0.95);
            p99s[t] = percentile(lat, 0.99);
            note = describe(cache);

            // throughput pass (no per-op timer)
            Cache<Integer, Integer> c2 = spec.factory().get();
            replayUntimed(c2, trace, 0, from);
            long s = System.nanoTime();
            long h2 = replayUntimed(c2, trace, from, trace.length);
            long elapsed = System.nanoTime() - s;
            if (h2 != hits) throw new IllegalStateException("throughput pass hit count differs");
            tputs[t] = cfg.ops / (elapsed / 1e9);
        }
        return new Result(kind.label, spec.name(), cfg.ops, hits, median(tputs),
                median(p50s), median(p95s), median(p99s), note);
    }

    /** Replays trace[from, to) read-through; returns hits. */
    static long replayUntimed(Cache<Integer, Integer> cache, Integer[] trace, int from, int to) {
        long hits = 0;
        for (int i = from; i < to; i++) {
            Integer k = trace[i];
            if (cache.get(k) == null) cache.put(k, VALUE);
            else hits++;
        }
        return hits;
    }

    private static String describe(Cache<Integer, Integer> cache) {
        if (cache instanceof PluggableCache<Integer, Integer> pc
                && pc.policy() instanceof SlidingWindowAdaptivePolicy<Integer, Integer> sw) {
            return String.format(Locale.ROOT, "window=%,d, %d climb steps, %,d second chances",
                    sw.currentWindowSize(), sw.adaptations(), sw.secondChances());
        }
        return "";
    }

    // ---------------------------------------------------------------- multi-threaded run

    public record ConcurrentResult(String config, int threads, double opsPerSec, double hitRate) {
    }

    public static ConcurrentResult runConcurrent(String name, Supplier<Cache<Integer, Integer>> factory,
                                                 int threads, Config cfg) throws InterruptedException {
        int perThread = cfg.ops * 2;
        Integer[][] traces = new Integer[threads][];
        for (int t = 0; t < threads; t++) {
            traces[t] = Workloads.trace(Workloads.Kind.ZIPFIAN, 0, perThread, cfg.keys, cfg.capacity, cfg.seed + 1_000 + t, cfg.seed);
        }
        // JIT warmup on a throwaway instance
        for (int w = 0; w < Math.max(1, cfg.jitWarmups); w++) runThreads(factory.get(), traces, threads);

        int trials = Math.max(1, Math.min(cfg.trials, 3));
        double[] tput = new double[trials];
        double hitRate = 0;
        for (int i = 0; i < trials; i++) {
            Cache<Integer, Integer> cache = factory.get();
            runThreads(cache, traces, threads); // fill (untimed)
            long[] hits = new long[threads];
            long elapsed = runThreadsTimed(cache, traces, threads, hits);
            tput[i] = (double) perThread * threads / (elapsed / 1e9);
            hitRate = (double) Arrays.stream(hits).sum() / ((long) perThread * threads);
        }
        return new ConcurrentResult(name, threads, median(tput), hitRate);
    }

    private static void runThreads(Cache<Integer, Integer> cache, Integer[][] traces, int threads) throws InterruptedException {
        runThreadsTimed(cache, traces, threads, new long[threads]);
    }

    private static long runThreadsTimed(Cache<Integer, Integer> cache, Integer[][] traces, int threads, long[] hits)
            throws InterruptedException {
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        Thread[] ts = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            final int id = t;
            ts[t] = new Thread(() -> {
                ready.countDown();
                try {
                    go.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                hits[id] = replayUntimed(cache, traces[id], 0, traces[id].length);
                done.countDown();
            });
            ts[t].start();
        }
        ready.await();
        long s = System.nanoTime();
        go.countDown();
        done.await();
        long elapsed = System.nanoTime() - s;
        for (Thread t : ts) t.join();
        return elapsed;
    }

    // ---------------------------------------------------------------- stats helpers

    /** Nearest-rank percentile on a sorted array. */
    static long percentile(long[] sorted, double p) {
        int idx = (int) Math.ceil(p * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, idx))];
    }

    private static long median(long[] xs) {
        long[] c = xs.clone();
        Arrays.sort(c);
        return c[c.length / 2];
    }

    private static double median(double[] xs) {
        double[] c = xs.clone();
        Arrays.sort(c);
        return c[c.length / 2];
    }

    /** Smallest observable non-zero step of System.nanoTime() on this machine. */
    static long timerGranularityNs() {
        long min = Long.MAX_VALUE;
        for (int i = 0; i < 200_000; i++) {
            long a = System.nanoTime(), b;
            do {
                b = System.nanoTime();
            } while (b == a);
            min = Math.min(min, b - a);
        }
        return min;
    }

    // ---------------------------------------------------------------- CLI

    public static void main(String[] args) throws Exception {
        Config cfg = new Config();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--ops" -> cfg.ops = Integer.parseInt(args[++i]);
                case "--keys" -> cfg.keys = Integer.parseInt(args[++i]);
                case "--capacity" -> cfg.capacity = Integer.parseInt(args[++i]);
                case "--trials" -> cfg.trials = Integer.parseInt(args[++i]);
                case "--seed" -> cfg.seed = Long.parseLong(args[++i]);
                case "--threads" -> cfg.threads = Arrays.stream(args[++i].split(",")).mapToInt(Integer::parseInt).toArray();
                case "--skip-concurrent" -> cfg.concurrent = false;
                default -> throw new IllegalArgumentException("unknown arg " + args[i]);
            }
        }
        run(cfg);
    }

    public static List<Result> run(Config cfg) throws Exception {
        Runtime rt = Runtime.getRuntime();
        System.out.println("# COSH benchmark");
        System.out.printf(Locale.ROOT, "JVM: %s %s | OS: %s %s | CPUs: %d | max heap: %d MB%n",
                System.getProperty("java.vm.name"), System.getProperty("java.version"),
                System.getProperty("os.name"), System.getProperty("os.arch"),
                rt.availableProcessors(), rt.maxMemory() >> 20);
        System.out.printf(Locale.ROOT,
                "Config: %,d measured requests per run | key space %,d | capacity %,d (%.0f%% of keys) | warm prefix %,d | trials %d (median) | seed %d%n",
                cfg.ops, cfg.keys, cfg.capacity, 100.0 * cfg.capacity / cfg.keys, cfg.prefix(), cfg.trials, cfg.seed);
        long gran = timerGranularityNs();
        System.out.printf(Locale.ROOT, "System.nanoTime() granularity on this machine: %d ns (latencies below are quantized to this step)%n%n", gran);

        List<Result> all = new ArrayList<>();
        for (Workloads.Kind kind : Workloads.Kind.values()) {
            System.out.printf(Locale.ROOT, "## %s%n%n", kind.label);
            System.out.println("| Policy | Hit rate | Throughput (ops/s) | P50 (ns) | P95 (ns) | P99 (ns) | Notes |");
            System.out.println("|---|---:|---:|---:|---:|---:|---|");
            for (PolicySpec spec : policies(cfg.capacity)) {
                Result r = runSingle(kind, spec, cfg);
                all.add(r);
                printRow(r);
            }
            System.out.println();
        }

        System.out.printf(Locale.ROOT, "## Routing overhead (Zipfian): 1 shard x %,d vs ShardedCache 4 shards x %,d (150 vnodes per shard)%n%n",
                cfg.capacity, cfg.capacity / 4);
        System.out.println("| Configuration | Hit rate | Throughput (ops/s) | P50 (ns) | P95 (ns) | P99 (ns) | Notes |");
        System.out.println("|---|---:|---:|---:|---:|---:|---|");
        List<PolicySpec> routing = List.of(
                new PolicySpec("PluggableCache LRU (no routing)", () -> new PluggableCache<>(cfg.capacity, new LruPolicy<>())),
                new PolicySpec("ShardedCache LRU, 4 nodes, MD5 ring hash",
                        () -> sharded(4, cfg.capacity / 4, new MD5HashFunction(), c -> new LruPolicy<>(), PluggableCache.ReadMode.STRICT)),
                new PolicySpec("ShardedCache LRU, 4 nodes, FNV-1a+fmix64 ring hash",
                        () -> sharded(4, cfg.capacity / 4, new Fnv1aMixHashFunction(), c -> new LruPolicy<>(), PluggableCache.ReadMode.STRICT)),
                new PolicySpec("ShardedCache SlidingWindowAdaptive, 4 nodes, FNV-1a+fmix64",
                        () -> sharded(4, cfg.capacity / 4, new Fnv1aMixHashFunction(), SlidingWindowAdaptivePolicy::forCapacity, PluggableCache.ReadMode.STRICT)));
        for (PolicySpec spec : routing) {
            Result r = runSingle(Workloads.Kind.ZIPFIAN, spec, cfg);
            all.add(r);
            printRow(r);
        }
        System.out.println();

        if (cfg.concurrent) {
            System.out.printf(Locale.ROOT, "## Multi-threaded throughput (Zipfian, %,d requests per thread, LRU, total capacity %,d)%n%n",
                    cfg.ops * 2, cfg.capacity);
            System.out.print("| Configuration |");
            for (int t : cfg.threads) System.out.print(" " + t + " thr (ops/s) |");
            System.out.println(" Hit rate @max thr |");
            System.out.print("|---|");
            for (int ignored : cfg.threads) System.out.print("---:|");
            System.out.println("---:|");
            List<PolicySpec> cc = List.of(
                    new PolicySpec("1 shard, STRICT reads (one lock)",
                            () -> new PluggableCache<>(cfg.capacity, new LruPolicy<>())),
                    new PolicySpec("1 shard, BEST_EFFORT reads (tryLock)",
                            () -> new PluggableCache<>(cfg.capacity, new LruPolicy<>(), PluggableCache.ReadMode.BEST_EFFORT)),
                    new PolicySpec("ShardedCache 8 shards, STRICT, FNV-1a+fmix64",
                            () -> sharded(8, cfg.capacity / 8, new Fnv1aMixHashFunction(), c -> new LruPolicy<>(), PluggableCache.ReadMode.STRICT)),
                    new PolicySpec("ShardedCache 8 shards, BEST_EFFORT, FNV-1a+fmix64",
                            () -> sharded(8, cfg.capacity / 8, new Fnv1aMixHashFunction(), c -> new LruPolicy<>(), PluggableCache.ReadMode.BEST_EFFORT)),
                    new PolicySpec("ShardedCache 64 shards, STRICT, FNV-1a+fmix64",
                            () -> sharded(64, Math.max(1, cfg.capacity / 64), new Fnv1aMixHashFunction(), c -> new LruPolicy<>(), PluggableCache.ReadMode.STRICT)));
            for (PolicySpec spec : cc) {
                System.out.print("| " + spec.name() + " |");
                double hr = 0;
                for (int t : cfg.threads) {
                    ConcurrentResult r = runConcurrent(spec.name(), spec.factory(), t, cfg);
                    System.out.printf(Locale.ROOT, " %,.0f |", r.opsPerSec());
                    hr = r.hitRate();
                }
                System.out.printf(Locale.ROOT, " %.2f%% |%n", hr * 100);
            }
            System.out.println();
        }
        return all;
    }

    private static void printRow(Result r) {
        System.out.printf(Locale.ROOT, "| %s | %.2f%% | %,.0f | %,d | %,d | %,d | %s |%n",
                r.policy(), r.hitRate() * 100, r.opsPerSec(), r.p50(), r.p95(), r.p99(), r.note());
    }
}
