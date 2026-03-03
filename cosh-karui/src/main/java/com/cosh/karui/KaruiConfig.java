package com.cosh.karui;

import com.cosh.core.eviction.EvictionPolicy;
import com.cosh.core.eviction.SampledLruPolicy;

/**
 * Fluent configuration for a {@link CoshKarui} embedded cache instance.
 *
 * <p>
 * Example:
 * 
 * <pre>{@code
 * KaruiConfig config = KaruiConfig.builder()
 *         .maxSize(5000)
 *         .evictionPolicy(new SampledLruPolicy())
 *         .build();
 *
 * CoshCache cache = CoshKarui.start(config);
 * }</pre>
 */
public final class KaruiConfig {

    private final int maxSize;
    private final EvictionPolicy evictionPolicy;

    private KaruiConfig(Builder builder) {
        this.maxSize = builder.maxSize;
        this.evictionPolicy = builder.evictionPolicy;
    }

    public int getMaxSize() {
        return maxSize;
    }

    public EvictionPolicy getEvictionPolicy() {
        return evictionPolicy;
    }

    /** Start building a new {@link KaruiConfig} with sensible defaults. */
    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private int maxSize = 1000;
        private EvictionPolicy evictionPolicy = new SampledLruPolicy();

        /**
         * Maximum number of entries before eviction runs.
         * Default: {@code 1000}.
         */
        public Builder maxSize(int maxSize) {
            if (maxSize <= 0)
                throw new IllegalArgumentException("maxSize must be > 0");
            this.maxSize = maxSize;
            return this;
        }

        /**
         * Eviction policy to apply when the store is full.
         * Default: {@link SampledLruPolicy} (Redis-style approximate LRU).
         */
        public Builder evictionPolicy(EvictionPolicy evictionPolicy) {
            this.evictionPolicy = evictionPolicy;
            return this;
        }

        public KaruiConfig build() {
            return new KaruiConfig(this);
        }
    }
}
