package com.cosh.core.metrics;

/**
 * Pure Java observer interface for cache lifecycle events.
 *
 * <p>
 * Implementations bridge events to Micrometer, logging, or any other
 * monitoring system — without coupling the core engine to any framework.
 *
 * <p>
 * The {@link #NO_OP} default does nothing, keeping cosh-core framework-free.
 */
public interface CacheMetricsListener {

    /** Called when a cache GET returns a valid, non-expired value. */
    void onHit();

    /** Called when a cache GET finds no entry, or the entry is expired. */
    void onMiss();

    /** Called when a key is lazily removed due to TTL expiry during a GET. */
    void onExpiration();

    /** Called when an entry is evicted to make room for a new one. */
    void onEviction();

    /**
     * Default no-op listener — used when no metrics system is attached.
     * Ensures the core engine never needs a null check.
     */
    CacheMetricsListener NO_OP = new CacheMetricsListener() {
        @Override
        public void onHit() {
        }

        @Override
        public void onMiss() {
        }

        @Override
        public void onExpiration() {
        }

        @Override
        public void onEviction() {
        }
    };
}
