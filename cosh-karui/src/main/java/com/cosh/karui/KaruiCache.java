package com.cosh.karui;

import com.cosh.core.api.CoshCache;
import com.cosh.core.store.CacheStore;

/**
 * Embedded {@link CoshCache} implementation backed by a {@link CacheStore}.
 *
 * <p>
 * Instantiated exclusively via {@link CoshKarui#start(KaruiConfig)}.
 * Package-private to enforce the factory pattern.
 */
final class KaruiCache implements CoshCache {

    private final CacheStore store;

    KaruiCache(KaruiConfig config) {
        this.store = new CacheStore(config.getMaxSize(), config.getEvictionPolicy());
        // Karui runs without an external metrics bridge — NO_OP by default
    }

    @Override
    public void put(String key, String value, long ttlSeconds) {
        store.put(key, value, ttlSeconds);
    }

    @Override
    public String get(String key) {
        return store.get(key);
    }

    @Override
    public void delete(String key) {
        store.delete(key);
    }

    @Override
    public int size() {
        return store.size();
    }
}
