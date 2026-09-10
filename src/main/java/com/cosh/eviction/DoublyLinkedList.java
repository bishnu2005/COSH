package com.cosh.eviction;

import java.util.concurrent.locks.ReentrantLock;

public class DoublyLinkedList<K, V> {
    private final CacheEntry<K, V> head;
    private final CacheEntry<K, V> tail;
    private final ReentrantLock lock = new ReentrantLock();

    public DoublyLinkedList() {
        head = new CacheEntry<>(null, null);
        tail = new CacheEntry<>(null, null);
        head.next = tail;
        tail.prev = head;
    }

    public void addFirst(CacheEntry<K, V> node) {
        lock.lock();
        try {
            node.next = head.next;
            node.prev = head;
            head.next.prev = node;
            head.next = node;
        } finally {
            lock.unlock();
        }
    }

    public void remove(CacheEntry<K, V> node) {
        lock.lock();
        try {
            if (node.prev != null && node.next != null) {
                node.prev.next = node.next;
                node.next.prev = node.prev;
                node.prev = null;
                node.next = null;
            }
        } finally {
            lock.unlock();
        }
    }

    public void moveToFirst(CacheEntry<K, V> node) {
        lock.lock();
        try {
            remove(node);
            addFirst(node);
        } finally {
            lock.unlock();
        }
    }

    public CacheEntry<K, V> removeLast() {
        lock.lock();
        try {
            if (tail.prev == head) {
                return null;
            }
            CacheEntry<K, V> last = tail.prev;
            remove(last);
            return last;
        } finally {
            lock.unlock();
        }
    }
}