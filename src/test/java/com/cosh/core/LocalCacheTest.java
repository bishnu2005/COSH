package com.cosh.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class LocalCacheTest {

    @Test
    public void testPutAndGet() {
        Cache<String, String> cache = new LocalCache<>(10);
        cache.put("key1", "value1");
        assertEquals("value1", cache.get("key1"));
        assertEquals(1, cache.size());
    }

    @Test
    public void testUpdateExistingKey() {
        Cache<String, String> cache = new LocalCache<>(10);
        cache.put("key1", "value1");
        cache.put("key1", "value2");
        assertEquals("value2", cache.get("key1"));
        assertEquals(1, cache.size());
    }

    @Test
    public void testRemove() {
        Cache<String, String> cache = new LocalCache<>(10);
        cache.put("key1", "value1");
        cache.remove("key1");
        assertNull(cache.get("key1"));
        assertEquals(0, cache.size());
    }

    @Test
    public void testCapacityHandling() {
        Cache<String, String> cache = new LocalCache<>(2);
        cache.put("k1", "v1");
        cache.put("k2", "v2");

        Exception exception = assertThrows(IllegalStateException.class, () -> {
            cache.put("k3", "v3");
        });

        assertEquals("Cache capacity reached", exception.getMessage());
    }
}