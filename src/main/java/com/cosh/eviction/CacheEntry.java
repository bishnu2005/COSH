package com.cosh.eviction;

/**
 * A single cache entry. It is stored in the cache's {@code ConcurrentHashMap} and is, at
 * the same time, a node of an intrusive doubly linked list owned by the eviction policy.
 *
 * <p>Because the entry is its own list node, removing or reordering it takes O(1) once the map
 * lookup has found it. There is no separate list-node lookup and no extra allocation per access.
 *
 * <p>Threading contract:
 * <ul>
 *   <li>{@code value} is {@code volatile}: readers on the lock-free path always see the most
 *       recent value written under the cache lock (no stale reads after an update).</li>
 *   <li>{@code prev}, {@code next}, {@code frequency} and {@code live} are only read or written
 *       while holding the owning cache's lock, so they need no volatile/atomic semantics.</li>
 * </ul>
 */
public final class CacheEntry<K, V> {
    public final K key;
    private volatile V value;

    // --- guarded by the owning cache's lock ---
    CacheEntry<K, V> prev;
    CacheEntry<K, V> next;
    /** Access count, used by {@link LfuPolicy}. */
    long frequency;
    /** True while the entry is in the cache map; false once removed or evicted. */
    boolean live;

    public CacheEntry(K key, V value) {
        this.key = key;
        this.value = value;
    }

    public V getValue() {
        return value;
    }

    public void setValue(V value) {
        this.value = value;
    }

    /** Caller must hold the owning cache's lock. */
    public boolean isLive() {
        return live;
    }

    /** Caller must hold the owning cache's lock. */
    public void markLive(boolean live) {
        this.live = live;
    }

    /** True if this entry is currently linked into some {@link DoublyLinkedList}. */
    public boolean isLinked() {
        return prev != null && next != null;
    }
}
