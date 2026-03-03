package com.cosh.benchmark.runner;

/**
 * Measures JVM heap usage before and after a benchmark run.
 *
 * <h2>Methodology</h2>
 * <ol>
 * <li>Trigger System.gc() twice before recording baseline (stabilise
 * heap).</li>
 * <li>Record used heap = {@code totalMemory - freeMemory}.</li>
 * <li>After the run, trigger GC twice again, record used heap.</li>
 * <li>Delta = after - before.</li>
 * </ol>
 *
 * <p>
 * Positive delta means the benchmark caused net heap growth (e.g., the
 * policy's internal data structures). Negative or near-zero means GC reclaimed
 * transient allocations cleanly.
 *
 * <h2>Limitations</h2>
 * <ul>
 * <li>Concurrent GC threads may cause non-determinism; the double-GC call
 * minimises but does not eliminate this.</li>
 * <li>Results should be treated as estimates (±2 MB typical noise on JVM).</li>
 * </ul>
 */
public class HeapSampler {

    private long beforeBytes = 0;

    /**
     * Records heap before the benchmark. Call this immediately before the
     * timed run begins.
     */
    public void snapshotBefore() {
        stabilise();
        beforeBytes = usedBytes();
    }

    /**
     * Returns heap delta in megabytes. Call immediately after the timed run.
     */
    public double deltaMb() {
        stabilise();
        long afterBytes = usedBytes();
        return (afterBytes - beforeBytes) / (1024.0 * 1024.0);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────────

    private static void stabilise() {
        System.gc();
        System.gc();
        // Brief sleep to allow concurrent GC threads to finish
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static long usedBytes() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }
}
