package com.cosh.grid.node.transport;

import com.cosh.core.eviction.SampledLruPolicy;
import com.cosh.core.store.CacheStore;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Transport pool sizing study.
 */
class PoolSizingBenchmarkTest {

    private static final int PORT = 17071;
    private static final int OPS_PER_RUN = 2_000;
    private static final String VALUE = "v".repeat(64);

    // CBP/1 constants
    private static final byte MAGIC = (byte) 0xC0;
    private static final byte CMD_GET = 0x01;
    private static final byte CMD_PUT = 0x02;

    private static NettyNodeServer server;
    private static EventLoopGroup eventLoopGroup;

    @BeforeAll
    static void start() throws InterruptedException {
        CacheStore store = new CacheStore(10_000, new SampledLruPolicy());
        server = new NettyNodeServer(PORT, store);
        server.start();
        eventLoopGroup = new NioEventLoopGroup(16);
        Thread.sleep(200);

        System.out.println("[PoolSizing] Warming up cache with " + OPS_PER_RUN + " keys...");
        byte[] v = VALUE.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < OPS_PER_RUN; i++) {
            sendOpSingleShoot(CMD_PUT, "bench-key-" + i, v);
        }
    }

    @AfterAll
    static void stop() {
        eventLoopGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
        server.stop();
    }

    @Test
    void poolSizingStudy() throws Exception {
        int[] poolSizes = { 4, 8, 16 };
        int[] threadCounts = { 1, 4, 16 }; // added 16

        System.out.println("\n╔════════════════════════════════════════════════════════════════════╗");
        System.out.println("║    COSH Transport Pool Sizing Study                                ║");
        System.out.printf("║    Ops/run: %,d  Value: %dB                                       ║%n",
                OPS_PER_RUN, VALUE.length());
        System.out.println("╠══════════╤═══════════╤═══════════╤═══════════╤═══════════╤═════════╣");
        System.out.printf("║ %-8s │ %-9s │ %-9s │ %-9s │ %-9s │ %-7s ║%n",
                "Pool", "Threads", "Avg μs", "P50 μs", "P95 μs", "ops/sec");
        System.out.println("╠══════════╪═══════════╪═══════════╪═══════════╪═══════════╪═════════╣");

        for (int threads : threadCounts) {
            for (int poolSize : poolSizes) {
                long[] times = runPooled(threads, poolSize, OPS_PER_RUN);
                double avgUs = avg(times);
                double p50Us = pXX(times, 0.50);
                double p95Us = pXX(times, 0.95);
                double opsSec = OPS_PER_RUN / (avg(times) * OPS_PER_RUN / 1_000_000.0);

                System.out.printf("║ %-8d │ %-9d │ %7.1f μs │ %7.1f μs │ %7.1f μs │ %,7.0f ║%n",
                        poolSize, threads, avgUs, p50Us, p95Us, opsSec);
            }
        }

        // Structural assertion only
        assertTrue(OPS_PER_RUN > 0);
    }

    // ─── Pooled runner ───────────────────────────────────────────────────────────

    private long[] runPooled(int threads, int poolSize, int totalOps) throws Exception {
        int effectiveThreads = Math.min(threads, poolSize);
        long[] times = new long[totalOps];
        AtomicInteger counter = new AtomicInteger(0);
        CountDownLatch done = new CountDownLatch(effectiveThreads);
        CyclicBarrier barrier = new CyclicBarrier(effectiveThreads);

        ExecutorService exec = Executors.newFixedThreadPool(effectiveThreads);
        for (int t = 0; t < effectiveThreads; t++) {
            exec.submit(() -> {
                Channel ch = null;
                try {
                    LinkedBlockingQueue<CountDownLatch> responseQueue = new LinkedBlockingQueue<>();
                    ch = new Bootstrap()
                            .group(eventLoopGroup)
                            .channel(NioSocketChannel.class)
                            .option(ChannelOption.TCP_NODELAY, true)
                            .handler(new ChannelInitializer<io.netty.channel.socket.SocketChannel>() {
                                @Override
                                protected void initChannel(io.netty.channel.socket.SocketChannel ch) {
                                    ch.pipeline().addLast(new SimpleChannelInboundHandler<ByteBuf>() {
                                        @Override
                                        protected void channelRead0(ChannelHandlerContext ctx, ByteBuf resp) {
                                            CountDownLatch latch = responseQueue.poll();
                                            if (latch != null) latch.countDown();
                                        }

                                        @Override
                                        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                                            CountDownLatch latch = responseQueue.poll();
                                            if (latch != null) latch.countDown();
                                            ctx.close();
                                        }
                                    });
                                }
                            })
                            .connect("localhost", PORT).sync().channel();

                    barrier.await();
                    int idx;
                    while ((idx = counter.getAndIncrement()) < totalOps) {
                        String key = "bench-key-" + (idx % OPS_PER_RUN);
                        byte[] keyB = key.getBytes(StandardCharsets.UTF_8);
                        ByteBuf frame = Unpooled.buffer(12 + keyB.length);
                        frame.writeByte(MAGIC);
                        frame.writeByte(CMD_GET);
                        frame.writeShort(keyB.length);
                        frame.writeInt(0);
                        frame.writeInt(0);
                        frame.writeBytes(keyB);

                        CountDownLatch latch = new CountDownLatch(1);
                        responseQueue.add(latch);

                        long t0 = System.nanoTime();
                        ch.writeAndFlush(frame);
                        latch.await(5, TimeUnit.SECONDS);
                        times[idx] = System.nanoTime() - t0;
                    }
                } catch (Exception e) {
                    System.err.println("[PoolSizing] error: " + e.getMessage());
                } finally {
                    if (ch != null) ch.close();
                    done.countDown();
                }
            });
        }
        done.await(30, TimeUnit.SECONDS);
        exec.shutdown();
        return times;
    }

    private static void sendOpSingleShoot(byte cmd, String key, byte[] valB) throws InterruptedException {
        byte[] keyB = key.getBytes(StandardCharsets.UTF_8);
        ByteBuf frame = Unpooled.buffer(12 + keyB.length + valB.length);
        frame.writeByte(MAGIC);
        frame.writeByte(cmd);
        frame.writeShort(keyB.length);
        frame.writeInt(valB.length);
        frame.writeInt(0);
        frame.writeBytes(keyB);
        if (valB.length > 0) frame.writeBytes(valB);

        CountDownLatch latch = new CountDownLatch(1);
        new Bootstrap()
                .group(eventLoopGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .handler(new ChannelInitializer<io.netty.channel.socket.SocketChannel>() {
                    @Override
                    protected void initChannel(io.netty.channel.socket.SocketChannel ch) {
                        ch.pipeline().addLast(new SimpleChannelInboundHandler<ByteBuf>() {
                            @Override
                            protected void channelRead0(ChannelHandlerContext ctx, ByteBuf resp) {
                                latch.countDown();
                                ctx.close();
                            }
                        });
                    }
                })
                .connect("localhost", PORT).sync().channel().writeAndFlush(frame);
        latch.await(5, TimeUnit.SECONDS);
    }

    private double avg(long[] ns) {
        long sum = 0;
        for (long n : ns) sum += n;
        return sum / (double) ns.length / 1_000.0;
    }

    private double pXX(long[] ns, double pct) {
        if (ns.length == 0) return 0;
        long[] s = ns.clone();
        Arrays.sort(s);
        return s[(int) (pct * s.length)] / 1_000.0;
    }
}
