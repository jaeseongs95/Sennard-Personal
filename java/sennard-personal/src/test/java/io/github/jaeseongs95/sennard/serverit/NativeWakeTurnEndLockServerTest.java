// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.Advised;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.jaeseongs95.sennard.app.messaging.InputObservationRepository;
import io.github.jaeseongs95.sennard.app.messaging.WakeReservation;
import io.github.jaeseongs95.sennard.app.messaging.WakeReservationService;
import io.github.jaeseongs95.sennard.hookclient.HookHeaders;
import io.github.jaeseongs95.sennard.server.hook.HookVerdict;
import io.github.jaeseongs95.sennard.server.hook.HostContext;
import io.github.jaeseongs95.sennard.server.hook.HostEvent;
import io.github.jaeseongs95.sennard.server.hook.HostId;
import io.github.jaeseongs95.sennard.server.messaging.IdleWakeService;
import io.github.jaeseongs95.sennard.server.messaging.PeerEnvelope;
import io.github.jaeseongs95.sennard.server.messaging.SessionMessageService;
import io.github.jaeseongs95.sennard.server.messaging.SessionMessageService.Boundary;
import io.github.jaeseongs95.sennard.server.messaging.SessionMessageService.Delivered;
import io.github.jaeseongs95.sennard.server.messaging.WakeHookBoundary;
import io.github.jaeseongs95.sennard.server.messaging.WakeServerIncarnation;
import io.github.jaeseongs95.sennard.server.session.HookSessions;
import tools.jackson.databind.json.JsonMapper;

/** Real observation/message lock competition; the advisor only observes and pauses real repository operations. */
@Tag("business-db")
class NativeWakeTurnEndLockServerTest {
    private static final String BODY = "turn-end-lock-body";
    private static RunningServer server;

    @BeforeAll static void start() throws Exception { server = SessionServerSupport.started(SessionServerSupport.sources()); }
    @AfterAll static void stop() { server.close(); }

    private record Target(String ref, String session, long generation, String messageId, WakeReservation reservation, HostContext context, Process process)
            implements AutoCloseable {
        @Override public void close() {
            process.destroyForcibly();
            process.onExit().orTimeout(5, TimeUnit.SECONDS).join();
            assertFalse(Thread.currentThread().isInterrupted(), "interrupted while reaping the ownership fixture process");
        }
    }

    private WakeReservationService reservations() { return server.bean(WakeReservationService.class); }

