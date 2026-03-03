package com.cosh.grid.node.transport;

import com.cosh.core.store.CacheStore;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Netty TCP server that runs on a dedicated port alongside the HTTP server.
 *
 * <p>
 * Uses the {@link SmartLifecycle} interface to integrate cleanly with
 * Spring Boot's startup/shutdown sequence. The Netty server starts after the
 * Spring context is fully initialized and shuts down gracefully before the JVM
 * exits.
 *
 * <h2>Thread model</h2>
 * <ul>
 * <li>1 boss thread — accepts connections</li>
 * <li>N worker threads (default: CPU×2) — reads, decodes, processes,
 * writes</li>
 * </ul>
 *
 * <h2>Pipeline per channel</h2>
 * 
 * <pre>
 * [CoshFrameDecoder] → [CoshRequestHandler] → [CoshFrameEncoder]
 * </pre>
 *
 * <p>
 * The {@link CoshRequestHandler} is {@code @Sharable} — one instance across
 * all channels per server, safe via {@link CacheStore}'s
 * {@link java.util.concurrent.ConcurrentHashMap}.
 */
public class NettyNodeServer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(NettyNodeServer.class);

    private final int port;
    private final CacheStore cacheStore;

    private volatile boolean running = false;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    public NettyNodeServer(int port, CacheStore cacheStore) {
        this.port = port;
        this.cacheStore = cacheStore;
    }

    @Override
    public void start() {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();

        CoshRequestHandler requestHandler = new CoshRequestHandler(cacheStore);

        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                                .addLast("decoder", new CoshFrameDecoder())
                                .addLast("encoder", new CoshFrameEncoder())
                                .addLast("handler", requestHandler);
                    }
                });

        try {
            ChannelFuture future = bootstrap.bind(port).sync();
            serverChannel = future.channel();
            running = true;
            log.info("COSH Netty binary transport listening on port {} (CBP/1)", port);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Netty server startup interrupted", e);
        }
    }

    @Override
    public void stop() {
        running = false;
        if (serverChannel != null) {
            serverChannel.close().awaitUninterruptibly();
        }
        if (bossGroup != null)
            bossGroup.shutdownGracefully();
        if (workerGroup != null)
            workerGroup.shutdownGracefully();
        log.info("COSH Netty binary transport stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Start after Spring's own lifecycle components. */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }
}
