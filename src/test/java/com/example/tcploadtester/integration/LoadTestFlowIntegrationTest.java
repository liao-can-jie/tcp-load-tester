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
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LoadTestFlowIntegrationTest {

    private final EventLoopGroup bossGroup = new NioEventLoopGroup(1);
    private final EventLoopGroup workerGroup = new NioEventLoopGroup(1);
    private final EventLoopGroup clientGroup = new NioEventLoopGroup(1);
    private final List<String> receivedMessages = new CopyOnWriteArrayList<>();
    private ChannelFuture serverFuture;

    @AfterEach
    void tearDown() {
        if (serverFuture != null) {
            serverFuture.channel().close().syncUninterruptibly();
        }
        bossGroup.shutdownGracefully().syncUninterruptibly();
        workerGroup.shutdownGracefully().syncUninterruptibly();
        clientGroup.shutdownGracefully().syncUninterruptibly();
    }

    @Test
    void loginThenReportWithAckResponses() throws Exception {
        CountDownLatch loginLatch = new CountDownLatch(1);
        CountDownLatch reportLatch = new CountDownLatch(1);
        startFakeServer(loginLatch, reportLatch, false);

        DeviceIdentityAllocator.DeviceIdentity identity = DeviceIdentityAllocator.allocate(1);
        DeviceSession session = new DeviceSession(1, identity.devId(), identity.imsi());
        LoadTestConfig config = new LoadTestConfig("127.0.0.1", 19090, 1, 1, 50, 1, 3);

        Bootstrap bootstrap = new Bootstrap()
                .group(clientGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000);

        DeviceMessageHandler handler = new DeviceMessageHandler(session, config, bootstrap);
        bootstrap.handler(new DeviceChannelInitializer(handler));
        bootstrap.connect(config.host(), config.port()).sync();

        assertTrue(loginLatch.await(3, TimeUnit.SECONDS));
        assertTrue(reportLatch.await(3, TimeUnit.SECONDS));
        assertTrue(receivedMessages.stream().anyMatch(msg -> msg.contains("\"msgType\":110")));
        assertTrue(receivedMessages.stream().anyMatch(msg -> msg.contains("\"msgType\":310")));
    }

    @Test
    void reconnectAfterThreeLoginAckTimeouts() throws Exception {
        CountDownLatch fourthLoginLatch = new CountDownLatch(1);
        startTimeoutServer(fourthLoginLatch);

        DeviceIdentityAllocator.DeviceIdentity identity = DeviceIdentityAllocator.allocate(1);
        DeviceSession session = new DeviceSession(1, identity.devId(), identity.imsi());
        LoadTestConfig config = new LoadTestConfig("127.0.0.1", 19090, 1, 1, 50, 1, 3);

        Bootstrap bootstrap = new Bootstrap()
                .group(clientGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000);

        DeviceMessageHandler handler = new DeviceMessageHandler(session, config, bootstrap);
        bootstrap.handler(new DeviceChannelInitializer(handler));
        bootstrap.connect(config.host(), config.port()).sync();

        assertTrue(fourthLoginLatch.await(5, TimeUnit.SECONDS));
    }

    private void startFakeServer(CountDownLatch loginLatch, CountDownLatch reportLatch, boolean dropLoginAck) throws InterruptedException {
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
                                receivedMessages.add(msg);
                                if (msg.contains("\"msgType\":110")) {
                                    loginLatch.countDown();
                                    String txnNo = msg.replaceAll(".*\\\"txnNo\\\":(\\d{13}).*", "$1");
                                    if (!dropLoginAck) {
                                        ctx.writeAndFlush("{\"msgType\":111,\"devId\":\"TSD000001\",\"txnNo\":" + txnNo + "}");
                                    }
                                }
                                if (msg.contains("\"msgType\":310")) {
                                    reportLatch.countDown();
                                    String txnNo = msg.replaceAll(".*\\\"txnNo\\\":\\\"?(\\d{13})\\\"?.*", "$1");
                                    ctx.writeAndFlush("{\"msgType\":311,\"devId\":\"TSD000001\",\"txnNo\":" + txnNo + "}");
                                }
                            }
                        });
                    }
                })
                .bind(19090)
                .sync();
    }

    private void startTimeoutServer(CountDownLatch fourthLoginLatch) throws InterruptedException {
        AtomicInteger loginCount = new AtomicInteger();
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
                                receivedMessages.add(msg);
                                if (msg.contains("\"msgType\":110")) {
                                    int count = loginCount.incrementAndGet();
                                    if (count == 4) {
                                        fourthLoginLatch.countDown();
                                        String txnNo = msg.replaceAll(".*\\\"txnNo\\\":(\\d{13}).*", "$1");
                                        ctx.writeAndFlush("{\"msgType\":111,\"devId\":\"TSD000001\",\"txnNo\":" + txnNo + "}");
                                    }
                                }
                            }
                        });
                    }
                })
                .bind(19090)
                .sync();
    }
}
