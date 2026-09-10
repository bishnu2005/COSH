package com.cosh.core;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.*;

public class ConcurrentLRUCacheTest {

    @Test
    public void testEviction() {
        Cache<String, String> cache = new ConcurrentLRUCache<>(2);
        cache.put("k1", "v1");
        cache.put("k2", "v2");
        cache.put("k3", "v3"); // Should evict k1

        assertNull(cache.get("k1"));
        assertEquals("v2", cache.get("k2"));
        assertEquals("v3", cache.get("k3"));
    }

    @Test
    public void testNodeReorderingOnGet() {
        Cache<String, String> cache = new ConcurrentLRUCache<>(2);
        cache.put("k1", "v1");
        cache.put("k2", "v2");
        cache.get("k1"); // k1 becomes most recently used
        cache.put("k3", "v3"); // Should evict k2

        assertNull(cache.get("k2"));
        assertEquals("v1", cache.get("k1"));
        assertEquals("v3", cache.get("k3"));
    }

    @Test
    public void testConcurrentAccess() throws InterruptedException {
        Cache<String, String> cache = new ConcurrentLRUCache<>(100);
        int threadCount = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            executor.submit(() -> {
                cache.put("key" + index, "value" + index);
                cache.get("key" + index);
                latch.countDown();
            });
        }

        latch.await();
        assertEquals(50, cache.size());
        executor.shutdown();
    }
}