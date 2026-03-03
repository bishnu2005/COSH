package com.cosh.grid.node.transport;

import com.cosh.core.store.CacheStore;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

/**
 * Netty inbound handler: processes decoded {@link CoshRequest} objects.
 *
 * <p>
 * This handler is the only point where the binary transport touches
 * {@link CacheStore}. The hot path is:
 * <ol>
 * <li>Decode frame → {@link CoshRequest}</li>
 * <li>Dispatch to CacheStore (GET / PUT / DELETE)</li>
 * <li>Encode response → {@link CoshResponse} → {@link CoshFrameEncoder}</li>
 * </ol>
 *
 * <p>
 * Values crossing the wire are raw bytes. Internally, we convert to/from
 * {@code String} when calling {@link CacheStore} to keep the cosh-core public
 * API unchanged. No JSON serialization occurs on this path.
 *
 * <p>
 * {@code @Sharable} is safe: all state is in {@code CacheStore} (thread-safe).
 */
@io.netty.channel.ChannelHandler.Sharable
public class CoshRequestHandler extends SimpleChannelInboundHandler<CoshRequest> {

    private static final Logger log = LoggerFactory.getLogger(CoshRequestHandler.class);

    private final CacheStore cacheStore;

    public CoshRequestHandler(CacheStore cacheStore) {
        this.cacheStore = cacheStore;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, CoshRequest req) {
        CoshResponse response = switch (req.cmd()) {
            case CoshBinaryProtocol.CMD_GET -> handleGet(req.key());
            case CoshBinaryProtocol.CMD_PUT -> handlePut(req.key(), req.value(), req.ttlSeconds());
            case CoshBinaryProtocol.CMD_DEL -> handleDelete(req.key());
            default -> {
                log.warn("Unknown command byte: 0x{}", Integer.toHexString(req.cmd() & 0xFF));
                yield CoshResponse.error();
            }
        };
        ctx.writeAndFlush(response);
    }

    // ─── Handlers ────────────────────────────────────────────────────────────────

    private CoshResponse handleGet(String key) {
        String value = cacheStore.get(key);
        if (value == null) {
            return CoshResponse.notFound();
        }
        // Values stored/served as UTF-8 bytes — no JSON overhead
        return CoshResponse.ok(value.getBytes(StandardCharsets.UTF_8));
    }

    private CoshResponse handlePut(String key, byte[] valueBytes, int ttlSeconds) {
        String value = new String(valueBytes, StandardCharsets.UTF_8);
        try {
            cacheStore.put(key, value, ttlSeconds);
            return CoshResponse.ok();
        } catch (Exception e) {
            log.error("PUT failed for key '{}': {}", key, e.getMessage());
            return CoshResponse.error();
        }
    }

    private CoshResponse handleDelete(String key) {
        cacheStore.delete(key);
        return CoshResponse.ok();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Netty channel error for {}: {}", ctx.channel().remoteAddress(), cause.getMessage());
        ctx.close();
    }
}
