package com.example.tcploadtester;

import com.example.tcploadtester.config.LoadTestConfig;
import com.example.tcploadtester.device.DeviceIdentityAllocator;
import com.example.tcploadtester.device.DeviceSession;
import com.example.tcploadtester.netty.DeviceChannelInitializer;
import com.example.tcploadtester.netty.DeviceMessageHandler;
import com.example.tcploadtester.netty.LoadTestClientBootstrap;
import io.netty.bootstrap.Bootstrap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public final class LoadTestApplication {

    private static final Logger log = LoggerFactory.getLogger(LoadTestApplication.class);

    private LoadTestApplication() {
    }

    public static void main(String[] args) {
        LoadTestConfig config = LoadTestConfig.load(args);
        LoadTestClientBootstrap bootstrapFactory = new LoadTestClientBootstrap();
        Runtime.getRuntime().addShutdownHook(new Thread(bootstrapFactory::shutdown));

        List<DeviceSession> sessions = new ArrayList<>();
        for (int i = 1; i <= config.deviceCount(); i++) {
            DeviceIdentityAllocator.DeviceIdentity identity = DeviceIdentityAllocator.allocate(i);
            DeviceSession session = new DeviceSession(i, identity.devId(), identity.imsi());
            sessions.add(session);

            Bootstrap bootstrap = bootstrapFactory.create(null);
            DeviceMessageHandler handler = new DeviceMessageHandler(session, config, bootstrap);
            bootstrap.handler(new DeviceChannelInitializer(handler, config.ackTimeoutSeconds() * 2));
            bootstrap.connect(config.host(), config.port());
        }

        log.info("started {} device sessions", sessions.size());
    }
}
