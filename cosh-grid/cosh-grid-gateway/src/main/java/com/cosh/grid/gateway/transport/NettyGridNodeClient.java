package com.cosh.grid.gateway.transport;

import com.cosh.core.error.CoshException;
import com.cosh.core.error.ErrorCode;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.pool.*;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Netty-based binary transport client for COSH grid nodes.
 *
 * <h2>Connection model (Phase 3 Enhanced)</h2>
 * <p>
 * Uses one {@link FixedChannelPool} per node address, eliminating TCP
 * handshake overhead from the hot path. Pool size is configurable via
 * {@code cosh.gateway.netty.pool-size} (default 8).
 *
 * <h2>Lifecycle</h2>
 * <p>
 * Implements {@link Closeable}: call {@link #close()} or configure as a
 * Spring-managed bean to drain pools cleanly on shutdown. The shared
 * {@link NioEventLoopGroup} is shut down gracefully on close.
 *
 * <h2>Protocol</h2>
 * <p>
 * CBP/1 binary: 12-byte request header + payload, 6-byte response header
 * + optional value body. No JSON, no HTTP overhead.
 *
 * <h2>Connection options</h2>
 * <ul>
 * <li>{@code SO_KEEPALIVE = true} — OS-level keep-alive probes</li>
 * <li>{@code TCP_NODELAY = true} — disable Nagle for sub-ms latency</li>
 * </ul>
 */
public class NettyGridNodeClient implements NodeTransport, Closeable {

    private static final Logger log = LoggerFactory.getLogger(NettyGridNodeClient.class);

    private static final int TIMEOUT_SECONDS = 5;

    // ─── CBP/1 constants (mirrored from node-side) ───────────────────────────────
    private static final byte MAGIC = (byte) 0xC0;
    private static final byte CMD_GET = 0x01;
    private static final byte CMD_PUT = 0x02;
    private static final byte CMD_DEL = 0x03;
    private static final byte STATUS_OK = 0x00;
    private static final byte STATUS_NOT_FOUND = 0x01;

    // ─── Pool infrastructure ─────────────────────────────────────────────────────
    private final int poolSize;
    private final EventLoopGroup group;
    private final Bootstrap baseBootstrap;
    /** One FixedChannelPool per "host:port" string. Created lazily, thread-safe. */
    private final Map<String, FixedChannelPool> pools = new ConcurrentHashMap<>();

    // ─── Constructors
    // ─────────────────────────────────────────────────────────────

    public NettyGridNodeClient() {
        this(8);
    }

    public NettyGridNodeClient(int poolSize) {
        this.poolSize = poolSize;
        this.group = new NioEventLoopGroup(Math.min(poolSize, Runtime.getRuntime().availableProcessors() * 2));
        this.baseBootstrap = new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, TIMEOUT_SECONDS * 1000);
        log.info("NettyGridNodeClient initialized with pool-size={}", poolSize);
    }

    // ─── NodeTransport API ───────────────────────────────────────────────────────

    @Override
    public String get(List<String> nodes, String key) {
        CoshException lastEx = null;
        for (String node : nodes) {
            try {
                return sendRequest(node, CMD_GET,
                        key.getBytes(StandardCharsets.UTF_8), new byte[0], 0);
            } catch (CoshException e) {
                log.warn("Netty GET failed on {}: {}", node, e.getMessage());
                lastEx = e;
            }
        }
        throw (lastEx != null) ? lastEx : new CoshException(ErrorCode.NO_NODES_AVAILABLE, "All nodes failed");
    }

    @Override
    public void put(List<String> nodes, String key, String value) {
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        byte[] valueBytes = value.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < nodes.size(); i++) {
            String node = nodes.get(i);
            boolean isPrimary = (i == 0);
            try {
                sendRequest(node, CMD_PUT, keyBytes, valueBytes, 0);
            } catch (CoshException e) {
                if (isPrimary)
                    throw e;
                log.warn("Netty PUT replica {} failed — ignoring: {}", node, e.getMessage());
            }
        }
    }

    @Override
    public void delete(List<String> nodes, String key) {
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < nodes.size(); i++) {
            String node = nodes.get(i);
            boolean isPrimary = (i == 0);
            try {
                sendRequest(node, CMD_DEL, keyBytes, new byte[0], 0);
            } catch (CoshException e) {
                if (isPrimary)
                    throw e;
                log.warn("Netty DEL replica {} failed — ignoring: {}", node, e.getMessage());
            }
        }
    }

    // ─── Pool management ─────────────────────────────────────────────────────────

    /**
     * Returns the pool for {@code nodeAddress} ("host:port"), creating it on
     * first access. Thread-safe via {@link ConcurrentHashMap#computeIfAbsent}.
     */
    private FixedChannelPool poolFor(String nodeAddress) {
        return pools.computeIfAbsent(nodeAddress, addr -> {
            String[] parts = addr.split(":");
            String host = parts[0];
            int port = Integer.parseInt(parts[1]);

            Bootstrap bs = baseBootstrap.clone()
                    .remoteAddress(new InetSocketAddress(host, port));

            FixedChannelPool pool = new FixedChannelPool(
                    bs,
                    new AbstractChannelPoolHandler() {
                        @Override
                        public void channelCreated(Channel ch) {
                            // Minimal pipeline — handler added per-request in sendRequest()
                            log.debug("Pool channel created for {}", addr);
                        }
                    },
                    poolSize);
            log.info("Created connection pool[size={}] for {}", poolSize, addr);
            return pool;
        });
    }

    // ─── Core request/response ───────────────────────────────────────────────────

    /**
     * Acquires a channel from the pool, writes a CBP/1 request frame, waits
     * for the response, then releases the channel back to the pool.
     *
     * <p>
     * Note: channels are borrowed and returned via {@link Future#addListener}
     * after the response arrives. The calling thread blocks on a
     * {@link CountDownLatch} — suitable for the synchronous NodeTransport API.
     */
    private String sendRequest(String nodeAddress, byte cmd,
            byte[] key, byte[] value, int ttl) {
        FixedChannelPool pool = poolFor(nodeAddress);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> resultRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        ByteBuf frame = buildFrame(cmd, key, value, ttl);

        Future<Channel> acquire = pool.acquire();
        acquire.addListener((Future<Channel> f) -> {
            if (!f.isSuccess()) {
                errorRef.set(f.cause());
                latch.countDown();
                return;
            }
            Channel ch = f.getNow();
            // Add a one-shot response handler
            ch.pipeline().addLast("resp-" + System.nanoTime(),
                    new SimpleChannelInboundHandler<ByteBuf>(false) {
                        @Override
                        protected void channelRead0(ChannelHandlerContext ctx, ByteBuf resp) {
                            try {
                                parseResponse(resp, resultRef, errorRef);
                            } finally {
                                ctx.pipeline().remove(this);
                                pool.release(ch);
                                latch.countDown();
                            }
                        }

                        @Override
                        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                            errorRef.set(cause);
                            ctx.pipeline().remove(this);
                            pool.release(ch);
                            latch.countDown();
                        }
                    });
            ch.writeAndFlush(frame);
        });

        try {
            if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new CoshException(ErrorCode.NODE_UNAVAILABLE,
                        "Timeout on " + nodeAddress);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CoshException(ErrorCode.NODE_UNAVAILABLE,
                    "Interrupted on " + nodeAddress);
        }

        if (errorRef.get() != null) {
            throw new CoshException(ErrorCode.NODE_UNAVAILABLE,
                    "Node " + nodeAddress + ": " + errorRef.get().getMessage(),
                    errorRef.get());
        }
        return resultRef.get();
    }

    // ─── CBP/1 helpers ───────────────────────────────────────────────────────────

    private ByteBuf buildFrame(byte cmd, byte[] key, byte[] value, int ttl) {
        ByteBuf buf = Unpooled.buffer(12 + key.length + value.length);
        buf.writeByte(MAGIC);
        buf.writeByte(cmd);
        buf.writeShort(key.length);
        buf.writeInt(value.length);
        buf.writeInt(ttl);
        buf.writeBytes(key);
        if (value.length > 0)
            buf.writeBytes(value);
        return buf;
    }

    private void parseResponse(ByteBuf resp,
            AtomicReference<String> resultRef,
            AtomicReference<Throwable> errorRef) {
        if (resp.readableBytes() < 6) {
            errorRef.set(new IllegalStateException("Short CBP/1 response frame"));
            return;
        }
        resp.readByte(); // MAGIC (not validated here — trust server)
        byte status = resp.readByte();
        int valLen = resp.readInt();

        if (status == STATUS_OK && valLen > 0) {
            byte[] bytes = new byte[valLen];
            resp.readBytes(bytes);
            resultRef.set(new String(bytes, StandardCharsets.UTF_8));
        } else if (status == STATUS_NOT_FOUND) {
            resultRef.set(null); // cache miss — valid, not an error
        }
    }

    // ─── Lifecycle ───────────────────────────────────────────────────────────────

    @Override
    public void close() {
        log.info("Closing NettyGridNodeClient — draining {} pool(s)", pools.size());
        pools.values().forEach(FixedChannelPool::close);
        pools.clear();
        group.shutdownGracefully(0, 2, TimeUnit.SECONDS)
                .syncUninterruptibly();
        log.info("NettyGridNodeClient shutdown complete");
    }
}
