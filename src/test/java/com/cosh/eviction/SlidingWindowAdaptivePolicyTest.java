package com.cosh.eviction;

import com.cosh.core.Cache;
import com.cosh.core.PluggableCache;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class SlidingWindowAdaptivePolicyTest {

    @Test
    public void testSecondChanceBehavior() {
        SlidingWindowAdaptivePolicy<String, String> policy = new SlidingWindowAdaptivePolicy<>(10);

        CacheEntry<String, String> e1 = new CacheEntry<>("k1", "v1");
        CacheEntry<String, String> e2 = new CacheEntry<>("k2", "v2");

        policy.onPut(e1);
        policy.onPut(e2); // e1 is at tail (oldest)

        // e1 is in the recent sliding window, receives a second chance, e2 evicted instead
        CacheEntry<String, String> evicted = policy.evict();
        assertEquals("k2", evicted.key);
    }

    @Test
    public void testPluggableCacheIntegration() {
        // Window size set to 1. This ensures "B" falls out of the window after "A" is accessed,
        // allowing standard eviction to occur so we can verify the cache map removes it.
        EvictionPolicy<String, String> policy = new SlidingWindowAdaptivePolicy<>(1);
        Cache<String, String> cache = new PluggableCache<>(2, policy);

        cache.put("A", "1");
        cache.put("B", "2");

        cache.get("A");

        cache.put("C", "3");

        assertNull(cache.get("B"));
        assertNotNull(cache.get("A"));
        assertNotNull(cache.get("C"));
    }
}