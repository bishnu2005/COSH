package com.cosh.grid.gateway.transport;

import com.cosh.grid.gateway.client.GridNodeClient;
import com.cosh.grid.gateway.routing.ConsistentHashRing;
import com.cosh.grid.gateway.routing.CacheRouter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Wires the active {@link NodeTransport} implementation based on
 * the {@code cosh.gateway.transport} configuration property.
 *
 * <p>
 * Transport selection (set in application.yml or env var):
 * 
 * <pre>
 *   cosh.gateway.transport: http    → {@link GridNodeClient}     (default, no change)
 *   cosh.gateway.transport: netty   → {@link NettyGridNodeClient} (CBP/1 binary protocol)
 * </pre>
 *
 * <p>
 * Zero code change required in {@code GatewayController} — it injects
 * {@link NodeTransport} regardless of which is active.
 */
@Configuration
public class TransportConfig {

    @Value("${cosh.gateway.netty.pool-size:8}")
    private int nettyPoolSize;

    @Bean
    @ConditionalOnProperty(name = "cosh.gateway.transport", havingValue = "http", matchIfMissing = true)
    public NodeTransport httpNodeTransport(RestClient restClient, MeterRegistry registry) {
        return new GridNodeClient(restClient, registry);
    }

    @Bean
    @ConditionalOnProperty(name = "cosh.gateway.transport", havingValue = "netty")
    public NodeTransport nettyNodeTransport() {
        return new NettyGridNodeClient(nettyPoolSize);
    }
}
