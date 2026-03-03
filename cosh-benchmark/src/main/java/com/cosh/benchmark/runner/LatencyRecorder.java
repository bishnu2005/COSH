package com.cosh.benchmark.runner;

import java.util.Arrays;

/**
 * Records operation latencies in nanoseconds and computes percentiles.
 *
 * <h2>Implementation</h2>
 * <p>
 * Stores individual timestamps in a pre-allocated {@code long[]} to avoid
 * allocation pressure during measurement. After recording, values are sorted
 * once for O(1) percentile lookup.
 *
 * <h2>Thread safety</h2>
 * <p>
 * NOT thread-safe. Use one instance per thread when benchmarking from
 * multiple threads, then merge via {@link #merge(LatencyRecorder...)}.
 */
public class LatencyRecorder {

    private final long[] samples;
    private int count = 0;
    private boolean sorted = false;

    public LatencyRecorder(int capacity) {
        this.samples = new long[capacity];
    }

    /** Records one latency sample in nanoseconds. */
    public void record(long nanos) {
        if (count < samples.length) {
            samples[count++] = nanos;
            sorted = false;
        }
    }

    public int count() {
        return count;
    }

    /** Resets the recorder for reuse across repetitions. */
    public void reset() {
        count = 0;
        sorted = false;
    }

    /** Average latency in microseconds. Returns 0 if no samples. */
    public double avgUs() {
        if (count == 0)
            return 0;
        long sum = 0;
        for (int i = 0; i < count; i++)
            sum += samples[i];
        return (sum / (double) count) / 1_000.0;
    }

    /** Returns the given percentile in microseconds (0–100). */
    public double percentileUs(double pct) {
        if (count == 0)
            return 0;
        ensureSorted();
        int idx = (int) Math.ceil(pct / 100.0 * count) - 1;
        idx = Math.max(0, Math.min(idx, count - 1));
        return samples[idx] / 1_000.0;
    }

    public double p50Us() {
        return percentileUs(50);
    }

    /** 95th-percentile latency in microseconds. */
    public double p95Us() {
        return percentileUs(95);
    }

    public double p99Us() {
        return percentileUs(99);
    }

    public double maxUs() {
        return percentileUs(100);
    }

    /**
     * Standard deviation of latency in microseconds.
     * Computed from stored samples (population std-dev).
     * Returns 0 if fewer than 2 samples.
     */
    public double stdDevUs() {
        if (count < 2)
            return 0.0;
        double mean = avgUs();
        double sumSq = 0.0;
        for (int i = 0; i < count; i++) {
            double diff = (samples[i] / 1_000.0) - mean;
            sumSq += diff * diff;
        }
        return Math.sqrt(sumSq / count);
    }

    // ─── Merge ───────────────────────────────────────────────────────────────────

    /**
     * Creates a new recorder combining all samples from the given recorders.
     * Used to aggregate per-thread recorders after a parallel benchmark.
     */
    public static LatencyRecorder merge(LatencyRecorder... recorders) {
        int total = 0;
        for (LatencyRecorder r : recorders)
            total += r.count;
        LatencyRecorder merged = new LatencyRecorder(total);
        for (LatencyRecorder r : recorders) {
            for (int i = 0; i < r.count; i++) {
                merged.record(r.samples[i]);
            }
        }
        return merged;
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────────

    private void ensureSorted() {
        if (!sorted) {
            Arrays.sort(samples, 0, count);
            sorted = true;
        }
    }
}
