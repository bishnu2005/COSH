package com.cosh.routing;

/**
 * Fast non-cryptographic ring hash: 64-bit FNV-1a over the string's UTF-16 code units,
 * followed by MurmurHash3's {@code fmix64} finalizer.
 *
 * <p>On its own, FNV-1a avalanches poorly for short, similar inputs such as {@code node-1#0} and
 * {@code node-1#1}. The finalizer spreads every input bit across all 64 output bits, which
 * is what ring placement needs. Compared with {@link MD5HashFunction} it allocates nothing
 * (no {@code getBytes}, no digest buffer) and is many times cheaper. That matters because
 * routing runs on every request. Measured in the benchmark's "Routing overhead" section.
 *
 * <p>Not suitable where adversarial keys could deliberately target one shard (no secret seed).
 */
public final class Fnv1aMixHashFunction implements HashFunction {
    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    @Override
    public long hash(String key) {
        long h = FNV_OFFSET;
        for (int i = 0, n = key.length(); i < n; i++) {
            h ^= key.charAt(i);
            h *= FNV_PRIME;
        }
        return fmix64(h);
    }

    static long fmix64(long k) {
        k ^= k >>> 33;
        k *= 0xff51afd7ed558ccdL;
        k ^= k >>> 33;
        k *= 0xc4ceb9fe1a85ec53L;
        k ^= k >>> 33;
        return k;
    }
}
