package com.cosh.grid.node.transport;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "cosh.node.netty-port=17080",
        "logging.level.org.springframework=WARN"
})
class UnifiedTransportBenchmarkTest {

    @LocalServerPort
    private int httpPort;
    private static final int NETTY_PORT = 17080;

    private static final int THREADS = 16;
    private static final int TOTAL_OPS = 5_000;
    private static final String VALUE = "v".repeat(128);

    private static final byte MAGIC = (byte) 0xC0;
    private static final byte CMD_GET = 0x01;
    private static final byte CMD_PUT = 0x02;

    private final Channel[] channels = new Channel[THREADS];
    private final LinkedBlockingQueue<CountDownLatch>[] latches = new LinkedBlockingQueue[THREADS];
    private static EventLoopGroup eventLoopGroup;
    private static HttpClient httpClient;

    @BeforeAll
    static void initClients() {
        eventLoopGroup = new NioEventLoopGroup(THREADS);
        httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @AfterAll
    static void tearDownClients() {
        if (eventLoopGroup != null) {
            eventLoopGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
        }
    }

    private void setupNetty() throws Exception {
        for (int i = 0; i < THREADS; i++) {
            latches[i] = new LinkedBlockingQueue<>();
            final int pIdx = i;
            channels[i] = new Bootstrap()
                    .group(eventLoopGroup)
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.TCP_NODELAY, true)
                    .handler(new ChannelInitializer<io.netty.channel.socket.SocketChannel>() {
                        @Override
                        protected void initChannel(io.netty.channel.socket.SocketChannel ch) {
                            ch.pipeline().addLast(new SimpleChannelInboundHandler<ByteBuf>() {
                                @Override
                                protected void channelRead0(ChannelHandlerContext ctx, ByteBuf resp) {
                                    CountDownLatch latch = latches[pIdx].poll();
                                    if (latch != null) latch.countDown();
                                }
                                @Override
                                public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                                    CountDownLatch latch = latches[pIdx].poll();
                                    if (latch != null) latch.countDown();
                                    ctx.close();
                                }
                            });
                        }
                    })
                    .connect("localhost", NETTY_PORT).sync().channel();
        }
    }

    private void tearDownNetty() {
        for (Channel c : channels) {
            if (c != null && c.isOpen()) {
                c.close().syncUninterruptibly();
            }
        }
    }

    @FunctionalInterface
    interface BenchmarkAction {
        void run(int threadId, int opIndex) throws Exception;
    }

    private long[] runConcurrent(BenchmarkAction action) throws Exception {
        long[] times = new long[TOTAL_OPS];
        AtomicInteger counter = new AtomicInteger(0);
        CountDownLatch done = new CountDownLatch(THREADS);
        CyclicBarrier barrier = new CyclicBarrier(THREADS);

        ExecutorService exec = Executors.newFixedThreadPool(THREADS);
        for (int t = 0; t < THREADS; t++) {
            final int threadId = t;
            exec.submit(() -> {
                try {
                    barrier.await();
                    int idx;
                    while ((idx = counter.getAndIncrement()) < TOTAL_OPS) {
                        long t0 = System.nanoTime();
                        action.run(threadId, idx);
                        times[idx] = System.nanoTime() - t0;
                    }
                } catch (Exception e) {
                    System.err.println("[UnifiedTest] Error: " + e.getMessage());
                } finally {
                    done.countDown();
                }
            });
        }
        done.await(60, TimeUnit.SECONDS);
        exec.shutdown();
        return times;
    }

    // ─── Actions ───────────────────────────────────────────────────────────────

    private void nettyPut(int threadId, int i) throws Exception {
        String key = "bench-key-" + (i % TOTAL_OPS);
        byte[] keyB = key.getBytes(StandardCharsets.UTF_8);
        byte[] valB = VALUE.getBytes(StandardCharsets.UTF_8);
        ByteBuf frame = Unpooled.buffer(12 + keyB.length + valB.length);
        frame.writeByte(MAGIC);
        frame.writeByte(CMD_PUT);
        frame.writeShort(keyB.length);
        frame.writeInt(valB.length);
        frame.writeInt(0);
        frame.writeBytes(keyB);
        frame.writeBytes(valB);

        CountDownLatch latch = new CountDownLatch(1);
        latches[threadId].add(latch);
        channels[threadId].writeAndFlush(frame);
        latch.await(5, TimeUnit.SECONDS);
    }

    private void nettyGet(int threadId, int i) throws Exception {
        String key = "bench-key-" + (i % TOTAL_OPS);
        byte[] keyB = key.getBytes(StandardCharsets.UTF_8);
        ByteBuf frame = Unpooled.buffer(12 + keyB.length);
        frame.writeByte(MAGIC);
        frame.writeByte(CMD_GET);
        frame.writeShort(keyB.length);
        frame.writeInt(0);
        frame.writeInt(0);
        frame.writeBytes(keyB);

        CountDownLatch latch = new CountDownLatch(1);
        latches[threadId].add(latch);
        channels[threadId].writeAndFlush(frame);
        latch.await(5, TimeUnit.SECONDS);
    }

    private void httpPut(int threadId, int i) throws Exception {
        String key = "bench-key-" + (i % TOTAL_OPS);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + httpPort + "/cache/" + key))
                .PUT(HttpRequest.BodyPublishers.ofString(VALUE))
                .header("Content-Type", "text/plain")
                .build();
        httpClient.send(req, HttpResponse.BodyHandlers.discarding());
    }

    private void httpGet(int threadId, int i) throws Exception {
        String key = "bench-key-" + (i % TOTAL_OPS);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + httpPort + "/cache/" + key))
                .GET()
                .build();
        httpClient.send(req, HttpResponse.BodyHandlers.discarding());
    }

    // ─── Stats ───────────────────────────────────────────────────────────────────

    private double avg(long[] ns) {
        if (ns.length == 0) return 0;
        long sum = 0;
        for (long n : ns) sum += n;
        return sum / (double) ns.length / 1_000.0;
    }

    private double p99(long[] ns) {
        if (ns.length == 0) return 0;
        long[] s = ns.clone();
        Arrays.sort(s);
        return s[(int) (0.99 * s.length)] / 1_000.0;
    }

    @Test
    void compareTransportProtocols() throws Exception {
        System.out.println("Starting Unified Transport Benchmark...");
        setupNetty();

        // Warmup Netty
        runConcurrent(this::nettyPut);

        // Benchmark Netty
        long[] nettyPutTimes = runConcurrent(this::nettyPut);
        long[] nettyGetTimes = runConcurrent(this::nettyGet);

        tearDownNetty();

        // Warmup HTTP
        runConcurrent(this::httpPut);

        // Benchmark HTTP
        long[] httpPutTimes = runConcurrent(this::httpPut);
        long[] httpGetTimes = runConcurrent(this::httpGet);

        System.out.println("\n╔══════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║   COSH Phase 4 — Unified Transport Benchmark: HTTP/1.1 vs Netty CBP/1   ║");
        System.out.printf("║   Ops: %,d  Threads: %d  Value: %dB                                    ║%n",
                TOTAL_OPS, THREADS, VALUE.length());
        System.out.println("╠═══════════════════════╤═════════════════════════╤════════════════════════╣");
        System.out.printf("║ %-21s │ %-23s │ %-22s ║%n", "Metric", "Netty CBP/1", "Spring MVC HTTP/1.1");
        System.out.println("╠═══════════════════════╪═════════════════════════╪════════════════════════╣");
        System.out.printf("║ %-21s │ %13.1f μs       │ %15.1f μs      ║%n", "PUT avg", avg(nettyPutTimes), avg(httpPutTimes));
        System.out.printf("║ %-21s │ %13.1f μs       │ %15.1f μs      ║%n", "PUT P99", p99(nettyPutTimes), p99(httpPutTimes));
        System.out.printf("║ %-21s │ %13.1f μs       │ %15.1f μs      ║%n", "GET avg", avg(nettyGetTimes), avg(httpGetTimes));
        System.out.printf("║ %-21s │ %13.1f μs       │ %15.1f μs      ║%n", "GET P99", p99(nettyGetTimes), p99(httpGetTimes));
        System.out.println("╚═══════════════════════╧═════════════════════════╧════════════════════════╝\n");

        assertTrue(avg(nettyPutTimes) > 0);
        assertTrue(avg(httpPutTimes) > 0);
    }
}
