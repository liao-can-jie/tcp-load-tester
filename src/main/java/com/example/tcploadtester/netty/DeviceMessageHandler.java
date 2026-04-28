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
    private static final int LOGIN_MSG_TYPE = 110;
    private static final int LOGIN_ACK_MSG_TYPE = 111;
    private static final int REPORT_MSG_TYPE = 310;
    private static final int REPORT_ACK_MSG_TYPE = 311;

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
        long connectionGeneration = session.nextConnectionGeneration();
        session.setChannel(ctx.channel());
        session.setReconnectTask(null);
        session.setLoggedIn(false);
        cancelAckRetryTask();
        session.clearPendingExchange();
        sendLogin(ctx.channel(), connectionGeneration);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, String msg) {
        inboundBuffer.append(msg);
        List<AckMessageMatcher.AckMessage> ackMessages = AckMessageMatcher.extract(inboundBuffer.toString());
        if (!ackMessages.isEmpty()) {
            inboundBuffer.setLength(0);
        }
        for (AckMessageMatcher.AckMessage ackMessage : ackMessages) {
            DeviceSession.PendingExchange pending = session.pendingExchange();
            if (pending == null) {
                continue;
            }
            if (!AckMessageMatcher.matches(ackMessage, pending.expectedAckMsgType(), pending.txnNo())) {
                continue;
            }
            cancelAckRetryTask();
            session.clearPendingExchange();
            if (pending.expectedAckMsgType() == LOGIN_ACK_MSG_TYPE) {
                session.setLoggedIn(true);
                int interval = ThreadLocalRandom.current().nextInt(config.minReportIntervalSeconds(), config.maxReportIntervalSeconds() + 1);
                log.debug("devId={} login success, report interval={}s", session.devId(), interval);
                ReportScheduler.start(session, ctx.channel().eventLoop(), interval, () -> trySendReport(ctx.channel(), session.connectionGeneration()));
                continue;
            }
            log.debug("devId={} report ack received txnNo={}", session.devId(), pending.txnNo());
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
        log.info("devId={} channel inactive, cleaning up", session.devId());
        cleanupBeforeReconnect(ctx.channel());
        ReconnectManager.schedule(session, ctx.channel().eventLoop(), config.reconnectDelayMillis(), this::reconnect);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("devId={} exception caught: {}", session.devId(), cause.getMessage(), cause);
        ctx.close();
    }

    private void sendLogin(Channel channel, long connectionGeneration) {
        long txnNo = System.currentTimeMillis();
        String payload = PayloadBuilder.buildLoginPayload(session.devId(), session.imsi(), txnNo);
        writePendingExchange(channel, new DeviceSession.PendingExchange(
                LOGIN_MSG_TYPE,
                LOGIN_ACK_MSG_TYPE,
                String.valueOf(txnNo),
                payload,
                System.currentTimeMillis(),
                0,
                connectionGeneration
        ));
    }

    private void trySendReport(Channel channel, long connectionGeneration) {
        if (!session.loggedIn() || session.isAwaitingAck()) {
            return;
        }
        long txnNo = System.currentTimeMillis();
        String payload = PayloadBuilder.buildAttributePayload(session.devId(), txnNo);
        writePendingExchange(channel, new DeviceSession.PendingExchange(
                REPORT_MSG_TYPE,
                REPORT_ACK_MSG_TYPE,
                String.valueOf(txnNo),
                payload,
                System.currentTimeMillis(),
                0,
                connectionGeneration
        ));
    }

    private void writePendingExchange(Channel channel, DeviceSession.PendingExchange pendingExchange) {
        session.setPendingExchange(pendingExchange);
        channel.writeAndFlush(pendingExchange.payload()).addListener(future -> {
            if (!future.isSuccess()) {
                log.warn("devId={} write failed msgType={} txnNo={} cause={}",
                        session.devId(),
                        pendingExchange.requestMsgType(),
                        pendingExchange.txnNo(),
                        future.cause() != null ? future.cause().getMessage() : "unknown");
                ReportScheduler.stop(session);
                cancelAckRetryTask();
                session.clearPendingExchange();
                channel.close();
                return;
            }
            scheduleAckRetry(channel, pendingExchange);
        });
    }

    private void scheduleAckRetry(Channel channel, DeviceSession.PendingExchange pendingExchange) {
        cancelAckRetryTask();
        session.setAckRetryTask(channel.eventLoop().schedule(() -> onAckRetry(channel, pendingExchange), config.ackRetryIntervalSeconds(), TimeUnit.SECONDS));
    }

    private void onAckRetry(Channel channel, DeviceSession.PendingExchange scheduledPendingExchange) {
        session.setAckRetryTask(null);
        DeviceSession.PendingExchange currentPendingExchange = session.pendingExchange();
        if (currentPendingExchange == null) {
            return;
        }
        if (!currentPendingExchange.txnNo().equals(scheduledPendingExchange.txnNo())
                || currentPendingExchange.expectedAckMsgType() != scheduledPendingExchange.expectedAckMsgType()) {
            return;
        }
        if (!channel.isActive() || currentPendingExchange.connectionGeneration() != session.connectionGeneration()) {
            return;
        }
        long elapsedMillis = System.currentTimeMillis() - currentPendingExchange.firstSentAtMillis();
        if (elapsedMillis >= TimeUnit.SECONDS.toMillis(config.ackRetryWindowSeconds())) {
            log.warn("devId={} ack retry window exceeded msgType={} txnNo={}, closing channel",
                    session.devId(), currentPendingExchange.requestMsgType(), currentPendingExchange.txnNo());
            ReportScheduler.stop(session);
            channel.close();
            return;
        }
        DeviceSession.PendingExchange retryPendingExchange = currentPendingExchange.nextRetry();
        session.setPendingExchange(retryPendingExchange);
        log.warn("devId={} missing ack msgType={} txnNo={}, retry #{}",
                session.devId(), retryPendingExchange.requestMsgType(), retryPendingExchange.txnNo(), retryPendingExchange.retryCount());
        channel.writeAndFlush(retryPendingExchange.payload()).addListener(future -> {
            if (!future.isSuccess()) {
                log.warn("devId={} retry write failed msgType={} txnNo={} cause={}",
                        session.devId(),
                        retryPendingExchange.requestMsgType(),
                        retryPendingExchange.txnNo(),
                        future.cause() != null ? future.cause().getMessage() : "unknown");
                ReportScheduler.stop(session);
                cancelAckRetryTask();
                session.clearPendingExchange();
                channel.close();
                return;
            }
            scheduleAckRetry(channel, retryPendingExchange);
        });
    }

    private void reconnect() {
        if (bootstrap != null) {
            Bootstrap reconnectBootstrap = bootstrap.clone();
            reconnectBootstrap.handler(new DeviceChannelInitializer(new DeviceMessageHandler(session, config, reconnectBootstrap), config.readerIdleSeconds()));
            reconnectBootstrap.connect(config.host(), config.port());
        }
    }

    private void cleanupBeforeReconnect(Channel channel) {
        session.setLoggedIn(false);
        session.clearChannel(channel);
        cancelAckRetryTask();
        session.clearPendingExchange();
        ReportScheduler.stop(session);
    }

    private void cancelAckRetryTask() {
        if (session.ackRetryTask() != null) {
            session.ackRetryTask().cancel(false);
            session.setAckRetryTask(null);
        }
    }
}
