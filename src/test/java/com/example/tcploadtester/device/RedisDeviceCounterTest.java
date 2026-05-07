package com.example.tcploadtester.device;

import org.junit.jupiter.api.Test;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RedisDeviceCounterTest {

    @Test
    void nextDeviceIndexSendsIncrCommandAndParsesIntegerReply() throws Exception {
        try (FakeRedisServer server = new FakeRedisServer()) {
            Future<List<CapturedCommand>> commandFuture = server.handleOnce(List.of(":41\r\n"));
            RedisDeviceCounter counter = new RedisDeviceCounter("127.0.0.1", server.port(), "", "device-counter");

            assertEquals(41, counter.nextDeviceIndex());
            assertEquals(List.of(new CapturedCommand("INCR", "device-counter")), commandFuture.get(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void nextDeviceIndexAuthenticatesBeforeIncrWhenPasswordConfigured() throws Exception {
        try (FakeRedisServer server = new FakeRedisServer()) {
            Future<List<CapturedCommand>> commandFuture = server.handleOnce(List.of("+OK\r\n", ":42\r\n"));
            RedisDeviceCounter counter = new RedisDeviceCounter("127.0.0.1", server.port(), "secret", "device-counter");

            assertEquals(42, counter.nextDeviceIndex());
            assertEquals(List.of(
                    new CapturedCommand("AUTH", "secret"),
                    new CapturedCommand("INCR", "device-counter")
            ), commandFuture.get(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void resetSendsDelCommand() throws Exception {
        try (FakeRedisServer server = new FakeRedisServer()) {
            Future<List<CapturedCommand>> commandFuture = server.handleOnce(List.of(":1\r\n"));
            RedisDeviceCounter counter = new RedisDeviceCounter("127.0.0.1", server.port(), "", "device-counter");

            counter.reset();

            assertEquals(List.of(new CapturedCommand("DEL", "device-counter")), commandFuture.get(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void nextDeviceIndexPropagatesRedisErrorReply() throws Exception {
        try (FakeRedisServer server = new FakeRedisServer()) {
            server.handleOnce(List.of("-ERR counter locked\r\n"));
            RedisDeviceCounter counter = new RedisDeviceCounter("127.0.0.1", server.port(), "", "device-counter");

            IllegalStateException error = assertThrows(IllegalStateException.class, counter::nextDeviceIndex);

            assertEquals("Redis error: ERR counter locked", error.getMessage());
        }
    }

    private record CapturedCommand(String command, String arg) {
    }

    private static final class FakeRedisServer implements AutoCloseable {

        private final ServerSocket serverSocket;
        private final ExecutorService executor;

        private FakeRedisServer() throws IOException {
            this.serverSocket = new ServerSocket(0);
            this.executor = Executors.newSingleThreadExecutor();
        }

        private int port() {
            return serverSocket.getLocalPort();
        }

        private Future<List<CapturedCommand>> handleOnce(List<String> replies) {
            return executor.submit(() -> {
                try (Socket socket = serverSocket.accept();
                     InputStream input = new BufferedInputStream(socket.getInputStream());
                     OutputStream output = new BufferedOutputStream(socket.getOutputStream())) {
                    List<CapturedCommand> commands = new ArrayList<>();
                    for (String reply : replies) {
                        commands.add(readCommand(input));
                        output.write(reply.getBytes(StandardCharsets.UTF_8));
                        output.flush();
                    }
                    return commands;
                }
            });
        }

        @Override
        public void close() throws Exception {
            serverSocket.close();
            executor.shutdownNow();
            executor.awaitTermination(3, TimeUnit.SECONDS);
        }

        private CapturedCommand readCommand(InputStream input) throws IOException {
            String arrayHeader = readLine(input);
            if (!"*2".equals(arrayHeader)) {
                throw new IllegalStateException("Unexpected RESP array header: " + arrayHeader);
            }
            return new CapturedCommand(readBulkString(input), readBulkString(input));
        }

        private String readBulkString(InputStream input) throws IOException {
            String header = readLine(input);
            if (!header.startsWith("$")) {
                throw new IllegalStateException("Unexpected RESP bulk string header: " + header);
            }
            int length = Integer.parseInt(header.substring(1));
            byte[] body = input.readNBytes(length);
            if (body.length != length) {
                throw new IllegalStateException("Unexpected end of RESP bulk string");
            }
            int carriageReturn = input.read();
            int lineFeed = input.read();
            if (carriageReturn != '\r' || lineFeed != '\n') {
                throw new IllegalStateException("Invalid RESP bulk string terminator");
            }
            return new String(body, StandardCharsets.UTF_8);
        }

        private String readLine(InputStream input) throws IOException {
            StringBuilder builder = new StringBuilder();
            int current;
            while ((current = input.read()) != -1) {
                if (current == '\r') {
                    int next = input.read();
                    if (next != '\n') {
                        throw new IllegalStateException("Invalid RESP line terminator");
                    }
                    return builder.toString();
                }
                builder.append((char) current);
            }
            throw new IllegalStateException("Unexpected end of RESP line");
        }
    }
}
