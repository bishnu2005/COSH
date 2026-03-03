package com.cosh.core.store;

import com.cosh.core.eviction.SampledLruPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CacheStoreTest {

    @Test
    void testBasicPutAndGet() {
        CacheStore store = new CacheStore(100, new SampledLruPolicy());
        store.put("key1", "value1", 0); // 0 = no expiry

        assertEquals("value1", store.get("key1"));
        assertNull(store.get("missing-key"));
    }

    @Test
    void testExpiry() throws InterruptedException {
        CacheStore store = new CacheStore(100, new SampledLruPolicy());
        store.put("short-lived", "temp", 1); // 1-second TTL

        assertEquals("temp", store.get("short-lived"), "Should be accessible before TTL");

        Thread.sleep(1100); // Wait for TTL to elapse

        assertNull(store.get("short-lived"), "Value should be lazily expired");
    }

    @Test
    void testNoExpiry() throws InterruptedException {
        CacheStore store = new CacheStore(100, new SampledLruPolicy());
        store.put("forever", "diamond", 0);
        store.put("forever-neg", "gold", -1);

        Thread.sleep(100);

        assertEquals("diamond", store.get("forever"));
        assertEquals("gold", store.get("forever-neg"));
    }

    @Test
    void testDelete() {
        CacheStore store = new CacheStore(100, new SampledLruPolicy());
        store.put("k", "v", 0);
        store.delete("k");
        assertNull(store.get("k"));
    }

    @Test
    void testNullKeyRejected() {
        CacheStore store = new CacheStore(100, new SampledLruPolicy());
        assertThrows(com.cosh.core.error.CoshException.class,
                () -> store.put(null, "value", 0));
    }
}
