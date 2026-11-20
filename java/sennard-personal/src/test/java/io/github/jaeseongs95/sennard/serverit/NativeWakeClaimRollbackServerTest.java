// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.Advised;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.jaeseongs95.sennard.app.messaging.WakeReservation;
import io.github.jaeseongs95.sennard.app.messaging.WakeReservationService;
import io.github.jaeseongs95.sennard.domain.error.ConflictCode;
import io.github.jaeseongs95.sennard.domain.error.ConflictException;
import io.github.jaeseongs95.sennard.hookclient.HookHeaders;
import io.github.jaeseongs95.sennard.server.hook.HookBody;
import io.github.jaeseongs95.sennard.server.hook.HookPipeline;
import io.github.jaeseongs95.sennard.server.hook.HookVerdict;
import io.github.jaeseongs95.sennard.server.hook.HostContext;
import io.github.jaeseongs95.sennard.server.hook.HostEventHandler;
import io.github.jaeseongs95.sennard.server.hook.HostId;
import io.github.jaeseongs95.sennard.server.hook.host.CodexHookAdapter;
import io.github.jaeseongs95.sennard.server.message.Messages;
import io.github.jaeseongs95.sennard.server.messaging.IdleWakeService;
import io.github.jaeseongs95.sennard.server.messaging.SessionMessageService;
import io.github.jaeseongs95.sennard.server.messaging.WakeHookBoundary;
import io.github.jaeseongs95.sennard.server.messaging.WakeServerIncarnation;
import io.github.jaeseongs95.sennard.server.session.HookSessions;
import tools.jackson.databind.json.JsonMapper;

