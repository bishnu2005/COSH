package com.cosh.grid.node.transport;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Netty inbound handler: decodes a raw TCP byte stream into {@link CoshRequest}
 * objects.
 *
 * <h2>CBP/1 Request Frame</h2>
 * 
 * <pre>
 * [0]    MAGIC  = 0xC0
 * [1]    CMD    (GET=0x01, PUT=0x02, DEL=0x03)
 * [2-3]  key_length  (unsigned short, big-endian)
 * [4-7]  value_length (int, big-endian)
 * [8-11] ttl_seconds  (int, big-endian)
 * [12..] key_bytes   (UTF-8)
 * [...] value_bytes (raw)
 * </pre>
 *
 * <p>
 * {@link ByteToMessageDecoder} accumulates bytes until a complete frame is
 * present.
 * Partial frames are left in the buffer for the next read cycle.
 *
 * <p>
 * This handler is <em>not</em> thread-safe by itself (Netty guarantees
 * single-thread per channel per handler, so this is correct).
 * NOT {@code @Sharable} — {@link ByteToMessageDecoder} accumulates partial
 * bytes in internal state; a new instance must be created per channel.
 */
public class CoshFrameDecoder extends ByteToMessageDecoder {

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        // Must have at least the fixed 12-byte header
        if (in.readableBytes() < CoshBinaryProtocol.REQUEST_HEADER_SIZE) {
            return;
        }

        in.markReaderIndex();

        byte magic = in.readByte();
        if (magic != CoshBinaryProtocol.MAGIC) {
            // Protocol violation — close connection
            ctx.close();
            return;
        }

        byte cmd = in.readByte();
        int keyLength = in.readUnsignedShort();
        int valueLength = in.readInt();
        int ttlSeconds = in.readInt();

        int totalPayload = keyLength + valueLength;
        if (in.readableBytes() < totalPayload) {
            // Not enough bytes yet — wait for more
            in.resetReaderIndex();
            return;
        }

        byte[] keyBytes = new byte[keyLength];
        in.readBytes(keyBytes);

        byte[] valueBytes = new byte[valueLength];
        if (valueLength > 0) {
            in.readBytes(valueBytes);
        }

        String key = new String(keyBytes, StandardCharsets.UTF_8);
        out.add(new CoshRequest(cmd, key, valueBytes, ttlSeconds));
    }
}
