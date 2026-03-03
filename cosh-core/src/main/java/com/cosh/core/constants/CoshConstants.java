package com.cosh.core.constants;

/**
 * Global constants for the COSH cache engine.
 */
public final class CoshConstants {

    /** Number of virtual nodes per physical node in the consistent hash ring. */
    public static final int VIRTUAL_NODES_PER_PHYSICAL_NODE = 100;

    /** Default header for forwarding cache key context. */
    public static final String HEADER_CACHE_KEY = "X-Cache-Key";

    /** Default TTL (in seconds) when none is specified — 0 = no expiry. */
    public static final long DEFAULT_TTL_SECONDS = 0L;

    private CoshConstants() {
        // Utility class — prevent instantiation
    }
}
