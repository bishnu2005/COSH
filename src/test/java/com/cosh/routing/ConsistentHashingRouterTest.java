package com.cosh.routing;

import com.cosh.core.CacheNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ConsistentHashingRouterTest {
    private static final int KEYS = 100_000;

    private static ConsistentHashingRouter<String, String> ring(int vnodes, int nodeCount) {
        ConsistentHashingRouter<String, String> r = new ConsistentHashingRouter<>(new MD5HashFunction(), vnodes);
        for (int i = 0; i < nodeCount; i++) r.addNode(new CacheNode<>("node-" + i, 16));
        return r;
    }

    private static Map<String, String> ownership(ConsistentHashingRouter<String, String> r) {
        Map<String, String> owner = new HashMap<>(KEYS * 2);
        for (int i = 0; i < KEYS; i++) owner.put("key-" + i, r.routeNode("key-" + i).getNodeId());
        return owner;
    }

    /** Coefficient of variation of per-node key counts (stddev / mean). */
    private static double loadCv(ConsistentHashingRouter<String, String> r, int nodeCount) {
        Map<String, Integer> load = new HashMap<>();
        for (int i = 0; i < KEYS; i++) load.merge(r.routeNode("key-" + i).getNodeId(), 1, Integer::sum);
        assertEquals(nodeCount, load.size(), "every node should own some keys");
        double mean = (double) KEYS / nodeCount, var = 0;
        for (int c : load.values()) var += (c - mean) * (c - mean);
        return Math.sqrt(var / nodeCount) / mean;
    }

    @Test
    void sameKeyAlwaysRoutesToSameNode() {
        ConsistentHashingRouter<String, String> a = ring(150, 5), b = ring(150, 5);
        for (int i = 0; i < 1_000; i++) {
            String k = "key-" + i;
            assertSame(a.routeNode(k), a.routeNode(k));
            assertEquals(a.routeNode(k).getNodeId(), b.routeNode(k).getNodeId(), "independent rings must agree");
        }
    }

    @Test
    void emptyRingReturnsNull() {
        assertNull(ring(100, 0).routeNode("any-key"));
    }

    @Test
    void singleNodeOwnsEveryKeyAndRemovalEmptiesRing() {
        ConsistentHashingRouter<String, String> r = new ConsistentHashingRouter<>(new MD5HashFunction(), 100);
        CacheNode<String, String> a = new CacheNode<>("A", 16);
        assertEquals(100, r.addNode(a));
        for (int i = 0; i < 1_000; i++) assertSame(a, r.routeNode("k" + i));
        r.removeNode(a);
        assertEquals(0, r.ringSize());
        assertNull(r.routeNode("k1"));
    }

    @Test
    void virtualNodesBalanceLoad() {
        double cv1 = loadCv(ring(1, 5), 5);
        double cv150 = loadCv(ring(150, 5), 5);
        System.out.printf("[distribution] 5 nodes, %,d keys: CV with 1 vnode = %.3f, with 150 vnodes = %.3f%n",
                KEYS, cv1, cv150);
        assertTrue(cv150 < 0.10, "150 vnodes should keep per-node load within ~10% CV, was " + cv150);
        assertTrue(cv150 < cv1, "virtual nodes must improve balance over raw node hashing");
    }

    @Test
    void fastHashBalancesAsWellAsMd5() {
        ConsistentHashingRouter<String, String> r = new ConsistentHashingRouter<>(new Fnv1aMixHashFunction(), 150);
        for (int i = 0; i < 5; i++) r.addNode(new CacheNode<>("node-" + i, 16));
        double cv = loadCv(r, 5);
        System.out.printf("[distribution] FNV-1a+fmix64, 5 nodes x 150 vnodes: CV = %.3f%n", cv);
        assertTrue(cv < 0.10, "fast hash should balance comparably to MD5, CV was " + cv);
    }

    @Test
    void addingNodeMovesOnlyAboutOneOverNPlusOneKeysAllToTheNewNode() {
        ConsistentHashingRouter<String, String> r = ring(150, 4);
        Map<String, String> before = ownership(r);
        r.addNode(new CacheNode<>("node-new", 16));
        Map<String, String> after = ownership(r);

        int moved = 0;
        for (Map.Entry<String, String> e : before.entrySet()) {
            String now = after.get(e.getKey());
            if (!now.equals(e.getValue())) {
                moved++;
                assertEquals("node-new", now, "a key may only move TO the new node");
            }
        }
        double fraction = (double) moved / KEYS;
        System.out.printf("[movement] add 5th node: %.2f%% of keys moved (ideal 20.00%%)%n", fraction * 100);
        assertTrue(fraction > 0.14 && fraction < 0.26, "expected ~1/5 of keys to move, got " + fraction);
    }

    @Test
    void removingNodeMovesOnlyThatNodesKeys() {
        ConsistentHashingRouter<String, String> r = ring(150, 5);
        Map<String, String> before = ownership(r);
        CacheNode<String, String> victim = r.routeNode("key-0");
        r.removeNode(victim);
        Map<String, String> after = ownership(r);

        int moved = 0;
        for (Map.Entry<String, String> e : before.entrySet()) {
            boolean wasOnVictim = e.getValue().equals(victim.getNodeId());
            boolean changed = !after.get(e.getKey()).equals(e.getValue());
            assertEquals(wasOnVictim, changed, "only the removed node's keys may change owner: " + e.getKey());
            if (changed) moved++;
        }
        System.out.printf("[movement] remove 1 of 5 nodes: %.2f%% of keys moved (ideal 20.00%%)%n", 100.0 * moved / KEYS);
    }

    @Test
    void reAddingANodeRestoresTheOriginalMapping() {
        ConsistentHashingRouter<String, String> r = ring(150, 4);
        Map<String, String> before = ownership(r);
        CacheNode<String, String> extra = new CacheNode<>("node-x", 16);
        r.addNode(extra);
        r.removeNode(extra);
        assertEquals(before, ownership(r));
    }

    @Test
    void hashCollisionNeverStealsOrDeletesAnotherNodesPoint() {
        HashFunction constant = key -> 42L; // pathological: every vnode collides
        ConsistentHashingRouter<String, String> r = new ConsistentHashingRouter<>(constant, 10);
        CacheNode<String, String> a = new CacheNode<>("A", 16), b = new CacheNode<>("B", 16);
        assertEquals(1, r.addNode(a));
        assertEquals(0, r.addNode(b), "B's colliding points must not overwrite A's");
        assertSame(a, r.routeNode("x"));
        r.removeNode(b);
        assertSame(a, r.routeNode("x"), "removing B must not delete A's point");
    }

    @Test
    void routingIsSafeDuringConcurrentTopologyChurn() throws Exception {
        ConsistentHashingRouter<String, String> r = ring(100, 2); // two permanent nodes
        int readers = 6;
        ExecutorService pool = Executors.newFixedThreadPool(readers + 1);
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean stop = new AtomicBoolean();
        AtomicInteger errors = new AtomicInteger();
        List<Throwable> failures = new ArrayList<>();

        pool.submit(() -> {
            try {
                start.await();
                for (int i = 0; i < 2_000; i++) {
                    CacheNode<String, String> n = new CacheNode<>("churn-" + (i % 7), 16);
                    r.addNode(n);
                    r.removeNode(n);
                }
            } catch (Throwable t) {
                errors.incrementAndGet();
                synchronized (failures) { failures.add(t); }
            } finally {
                stop.set(true);
            }
        });
        CountDownLatch done = new CountDownLatch(readers);
        for (int t = 0; t < readers; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    int i = 0;
                    while (!stop.get()) {
                        if (r.routeNode("key-" + (i++ % 10_000)) == null) errors.incrementAndGet();
                    }
                } catch (Throwable t2) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "router readers hung");
        pool.shutdown();
        assertEquals(0, errors.get(), "routing returned null or threw during churn: " + failures);
        assertEquals(200, r.ringSize(), "only the two permanent nodes' points should remain");
    }

    @Test
    void rejectsInvalidArguments() {
        assertThrows(IllegalArgumentException.class, () -> new ConsistentHashingRouter<>(new MD5HashFunction(), 0));
        assertThrows(NullPointerException.class, () -> new ConsistentHashingRouter<>(null, 10));
    }
}