/** Real DB rollback and hook interception; the advisor schedules a competing write but never replaces consume's result. */
@Tag("business-db")
class NativeWakeClaimRollbackServerTest {
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
            var prepared = messages.prepare(senderRef, "codex", session, "rollback-body", null, OperationKeys.operation("prepare_session_message", senderRef));
            messages.send(senderRef, "codex", prepared.messageId(), OperationKeys.operation("send_session_message", senderRef));
            HostContext context = new HostContext(new HostId("codex"), session, HostContext.SessionActor.root(HostContext.Basis.OBSERVED, "test"),
                    null, null, null, null, null);
            IdleWakeService wake = server.bean(IdleWakeService.class);
            wake.bootstrap(context, UUID.randomUUID().toString());
            String incarnation = server.bean(WakeServerIncarnation.class).value();
            WakeReservation reservation = reservations().reserve(owner.sessionRef(), owner.generation(), incarnation).orElseThrow();
            assertTrue(reservations().start(reservation));
            return new Target(owner.sessionRef(), session, owner.generation(), prepared.messageId(), reservation, context, process);
        } catch (Exception | Error failure) {
            process.destroyForcibly();
            process.onExit().orTimeout(5, TimeUnit.SECONDS).join();
            throw failure;
        }
    }

    private WakeReservationService reservations() { return server.bean(WakeReservationService.class); }

    private void assertPendingBodyAndReceipt(Target target) {
        assertEquals(1, SessionServerSupport.count(server,
                "SELECT count(*) FROM sennard.messages WHERE message_id = ? AND claimed_at IS NOT NULL AND claim_receipt IS NOT NULL", target.messageId()),
                "the final CAS must follow the actual body claim");
        assertEquals(1, SessionServerSupport.count(server,
                "SELECT count(*) FROM sennard.input_source_receipts WHERE host = 'codex' AND session_id = ? AND event_id = ?", target.session(), target.messageId()),
                "the final CAS must follow the actual source receipt");
    }

    private void assertRolledBack(Target target) {
        var row = SessionServerSupport.row(server, "SELECT claimed_at, claim_receipt, delivery_attempts, body FROM sennard.messages WHERE message_id = ?", target.messageId());
        assertNull(row.get("claimed_at"));
        assertNull(row.get("claim_receipt"));
        assertEquals(0L, ((Number) row.get("delivery_attempts")).longValue());
        assertEquals("rollback-body", row.get("body"));
        assertEquals(0, SessionServerSupport.count(server,
                "SELECT count(*) FROM sennard.input_source_receipts WHERE host = 'codex' AND session_id = ? AND event_id = ?", target.session(), target.messageId()));
    }

    private MethodInterceptor loseFinalCas(Target target, AtomicInteger lost) {
        return invocation -> {
            if (invocation.getMethod().getName().equals("consume") && target.ref().equals(invocation.getArguments()[0])) {
                assertPendingBodyAndReceipt(target);
                try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                    executor.submit(() -> reservations().bodyObserved(target.ref())).get(10, TimeUnit.SECONDS);
                }
                Object result = invocation.proceed();
                assertEquals(Boolean.FALSE, result, "the actual database CAS loses to the committed observation");
                lost.incrementAndGet();
                return result;
            }
            return invocation.proceed();
        };
    }

    private HookPipeline pipeline(AtomicInteger nativeCalls) {
        var beans = new StaticListableBeanFactory();
        beans.addBean("handler", (HostEventHandler) call -> { nativeCalls.incrementAndGet(); return new HookVerdict.Proceed(); });
        return new HookPipeline(List.of(new CodexHookAdapter(server.bean(JsonMapper.class))), beans.getBeanProvider(HostEventHandler.class),
                server.bean(Messages.class), List.of(server.bean(WakeHookBoundary.class)));
    }

    private void answer(Target target, AtomicInteger nativeCalls) {
        String json = "{\"session_id\":\"" + target.session() + "\",\"cwd\":\"/work\",\"prompt\":\"" + IdleWakeService.marker(target.reservation().nonce()) + "\"}";
        pipeline(nativeCalls).answer("codex", "UserPromptSubmit", null, HookBody.posted(json.getBytes(StandardCharsets.UTF_8)), List.of());
    }

    @Test void finalCasLossRollsBackBodyAndReceiptAndKeepsMarkerOutOfNativeConsumers() throws Exception {
        try (Target target = target()) {
            AtomicInteger lost = new AtomicInteger();
            AtomicInteger nativeCalls = new AtomicInteger();
            Advised advised = (Advised) reservations();
            MethodInterceptor race = loseFinalCas(target, lost);
            advised.addAdvice(race);
            try { assertDoesNotThrow(() -> answer(target, nativeCalls)); }
            finally { advised.removeAdvice(race); }
            assertEquals(1, lost.get());
            assertEquals(0, nativeCalls.get(), "the marker cannot become native input after its CAS loses");
            assertRolledBack(target);
            assertEquals(WakeReservation.State.OBSERVED, reservations().status(target.ref()).orElseThrow().state(), "the competing transaction remains committed");
        }
    }

    @Test void joinedTransactionPropagatesTypedLossAndRollsBackItsEarlierWrite() throws Exception {
        try (Target target = target()) {
            AtomicInteger lost = new AtomicInteger();
            Advised advised = (Advised) reservations();
            MethodInterceptor race = loseFinalCas(target, lost);
            advised.addAdvice(race);
            try {
                TransactionTemplate transaction = server.bean(TransactionTemplate.class);
                JdbcClient jdbc = server.context.getBean("storeJdbcClient", JdbcClient.class);
                ConflictException lostClaim = assertThrows(ConflictException.class, () -> transaction.executeWithoutResult(status -> {
                    jdbc.sql("UPDATE sennard.messages SET body = body || '-outer' WHERE message_id = ?").param(target.messageId()).update();
                    server.bean(SessionMessageService.class).claimWakeInput(target.context(), target.reservation().nonce());
                }));
                assertEquals(ConflictCode.NATIVE_WAKE_CLAIM_CHANGED, lostClaim.code());
            } finally { advised.removeAdvice(race); }
            assertEquals(1, lost.get());
            assertRolledBack(target);
        }
    }

    @Test void unrelatedClaimFailurePropagatesAndRollsBack() throws Exception {
        try (Target target = target()) {
            IllegalStateException failure = new IllegalStateException("unrelated-claim-failure");
            MethodInterceptor fail = invocation -> {
                if (invocation.getMethod().getName().equals("consume") && target.ref().equals(invocation.getArguments()[0])) {
                    assertPendingBodyAndReceipt(target);
                    throw failure;
                }
                return invocation.proceed();
            };
            Advised advised = (Advised) reservations();
            advised.addAdvice(fail);
            try { assertSame(failure, assertThrows(IllegalStateException.class, () -> answer(target, new AtomicInteger()))); }
            finally { advised.removeAdvice(fail); }
            assertRolledBack(target);
        }
    }
}
