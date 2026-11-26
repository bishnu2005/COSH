package com.cosh.routing;

/** Maps a string to a position on the 64-bit hash ring. Must be deterministic and thread-safe. */
@FunctionalInterface
public interface HashFunction {
    long hash(String key);
}
