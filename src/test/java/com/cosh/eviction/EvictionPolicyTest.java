package com.cosh.eviction;

import com.cosh.core.PluggableCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.util.function.IntFunction;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Behaviour and complexity tests shared by every built-in policy, plus per-policy ordering tests. */
class EvictionPolicyTest {

    static Stream<Arguments> policies() {
        return Stream.of(
                Arguments.of("LRU", (IntFunction<EvictionPolicy<Integer, Integer>>) c -> new LruPolicy<>()),
                Arguments.of("LFU", (IntFunction<EvictionPolicy<Integer, Integer>>) c -> new LfuPolicy<>()),
                Arguments.of("SlidingWindowAdaptive",
                        (IntFunction<EvictionPolicy<Integer, Integer>>) SlidingWindowAdaptivePolicy::forCapacity));
    }

    // ------------------------------------------------------------------ shared contract

    @ParameterizedTest(name = "{0}")
    @MethodSource("policies")
    void neverExceedsCapacityAndStaysConsistent(String name, IntFunction<EvictionPolicy<Integer, Integer>> factory) {
        PluggableCache<Integer, Integer> cache = new PluggableCache<>(50, factory.apply(50));
        for (int i = 0; i < 5_000; i++) {
            cache.put(i % 173, i);
            cache.get((i * 7) % 211);
            if (i % 13 == 0) cache.remove(i % 97);
            assertTrue(cache.size() <= 50);
        }
        cache.verifyIntegrity();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("policies")
    void capacityOneAlwaysHoldsTheLatestKey(String name, IntFunction<EvictionPolicy<Integer, Integer>> factory) {
        PluggableCache<Integer, Integer> cache = new PluggableCache<>(1, factory.apply(1));
        for (int i = 0; i < 1_000; i++) {
            cache.put(i, i);
            cache.get(i);
            cache.get(i);           // make it "hot" so adaptive policies try to protect it
            assertEquals(1, cache.size());
            assertEquals(i, cache.get(i));
            if (i > 0) assertNull(cache.get(i - 1));
        }
        cache.verifyIntegrity();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("policies")
    void evictReturnsNullWhenEmptyAndClearResets(String name, IntFunction<EvictionPolicy<Integer, Integer>> factory) {
        EvictionPolicy<Integer, Integer> p = factory.apply(8);
        assertNull(p.evict());
        PluggableCache<Integer, Integer> cache = new PluggableCache<>(8, p);
        for (int i = 0; i < 8; i++) cache.put(i, i);
        cache.clear();
        assertEquals(0, cache.size());
        assertEquals(0, p.size());
        assertNull(p.evict());
        cache.put(1, 1);
        cache.verifyIntegrity();
    }

    /**
     * Complexity guard: 300,000 evictions from a 100,000-entry cache. With O(1) eviction this
     * takes tens of milliseconds. Any O(n) step, such as the original policy's
     * {@code ConcurrentLinkedQueue.contains} scan over the window, would need more than
     * 10^10 node visits and blow far past the timeout.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("policies")
    void evictionIsConstantTimeNotLinear(String name, IntFunction<EvictionPolicy<Integer, Integer>> factory) {
        int capacity = 100_000;
        PluggableCache<Integer, Integer> cache = new PluggableCache<>(capacity, factory.apply(capacity));
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            long t0 = System.nanoTime();
            for (int i = 0; i < capacity + 300_000; i++) {
                cache.put(i, i);
                if ((i & 3) == 0) cache.get(i >> 1);   // keep frequency/recency structures busy
            }
            long ms = (System.nanoTime() - t0) / 1_000_000;
            System.out.printf("[complexity] %s: 400,000 puts (300,000 evictions) at capacity 100,000 in %d ms%n", name, ms);
        });
        assertEquals(capacity, cache.size());
        cache.verifyIntegrity();
    }

    // ------------------------------------------------------------------ ordering semantics

    @Test
    void lruEvictsLeastRecentlyUsed() {
        PluggableCache<String, String> cache = new PluggableCache<>(2, new LruPolicy<>());
        cache.put("k1", "v1");
        cache.put("k2", "v2");
        cache.get("k1");            // k2 is now LRU
        cache.put("k3", "v3");
        assertNull(cache.get("k2"));
        assertEquals("v1", cache.get("k1"));
        assertEquals("v3", cache.get("k3"));
    }

    @Test
    void lfuEvictsLeastFrequentlyUsedWithLruTieBreak() {
        PluggableCache<String, String> cache = new PluggableCache<>(3, new LfuPolicy<>());
        cache.put("a", "1");
        cache.put("b", "2");
        cache.put("c", "3");
        cache.get("a");
        cache.get("a");             // a: freq 3
        cache.get("b");             // b: freq 2, c: freq 1
        cache.put("d", "4");        // evicts c (lowest freq)
        assertFalse(cache.containsKey("c"));
        cache.put("e", "5");        // d and e... d has freq 1 -> d evicted
        assertFalse(cache.containsKey("d"));
        assertTrue(cache.containsKey("a") && cache.containsKey("b") && cache.containsKey("e"));

        // tie-break: two freq-1 entries, the older one goes first
        PluggableCache<String, String> tie = new PluggableCache<>(2, new LfuPolicy<>());
        tie.put("old", "x");
        tie.put("new", "y");
        tie.put("z", "z");
        assertFalse(tie.containsKey("old"));
        assertTrue(tie.containsKey("new"));
        tie.verifyIntegrity();
    }

    @Test
    void lfuRemovalOfMinimumBucketKeepsEvictionCorrect() {
        LfuPolicy<String, String> p = new LfuPolicy<>();
        PluggableCache<String, String> cache = new PluggableCache<>(3, p);
        cache.put("a", "1");        // freq1: a
        cache.put("b", "2");
        cache.get("b");             // freq2: b
        cache.put("c", "3");
        cache.get("c");
        cache.get("c");             // freq3: c
        cache.remove("a");          // empties the min bucket; next min must be found in O(1)
        cache.put("d", "4");        // freq1: d (cache: b,c,d)
        cache.put("e", "5");        // evict d (freq1, only one) -> b,c,e
        assertFalse(cache.containsKey("d"));
        assertTrue(cache.containsKey("b"));
        cache.verifyIntegrity();
    }

    @Test
    void updateDoesNotEvictAndRefreshesValue() {
        PluggableCache<String, String> cache = new PluggableCache<>(2, new LruPolicy<>());
        cache.put("a", "1");
        cache.put("b", "2");
        cache.put("a", "1b");       // update, not insert
        assertEquals(2, cache.size());
        assertEquals("1b", cache.get("a"));
        cache.put("c", "3");        // b is LRU now
        assertNull(cache.get("b"));
    }
}
