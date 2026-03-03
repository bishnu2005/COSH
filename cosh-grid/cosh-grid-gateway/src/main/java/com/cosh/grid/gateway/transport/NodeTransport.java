package com.cosh.grid.gateway.transport;

import java.util.List;

/**
 * Transport abstraction for communicating with COSH grid nodes.
 *
 * <p>
 * Implementations:
 * <ul>
 * <li>{@code GridNodeClient} — HTTP/JSON transport (existing, always
 * available)</li>
 * <li>{@code NettyGridNodeClient} — CBP/1 binary transport (Phase 3, switchable
 * via config)</li>
 * </ul>
 *
 * <p>
 * Selected per-gateway at startup via {@code cosh.gateway.transport} property.
 * No code changes needed to switch between transports.
 */
public interface NodeTransport {

    /**
     * GET a value from the first available node.
     * Falls back to replicas on primary failure.
     *
     * @param nodes ordered list of node addresses (host:port for HTTP,
     *              host:nettyPort for Netty)
     * @param key   cache key
     * @return the value, or {@code null} if not found
     */
    String get(List<String> nodes, String key);

    /**
     * PUT a value across all nodes.
     * Primary must succeed; replica failures are tolerated.
     *
     * @param nodes ordered list of nodes
     * @param key   cache key
     * @param value value to store
     */
    void put(List<String> nodes, String key, String value);

    /**
     * DELETE a key from all nodes.
     * Primary must succeed; replica failures are tolerated.
     *
     * @param nodes ordered list of nodes
     * @param key   cache key
     */
    void delete(List<String> nodes, String key);
}
