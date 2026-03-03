package com.cosh.core.config;

import com.cosh.core.eviction.EvictionPolicy;
import com.cosh.core.eviction.SampledLruPolicy;
import com.cosh.core.metrics.CacheMetricsListener;

/**
 * Immutable configuration for a {@code CacheStore} instance.
 *
 * <p>
 * Use {@link #builder()} for a fluent construction experience.
 * Sensible defaults are provided for all fields.
 *
 * <p>
 * Used by both {@code cosh-karui} (embedded mode) and
 * {@code cosh-grid-node} (Spring-wired via {@code @Bean}).
 */
public final class CoshConfig {

    private final int maxSize;
    private final EvictionPolicy evictionPolicy;
    private final CacheMetricsListener metricsListener;

    private CoshConfig(Builder builder) {
        this.maxSize = builder.maxSize;
        this.evictionPolicy = builder.evictionPolicy;
        this.metricsListener = builder.metricsListener;
    }

    public int getMaxSize() {
        return maxSize;
    }

    public EvictionPolicy getEvictionPolicy() {
        return evictionPolicy;
    }

    public CacheMetricsListener getMetricsListener() {
        return metricsListener;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private int maxSize = 1000;
        private EvictionPolicy evictionPolicy = new SampledLruPolicy();
        private CacheMetricsListener metricsListener = CacheMetricsListener.NO_OP;

        /** Maximum number of entries before eviction runs. Default: 1000. */
        public Builder maxSize(int maxSize) {
            this.maxSize = maxSize;
            return this;
        }

        /** Eviction algorithm. Default: {@link SampledLruPolicy}. */
        public Builder evictionPolicy(EvictionPolicy evictionPolicy) {
            this.evictionPolicy = evictionPolicy;
            return this;
        }

        /** Metrics bridge. Default: {@link CacheMetricsListener#NO_OP}. */
        public Builder metricsListener(CacheMetricsListener metricsListener) {
            this.metricsListener = metricsListener;
            return this;
        }

        public CoshConfig build() {
            return new CoshConfig(this);
        }
    }
}
