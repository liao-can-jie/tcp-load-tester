package com.example.tcploadtester.config;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

public record LoadTestConfig(
        String host,
        int port,
        String redisHost,
        int redisPort,
        String redisCounterKey,
        int deviceCount,
        int minReportIntervalSeconds,
        int maxReportIntervalSeconds,
        long reconnectDelayMillis,
        int loginRetryIntervalSeconds,
        int loginRetryWindowSeconds,
        int readerIdleSeconds
) {
    public static LoadTestConfig load(String[] args) {
        Map<String, String> overrides = parseArgs(args);
        String configPath = overrides.get("config");

        Properties properties = new Properties();
        try (InputStream inputStream = resolveConfigStream(configPath)) {
            properties.load(inputStream);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load application.properties", e);
        }

        LoadTestConfig config = new LoadTestConfig(
                overrides.getOrDefault("host", properties.getProperty("host")),
                Integer.parseInt(overrides.getOrDefault("port", properties.getProperty("port"))),
                overrides.getOrDefault("redisHost", properties.getProperty("redisHost")),
                Integer.parseInt(overrides.getOrDefault("redisPort", properties.getProperty("redisPort"))),
                overrides.getOrDefault("redisCounterKey", properties.getProperty("redisCounterKey")),
                Integer.parseInt(overrides.getOrDefault("deviceCount", properties.getProperty("deviceCount"))),
                Integer.parseInt(overrides.getOrDefault("minReportIntervalSeconds", properties.getProperty("minReportIntervalSeconds"))),
                Integer.parseInt(overrides.getOrDefault("maxReportIntervalSeconds", properties.getProperty("maxReportIntervalSeconds"))),
                Long.parseLong(overrides.getOrDefault("reconnectDelayMillis", properties.getProperty("reconnectDelayMillis"))),
                Integer.parseInt(overrides.getOrDefault("loginRetryIntervalSeconds", properties.getProperty("loginRetryIntervalSeconds"))),
                Integer.parseInt(overrides.getOrDefault("loginRetryWindowSeconds", properties.getProperty("loginRetryWindowSeconds"))),
                Integer.parseInt(overrides.getOrDefault("readerIdleSeconds", properties.getProperty("readerIdleSeconds")))
        );
        config.validate();
        return config;
    }

    private static InputStream resolveConfigStream(String configPath) throws IOException {
        if (configPath != null) {
            File f = new File(configPath);
            if (!f.isFile()) {
                throw new IOException("Config file not found: " + configPath);
            }
            return new FileInputStream(f);
        }

        Path jarDir = getJarDir();
        if (jarDir != null) {
            File inConfigDir = jarDir.resolve("config/application.properties").toFile();
            if (inConfigDir.isFile()) {
                return new FileInputStream(inConfigDir);
            }
            File inJarDir = jarDir.resolve("application.properties").toFile();
            if (inJarDir.isFile()) {
                return new FileInputStream(inJarDir);
            }
        }

        InputStream cpStream = LoadTestConfig.class.getClassLoader().getResourceAsStream("application.properties");
        if (cpStream != null) {
            return cpStream;
        }
        throw new IOException("application.properties not found (tried external and classpath)");
    }

    private static Path getJarDir() {
        try {
            var codeSource = LoadTestConfig.class.getProtectionDomain().getCodeSource();
            if (codeSource != null && codeSource.getLocation() != null) {
                File jarFile = new File(codeSource.getLocation().toURI());
                if (jarFile.isFile()) {
                    return jarFile.getParentFile().toPath();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
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
        Objects.requireNonNull(redisHost, "redisHost must not be null");
        Objects.requireNonNull(redisCounterKey, "redisCounterKey must not be null");
        if (host.isBlank()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
        if (redisHost.isBlank()) {
            throw new IllegalArgumentException("redisHost must not be blank");
        }
        if (redisPort <= 0 || redisPort > 65535) {
            throw new IllegalArgumentException("redisPort must be between 1 and 65535");
        }
        if (redisCounterKey.isBlank()) {
            throw new IllegalArgumentException("redisCounterKey must not be blank");
        }
        if (deviceCount <= 0 || deviceCount > 100000) {
            throw new IllegalArgumentException("deviceCount must be between 1 and 100000");
        }
        if (minReportIntervalSeconds <= 0 || minReportIntervalSeconds > maxReportIntervalSeconds) {
            throw new IllegalArgumentException("minReportIntervalSeconds must be between 1 and maxReportIntervalSeconds");
        }
        if (reconnectDelayMillis < 0) {
            throw new IllegalArgumentException("reconnectDelayMillis must be >= 0");
        }
        if (loginRetryIntervalSeconds <= 0) {
            throw new IllegalArgumentException("loginRetryIntervalSeconds must be > 0");
        }
        if (loginRetryWindowSeconds < loginRetryIntervalSeconds) {
            throw new IllegalArgumentException("loginRetryWindowSeconds must be >= loginRetryIntervalSeconds");
        }
        if (readerIdleSeconds <= loginRetryWindowSeconds) {
            throw new IllegalArgumentException("readerIdleSeconds must be greater than loginRetryWindowSeconds");
        }
    }
}
