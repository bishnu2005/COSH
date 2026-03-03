package com.cosh.grid.gateway.config;

import com.cosh.grid.gateway.routing.ConsistentHashRing;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.util.Arrays;
import java.util.List;

/**
 * Spring configuration for the COSH gateway.
 *
 * <p>
 * Node list is provided via the {@code COSH_GATEWAY_NODES} environment variable
 * (or {@code cosh.gateway.nodes} property), as a comma-separated list of
 * {@code host:port}.
 *
 * <p>
 * Example: {@code COSH_GATEWAY_NODES=node1:8080,node2:8080}
 */
@Configuration
public class GatewayConfig {

    @Value("${cosh.gateway.nodes}")
    private String nodesProperty;

    @Bean
    public RestClient restClient() {
        return RestClient.builder().build();
    }

    @Bean
    public ConsistentHashRing consistentHashRing() {
        if (nodesProperty == null || nodesProperty.isBlank()) {
            throw new IllegalStateException(
                    "Property 'cosh.gateway.nodes' must be set (comma-separated host:port list)");
        }

        List<String> nodeList = Arrays.stream(nodesProperty.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        return new ConsistentHashRing(nodeList);
    }
}
