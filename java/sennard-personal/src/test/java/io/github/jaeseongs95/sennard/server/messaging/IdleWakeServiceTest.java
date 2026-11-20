// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.messaging;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import io.github.jaeseongs95.sennard.domain.error.BindingCode;
import io.github.jaeseongs95.sennard.domain.error.ConflictCode;
import io.github.jaeseongs95.sennard.domain.error.ConflictException;
import io.github.jaeseongs95.sennard.domain.error.BindingException;
import io.github.jaeseongs95.sennard.hookclient.NativeWakeProtocol;
import io.github.jaeseongs95.sennard.server.diagnostics.SafeFailure;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.context.support.MessageSourceAccessor;
import org.springframework.context.support.StaticMessageSource;
import io.github.jaeseongs95.sennard.app.messaging.WakeReservation;
import io.github.jaeseongs95.sennard.app.messaging.WakeReservationService;
import io.github.jaeseongs95.sennard.server.hook.*;
import io.github.jaeseongs95.sennard.server.hook.host.CodexHookAdapter;
import io.github.jaeseongs95.sennard.server.message.Messages;
import io.github.jaeseongs95.sennard.server.mcp.SelfSession;
import io.github.jaeseongs95.sennard.server.session.HookSessions;
import io.github.jaeseongs95.sennard.service.identity.SessionRegistry;
import tools.jackson.databind.json.JsonMapper;

