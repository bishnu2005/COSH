package com.cosh.core;

import com.cosh.eviction.CacheEntry;
import com.cosh.eviction.EvictionPolicy;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Bounded, thread-safe cache whose eviction order is delegated to an {@link EvictionPolicy}.
 *
 * <h2>Concurrency design</h2>
 * <ul>
 *   <li><b>Storage:</b> a {@link ConcurrentHashMap} gives lock-free key lookup and lock-free
 *       {@link #size()}. Entries are published safely, and values are volatile.</li>
 *   <li><b>Ordering:</b> the policy's linked structures are guarded by one {@link ReentrantLock}
 *       per cache instance. Every mutation of the map and the policy, including the reorder a
 *       read triggers, happens under that lock, so the map and the list always change
 *       together. A {@code live} flag, checked under the lock, stops a reader that found
 *       an entry just before it was evicted from linking it back into the list.</li>
 *   <li><b>Scaling:</b> lock granularity is one lock per cache, and {@link ShardedCache} runs N
 *       independent caches, so the hash ring doubles as lock striping.</li>
 * </ul>
 *
 * <h2>Read modes</h2>
 * <ul>
 *   <li>{@link ReadMode#STRICT}: every get takes the lock, so the policy sees every access
 *       (exact LRU/LFU semantics).</li>
 *   <li>{@link ReadMode#BEST_EFFORT}: a get looks up the value without any lock and then
 *       <em>tries</em> the lock to record the access. Under contention the recency update is
 *       skipped. Reads never block, at the cost of approximate ordering, the same trade-off
 *       Caffeine makes with its lossy read buffers. Hit/miss correctness is unaffected.</li>
 * </ul>
 */
public class PluggableCache<K, V> implements Cache<K, V> {

    public enum ReadMode { STRICT, BEST_EFFORT }

    private final int capacity;
    private final ConcurrentHashMap<K, CacheEntry<K, V>> map;
    private final EvictionPolicy<K, V> policy;
    private final ReadMode readMode;
    private final ReentrantLock lock = new ReentrantLock();

    public PluggableCache(int capacity, EvictionPolicy<K, V> policy) {
        this(capacity, policy, ReadMode.STRICT);
    }

    public PluggableCache(int capacity, EvictionPolicy<K, V> policy, ReadMode readMode) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be > 0");
        this.capacity = capacity;
        this.policy = Objects.requireNonNull(policy, "policy");
        this.readMode = Objects.requireNonNull(readMode, "readMode");
        // Sized so the map never resizes while at capacity (load factor 0.75).
        this.map = new ConcurrentHashMap<>((int) Math.min(Integer.MAX_VALUE, capacity * 4L / 3 + 1));
    }

    @Override
    public void put(K key, V value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        lock.lock();
        try {
            CacheEntry<K, V> existing = map.get(key);
            if (existing != null) {
                existing.setValue(value);
                policy.onUpdate(existing);
                return;
            }
            // Evict BEFORE inserting so map.size() never exceeds capacity, even transiently.
            if (map.size() >= capacity) {
                CacheEntry<K, V> victim = policy.evict();
                if (victim == null) {
                    throw new IllegalStateException("policy " + policy.name() + " returned no victim at capacity");
                }
                map.remove(victim.key, victim);
                victim.markLive(false);
            }
            CacheEntry<K, V> entry = new CacheEntry<>(key, value);
            entry.markLive(true);
            map.put(key, entry);
            policy.onPut(entry);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public V get(K key) {
        Objects.requireNonNull(key, "key");
        CacheEntry<K, V> entry = map.get(key); // lock-free lookup
        if (readMode == ReadMode.BEST_EFFORT) {
            if (lock.tryLock()) {
                try {
                    recordRead(key, entry);
                } finally {
                    lock.unlock();
                }
            }
            return entry == null ? null : entry.getValue();
        }
        lock.lock();
        try {
            return recordRead(key, entry) ? entry.getValue() : null;
        } finally {
            lock.unlock();
        }
    }

    /** Must hold lock. Returns true if {@code entry} is a live hit. */
    private boolean recordRead(K key, CacheEntry<K, V> entry) {
        if (entry != null && entry.isLive()) {
            policy.onGet(entry);
            return true;
        }
        // Either absent, or evicted between the lock-free lookup and acquiring the lock.
        // Never touch a dead entry's links: doing so would put it back in the list.
        policy.onMiss(key);
        return false;
    }

    @Override
    public V remove(K key) {
        Objects.requireNonNull(key, "key");
        lock.lock();
        try {
            CacheEntry<K, V> entry = map.remove(key);
            if (entry == null) {
                return null;
            }
            entry.markLive(false);
            policy.onRemove(entry);
            return entry.getValue();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int size() {
        return map.size();
    }

    public int capacity() {
        return capacity;
    }

    public EvictionPolicy<K, V> policy() {
        return policy;
    }

    public boolean containsKey(K key) {
        return map.containsKey(key);
    }

    /** Point-in-time copy of the keys (used for rebalancing). Does not touch the policy. */
    public List<K> keySnapshot() {
        return new ArrayList<>(map.keySet());
    }

    @Override
    public void clear() {
        lock.lock();
        try {
            for (CacheEntry<K, V> e : map.values()) {
                e.markLive(false);
            }
            map.clear();
            policy.clear();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Checks that the map and the policy agree, for tests and diagnostics.
     *
     * @throws IllegalStateException on any corruption
     */
    public void verifyIntegrity() {
        lock.lock();
        try {
            int mapSize = map.size();
            if (mapSize > capacity) throw new IllegalStateException("size " + mapSize + " > capacity " + capacity);
            if (policy.size() != mapSize) {
                throw new IllegalStateException("policy tracks " + policy.size() + " entries, map holds " + mapSize);
            }
            for (CacheEntry<K, V> e : map.values()) {
                if (!e.isLive()) throw new IllegalStateException("dead entry in map: " + e.key);
                if (!e.isLinked()) throw new IllegalStateException("map entry not linked in policy: " + e.key);
            }
            policy.checkInvariants();
        } finally {
            lock.unlock();
        }
    }
}
