package com.cosh.eviction;

public interface EvictionPolicy<K, V> {
    void onPut(CacheEntry<K, V> entry);
    void onGet(CacheEntry<K, V> entry);
    void onRemove(CacheEntry<K, V> entry);
    CacheEntry<K, V> evict();
    void clear();
}