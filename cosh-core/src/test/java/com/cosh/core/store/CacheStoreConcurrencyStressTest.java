package com.cosh.core.store;

import com.cosh.core.eviction.SampledLruPolicy;
import org.junit.jupiter.api.Test;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Concurrency stress test for {@link CacheStore}.
 *
 * <h2>Test parameters</h2>
 * <ul>
 * <li>16 threads, 100,000 mixed ops (80% GET / 20% PUT)</li>
 * <li>Cache capacity: 500 (small — maximises eviction pressure)</li>
 * <li>Key space: 2,000 (4× capacity — guarantees constant evictions)</li>
 * </ul>
 *
 * <h2>Assertions</h2>
 * <ul>
 * <li>No exceptions / {@code ConcurrentModificationException}</li>
 * <li>Cache size stays in [0, capacity]</li>
 * <li>At least 1 cache hit (warmup seeds the cache)</li>
 * <li>Completes within 60 seconds</li>
 * </ul>
 *
 * <p>
 * Kept in {@code cosh-core} (depends only on core). For Adaptive policy
 * concurrency stress, see {@code AdaptiveConcurrencyStressTest} in
 * {@code cosh-adaptive}.
 */
class CacheStoreConcurrencyStressTest {

    private static final int THREADS = 16;
    private static final int TOTAL_OPS = 100_000;
    private static final int CAPACITY = 500;
    private static final int KEY_SPACE = 2_000;
    private static final String VALUE = "x".repeat(32);

    @Test
    void stressSampledLruUnderHighContention() throws Exception {
        CacheStore store = new CacheStore(CAPACITY, new SampledLruPolicy());

        // Warmup: seed 200 keys so GETs aren't all misses
        for (int i = 0; i < 200; i++) {
            store.put("key-" + i, VALUE, 0);
        }

        AtomicInteger counter = new AtomicInteger(0);
        AtomicLong hits = new AtomicLong(0);
        AtomicLong misses = new AtomicLong(0);
        AtomicInteger errors = new AtomicInteger(0);
        CountDownLatch done = new CountDownLatch(THREADS);
        CyclicBarrier barrier = new CyclicBarrier(THREADS);
        ExecutorService exec = Executors.newFixedThreadPool(THREADS);

        for (int t = 0; t < THREADS; t++) {
            exec.submit(() -> {
                try {
                    barrier.await();
                    int idx;
                    while ((idx = counter.getAndIncrement()) < TOTAL_OPS) {
                        String key = "key-" + (idx % KEY_SPACE);
                        try {
                            if (idx % 5 == 0) {
                                store.put(key, VALUE, 0);
                            } else {
                                String v = store.get(key);
                                if (v != null)
                                    hits.incrementAndGet();
                                else
                                    misses.incrementAndGet();
                            }
                        } catch (Exception ex) {
                            errors.incrementAndGet();
                            System.err.println("[Stress] " + ex.getClass().getSimpleName());
                        }
                    }
                } catch (Exception ex) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        boolean completed = done.await(60, TimeUnit.SECONDS);
        exec.shutdown();

        long getOps = hits.get() + misses.get();
        double hitRate = getOps == 0 ? 0.0 : (double) hits.get() / getOps;

        System.out.printf("%n[ConcurrencyStress] threads=%d  ops=%,d  hits=%,d  " +
                "misses=%,d  hitRate=%.2f%%  errors=%d  finalSize=%d%n",
                THREADS, TOTAL_OPS, hits.get(), misses.get(),
                hitRate * 100, errors.get(), store.size());

        assertTrue(completed, "Must complete within 60 seconds");
        assertEquals(0, errors.get(), "Zero exceptions during concurrent access");
        assertTrue(hits.get() > 0, "At least some hits expected after warmup");
        assertTrue(store.size() >= 0, "Cache size must be non-negative");
        assertTrue(store.size() <= CAPACITY, "Cache size must not exceed capacity");
    }
}
