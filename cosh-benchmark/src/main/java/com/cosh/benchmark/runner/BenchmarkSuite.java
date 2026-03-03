package com.cosh.benchmark.runner;

import com.cosh.benchmark.report.BenchmarkResult;
import com.cosh.benchmark.workload.WorkloadProfile;

import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates the full benchmark suite: all policy × workload permutations.
 *
 * <p>
 * Runs each combination {@link BenchmarkConfig#repetitions} times via
 * {@link BenchmarkRunner}, collecting averaged {@link BenchmarkResult}s.
 * Results are intended for hand-off to
 * {@link com.cosh.benchmark.report.CsvReporter} and
 * {@link com.cosh.benchmark.report.ConsoleReporter}.
 *
 * <h2>Ordering</h2>
 * <p>
 * Outer loop = workloads, inner loop = policies. This means each workload
 * is compared across all policies before moving on, which keeps reported tables
 * naturally grouped.
 */
public class BenchmarkSuite {

    private final BenchmarkConfig config;
    private final BenchmarkRunner runner;

    public BenchmarkSuite(BenchmarkConfig config) {
        this.config = config;
        this.runner = new BenchmarkRunner(config);
    }

    /**
     * Runs all {@code (workload × policy)} permutations and returns results
     * in execution order. Each (policy × workload) is repeated
     * {@link BenchmarkConfig#repetitions} times and the result averaged.
     */
    public List<BenchmarkResult> runAll() {
        List<BenchmarkResult> results = new ArrayList<>();
        for (WorkloadProfile profile : config.workloads) {
            for (BenchmarkConfig.NamedPolicy named : config.policies) {
                System.out.printf("[BenchmarkSuite] Running %-12s × %-15s (×%d reps)...%n",
                        profile, named.name(), config.repetitions);
                BenchmarkResult result = runner.run(named, profile);
                results.add(result);
                System.out.printf(
                        "  → hit=%.2f%%  avg=%.1fμs  p95=%.1fμs  p99=%.1fμs  σ=%.1fμs  heap=%.1fMB  cpu=%.1f%%%n",
                        result.hitRate() * 100,
                        result.avgUs(), result.p95Us(), result.p99Us(),
                        result.stdDevUs(),
                        result.heapDeltaMb(), result.cpuPct());
            }
        }
        return results;
    }
}
