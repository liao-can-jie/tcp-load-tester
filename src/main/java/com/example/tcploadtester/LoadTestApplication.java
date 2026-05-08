package com.example.tcploadtester;

import com.example.tcploadtester.config.LoadTestConfig;
import com.example.tcploadtester.device.DeviceIdentityAllocator;
import com.example.tcploadtester.device.DeviceSession;
import com.example.tcploadtester.netty.ConnectionStats;
import com.example.tcploadtester.netty.DeviceChannelInitializer;
import com.example.tcploadtester.netty.DeviceMessageHandler;
import com.example.tcploadtester.netty.LoadTestClientBootstrap;
import com.example.tcploadtester.netty.ReconnectManager;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.EventLoop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class LoadTestApplication {

    private static final Logger log = LoggerFactory.getLogger(LoadTestApplication.class);
    private static final URI TENCENT_PUBLIC_IP_ENDPOINT = URI.create("http://metadata.tencentyun.com/latest/meta-data/public-ipv4");

    private LoadTestApplication() {
    }

    public static void main(String[] args) {
        LoadTestConfig config = LoadTestConfig.load(args);
        LoadTestClientBootstrap bootstrapFactory = new LoadTestClientBootstrap();
        DeviceIdentityAllocator allocator = new DeviceIdentityAllocator(resolvePublicIp());

        List<DeviceSession> sessions = new ArrayList<>();
        for (int i = 1; i <= config.deviceCount(); i++) {
            DeviceIdentityAllocator.DeviceIdentity identity = allocator.allocate(i);
            DeviceSession session = new DeviceSession(i, identity.devId(), identity.imsi());
            sessions.add(session);

            Bootstrap bootstrap = bootstrapFactory.create();
            DeviceMessageHandler handler = new DeviceMessageHandler(session, config, bootstrap);
            bootstrap.handler(new DeviceChannelInitializer(handler, config.readerIdleSeconds()));
            bootstrap.connect(config.host(), config.port()).addListener(future -> {
                if (!future.isSuccess()) {
                    log.warn("devId={} connect failed: {}, scheduling reconnect", session.devId(),
                            future.cause() != null ? future.cause().getMessage() : "unknown");
                    EventLoop eventLoop = bootstrap.config().group().next();
                    ReconnectManager.schedule(session, eventLoop, config.reconnectDelayMillis(), handler::reconnect);
                }
            });
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                log.error("sleep interrupted", e);
                Thread.currentThread().interrupt();
            }
        }

        log.info("started {} device sessions", sessions.size());

        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "stats-reporter");
            t.setDaemon(true);
            return t;
        }).scheduleAtFixedRate(() -> log.info(ConnectionStats.snapshot()), 10, 10, TimeUnit.SECONDS);
    }

    private static String resolvePublicIp() {
        try {
            HttpRequest request = HttpRequest.newBuilder(TENCENT_PUBLIC_IP_ENDPOINT)
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Failed to resolve public IP: HTTP " + response.statusCode());
            }
            String publicIp = response.body().trim();
            if (!publicIp.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
                throw new IllegalStateException("Failed to resolve public IP: " + publicIp);
            }
            return publicIp;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to resolve public IP from Tencent metadata", e);
        }
    }
}
