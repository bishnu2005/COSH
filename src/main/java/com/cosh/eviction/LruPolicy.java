package com.cosh.eviction;

/**
 * Classic least-recently-used eviction on top of the intrusive {@link DoublyLinkedList}.
 * Head = most recent, tail = victim. Every callback is O(1).
 */
public final class LruPolicy<K, V> implements EvictionPolicy<K, V> {
    private final DoublyLinkedList<K, V> list = new DoublyLinkedList<>();

    @Override
    public void onPut(CacheEntry<K, V> entry) {
        list.addFirst(entry);
    }

    @Override
    public void onGet(CacheEntry<K, V> entry) {
        list.moveToFirst(entry);
    }

    @Override
    public void onRemove(CacheEntry<K, V> entry) {
        list.remove(entry);
    }

    @Override
    public CacheEntry<K, V> evict() {
        return list.removeLast();
    }

    @Override
    public void clear() {
        list.clear();
    }

    @Override
    public int size() {
        return list.size();
    }

    @Override
    public void checkInvariants() {
        list.validate();
    }

    @Override
    public String name() {
        return "LRU";
    }
}
