package com.example.tcploadtester.device;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class RedisDeviceCounter implements DeviceIdentityAllocator.DeviceCounter {

    private final String host;
    private final int port;
    private final String counterKey;

    public RedisDeviceCounter(String host, int port, String counterKey) {
        this.host = Objects.requireNonNull(host, "host must not be null");
        this.port = port;
        this.counterKey = Objects.requireNonNull(counterKey, "counterKey must not be null");
    }

    @Override
    public long nextDeviceIndex() {
        return executeLongCommand("INCR", counterKey);
    }

    @Override
    public void reset() {
        executeSimpleCommand("DEL", counterKey);
    }

    private long executeLongCommand(String command, String key) {
        try (Socket socket = new Socket(host, port);
             OutputStream output = new BufferedOutputStream(socket.getOutputStream());
             InputStream input = new BufferedInputStream(socket.getInputStream())) {
            writeCommand(output, command, key);
            return readIntegerReply(input);
        } catch (IOException e) {
            throw new IllegalStateException("Redis command failed: " + command, e);
        }
    }

    private void executeSimpleCommand(String command, String key) {
        try (Socket socket = new Socket(host, port);
             OutputStream output = new BufferedOutputStream(socket.getOutputStream());
             InputStream input = new BufferedInputStream(socket.getInputStream())) {
            writeCommand(output, command, key);
            skipReply(input);
        } catch (IOException e) {
            throw new IllegalStateException("Redis command failed: " + command, e);
        }
    }

    private void writeCommand(OutputStream output, String command, String key) throws IOException {
        writeBulkArrayHeader(output, 2);
        writeBulkString(output, command);
        writeBulkString(output, key);
        output.flush();
    }

    private void writeBulkArrayHeader(OutputStream output, int elements) throws IOException {
        output.write(("*" + elements + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private void writeBulkString(OutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.write(("$" + bytes.length + "\r\n").getBytes(StandardCharsets.UTF_8));
        output.write(bytes);
        output.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private long readIntegerReply(InputStream input) throws IOException {
        int prefix = input.read();
        if (prefix == ':') {
            return Long.parseLong(readLine(input));
        }
        if (prefix == '-') {
            throw new IllegalStateException("Redis error: " + readLine(input));
        }
        throw new IllegalStateException("Unexpected Redis reply: " + (char) prefix);
    }

    private void skipReply(InputStream input) throws IOException {
        int prefix = input.read();
        if (prefix == ':') {
            readLine(input);
            return;
        }
        if (prefix == '+') {
            readLine(input);
            return;
        }
        if (prefix == '-') {
            throw new IllegalStateException("Redis error: " + readLine(input));
        }
        throw new IllegalStateException("Unexpected Redis reply: " + (char) prefix);
    }

    private String readLine(InputStream input) throws IOException {
        StringBuilder builder = new StringBuilder();
        int current;
        while ((current = input.read()) != -1) {
            if (current == '\r') {
                int next = input.read();
                if (next != '\n') {
                    throw new IllegalStateException("Invalid Redis reply terminator");
                }
                return builder.toString();
            }
            builder.append((char) current);
        }
        throw new IllegalStateException("Unexpected end of Redis reply");
    }
}
