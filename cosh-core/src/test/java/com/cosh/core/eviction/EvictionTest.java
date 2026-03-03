package com.cosh.core.eviction;

import com.cosh.core.store.CacheStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EvictionTest {

    @Test
    void testEvictionLimit() {
        CacheStore store = new CacheStore(5, new SampledLruPolicy());

        for (int i = 0; i < 5; i++) {
            store.put("key" + i, "val" + i, 0);
        }

        assertEquals(5, store.size());

        // Adding a 6th key must trigger eviction
        store.put("key5", "val5", 0);

        assertEquals(5, store.size(), "Store should have evicted one entry");
    }

    @Test
    void testLruBehavior() throws InterruptedException {
        CacheStore store = new CacheStore(3, new SampledLruPolicy());

        store.put("A", "val", 0);
        store.put("B", "val", 0);
        store.put("C", "val", 0);

        // Access A and C — B is now the least recently used
        store.get("A");
        Thread.sleep(1); // Ensure nanosecond clock ticks
        store.get("C");

        // Adding D should evict B (oldest last-access time)
        store.put("D", "val", 0);

        assertNull(store.get("B"), "B should have been evicted (LRU)");
        assertNotNull(store.get("A"));
        assertNotNull(store.get("C"));
        assertNotNull(store.get("D"));
    }
}
