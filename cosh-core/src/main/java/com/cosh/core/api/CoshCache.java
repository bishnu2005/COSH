package com.cosh.core.api;

/**
 * CoshCache — the universal cache interface in COSH.
 *
 * <p>
 * All implementations (Karui embedded, Grid node) implement this contract.
 * Framework-free. No Spring. No Micrometer. Pure Java.
 */
public interface CoshCache {

    /**
     * Store a key-value pair with an optional TTL.
     *
     * @param key        non-null cache key
     * @param value      non-null value to store
     * @param ttlSeconds time-to-live in seconds; 0 or negative means no expiry
     */
    void put(String key, String value, long ttlSeconds);

    /**
     * Retrieve a value by key.
     *
     * @param key the cache key
     * @return the value, or {@code null} if the key does not exist or has expired
     */
    String get(String key);

    /**
     * Delete a key from the cache.
     *
     * @param key the cache key to remove
     */
    void delete(String key);

    /**
     * @return the current number of entries in the cache
     */
    int size();
}
