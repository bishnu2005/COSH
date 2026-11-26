package com.cosh.routing;

import com.cosh.core.CacheNode;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Consistent-hash ring with virtual nodes.
 *
 * <p>Each physical node is hashed onto the 64-bit ring {@code virtualNodes} times, as
 * {@code nodeId#0 ... nodeId#(v-1)}. A key belongs to the first virtual node clockwise from
 * {@code hash(key)}, wrapping around at the end of the ring.
 *
 * <h2>Data structure: copy-on-write sorted array</h2>
 * The ring is an immutable snapshot of two parallel arrays, sorted {@code long[] points} and
 * {@code CacheNode[] owners}, published through a {@code volatile} field.
 * <ul>
 *   <li><b>Lookup</b> ({@link #routeNode}) is one volatile read plus {@link Arrays#binarySearch}:
 *       O(log(N*v)), lock-free, allocation-free, and cache-friendly because the array is contiguous.
 *       An earlier version used {@code ConcurrentSkipListMap.ceilingEntry}, which chases about
 *       log2(N*v) pointers and allocates an entry object on every request.</li>
 *   <li><b>Mutation</b> ({@link #addNode}/{@link #removeNode}) rebuilds the arrays in
 *       O(N*v log(N*v)) under a mutex and swaps the reference. Topology changes are rare, and
 *       readers never see a half-built ring.</li>
 *   <li><b>Minimal movement:</b> adding the (N+1)th node moves about 1/(N+1) of the keys, all of
 *       them onto the new node. Removing a node moves only that node's keys.</li>
 *   <li><b>Collision safety:</b> a virtual-node point that collides with an existing point is
 *       skipped, never overwritten. Removing a node only deletes points it owns.</li>
 * </ul>
 */
public class ConsistentHashingRouter<K, V> {

    private static final class Ring<K, V> {
        final long[] points;
        final CacheNode<K, V>[] owners;

        Ring(long[] points, CacheNode<K, V>[] owners) {
            this.points = points;
            this.owners = owners;
        }
    }

    private final HashFunction hashFunction;
    private final int virtualNodes;
    private final Object mutationLock = new Object();
    private volatile Ring<K, V> ring;

    public ConsistentHashingRouter(HashFunction hashFunction, int virtualNodes) {
        if (virtualNodes < 1) throw new IllegalArgumentException("virtualNodes must be >= 1");
        this.hashFunction = Objects.requireNonNull(hashFunction, "hashFunction");
        this.virtualNodes = virtualNodes;
        this.ring = build(new TreeMap<>());
    }

    /** @return number of virtual-node points actually placed (less than v only on a hash collision) */
    public int addNode(CacheNode<K, V> node) {
        Objects.requireNonNull(node, "node");
        synchronized (mutationLock) {
            TreeMap<Long, CacheNode<K, V>> next = toMap(ring);
            int placed = 0;
            for (int i = 0; i < virtualNodes; i++) {
                if (next.putIfAbsent(hashFunction.hash(vnodeLabel(node, i)), node) == null) {
                    placed++;
                }
            }
            ring = build(next);
            return placed;
        }
    }

    public void removeNode(CacheNode<K, V> node) {
        Objects.requireNonNull(node, "node");
        synchronized (mutationLock) {
            TreeMap<Long, CacheNode<K, V>> next = toMap(ring);
            for (int i = 0; i < virtualNodes; i++) {
                next.remove(hashFunction.hash(vnodeLabel(node, i)), node); // only if owned by node
            }
            ring = build(next);
        }
    }

    /** Owner of {@code key}, or null if the ring is empty. Keys are hashed via {@code String.valueOf}. */
    public CacheNode<K, V> routeNode(K key) {
        Ring<K, V> r = ring; // single volatile read: a consistent snapshot
        int n = r.points.length;
        if (n == 0) {
            return null;
        }
        int i = Arrays.binarySearch(r.points, hashFunction.hash(String.valueOf(key)));
        if (i < 0) {
            i = -i - 1;            // insertion point = first point clockwise
        }
        return r.owners[i == n ? 0 : i]; // wrap around the ring
    }

    public Set<CacheNode<K, V>> nodes() {
        return new LinkedHashSet<>(Arrays.asList(ring.owners));
    }

    public int ringSize() {
        return ring.points.length;
    }

    public int virtualNodesPerNode() {
        return virtualNodes;
    }

    private static String vnodeLabel(CacheNode<?, ?> node, int i) {
        return node.getNodeId() + "#" + i;
    }

    private static <K, V> TreeMap<Long, CacheNode<K, V>> toMap(Ring<K, V> r) {
        TreeMap<Long, CacheNode<K, V>> m = new TreeMap<>();
        for (int i = 0; i < r.points.length; i++) m.put(r.points[i], r.owners[i]);
        return m;
    }

    @SuppressWarnings("unchecked")
    private static <K, V> Ring<K, V> build(TreeMap<Long, CacheNode<K, V>> m) {
        long[] points = new long[m.size()];
        CacheNode<K, V>[] owners = new CacheNode[m.size()];
        int i = 0;
        for (Map.Entry<Long, CacheNode<K, V>> e : m.entrySet()) { // ascending signed order
            points[i] = e.getKey();
            owners[i++] = e.getValue();
        }
        return new Ring<>(points, owners);
    }
}
