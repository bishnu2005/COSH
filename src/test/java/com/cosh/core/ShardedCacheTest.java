package com.cosh.core;

import com.cosh.routing.MD5HashFunction;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ShardedCacheTest {

    private static ShardedCache<String, String> cluster(int nodes, int capacityPerNode) {
        ShardedCache<String, String> c = new ShardedCache<>(new MD5HashFunction(), 150);
        for (int i = 0; i < nodes; i++) c.addNode(new CacheNode<>("node-" + i, capacityPerNode));
        return c;
    }

    @Test
    void routesEachKeyToExactlyOneShard() {
        ShardedCache<String, String> c = cluster(4, 1_000);
        for (int i = 0; i < 2_000; i++) c.put("k" + i, "v" + i);
        for (int i = 0; i < 2_000; i++) assertEquals("v" + i, c.get("k" + i));
        int total = 0;
        for (CacheNode<String, String> n : c.nodes()) {
            assertTrue(n.getCache().size() > 300, "shard unexpectedly empty: " + n);
            total += n.getCache().size();
        }
        assertEquals(2_000, total, "a key must live on exactly one shard");
        assertEquals(2_000, c.size());
        c.verifyIntegrity();
    }

    @Test
    void addingNodeMigratesOnlyKeysItNowOwns() {
        ShardedCache<String, String> c = cluster(4, 10_000);
        for (int i = 0; i < 10_000; i++) c.put("k" + i, "v" + i);
        Map<String, String> ownerBefore = new HashMap<>();
        for (int i = 0; i < 10_000; i++) ownerBefore.put("k" + i, c.nodeFor("k" + i).getNodeId());

        int moved = c.addNode(new CacheNode<>("node-new", 10_000));

        int changed = 0;
        for (int i = 0; i < 10_000; i++) {
            String k = "k" + i;
            assertEquals("v" + i, c.get(k), "no data loss during rebalance");
            String now = c.nodeFor(k).getNodeId();
            if (!now.equals(ownerBefore.get(k))) {
                changed++;
                assertEquals("node-new", now);
            }
        }
        assertEquals(changed, moved, "exactly the re-owned keys are migrated");
        assertTrue(moved > 1_400 && moved < 2_600, "expected ~20% movement, got " + moved);
        c.verifyIntegrity();
    }

    @Test
    void removingNodeHandsItsKeysToSuccessors() {
        ShardedCache<String, String> c = cluster(5, 10_000);
        for (int i = 0; i < 5_000; i++) c.put("k" + i, "v" + i);
        String victim = c.nodeFor("k0").getNodeId();
        int victimKeys = c.nodes().stream().filter(n -> n.getNodeId().equals(victim)).findFirst()
                .orElseThrow().getCache().size();

        assertEquals(victimKeys, c.removeNode(victim));
        assertEquals(4, c.nodes().size());
        for (int i = 0; i < 5_000; i++) assertEquals("v" + i, c.get("k" + i));
        c.verifyIntegrity();
    }

    /**
     * Regression for the stale-read bug that a ring without migration has:
     * put v1 -> add node (key re-owned) -> put v2 -> remove node -> get must be v2, not v1.
     */
    @Test
    void staleValueIsNotResurrectedAfterTopologyChanges() {
        ShardedCache<String, String> c = cluster(1, 100);
        CacheNode<String, String> extra = null;
        String key = null;
        for (int attempt = 0; key == null; attempt++) {
            CacheNode<String, String> candidate = new CacheNode<>("extra-" + attempt, 100);
            c.addNode(candidate);
            for (int i = 0; i < 1_000 && key == null; i++) {
                if (c.nodeFor("key" + i) == candidate) key = "key" + i;
            }
            if (key == null) c.removeNode(candidate.getNodeId()); else extra = candidate;
        }
        c.removeNode(extra.getNodeId());

        c.put(key, "v1");                         // lives on node-0
        c.addNode(new CacheNode<>(extra.getNodeId(), 100)); // key re-owned by extra
        c.put(key, "v2");
        c.removeNode(extra.getNodeId());          // key back to node-0
        assertEquals("v2", c.get(key));
    }

    @Test
    void emptyClusterAndDuplicateNodesAreRejected() {
        ShardedCache<String, String> c = new ShardedCache<>(new MD5HashFunction(), 10);
        assertThrows(IllegalStateException.class, () -> c.get("k"));
        c.addNode(new CacheNode<>("a", 10));
        assertThrows(IllegalArgumentException.class, () -> c.addNode(new CacheNode<>("a", 10)));
        assertEquals(0, c.removeNode("does-not-exist"));
    }

    @Test
    void perShardCapacityIsEnforced() {
        ShardedCache<String, String> c = cluster(4, 50);
        for (int i = 0; i < 10_000; i++) c.put("k" + i, "v");
        for (CacheNode<String, String> n : c.nodes()) assertEquals(50, n.getCache().size());
        c.verifyIntegrity();
    }
}
