package com.cosh.karui;

import com.cosh.core.api.CoshCache;

/**
 * Entry point for COSH Karui — the embedded, zero-infrastructure cache mode.
 *
 * <p>
 * Use this when you need a high-performance in-process cache without
 * running any external services or loading Spring.
 *
 * <h2>Usage</h2>
 * 
 * <pre>{@code
 * CoshCache cache = CoshKarui.start(
 *         KaruiConfig.builder()
 *                 .maxSize(10_000)
 *                 .evictionPolicy(new SampledLruPolicy())
 *                 .build());
 *
 * cache.put("session:user123", token, 3600);
 * String token = cache.get("session:user123");
 * cache.delete("session:user123");
 * }</pre>
 *
 * <p>
 * Thread-safe. No shutdown needed. GC-managed lifecycle.
 */
public final class CoshKarui {

    private CoshKarui() {
        // Static factory — no instances
    }

    /**
     * Start a new embedded COSH cache with the given configuration.
     *
     * @param config non-null configuration produced by
     *               {@link KaruiConfig#builder()}
     * @return a ready-to-use {@link CoshCache} instance
     */
    public static CoshCache start(KaruiConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("KaruiConfig must not be null");
        }
        return new KaruiCache(config);
    }
}
