package com.cosh.eviction;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

public class SlidingWindowAdaptivePolicy<K, V> implements EvictionPolicy<K, V> {
    private final DoublyLinkedList<K, V> mainList;
    private final ConcurrentLinkedQueue<K> recentAccessWindow;
    private final int windowSize;
    private final AtomicInteger windowCount;

    public SlidingWindowAdaptivePolicy(int windowSize) {
        this.mainList = new DoublyLinkedList<>();
        this.recentAccessWindow = new ConcurrentLinkedQueue<>();
        this.windowSize = windowSize;
        this.windowCount = new AtomicInteger(0);
    }

    private void recordAccess(K key) {
        recentAccessWindow.offer(key);
        if (windowCount.incrementAndGet() > windowSize) {
            recentAccessWindow.poll();
            windowCount.decrementAndGet();
        }
    }

    @Override
    public void onPut(CacheEntry<K, V> entry) {
        mainList.addFirst(entry);
        recordAccess(entry.key);
    }

    @Override
    public void onGet(CacheEntry<K, V> entry) {
        mainList.moveToFirst(entry);
        recordAccess(entry.key);
    }

    @Override
    public void onRemove(CacheEntry<K, V> entry) {
        mainList.remove(entry);
    }

    @Override
    public CacheEntry<K, V> evict() {
        CacheEntry<K, V> candidate = mainList.removeLast();
        if (candidate == null) {
            return null;
        }

        // Adaptive logic: If the eviction candidate was accessed in our recent sliding window,
        // it gets a second chance (promoted to front), and we evict the next candidate.
        if (recentAccessWindow.contains(candidate.key)) {
            mainList.addFirst(candidate);
            candidate = mainList.removeLast();
        }

        return candidate;
    }

    @Override
    public void clear() {
        recentAccessWindow.clear();
        windowCount.set(0);
        while (mainList.removeLast() != null) {}
    }
}