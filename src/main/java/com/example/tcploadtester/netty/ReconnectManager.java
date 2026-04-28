package com.example.tcploadtester.netty;

import com.example.tcploadtester.device.DeviceSession;
import io.netty.channel.EventLoop;

import java.util.concurrent.TimeUnit;

public final class ReconnectManager {

    private ReconnectManager() {
    }

    public static void schedule(DeviceSession session, EventLoop eventLoop, long reconnectDelayMillis, Runnable reconnectAction) {
        eventLoop.schedule(reconnectAction, reconnectDelayMillis, TimeUnit.MILLISECONDS);
    }
}
