package com.cosh.core.hashing;

import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;

import java.nio.charset.StandardCharsets;

/**
 * Consistent-hashing utility using MurmurHash3 via Guava.
 *
 * <p>
 * MurmurHash3 is preferred over MD5/SHA for cache ring placement:
 * faster, fewer collisions at 32/64-bit, non-cryptographic (appropriate here).
 *
 * <p>
 * All methods are deterministic and thread-safe (stateless).
 */
public final class HashUtils {

    private static final HashFunction MURMUR_3_32 = Hashing.murmur3_32_fixed();
    private static final HashFunction MURMUR_3_128 = Hashing.murmur3_128();

    private HashUtils() {
    }

    /**
     * 32-bit MurmurHash3 — used for general key hashing.
     *
     * @param key the input string (cache key or node identifier)
     * @return a deterministic 32-bit integer hash
     */
    public static int hash(String key) {
        if (key == null)
            return 0;
        return MURMUR_3_32.hashString(key, StandardCharsets.UTF_8).asInt();
    }

    /**
     * 64-bit MurmurHash3 — preferred for the consistent hash ring.
     * Better distribution in a {@link java.util.TreeMap} key space.
     *
     * @param key the input string
     * @return a deterministic 64-bit long hash
     */
    public static long hash64(String key) {
        if (key == null)
            return 0L;
        return MURMUR_3_128.hashString(key, StandardCharsets.UTF_8).asLong();
    }
}
