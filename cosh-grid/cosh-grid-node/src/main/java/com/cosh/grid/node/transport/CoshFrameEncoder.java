package com.cosh.grid.node.transport;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * Netty outbound handler: encodes response frames using CBP/1 binary format.
 *
 * <h2>CBP/1 Response Frame</h2>
 * 
 * <pre>
 * [0]    MAGIC  = 0xC0
 * [1]    STATUS (OK=0x00, NOT_FOUND=0x01, ERROR=0xFF)
 * [2-5]  value_length (int, big-endian) — 0 if no value body
 * [6..]  value_bytes  (raw bytes)
 * </pre>
 * 
 * Header total: 6 bytes.
 *
 * <p>
 * A response without a value body (e.g. for PUT or DEL) still writes
 * the 6-byte header with value_length = 0.
 */
public class CoshFrameEncoder extends MessageToByteEncoder<CoshResponse> {

    @Override
    protected void encode(ChannelHandlerContext ctx, CoshResponse response, ByteBuf out) {
        byte[] body = (response.value() != null) ? response.value() : new byte[0];

        out.writeByte(CoshBinaryProtocol.MAGIC);
        out.writeByte(response.status());
        out.writeInt(body.length);
        if (body.length > 0) {
            out.writeBytes(body);
        }
    }
}
