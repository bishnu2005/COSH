package com.cosh.benchmark.runner;

import com.sun.management.OperatingSystemMXBean;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Samples JVM process CPU load during a benchmark run.
 *
 * <h2>Methodology</h2>
 * <ul>
 * <li>Uses {@link com.sun.management.OperatingSystemMXBean#getProcessCpuLoad()}
 * which returns the fraction of time this JVM process used the CPU
 * since the last call (0.0–1.0).</li>
 * <li>Sampled every 100ms via a background daemon thread.</li>
 * <li>Values of {@code -1.0} (unavailable) are silently discarded.</li>
 * <li>Final result = arithmetic mean of valid samples × 100 (percent).</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * 
 * <pre>
 * CpuSampler sampler = new CpuSampler();
 * sampler.start();
 * // ... run benchmark ...
 * double cpuPct = sampler.stop();
 * </pre>
 */
public class CpuSampler {

    private static final long SAMPLE_INTERVAL_MS = 100;

    private final OperatingSystemMXBean osMxBean;
    private final List<Double> samples = new ArrayList<>();
    private final AtomicInteger validCount = new AtomicInteger(0);

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> future;

    public CpuSampler() {
        this.osMxBean = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    }

    /** Starts background CPU sampling. */
    public void start() {
        samples.clear();
        validCount.set(0);
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cpu-sampler");
            t.setDaemon(true);
            return t;
        });
        future = scheduler.scheduleAtFixedRate(this::sample,
                SAMPLE_INTERVAL_MS, SAMPLE_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Stops sampling and returns average CPU percentage over the run.
     * Returns {@code 0.0} if no valid samples were collected (e.g., on some JVMs).
     */
    public double stop() {
        if (future != null)
            future.cancel(false);
        if (scheduler != null)
            scheduler.shutdownNow();

        synchronized (samples) {
            if (samples.isEmpty())
                return 0.0;
            double sum = samples.stream().mapToDouble(Double::doubleValue).sum();
            return (sum / samples.size()) * 100.0;
        }
    }

    // ─── Internal ────────────────────────────────────────────────────────────────

    private void sample() {
        double load = osMxBean.getProcessCpuLoad();
        if (load >= 0.0) { // -1.0 means unavailable on this JVM/OS
            synchronized (samples) {
                samples.add(load);
            }
            validCount.incrementAndGet();
        }
    }
}
