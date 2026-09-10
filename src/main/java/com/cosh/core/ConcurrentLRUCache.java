package com.cosh.core;

import com.cosh.eviction.CacheEntry;
import com.cosh.eviction.DoublyLinkedList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class ConcurrentLRUCache<K, V> implements Cache<K, V> {
    private final int capacity;
    private final ConcurrentHashMap<K, CacheEntry<K, V>> map;
    private final DoublyLinkedList<K, V> list;
    private final Lock writeLock = new ReentrantLock();

    public ConcurrentLRUCache(int capacity) {
        this.capacity = capacity;
        this.map = new ConcurrentHashMap<>(capacity);
        this.list = new DoublyLinkedList<>();
    }

    @Override
    public void put(K key, V value) {
        writeLock.lock();
        try {
            CacheEntry<K, V> existingNode = map.get(key);
            if (existingNode != null) {
                existingNode.value = value;
                list.moveToFirst(existingNode);
            } else {
                if (map.size() >= capacity) {
                    CacheEntry<K, V> lruNode = list.removeLast();
                    if (lruNode != null) {
                        map.remove(lruNode.key);
                    }
                }
                CacheEntry<K, V> newNode = new CacheEntry<>(key, value);
                list.addFirst(newNode);
                map.put(key, newNode);
            }
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public V get(K key) {
        CacheEntry<K, V> node = map.get(key);
        if (node != null) {
            list.moveToFirst(node);
            return node.value;
        }
        return null;
    }

    @Override
    public void remove(K key) {
        writeLock.lock();
        try {
            CacheEntry<K, V> node = map.remove(key);
            if (node != null) {
                list.remove(node);
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
            while (list.removeLast() != null) {}
        } finally {
            writeLock.unlock();
        }
    }
}