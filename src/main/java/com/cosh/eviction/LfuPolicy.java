package com.cosh.eviction;

import java.util.HashMap;

/**
 * O(1) least-frequently-used eviction (Shah, Mitra and Matani, "An O(1) algorithm for
 * implementing the LFU cache eviction scheme", 2010).
 *
 * <pre>
 *   bucketHead &lt;-&gt; [freq=1: e7 e3] &lt;-&gt; [freq=2: e1] &lt;-&gt; [freq=5: e2 e9] &lt;-&gt; bucketHead
 *                      ^ victim bucket (lowest freq); victim = its LRU tail
 * </pre>
 *
 * Buckets form a circular doubly linked list sorted by frequency. Each bucket holds
 * an intrusive {@link DoublyLinkedList} of entries, so ties at the same frequency are
 * broken by recency. An access moves an entry to the adjacent {@code freq+1} bucket,
 * creating it if needed. Insert, access, remove and evict are all O(1). No heap and
 * no scan over frequencies are needed.
 */
public final class LfuPolicy<K, V> implements EvictionPolicy<K, V> {

    private static final class Bucket<K, V> {
        final long freq;
        final DoublyLinkedList<K, V> entries = new DoublyLinkedList<>();
        Bucket<K, V> prev;
        Bucket<K, V> next;

        Bucket(long freq) {
            this.freq = freq;
        }
    }

    /** Sentinel: bucketHead.next is the lowest-frequency bucket. */
    private final Bucket<K, V> bucketHead = new Bucket<>(0);
    private final HashMap<Long, Bucket<K, V>> buckets = new HashMap<>();
    private int size;

    public LfuPolicy() {
        bucketHead.next = bucketHead;
        bucketHead.prev = bucketHead;
    }

    @Override
    public void onPut(CacheEntry<K, V> entry) {
        entry.frequency = 1;
        Bucket<K, V> first = bucketHead.next;
        Bucket<K, V> target = (first != bucketHead && first.freq == 1) ? first : insertBucketAfter(bucketHead, 1);
        target.entries.addFirst(entry);
        size++;
    }

    @Override
    public void onGet(CacheEntry<K, V> entry) {
        Bucket<K, V> current = buckets.get(entry.frequency);
        long nextFreq = entry.frequency + 1;
        Bucket<K, V> target = (current.next != bucketHead && current.next.freq == nextFreq)
                ? current.next
                : insertBucketAfter(current, nextFreq);
        current.entries.remove(entry);
        entry.frequency = nextFreq;
        target.entries.addFirst(entry);
        if (current.entries.isEmpty()) {
            unlinkBucket(current);
        }
    }

    @Override
    public void onRemove(CacheEntry<K, V> entry) {
        Bucket<K, V> bucket = buckets.get(entry.frequency);
        if (bucket != null && bucket.entries.remove(entry)) {
            size--;
            if (bucket.entries.isEmpty()) {
                unlinkBucket(bucket);
            }
        }
    }

    @Override
    public CacheEntry<K, V> evict() {
        Bucket<K, V> lowest = bucketHead.next;
        if (lowest == bucketHead) {
            return null;
        }
        CacheEntry<K, V> victim = lowest.entries.removeLast();
        size--;
        if (lowest.entries.isEmpty()) {
            unlinkBucket(lowest);
        }
        return victim;
    }

    @Override
    public void clear() {
        for (Bucket<K, V> b = bucketHead.next; b != bucketHead; b = b.next) {
            b.entries.clear();
        }
        buckets.clear();
        bucketHead.next = bucketHead;
        bucketHead.prev = bucketHead;
        size = 0;
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public void checkInvariants() {
        int total = 0;
        long lastFreq = 0;
        int bucketCount = 0;
        for (Bucket<K, V> b = bucketHead.next; b != bucketHead; b = b.next) {
            if (b.freq <= lastFreq) throw new IllegalStateException("buckets not strictly ascending");
            if (b.entries.isEmpty()) throw new IllegalStateException("empty bucket left linked: freq " + b.freq);
            if (buckets.get(b.freq) != b) throw new IllegalStateException("bucket index out of sync");
            total += b.entries.validate();
            lastFreq = b.freq;
            bucketCount++;
        }
        if (bucketCount != buckets.size()) throw new IllegalStateException("bucket map has stale buckets");
        if (total != size) throw new IllegalStateException("size=" + size + " but buckets hold " + total);
    }

    @Override
    public String name() {
        return "LFU";
    }

    private Bucket<K, V> insertBucketAfter(Bucket<K, V> anchor, long freq) {
        Bucket<K, V> b = new Bucket<>(freq);
        b.prev = anchor;
        b.next = anchor.next;
        anchor.next.prev = b;
        anchor.next = b;
        buckets.put(freq, b);
        return b;
    }

    private void unlinkBucket(Bucket<K, V> b) {
        b.prev.next = b.next;
        b.next.prev = b.prev;
        buckets.remove(b.freq);
    }
}
