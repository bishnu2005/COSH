package com.cosh.benchmark;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Keeps the CLI benchmark honest and runnable. It replays a small configuration and checks
 * the metrics are internally consistent and deterministic. It asserts nothing about
 * performance numbers, which belong in the real run (see README).
 */
class BenchmarkRunnerSmokeTest {

    @Test
    void everyWorkloadAndPolicyProducesConsistentMetrics() {
        BenchmarkRunner.Config cfg = BenchmarkRunner.Config.small();
        for (Workloads.Kind kind : Workloads.Kind.values()) {
            for (BenchmarkRunner.PolicySpec spec : BenchmarkRunner.policies(cfg.capacity)) {
                BenchmarkRunner.Result r = BenchmarkRunner.runSingle(kind, spec, cfg);
                assertEquals(cfg.ops, r.requests());
                assertTrue(r.hitRate() > 0 && r.hitRate() < 1, kind + "/" + spec.name() + " hit rate " + r.hitRate());
                assertTrue(r.p50() <= r.p95() && r.p95() <= r.p99(), "percentiles must be monotonic");
                assertTrue(r.opsPerSec() > 0);

                BenchmarkRunner.Result again = BenchmarkRunner.runSingle(kind, spec, cfg);
                assertEquals(r.hits(), again.hits(), "same seed must give identical hit counts");
            }
        }
    }

    @Test
    void tracesAreDeterministicAndShaped() {
        Integer[] a = Workloads.trace(Workloads.Kind.ZIPFIAN, 0, 50_000, 1_000, 100, 7);
        Integer[] b = Workloads.trace(Workloads.Kind.ZIPFIAN, 0, 50_000, 1_000, 100, 7);
        assertArrayEquals(a, b);

        // Zipf(0.99) over 1,000 keys: the top 10% of keys should carry well over half the traffic
        int[] counts = new int[1_000];
        for (Integer k : a) counts[k]++;
        int[] sorted = counts.clone();
        java.util.Arrays.sort(sorted);
        long top = 0;
        for (int i = 900; i < 1_000; i++) top += sorted[i];
        assertTrue(top > 25_000, "Zipf skew too weak: top-10% share " + top);

        // shifting: hot keys of the first and last phase must differ
        Integer[] s = Workloads.trace(Workloads.Kind.SHIFTING, 0, 40_000, 1_000, 100, 7);
        assertNotEquals(mostFrequent(s, 0, 10_000), mostFrequent(s, 30_000, 40_000));
    }

    @Test
    void percentileUsesNearestRank() {
        long[] xs = new long[100];
        for (int i = 0; i < 100; i++) xs[i] = i + 1;
        assertEquals(50, BenchmarkRunner.percentile(xs, 0.50));
        assertEquals(95, BenchmarkRunner.percentile(xs, 0.95));
        assertEquals(99, BenchmarkRunner.percentile(xs, 0.99));
        assertEquals(100, BenchmarkRunner.percentile(xs, 1.0));
    }

    private static Set<Integer> mostFrequent(Integer[] t, int from, int to) {
        java.util.Map<Integer, Integer> c = new java.util.HashMap<>();
        for (int i = from; i < to; i++) c.merge(t[i], 1, Integer::sum);
        Set<Integer> top = new HashSet<>();
        c.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue()).limit(10).forEach(e -> top.add(e.getKey()));
        return top;
    }
}
