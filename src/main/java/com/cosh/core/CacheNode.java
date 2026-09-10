package com.cosh.core;

import java.util.Objects;

public class CacheNode<K, V> {
    private final String nodeId;
    private final Cache<K, V> cache;

    public CacheNode(String nodeId, int capacity) {
        this.nodeId = nodeId;
        this.cache = new LocalCache<>(capacity);
    }

    public String getNodeId() {
        return nodeId;
    }

    public Cache<K, V> getCache() {
        return cache;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CacheNode<?, ?> cacheNode = (CacheNode<?, ?>) o;
        return Objects.equals(nodeId, cacheNode.nodeId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(nodeId);
    }
}