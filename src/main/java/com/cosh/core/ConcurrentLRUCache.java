package com.cosh.core;

import com.cosh.eviction.LruPolicy;

/**
 * Thread-safe LRU cache: {@link PluggableCache} preconfigured with {@link LruPolicy}.
 *
 * <p>It used to be a separate copy of the map + list + lock logic. That copy carried the same
 * lost-update race as the original {@code PluggableCache}, so it now reuses the single
 * corrected implementation.
 */
public class ConcurrentLRUCache<K, V> extends PluggableCache<K, V> {
    public ConcurrentLRUCache(int capacity) {
        super(capacity, new LruPolicy<>());
    }
}
