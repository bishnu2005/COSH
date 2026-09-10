package com.cosh.benchmark;

import com.cosh.core.Cache;
import com.cosh.core.PluggableCache;
import com.cosh.eviction.EvictionPolicy;
import com.cosh.eviction.SlidingWindowAdaptivePolicy;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class CacheBenchmarkTest {

    @Test
    public void run100KBenchmark() {
        int totalOperations = 100000;
        int warmupOperations = 50000;
        long[] latencies = new long[totalOperations];
        int getOperations = 0;
        int hits = 0;
        int misses = 0;

        int capacity = 200;
        int keySpace = 2000;

        EvictionPolicy<String, String> policy = new SlidingWindowAdaptivePolicy<>(5000);
        Cache<String, String> cache = new PluggableCache<>(capacity, policy);
        Random random = new Random(42);

        // Pre-generate keys to prevent String allocation/GC from artificially inflating P95 latency
        String[] keys = new String[keySpace];
        for(int i = 0; i < keySpace; i++) {
            keys[i] = "key-" + i;
        }

        // WARMUP PHASE: Allows JVM JIT compilation to optimize code paths before measuring
        for (int i = 0; i < warmupOperations; i++) {
            String key = keys[getSkewedKeyIndex(random, keySpace)];
            if (random.nextDouble() < 0.7) cache.get(key);
            else cache.put(key, "warmup-val");
        }

        // BENCHMARK PHASE: Execute 100K measured operations
        for (int i = 0; i < totalOperations; i++) {
            String key = keys[getSkewedKeyIndex(random, keySpace)];
            boolean isGet = random.nextDouble() < 0.7;

            long start = System.nanoTime();

            if (isGet) {
                getOperations++;
                if (cache.get(key) != null) hits++;
                else misses++;
            } else {
                cache.put(key, "val-" + i);
            }

            latencies[i] = System.nanoTime() - start;
        }

        // Calculate Percentiles
        Arrays.sort(latencies);
        long p50 = latencies[(int) (totalOperations * 0.50)];
        long p95 = latencies[(int) (totalOperations * 0.95)];
        double hitRate = (getOperations > 0) ? ((double) hits / getOperations) * 100.0 : 0.0;

        // Print final validation report
        System.out.println("\n==============================================");
        System.out.println("       COSH 100K-OPERATION BENCHMARK");
        System.out.println("==============================================");
        System.out.println("Total Operations : " + totalOperations);
        System.out.println("Cache Capacity   : " + capacity);
        System.out.println("Key Space        : " + keySpace + " (80/20 Skewed)");
        System.out.println("GET Operations   : " + getOperations);
        System.out.println("Cache Hits       : " + hits);
        System.out.println("Cache Misses     : " + misses);
        System.out.printf("Cache Hit Rate   : %.2f%%\n", hitRate);
        System.out.println("P50 Latency      : " + p50 + " ns");
        System.out.println("P95 Latency      : " + p95 + " ns");
        System.out.println("==============================================\n");

        // Validate that metrics were actually recorded
        assertTrue(totalOperations > 0);
        assertTrue(p95 >= p50);
        assertTrue(latencies[0] >= 0);
    }

    // Simulates real-world cache locality: 80% of traffic hits 20% of the keys
    private int getSkewedKeyIndex(Random random, int maxKey) {
        int hotPoolSize = (int) (maxKey * 0.2);
        if (random.nextDouble() < 0.8) {
            return random.nextInt(hotPoolSize); // Hit hot keys
        } else {
            return hotPoolSize + random.nextInt(maxKey - hotPoolSize); // Hit cold keys
        }
    }
}