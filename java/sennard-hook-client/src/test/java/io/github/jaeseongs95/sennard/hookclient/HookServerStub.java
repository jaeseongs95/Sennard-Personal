// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.hookclient;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * The server stand-in of the hook client's tests: the JDK's own {@code com.sun.net.httpserver.HttpServer} on a real loopback socket. It records each request and answers what
 * the test set: a status and a body, or, with {@link #holdTheAnswer}, no answer until it is closed (a server that accepts and never answers, which the client's read timeout ends).
 * Not a test itself.
 */
final class HookServerStub implements AutoCloseable {
    /** The answer a stub gives unless a test sets another: UTF-8 text beyond ASCII, so that a client that picks a charset for it is found. */
    static final String ANSWER = "{\"hookSpecificOutput\":{\"additionalContext\":\"한글 😀\"}}";

    /** What the server received of one request; the body is its bytes. */
    record Received(String method, String path, Headers headers, byte[] body) {
        String bodyText() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final CountDownLatch closed = new CountDownLatch(1);
    final List<Received> received = new CopyOnWriteArrayList<>();
    volatile int status = 200;
    volatile byte[] answer = ANSWER.getBytes(StandardCharsets.UTF_8);
    /** The answer's {@code Content-Type}, or null for none (a hook's answer has none the client reads; the MCP relay reads it). */
    volatile String contentType;
    /** When true a request is recorded and then neither answered nor ended until the stub is closed. */
    volatile boolean holdTheAnswer;

    HookServerStub() throws IOException {
        this(0);
    }

    /** @param port the port to listen on; 0 for any free one */
    HookServerStub(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        server.createContext("/", this::handle);
        server.setExecutor(executor);
        server.start();
    }

    int port() {
        return server.getAddress().getPort();
    }

    /** A port nothing listens on: it was free when this returned. */
    static int unusedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    /**
     * Announces this stub as the server, as the real server does: the address file a client reads ({@link ServerAddress}) names this process (the test's own, which is alive) and
     * the stub's port. The file is real and so is the liveness check; only the server behind the port is a stand-in.
     */
    void announceIn(Path file) throws IOException {
        announce(port(), file);
    }

    /** Writes an address file of this process for {@code port}: with a port nothing listens on it is a server that is announced and gone (the connection is refused). */
    static void announce(int port, Path file) throws IOException {
        ServerAddress.ofThisProcess(port).writeAtomically(file);
    }

    /** What the stub answers a request with, when the test sets a {@link #responder}. */
    record Reply(int status, String contentType, byte[] body) {
    }

    /** When set, decides the answer to each request from the request (the MCP relay's requests have different answers); otherwise {@link #status}, {@link #contentType} and {@link #answer}. */
    volatile Function<Received, Reply> responder;

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            Received request = new Received(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(), exchange.getRequestHeaders(), exchange.getRequestBody().readAllBytes());
            received.add(request);
            Function<Received, Reply> decide = responder;
            if (decide != null) {
                Reply reply = decide.apply(request);
                if (reply.contentType() != null) {
                    exchange.getResponseHeaders().add("Content-Type", reply.contentType());
                }
                exchange.sendResponseHeaders(reply.status(), reply.body().length == 0 ? -1 : reply.body().length);
                if (reply.body().length > 0) {
                    exchange.getResponseBody().write(reply.body());
                }
                return;
            }
            if (holdTheAnswer) {
                try {
                    closed.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("the stub was stopped while it held an answer");
                }
            }
            if (contentType != null) {
                exchange.getResponseHeaders().add("Content-Type", contentType);
            }
            if (status / 100 == 3) {
                exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + port() + "/elsewhere");
            }
            exchange.sendResponseHeaders(status, answer.length == 0 ? -1 : answer.length);
            if (answer.length > 0) {
                exchange.getResponseBody().write(answer);
            }
        }
    }

    @Override
    public void close() {
        closed.countDown();
        server.stop(0);
        executor.shutdownNow();
    }
}
