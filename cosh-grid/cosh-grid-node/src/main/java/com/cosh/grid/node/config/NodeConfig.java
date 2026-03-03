package com.cosh.grid.node.config;

import com.cosh.core.eviction.SampledLruPolicy;
import com.cosh.core.store.CacheStore;
import com.cosh.grid.node.metrics.MicrometerMetricsBridge;
import com.cosh.grid.node.transport.NettyNodeServer;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring wiring for the COSH grid node.
 *
 * <p>
 * Creates:
 * <ul>
 * <li>The singleton {@link CacheStore} (framework-free core engine)</li>
 * <li>The {@link NettyNodeServer} (binary transport, alongside HTTP)</li>
 * </ul>
 * The Netty port is separate from the Spring HTTP port — both run concurrently.
 */
@Configuration
public class NodeConfig {

    @Value("${cosh.node.max-size:1000}")
    private int maxSize;

    @Value("${cosh.node.netty-port:7070}")
    private int nettyPort;

    @Bean
    public CacheStore cacheStore(MeterRegistry registry) {
        CacheStore store = new CacheStore(maxSize, new SampledLruPolicy());
        store.setMetricsListener(new MicrometerMetricsBridge(registry));
        return store;
    }

    /** Starts Netty binary transport on a dedicated port via SmartLifecycle. */
    @Bean
    public NettyNodeServer nettyNodeServer(CacheStore cacheStore) {
        return new NettyNodeServer(nettyPort, cacheStore);
    }
}
