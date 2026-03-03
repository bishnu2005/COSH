package com.cosh.benchmark.runner;

import com.cosh.benchmark.report.BenchmarkResult;
import com.cosh.benchmark.workload.WorkloadGenerator;
import com.cosh.benchmark.workload.WorkloadProfile;
import com.cosh.core.eviction.EvictionPolicy;
import com.cosh.core.store.CacheStore;

/**
 * Drives a single benchmark run for one (policy × workload) combination,
 * optionally averaging across {@link BenchmarkConfig#repetitions} repetitions.
 *
 * <h2>Run structure</h2>
 * <ol>
 * <li>Force GC + sleep 100ms to settle heap before each timed rep.</li>
 * <li>Create a fresh {@link CacheStore} with the given policy.</li>
 * <li>Warmup: execute {@link BenchmarkConfig#warmupOps} PUT ops (not
 * timed).</li>
 * <li>Measure: execute {@link BenchmarkConfig#totalOps} GET ops (80%) and PUT
 * ops (20%), recording latency for each.</li>
 * <li>Collect heap delta and average CPU load.</li>
 * <li>Repeat {@link BenchmarkConfig#repetitions} times, then average
 * results.</li>
 * </ol>
 *
 * <h2>Access pattern</h2>
 * <p>
 * 80% GET / 20% PUT ratio is a realistic read-heavy cache workload. PUTs ensure
 * the key space is gradually filled during measurement (avoids all-miss
 * pathology).
 */
public class BenchmarkRunner {

    private static final double PUT_RATIO = 0.20;

    private final BenchmarkConfig config;

    public BenchmarkRunner(BenchmarkConfig config) {
        this.config = config;
    }

    /**
     * Executes one benchmark run averaged across {@code config.repetitions} reps.
     *
     * @param named   the eviction policy with its display name
     * @param profile the workload distribution
     * @return collected metrics (averaged across all repetitions)
     */
    public BenchmarkResult run(BenchmarkConfig.NamedPolicy named, WorkloadProfile profile) {
        int reps = Math.max(1, config.repetitions);

        double[] repAvgUs = new double[reps];
        double[] repP50Us = new double[reps];
        double[] repP95Us = new double[reps];
        double[] repP99Us = new double[reps];
        double[] repMaxUs = new double[reps];
        long[] repHits = new long[reps];
        long[] repMisses = new long[reps];
        double[] repHeap = new double[reps];
        double[] repCpu = new double[reps];

        for (int rep = 0; rep < reps; rep++) {
            // ── Pre-rep GC + settle ──────────────────────────────────────────────
            System.gc();
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            EvictionPolicy policy = named.policy();
            CacheStore store = new CacheStore(config.cacheCapacity, policy);
            WorkloadGenerator gen = new WorkloadGenerator(config.keySpaceSize, config.seed);

            // ── Warmup ──────────────────────────────────────────────────────────
            gen.reset();
            for (int i = 0; i < config.warmupOps; i++) {
                String key = gen.nextKey(profile, i, config.warmupOps);
                store.put(key, config.value, 0);
            }

            // ── Instrument ──────────────────────────────────────────────────────
            HeapSampler heap = new HeapSampler();
            CpuSampler cpu = new CpuSampler();
            LatencyRecorder lats = new LatencyRecorder(config.totalOps);

            long hits = 0;
            long misses = 0;

            gen.reset();
            heap.snapshotBefore();
            cpu.start();

            // ── Timed phase ─────────────────────────────────────────────────────
            for (int i = 0; i < config.totalOps; i++) {
                String key = gen.nextKey(profile, i, config.totalOps);
                boolean isPut = (i % (int) (1.0 / PUT_RATIO) == 0);

                long t0 = System.nanoTime();
                if (isPut) {
                    store.put(key, config.value, 0);
                } else {
                    String val = store.get(key);
                    if (val != null)
                        hits++;
                    else
                        misses++;
                }
                lats.record(System.nanoTime() - t0);
            }

            // ── Collect rep results ──────────────────────────────────────────────
            repCpu[rep] = cpu.stop();
            repHeap[rep] = heap.deltaMb();
            repAvgUs[rep] = lats.avgUs();
            repP50Us[rep] = lats.p50Us();
            repP95Us[rep] = lats.p95Us();
            repP99Us[rep] = lats.p99Us();
            repMaxUs[rep] = lats.maxUs();
            repHits[rep] = hits;
            repMisses[rep] = misses;
        }

        // ── Average across reps ──────────────────────────────────────────────
        double avgUs = mean(repAvgUs);
        double p50Us = mean(repP50Us);
        double p95Us = mean(repP95Us);
        double p99Us = mean(repP99Us);
        double maxUs = mean(repMaxUs);
        double heapDeltaMb = mean(repHeap);
        double cpuPct = mean(repCpu);

        long hits = 0, misses = 0;
        for (int i = 0; i < reps; i++) {
            hits += repHits[i];
            misses += repMisses[i];
        }
        // hits/misses are now summed across reps; get per-rep ratio
        long totalHits = hits / reps;
        long totalMisses = misses / reps;
        long totalMeasured = totalHits + totalMisses;
        double hitRate = (totalMeasured == 0) ? 0.0 : (double) totalHits / totalMeasured;

        // Std-dev of avgUs across repetitions
        double stdDevUs = stdDev(repAvgUs);

        return new BenchmarkResult(
                named.name(),
                profile.name(),
                config.totalOps,
                totalHits,
                totalMisses,
                hitRate,
                avgUs,
                p50Us,
                p95Us,
                p99Us,
                maxUs,
                stdDevUs,
                reps,
                heapDeltaMb,
                cpuPct);
    }

    // ─── Statistics helpers ──────────────────────────────────────────────────────

    private static double mean(double[] v) {
        if (v.length == 0) return 0;
        double sum = 0;
        for (double d : v) sum += d;
        return sum / v.length;
    }

    private static double stdDev(double[] v) {
        if (v.length < 2) return 0;
        double m = mean(v);
        double sumSq = 0;
        for (double d : v) {
            double diff = d - m;
            sumSq += diff * diff;
        }
        return Math.sqrt(sumSq / v.length);
    }
}
