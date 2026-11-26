package com.cosh.core;

import com.cosh.routing.ConsistentHashingRouter;
import com.cosh.routing.HashFunction;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Client-facing facade over a consistent-hash ring of in-process {@link CacheNode}s.
 *
 * <pre>
 *   get(k) -> ring.routeNode(k) -> node.cache.get(k) -> policy.onGet / onMiss
 * </pre>
 *
 * <h2>Topology changes and rebalancing</h2>
 * When a node is added, only the keys whose ring successor is now the new node change owner.
 * They are <em>migrated</em> (moved) to the new node. When a node is removed, its keys move to
 * their new successors. Migration matters for correctness, not just hit rate. Without it, a key
 * written after a topology change can be shadowed by an older copy on its previous owner.
 * The {@code staleValueIsNotResurrected} test shows the scenario: put v1, add a node, put v2,
 * remove that node, and a get would return v1.
 *
 * <p>Data operations take the read side of a {@link TopologyLock}, a striped, cache-line-padded
 * reader lock, so readers on different cores do not contend. Topology changes take the write
 * side, so a migration never interleaves with a client write to a migrating key. Topology changes
 * are rare and cost O(keys held by the affected nodes).
 */
public class ShardedCache<K, V> implements Cache<K, V> {
    private final ConsistentHashingRouter<K, V> router;
    private final Map<String, CacheNode<K, V>> nodes = new LinkedHashMap<>();
    private final TopologyLock topologyLock = new TopologyLock();

    public ShardedCache(HashFunction hashFunction, int virtualNodesPerNode) {
        this.router = new ConsistentHashingRouter<>(hashFunction, virtualNodesPerNode);
    }

    /** Adds a node and migrates to it the keys it now owns. Returns the number of keys moved. */
    public int addNode(CacheNode<K, V> node) {
        Objects.requireNonNull(node, "node");
        topologyLock.writeLock();
        try {
            if (nodes.containsKey(node.getNodeId())) {
                throw new IllegalArgumentException("duplicate node id " + node.getNodeId());
            }
            router.addNode(node);
            nodes.put(node.getNodeId(), node);
            int moved = 0;
            for (CacheNode<K, V> other : nodes.values()) {
                if (other == node) continue;
                for (K key : other.getCache().keySnapshot()) {
                    if (router.routeNode(key) == node) {
                        V value = other.getCache().remove(key);
                        if (value != null) {
                            node.getCache().put(key, value);
                            moved++;
                        }
                    }
                }
            }
            return moved;
        } finally {
            topologyLock.writeUnlock();
        }
    }

    /** Removes a node and moves its keys to their new owners. Returns the number of keys moved. */
    public int removeNode(String nodeId) {
        topologyLock.writeLock();
        try {
            CacheNode<K, V> node = nodes.remove(nodeId);
            if (node == null) {
                return 0;
            }
            router.removeNode(node);
            int moved = 0;
            if (!nodes.isEmpty()) {
                for (K key : node.getCache().keySnapshot()) {
                    V value = node.getCache().remove(key);
                    if (value != null) {
                        router.routeNode(key).getCache().put(key, value);
                        moved++;
                    }
                }
            }
            node.getCache().clear();
            return moved;
        } finally {
            topologyLock.writeUnlock();
        }
    }

    @Override
    public void put(K key, V value) {
        int token = topologyLock.readLock();
        try {
            requireNode(key).getCache().put(key, value);
        } finally {
            topologyLock.readUnlock(token);
        }
    }

    @Override
    public V get(K key) {
        int token = topologyLock.readLock();
        try {
            return requireNode(key).getCache().get(key);
        } finally {
            topologyLock.readUnlock(token);
        }
    }

    @Override
    public V remove(K key) {
        int token = topologyLock.readLock();
        try {
            return requireNode(key).getCache().remove(key);
        } finally {
            topologyLock.readUnlock(token);
        }
    }

    @Override
    public int size() {
        int token = topologyLock.readLock();
        try {
            int total = 0;
            for (CacheNode<K, V> n : nodes.values()) total += n.getCache().size();
            return total;
        } finally {
            topologyLock.readUnlock(token);
        }
    }

    @Override
    public void clear() {
        int token = topologyLock.readLock();
        try {
            for (CacheNode<K, V> n : nodes.values()) n.getCache().clear();
        } finally {
            topologyLock.readUnlock(token);
        }
    }

    /** The node that currently owns {@code key}, or null if the ring is empty. */
    public CacheNode<K, V> nodeFor(K key) {
        return router.routeNode(key);
    }

    public List<CacheNode<K, V>> nodes() {
        int token = topologyLock.readLock();
        try {
            return new ArrayList<>(nodes.values());
        } finally {
            topologyLock.readUnlock(token);
        }
    }

    public ConsistentHashingRouter<K, V> router() {
        return router;
    }

    /**
     * Checks every shard's integrity and that every stored key lives on the node the ring
     * assigns it to.
     */
    public void verifyIntegrity() {
        topologyLock.writeLock();
        try {
            Collection<CacheNode<K, V>> all = nodes.values();
            for (CacheNode<K, V> n : all) {
                n.getCache().verifyIntegrity();
                for (K key : n.getCache().keySnapshot()) {
                    if (router.routeNode(key) != n) {
                        throw new IllegalStateException("key " + key + " stored on " + n.getNodeId()
                                + " but routed to " + router.routeNode(key).getNodeId());
                    }
                }
            }
        } finally {
            topologyLock.writeUnlock();
        }
    }

    private CacheNode<K, V> requireNode(K key) {
        Objects.requireNonNull(key, "key");
        CacheNode<K, V> node = router.routeNode(key);
        if (node == null) {
            throw new IllegalStateException("no cache nodes in the ring");
        }
        return node;
    }
}
