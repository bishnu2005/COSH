package com.cosh.core;

import com.cosh.eviction.EvictionPolicy;
import com.cosh.eviction.LfuPolicy;
import com.cosh.eviction.LruPolicy;
import com.cosh.eviction.SlidingWindowAdaptivePolicy;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Multi-threaded stress tests. 16 threads are released together by a {@link CountDownLatch}
 * and hammer a small key space (heavy contention, constant eviction) with a mix of
 * get / put / remove / clear / verifyIntegrity. A monitor thread samples {@code size()}
 * the whole time.
 *
 * <p>Each run must show: zero exceptions, every thread finishing (no deadlock or livelock;
 * also checked with {@code ThreadMXBean.findDeadlockedThreads}), size never above capacity
 * at any sampled instant, every value read belonging to the key it was read from,
 * and intact map/list invariants at the end.
 *
 * <p>The committed code before the fix failed the first of these checks: a capacity-100
 * cache grew to 400 entries.
 */
class ConcurrencyStressTest {
    private static final int THREADS = 16;
    private static final int OPS_PER_THREAD = 50_000;
    private static final long KEY_FACTOR = 1_000_000_000L; // value = key * KEY_FACTOR + seq

    static Stream<Arguments> configs() {
        List<Arguments> out = new ArrayList<>();
        for (String policy : new String[]{"LRU", "LFU", "SlidingWindowAdaptive"}) {
            for (PluggableCache.ReadMode mode : PluggableCache.ReadMode.values()) {
                for (int capacity : new int[]{1, 64}) {
                    out.add(Arguments.of(policy, mode, capacity));
                }
            }
        }
        return out.stream();
    }

    static EvictionPolicy<Integer, Long> policy(String name, int capacity) {
        return switch (name) {
            case "LRU" -> new LruPolicy<>();
            case "LFU" -> new LfuPolicy<>();
            default -> SlidingWindowAdaptivePolicy.forCapacity(capacity);
        };
    }

    @ParameterizedTest(name = "{0} {1} capacity={2}")
    @MethodSource("configs")
    void mixedWorkloadPreservesInvariants(String policyName, PluggableCache.ReadMode mode, int capacity) throws Exception {
        PluggableCache<Integer, Long> cache = new PluggableCache<>(capacity, policy(policyName, capacity), mode);
        int keySpace = Math.max(8, capacity * 4);
        runStress(cache, keySpace, cache::verifyIntegrity, capacity);
        cache.verifyIntegrity();
        assertTrue(cache.size() <= capacity);
    }

    @ParameterizedTest(name = "sharded {0}")
    @MethodSource("shardPolicies")
    void shardedClusterUnderTopologyChurnPreservesInvariants(String policyName) throws Exception {
        ShardedCache<Integer, Long> cluster = new ShardedCache<>(new com.cosh.routing.MD5HashFunction(), 64);
        IntFunction<CacheNode<Integer, Long>> node = i ->
                new CacheNode<>("node-" + i, new PluggableCache<>(32, policy(policyName, 32)));
        for (int i = 0; i < 3; i++) cluster.addNode(node.apply(i));

        AtomicBoolean stop = new AtomicBoolean();
        AtomicInteger topologyChanges = new AtomicInteger();
        Thread churn = new Thread(() -> {
            int i = 100;
            while (!stop.get()) {
                cluster.addNode(node.apply(i));
                cluster.removeNode("node-" + i);
                i++;
                topologyChanges.incrementAndGet();
                // ~thousands of topology changes/sec: still far beyond any real cluster, but leaves the
                // read lock available. A zero-pause loop of writers starves readers on a non-fair
                // ReentrantReadWriteLock (documented trade-off, see ENGINEERING_DEEP_DIVE.md).
                java.util.concurrent.locks.LockSupport.parkNanos(200_000);
            }
        });
        churn.start();
        try {
            runStress(cluster, 512, cluster::verifyIntegrity, Integer.MAX_VALUE);
        } finally {
            stop.set(true);
            churn.join(30_000);
        }
        assertFalse(churn.isAlive(), "topology thread hung");
        assertTrue(topologyChanges.get() > 0);
        cluster.verifyIntegrity();
        System.out.printf("[stress] sharded %s survived %d add/remove topology changes%n", policyName, topologyChanges.get());
    }

