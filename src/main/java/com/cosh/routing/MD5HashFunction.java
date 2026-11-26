package com.cosh.routing;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * MD5-based ring hash, the scheme used by ketama-style memcached clients. MD5 is used here for
 * its uniform bit distribution, not for security.
 *
 * <ul>
 *   <li>The first 8 digest bytes become a 64-bit ring position (the original version used 32
 *       bits). That makes virtual-node collisions negligible, about 1e-12 for thousands of points.</li>
 *   <li>Bytes are encoded as UTF-8 explicitly, so routing is identical on every JVM and OS
 *       (the original version used the platform default charset).</li>
 *   <li>One {@link MessageDigest} is reused per thread. {@code getInstance} on every call was a
 *       provider lookup plus an allocation on the hot routing path.</li>
 * </ul>
 */
public class MD5HashFunction implements HashFunction {
    private static final ThreadLocal<MessageDigest> MD5 = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 algorithm not available", e);
        }
    });

    @Override
    public long hash(String key) {
        byte[] d = MD5.get().digest(key.getBytes(StandardCharsets.UTF_8)); // digest() also resets
        long h = 0;
        for (int i = 0; i < 8; i++) {
            h = (h << 8) | (d[i] & 0xFFL);
        }
        return h;
    }
}
