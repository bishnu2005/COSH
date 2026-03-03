package com.cosh.benchmark.report;

/**
 * Immutable result from one benchmark run (one policy × one workload),
 * potentially averaged across multiple repetitions.
 *
 * <p>
 * All latency fields are in microseconds. {@code cpu_pct} is 0–100 (percent).
 * {@code heapDeltaMb} may be negative if GC reclaimed allocations mid-run.
 * {@code stdDevUs} is the standard deviation of avgUs across repetitions (0 if
 * only one run). {@code runCount} is the number of repetitions averaged.
 */
public record BenchmarkResult(
        String policyName,
        String workloadName,
        int totalOps,
        long hits,
        long misses,
        double hitRate,
        double avgUs,
        double p50Us,
        double p95Us,
        double p99Us,
        double maxUs,
        double stdDevUs,
        int runCount,
        double heapDeltaMb,
        double cpuPct) {

    /** Convenience constructor for a single-rep result (stdDevUs=0, runCount=1). */
    public BenchmarkResult(
            String policyName, String workloadName, int totalOps,
            long hits, long misses, double hitRate,
            double avgUs, double p50Us, double p99Us, double maxUs,
            double heapDeltaMb, double cpuPct) {
        this(policyName, workloadName, totalOps, hits, misses, hitRate,
                avgUs, p50Us, /* p95Us */ p99Us, p99Us, maxUs,
                /* stdDevUs */ 0.0, /* runCount */ 1,
                heapDeltaMb, cpuPct);
    }

    /** Hit rate as 0–100 percentage string. */
    public String hitRatePct() {
        return String.format("%.2f%%", hitRate * 100);
    }
}
