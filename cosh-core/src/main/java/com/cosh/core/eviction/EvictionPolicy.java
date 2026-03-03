package com.cosh.core.eviction;

import com.cosh.core.store.CacheEntry;
import java.util.Map;

/**
 * SPI for cache eviction algorithms.
 *
 * <p>
 * Implementations are stateless (preferred) or manage their own internal state.
 * The interface is designed for ServiceLoader discovery in
 * {@code cosh-adaptive}.
 *
 * <p>
 * The three lifecycle callbacks — {@code onGet}, {@code onPut},
 * {@code onRemove} —
 * allow policies to maintain ordering metadata without coupling to
 * {@link CacheEntry}.
 */
public interface EvictionPolicy {

    /**
     * Called <em>after</em> a successful GET (non-expired key found).
     *
     * @param key the accessed cache key
     */
    void onGet(String key);

    /**
     * Called <em>after</em> a successful PUT (new or updated key).
     *
     * @param key the inserted or updated cache key
     */
    void onPut(String key);

    /**
     * Called <em>after</em> a key is removed (eviction, deletion, or expiry).
     *
     * @param key the removed cache key
     */
    void onRemove(String key);

    /**
     * Selects the key to evict when the store is full.
     *
     * @param store live snapshot of the current cache entries
     * @return key to evict, or {@code null} if the store is empty
     */
    String selectEvictionCandidate(Map<String, CacheEntry> store);
}
