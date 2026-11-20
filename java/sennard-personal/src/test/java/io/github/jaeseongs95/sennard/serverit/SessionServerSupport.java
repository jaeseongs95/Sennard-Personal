// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.springframework.ai.mcp.annotation.McpMeta;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.jaeseongs95.sennard.server.mcp.McpSessionResolver;
import io.github.jaeseongs95.sennard.server.mcp.SessionBinding;
import io.github.jaeseongs95.sennard.server.session.SessionClosed;
import io.github.jaeseongs95.sennard.service.store.pg.PreparedStore;
import io.github.jaeseongs95.sennard.service.store.pg.StorePhase;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The application with the real business database (the PostgreSQL database the test run names, {@link SharedDatabase}), the real session services and the recorded host traffic.
 * Waiting is on conditions, with a deadline that only a failure reaches.
 */
final class SessionServerSupport {
    static final Duration DEADLINE = Duration.ofSeconds(180);
    static final String FIXTURE_SESSION = HookTestSupport.SESSION;
    static final String FIXTURE_TOOL_USE = "toolu_fixture_2";
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private SessionServerSupport() {
    }

    /** A tool that binds its caller the way the domain tools do and keeps the answer for the test. */
    static class SessionProbe {
        private final McpSessionResolver resolver;
        final java.util.concurrent.atomic.AtomicReference<SessionBinding> last = new java.util.concurrent.atomic.AtomicReference<>();

        SessionProbe(McpSessionResolver resolver) {
            this.resolver = resolver;
        }

        @McpTool(name = "session_probe", description = "Binds its session.",
                annotations = @McpTool.McpAnnotations(readOnlyHint = false, idempotentHint = false, destructiveHint = false, openWorldHint = false))
        public String probe(McpMeta meta, io.modelcontextprotocol.common.McpTransportContext context, @McpToolParam(description = "A note", required = false) String note,
                @McpToolParam(description = "A session reference", required = false) String sessionRef) {
            last.set(resolver.bind("session_probe", note == null ? Map.of() : Map.of("note", note), meta, context, sessionRef));
            return "ok";
        }
    }

    /** Lets a test wait for a session's end by the event the product publishes. */
    static class ClosedSessions {
        private final Map<String, CompletableFuture<SessionClosed>> closed = new ConcurrentHashMap<>();

        @EventListener
        void on(SessionClosed event) {
            future(event.sessionRef()).complete(event);
        }

        CompletableFuture<SessionClosed> future(String sessionRef) {
            return closed.computeIfAbsent(sessionRef, ignored -> new CompletableFuture<>());
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Probes {
        @Bean
        SessionProbe sessionProbe(McpSessionResolver resolver) {
            return new SessionProbe(resolver);
        }

        @Bean
        ClosedSessions closedSessions() {
            return new ClosedSessions();
        }
    }

    /** On a state directory of its own, which is deleted when the server is closed (D-0194). */
    static RunningServer started(List<Class<?>> sources, String... extraArguments) throws Exception {
        return started(sources, Files.createTempDirectory("sennard-session-it-"), true, extraArguments);
    }

    /** On a state directory the caller made and deletes, for a test that starts the server again over the same state. */
    static RunningServer started(List<Class<?>> sources, Path stateDirectory, String... extraArguments) throws Exception {
        return started(sources, stateDirectory, false, extraArguments);
    }

    private static RunningServer started(List<Class<?>> sources, Path stateDirectory, boolean owned, String... extraArguments) throws Exception {
        SharedDatabase.resetForTheClass();
        // The database is linked the way a user links it: in the settings database of the state folder, before the server starts on that folder (a restart over the same folder links it again).
        Files.createDirectories(stateDirectory);
        SharedDatabase.linkInto(stateDirectory.resolve("settings"));
        List<String> arguments = new java.util.ArrayList<>(List.of("--sennard.store.prepare=true"));
        arguments.addAll(List.of(extraArguments));
        arguments.addAll(ChangeWorkflows.checkoutSettings(extraArguments));
        RunningServer server = owned
                ? RunningServer.startOwned(stateDirectory, sources, arguments.toArray(String[]::new))
                : RunningServer.startAt(stateDirectory, sources, arguments.toArray(String[]::new));
        try {
            await(() -> server.bean(PreparedStore.class).status().phase() == StorePhase.READY ? Optional.of(true) : Optional.empty(), "the store to be ready");
        } catch (InterruptedException | RuntimeException | Error failure) {
            server.close(); // a server that is not ready is not handed out, and its state directory must not stay behind
            throw failure;
        }
        return server;
    }

    static List<Class<?>> sources() {
        return List.of(ServerFixtures.Beans.class, Probes.class);
    }

    static <T> T await(Supplier<Optional<T>> condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + DEADLINE.toNanos();
        do {
            Optional<T> value = condition.get();
            if (value.isPresent()) {
                return value.get();
            }
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Timed out waiting for " + what);
    }

    /** One row of a query, in one transaction. */
    static Map<String, Object> row(RunningServer server, String sql, Object... params) {
        JdbcClient jdbc = server.context.getBean("storeJdbcClient", JdbcClient.class);
        TransactionTemplate transactions = server.bean(TransactionTemplate.class);
        return transactions.execute(status -> jdbc.sql(sql).params(params).query().singleRow());
    }

    static long count(RunningServer server, String sql, Object... params) {
        return ((Number) row(server, sql, params).values().iterator().next()).longValue();
    }

    static void update(RunningServer server, String sql, Object... params) {
        JdbcClient jdbc = server.context.getBean("storeJdbcClient", JdbcClient.class);
        TransactionTemplate transactions = server.bean(TransactionTemplate.class);
        transactions.execute(status -> jdbc.sql(sql).params(params).update());
    }

    /** A recorded hook body with this test's session and call in place of the recorded ones. */
    static byte[] hook(String os, String host, String event, String session, String toolUse) {
        return HookTestSupport.fixtureText(os, host, event).replace(FIXTURE_SESSION, session).replace(FIXTURE_TOOL_USE, toolUse).getBytes(StandardCharsets.UTF_8);
    }

    /** The recorded {@code _meta} of a tool call by this host, with this test's session and call. */
    static Map<String, Object> meta(String os, String host, String session, String toolUse) {
        String resource = "/mcp-meta-fixtures/" + os + "/" + host + ".json";
        try (InputStream stream = SessionServerSupport.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalArgumentException("No fixture at " + resource);
            }
            String text = new String(stream.readAllBytes(), StandardCharsets.UTF_8).replace(FIXTURE_SESSION, session).replace(FIXTURE_TOOL_USE, toolUse);
            @SuppressWarnings("unchecked")
            Map<String, Object> meta = MAPPER.readValue(text, LinkedHashMap.class);
            return meta;
        } catch (IOException unreadable) {
            throw new IllegalStateException(unreadable);
        }
    }

    static RawHttp.Response post(int port, String path, byte[] body) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Content-Length", Integer.toString(body.length));
        return RawHttp.exchange(port, "POST", path, headers, body);
    }

    static JsonNode json(RawHttp.Response response) {
        return MAPPER.readTree(response.body());
    }

    static String uniqueSession() {
        return UUID.randomUUID().toString();
    }

}
