package com.cosh.benchmark.report;

import com.opencsv.CSVWriter;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Writes {@link BenchmarkResult}s to a structured CSV file for paper export.
 *
 * <h2>Column layout</h2>
 *
 * <pre>
 * policy,workload,ops,run_count,hit_rate,avg_us,p50_us,p95_us,p99_us,max_us,std_dev_us,heap_mb,cpu_pct
 * </pre>
 */
public class CsvReporter {

    private static final String[] HEADER = {
            "policy", "workload", "ops", "run_count", "hit_rate",
            "avg_us", "p50_us", "p95_us", "p99_us", "max_us",
            "std_dev_us", "heap_mb", "cpu_pct"
    };

    /**
     * Writes results to the given path. Creates parent directories if needed.
     *
     * @param results non-empty list of benchmark results
     * @param path    target CSV file path
     * @throws IOException if the file cannot be written
     */
    public void write(List<BenchmarkResult> results, Path path) throws IOException {
        Path parent = path.getParent();
        if (parent != null)
            parent.toFile().mkdirs();

        try (CSVWriter writer = new CSVWriter(new FileWriter(path.toFile()))) {
            writer.writeNext(HEADER);
            for (BenchmarkResult r : results) {
                writer.writeNext(toRow(r));
            }
        }
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    private String[] toRow(BenchmarkResult r) {
        return new String[] {
                r.policyName(),
                r.workloadName(),
                String.valueOf(r.totalOps()),
                String.valueOf(r.runCount()),
                String.format("%.4f", r.hitRate()),
                String.format("%.2f", r.avgUs()),
                String.format("%.2f", r.p50Us()),
                String.format("%.2f", r.p95Us()),
                String.format("%.2f", r.p99Us()),
                String.format("%.2f", r.maxUs()),
                String.format("%.2f", r.stdDevUs()),
                String.format("%.2f", r.heapDeltaMb()),
                String.format("%.2f", r.cpuPct())
        };
    }
}
