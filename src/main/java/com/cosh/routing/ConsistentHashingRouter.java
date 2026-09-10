package com.cosh.routing;

import com.cosh.core.CacheNode;
import java.util.SortedMap;
import java.util.TreeMap;

public class ConsistentHashingRouter<K, V> {
    private final HashFunction hashFunction;
    private final int virtualNodes;
    private final TreeMap<Long, CacheNode<K, V>> ring;

    public ConsistentHashingRouter(HashFunction hashFunction, int virtualNodes) {
        this.hashFunction = hashFunction;
        this.virtualNodes = virtualNodes;
        this.ring = new TreeMap<>();
    }

    public void addNode(CacheNode<K, V> node) {
        for (int i = 0; i < virtualNodes; i++) {
            long hash = hashFunction.hash(node.getNodeId() + "-VN" + i);
            ring.put(hash, node);
        }
    }

    public void removeNode(CacheNode<K, V> node) {
        for (int i = 0; i < virtualNodes; i++) {
            long hash = hashFunction.hash(node.getNodeId() + "-VN" + i);
            ring.remove(hash);
        }
    }

    public CacheNode<K, V> routeNode(String key) {
        if (ring.isEmpty()) {
            return null;
        }
        long hash = hashFunction.hash(key);
        SortedMap<Long, CacheNode<K, V>> tailMap = ring.tailMap(hash);
        long nodeHash = tailMap.isEmpty() ? ring.firstKey() : tailMap.firstKey();
        return ring.get(nodeHash);
    }
}