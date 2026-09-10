package com.cosh.core;

import com.cosh.eviction.CacheEntry;
import com.cosh.eviction.EvictionPolicy;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class PluggableCache<K, V> implements Cache<K, V> {
    private final int capacity;
    private final ConcurrentHashMap<K, CacheEntry<K, V>> map;
    private final EvictionPolicy<K, V> policy;
    private final Lock writeLock = new ReentrantLock();

    public PluggableCache(int capacity, EvictionPolicy<K, V> policy) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be > 0");
        this.capacity = capacity;
        this.map = new ConcurrentHashMap<>(capacity);
        this.policy = policy;
    }

    @Override
    public void put(K key, V value) {
        writeLock.lock();
        try {
            CacheEntry<K, V> existingEntry = map.get(key);
            if (existingEntry != null) {
                existingEntry.value = value;
                policy.onGet(existingEntry);
            } else {
                if (map.size() >= capacity) {
                    CacheEntry<K, V> evicted = policy.evict();
                    if (evicted != null) {
                        map.remove(evicted.key);
                    }
                }
                CacheEntry<K, V> newEntry = new CacheEntry<>(key, value);
                map.put(key, newEntry);
                policy.onPut(newEntry);
            }
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public V get(K key) {
        CacheEntry<K, V> entry = map.get(key);
        if (entry != null) {
            policy.onGet(entry);
            return entry.value;
        }
        return null;
    }

    @Override
    public void remove(K key) {
        writeLock.lock();
        try {
            CacheEntry<K, V> entry = map.remove(key);
            if (entry != null) {
                policy.onRemove(entry);
            }
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public int size() {
        return map.size();
    }

    @Override
    public void clear() {
        writeLock.lock();
        try {
            map.clear();
            policy.clear();
        } finally {
            writeLock.unlock();
        }
    }
}