package com.cosh.core;

/**
 * Minimal key/value cache contract.
 *
 * <p>Null keys and null values are rejected with {@link NullPointerException}. A
 * {@code null} return from {@link #get} therefore always means "miss" and is never ambiguous.
 */
public interface Cache<K, V> {
    void put(K key, V value);

    /** @return the cached value, or null on a miss */
    V get(K key);

    /** @return the removed value, or null if the key was absent */
    V remove(K key);

    int size();

    void clear();
}