    private Target target() throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Path classes = Path.of(HostOwnedSessionServerTest.Sleeper.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Process process = new ProcessBuilder(java.toString(), "-Xmx" + System.getProperty("sennard.test.childHeap"), "-cp", classes.toString(),
                HostOwnedSessionServerTest.Sleeper.class.getName()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        try {
            String session = SessionServerSupport.uniqueSession();
            byte[] body = SessionServerSupport.hook("windows", "codex", "SessionStart", session, "none");
            assertEquals(200, RawHttp.exchange(server.port, "POST", "/hooks/v1/codex/SessionStart", Map.of("Content-Type", "application/json",
                    "Content-Length", Integer.toString(body.length), HookHeaders.HOST_PIDS, Long.toString(process.pid())), body).status());
            var owner = SessionServerSupport.await(() -> server.bean(HookSessions.class).ownership("codex", session), "the fixture process to own the session");
            String sender = SessionServerSupport.uniqueSession();
            assertEquals(200, DomainServerSupport.hook(server.port, "windows", "codex", "SessionStart", null, sender, ignored -> { }).status());
            String senderRef = (String) SessionServerSupport.row(server,
                    "SELECT session_ref FROM sennard.session_ref WHERE host = 'codex' AND local_session_id = ?", sender).get("session_ref");
            SessionMessageService messages = server.bean(SessionMessageService.class);
            var prepared = messages.prepare(senderRef, "codex", session, BODY, null, OperationKeys.operation("prepare_session_message", senderRef));
            messages.send(senderRef, "codex", prepared.messageId(), OperationKeys.operation("send_session_message", senderRef));
            HostContext context = new HostContext(new HostId("codex"), session, HostContext.SessionActor.root(HostContext.Basis.OBSERVED, "test"),
                    null, null, null, null, null);
            IdleWakeService wake = server.bean(IdleWakeService.class);
            wake.bootstrap(context, UUID.randomUUID().toString());
            String incarnation = server.bean(WakeServerIncarnation.class).value();
            WakeReservation reservation = reservations().reserve(owner.sessionRef(), owner.generation(), incarnation).orElseThrow();
            assertTrue(reservations().start(reservation));
            messages.observeNativeInput(owner.sessionRef());
            assertEquals(1, SessionServerSupport.count(server,
                    "SELECT count(*) FROM sennard.input_observations WHERE session_ref = ? AND state = 'DEFERRED'", owner.sessionRef()));
            return new Target(owner.sessionRef(), session, owner.generation(), prepared.messageId(), reservation, context, process);
        } catch (Exception | Error failure) {
            process.destroyForcibly();
            process.onExit().orTimeout(5, TimeUnit.SECONDS).join();
            throw failure;
        }
    }

    @Test void turnEndAndNativeNonceDoNotInvertObservationAndMessageLocks() throws Exception {
        try (Target target = target()) {
            JdbcClient jdbc = server.context.getBean("storeJdbcClient", JdbcClient.class);
            TransactionTemplate transaction = server.bean(TransactionTemplate.class);
            SessionMessageService messages = server.bean(SessionMessageService.class);
            CompletableFuture<Integer> ordinaryPid = new CompletableFuture<>();
            CompletableFuture<Integer> nativePid = new CompletableFuture<>();
            CompletableFuture<Void> nativeFinished = new CompletableFuture<>();
            CountDownLatch proceed = new CountDownLatch(1);
            MethodInterceptor observeAndPause = invocation -> {
                String method = invocation.getMethod().getName();
                boolean selected = (method.equals("settle") || method.equals("seenAt")) && target.ref().equals(invocation.getArguments()[0]);
                if (selected && method.equals("seenAt")) {
                    jdbc.sql("SET LOCAL statement_timeout = '15s'").update();
                    nativePid.complete(jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single());
                }
                Object result = invocation.proceed();
                if (selected && method.equals("settle") && Integer.valueOf(1).equals(result)) {
                    ordinaryPid.complete(jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single());
                    try {
                        assertTrue(proceed.await(20, TimeUnit.SECONDS), "the orchestrator must release the real TURN_END observation update");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    }
                }
                return result;
            };
            Advised observations = (Advised) server.bean(InputObservationRepository.class);
            // QueryExecutorMethodInterceptor completes declared queries without proceeding to later advice.
            observations.addAdvice(0, observeAndPause);
            assertSame(observeAndPause, observations.getAdvisors()[0].getAdvice(), "the observer must wrap the real repository query chain");
            try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                Future<List<Delivered>> ordinaryClaim = pool.submit(() -> {
                    try {
                        List<Delivered> result = transaction.execute(status -> {
                            jdbc.sql("SET LOCAL statement_timeout = '15s'").update();
                            return messages.claim("codex", target.session(), target.ref(), Boundary.TURN_END);
                        });
                        ordinaryPid.completeExceptionally(new AssertionError("TURN_END returned before its real settle changed one row"));
                        return result;
                    } catch (Exception | Error failure) {
                        ordinaryPid.completeExceptionally(failure);
                        throw failure;
                    }
                });
                try {
                    int blocker = ordinaryPid.get(20, TimeUnit.SECONDS);
                    Future<HookVerdict> nativeClaim = pool.submit(() -> {
                        try {
                            // No outer test transaction: the real boundary catches a typed final CAS loss only after rollback.
                            HookVerdict result = server.bean(WakeHookBoundary.class).before(new HostEvent.UserPromptSubmitted(target.context(),
                                    IdleWakeService.marker(target.reservation().nonce()), HostEvent.PromptOrigin.USER)).orElseThrow();
                            nativePid.completeExceptionally(new AssertionError("native boundary returned before its real observation update"));
                            nativeFinished.complete(null);
                            return result;
                        } catch (Exception | Error failure) {
                            nativePid.completeExceptionally(failure);
                            nativeFinished.completeExceptionally(failure);
                            throw failure;
                        }
                    });
                    int waiter = nativePid.get(20, TimeUnit.SECONDS);
                    assertNotEquals(blocker, waiter, "the claims must use independent database connections");
                    SessionServerSupport.await(() -> {
                        if (SessionServerSupport.count(server, "SELECT CASE WHEN ? = ANY(pg_blocking_pids(?)) THEN 1 ELSE 0 END", blocker, waiter) == 1) {
                            return Optional.of(true);
                        }
                        if (nativeFinished.isDone()) {
                            nativeFinished.join();
                            throw new AssertionError("native boundary finished before the required database blocking witness");
                        }
                        return Optional.empty();
                    }, "the native observation update to wait for the real TURN_END transaction");
                    proceed.countDown();
                    List<Delivered> ordinaryResult = ordinaryClaim.get(20, TimeUnit.SECONDS);
                    HookVerdict nativeResult = nativeClaim.get(20, TimeUnit.SECONDS);
                    assertSingleDelivery(target, ordinaryResult, nativeResult);
                } finally {
                    proceed.countDown();
                }
            } finally {
                observations.removeAdvice(observeAndPause);
            }
        }
    }

    private void assertSingleDelivery(Target target, List<Delivered> ordinary, HookVerdict nativeVerdict) {
        List<String> deliveredIds = new ArrayList<>();
        for (Delivered delivered : ordinary) {
            deliveredIds.add(delivered.messageId());
            assertEquals(BODY, delivered.body());
            assertNotNull(delivered.sourceReceiptId());
        }
        switch (nativeVerdict) {
            case HookVerdict.Proceed ignored -> { }
            case HookVerdict.AddContext context -> {
                JsonMapper mapper = server.bean(JsonMapper.class);
                for (var block : context.blocks()) {
                    for (String line : block.text().lines().filter(value -> value.startsWith("{")).toList()) {
                        PeerEnvelope.Content content = mapper.readValue(line, PeerEnvelope.Content.class);
                        deliveredIds.add(content.receipt().messageId());
                        assertEquals(BODY, content.message());
                        assertNotNull(content.receipt().sourceReceiptId());
                    }
                }
            }
            default -> fail("unexpected native marker verdict: " + nativeVerdict);
        }
        assertEquals(List.of(target.messageId()), deliveredIds, "both boundaries together deliver the fixture message exactly once");
        assertEquals(1, SessionServerSupport.count(server,
                "SELECT count(*) FROM sennard.messages WHERE message_id = ? AND claimed_at IS NOT NULL AND claim_receipt IS NOT NULL AND delivery_attempts = 1",
                target.messageId()));
        assertEquals(1, SessionServerSupport.count(server,
                "SELECT count(*) FROM sennard.input_source_receipts WHERE host = 'codex' AND session_id = ? AND event_id = ?", target.session(), target.messageId()));
        WakeReservation after = reservations().status(target.ref()).orElseThrow();
        assertEquals(target.reservation().nonce(), after.nonce());
        assertEquals(WakeReservation.State.OBSERVED, after.state());
        assertFalse(reservations().consume(target.ref(), target.generation(), target.reservation().nonce(), target.reservation().serverId()), "the nonce cannot replay");
    }
}
