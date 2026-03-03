package com.cosh.core.store;

/**
 * An immutable value container stored in the cache.
 * Tracks last access time (for LRU) and an absolute expiry timestamp (for TTL).
 *
 * <p>
 * Thread-safe access via volatile {@code lastAccessTime}.
 */
public final class CacheEntry {

    private final String value;
    private final long expiryTime; // Absolute epoch-millis; -1 = no expiry
    private volatile long lastAccessTime; // Epoch-nanos, updated on each access

    public CacheEntry(String value, long ttlSeconds) {
        this.value = value;
        this.lastAccessTime = System.nanoTime();
        this.expiryTime = (ttlSeconds <= 0) ? -1L
                : System.currentTimeMillis() + (ttlSeconds * 1000L);
    }

    /** Returns the stored value. */
    public String getValue() {
        return value;
    }

    /** Updates the last-access timestamp. Called on every successful GET. */
    public void access() {
        this.lastAccessTime = System.nanoTime();
    }

    /** Returns the last-access time in nanoseconds (for eviction ordering). */
    public long getLastAccessTime() {
        return lastAccessTime;
    }

    /**
     * @return {@code true} if this entry has a TTL and it has elapsed.
     */
    public boolean isExpired() {
        return expiryTime != -1L && System.currentTimeMillis() > expiryTime;
    }
}
