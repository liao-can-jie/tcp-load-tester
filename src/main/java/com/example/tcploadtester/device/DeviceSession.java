package com.example.tcploadtester.device;

import io.netty.channel.Channel;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class DeviceSession {

    public record PendingLogin(
            String txnNo,
            String payload,
            long firstSentAtMillis,
            int retryCount
    ) {
        public PendingLogin nextRetry() {
            return new PendingLogin(txnNo, payload, firstSentAtMillis, retryCount + 1);
        }
    }

    private final int deviceIndex;
    private final String devId;
    private final String imsi;
    private final AtomicBoolean loggedIn = new AtomicBoolean(false);
    private final AtomicReference<Channel> channelRef = new AtomicReference<>();
    private final AtomicReference<PendingLogin> pendingLoginRef = new AtomicReference<>();
    private final AtomicReference<ScheduledFuture<?>> loginRetryTaskRef = new AtomicReference<>();
    private final AtomicReference<ScheduledFuture<?>> reportTaskRef = new AtomicReference<>();
    private final AtomicReference<ScheduledFuture<?>> reconnectTaskRef = new AtomicReference<>();
    private final AtomicLong connectionGeneration = new AtomicLong(0);

    public DeviceSession(int deviceIndex, String devId, String imsi) {
        this.deviceIndex = deviceIndex;
        this.devId = devId;
        this.imsi = imsi;
    }

    public int deviceIndex() { return deviceIndex; }
    public String devId() { return devId; }
    public String imsi() { return imsi; }

    public boolean loggedIn() { return loggedIn.get(); }
    public void setLoggedIn(boolean value) { loggedIn.set(value); }

    public Channel channel() { return channelRef.get(); }
    public void setChannel(Channel channel) { channelRef.set(channel); }
    public void clearChannel(Channel channel) { channelRef.compareAndSet(channel, null); }

    public long nextConnectionGeneration() { return connectionGeneration.incrementAndGet(); }
    public long connectionGeneration() { return connectionGeneration.get(); }

    public PendingLogin pendingLogin() { return pendingLoginRef.get(); }
    public void setPendingLogin(PendingLogin pendingLogin) { pendingLoginRef.set(pendingLogin); }
    public void clearPendingLogin() { pendingLoginRef.set(null); }

    public ScheduledFuture<?> loginRetryTask() { return loginRetryTaskRef.get(); }
    public void setLoginRetryTask(ScheduledFuture<?> future) { loginRetryTaskRef.set(future); }

    public ScheduledFuture<?> reportTask() { return reportTaskRef.get(); }
    public void setReportTask(ScheduledFuture<?> future) { reportTaskRef.set(future); }

    public ScheduledFuture<?> reconnectTask() { return reconnectTaskRef.get(); }
    public void setReconnectTask(ScheduledFuture<?> future) { reconnectTaskRef.set(future); }
}
