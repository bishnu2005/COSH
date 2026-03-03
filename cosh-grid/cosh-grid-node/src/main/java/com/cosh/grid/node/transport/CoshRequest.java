package com.cosh.grid.node.transport;

/**
 * Decoded representation of a CBP/1 request frame.
 * Produced by {@link CoshFrameDecoder} and consumed by
 * {@link CoshRequestHandler}.
 */
public record CoshRequest(byte cmd, String key, byte[] value, int ttlSeconds) {
}
