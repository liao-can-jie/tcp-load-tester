package com.example.tcploadtester.netty;

import com.example.tcploadtester.config.LoadTestConfig;
import com.example.tcploadtester.device.DeviceSession;
import com.example.tcploadtester.matcher.AckMessageMatcher;
import com.example.tcploadtester.payload.PayloadBuilder;
import com.example.tcploadtester.scheduler.ReportScheduler;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
//import io.netty.util.internal.ThreadLocalRandom;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

public final class DeviceMessageHandler extends SimpleChannelInboundHandler<String> {

    private final DeviceSession session;
    private final LoadTestConfig config;
    private final Bootstrap bootstrap;
    private final StringBuilder inboundBuffer = new StringBuilder();

    public DeviceMessageHandler(DeviceSession session, LoadTestConfig config, Bootstrap bootstrap) {
        this.session = session;
        this.config = config;
        this.bootstrap = bootstrap;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        session.setChannel(ctx.channel());
        session.setLoggedIn(false);
        session.clearAwaitingAck();
        sendLogin(ctx.channel());
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, String msg) {
        inboundBuffer.append(msg);
        List<AckMessageMatcher.AckMessage> ackMessages = AckMessageMatcher.extract(inboundBuffer.toString());
        if (!ackMessages.isEmpty()) {
            inboundBuffer.setLength(0);
        }
        for (AckMessageMatcher.AckMessage ackMessage : ackMessages) {
            Integer expectedMsgType = session.awaitingAckMsgType();
            String expectedTxnNo = session.awaitingAckTxnNo();
            if (expectedMsgType == null || expectedTxnNo == null) {
                continue;
            }
            if (!AckMessageMatcher.matches(ackMessage, expectedMsgType, expectedTxnNo)) {
                continue;
            }
            if (session.ackTimeoutTask() != null) {
                session.ackTimeoutTask().cancel(false);
                session.setAckTimeoutTask(null);
            }
            session.clearAwaitingAck();
            session.resetTimeouts();
            if (ackMessage.msgType() == 111) {
                session.setLoggedIn(true);
                int i = ThreadLocalRandom.current().nextInt(config.minReportIntervalSeconds(), config.maxReportIntervalSeconds() + 1);
                System.out.println(i);
                ReportScheduler.start(session, ctx.channel().eventLoop()
                        , i
                        , () -> trySendReport(ctx.channel()));
            }
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        cleanupBeforeReconnect();
        ReconnectManager.schedule(session, ctx.channel().eventLoop(), config.reconnectDelayMillis(), this::reconnect);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        ctx.close();
    }

    private void sendLogin(Channel channel) {
        long txnNo = System.currentTimeMillis();
        String payload = PayloadBuilder.buildLoginPayload(session.devId(), session.imsi(), txnNo);
        session.setAwaitingAck(111, String.valueOf(txnNo));
        channel.writeAndFlush(payload).addListener(future -> {
            if (!future.isSuccess()) {
                channel.close();
                return;
            }
            scheduleAckTimeout(channel, true);
        });
    }

    private void trySendReport(Channel channel) {
        if (!session.loggedIn() || session.isAwaitingAck()) {
            return;
        }
        long txnNo = System.currentTimeMillis();
        String payload = PayloadBuilder.buildAttributePayload(session.devId(), txnNo);
        session.setAwaitingAck(311, String.valueOf(txnNo));
        channel.writeAndFlush(payload).addListener(future -> {
            if (!future.isSuccess()) {
                channel.close();
                return;
            }
            scheduleAckTimeout(channel, false);
        });
    }

    private void scheduleAckTimeout(Channel channel, boolean loginStage) {
        if (session.ackTimeoutTask() != null) {
            session.ackTimeoutTask().cancel(false);
        }
        session.setAckTimeoutTask(channel.eventLoop().schedule(() -> onAckTimeout(channel, loginStage), config.ackTimeoutSeconds(), TimeUnit.SECONDS));
    }

    private void onAckTimeout(Channel channel, boolean loginStage) {
        session.setAckTimeoutTask(null);
        session.clearAwaitingAck();
        int timeoutCount = session.incrementTimeouts();
        if (timeoutCount >= config.maxConsecutiveTimeouts()) {
            channel.close();
            return;
        }
        if (loginStage && channel.isActive()) {
            sendLogin(channel);
        }
    }

    private void reconnect() {
        if (bootstrap != null) {
            Bootstrap reconnectBootstrap = bootstrap.clone();
            reconnectBootstrap.handler(new DeviceChannelInitializer(new DeviceMessageHandler(session, config, reconnectBootstrap)));
            reconnectBootstrap.connect(config.host(), config.port());
        }
    }

    private void cleanupBeforeReconnect() {
        session.setLoggedIn(false);
        session.clearAwaitingAck();
        if (session.ackTimeoutTask() != null) {
            session.ackTimeoutTask().cancel(false);
            session.setAckTimeoutTask(null);
        }
        ReportScheduler.stop(session);
    }
}