/** Real policy classes with explicit boundary stand-ins; does not attest database CAS or native host execution. */
class IdleWakeServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-17T00:00:00Z"), ZoneOffset.UTC);

    private static class Owners extends HookSessions {
        long generation = 7;
        Owners() { super(null, null, null, CLOCK, new io.github.jaeseongs95.sennard.server.config.SennardProperties(null, null, null, null, false, null,
                new io.github.jaeseongs95.sennard.server.config.SennardProperties.Hooks(java.time.Duration.ZERO, java.time.Duration.ZERO), null, null, null, null, null)); }
        @Override public Optional<Ownership> ownership(String host, String session) { return Optional.of(new Ownership("ref-a", generation)); }
    }
    private static final class Live extends SessionRegistry {
        Live() { super(null, null, null, CLOCK); }
        @Override public boolean isLiveGeneration(String ref, long generation) { return generation == 7; }
        @Override public Optional<String> findRef(String host, String session) { return Optional.of("ref-" + session); }
        @Override public boolean bindManagedChild(String host, String rootSessionId, String rootRef, long generation, String childSessionId) {
            return host.equals("codex") && rootSessionId.equals("session-a") && rootRef.equals("ref-a") && generation == 7 && childSessionId.equals("child-a");
        }
    }
    private static class Reservations extends WakeReservationService {
        int nativeInputs;
        int consumes;
        boolean accepts;
        int starts;
        WakeReservation held;
        Reservations() { super(null, CLOCK); }
        @Override public void cancelReserved(String ref, long generation) { }
        @Override public void nativeActivity(String ref, long generation) { nativeInputs++; }
        @Override public boolean consume(String ref, long generation, String nonce, String server) { consumes++; return accepts; }
        @Override public Optional<WakeReservation> status(String ref) { return Optional.empty(); }
        @Override public void recover(String ref, long generation, String server) { }
        @Override public Optional<WakeReservation> reserve(String ref, long generation, String server) {
            if (held != null) return Optional.empty();
            held = new WakeReservation(ref, generation, UUID.randomUUID().toString(), server, WakeReservation.State.RESERVED, CLOCK.instant(), CLOCK.instant(), null);
            return Optional.of(held);
        }
        @Override public boolean start(WakeReservation row) { starts++; return true; }
        @Override public void finish(WakeReservation row, WakeReservation.State state) {
            held = new WakeReservation(row.sessionRef(), row.generation(), row.nonce(), row.serverId(), state, CLOCK.instant(), CLOCK.instant(), null);
        }
    }
    private static HostContext context() {
        return new HostContext(new HostId("codex", "codex"), "session-a", HostContext.SessionActor.root(HostContext.Basis.OBSERVED, "test"), null, null, null, null, null);
    }
    private static IdleWakeService service(Reservations reservations) {
        return new IdleWakeService(List.of(new WakeTransport() {
            @Override public HostId host() { return context().hostLabel(); }
            @Override public Availability connect(String registration, Target target) { return new Availability.Ready((session, marker) -> new Outcome.Submitted()); }
        }), new Owners(), new Live(), reservations, null, null, CLOCK, new WakeServerIncarnation());
    }

    @Test
    void managedLaunchRequiresTheObservedRootAndItsCurrentOwnedGeneration() throws Exception {
        Owners owners = new Owners();
        var relay = new NativeWakeRelay();
        IdleWakeService wake = new IdleWakeService(List.of(relay.transport(context().hostLabel())),
                owners, new Live(), new Reservations(), null, null, CLOCK, new WakeServerIncarnation());
        String key = UUID.randomUUID().toString();
        assertTrue(wake.bootstrap(context(), key));
        var channel = relay.find(key);
        assertEquals(NativeWakeRelay.Poll.FIRST_CONTACT, channel.beginPoll(NativeWakeProtocol.Capability.MANAGED));
        var root = new SelfSession("ref-a", 7, "codex", "session-a", SelfSession.Provenance.HOST_OBSERVED, context().actor(), null, null, null, null);
        var named = new SelfSession("ref-a", 7, "codex", "session-a", SelfSession.Provenance.UNAVAILABLE, context().actor(), null, null, null, null);
        var inferred = new SelfSession("ref-a", 7, "codex", "session-a", SelfSession.Provenance.HOST_OBSERVED,
                HostContext.SessionActor.root(HostContext.Basis.INFERRED, "test"), null, null, null, null);
        var wrongGeneration = new SelfSession("ref-a", 8, "codex", "session-a", SelfSession.Provenance.HOST_OBSERVED, context().actor(), null, null, null, null);
        var wrongRoot = new SelfSession("ref-other", 7, "codex", "session-a", SelfSession.Provenance.HOST_OBSERVED, context().actor(), null, null, null, null);
        assertThrows(BindingException.class, () -> wake.launchManagedChild(named, "model", "effort"));
        assertThrows(BindingException.class, () -> wake.launchManagedChild(inferred, "model", "effort"));
        assertEquals(BindingCode.BINDING_REQUIRED,
                assertThrows(BindingException.class, () -> wake.launchManagedChild(wrongGeneration, "model", "effort")).code());
        assertEquals(BindingCode.BINDING_REQUIRED,
                assertThrows(BindingException.class, () -> wake.launchManagedChild(wrongRoot, "model", "effort")).code());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = executor.submit(() -> wake.launchManagedChild(root, "model", "effort"));
            var launch = channel.awaitOffer().launch();
            assertNotNull(launch);
            channel.endPoll();
            var created = new NativeWakeProtocol.LaunchReply(NativeWakeProtocol.LaunchResult.CREATED, "child-a");
            assertTrue(channel.completeLaunch(launch.id(), created));
            assertEquals(created, result.get(2, TimeUnit.SECONDS));
            assertEquals(NativeWakeRelay.Poll.CONTINUING, channel.beginPoll(NativeWakeProtocol.Capability.MANAGED));
            var conflicting = executor.submit(() -> wake.launchManagedChild(root, "model", "effort"));
            var otherLaunch = channel.awaitOffer().launch();
            assertNotNull(otherLaunch);
            channel.endPoll();
            assertFalse(channel.completeLaunch(otherLaunch.id(), new NativeWakeProtocol.LaunchReply(NativeWakeProtocol.LaunchResult.CREATED, "child-already-owned")));
            assertEquals(NativeWakeProtocol.LaunchResult.UNKNOWN, conflicting.get(2, TimeUnit.SECONDS).result());
        } finally {
            channel.close();
        }
    }

    @Test void reservedSyntaxIsInterceptedBeforeEveryNativeConsumerEvenWhenInvalidOrReplayed() {
        Reservations reservations = new Reservations();
        IdleWakeService wake = service(reservations);
        wake.bootstrap(context(), null);
        AtomicInteger claims = new AtomicInteger();
        SessionMessageService messages = new SessionMessageService(null, null, null, null, null, null, JsonMapper.builder().build(), CLOCK, wake, reservations) {
            @Override public List<Delivered> claimWakeInput(HostContext context, String nonce) {
                claims.incrementAndGet();
                wake.consume(context, nonce);
                return List.of();
            }
        };
        WakeHookBoundary boundary = new WakeHookBoundary(wake, messages, null);
        HostEvent marker = new HostEvent.UserPromptSubmitted(context(), IdleWakeService.marker(UUID.randomUUID().toString()));
        assertInstanceOf(HookVerdict.Proceed.class, boundary.before(marker).orElseThrow());
        assertEquals(1, reservations.consumes);
        assertEquals(1, claims.get());
        assertEquals(0, reservations.nativeInputs);
        assertTrue(boundary.before(new HostEvent.UserPromptSubmitted(context(), "ordinary instruction")).isEmpty());
        assertEquals(1, reservations.nativeInputs);
    }

    @Test void pipelinePreprocessorRunsBeforeHostEventHandler() {
        AtomicInteger domainCalls = new AtomicInteger();
        var beans = new StaticListableBeanFactory();
        beans.addBean("handler", (HostEventHandler) call -> { domainCalls.incrementAndGet(); return new HookVerdict.Proceed(); });
        HookBoundary boundary = new HookBoundary() {
            @Override public Optional<HookVerdict> before(HostEvent event) {
                return event instanceof HostEvent.UserPromptSubmitted prompt && IdleWakeService.markerNonce(prompt.prompt()).isPresent()
                        ? Optional.of(new HookVerdict.Proceed()) : Optional.empty();
            }
        };
        HookPipeline pipeline = new HookPipeline(List.of(new CodexHookAdapter(JsonMapper.builder().build())), beans.getBeanProvider(HostEventHandler.class),
                new Messages(new MessageSourceAccessor(new StaticMessageSource())), List.of(boundary));
        String prefix = "{\"session_id\":\"session-a\",\"cwd\":\"/work\",\"prompt\":\"";
        pipeline.answer("codex", "UserPromptSubmit", null, HookBody.posted((prefix + IdleWakeService.marker(UUID.randomUUID().toString()) + "\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8)), List.of());
        assertEquals(0, domainCalls.get());
        pipeline.answer("codex", "UserPromptSubmit", null, HookBody.posted((prefix + "ordinary\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8)), List.of());
        assertEquals(1, domainCalls.get());
    }

    @Test void oldServerMarkerCannotConsumeWithoutFreshBindingAndCloseDropsConnections() {
        Reservations reservations = new Reservations();
        reservations.accepts = true;
        IdleWakeService wake = service(reservations);
        assertTrue(wake.consume(context(), UUID.randomUUID().toString()).isEmpty());
        wake.bootstrap(context(), null);
        assertTrue(wake.consume(context(), UUID.randomUUID().toString()).isPresent());
        wake.on(new org.springframework.context.event.ContextClosedEvent(new org.springframework.context.support.GenericApplicationContext()));
        assertTrue(wake.consume(context(), UUID.randomUUID().toString()).isEmpty());
        assertTrue(wake.outlook("codex", "session-a").isEmpty());
    }

    @Test void nativeActivityDefersWakeAndUnknownAttemptDoesNotImmediatelyResubmit() {
        Reservations reservations = new Reservations();
        AtomicInteger launches = new AtomicInteger();
        WakeTransport transport = new WakeTransport() {
            @Override public HostId host() { return context().hostLabel(); }
            @Override public Availability connect(String registration, Target target) {
                return new Availability.Ready((session, marker) -> { launches.incrementAndGet(); return new Outcome.Unknown("timeout"); });
            }
        };
        var queue = (io.github.jaeseongs95.sennard.app.messaging.MessageRepository) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {io.github.jaeseongs95.sennard.app.messaging.MessageRepository.class}, (proxy, method, args) -> {
                    if (method.getName().equals("countClaimable")) return 1L;
                    throw new AssertionError("unexpected queue operation " + method.getName());
                });
        var direct = new org.springframework.core.task.support.TaskExecutorAdapter(Runnable::run);
        IdleWakeService wake = new IdleWakeService(List.of(transport), new Owners(), new Live(), reservations, queue, direct, CLOCK, new WakeServerIncarnation());
        wake.bootstrap(context(), null);
        wake.on(new WakeWaiters.MessageQueued("codex", "session-a"));
        assertEquals(0, launches.get(), "a new session is active until a real turn end");
        wake.boundary(new HostEvent.TurnEnded(context(), false, true), new HookVerdict.Proceed());
        assertEquals(1, launches.get());
        assertEquals(WakeReservation.State.UNKNOWN, reservations.held.state());
        wake.on(new WakeWaiters.MessageQueued("codex", "session-a"));
        assertEquals(1, launches.get());
        wake.nativeInput(context());
        wake.on(new WakeWaiters.MessageQueued("codex", "session-a"));
        assertEquals(1, launches.get());
    }

    @Test void queuedWorkIsCancelledWhenSessionLeavesBeforeDispatch() {
        Reservations reservations = new Reservations();
        AtomicInteger launches = new AtomicInteger();
        WakeTransport transport = new WakeTransport() {
            @Override public HostId host() { return context().hostLabel(); }
            @Override public Availability connect(String registration, Target target) {
                return new Availability.Ready((session, marker) -> { launches.incrementAndGet(); return new Outcome.Submitted(); });
            }
        };
        java.util.ArrayList<Runnable> pending = new java.util.ArrayList<>();
        var delayed = new org.springframework.core.task.support.TaskExecutorAdapter(pending::add);
        IdleWakeService wake = new IdleWakeService(List.of(transport), new Owners(), new Live(), reservations, null, delayed, CLOCK, new WakeServerIncarnation());
        wake.bootstrap(context(), null);
        wake.boundary(new HostEvent.TurnEnded(context(), false, true), new HookVerdict.Proceed());
        assertEquals(1, pending.size());
        wake.on(new HookSessions.SessionLeft("codex", "session-a"));
        pending.getFirst().run();
        assertEquals(0, launches.get());
        assertEquals(0, reservations.starts);
        assertTrue(wake.outlook("codex", "session-a").isEmpty());
    }

    @Test void messageArrivingAfterEmptyQueueSnapshotGetsOneFreshCheck() {
        Reservations reservations = new Reservations();
        AtomicInteger launches = new AtomicInteger();
        AtomicInteger snapshots = new AtomicInteger();
        java.util.ArrayList<Runnable> pending = new java.util.ArrayList<>();
        IdleWakeService[] wake = new IdleWakeService[1];
        var queue = queue(() -> {
            if (snapshots.incrementAndGet() == 1) {
                wake[0].on(new WakeWaiters.MessageQueued("codex", "session-a"));
                return 0L; // The snapshot preceded the commit whose event was just delivered.
            }
            return 1L;
        });
        wake[0] = new IdleWakeService(List.of(transport((session, marker) -> {
            launches.incrementAndGet();
            return new WakeTransport.Outcome.Submitted();
        })), new Owners(), new Live(), reservations, queue, new org.springframework.core.task.support.TaskExecutorAdapter(pending::add), CLOCK, new WakeServerIncarnation());
        wake[0].bootstrap(context(), null);
        wake[0].boundary(new HostEvent.TurnEnded(context(), false, true), new HookVerdict.Proceed());
        pending.removeFirst().run();
        assertEquals(0, launches.get());
        assertEquals(1, pending.size(), "the event during the old snapshot must survive completion");
        pending.removeFirst().run();
        assertEquals(1, launches.get());
        assertEquals(2, snapshots.get());
        assertTrue(pending.isEmpty());
    }

    @Test void eventsDuringUnknownDispatchCoalesceWithoutResending() {
        Reservations reservations = new Reservations();
        AtomicInteger launches = new AtomicInteger();
        AtomicInteger snapshots = new AtomicInteger();
        java.util.ArrayList<Runnable> pending = new java.util.ArrayList<>();
        IdleWakeService[] wake = new IdleWakeService[1];
        wake[0] = new IdleWakeService(List.of(transport((session, marker) -> {
            launches.incrementAndGet();
            wake[0].on(new WakeWaiters.MessageQueued("codex", "session-a"));
            wake[0].on(new WakeWaiters.MessageQueued("codex", "session-a"));
            return new WakeTransport.Outcome.Unknown("timeout");
        })), new Owners(), new Live(), reservations, queue(() -> { snapshots.incrementAndGet(); return 1L; }),
                new org.springframework.core.task.support.TaskExecutorAdapter(pending::add), CLOCK, new WakeServerIncarnation());
        wake[0].bootstrap(context(), null);
        wake[0].boundary(new HostEvent.TurnEnded(context(), false, true), new HookVerdict.Proceed());
        pending.removeFirst().run();
        assertEquals(1, pending.size(), "multiple events coalesce to one recheck");
        pending.removeFirst().run();
        assertEquals(1, launches.get(), "UNKNOWN remains reserved even after a recheck");
        assertEquals(2, snapshots.get());
        assertEquals(WakeReservation.State.UNKNOWN, reservations.held.state());
        assertTrue(pending.isEmpty());
    }

    @Test void closeDiscardsARecheckRequestedDuringDispatch() {
        Reservations reservations = new Reservations();
        AtomicInteger launches = new AtomicInteger();
        java.util.ArrayList<Runnable> pending = new java.util.ArrayList<>();
        IdleWakeService[] wake = new IdleWakeService[1];
        wake[0] = new IdleWakeService(List.of(transport((session, marker) -> {
            launches.incrementAndGet();
            wake[0].on(new WakeWaiters.MessageQueued("codex", "session-a"));
            wake[0].on(new HookSessions.SessionLeft("codex", "session-a"));
            return new WakeTransport.Outcome.Unknown("interrupted");
        })), new Owners(), new Live(), reservations, queue(() -> 1L), new org.springframework.core.task.support.TaskExecutorAdapter(pending::add), CLOCK, new WakeServerIncarnation());
        wake[0].bootstrap(context(), null);
        wake[0].boundary(new HostEvent.TurnEnded(context(), false, true), new HookVerdict.Proceed());
        pending.removeFirst().run();
        assertTrue(Thread.interrupted(), "closing the running attempt requests interruption");
        assertEquals(1, launches.get());
        assertTrue(pending.isEmpty(), "closed connections cannot schedule their pending recheck");
        assertTrue(wake[0].outlook("codex", "session-a").isEmpty());
    }

    @Test void pendingRecheckUsesCurrentGenerationAndRejectsAnExpiredReplacement() {
        Reservations reservations = new Reservations();
        Owners owners = new Owners();
        AtomicInteger launches = new AtomicInteger();
        AtomicInteger snapshots = new AtomicInteger();
        java.util.ArrayList<Runnable> pending = new java.util.ArrayList<>();
        IdleWakeService[] wake = new IdleWakeService[1];
        wake[0] = new IdleWakeService(List.of(transport((session, marker) -> {
            launches.incrementAndGet();
            return new WakeTransport.Outcome.Submitted();
        })), owners, new Live(), reservations, queue(() -> {
            snapshots.incrementAndGet();
            owners.generation = 8; // Generation 8 is no longer live in the registry.
            wake[0].on(new WakeWaiters.MessageQueued("codex", "session-a"));
            return 0L;
        }), new org.springframework.core.task.support.TaskExecutorAdapter(pending::add), CLOCK, new WakeServerIncarnation());
        wake[0].bootstrap(context(), null);
        wake[0].boundary(new HostEvent.TurnEnded(context(), false, true), new HookVerdict.Proceed());
        pending.removeFirst().run();
        assertEquals(1, pending.size());
        pending.removeFirst().run();
        assertEquals(0, launches.get());
        assertEquals(1, snapshots.get(), "the replacement generation is checked before reading its queue");
        assertNull(reservations.held);
        assertTrue(pending.isEmpty());
    }

    @Test void executorSaturationRetainsEveryTargetWithoutAnotherEvent() throws Exception {
        var executor = new WakeExecutionConfiguration().nativeWakeExecutor();
        executor.initialize();
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(67);
        var workerWaitFailed = new java.util.concurrent.atomic.AtomicBoolean();
        var calls = new ConcurrentHashMap<String, AtomicInteger>();
        var rows = new ConcurrentHashMap<String, WakeReservation>();
        Owners owners = new Owners() {
            @Override public Optional<Ownership> ownership(String host, String session) { return Optional.of(new Ownership(session, 7)); }
        };
        Reservations reservations = new Reservations() {
            @Override public Optional<WakeReservation> reserve(String ref, long generation, String server) {
                var row = new WakeReservation(ref, generation, UUID.randomUUID().toString(), server, WakeReservation.State.RESERVED, CLOCK.instant(), CLOCK.instant(), null);
                return rows.putIfAbsent(ref, row) == null ? Optional.of(row) : Optional.empty();
            }
            @Override public boolean start(WakeReservation row) { return true; }
            @Override public void finish(WakeReservation row, WakeReservation.State state) {
                rows.put(row.sessionRef(), new WakeReservation(row.sessionRef(), row.generation(), row.nonce(), row.serverId(), state, CLOCK.instant(), CLOCK.instant(), null));
                finished.countDown();
            }
        };
        IdleWakeService wake = new IdleWakeService(List.of(transport((session, marker) -> {
            calls.computeIfAbsent(session, ignored -> new AtomicInteger()).incrementAndGet();
            if (session.equals("session-0") || session.equals("session-1")) {
                entered.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) {
                        workerWaitFailed.set(true);
                    }
                } catch (InterruptedException interrupted) {
                    workerWaitFailed.set(true);
                    Thread.currentThread().interrupt();
                }
            }
            return new WakeTransport.Outcome.Unknown("uncertain");
        })), owners, new Live(), reservations, queue(() -> 1L), executor, CLOCK, new WakeServerIncarnation());
        try {
            for (int index = 0; index < 2; index++) {
                HostContext context = context("session-" + index, "/work");
                wake.bootstrap(context, null);
                wake.boundary(new HostEvent.TurnEnded(context, false, true), new HookVerdict.Proceed());
            }
            assertTrue(entered.await(10, TimeUnit.SECONDS), "both real workers must be occupied before filling the queue");
            for (int index = 2; index < 68; index++) {
                HostContext context = context("session-" + index, "/work");
                wake.bootstrap(context, null);
                wake.boundary(new HostEvent.TurnEnded(context, false, true), new HookVerdict.Proceed());
            }
            assertEquals(64, executor.getThreadPoolExecutor().getQueue().size());
            wake.on(new HookSessions.SessionLeft("codex", "session-66"));
            release.countDown();
            assertTrue(finished.await(10, TimeUnit.SECONDS), "rejected target 67 must drain without another event");
            assertFalse(workerWaitFailed.get(), "both occupied workers must be released before their deadline without interruption");
            assertEquals(67, calls.size());
            assertFalse(calls.containsKey("session-66"), "a cancelled pending target never crosses dispatch");
            assertEquals(1, calls.get("session-67").get());
            for (var count : calls.values()) {
                assertEquals(1, count.get(), "UNKNOWN is not resubmitted");
            }
        } finally {
            release.countDown();
            wake.on(new org.springframework.context.event.ContextClosedEvent(new org.springframework.context.support.GenericApplicationContext()));
            executor.shutdown();
        }
    }

    @Test void safeFailureRetainsGraphAndStacksWithoutReadingMessages() {
        class MessageTrap extends RuntimeException {
            private static final long serialVersionUID = 1L;
            MessageTrap() { super("secret-root-token"); }
            @Override public String getMessage() { throw new AssertionError("original message must never be read"); }
            @Override public String getLocalizedMessage() { throw new AssertionError("original localized message must never be read"); }
            @Override public String toString() { throw new AssertionError("original toString must never be read"); }
        }
        Throwable root = new MessageTrap();
        Throwable cause = new IllegalStateException("secret-cause-token");
        Throwable suppressed = new UnsupportedOperationException("secret-suppressed-token");
        root.initCause(cause);
        cause.initCause(root);
        root.addSuppressed(cause);
        root.addSuppressed(suppressed);
        suppressed.addSuppressed(root);
        SafeFailure safe = SafeFailure.capture(root);
        assertEquals(MessageTrap.class.getName(), safe.getMessage());
        assertArrayEquals(root.getStackTrace(), safe.getStackTrace());
        assertArrayEquals(cause.getStackTrace(), safe.getCause().getStackTrace());
        assertEquals(IllegalStateException.class.getName(), safe.getCause().getMessage());
        assertSame(safe, safe.getCause().getCause(), "cause cycle keeps identity");
        assertSame(safe.getCause(), safe.getSuppressed()[0], "shared nodes remain shared");
        assertSame(safe, safe.getSuppressed()[1].getSuppressed()[0]);
        String rendered = ch.qos.logback.classic.spi.ThrowableProxyUtil.asString(
                new ch.qos.logback.classic.spi.ThrowableProxy(safe));
        assertFalse(rendered.toString().contains("secret-"));
        assertTrue(rendered.toString().contains(IllegalStateException.class.getName()));
        assertTrue(rendered.toString().contains("CIRCULAR REFERENCE"));
    }

    @Test void markerSyntaxRemainsExact() {
        String nonce = "12345678-1234-abcd-1234-123456789abc";
        String marker = "[sennard wake:12345678-1234-abcd-1234-123456789abc]";
        assertEquals(Optional.of(nonce), IdleWakeService.markerNonce(marker));
        assertEquals(marker, IdleWakeService.marker(nonce));
        assertTrue(IdleWakeService.markerNonce(null).isEmpty());
        for (String invalid : List.of(marker.toUpperCase(java.util.Locale.ROOT), " " + marker, marker + "\n", "prefix" + marker,
                "[sennard wake:12345678-1234-abcd-1234-123456789ab]", "ordinary")) {
            assertTrue(IdleWakeService.markerNonce(invalid).isEmpty());
        }
    }

    @Test void onlyWakeClaimConflictIsIntercepted() {
        IdleWakeService wake = service(new Reservations());
        HostEvent event = new HostEvent.UserPromptSubmitted(context(), "[sennard wake:12345678-1234-abcd-1234-123456789abc]");
        var failure = new java.util.concurrent.atomic.AtomicReference<RuntimeException>(new ConflictException(ConflictCode.NATIVE_WAKE_CLAIM_CHANGED));
        SessionMessageService messages = new SessionMessageService(null, null, null, null, null, null, JsonMapper.builder().build(), CLOCK, wake, null) {
            @Override public List<Delivered> claimWakeInput(HostContext context, String nonce) { throw failure.get(); }
        };
        WakeHookBoundary boundary = new WakeHookBoundary(wake, messages, null);
        assertInstanceOf(HookVerdict.Proceed.class, boundary.before(event).orElseThrow());
        var unrelated = new ConflictException(ConflictCode.CONCURRENT_UPDATE);
        failure.set(unrelated);
        assertSame(unrelated, assertThrows(ConflictException.class, () -> boundary.before(event)));
        var unexpected = new IllegalStateException("unexpected");
        failure.set(unexpected);
        assertSame(unexpected, assertThrows(IllegalStateException.class, () -> boundary.before(event)));
    }

    @Test void bootstrapPassesObservedTargetAndIncarnation() {
        var incarnation = new WakeServerIncarnation();
        var target = new java.util.concurrent.atomic.AtomicReference<WakeTransport.Target>();
        var consumedBy = new java.util.concurrent.atomic.AtomicReference<String>();
        Reservations reservations = new Reservations() {
            @Override public boolean consume(String ref, long generation, String nonce, String server) { consumedBy.set(server); return true; }
        };
        WakeTransport transport = new WakeTransport() {
            @Override public HostId host() { return context().hostLabel(); }
            @Override public Availability connect(String registration, Target observed) {
                target.set(observed);
                return new Availability.Ready((session, marker) -> new Outcome.Submitted());
            }
        };
        IdleWakeService wake = new IdleWakeService(List.of(transport), new Owners(), new Live(), reservations, null, null, CLOCK, incarnation);
        HostContext observed = context("observed-session", "/observed-workspace");
        wake.bootstrap(observed, null);
        assertEquals(new WakeTransport.Target("observed-session", "/observed-workspace"), target.get());
        assertTrue(wake.consume(observed, "12345678-1234-abcd-1234-123456789abc").isPresent());
        assertEquals(incarnation.value(), consumedBy.get());
    }

    private static HostContext context(String session, String workspace) {
        return new HostContext(context().hostLabel(), session, context().actor(), workspace, null, null, null, null);
    }

    private static WakeTransport transport(WakeTransport.Connection connection) {
        return new WakeTransport() {
            @Override public HostId host() { return context().hostLabel(); }
            @Override public Availability connect(String registration, Target target) { return new Availability.Ready(connection); }
        };
    }

    private static io.github.jaeseongs95.sennard.app.messaging.MessageRepository queue(java.util.function.LongSupplier snapshots) {
        return (io.github.jaeseongs95.sennard.app.messaging.MessageRepository) java.lang.reflect.Proxy.newProxyInstance(IdleWakeServiceTest.class.getClassLoader(),
                new Class<?>[] {io.github.jaeseongs95.sennard.app.messaging.MessageRepository.class}, (proxy, method, args) -> {
                    if (method.getName().equals("countClaimable")) return snapshots.getAsLong();
                    throw new AssertionError("unexpected queue operation " + method.getName());
                });
    }
}
