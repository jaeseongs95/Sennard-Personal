// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

import io.github.jaeseongs95.sennard.hookclient.HookHeaders;
import io.github.jaeseongs95.sennard.server.session.SessionClosed;

/**
 * A session start hook that names the processes above its launcher ({@code X-Sennard-Host-Pids}, the nearest first) makes the server watch them, decide
 * which is the host once the grace time has passed (the nearest that still lives: the shell and the launcher end with the hook) and end the session the
 * moment that process ends. The processes are real children of the test; the server is the assembled application with its embedded PostgreSQL. Nothing
 * here replaces the process watching: it is {@code ProcessHandle#onExit} behind {@code HostWatch}, the JDK's own way to learn that a process ended.
 */
@Tag("business-db")
class HostOwnedSessionServerTest {
    private static final String STORED_HOST = "claude-code";
    /** The grace time of the server under test; the tests end the middle processes well inside it. */
    private static final long GRACE_MS = 1_500;
    private static RunningServer server;
    @TempDir
    private static Path serverState; // JUnit deletes it after the class, after the server is closed (D-0194)

    @BeforeAll
    static void start() throws Exception {
        server = started(serverState);
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    /** What these tests add to the real application: a record of every committed session end, to count them. */
    @Configuration(proxyBeanMethods = false)
    static class Closings {
        @Bean
        ClosingLog closingLog() {
            return new ClosingLog();
        }
    }

    static class ClosingLog {
        final List<SessionClosed> all = new CopyOnWriteArrayList<>();

        @EventListener
        void on(SessionClosed event) {
            all.add(event);
        }

        long count(String sessionRef) {
            return all.stream().filter(event -> event.sessionRef().equals(sessionRef)).count();
        }
    }

    private static RunningServer started(Path state) throws Exception {
        List<Class<?>> sources = new ArrayList<>(SessionServerSupport.sources());
        sources.add(Closings.class);
        return SessionServerSupport.started(sources, state, "--sennard.hooks.host-grace-ms=" + GRACE_MS);
    }

    /** A process that stands where a host, or a shell or launcher between the host and the hook, stands: it does nothing until it is killed. */
    public static final class Sleeper {
        public static void main(String[] args) throws InterruptedException {
            Thread.sleep(10 * 60_000L);
        }
    }

    private static final class Proc implements AutoCloseable {
        final Process process;

        private Proc(Process process) {
            this.process = process;
        }

        /** It needs no class of the product, so its class path is only this class's. */
        static Proc start() throws IOException, URISyntaxException {
            Path java = Path.of(System.getProperty("java.home"), "bin", "java");
            Path classes = Path.of(Sleeper.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return new Proc(new ProcessBuilder(java.toString(), "-Xmx" + System.getProperty("sennard.test.childHeap"), "-cp", classes.toString(), Sleeper.class.getName())
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start());
        }

        long pid() {
            return process.pid();
        }

        void kill() throws InterruptedException {
            process.destroyForcibly();
            process.waitFor();
        }

        @Override
        public void close() {
            process.destroyForcibly();
        }
    }

    private static Map<String, Object> rowOf(RunningServer running, String session) {
        return SessionServerSupport.row(running, "SELECT r.session_ref, g.generation, g.process_key, g.relay_id, g.ended_at, g.end_reason, g.superseded_at "
                + "FROM sennard.session_ref r JOIN sennard.session_generation g ON g.session_ref = r.session_ref "
                + "AND g.generation = r.last_generation WHERE r.host = ? AND r.local_session_id = ?", STORED_HOST, session);
    }

    private static RawHttp.Response hook(RunningServer running, String event, String session, List<Long> candidates) throws IOException {
        byte[] body = SessionServerSupport.hook("linux", "claude", event, session, "none");
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Content-Length", Integer.toString(body.length));
        if (!candidates.isEmpty()) {
            headers.put(HookHeaders.HOST_PIDS,candidates.stream().map(String::valueOf).collect(Collectors.joining(",")));
        }
        return RawHttp.exchange(running.port, "POST", "/hooks/v1/claude/" + event, headers, body);
    }

    private static SessionClosed awaitClosed(RunningServer running, String sessionRef) throws Exception {
        return running.bean(SessionServerSupport.ClosedSessions.class).future(sessionRef).get(SessionServerSupport.DEADLINE.toSeconds(), TimeUnit.SECONDS);
    }

    /** Waits for the decision of the owner: the row then carries the process key of the host. */
    private static Map<String, Object> awaitOwned(RunningServer running, String session) throws InterruptedException {
        return SessionServerSupport.await(() -> {
            Map<String, Object> row = rowOf(running, session);
            return row.get("process_key") != null ? Optional.of(row) : Optional.empty();
        }, "the owner of the session to be decided");
    }

    private static String processKeyStart(Proc process) {
        return "local-v1:" + process.pid() + ":";
    }

    // (a) a middle process that ends with the hook is not the host: the nearest one that still lives owns the session

    @Test
    void theNearestCandidateThatLivesAfterTheHookOwnsTheSessionAndItsEndEndsTheSession() throws Exception {
        String session = SessionServerSupport.uniqueSession();
        try (Proc middle = Proc.start(); Proc host = Proc.start()) {
            assertEquals(200, hook(server, "SessionStart", session, List.of(middle.pid(), host.pid())).status());
            Map<String, Object> first = rowOf(server, session);
            assertNull(first.get("process_key"), "usable at once, as an unowned generation, before the owner is decided");
            String ref = (String) first.get("session_ref");
            middle.kill(); // the shell or launcher ends with its hook

            Map<String, Object> owned = awaitOwned(server, session);
            assertTrue(String.valueOf(owned.get("process_key")).startsWith(processKeyStart(host)), "the host owns it, not the middle process: " + owned.get("process_key"));
            assertNotNull(owned.get("relay_id"));
            assertNull(owned.get("ended_at"), "the middle process ending did not end the session");

            // a later hook does not name processes: the ownership stays as it is
            assertEquals(200, hook(server, "UserPromptSubmit", session, List.of()).status());
            assertEquals(owned.get("relay_id"), rowOf(server, session).get("relay_id"));
            assertNull(rowOf(server, session).get("ended_at"));

            host.kill();
            assertEquals("HOST_PROCESS_ENDED", awaitClosed(server, ref).reason());
            Map<String, Object> ended = rowOf(server, session);
            assertNotNull(ended.get("ended_at"));
            assertEquals("HOST_PROCESS_ENDED", ended.get("end_reason"));
        }
    }

    @Test
    void aSessionWhoseCandidatesAllEndedWithinTheGraceEnds() throws Exception {
        String session = SessionServerSupport.uniqueSession();
        try (Proc shell = Proc.start(); Proc launcher = Proc.start()) {
            assertEquals(200, hook(server, "SessionStart", session, List.of(shell.pid(), launcher.pid())).status());
            String ref = (String) rowOf(server, session).get("session_ref");
            shell.kill();
            launcher.kill();

            assertEquals("HOST_PROCESS_ENDED", awaitClosed(server, ref).reason(), "the host ended: nothing above the hook lived");
            assertNotNull(rowOf(server, session).get("ended_at"));
        }
    }

    // (b) nothing named: an unowned generation, as before

    @Test
    void aStartThatNamesNoProcessRegistersAnUnownedGeneration() throws Exception {
        String session = SessionServerSupport.uniqueSession();
        assertEquals(200, hook(server, "SessionStart", session, List.of()).status());
        Map<String, Object> row = rowOf(server, session);
        assertEquals(1L, ((Number) row.get("generation")).longValue());
        assertNull(row.get("process_key"));
        assertNull(row.get("relay_id"));
        assertNull(row.get("ended_at"));
    }

    // (c) only a process id that is already gone: it cannot be bound, the session stays unowned and usable

    @Test
    void aProcessIdThatAlreadyEndedLeavesAnUnownedGeneration() throws Exception {
        String session = SessionServerSupport.uniqueSession();
        long gone;
        try (Proc process = Proc.start()) {
            gone = process.pid();
            process.kill();
        }
        assertEquals(200, hook(server, "SessionStart", session, List.of(gone)).status());
        Thread.sleep(GRACE_MS + 500); // a decision that must not come
        Map<String, Object> row = rowOf(server, session);
        assertNull(row.get("process_key"), "nothing was bound");
        assertNull(row.get("relay_id"));
        assertNull(row.get("ended_at"), "the session itself is usable");
    }

    // (d) the end hook inside the grace cancels the decision

    @Test
    void anEndHookInsideTheGraceCancelsTheDecision() throws Exception {
        String session = SessionServerSupport.uniqueSession();
        try (Proc host = Proc.start()) {
            assertEquals(200, hook(server, "SessionStart", session, List.of(host.pid())).status());
            String ref = (String) rowOf(server, session).get("session_ref");
            assertEquals(200, hook(server, "SessionEnd", session, List.of()).status());
            assertNotNull(rowOf(server, session).get("ended_at"));

            Thread.sleep(GRACE_MS + 500); // a decision that must not come
            Map<String, Object> row = rowOf(server, session);
            assertNull(row.get("process_key"), "no owner was registered for an ended session");
            assertEquals(1, server.bean(ClosingLog.class).count(ref));
            assertTrue(host.process.isAlive());
        }
    }

    // (e) a second start of the same session inside the grace replaces the wait

    @Test
    void aNewStartOfTheSameSessionReplacesTheWaitingDecision() throws Exception {
        String session = SessionServerSupport.uniqueSession();
        try (Proc first = Proc.start(); Proc second = Proc.start()) {
            assertEquals(200, hook(server, "SessionStart", session, List.of(first.pid())).status());
            assertEquals(200, hook(server, "SessionStart", session, List.of(second.pid())).status());
            String ref = (String) rowOf(server, session).get("session_ref");

            Map<String, Object> owned = awaitOwned(server, session);
            assertTrue(String.valueOf(owned.get("process_key")).startsWith(processKeyStart(second)), "the later start decides: " + owned.get("process_key"));

            first.kill(); // watched no more: its end changes nothing
            Thread.sleep(500);
            assertNull(rowOf(server, session).get("ended_at"));

            second.kill();
            assertEquals("HOST_PROCESS_ENDED", awaitClosed(server, ref).reason());
        }
    }

    // (f) the host's end and the end hook arrive together: the session ends once

    @Test
    void anEndHookAndTheHostsEndAtTheSameTimeEndTheSessionOnce() throws Exception {
        ClosingLog log = server.bean(ClosingLog.class);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            for (int round = 0; round < 5; round += 1) {
                String session = SessionServerSupport.uniqueSession();
                try (Proc host = Proc.start()) {
                    assertEquals(200, hook(server, "SessionStart", session, List.of(host.pid())).status());
                    Map<String, Object> owned = awaitOwned(server, session);
                    String ref = (String) owned.get("session_ref");
                    CyclicBarrier together = new CyclicBarrier(2);
                    Future<?> hostEnds = pool.submit(() -> {
                        together.await();
                        host.kill();
                        return null;
                    });
                    Future<RawHttp.Response> endHook = pool.submit(() -> {
                        together.await();
                        return hook(server, "SessionEnd", session, List.of());
                    });
                    hostEnds.get(60, TimeUnit.SECONDS);
                    assertEquals(200, endHook.get(60, TimeUnit.SECONDS).status());
                    awaitClosed(server, ref);
                    SessionServerSupport.await(() -> rowOf(server, session).get("ended_at") != null ? Optional.of(true) : Optional.empty(), "the end to be stored");
                    // Waiting for something that must not happen: the slower of the two has had this long to end it again.
                    Thread.sleep(500);
                    assertEquals(1, log.count(ref), "round " + round + ": one committed end");
                    assertNotNull(rowOf(server, session).get("end_reason"));
                }
            }
        }
    }

    // (g) a restart: a host that lives is watched again, one that died meanwhile is ended at once

    @Test
    void aRestartWatchesTheHostThatLivesAndEndsTheOneThatDiedMeanwhile(@TempDir Path state) throws Exception {
        String aliveSession = SessionServerSupport.uniqueSession();
        String deadSession = SessionServerSupport.uniqueSession();
        try (Proc alive = Proc.start(); Proc dead = Proc.start()) {
            Object aliveRelay;
            String aliveRef;
            String deadRef;
            try (RunningServer first = started(state)) {
                assertEquals(200, hook(first, "SessionStart", aliveSession, List.of(alive.pid())).status());
                assertEquals(200, hook(first, "SessionStart", deadSession, List.of(dead.pid())).status());
                aliveRelay = awaitOwned(first, aliveSession).get("relay_id");
                aliveRef = (String) rowOf(first, aliveSession).get("session_ref");
                awaitOwned(first, deadSession);
                deadRef = (String) rowOf(first, deadSession).get("session_ref");
                assertNotNull(aliveRelay);
            }
            dead.kill(); // while no server runs

            try (RunningServer second = started(state)) {
                assertEquals("HOST_PROCESS_ENDED", awaitClosed(second, deadRef).reason(), "the host ended while the server was down");
                assertNotNull(rowOf(second, deadSession).get("ended_at"));

                SessionServerSupport.await(() -> !aliveRelay.equals(rowOf(second, aliveSession).get("relay_id")) ? Optional.of(true) : Optional.empty(),
                        "the live session to be taken over by this run");
                Map<String, Object> row = rowOf(second, aliveSession);
                assertNull(row.get("ended_at"));
                assertNull(row.get("superseded_at"));
                assertNotEquals(aliveRelay, row.get("relay_id"), "the previous run's owner is fenced");

                alive.kill();
                assertEquals("HOST_PROCESS_ENDED", awaitClosed(second, aliveRef).reason(), "the restored session is watched again");
            }
        }
    }
}
