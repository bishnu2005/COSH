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
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Full benchmark smoke test — runs all workloads × all policies.
 *
 * <h2>Stability rules</h2>
 * <ul>
 * <li>Assertions are ONLY: no exceptions, CSV generated, ops > 0.</li>
 * <li>NO numeric performance assertions (no "Adaptive must beat LRU").</li>
 * <li>All metrics are informational — printed to stdout for paper.</li>
 * </ul>
 *
 * <h2>Configuration</h2>
 * <ul>
 * <li>100,000 ops / 20,000 warmup / 5 repetitions per (policy × workload)</li>
 * <li>Workloads: UNIFORM, ZIPF, ZIPF_S12, ZIPF_S15, HOT_KEY_SPIKE</li>
 * </ul>
 */
class BenchmarkSmokeTest {

    private static final Path CSV_OUT = Path.of("target", "benchmark_results.csv");

    @Test
    void runFullBenchmarkSuite() throws Exception {
        BenchmarkConfig config = BenchmarkConfig.builder()
                .totalOps(100_000)
                .warmupOps(20_000)
                .cacheCapacity(1_000)
                .keySpaceSize(2_000)
                .seed(42L)
                .value("v".repeat(64))
                .workloads(List.of(WorkloadProfile.values()))
                .policies(List.of(
                        new BenchmarkConfig.NamedPolicy("SampledLRU", new SampledLruPolicy()),
                        new BenchmarkConfig.NamedPolicy("Adaptive", new AdaptiveEvictionPolicy())))
                .repetitions(5)
                .build();

        BenchmarkSuite suite = new BenchmarkSuite(config);
        List<BenchmarkResult> results = suite.runAll();

        new ConsoleReporter().print(results);
        new CsvReporter().write(results, CSV_OUT);

        // Structural assertions only
        // 5 workloads × 2 policies = 10 results
        assertEquals(10, results.size(), "Expected 5 workloads x 2 policies = 10 results");
        for (BenchmarkResult r : results) {
            assertTrue(r.totalOps() > 0, "totalOps must be > 0 for " + r.policyName());
            assertTrue(r.hits() + r.misses() >= 0, "hits/misses must be non-negative");
            assertEquals(5, r.runCount(), "Each result must have 5 reps: " + r.policyName());
            assertTrue(r.stdDevUs() >= 0, "stdDevUs must be non-negative: " + r.policyName());
        }

        File csv = CSV_OUT.toFile();
        assertTrue(csv.exists(), "CSV file must be created");
        assertTrue(csv.length() > 0, "CSV file must be non-empty");

        System.out.println("\n[BenchmarkSmokeTest] CSV written to: " + csv.getAbsolutePath());
        System.out.println("[BenchmarkSmokeTest] All assertions passed — " + results.size() + " runs complete.");
    }
}
