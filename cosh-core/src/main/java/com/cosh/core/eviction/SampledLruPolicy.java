package com.cosh.core.eviction;

import com.cosh.core.store.CacheEntry;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Approximate LRU eviction policy based on random sampling.
 *
 * <p>
 * Inspired by Redis's approximation algorithm: instead of maintaining a global
 * LRU queue (expensive), we randomly sample {@value #SAMPLE_SIZE} entries and
 * evict the one with the oldest {@code lastAccessTime}.
 *
 * <p>
 * Thread-safe and stateless — relies solely on
 * {@link CacheEntry#getLastAccessTime()}.
 */
public class SampledLruPolicy implements EvictionPolicy {

    private static final int SAMPLE_SIZE = 5;

    @Override
    public void onGet(String key) {
        // CacheEntry.access() updates the timestamp; no policy state needed here.
    }

    @Override
    public void onPut(String key) {
        // Timestamp is initialized in CacheEntry constructor.
    }

    @Override
    public void onRemove(String key) {
        // Stateless — nothing to clean up.
    }

    @Override
    public String selectEvictionCandidate(Map<String, CacheEntry> store) {
        if (store.isEmpty()) {
            return null;
        }

        int size = store.size();

        // Random offset reduces selection bias on ordered map iteration
        int skip = (size > SAMPLE_SIZE) ? ThreadLocalRandom.current().nextInt(size) : 0;

        Iterator<Map.Entry<String, CacheEntry>> it = store.entrySet().iterator();

        // Advance iterator to random position
        while (skip-- > 0 && it.hasNext()) {
            it.next();
        }

        // If we skipped past the end (concurrent modification edge case), restart
        if (!it.hasNext()) {
            it = store.entrySet().iterator();
        }

        String oldestKey = null;
        long oldestTime = Long.MAX_VALUE;
        int samples = 0;

        while (samples < SAMPLE_SIZE && it.hasNext()) {
            Map.Entry<String, CacheEntry> entry = it.next();
            long accessTime = entry.getValue().getLastAccessTime();
            if (accessTime < oldestTime) {
                oldestTime = accessTime;
                oldestKey = entry.getKey();
            }
            samples++;
        }

        return oldestKey;
    }
}
