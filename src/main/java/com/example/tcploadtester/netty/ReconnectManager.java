package com.example.tcploadtester.netty;

import com.example.tcploadtester.device.DeviceSession;
import io.netty.channel.EventLoop;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class ReconnectManager {

    private ReconnectManager() {
    }

    public static void schedule(DeviceSession session, EventLoop eventLoop, long reconnectDelayMillis, Runnable reconnectAction) {
        ScheduledFuture<?> existing = session.reconnectTask();
        if (existing != null && !existing.isDone()) {
            return;
        }
        ScheduledFuture<?> future = eventLoop.schedule(() -> {
            session.setReconnectTask(null);
            reconnectAction.run();
        }, reconnectDelayMillis, TimeUnit.MILLISECONDS);
        session.setReconnectTask(future);
    }
}
