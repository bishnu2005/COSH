package com.cosh.eviction;

import com.cosh.core.PluggableCache;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SlidingWindowAdaptivePolicyTest {

    private static <K, V> SlidingWindowAdaptivePolicy<K, V> fixed(int window) {
        return new SlidingWindowAdaptivePolicy<>(window);
    }

    @Test
    void frequentlyRequestedTailEntryGetsASecondChance() {
        SlidingWindowAdaptivePolicy<String, String> policy = fixed(100);
        PluggableCache<String, String> cache = new PluggableCache<>(3, policy);
        cache.put("hot", "h");
        cache.get("hot");
        cache.get("hot");           // 2 requests in window -> protected
        cache.put("a", "1");
        cache.put("b", "2");        // LRU order (tail first): hot, a, b

        cache.put("c", "3");        // plain LRU would evict "hot"
        assertTrue(cache.containsKey("hot"), "hot key should survive via second chance");
        assertFalse(cache.containsKey("a"), "next unprotected tail entry is evicted instead");
        assertEquals(1, policy.secondChances());
        cache.verifyIntegrity();
    }

    @Test
    void protectionExpiresWhenRequestsSlideOutOfTheWindow() {
        SlidingWindowAdaptivePolicy<String, String> policy = fixed(4);
        PluggableCache<String, String> cache = new PluggableCache<>(3, policy);
        cache.put("hot", "h");
        cache.get("hot");
        cache.get("hot");
        cache.put("a", "1");
        cache.put("b", "2");
        for (int i = 0; i < 4; i++) cache.get("missing-" + i); // 4 misses push both "hot" requests out
        cache.put("c", "3");
        assertFalse(cache.containsKey("hot"), "stale-hot key must lose protection once outside the window");
        assertEquals(0, policy.secondChances());
    }

    @Test
    void evictionCostIsBoundedEvenWhenEveryEntryIsProtected() {
        SlidingWindowAdaptivePolicy<Integer, Integer> policy = fixed(10_000);
        PluggableCache<Integer, Integer> cache = new PluggableCache<>(100, policy);
        for (int i = 0; i < 100; i++) {
            cache.put(i, i);
            cache.get(i);
            cache.get(i);
        }
        int evictions = 1_000;
        for (int i = 0; i < evictions; i++) {
            cache.put(1_000 + i, i);
            cache.get(1_000 + i);
            cache.get(1_000 + i);   // newcomers are hot too
        }
        assertTrue(policy.secondChances() <= (long) policy.maxProbes() * evictions,
                "at most maxProbes second chances per eviction, saw " + policy.secondChances());
        assertEquals(100, cache.size());
        cache.verifyIntegrity();
    }

    @Test
    void newlyInsertedKeyIsNotProtectedByItsOwnFill() {
        // miss + fill is one logical request; a one-hit wonder must not earn a second chance
        SlidingWindowAdaptivePolicy<String, String> policy = fixed(100);
        PluggableCache<String, String> cache = new PluggableCache<>(2, policy);
        for (String k : new String[]{"x", "y", "z"}) {
            if (cache.get(k) == null) cache.put(k, k);
        }
        assertFalse(cache.containsKey("x"));
        assertEquals(0, policy.secondChances());
    }

    @Test
    void hillClimberMovesWindowAndStaysWithinBounds() {
        SlidingWindowAdaptivePolicy<String, String> policy =
                new SlidingWindowAdaptivePolicy<>(400, 100, 1_600, 2, 4, 100);
        PluggableCache<String, String> cache = new PluggableCache<>(50, policy);
        List<Integer> trajectory = new ArrayList<>();
        int key = 0;
        for (int round = 0; round < 60; round++) {
            // alternate between a cache-friendly and a cache-hostile phase so hit rate swings
            boolean friendly = (round / 10) % 2 == 0;
            for (int i = 0; i < 100; i++) {
                String k = friendly ? "hot-" + (i % 20) : "cold-" + (key++);
                if (cache.get(k) == null) cache.put(k, k);
            }
            int w = policy.currentWindowSize();
            assertTrue(w >= 100 && w <= 1_600, "window out of bounds: " + w);
            trajectory.add(w);
        }
        assertTrue(policy.adaptations() >= 50, "climber should step once per 100 reads");
        assertTrue(trajectory.stream().distinct().count() > 3, "window should actually move: " + trajectory);
        cache.verifyIntegrity();
    }

    @Test
    void fixedWindowConstructorNeverAdapts() {
        SlidingWindowAdaptivePolicy<String, String> policy = fixed(64);
        PluggableCache<String, String> cache = new PluggableCache<>(16, policy);
        for (int i = 0; i < 10_000; i++) {
            String k = "k" + (i % 40);
            if (cache.get(k) == null) cache.put(k, k);
        }
        assertEquals(64, policy.currentWindowSize());
        assertEquals(0, policy.adaptations());
    }

    @Test
    void forCapacityDerivesSaneBounds() {
        SlidingWindowAdaptivePolicy<String, String> p = SlidingWindowAdaptivePolicy.forCapacity(1_000);
        assertEquals(4_000, p.currentWindowSize());
        assertThrows(IllegalArgumentException.class, () -> SlidingWindowAdaptivePolicy.forCapacity(0));
        assertThrows(IllegalArgumentException.class,
                () -> new SlidingWindowAdaptivePolicy<String, String>(10, 20, 30, 2, 4, 100));
    }
}
