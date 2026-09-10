package com.cosh.core;

import com.cosh.eviction.EvictionPolicy;
import com.cosh.eviction.SlidingWindowAdaptivePolicy;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class ConcurrentWorkloadTest {

    @Test
    public void testHighConcurrencyUnderEvictionLoad() throws InterruptedException {
        int capacity = 1000;
        int threadCount = 50;
        int operationsPerThread = 2000; // 100,000 total operations

        EvictionPolicy<String, String> policy = new SlidingWindowAdaptivePolicy<>(5000);
        Cache<String, String> cache = new PluggableCache<>(capacity, policy);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        AtomicInteger errorCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    startLatch.await(); // Wait for all threads to be ready
                    for (int j = 0; j < operationsPerThread; j++) {
                        // Mix of overlapping keys to force race conditions on specific entries
                        String key = "key-" + (j % 2000);
                        String value = "val-" + threadId + "-" + j;

                        // 70% gets, 30% puts
                        if (Math.random() < 0.7) {
                            cache.get(key);
                        } else {
                            cache.put(key, value);
                        }
                    }
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                    e.printStackTrace();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        // Release all threads simultaneously
        startLatch.countDown();
        // Wait for all threads to finish
        endLatch.await();
        executor.shutdown();

        // Verification
        assertEquals(0, errorCount.get(), "Exceptions occurred during concurrent execution");
        assertTrue(cache.size() <= capacity, "Cache size exceeded capacity limit under concurrent load");
    }
}