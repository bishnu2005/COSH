package com.cosh.adaptive;

import com.cosh.core.eviction.EvictionPolicy;
import com.cosh.core.store.CacheStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AdaptiveEvictionPolicy}.
 *
 * <p>
 * Covers:
 * <ul>
 * <li>Eviction correctness — cold key evicted over hot key</li>
 * <li>SPI registration — ServiceLoader discovers the policy</li>
 * <li>Optionality — system works when using adaptive as a drop-in</li>
 * <li>Stats lifecycle — onPut/onGet/onRemove housekeeping</li>
 * </ul>
 */
class AdaptivePolicyTest {

    // ─── Core correctness ────────────────────────────────────────────────────────

    @Test
    void testHotKeyProtected() throws InterruptedException {
        AdaptiveEvictionPolicy policy = new AdaptiveEvictionPolicy();
        CacheStore store = new CacheStore(2, policy);

        store.put("hot", "h", 0);
        store.put("cold", "c", 0);

        // Warm up "hot" with 50 accesses — it should be protected by frequency score
        for (int i = 0; i < 50; i++) {
            store.get("hot");
        }

        // Let a small amount of time pass so recency differs
        Thread.sleep(5);

        // Adding a 3rd key triggers eviction — "cold" must be evicted, NOT "hot"
        store.put("new", "n", 0);

        assertNotNull(store.get("hot"), "hot key must be protected by frequency score");
        assertNotNull(store.get("new"), "newly inserted key must exist");
        // "cold" should have been evicted (it was never accessed → maximal score)
        assertNull(store.get("cold"), "cold key (never accessed) should be evicted");
    }

    @Test
    void testStatsCleanedOnRemove() {
        AdaptiveEvictionPolicy policy = new AdaptiveEvictionPolicy();
        CacheStore store = new CacheStore(10, policy);

        store.put("key", "value", 0);
        assertEquals(1, policy.trackedKeyCount());

        store.delete("key");
        assertEquals(0, policy.trackedKeyCount(), "Stats must be cleaned up on key removal");
    }

    @Test
    void testNeverAccessedKeyIsMaximalCandidate() throws InterruptedException {
        AdaptiveEvictionPolicy policy = new AdaptiveEvictionPolicy();
        CacheStore store = new CacheStore(3, policy);

        store.put("A", "v", 0);
        store.put("B", "v", 0);
        store.put("C", "v", 0); // C never accessed

        // Access A and B many times
        for (int i = 0; i < 30; i++)
            store.get("A");
        for (int i = 0; i < 30; i++)
            store.get("B");

        Thread.sleep(5); // ensure recency gap for C

        store.put("D", "v", 0); // triggers eviction

        // C should be evicted — never accessed = highest score (Double.MAX_VALUE)
        assertNull(store.get("C"), "Never-accessed key must be evicted first");
        assertNotNull(store.get("A"));
        assertNotNull(store.get("B"));
        assertNotNull(store.get("D"));
    }

    @Test
    void testDropInWithCacheStore() {
        // Drop-in usage — no special wiring needed
        CacheStore store = new CacheStore(100, new AdaptiveEvictionPolicy());
        store.put("x", "1", 60);
        assertEquals("1", store.get("x"));
        store.delete("x");
        assertNull(store.get("x"));
    }

    // ─── SPI registration ────────────────────────────────────────────────────────

    @Test
    void testSpiRegistrationDiscoverable() {
        // Verify ServiceLoader can find AdaptiveEvictionPolicy via SPI
        List<EvictionPolicy> policies = PolicyDiscovery.findAllPolicies();
        assertFalse(policies.isEmpty(), "ServiceLoader must discover at least one EvictionPolicy");

        boolean hasAdaptive = policies.stream()
                .anyMatch(p -> p instanceof AdaptiveEvictionPolicy);
        assertTrue(hasAdaptive, "AdaptiveEvictionPolicy must be discoverable via SPI");
    }

    @Test
    void testSpiDirectServiceLoader() {
        ServiceLoader<EvictionPolicy> loader = ServiceLoader.load(EvictionPolicy.class);
        long count = loader.stream().count();
        assertTrue(count >= 1, "At least one EvictionPolicy must be registered via SPI — found: " + count);
    }

    @Test
    void testPolicyDiscoveryOptional() {
        Optional<EvictionPolicy> adaptive = PolicyDiscovery.findAdaptivePolicy();
        // cosh-adaptive IS on classpath in this test — must be present
        assertTrue(adaptive.isPresent(), "findAdaptivePolicy() must return a value when cosh-adaptive is on classpath");
        assertInstanceOf(AdaptiveEvictionPolicy.class, adaptive.get());
    }
}
