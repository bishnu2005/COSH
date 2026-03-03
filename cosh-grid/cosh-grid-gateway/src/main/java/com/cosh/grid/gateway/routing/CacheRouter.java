package com.cosh.grid.gateway.routing;

import com.cosh.core.error.CoshException;
import com.cosh.core.error.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Routes a cache key to one or more grid nodes based on the replication factor.
 *
 * <p>
 * Returns a list of distinct physical nodes ordered by ring proximity:
 * index 0 is the primary, subsequent indices are replicas.
 */
@Service
public class CacheRouter {

    private final ConsistentHashRing ring;
    private final int replicationFactor;

    public CacheRouter(
            ConsistentHashRing ring,
            @Value("${cosh.gateway.replication-factor:1}") int replicationFactor) {
        this.ring = ring;
        this.replicationFactor = replicationFactor;
    }

    /**
     * Returns the ordered list of nodes responsible for the given key.
     *
     * @param key cache key to route
     * @return [primary, replica1, replica2, ...] — length ≤ replicationFactor
     * @throws CoshException on bad input or missing nodes
     */
    public List<String> route(String key) {
        if (key == null || key.isBlank()) {
            throw new CoshException(ErrorCode.INVALID_ARGUMENT, "Cache key must not be null or blank");
        }
        if (replicationFactor < 1) {
            throw new CoshException(ErrorCode.CONFIGURATION_ERROR,
                    "replication-factor must be >= 1, got: " + replicationFactor);
        }
        return ring.getNodes(key, replicationFactor);
    }
}
