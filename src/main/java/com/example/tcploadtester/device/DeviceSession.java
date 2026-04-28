package com.example.tcploadtester.device;

import io.netty.channel.Channel;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class DeviceSession {

    private final int deviceIndex;
    private final String devId;
    private final String imsi;
    private final AtomicBoolean loggedIn = new AtomicBoolean(false);
    private final AtomicInteger consecutiveTimeouts = new AtomicInteger(0);
    private final AtomicReference<Channel> channelRef = new AtomicReference<>();
    private final AtomicReference<Integer> awaitingAckMsgType = new AtomicReference<>();
    private final AtomicReference<String> awaitingAckTxnNo = new AtomicReference<>();
    private final AtomicReference<ScheduledFuture<?>> reportTaskRef = new AtomicReference<>();
    private final AtomicReference<ScheduledFuture<?>> ackTimeoutTaskRef = new AtomicReference<>();

    public DeviceSession(int deviceIndex, String devId, String imsi) {
        this.deviceIndex = deviceIndex;
        this.devId = devId;
        this.imsi = imsi;
    }

    public int deviceIndex() {
        return deviceIndex;
    }

    public String devId() {
        return devId;
    }

    public String imsi() {
        return imsi;
    }

    public boolean loggedIn() {
        return loggedIn.get();
    }

    public void setLoggedIn(boolean value) {
        loggedIn.set(value);
    }

    public int consecutiveTimeouts() {
        return consecutiveTimeouts.get();
    }

    public int incrementTimeouts() {
        return consecutiveTimeouts.incrementAndGet();
    }

    public void resetTimeouts() {
        consecutiveTimeouts.set(0);
    }

    public Channel channel() {
        return channelRef.get();
    }

    public void setChannel(Channel channel) {
        channelRef.set(channel);
    }

    public Integer awaitingAckMsgType() {
        return awaitingAckMsgType.get();
    }

    public String awaitingAckTxnNo() {
        return awaitingAckTxnNo.get();
    }

    public boolean isAwaitingAck() {
        return awaitingAckMsgType.get() != null && awaitingAckTxnNo.get() != null;
    }

    public void setAwaitingAck(int msgType, String txnNo) {
        awaitingAckMsgType.set(msgType);
        awaitingAckTxnNo.set(txnNo);
    }

    public void clearAwaitingAck() {
        awaitingAckMsgType.set(null);
        awaitingAckTxnNo.set(null);
    }

    public ScheduledFuture<?> reportTask() {
        return reportTaskRef.get();
    }

    public void setReportTask(ScheduledFuture<?> future) {
        reportTaskRef.set(future);
    }

    public ScheduledFuture<?> ackTimeoutTask() {
        return ackTimeoutTaskRef.get();
    }

    public void setAckTimeoutTask(ScheduledFuture<?> future) {
        ackTimeoutTaskRef.set(future);
    }
}
