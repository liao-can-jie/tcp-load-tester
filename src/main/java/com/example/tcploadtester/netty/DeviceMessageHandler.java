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
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public final class DeviceMessageHandler extends SimpleChannelInboundHandler<String> {

    private static final Logger log = LoggerFactory.getLogger(DeviceMessageHandler.class);

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
                log.debug("devId={} login success, report interval={}s", session.devId(), i);
                ReportScheduler.start(session, ctx.channel().eventLoop()
                        , i
                        , () -> trySendReport(ctx.channel()));
            }
        }
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt instanceof IdleStateEvent) {
            IdleStateEvent e = (IdleStateEvent) evt;
            if (e.state() == IdleState.READER_IDLE) {
                log.warn("devId={} read idle timeout, closing channel", session.devId());
                ctx.close();
            }
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        log.info("devId={} channel inactive, cleaning up", session.devId());
        cleanupBeforeReconnect();
        ReconnectManager.schedule(session, ctx.channel().eventLoop(), config.reconnectDelayMillis(), this::reconnect);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("devId={} exception caught: {}", session.devId(), cause.getMessage(), cause);
        ctx.close();
    }

    private void sendLogin(Channel channel) {
        long txnNo = System.currentTimeMillis();
        String payload = PayloadBuilder.buildLoginPayload(session.devId(), session.imsi(), txnNo);
        session.setAwaitingAck(111, String.valueOf(txnNo));
        channel.writeAndFlush(payload).addListener(future -> {
            if (!future.isSuccess()) {
                log.warn("devId={} login write failed: {}", session.devId(), future.cause() != null ? future.cause().getMessage() : "unknown");
                ReportScheduler.stop(session);
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
                log.warn("devId={} report write failed: {}", session.devId(), future.cause() != null ? future.cause().getMessage() : "unknown");
                ReportScheduler.stop(session);
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
        session.setAckTimeoutTask(channel.eventLoop().schedule(() -> onAckTimeout(channel, loginStage), config.ackTimeoutSeconds(), java.util.concurrent.TimeUnit.SECONDS));
    }

    private void onAckTimeout(Channel channel, boolean loginStage) {
        session.setAckTimeoutTask(null);
        session.clearAwaitingAck();
        int timeoutCount = session.incrementTimeouts();
        log.warn("devId={} ack timeout (count={}/{}), loginStage={}", session.devId(), timeoutCount, config.maxConsecutiveTimeouts(), loginStage);
        if (timeoutCount >= config.maxConsecutiveTimeouts()) {
            log.warn("devId={} max consecutive timeouts reached, closing", session.devId());
            ReportScheduler.stop(session);
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
            reconnectBootstrap.handler(new DeviceChannelInitializer(new DeviceMessageHandler(session, config, reconnectBootstrap), config.ackTimeoutSeconds() * 2));
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
