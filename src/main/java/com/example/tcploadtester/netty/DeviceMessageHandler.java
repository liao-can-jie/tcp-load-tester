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
import java.util.concurrent.TimeUnit;

public final class DeviceMessageHandler extends SimpleChannelInboundHandler<String> {

    private static final Logger log = LoggerFactory.getLogger(DeviceMessageHandler.class);

    private final DeviceSession session;
    private final LoadTestConfig config;
    private final Bootstrap bootstrap;
    private final StringBuilder inboundBuffer = new StringBuilder();
    private long connectionGeneration;

    public DeviceMessageHandler(DeviceSession session, LoadTestConfig config, Bootstrap bootstrap) {
        this.session = session;
        this.config = config;
        this.bootstrap = bootstrap;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        connectionGeneration = session.nextConnectionGeneration();
        session.setChannel(ctx.channel());
        session.setReconnectTask(null);
        session.setLoggedIn(false);
        cancelLoginRetryTask();
        session.clearPendingLogin();
        ReportScheduler.stop(session);
        ConnectionStats.onConnected();
        log.debug("devId={} channel active gen={}, sending login 110", session.devId(), connectionGeneration);
        sendLogin(ctx.channel());
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, String msg) {
        log.debug("devId={} received: {}", session.devId(), msg);
        inboundBuffer.append(msg);
        List<AckMessageMatcher.AckMessage> ackMessages = AckMessageMatcher.extract(inboundBuffer.toString());
        if (!ackMessages.isEmpty()) {
            inboundBuffer.setLength(0);
        }
        for (AckMessageMatcher.AckMessage ackMessage : ackMessages) {
            DeviceSession.PendingLogin pending = session.pendingLogin();
            if (pending == null) {
                log.debug("devId={} received ack msgType={} txnNo={} but no pending login", session.devId(), ackMessage.msgType(), ackMessage.txnNo());
                continue;
            }
            if (ackMessage.msgType() != 111 || !AckMessageMatcher.matches(ackMessage, 111, pending.txnNo())) {
                log.debug("devId={} received ack msgType={} txnNo={} expected msgType=111 txnNo={}", session.devId(), ackMessage.msgType(), ackMessage.txnNo(), pending.txnNo());
                continue;
            }
            cancelLoginRetryTask();
            session.clearPendingLogin();
            session.setLoggedIn(true);
            ConnectionStats.onLoginSuccess();
            log.debug("devId={} login success txnNo={}", session.devId(), pending.txnNo());
            startPeriodicReport(ctx.channel());
        }
        if (inboundBuffer.length() > 8192) {
            log.warn("devId={} inbound buffer overflow ({} chars), clearing", session.devId(), inboundBuffer.length());
            inboundBuffer.setLength(0);
        }
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt instanceof IdleStateEvent idleStateEvent && idleStateEvent.state() == IdleState.READER_IDLE) {
            log.warn("devId={} read idle timeout, closing channel", session.devId());
            ctx.close();
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        log.debug("devId={} channel inactive, scheduling reconnect in {}ms", session.devId(), config.reconnectDelayMillis());
        cleanupBeforeReconnect(ctx.channel());
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
        DeviceSession.PendingLogin pending = new DeviceSession.PendingLogin(
                String.valueOf(txnNo), payload, System.currentTimeMillis(), 0
        );
        session.setPendingLogin(pending);
        log.debug("devId={} sending login 110 txnNo={}", session.devId(), txnNo);
        channel.writeAndFlush(payload).addListener(future -> {
            if (!future.isSuccess()) {
                log.warn("devId={} login write failed, closing channel: {}", session.devId(),
                        future.cause() != null ? future.cause().getMessage() : "unknown");
                channel.close();
                return;
            }
            log.debug("devId={} login 110 sent, scheduling retry in {}s", session.devId(), config.loginRetryIntervalSeconds());
            scheduleLoginRetry(channel, pending);
        });
    }

    private void scheduleLoginRetry(Channel channel, DeviceSession.PendingLogin pending) {
        cancelLoginRetryTask();
        session.setLoginRetryTask(channel.eventLoop().schedule(
                () -> onLoginRetry(channel, pending), config.loginRetryIntervalSeconds(), TimeUnit.SECONDS));
    }

    private void onLoginRetry(Channel channel, DeviceSession.PendingLogin scheduled) {
        session.setLoginRetryTask(null);
        DeviceSession.PendingLogin current = session.pendingLogin();
        if (current == null || !current.txnNo().equals(scheduled.txnNo())) {
            log.debug("devId={} login retry skipped: pending cleared or txnNo changed", session.devId());
            return;
        }
        if (!channel.isActive() || connectionGeneration != session.connectionGeneration()) {
            log.debug("devId={} login retry skipped: channel inactive or generation mismatch", session.devId());
            return;
        }
        long elapsedMillis = System.currentTimeMillis() - current.firstSentAtMillis();
        if (elapsedMillis >= TimeUnit.SECONDS.toMillis(config.loginRetryWindowSeconds())) {
            log.warn("devId={} login retry window exceeded ({}s), closing channel", session.devId(), config.loginRetryWindowSeconds());
            channel.close();
            return;
        }
        DeviceSession.PendingLogin retry = current.nextRetry();
        session.setPendingLogin(retry);
        log.warn("devId={} missing login ack txnNo={}, retry #{}", session.devId(), retry.txnNo(), retry.retryCount());
        channel.writeAndFlush(retry.payload()).addListener(future -> {
            if (!future.isSuccess()) {
                log.warn("devId={} login retry write failed, closing channel: {}", session.devId(),
                        future.cause() != null ? future.cause().getMessage() : "unknown");
                channel.close();
                return;
            }
            scheduleLoginRetry(channel, retry);
        });
    }

    private void startPeriodicReport(Channel channel) {
        int interval = ThreadLocalRandom.current().nextInt(
                config.minReportIntervalSeconds(), config.maxReportIntervalSeconds() + 1);
        log.debug("devId={} starting periodic 310 reports every {}s", session.devId(), interval);
        ReportScheduler.start(session, channel.eventLoop(), interval, () -> {
            if (!channel.isActive() || !session.loggedIn()) {
                return;
            }
            long txnNo = System.currentTimeMillis();
            String payload = PayloadBuilder.buildAttributePayload(session.devId(), txnNo);
            channel.writeAndFlush(payload).addListener(future -> {
                if (!future.isSuccess()) {
                    log.warn("devId={} report write failed, closing channel: {}", session.devId(),
                            future.cause() != null ? future.cause().getMessage() : "unknown");
                    channel.close();
                }
            });
        });
    }

    private void reconnect() {
        if (bootstrap != null) {
            log.info("devId={} reconnecting...", session.devId());
            Bootstrap reconnectBootstrap = bootstrap.clone();
            reconnectBootstrap.handler(new DeviceChannelInitializer(
                    new DeviceMessageHandler(session, config, reconnectBootstrap), config.readerIdleSeconds()));
            reconnectBootstrap.connect(config.host(), config.port());
        }
    }

    private void cleanupBeforeReconnect(Channel channel) {
        session.setLoggedIn(false);
        session.clearChannel(channel);
        cancelLoginRetryTask();
        session.clearPendingLogin();
        ReportScheduler.stop(session);
    }

    private void cancelLoginRetryTask() {
        if (session.loginRetryTask() != null) {
            session.loginRetryTask().cancel(false);
            session.setLoginRetryTask(null);
        }
    }
}
