package com.cosh.grid.node.metrics;

import com.cosh.core.metrics.CacheMetricsListener;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Bridges {@link CacheMetricsListener} events to Micrometer counters.
 *
 * <p>
 * This is the ONLY place in cosh-grid-node where Micrometer is imported.
 * The core engine ({@code CacheStore}) never knows about Micrometer.
 *
 * <p>
 * Metric namespace: {@code cosh.*}
 */
public class MicrometerMetricsBridge implements CacheMetricsListener {

    private final Counter hits;
    private final Counter misses;
    private final Counter expirations;
    private final Counter evictions;

    public MicrometerMetricsBridge(MeterRegistry registry) {
        this.hits = registry.counter("cosh.hits");
        this.misses = registry.counter("cosh.misses");
        this.expirations = registry.counter("cosh.ttl.expirations");
        this.evictions = registry.counter("cosh.evictions");
    }

    @Override
    public void onHit() {
        hits.increment();
    }

    @Override
    public void onMiss() {
        misses.increment();
    }

    @Override
    public void onExpiration() {
        expirations.increment();
    }

    @Override
    public void onEviction() {
        evictions.increment();
    }
}
