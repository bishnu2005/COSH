package com.cosh.eviction;

/**
 * Strategy interface for eviction. {@code PluggableCache} owns storage (the map) and
 * concurrency (the lock). The policy owns only the order in which entries become victims.
 *
 * <p><b>Threading:</b> every callback is invoked while the owning cache holds its lock,
 * so implementations do not need to be thread-safe. They must not block or call back
 * into the cache.
 *
 * <p><b>Complexity contract:</b> every callback, including {@link #evict()}, should run
 * in O(1) (amortized or bounded-constant) time. All built-in policies meet this.
 */
public interface EvictionPolicy<K, V> {

    /** A new entry was inserted after a miss. */
    void onPut(CacheEntry<K, V> entry);

    /** A read hit on {@code entry}. */
    void onGet(CacheEntry<K, V> entry);

    /** An existing key was overwritten. By default treated as an access. */
    default void onUpdate(CacheEntry<K, V> entry) {
        onGet(entry);
    }

    /** A read for {@code key} missed. Policies that learn from misses can override this. */
    default void onMiss(K key) {
    }

    /** The entry was explicitly removed by the client (not evicted). */
    void onRemove(CacheEntry<K, V> entry);

    /**
     * Chooses a victim, unlinks it from the policy's structures, and returns it.
     * Returns null only if the policy tracks no entries.
     */
    CacheEntry<K, V> evict();

    /** Forgets all tracked entries. */
    void clear();

    /** Number of entries currently tracked; must equal the cache's map size. */
    int size();

    /** Validates internal structure; throws {@link IllegalStateException} if corrupt. */
    default void checkInvariants() {
    }

    default String name() {
        return getClass().getSimpleName();
    }
}
