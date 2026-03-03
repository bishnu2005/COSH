package com.cosh.benchmark.report;

import java.util.List;

/**
 * Prints benchmark results as a formatted ASCII table to standard output.
 *
 * <p>
 * Suitable for surefire log capture and terminal inspection.
 * Includes P95 and std-dev columns for statistical rigor.
 */
public class ConsoleReporter {

    // Header widths: Policy(16), Workload(12), Ops(6), Runs(4), HitRate(8),
    //                Avg(7), P50(7), P95(7), P99(7), Max(7), σ(7), HeapΔMB(8), CPU%(8)
    private static final String DIVIDER =
            "╠══════════════════╪══════════════╪════════╪══════╪══════════╪═════════╪" +
            "═════════╪═════════╪═════════╪═════════╪═════════╪══════════╪══════════╣";
    private static final String TOP =
            "╔══════════════════╤══════════════╤════════╤══════╤══════════╤═════════╤" +
            "═════════╤═════════╤═════════╤═════════╤═════════╤══════════╤══════════╗";
    private static final String BOTTOM =
            "╚══════════════════╧══════════════╧════════╧══════╧══════════╧═════════╧" +
            "═════════╧═════════╧═════════╧═════════╧═════════╧══════════╧══════════╝";

    public void print(List<BenchmarkResult> results) {
        System.out.println("\n" + TOP);
        System.out.printf(
                "║ %-16s │ %-12s │ %6s │ %4s │ %8s │ %7s │ %7s │ %7s │ %7s │ %7s │ %7s │ %8s │ %7s ║%n",
                "Policy", "Workload", "Ops", "Runs", "HitRate",
                "Avg μs", "P50 μs", "P95 μs", "P99 μs", "Max μs", "σ μs",
                "HeapΔMB", "CPU%");
        System.out.println(DIVIDER);

        for (BenchmarkResult r : results) {
            System.out.printf(
                    "║ %-16s │ %-12s │ %6d │ %4d │ %7.2f%% │ %7.1f │ %7.1f │ %7.1f │ %7.1f │ %7.1f │ %7.1f │ %8.2f │ %7.1f%% ║%n",
                    r.policyName(),
                    r.workloadName(),
                    r.totalOps(),
                    r.runCount(),
                    r.hitRate() * 100,
                    r.avgUs(),
                    r.p50Us(),
                    r.p95Us(),
                    r.p99Us(),
                    r.maxUs(),
                    r.stdDevUs(),
                    r.heapDeltaMb(),
                    r.cpuPct());
        }
        System.out.println(BOTTOM);
        System.out.println();
    }
}
