package com.cosh.adaptive;

import com.cosh.core.eviction.EvictionPolicy;
import com.cosh.core.eviction.SampledLruPolicy;
import com.cosh.core.store.CacheStore;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Synthetic workload comparison: Sampled LRU vs Adaptive eviction.
 *
 * <h2>Workload Design: Zipf Distribution</h2>
 * <p>
 * In real-world cache workloads, access frequency follows a power-law (Zipf)
 * distribution:
 * a small number of keys are accessed far more often than others.
 * This benchmark exploits that structure to highlight where Adaptive
 * outperforms LRU.
 *
 * <h2>Scan-pollution scenario</h2>
 * <p>
 * After warming up with Zipf patterns, we inject a sequential scan of NEW cold
 * keys
 * that causes pure LRU to evict the established hot keys. The adaptive policy,
 * protected by its frequency denominator, resists this pollution.
 *
 * <h2>Parameters</h2>
 * <ul>
 * <li>Keyspace: 1000 unique keys</li>
 * <li>Cache size: 100 (10% of keyspace — realistic working set pressure)</li>
 * <li>Warmup: 20,000 Zipf-distributed requests</li>
 * <li>Scan injection: 200 cold sequential keys</li>
 * <li>Eval phase: 10,000 Zipf-distributed requests</li>
 * </ul>
 */
class WorkloadComparisonTest {

    private static final int KEYSPACE = 1000;
    private static final int CACHE_SIZE = 100;
    private static final int WARMUP_OPS = 20_000;
    private static final int SCAN_SIZE = 200; // cold keys injected as scan
    private static final int EVAL_OPS = 10_000;
    private static final double ZIPF_S = 1.2; // skew parameter: higher = more skewed
    private static final long SEED = 42L;

    // precomputed Zipf probability table
    private static final double[] ZIPF_WEIGHTS = computeZipfWeights(KEYSPACE, ZIPF_S);

    @Test
    void testAdaptiveOutperformsLruUnderScanPollution() {
        HitRateResult lruResult = runWorkload(new SampledLruPolicy());
        HitRateResult adaptiveResult = runWorkload(new AdaptiveEvictionPolicy());

        System.out.println("\n╔══════════════════════════════════════════════════╗");
        System.out.println("║       COSH Eviction Policy Comparison            ║");
        System.out.printf("║  Keyspace: %-4d  Cache size: %-4d  Zipf s=%.1f  ║%n",
                KEYSPACE, CACHE_SIZE, ZIPF_S);
        System.out.println("╠══════════════════════════════════════════════════╣");
        System.out.printf("║  SampledLRU   hit-rate: %6.2f%%                 ║%n", lruResult.hitRatePct());
        System.out.printf("║  Adaptive     hit-rate: %6.2f%%                 ║%n", adaptiveResult.hitRatePct());
        System.out.printf("║  Improvement: %+.2f pp                           ║%n",
                adaptiveResult.hitRatePct() - lruResult.hitRatePct());
        System.out.println("╚══════════════════════════════════════════════════╝\n");

        assertTrue(adaptiveResult.hitRate() >= lruResult.hitRate(),
                String.format("Adaptive (%.2f%%) should >= Sampled LRU (%.2f%%) under scan-pollution workload",
                        adaptiveResult.hitRatePct(), lruResult.hitRatePct()));
    }

    // ─── Workload runner ─────────────────────────────────────────────────────────

    private HitRateResult runWorkload(EvictionPolicy policy) {
        CacheStore store = new CacheStore(CACHE_SIZE, policy);
        Random rng = new Random(SEED);

        // Phase 1: Warmup — establish hot key frequency in the cache
        for (int i = 0; i < WARMUP_OPS; i++) {
            String key = zipfKey(rng);
            String val = store.get(key);
            if (val == null) {
                store.put(key, "v:" + key, 0);
            }
        }

        // Phase 2: Scan pollution — inject SCAN_SIZE cold keys to contaminate LRU order
        for (int i = 0; i < SCAN_SIZE; i++) {
            String coldKey = "scan-cold-" + i;
            store.put(coldKey, "scan-val", 0);
            store.get(coldKey); // single access ensures it flows through LRU recency window
        }

        // Phase 3: Eval — measure hit rate against the Zipf distribution (same seed)
        rng = new Random(SEED);
        int hits = 0;
        for (int i = 0; i < EVAL_OPS; i++) {
            String key = zipfKey(rng);
            if (store.get(key) != null)
                hits++;
        }

        return new HitRateResult(hits, EVAL_OPS);
    }

    // ─── Zipf distribution ───────────────────────────────────────────────────────

    /**
     * Sample a key from the Zipf distribution (1-indexed, mapped to "key-N"
     * strings).
     */
    private static String zipfKey(Random rng) {
        double u = rng.nextDouble();
        double cumulative = 0.0;
        for (int i = 0; i < KEYSPACE; i++) {
            cumulative += ZIPF_WEIGHTS[i];
            if (u <= cumulative) {
                return "key-" + i;
            }
        }
        return "key-" + (KEYSPACE - 1);
    }

    /**
     * Precompute the normalized Zipf probability for each rank. O(N) one-time cost.
     */
    private static double[] computeZipfWeights(int n, double s) {
        double[] weights = new double[n];
        double harmonic = 0.0;
        for (int i = 1; i <= n; i++) {
            harmonic += 1.0 / Math.pow(i, s);
        }
        for (int i = 0; i < n; i++) {
            weights[i] = (1.0 / Math.pow(i + 1, s)) / harmonic;
        }
        return weights;
    }

    // ─── Result record ───────────────────────────────────────────────────────────

    record HitRateResult(int hits, int total) {
        double hitRate() {
            return (double) hits / total;
        }

        double hitRatePct() {
            return hitRate() * 100.0;
        }
    }
}
