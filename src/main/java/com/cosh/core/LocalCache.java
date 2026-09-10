package com.cosh.core;

import java.util.concurrent.ConcurrentHashMap;

public class LocalCache<K, V> implements Cache<K, V> {
    private final int capacity;
    private final ConcurrentHashMap<K, V> store;

    public LocalCache(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be greater than 0");
        }
        this.capacity = capacity;
        this.store = new ConcurrentHashMap<>();
    }

    @Override
    public void put(K key, V value) {
        if (store.size() >= capacity && !store.containsKey(key)) {
            // Placeholder capacity handling until Phase 4/5 eviction framework
            throw new IllegalStateException("Cache capacity reached");
        }
        store.put(key, value);
    }

    @Override
    public V get(K key) {
        return store.get(key);
    }

    @Override
    public void remove(K key) {
        store.remove(key);
    }

    @Override
    public int size() {
        return store.size();
    }

    @Override
    public void clear() {
        store.clear();
    }
}