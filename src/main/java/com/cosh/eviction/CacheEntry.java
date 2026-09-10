package com.cosh.eviction;

public class CacheEntry<K, V> {
    public final K key;
    public V value;
    public CacheEntry<K, V> prev;
    public CacheEntry<K, V> next;

    public CacheEntry(K key, V value) {
        this.key = key;
        this.value = value;
    }
}