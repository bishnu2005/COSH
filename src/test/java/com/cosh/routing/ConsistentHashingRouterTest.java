package com.cosh.routing;

import com.cosh.core.CacheNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class ConsistentHashingRouterTest {
    private ConsistentHashingRouter<String, String> router;
    private CacheNode<String, String> nodeA;
    private CacheNode<String, String> nodeB;
    private CacheNode<String, String> nodeC;

    @BeforeEach
    public void setup() {
        router = new ConsistentHashingRouter<>(new MD5HashFunction(), 100);
        nodeA = new CacheNode<>("NodeA", 100);
        nodeB = new CacheNode<>("NodeB", 100);
        nodeC = new CacheNode<>("NodeC", 100);
    }

    @Test
    public void testRoutingConsistentlyRoutesSameKeyToSameNode() {
        router.addNode(nodeA);
        router.addNode(nodeB);

        CacheNode<String, String> target1 = router.routeNode("my-key-1");
        CacheNode<String, String> target2 = router.routeNode("my-key-1");

        assertEquals(target1.getNodeId(), target2.getNodeId());
    }

    @Test
    public void testRemovingNodeRemapsKeys() {
        router.addNode(nodeA);

        CacheNode<String, String> targetWithOneNode = router.routeNode("test-key");
        assertEquals("NodeA", targetWithOneNode.getNodeId());

        router.addNode(nodeB);
        router.removeNode(nodeA);

        CacheNode<String, String> targetAfterRemoval = router.routeNode("test-key");
        assertEquals("NodeB", targetAfterRemoval.getNodeId());
    }

    @Test
    public void testEmptyRingReturnsNull() {
        assertNull(router.routeNode("any-key"));
    }
}