    static Stream<String> shardPolicies() {
        return Stream.of("LRU", "LFU", "SlidingWindowAdaptive");
    }

    /** Read-your-writes: each thread owns its keys and must never read back an older value. */
    static Stream<Arguments> policyModes() {
        return configs().filter(a -> (int) a.get()[2] == 64).map(a -> Arguments.of(a.get()[0], a.get()[1]));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("policyModes")
    void noStaleOrTornValuesForOwnedKeys(String policyName, PluggableCache.ReadMode mode) throws Exception {
        // capacity 64 = 16 threads x 4 owned keys, so nothing is evicted and every read must hit
        PluggableCache<Integer, Long> cache = new PluggableCache<>(64, policy(policyName, 64), mode);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        for (int t = 0; t < THREADS; t++) {
            final int tid = t;
            pool.submit(() -> {
                try {
                    start.await();
                    long[] last = new long[4];
                    for (int i = 1; i <= OPS_PER_THREAD; i++) {
                        int slot = i & 3;
                        int key = tid * 4 + slot;               // 16 threads x 4 keys = 64 = capacity
                        long v = key * KEY_FACTOR + i;
                        cache.put(key, v);
                        last[slot] = v;
                        Long read = cache.get(key);
                        if (read == null || read != last[slot]) {
                            throw new AssertionError("key " + key + " expected " + last[slot] + " got " + read);
                        }
                    }
                } catch (Throwable e) {
                    errors.add(e);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "threads did not finish (deadlock?)");
        pool.shutdownNow();
        assertTrue(errors.isEmpty(), "stale/torn reads: " + errors.stream().limit(3).toList());
        cache.verifyIntegrity();
    }

    // ------------------------------------------------------------------ harness

    private static void runStress(Cache<Integer, Long> cache, int keySpace, Runnable integrityCheck,
                                  int maxSize) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicInteger maxObserved = new AtomicInteger();

        Thread monitor = new Thread(() -> {
            while (running.get()) {
                int s = cache.size();
                maxObserved.accumulateAndGet(s, Math::max);
            }
        });
        monitor.start();

        for (int t = 0; t < THREADS; t++) {
            pool.submit(() -> {
                ThreadLocalRandom rnd = ThreadLocalRandom.current();
                try {
                    start.await();
                    for (int i = 0; i < OPS_PER_THREAD; i++) {
                        int key = rnd.nextInt(keySpace);
                        int op = rnd.nextInt(1_000);
                        if (op < 550) {
                            Long v = cache.get(key);
                            if (v != null && v / KEY_FACTOR != key) {
                                throw new AssertionError("value " + v + " returned for key " + key);
                            }
                        } else if (op < 900) {
                            cache.put(key, key * KEY_FACTOR + i);
                        } else if (op < 990) {
                            cache.remove(key);
                        } else if (op < 999) {
                            integrityCheck.run(); // validate while others mutate
                        } else {
                            cache.clear();
                        }
                    }
                } catch (Throwable e) {
                    errors.add(e);
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        boolean finished = done.await(120, TimeUnit.SECONDS);
        running.set(false);
        monitor.join();
        long[] deadlocked = ManagementFactory.getThreadMXBean().findDeadlockedThreads();
        pool.shutdownNow();

        assertNull(deadlocked, "deadlocked threads detected");
        assertTrue(finished, "worker threads did not finish within timeout");
        assertTrue(errors.isEmpty(), errors.size() + " errors, first: " + (errors.isEmpty() ? "" : errors.get(0)));
        assertTrue(maxObserved.get() <= maxSize, "size reached " + maxObserved.get() + " > capacity " + maxSize);
    }
}
