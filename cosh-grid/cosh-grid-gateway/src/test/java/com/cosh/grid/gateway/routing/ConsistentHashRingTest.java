package com.cosh.grid.gateway.routing;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class ConsistentHashRingTest {

    @Test
    void testDistributionIsRoughlyEven() {
        List<String> nodes = List.of("node1:8080", "node2:8080", "node3:8080");
        ConsistentHashRing ring = new ConsistentHashRing(nodes);

        // Route 1000 keys and count distribution per node
        Map<String, Long> distribution = IntStream.range(0, 1000)
                .mapToObj(i -> "key-" + i)
                .map(ring::getNode)
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));

        System.out.println("Distribution: " + distribution);

        assertEquals(3, distribution.size(), "All 3 nodes should receive some keys");
        distribution.values()
                .forEach(count -> assertTrue(count > 200, "Each node should get >20% of keys — got: " + count));
    }

    @Test
    void testDeterministicRouting() {
        List<String> nodes = List.of("A", "B", "C");
        ConsistentHashRing ring = new ConsistentHashRing(nodes);

        String first = ring.getNode("my-key");
        String second = ring.getNode("my-key");

        assertEquals(first, second, "Same key must always route to same node");
    }

    @Test
    void testReplicationReturnsDistinctNodes() {
        List<String> nodes = List.of("A", "B", "C");
        ConsistentHashRing ring = new ConsistentHashRing(nodes);

        List<String> twoNodes = ring.getNodes("key-1", 2);
        assertEquals(2, twoNodes.size());
        assertNotEquals(twoNodes.get(0), twoNodes.get(1), "Replicas must be distinct nodes");

        // Request more nodes than available — should cap at available count
        List<String> allNodes = ring.getNodes("key-1", 5);
        assertEquals(3, allNodes.size(), "Should return all 3 available nodes when count > ring size");

        long distinct = allNodes.stream().distinct().count();
        assertEquals(3, distinct, "All returned nodes must be distinct");
    }

    @Test
    void testEmptyRingThrows() {
        assertThrows(com.cosh.core.error.CoshException.class,
                () -> new ConsistentHashRing(List.of()));
    }
}
