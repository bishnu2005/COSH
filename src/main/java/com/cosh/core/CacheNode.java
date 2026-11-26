package com.cosh.core;

import com.cosh.eviction.LruPolicy;

import java.util.Objects;

/**
 * One shard of the in-process cluster: a stable identifier (hashed onto the ring) plus
 * its own bounded {@link PluggableCache}, which has its own lock and eviction policy.
 *
 * <p>Nodes are in-JVM objects, not network peers. See ENGINEERING_DEEP_DIVE.md
 * ("Honest positioning") for what would be needed to make them remote.
 */
public class CacheNode<K, V> {
    private final String nodeId;
    private final PluggableCache<K, V> cache;

    /** Convenience: LRU-backed node. */
    public CacheNode(String nodeId, int capacity) {
        this(nodeId, new PluggableCache<>(capacity, new LruPolicy<>()));
    }

    public CacheNode(String nodeId, PluggableCache<K, V> cache) {
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        this.cache = Objects.requireNonNull(cache, "cache");
    }

    public String getNodeId() {
        return nodeId;
    }

    public PluggableCache<K, V> getCache() {
        return cache;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        return nodeId.equals(((CacheNode<?, ?>) o).nodeId);
    }

    @Override
    public int hashCode() {
        return nodeId.hashCode();
    }

    @Override
    public String toString() {
        return "CacheNode[" + nodeId + ", size=" + cache.size() + "/" + cache.capacity() + "]";
    }
}
