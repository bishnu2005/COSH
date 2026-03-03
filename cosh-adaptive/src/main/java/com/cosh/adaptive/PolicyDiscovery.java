package com.cosh.adaptive;

import com.cosh.core.eviction.EvictionPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * Utility for discovering {@link EvictionPolicy} implementations via Java SPI.
 *
 * <p>
 * This is the runtime bridge for "optional adaptive mode":
 * 
 * <pre>{@code
 * // In application code — no import of AdaptiveEvictionPolicy!
 * EvictionPolicy policy = PolicyDiscovery.findAdaptivePolicy()
 *         .orElse(new SampledLruPolicy()); // graceful fallback
 *
 * CoshCache cache = CoshKarui.start(
 *         KaruiConfig.builder()
 *                 .evictionPolicy(policy)
 *                 .build());
 * }</pre>
 *
 * <p>
 * If {@code cosh-adaptive} is on the classpath → {@code AdaptiveEvictionPolicy}
 * is returned.
 * If absent → {@link Optional#empty()} is returned. The caller decides what to
 * do.
 *
 * <h2>How SPI Works Here</h2>
 * <ol>
 * <li>{@code cosh-adaptive} declares itself in
 * {@code META-INF/services/com.cosh.core.eviction.EvictionPolicy}</li>
 * <li>{@link ServiceLoader#load(Class)} scans the classpath for that file</li>
 * <li>This class provides a clean API over the raw {@link ServiceLoader}</li>
 * </ol>
 */
public final class PolicyDiscovery {

    private static final Logger log = LoggerFactory.getLogger(PolicyDiscovery.class);

    private PolicyDiscovery() {
    }

    /**
     * Finds the first {@link AdaptiveEvictionPolicy} on the classpath via SPI.
     *
     * @return the adaptive policy if cosh-adaptive is present, otherwise empty
     */
    public static Optional<EvictionPolicy> findAdaptivePolicy() {
        for (EvictionPolicy policy : ServiceLoader.load(EvictionPolicy.class)) {
            if (policy instanceof AdaptiveEvictionPolicy) {
                log.info("AdaptiveEvictionPolicy discovered via SPI");
                return Optional.of(policy);
            }
        }
        log.debug("AdaptiveEvictionPolicy not found on classpath — using fallback");
        return Optional.empty();
    }

    /**
     * Returns all {@link EvictionPolicy} implementations discoverable via SPI.
     * Useful for diagnostic / admin endpoints.
     *
     * @return list of available policy instances (may be empty)
     */
    public static List<EvictionPolicy> findAllPolicies() {
        List<EvictionPolicy> found = new ArrayList<>();
        ServiceLoader.load(EvictionPolicy.class).forEach(found::add);
        log.info("SPI discovery found {} EvictionPolicy implementation(s)", found.size());
        return found;
    }
}
