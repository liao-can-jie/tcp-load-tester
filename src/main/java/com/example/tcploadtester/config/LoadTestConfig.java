package com.example.tcploadtester.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

public record LoadTestConfig(
        String host,
        int port,
        int deviceCount,
        int minReportIntervalSeconds,
        int maxReportIntervalSeconds,
        long reconnectDelayMillis,
        int ackTimeoutSeconds,
        int maxConsecutiveTimeouts
) {
    public static LoadTestConfig load(String[] args) {
        Properties properties = new Properties();
        try (InputStream inputStream = LoadTestConfig.class.getClassLoader().getResourceAsStream("application.properties")) {
            if (inputStream == null) {
                throw new IllegalStateException("application.properties not found");
            }
            properties.load(inputStream);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load application.properties", e);
        }

        Map<String, String> overrides = parseArgs(args);
        LoadTestConfig config = new LoadTestConfig(
                overrides.getOrDefault("host", properties.getProperty("host")),
                Integer.parseInt(overrides.getOrDefault("port", properties.getProperty("port"))),
                Integer.parseInt(overrides.getOrDefault("deviceCount", properties.getProperty("deviceCount"))),
                Integer.parseInt(overrides.getOrDefault("minReportIntervalSeconds", properties.getProperty("minReportIntervalSeconds"))),
                Integer.parseInt(overrides.getOrDefault("maxReportIntervalSeconds", properties.getProperty("maxReportIntervalSeconds"))),
                Long.parseLong(overrides.getOrDefault("reconnectDelayMillis", properties.getProperty("reconnectDelayMillis"))),
                Integer.parseInt(overrides.getOrDefault("ackTimeoutSeconds", properties.getProperty("ackTimeoutSeconds"))),
                Integer.parseInt(overrides.getOrDefault("maxConsecutiveTimeouts", properties.getProperty("maxConsecutiveTimeouts")))
        );
        config.validate();
        return config;
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> values = new HashMap<>();
        for (String arg : args) {
            if (!arg.startsWith("--") || !arg.contains("=")) {
                continue;
            }
            int separatorIndex = arg.indexOf('=');
            values.put(arg.substring(2, separatorIndex), arg.substring(separatorIndex + 1));
        }
        return values;
    }

    public void validate() {
        Objects.requireNonNull(host, "host must not be null");
        if (host.isBlank()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
        if (deviceCount <= 0 || deviceCount > 100000) {
            throw new IllegalArgumentException("deviceCount must be between 1 and 100000");
        }
     /*   if (reportIntervalSeconds <= 0) {
            throw new IllegalArgumentException("reportIntervalSeconds must be > 0");
        }*/
        if (minReportIntervalSeconds <= 0 || minReportIntervalSeconds > maxReportIntervalSeconds) {
            throw new IllegalArgumentException("minReportIntervalSeconds must be between 1 and maxReportIntervalSeconds");
        }
        if (reconnectDelayMillis < 0) {
            throw new IllegalArgumentException("reconnectDelayMillis must be >= 0");
        }
        if (ackTimeoutSeconds <= 0) {
            throw new IllegalArgumentException("ackTimeoutSeconds must be > 0");
        }
        if (maxConsecutiveTimeouts <= 0) {
            throw new IllegalArgumentException("maxConsecutiveTimeouts must be > 0");
        }
    }
}
