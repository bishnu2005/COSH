package com.cosh.core;

import com.cosh.eviction.LruPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PluggableCacheTest {

    @Test
    void rejectsNonPositiveCapacityAndNullPolicy() {
        assertThrows(IllegalArgumentException.class, () -> new PluggableCache<>(0, new LruPolicy<>()));
        assertThrows(IllegalArgumentException.class, () -> new PluggableCache<>(-5, new LruPolicy<>()));
        assertThrows(IllegalArgumentException.class, () -> new ConcurrentLRUCache<>(0));
        assertThrows(NullPointerException.class, () -> new PluggableCache<String, String>(4, null));
    }

    @Test
    void rejectsNullKeysAndValues() {
        Cache<String, String> cache = new PluggableCache<>(4, new LruPolicy<>());
        assertThrows(NullPointerException.class, () -> cache.put(null, "v"));
        assertThrows(NullPointerException.class, () -> cache.put("k", null));
        assertThrows(NullPointerException.class, () -> cache.get(null));
        assertThrows(NullPointerException.class, () -> cache.remove(null));
        assertEquals(0, cache.size(), "failed puts must not leave partial state");
    }

    @Test
    void removeReturnsPreviousValueOrNull() {
        PluggableCache<String, String> cache = new PluggableCache<>(4, new LruPolicy<>());
        cache.put("a", "1");
        assertEquals("1", cache.remove("a"));
        assertNull(cache.remove("a"));
        assertNull(cache.remove("never-there"));
        cache.verifyIntegrity();
    }

    @Test
    void removedEntryIsNeverResurrectedByALaterRead() {
        PluggableCache<String, String> cache = new PluggableCache<>(2, new LruPolicy<>());
        cache.put("a", "1");
        cache.remove("a");
        assertNull(cache.get("a"));
        cache.put("b", "2");
        cache.put("c", "3");
        cache.verifyIntegrity();
        assertEquals(2, cache.size());
    }

    @Test
    void bestEffortModeHasSameSingleThreadedSemanticsAsStrict() {
        for (PluggableCache.ReadMode mode : PluggableCache.ReadMode.values()) {
            PluggableCache<String, String> cache = new PluggableCache<>(2, new LruPolicy<>(), mode);
            cache.put("k1", "v1");
            cache.put("k2", "v2");
            cache.get("k1");
            cache.put("k3", "v3");
            assertNull(cache.get("k2"), mode + ": k2 should be LRU victim");
            assertEquals("v1", cache.get("k1"));
            cache.verifyIntegrity();
        }
    }

    @Test
    void clearEmptiesMapAndPolicy() {
        PluggableCache<Integer, Integer> cache = new PluggableCache<>(10, new LruPolicy<>());
        for (int i = 0; i < 10; i++) cache.put(i, i);
        cache.clear();
        assertEquals(0, cache.size());
        assertEquals(0, cache.policy().size());
        for (int i = 0; i < 10; i++) assertNull(cache.get(i));
        cache.verifyIntegrity();
    }
}
