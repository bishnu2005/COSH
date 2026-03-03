package com.cosh.grid.node.transport;

/**
 * Encoded response frame to send back to the client.
 * Produced by {@link CoshRequestHandler} and consumed by
 * {@link CoshFrameEncoder}.
 */
public record CoshResponse(byte status, byte[] value) {

    /** Convenience factory: OK response with a value payload. */
    public static CoshResponse ok(byte[] value) {
        return new CoshResponse(CoshBinaryProtocol.STATUS_OK, value);
    }

    /** Convenience factory: OK response with no payload (for PUT, DELETE). */
    public static CoshResponse ok() {
        return new CoshResponse(CoshBinaryProtocol.STATUS_OK, null);
    }

    /** Convenience factory: key not found. */
    public static CoshResponse notFound() {
        return new CoshResponse(CoshBinaryProtocol.STATUS_NOT_FOUND, null);
    }

    /** Convenience factory: server-side error. */
    public static CoshResponse error() {
        return new CoshResponse(CoshBinaryProtocol.STATUS_ERROR, null);
    }
}
