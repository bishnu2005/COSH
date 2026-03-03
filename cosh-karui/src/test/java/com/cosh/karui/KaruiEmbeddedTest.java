package com.cosh.karui;

import com.cosh.core.api.CoshCache;
import com.cosh.core.eviction.SampledLruPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for the CoshKarui embedded API.
 *
 * <p>
 * This is the primary smoke test validating the public contract.
 * No Spring. No mocks. Pure Java.
 */
class KaruiEmbeddedTest {

    @Test
    void testEmbeddedApiStartAndGet() {
        CoshCache cache = CoshKarui.start(
                KaruiConfig.builder()
                        .maxSize(100)
                        .build());

        cache.put("hello", "world", 60);

        assertEquals("world", cache.get("hello"), "Karui embedded API must return stored value");
    }

    @Test
    void testEmbeddedApiDelete() {
        CoshCache cache = CoshKarui.start(KaruiConfig.builder().maxSize(100).build());

        cache.put("key", "value", 0);
        assertEquals("value", cache.get("key"));

        cache.delete("key");
        assertNull(cache.get("key"), "Value must be null after delete");
    }

    @Test
    void testEmbeddedApiTtlExpiry() throws InterruptedException {
        CoshCache cache = CoshKarui.start(KaruiConfig.builder().maxSize(100).build());

        cache.put("temp", "data", 1); // 1-second TTL
        assertEquals("data", cache.get("temp"));

        Thread.sleep(1100);
        assertNull(cache.get("temp"), "TTL-expired entry must return null");
    }

    @Test
    void testCustomEvictionPolicy() {
        CoshCache cache = CoshKarui.start(
                KaruiConfig.builder()
                        .maxSize(3)
                        .evictionPolicy(new SampledLruPolicy())
                        .build());

        cache.put("a", "1", 0);
        cache.put("b", "2", 0);
        cache.put("c", "3", 0);

        // Access a and c — b is now LRU
        cache.get("a");
        cache.get("c");

        // Adding d triggers eviction — b should be gone
        cache.put("d", "4", 0);

        assertEquals(3, cache.size(), "Store should not exceed maxSize");
        assertNull(cache.get("b"), "LRU entry (b) should be evicted");
    }

    @Test
    void testSizeTracking() {
        CoshCache cache = CoshKarui.start(KaruiConfig.builder().maxSize(100).build());

        assertEquals(0, cache.size());
        cache.put("x", "1", 0);
        cache.put("y", "2", 0);
        assertEquals(2, cache.size());
    }
}
