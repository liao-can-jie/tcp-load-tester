package com.example.tcploadtester.netty;

import java.util.concurrent.atomic.AtomicInteger;

public final class ConnectionStats {

    private static final AtomicInteger connected = new AtomicInteger(0);
    private static final AtomicInteger loginSuccess = new AtomicInteger(0);

    private ConnectionStats() {
    }

    public static void onConnected() {
        connected.incrementAndGet();
    }

    public static void onLoginSuccess() {
        loginSuccess.incrementAndGet();
    }

    public static int connected() {
        return connected.get();
    }

    public static int loginSuccess() {
        return loginSuccess.get();
    }

    public static String snapshot() {
        return String.format("[stats] connected=%d, loginSuccess=%d, gap=%d",
                connected.get(), loginSuccess.get(), connected.get() - loginSuccess.get());
    }
}
