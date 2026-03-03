package com.cosh.grid.gateway.client;

import com.cosh.core.error.CoshException;
import com.cosh.core.error.ErrorCode;
import com.cosh.grid.gateway.transport.NodeTransport;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * HTTP client that speaks to COSH grid nodes on behalf of the gateway.
 *
 * <p>
 * Read strategy: try primary, fall back to replicas on {@code 5xx}/network
 * error.
 * Write strategy: must succeed on primary; replica failures are logged but
 * tolerated.
 *
 * <p>
 * Metrics: {@code cosh.gateway.fallback} is incremented whenever a replica
 * node is used for a GET (primary unavailable).
 */
public class GridNodeClient implements NodeTransport {

    private static final Logger log = LoggerFactory.getLogger(GridNodeClient.class);

    private final RestClient restClient;
    private final Counter fallbackCounter;

    public GridNodeClient(RestClient restClient, MeterRegistry registry) {
        this.restClient = restClient;
        this.fallbackCounter = registry.counter("cosh.gateway.fallback");
    }

    /**
     * GET a value from the first available node in the priority list.
     * Nodes[0] = primary; subsequent = replicas.
     *
     * @param nodes ordered list of node addresses (host:port)
     * @param key   cache key
     * @return the cached value, or {@code null} if not found
     * @throws CoshException if all nodes are unavailable
     */
    public String get(List<String> nodes, String key) {
        CoshException lastException = null;

        for (int i = 0; i < nodes.size(); i++) {
            String node = nodes.get(i);
            boolean isPrimary = (i == 0);

            try {
                String value = restClient.get()
                        .uri("http://" + node + "/cache/" + key)
                        .retrieve()
                        .body(String.class);

                // ✅ FIX: correctly increment fallback counter when a replica responds
                if (!isPrimary) {
                    fallbackCounter.increment();
                    log.debug("Fallback read succeeded on replica {} for key '{}'", node, key);
                }

                return value;

            } catch (HttpClientErrorException.NotFound e) {
                // 404 = logical miss — do NOT try replicas, the key simply does not exist
                return null;
            } catch (HttpClientErrorException e) {
                // Other 4xx = client error — surface immediately, no fallback
                log.error("Client error from node {} for key '{}': {}", node, key, e.getStatusCode());
                throw e;
            } catch (Exception e) {
                log.warn("GET failed on node {} for key '{}': {}", node, key, e.getMessage());
                lastException = new CoshException(ErrorCode.NODE_UNAVAILABLE,
                        "Node " + node + " unavailable", e);
                // Fall through to next replica
            }
        }

        throw lastException != null
                ? lastException
                : new CoshException(ErrorCode.NO_NODES_AVAILABLE, "All nodes failed for key: " + key);
    }

    /**
     * PUT a value across all nodes. Primary must succeed; replica failures are
     * tolerated.
     *
     * @param nodes ordered list (nodes[0] = primary)
     * @param key   cache key
     * @param value value to store
     * @throws CoshException if the primary node fails
     */
    public void put(List<String> nodes, String key, String value) {
        for (int i = 0; i < nodes.size(); i++) {
            String node = nodes.get(i);
            boolean isPrimary = (i == 0);

            try {
                restClient.put()
                        .uri("http://" + node + "/cache/" + key)
                        .contentType(MediaType.TEXT_PLAIN)
                        .body(value)
                        .retrieve()
                        .toBodilessEntity();

            } catch (Exception e) {
                if (isPrimary) {
                    log.error("Primary node {} failed on PUT '{}' — aborting", node, key, e);
                    throw new CoshException(ErrorCode.NODE_UNAVAILABLE, "Primary node failed on PUT", e);
                } else {
                    log.warn("Replica node {} failed on PUT '{}' — ignoring", node, key, e);
                }
            }
        }
    }

    /**
     * DELETE a key from all nodes. Primary must succeed; replica failures are
     * tolerated.
     *
     * @param nodes ordered list (nodes[0] = primary)
     * @param key   cache key to delete
     * @throws CoshException if the primary node fails
     */
    public void delete(List<String> nodes, String key) {
        for (int i = 0; i < nodes.size(); i++) {
            String node = nodes.get(i);
            boolean isPrimary = (i == 0);

            try {
                restClient.delete()
                        .uri("http://" + node + "/cache/" + key)
                        .retrieve()
                        .toBodilessEntity();

            } catch (Exception e) {
                if (isPrimary) {
                    log.error("Primary node {} failed on DELETE '{}' — aborting", node, key, e);
                    throw new CoshException(ErrorCode.NODE_UNAVAILABLE, "Primary node failed on DELETE", e);
                } else {
                    log.warn("Replica node {} failed on DELETE '{}' — ignoring", node, key, e);
                }
            }
        }
    }
}
