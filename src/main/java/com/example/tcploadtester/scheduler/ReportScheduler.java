package com.example.tcploadtester.scheduler;

import com.example.tcploadtester.device.DeviceSession;
import io.netty.channel.EventLoop;

import java.util.concurrent.TimeUnit;

public final class ReportScheduler {

    private ReportScheduler() {
    }

    public static void start(DeviceSession session, EventLoop eventLoop, int reportIntervalSeconds, Runnable task) {
        stop(session);
        session.setReportTask(eventLoop.scheduleAtFixedRate(task, reportIntervalSeconds, reportIntervalSeconds, TimeUnit.SECONDS));
    }

    public static void stop(DeviceSession session) {
        if (session.reportTask() != null) {
            session.reportTask().cancel(false);
            session.setReportTask(null);
        }
    }
}
