package com.cosh.grid.gateway.routing;

import com.cosh.core.constants.CoshConstants;
import com.cosh.core.error.CoshException;
import com.cosh.core.error.ErrorCode;
import com.cosh.core.hashing.HashUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Immutable consistent hash ring with virtual node support.
 *
 * <p>
 * Lives in {@code cosh-grid-gateway} — not in {@code cosh-core} —
 * because consistent hashing is a distributed routing concern, not a cache
 * engine concern.
 *
 * <p>
 * Thread-safe by design: the underlying {@link TreeMap} is never mutated after
 * construction.
 * To add/remove nodes, create a new instance.
 *
 * <p>
 * Algorithm:
 * <ol>
 * <li>Each physical node gets
 * {@value CoshConstants#VIRTUAL_NODES_PER_PHYSICAL_NODE} virtual nodes.</li>
 * <li>Virtual node keys are hashed to 64-bit positions on the ring.</li>
 * <li>For a given cache key, we find the first node at or after the key's hash
 * (ceiling entry).</li>
 * <li>Wrap-around is handled by falling back to the first entry if no ceiling
 * is found.</li>
 * </ol>
 */
public class ConsistentHashRing {

    private static final Logger log = LoggerFactory.getLogger(ConsistentHashRing.class);

    /** hash position → physical node identifier (e.g. "node1:8080") */
    private final NavigableMap<Long, String> ring;

    public ConsistentHashRing(List<String> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            throw new CoshException(ErrorCode.NO_NODES_AVAILABLE,
                    "Cannot initialize ConsistentHashRing with an empty node list");
        }

        NavigableMap<Long, String> newRing = new TreeMap<>();
        for (String node : nodes) {
            for (int i = 0; i < CoshConstants.VIRTUAL_NODES_PER_PHYSICAL_NODE; i++) {
                String virtualNodeKey = node + "#" + i;
                long hash = HashUtils.hash64(virtualNodeKey);
                newRing.put(hash, node);
            }
        }

        this.ring = newRing;
        log.info("ConsistentHashRing initialized: {} physical nodes × {} virtual nodes = {} positions",
                nodes.size(), CoshConstants.VIRTUAL_NODES_PER_PHYSICAL_NODE, ring.size());
    }

    /**
     * Route a key to its primary node.
     *
     * @param key cache key to route
     * @return the primary physical node identifier
     */
    public String getNode(String key) {
        return getNodes(key, 1).get(0);
    }

    /**
     * Returns up to {@code count} distinct physical nodes for the given key,
     * ordered by ring proximity. Used for replication fan-out.
     *
     * <p>
     * If fewer physical nodes exist than {@code count}, returns all available
     * nodes.
     *
     * @param key   cache key to route
     * @param count desired number of distinct nodes
     * @return distinct node identifiers, ordered from primary to furthest replica
     */
    public List<String> getNodes(String key, int count) {
        if (ring.isEmpty()) {
            throw new CoshException(ErrorCode.NO_NODES_AVAILABLE, "Hash ring is empty");
        }

        long keyHash = HashUtils.hash64(key);
        List<String> distinctNodes = new ArrayList<>();
        Set<String> found = new HashSet<>();

        // Find the start position on the ring (ceiling, with wrap-around)
        Map.Entry<Long, String> startEntry = ring.ceilingEntry(keyHash);
        if (startEntry == null) {
            startEntry = ring.firstEntry();
        }

        Long currentHash = startEntry.getKey();
        int attempts = 0;
        int maxAttempts = ring.size();

        while (distinctNodes.size() < count && attempts < maxAttempts) {
            String node = ring.get(currentHash);
            if (found.add(node)) {
                distinctNodes.add(node);
            }

            Map.Entry<Long, String> next = ring.higherEntry(currentHash);
            if (next == null) {
                next = ring.firstEntry();
            }
            currentHash = next.getKey();
            attempts++;
        }

        return distinctNodes;
    }
}
