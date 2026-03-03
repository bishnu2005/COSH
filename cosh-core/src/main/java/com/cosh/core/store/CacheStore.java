package com.cosh.core.store;

import com.cosh.core.error.CoshException;
import com.cosh.core.error.ErrorCode;
import com.cosh.core.eviction.EvictionPolicy;
import com.cosh.core.metrics.CacheMetricsListener;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Core in-memory storage engine for COSH.
 *
 * <p>
 * Design invariants:
 * <ul>
 * <li>100% framework-free — no Spring, no Micrometer, no annotations.</li>
 * <li>Thread-safe via {@link ConcurrentHashMap}.</li>
 * <li>Lazy TTL expiry — expired entries are removed on first access after
 * expiry.</li>
 * <li>Eviction is pluggable via {@link EvictionPolicy}.</li>
 * <li>Metrics are decoupled via {@link CacheMetricsListener} (defaults to
 * NO_OP).</li>
 * </ul>
 */
public class CacheStore {

    private final Map<String, CacheEntry> store = new ConcurrentHashMap<>();
    private final int maxSize;
    private final EvictionPolicy evictionPolicy;
    private CacheMetricsListener metricsListener = CacheMetricsListener.NO_OP;

    public CacheStore(int maxSize, EvictionPolicy evictionPolicy) {
        if (maxSize <= 0) {
            throw new CoshException(ErrorCode.CONFIGURATION_ERROR,
                    "CacheStore maxSize must be positive, got: " + maxSize);
        }
        this.maxSize = maxSize;
        this.evictionPolicy = evictionPolicy;
    }

    /**
     * Attach a metrics listener. Safe to call after construction.
     * Passing {@code null} resets to NO_OP.
     */
    public void setMetricsListener(CacheMetricsListener metricsListener) {
        this.metricsListener = (metricsListener != null) ? metricsListener : CacheMetricsListener.NO_OP;
    }

    /**
     * Store a key-value pair with optional TTL.
     *
     * <p>
     * If the store is at capacity and the key is new, one entry is evicted first.
     *
     * @param key        non-null cache key
     * @param value      non-null value
     * @param ttlSeconds 0 or negative = no expiry
     * @throws CoshException if key or value is null
     */
    public void put(String key, String value, long ttlSeconds) {
        if (key == null || value == null) {
            throw new CoshException(ErrorCode.INVALID_ARGUMENT, "Key and value must not be null");
        }

        // Evict before inserting a NEW key if at capacity
        if (store.size() >= maxSize && !store.containsKey(key)) {
            String candidate = evictionPolicy.selectEvictionCandidate(store);
            if (candidate != null) {
                store.remove(candidate);
                evictionPolicy.onRemove(candidate);
                metricsListener.onEviction();
            }
        }

        store.put(key, new CacheEntry(value, ttlSeconds));
        evictionPolicy.onPut(key);
    }

    /**
     * Retrieve a value. Returns {@code null} for missing or expired entries.
     * Expired entries are lazily removed (no background thread required).
     */
    public String get(String key) {
        CacheEntry entry = store.get(key);
        if (entry == null) {
            metricsListener.onMiss();
            return null;
        }

        if (entry.isExpired()) {
            store.remove(key);
            evictionPolicy.onRemove(key);
            metricsListener.onExpiration();
            metricsListener.onMiss(); // Expired = miss from the caller's perspective
            return null;
        }

        entry.access();
        evictionPolicy.onGet(key);
        metricsListener.onHit();
        return entry.getValue();
    }

    /**
     * Delete a key. A no-op if the key does not exist.
     */
    public void delete(String key) {
        if (store.remove(key) != null) {
            evictionPolicy.onRemove(key);
        }
    }

    /**
     * @return current number of entries (includes entries that may be lazily
     *         expired)
     */
    public int size() {
        return store.size();
    }
}
