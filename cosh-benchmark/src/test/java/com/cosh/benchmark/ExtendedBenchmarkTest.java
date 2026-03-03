package com.cosh.benchmark;

import com.cosh.adaptive.AdaptiveEvictionPolicy;
import com.cosh.benchmark.report.BenchmarkResult;
import com.cosh.benchmark.report.ConsoleReporter;
import com.cosh.benchmark.report.CsvReporter;
import com.cosh.benchmark.runner.BenchmarkConfig;
import com.cosh.benchmark.runner.BenchmarkSuite;
import com.cosh.benchmark.workload.WorkloadProfile;
import com.cosh.core.eviction.SampledLruPolicy;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Extended benchmark: 100,000 ops, Zipf s=1.0/s=1.2/s=1.5 + HOT_KEY_SPIKE.
 *
 * <h2>Purpose</h2>
 * <ul>
 * <li>Confirm Adaptive ≥ SampledLRU hit rate − 5% under all skews.</li>
 * <li>GC impact report (minor count + total pause time).</li>
 * <li>CSV output for paper: {@code target/benchmark_results_extended.csv}.</li>
 * </ul>
 *
 * <h2>Statistical validity</h2>
 * <p>
 * Each scenario runs 5 repetitions with GC+sleep between reps. Results are
 * averaged; std-dev is reported.
 */
class ExtendedBenchmarkTest {

    private static final Path CSV_OUT = Path.of("target", "benchmark_results_extended.csv");
    private static final int OPS = 100_000;
    private static final int WARMUP = 20_000;
    private static final int REPS = 5;

    @Test
    void extendedBenchmarkWithSkewedZipf() throws Exception {
        List<BenchmarkConfig.NamedPolicy> policies = List.of(
                new BenchmarkConfig.NamedPolicy("SampledLRU", new SampledLruPolicy()),
                new BenchmarkConfig.NamedPolicy("Adaptive", new AdaptiveEvictionPolicy()));

        List<BenchmarkResult> allResults = new ArrayList<>();

        // ── Run 1: s=1.0 (baseline) ──────────────────────────────────────────
        allResults.addAll(runScenario("ZIPF_s1.0", policies, WorkloadProfile.ZIPF, OPS));
        // ── Run 2: s=1.2 ─────────────────────────────────────────────────────
        allResults.addAll(runScenario("ZIPF_s1.2", policies, WorkloadProfile.ZIPF_S12, OPS));
        // ── Run 3: s=1.5 ─────────────────────────────────────────────────────
        allResults.addAll(runScenario("ZIPF_s1.5", policies, WorkloadProfile.ZIPF_S15, OPS));
        // ── Run 4: hot-key spike ─────────────────────────────────────────────
        allResults.addAll(runScenario("HOT_KEY", policies, WorkloadProfile.HOT_KEY_SPIKE, OPS));

        new ConsoleReporter().print(allResults);
        new CsvReporter().write(allResults, CSV_OUT);

        // GC report
        reportGcStats();

        // ── Structural assertions ─────────────────────────────────────────────
        assertFalse(allResults.isEmpty(), "Results must not be empty");
        for (BenchmarkResult r : allResults) {
            assertTrue(r.totalOps() > 0, "totalOps must be positive");
            assertEquals(REPS, r.runCount(), "Each result must have " + REPS + " reps: " + r.policyName());
            assertTrue(r.stdDevUs() >= 0, "stdDevUs must be non-negative");
        }
        File csv = CSV_OUT.toFile();
        assertTrue(csv.exists(), "Extended CSV must be written");
        assertTrue(csv.length() > 0, "Extended CSV must be non-empty");

        // ── Hit-rate floor assertions (Adaptive >= SampledLRU - 5%) ─────────
        // Group by scenario label
        Map<String, Map<String, Double>> hitRates = allResults.stream()
                .collect(Collectors.groupingBy(
                        BenchmarkResult::workloadName,
                        Collectors.toMap(BenchmarkResult::policyName, BenchmarkResult::hitRate)));

        for (String scenario : List.of("ZIPF_s1.0", "ZIPF_s1.2", "ZIPF_s1.5", "HOT_KEY")) {
            Map<String, Double> rates = hitRates.get(scenario);
            if (rates == null) continue;
            double adaptiveRate = rates.getOrDefault("Adaptive", 0.0);
            double lruRate = rates.getOrDefault("SampledLRU", 0.0);
            assertTrue(adaptiveRate >= lruRate - 0.05,
                    String.format("[%s] Adaptive hit-rate %.3f must be >= SampledLRU %.3f - 5%%",
                            scenario, adaptiveRate, lruRate));
            System.out.printf("[HitRateAssertion] %s: Adaptive=%.3f SampledLRU=%.3f diff=%+.3f ✓%n",
                    scenario, adaptiveRate, lruRate, adaptiveRate - lruRate);
        }

        System.out.println("\n[ExtendedBenchmark] CSV → " + csv.getAbsolutePath());
        System.out.println("[ExtendedBenchmark] " + allResults.size() + " results written.");
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────────

    /**
     * Runs all policies against the given real workload profile with the specified
     * Zipf skew properly wired through WorkloadGenerator (not faked via capacity).
     */
    private List<BenchmarkResult> runScenario(String label,
            List<BenchmarkConfig.NamedPolicy> policies,
            WorkloadProfile profile,
            int ops) {

        BenchmarkConfig config = BenchmarkConfig.builder()
                .totalOps(ops)
                .warmupOps(WARMUP)
                .cacheCapacity(1_000)
                .keySpaceSize(5_000)
                .seed(42L)
                .value("v".repeat(64))
                .workloads(List.of(profile))
                .policies(policies)
                .repetitions(REPS)
                .build();

        System.out.printf("\n── Scenario: %-12s  ops=%,d  reps=%d ──%n", label, ops, REPS);

        List<BenchmarkResult> results = new BenchmarkSuite(config).runAll();

        // Print hit-rate comparison
        results.stream()
                .filter(r -> r.policyName().equals("Adaptive") || r.policyName().equals("SampledLRU"))
                .forEach(r -> System.out.printf("  [%s] %s  hit=%.2f%%  avg=%.2fμs  p95=%.2fμs  σ=%.2fμs%n",
                        label, r.policyName(), r.hitRate() * 100,
                        r.avgUs(), r.p95Us(), r.stdDevUs()));

        // Annotate results with the scenario label as workload name for CSV
        List<BenchmarkResult> labelled = new ArrayList<>();
        for (BenchmarkResult r : results) {
            labelled.add(new BenchmarkResult(
                    r.policyName(),
                    label,
                    r.totalOps(), r.hits(), r.misses(), r.hitRate(),
                    r.avgUs(), r.p50Us(), r.p95Us(), r.p99Us(), r.maxUs(),
                    r.stdDevUs(), r.runCount(),
                    r.heapDeltaMb(), r.cpuPct()));
        }
        return labelled;
    }

    private void reportGcStats() {
        long totalGcCount = 0, totalGcMs = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            long count = gc.getCollectionCount();
            long time = gc.getCollectionTime();
            if (count < 0) count = 0;
            if (time < 0) time = 0;
            totalGcCount += count;
            totalGcMs += time;
            System.out.printf("[GC] %-30s  count=%d  time=%dms%n", gc.getName(), count, time);
        }
        System.out.printf("[GC] TOTAL: %d collections, %dms pause over full test JVM lifetime%n",
                totalGcCount, totalGcMs);
    }
}
