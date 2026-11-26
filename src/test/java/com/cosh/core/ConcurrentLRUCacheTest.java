package com.cosh.core;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ConcurrentLRUCacheTest {

    @Test
    void evictsLeastRecentlyUsed() {
        Cache<String, String> cache = new ConcurrentLRUCache<>(2);
        cache.put("k1", "v1");
        cache.put("k2", "v2");
        cache.put("k3", "v3"); // evicts k1

        assertNull(cache.get("k1"));
        assertEquals("v2", cache.get("k2"));
        assertEquals("v3", cache.get("k3"));
    }

    @Test
    void getReordersNode() {
        Cache<String, String> cache = new ConcurrentLRUCache<>(2);
        cache.put("k1", "v1");
        cache.put("k2", "v2");
        cache.get("k1");       // k1 becomes most recently used
        cache.put("k3", "v3"); // evicts k2

        assertNull(cache.get("k2"));
        assertEquals("v1", cache.get("k1"));
        assertEquals("v3", cache.get("k3"));
    }

    @Test
    void concurrentDistinctKeyInsertsAreAllRetained() throws InterruptedException {
        ConcurrentLRUCache<String, String> cache = new ConcurrentLRUCache<>(100);
        int threadCount = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    start.await();
                    cache.put("key" + index, "value" + index);
                    assertEquals("value" + index, cache.get("key" + index));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        executor.shutdown();
        assertEquals(50, cache.size());
        cache.verifyIntegrity();
    }
}
