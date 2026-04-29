package com.example.tcploadtester.integration;

import com.example.tcploadtester.config.LoadTestConfig;
import com.example.tcploadtester.device.DeviceIdentityAllocator;
import com.example.tcploadtester.device.DeviceSession;
import com.example.tcploadtester.netty.DeviceChannelInitializer;
import com.example.tcploadtester.netty.DeviceMessageHandler;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.util.CharsetUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoadTestFlowIntegrationTest {

    private final EventLoopGroup bossGroup = new NioEventLoopGroup(1);
    private final EventLoopGroup workerGroup = new NioEventLoopGroup(1);
    private final EventLoopGroup clientGroup = new NioEventLoopGroup(1);
    private final List<MessageEvent> receivedMessages = new CopyOnWriteArrayList<>();
    private final ByteArrayOutputStream errBuffer = new ByteArrayOutputStream();
    private PrintStream originalErr;
    private ChannelFuture serverFuture;

    @BeforeEach
    void setUp() {
        errBuffer.reset();
        originalErr = System.err;
        System.setErr(new PrintStream(errBuffer, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void tearDown() {
        System.setErr(originalErr);
        if (serverFuture != null) {
            serverFuture.channel().close().syncUninterruptibly();
        }
        bossGroup.shutdownGracefully().syncUninterruptibly();
        workerGroup.shutdownGracefully().syncUninterruptibly();
        clientGroup.shutdownGracefully().syncUninterruptibly();
    }

    @Test
    void loginAckThenPeriodicReportWithoutWaiting311() throws Exception {
        CountDownLatch loginLatch = new CountDownLatch(1);
        CountDownLatch twoReportsLatch = new CountDownLatch(2);
        AtomicBoolean loginAcked = new AtomicBoolean(false);
        startServer((ctx, event) -> {
            if (event.msgType() == 110 && loginAcked.compareAndSet(false, true)) {
                loginLatch.countDown();
                ctx.writeAndFlush(loginAck(event.txnNo()));
                return;
            }
            if (event.msgType() == 310) {
                twoReportsLatch.countDown();
            }
        });

        startClient(testConfig());

        assertTrue(loginLatch.await(3, TimeUnit.SECONDS));
        assertTrue(twoReportsLatch.await(5, TimeUnit.SECONDS));

        List<MessageEvent> reports = receivedMessages.stream()
                .filter(e -> e.msgType() == 310)
                .toList();
        assertTrue(reports.size() >= 2);
    }

    @Test
    void resendSameLoginUntilReconnectThenFreshLogin() throws Exception {
        CountDownLatch secondConnectionLoginLatch = new CountDownLatch(1);
        AtomicReference<String> firstChannelId = new AtomicReference<>();
        AtomicBoolean secondConnectionAcked = new AtomicBoolean(false);
        startServer((ctx, event) -> {
            if (event.msgType() != 110) return;
            String channelId = event.channelId();
            if (firstChannelId.compareAndSet(null, channelId)) {
                return;
            }
            if (!channelId.equals(firstChannelId.get()) && secondConnectionAcked.compareAndSet(false, true)) {
                secondConnectionLoginLatch.countDown();
                ctx.writeAndFlush(loginAck(event.txnNo()));
            }
        });

        startClient(testConfig());

        assertTrue(secondConnectionLoginLatch.await(8, TimeUnit.SECONDS));

        List<MessageEvent> firstLogins = receivedMessages.stream()
                .filter(e -> e.msgType() == 110)
                .filter(e -> e.channelId().equals(firstChannelId.get()))
                .toList();
        assertTrue(firstLogins.size() >= 3);
        assertEquals(1, firstLogins.stream().map(MessageEvent::txnNo).distinct().count());

        MessageEvent secondLogin = receivedMessages.stream()
                .filter(e -> e.msgType() == 110)
                .filter(e -> !e.channelId().equals(firstChannelId.get()))
                .findFirst().orElseThrow();
        assertNotEquals(firstLogins.get(0).txnNo(), secondLogin.txnNo());
    }

    @Test
    void delayedLoginAckStopsRetryImmediately() throws Exception {
        CountDownLatch firstLoginLatch = new CountDownLatch(1);
        CountDownLatch retryLoginLatch = new CountDownLatch(1);
        AtomicReference<String> pendingTxnNo = new AtomicReference<>();
        startServer((ctx, event) -> {
            if (event.msgType() == 110) {
                if (pendingTxnNo.compareAndSet(null, event.txnNo())) {
                    firstLoginLatch.countDown();
                    return;
                }
                if (event.txnNo().equals(pendingTxnNo.get())) {
                    retryLoginLatch.countDown();
                    ctx.writeAndFlush(loginAck(event.txnNo()));
                }
            }
        });

        startClient(testConfig());

        assertTrue(firstLoginLatch.await(3, TimeUnit.SECONDS));
        assertTrue(retryLoginLatch.await(4, TimeUnit.SECONDS));
        Thread.sleep(1800);

        String logs = capturedLogs();
        assertTrue(logs.contains("missing login ack txnNo=" + pendingTxnNo.get() + ", retry #1"));
        assertTrue(!logs.contains("missing login ack txnNo=" + pendingTxnNo.get() + ", retry #2"));
        assertTrue(!logs.contains("login retry window exceeded"));
    }

    @Test
    void disconnectTriggersReconnectAndRelogin() throws Exception {
        CountDownLatch secondLoginLatch = new CountDownLatch(1);
        AtomicBoolean firstAcked = new AtomicBoolean(false);
        AtomicBoolean secondAcked = new AtomicBoolean(false);
        AtomicReference<io.netty.channel.Channel> firstChannel = new AtomicReference<>();
        startServer((ctx, event) -> {
            if (event.msgType() == 110) {
                if (firstAcked.compareAndSet(false, true)) {
                    firstChannel.set(ctx.channel());
                    ctx.writeAndFlush(loginAck(event.txnNo()));
                    return;
                }
                if (secondAcked.compareAndSet(false, true)) {
                    secondLoginLatch.countDown();
                    ctx.writeAndFlush(loginAck(event.txnNo()));
                }
            }
        });

        startClient(testConfig());

        Thread.sleep(1500);
        io.netty.channel.Channel ch = firstChannel.get();
        if (ch != null) {
            ch.close().sync();
        }

        assertTrue(secondLoginLatch.await(5, TimeUnit.SECONDS));
        assertTrue(receivedMessages.stream().filter(e -> e.msgType() == 110).count() >= 2);
    }

    private void startServer(TestServerHandler handler) throws InterruptedException {
        serverFuture = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) {
                        channel.pipeline().addLast(new StringDecoder(CharsetUtil.UTF_8));
                        channel.pipeline().addLast(new StringEncoder(CharsetUtil.UTF_8));
                        channel.pipeline().addLast(new SimpleChannelInboundHandler<String>() {
                            @Override
                            protected void channelRead0(ChannelHandlerContext ctx, String msg) {
                                MessageEvent event = new MessageEvent(
                                        ctx.channel().id().asShortText(),
                                        extractMsgType(msg),
                                        extractTxnNo(msg)
                                );
                                receivedMessages.add(event);
                                handler.handle(ctx, event);
                            }
                        });
                    }
                })
                .bind(19090)
                .sync();
    }

    private void startClient(LoadTestConfig config) {
        DeviceIdentityAllocator.DeviceIdentity identity = DeviceIdentityAllocator.allocate(1);
        DeviceSession session = new DeviceSession(1, identity.devId(), identity.imsi());
        Bootstrap bootstrap = new Bootstrap()
                .group(clientGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000);
        DeviceMessageHandler handler = new DeviceMessageHandler(session, config, bootstrap);
        bootstrap.handler(new DeviceChannelInitializer(handler, config.readerIdleSeconds()));
        bootstrap.connect(config.host(), config.port()).syncUninterruptibly();
    }

    private LoadTestConfig testConfig() {
        return new LoadTestConfig("127.0.0.1", 19090, 1, 1, 1, 100, 1, 3, 5);
    }

    private String capturedLogs() {
        return errBuffer.toString(StandardCharsets.UTF_8);
    }

    private int extractMsgType(String msg) {
        return Integer.parseInt(msg.replaceAll(".*\\\"msgType\\\":(\\d+).*", "$1"));
    }

    private String extractTxnNo(String msg) {
        return msg.replaceAll(".*\\\"txnNo\\\":\\\"?(\\d{13})\\\"?.*", "$1");
    }

    private String loginAck(String txnNo) {
        return "{\"msgType\":111,\"devId\":\"TSD000001\",\"txnNo\":" + txnNo + "}";
    }

    @FunctionalInterface
    private interface TestServerHandler {
        void handle(ChannelHandlerContext ctx, MessageEvent event);
    }

    private record MessageEvent(String channelId, int msgType, String txnNo) {}
}
