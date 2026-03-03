package com.cosh.grid.node.transport;

/**
 * COSH Binary Protocol v1 (CBP/1) — wire format constants for the
 * Netty-based high-performance transport.
 *
 * <h2>Request Frame Layout (server side: inbound)</h2>
 * 
 * <pre>
 * Byte  0       : MAGIC = 0xC0
 * Byte  1       : CMD   (GET=0x01, PUT=0x02, DEL=0x03)
 * Bytes 2-3     : key_length  (unsigned short, big-endian)
 * Bytes 4-7     : value_length (int, big-endian) — 0 for GET/DEL
 * Bytes 8-11    : ttl_seconds  (int, big-endian) — 0 for GET/DEL / no-expiry
 * Bytes 12..    : key bytes (UTF-8, length = key_length)
 * Bytes 12+k..  : value bytes (raw, length = value_length)
 * </pre>
 * 
 * Fixed request header: <strong>12 bytes</strong>
 *
 * <h2>Response Frame Layout (server side: outbound)</h2>
 * 
 * <pre>
 * Byte  0       : MAGIC  = 0xC0
 * Byte  1       : STATUS (OK=0x00, NOT_FOUND=0x01, ERROR=0xFF)
 * Bytes 2-5     : value_length (int, big-endian) — 0 if no value
 * Bytes 6..     : value bytes (raw, length = value_length)
 * </pre>
 * 
 * Fixed response header: <strong>6 bytes</strong>
 *
 * <h2>Wire Size vs HTTP (key "user:abc", value "session-token-xyz")</h2>
 * 
 * <pre>
 * CBP  GET request  : 12 + 8       = 20  bytes
 * HTTP GET request  : ~80 + headers ≈ 250 bytes
 *
 * CBP  GET response : 6  + 17      = 23  bytes
 * HTTP GET response : ~150 headers + 17 ≈ 167 bytes
 * </pre>
 */
public final class CoshBinaryProtocol {

    // ─── Magic byte ─────────────────────────────────────────────────────────────
    public static final byte MAGIC = (byte) 0xC0;

    // ─── Commands ────────────────────────────────────────────────────────────────
    public static final byte CMD_GET = 0x01;
    public static final byte CMD_PUT = 0x02;
    public static final byte CMD_DEL = 0x03;

    // ─── Status codes ────────────────────────────────────────────────────────────
    public static final byte STATUS_OK = 0x00;
    public static final byte STATUS_NOT_FOUND = 0x01;
    public static final byte STATUS_ERROR = (byte) 0xFF;

    // ─── Frame offsets ───────────────────────────────────────────────────────────
    /** Total size of the fixed request header in bytes. */
    public static final int REQUEST_HEADER_SIZE = 12;

    /** Total size of the fixed response header in bytes. */
    public static final int RESPONSE_HEADER_SIZE = 6;

    private CoshBinaryProtocol() {
    }
}
