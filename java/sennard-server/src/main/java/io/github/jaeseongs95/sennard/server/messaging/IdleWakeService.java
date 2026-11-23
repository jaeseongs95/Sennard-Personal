// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.messaging;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import io.github.jaeseongs95.sennard.domain.error.BindingCode;
import io.github.jaeseongs95.sennard.domain.error.BindingException;
import io.github.jaeseongs95.sennard.domain.messaging.PeerAddress;
import io.github.jaeseongs95.sennard.hookclient.NativeWakeProtocol.LaunchReply;
import io.github.jaeseongs95.sennard.hookclient.NativeWakeProtocol.LaunchResult;
import io.github.jaeseongs95.sennard.app.messaging.MessageRepository;
import io.github.jaeseongs95.sennard.app.messaging.WakeReservation;
import io.github.jaeseongs95.sennard.app.messaging.WakeReservation.State;
import io.github.jaeseongs95.sennard.app.messaging.WakeReservationService;
import io.github.jaeseongs95.sennard.server.diagnostics.SafeFailure;
import io.github.jaeseongs95.sennard.server.hook.HookActorObserved;
import io.github.jaeseongs95.sennard.server.hook.HostContext;
import io.github.jaeseongs95.sennard.server.hook.HostContext.Basis;
import io.github.jaeseongs95.sennard.server.hook.HostContext.Role;
import io.github.jaeseongs95.sennard.server.hook.HostContext.SessionActor;
import io.github.jaeseongs95.sennard.server.hook.HostEvent;
import io.github.jaeseongs95.sennard.server.hook.HostId;
import io.github.jaeseongs95.sennard.server.hook.HookVerdict;
import io.github.jaeseongs95.sennard.server.mcp.HostCallerAdapter;
import io.github.jaeseongs95.sennard.server.mcp.SelfSession;
import io.github.jaeseongs95.sennard.server.messaging.WakeTransport.RelayState;
import io.github.jaeseongs95.sennard.server.messaging.WakeTransport.RootReport;
import io.github.jaeseongs95.sennard.server.messaging.WakeTransport.RootStatus;
import io.github.jaeseongs95.sennard.server.session.HookSessions;
import io.github.jaeseongs95.sennard.server.session.SessionClosed;
import io.github.jaeseongs95.sennard.service.identity.SessionRegistry;

/** Ephemeral target connections and the native doorbell policy; durable CAS state belongs to WakeReservationService. No host name, environment key or CLI shape is policy here. */
@Service
public class IdleWakeService {
    private static final Logger LOG = LoggerFactory.getLogger(IdleWakeService.class);
    private static final Duration CLIENT_START_GRACE = Duration.ofSeconds(30);
    private static final String PREFIX = "[sennard wake:";
    private static final Pattern MARKER = Pattern.compile("\\[sennard wake:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\]");
    private record Key(String host, String session) {
    }
    private record Managed(String childRef, Key root, HookSessions.Ownership owner, Binding rootBinding) {
    }
    private static final class Binding {
        WakeTransport.Connection connection;
        RootStatus disconnectedStatus = new RootStatus(RelayState.UNAVAILABLE, RootReport.UNREPORTED);
        final BooleanSupplier actorCurrent;
        final Managed managed;
        // Each entry counts outstanding launches and attached child bindings, independently of root wake.
        final Map<WakeTransport.Connection, Integer> managedConnections = new LinkedHashMap<>();
        long registrationTime = System.nanoTime();
        volatile boolean active;
        boolean closed;
        boolean requestedAgain;
        FutureTask<Void> attempt;
        Binding(WakeTransport.Availability availability, BooleanSupplier actorCurrent, boolean active, Managed managed) {
            switch (availability) {
                case WakeTransport.Availability.Ready ready -> connection = ready.connection();
                case WakeTransport.Availability.Disabled ignored -> disconnectedStatus = new RootStatus(RelayState.UNAVAILABLE, RootReport.DISABLED);
                case WakeTransport.Availability.Missing ignored -> disconnectedStatus = new RootStatus(RelayState.UNAVAILABLE, RootReport.MISSING);
            }
            this.actorCurrent = actorCurrent;
            this.active = active;
            this.managed = managed;
        }

        RootStatus rootStatus() {
            return connection == null ? disconnectedStatus : connection.rootStatus();
        }
    }
    /** The only identity a wake input may claim for, after nonce, server incarnation, ownership and live-generation checks. */
    public record Input(String host, String sessionId, String sessionRef, long generation, PeerAddress address) {
    }
    public record Advisory(WakeOutlook.State state, WakeOutlook.Reason reason) {
    }

    private final Map<HostId, WakeTransport> transports;
    private final Map<String, HostCallerAdapter> callers;
    private final Map<Key, Binding> bindings = new ConcurrentHashMap<>();
    private final HookSessions hooks;
    private final SessionRegistry sessions;
    private final WakeReservationService reservations;
    private final MessageRepository messages;
    private final AsyncTaskExecutor executor;
    private final Clock clock;
    private final String serverId;
    private final Object submissionMonitor = new Object();
    private final ArrayDeque<FutureTask<Void>> pending = new ArrayDeque<>();
    private volatile boolean closing;

    IdleWakeService(List<WakeTransport> transports, HookSessions hooks, SessionRegistry sessions, WakeReservationService reservations,
            MessageRepository messages, AsyncTaskExecutor executor, Clock clock, WakeServerIncarnation incarnation) {
        this(transports, hooks, sessions, reservations, messages, executor, clock, incarnation, List.of());
    }

    @Autowired
    public IdleWakeService(List<WakeTransport> transports, HookSessions hooks, SessionRegistry sessions, WakeReservationService reservations,
            MessageRepository messages, @Qualifier("nativeWakeExecutor") AsyncTaskExecutor executor, Clock clock, WakeServerIncarnation incarnation,
            List<HostCallerAdapter> callers) {
        this.transports = transports.stream().collect(Collectors.toUnmodifiableMap(WakeTransport::host, Function.identity()));
        this.callers = callers.stream().collect(Collectors.toUnmodifiableMap(adapter -> adapter.host().sessionHost(), Function.identity()));
        this.hooks = hooks;
        this.sessions = sessions;
        this.reservations = reservations;
        this.messages = messages;
        this.executor = executor;
        this.clock = clock;
        this.serverId = incarnation.value();
    }

    public boolean bootstrap(HostContext context, String registration) {
        return register(context, registration, false);
    }

    boolean registerIdle(HostContext context, String registration) {
        return register(context, registration, true);
    }

    private synchronized boolean register(HostContext context, String registration, boolean idle) {
        WakeTransport transport = transports.get(context.hostLabel());
        if (transport == null || closing || !root(context)) {
            return false;
        }
        BooleanSupplier actorCurrent = actorCurrent(context);
        if (!actorCurrent.getAsBoolean()) {
            return false;
        }
        Key key = key(context);
        Binding current = bindings.get(key);
        var target = new WakeTransport.Target(context.sessionId(), context.workspace(), actorCurrent);
        if (idle && current != null && current.managed == null && current.actorCurrent.getAsBoolean()) {
            return replaceIdleRelay(key, current, transport, registration, target);
        }
        WakeTransport.Availability availability = transport.connect(registration, target);
        if (idle && !(availability instanceof WakeTransport.Availability.Ready)) {
            return false;
        }
        Binding binding = new Binding(availability, actorCurrent, !idle, null);
        Binding previous = bindings.put(key, binding);
        if (previous != null) {
            close(key, previous);
        }
        if (closing || !actorCurrent.getAsBoolean()) {
            if (bindings.remove(key, binding)) {
                close(key, binding);
            }
            return false;
        }
        return availability instanceof WakeTransport.Availability.Ready;
    }

    private boolean replaceIdleRelay(Key key, Binding binding, WakeTransport transport, String registration, WakeTransport.Target target) {
        synchronized (binding) {
            if (binding.closed || bindings.get(key) != binding || !target.actorCurrent().getAsBoolean()) {
                return false;
            }
            var previous = binding.connection;
            if (previous != null) {
                RootStatus status = previous.rootStatus();
                boolean starting = status.relay() == RelayState.OPEN && status.report() == RootReport.UNREPORTED
                        && !previous.managedReady() && System.nanoTime() - binding.registrationTime < CLIENT_START_GRACE.toNanos();
                if (previous.live() || starting) {
                    return false;
                }
            }
            if (!(transport.connect(registration, target) instanceof WakeTransport.Availability.Ready ready)) {
                return false;
            }
            if (bindings.get(key) != binding || !target.actorCurrent().getAsBoolean()) {
                ready.connection().close();
                return false;
            }
            binding.connection = ready.connection();
            if (previous != null && previous != binding.connection && !binding.managedConnections.containsKey(previous)) {
                previous.close();
            }
            binding.registrationTime = System.nanoTime();
            binding.active = false;
            return true;
        }
    }

    /** Launches through an accepted root worker and binds the child before the worker can keep it. */
    LaunchReply launchManagedChild(SelfSession caller, String model, String effort) {
        var owner = requireManagedOwner(caller);
        Key key = new Key(caller.host(), caller.hostSessionId());
        Binding binding = bindings.get(key);
        BooleanSupplier registrationCurrent = () -> !closing && binding != null && bindings.get(key) == binding
                && binding.actorCurrent.getAsBoolean() && hooks.ownership(caller.host(), caller.hostSessionId()).filter(owner::equals).isPresent();
        if (!registrationCurrent.getAsBoolean()) {
            return new LaunchReply(LaunchResult.NOT_SENT, null);
        }
        WakeTransport.Connection connection;
        synchronized (binding) {
            connection = binding.managedConnections.keySet().stream().filter(WakeTransport.Connection::managedReady).findFirst()
                    .orElseGet(() -> binding.connection != null && binding.connection.managedReady() ? binding.connection : null);
            if (connection == null || binding.closed || !registrationCurrent.getAsBoolean()) {
                return new LaunchReply(LaunchResult.NOT_SENT, null);
            }
            binding.managedConnections.merge(connection, 1, Integer::sum);
        }
        try {
            LaunchReply reply = connection.createChild(model, effort, registrationCurrent,
                    childId -> sessions.bindManagedChild(caller.host(), caller.hostSessionId(), owner.sessionRef(), owner.generation(), childId));
            if (reply.result() == LaunchResult.CREATED
                    && (!registrationCurrent.getAsBoolean() || !sessions.isLiveGeneration(owner.sessionRef(), owner.generation())
                            || !registerManaged(key, binding, connection, owner, reply.childId(), registrationCurrent))) {
                return new LaunchReply(LaunchResult.UNKNOWN, null);
            }
            return reply;
        } finally {
            releaseManaged(binding, connection);
        }
    }

    private static void releaseManaged(Binding root, WakeTransport.Connection connection) {
        synchronized (root) {
            Integer count = root.managedConnections.get(connection);
            if (count == null) {
                return;
            }
            if (count == 1) {
                root.managedConnections.remove(connection);
                if (root.connection != connection) {
                    connection.close();
                }
            } else {
                root.managedConnections.put(connection, count - 1);
            }
        }
    }

    HookSessions.Ownership requireManagedOwner(SelfSession caller) {
        if (caller.provenance() != SelfSession.Provenance.HOST_OBSERVED || !caller.actor().rootEstablished()
                || caller.hostSessionId() == null || (caller.actor().basis() != Basis.OBSERVED && !matchedInferredManagedRoot(caller))) {
            throw new BindingException(BindingCode.BINDING_REQUIRED);
        }
        var owner = new HookSessions.Ownership(caller.sessionRef(), caller.generation());
        if (hooks.ownership(caller.host(), caller.hostSessionId()).filter(owner::equals).isEmpty()
                || !sessions.isLiveGeneration(owner.sessionRef(), owner.generation())) {
            throw new BindingException(BindingCode.BINDING_REQUIRED);
        }
        return owner;
    }

    private boolean matchedInferredManagedRoot(SelfSession caller) {
        HostCallerAdapter adapter = callers.get(caller.host());
        return caller.callId() != null && adapter != null && adapter.acceptsInferredManagedRoot();
    }

    private boolean registerManaged(Key root, Binding rootBinding, WakeTransport.Connection connection,
            HookSessions.Ownership owner, String childId, BooleanSupplier rootCurrent) {
        return sessions.findRef(root.host(), childId).map(childRef -> {
            Key child = new Key(root.host(), childId);
            var managed = new Binding(new WakeTransport.Availability.Ready(connection), rootCurrent, false, new Managed(childRef, root, owner, rootBinding));
            synchronized (rootBinding) {
                if (rootBinding.closed || !rootCurrent.getAsBoolean() || !connection.managedReady()
                        || bindings.putIfAbsent(child, managed) != null) {
                    return false;
                }
                rootBinding.managedConnections.merge(connection, 1, Integer::sum);
                return true;
            }
        }).orElse(false);
    }

    public static String marker(String nonce) {
        return PREFIX + nonce + "]";
    }

    /** Reserved wire syntax is never native user authority, even when forged, replayed or from an older server. */
    public static Optional<String> markerNonce(String prompt) {
        if (prompt != null && MARKER.matcher(prompt).matches()) {
            return Optional.of(prompt.substring(PREFIX.length(), prompt.length() - 1));
        }
        return Optional.empty();
    }

    /** Caller owns the hook/source-receipt transaction, so a claim failure rolls back consumption. Old-server nonces stay unconsumable. */
    public Optional<Input> consume(HostContext context, String nonce) {
        Key key = key(context);
        Binding binding = bindings.get(key);
        if (!boundActor(context, binding)) {
            return Optional.empty();
        }
        Optional<Input> input = liveInput(key, binding).filter(target -> reservations.consume(target.sessionRef(), target.generation(), nonce, serverId));
        if (input.isPresent()) {
            // The caller holds a database transaction. Never acquire the monitor here: nativeInput/start/close take it before their DB CAS.
            binding.active = true;
        }
        return input;
    }

    /** Read-only eligibility before claiming a body; consume repeats ownership and liveness checks after the claim. */
    public Optional<Input> verify(HostContext context, String nonce) {
        Key key = key(context);
        Binding binding = bindings.get(key);
        if (!boundActor(context, binding)) {
            return Optional.empty();
        }
        return liveInput(key, binding)
                .filter(target -> reservations.status(target.sessionRef()).filter(row -> row.generation() == target.generation()
                        && row.nonce().equals(nonce) && row.serverId().equals(serverId)
                        && (row.state() == State.STARTED || row.state() == State.SUBMITTED || row.state() == State.UNKNOWN)).isPresent());
    }

    private Optional<Input> liveInput(Key key, Binding binding) {
        if (closing || bindings.get(key) != binding || !binding.actorCurrent.getAsBoolean()) {
            return Optional.empty();
        }
        return owner(key, binding).filter(found -> sessions.isLiveGeneration(found.sessionRef(), found.generation())).map(found -> {
            Managed managed = binding.managed;
            return managed == null ? new Input(key.host(), key.session(), found.sessionRef(), found.generation(), PeerAddress.session(found.sessionRef()))
                    : new Input(key.host(), managed.root().session(), managed.childRef(), found.generation(), new PeerAddress(found.sessionRef(), key.session()));
        });
    }

    private Optional<HookSessions.Ownership> owner(Key key, Binding binding) {
        return binding.managed == null ? hooks.ownership(key.host(), key.session()) : Optional.of(binding.managed.owner());
    }

    private static boolean boundActor(HostContext context, Binding binding) {
        if (binding == null) {
            return false;
        }
        if (binding.managed == null) {
            return root(context);
        }
        return context.actor().role() == Role.CHILD && context.actor().basis() == Basis.OBSERVED && context.sessionId().equals(context.actor().agentKey());
    }

    /** Normal native inputs keep their original domain path and deferral; they only fence an attempt that has not crossed STARTED. */
    public void nativeInput(HostContext context) {
        Key key = key(context);
        Binding binding = bindings.get(key);
        if (!boundActor(context, binding)) {
            return;
        }
        synchronized (binding) {
            binding.active = true;
            liveInput(key, binding)
                    .ifPresent(target -> {
                        reservations.cancelReserved(target.sessionRef(), target.generation());
                        reservations.nativeActivity(target.sessionRef(), target.generation());
                    });
        }
    }

    public void boundary(HostEvent event, HookVerdict verdict) {
        Key key = key(event.context());
        Binding binding = bindings.get(key);
        if (!boundActor(event.context(), binding)) {
            return;
        }
        if (event instanceof HostEvent.TurnEnded ended) {
            synchronized (binding) {
                binding.active = !ended.continuable() || verdict instanceof HookVerdict.ContinueTurn || verdict instanceof HookVerdict.AddContext;
            }
            request(key, binding);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(WakeWaiters.MessageQueued queued) {
        Key key = new Key(queued.host(), queued.actorId() == null ? queued.hostSessionId() : queued.actorId());
        Binding binding = addressedBinding(queued.host(), queued.hostSessionId(), queued.actorId());
        if (binding != null) {
            request(key, binding);
        }
    }

    private Binding addressedBinding(String host, String session, String actorId) {
        Binding binding = bindings.get(new Key(host, actorId == null ? session : actorId));
        if (binding == null) {
            return null;
        }
        return (actorId == null ? binding.managed == null : binding.managed != null && binding.managed.root().session().equals(session)) ? binding : null;
    }

    @EventListener
    public void on(HookSessions.OwnerReady ready) {
        relayAvailable(ready.host(), ready.hostSessionId());
    }

    void relayAvailable(String host, String session) {
        Key key = new Key(host, session);
        Binding binding = bindings.get(key);
        if (binding != null) {
            request(key, binding);
        }
    }

    private void request(Key key, Binding binding) {
        FutureTask<Void> task;
        synchronized (binding) {
            var connection = binding.connection;
            if (closing || binding.closed || binding.active || bindings.get(key) != binding || !binding.actorCurrent.getAsBoolean()
                    || connection == null || !connectionReady(binding, connection)) {
                return;
            }
            if (binding.attempt != null) {
                binding.requestedAgain = true;
                return;
            }
            task = new FutureTask<>(() -> {
                try {
                    dispatch(key, binding, connection);
                } catch (RuntimeException failure) {
                    // Async boundary: queueing remains committed; a failed reservation is never reported as a wake success or retried here.
                    LOG.warn("NATIVE_WAKE_ATTEMPT_FAILED", SafeFailure.capture(failure));
                } finally {
                    boolean requestedAgain;
                    synchronized (binding) {
                        binding.attempt = null;
                        requestedAgain = binding.requestedAgain;
                        binding.requestedAgain = false;
                    }
                    // An event may arrive after the queue snapshot but before this task completes. Recheck once; the durable CAS still fences every uncertain attempt.
                    if (requestedAgain) {
                        request(key, binding);
                    }
                }
            }, null);
            binding.attempt = task;
        }
        // Never acquire submissionMonitor while holding a binding monitor, including with an inline executor.
        synchronized (submissionMonitor) {
            if (closing || task.isCancelled()) {
                task.cancel(false);
                return;
            }
            try {
                executor.execute(() -> runAndDrain(task));
            } catch (RejectedExecutionException unavailable) {
                // One retained Future per binding coalesces events until an accepted worker has capacity. No reservation has crossed STARTED yet.
                pending.addLast(task);
                LOG.warn("NATIVE_WAKE_EXECUTOR_UNAVAILABLE", SafeFailure.capture(unavailable));
            }
        }
    }

    private void runAndDrain(FutureTask<Void> first) {
        FutureTask<Void> task = first;
        boolean interrupted = false;
        try {
            while (task != null && !closing) {
                task.run();
                // Cancellation interrupts only this target's attempt. Give the next target the same clean task boundary as the managed pool.
                interrupted |= Thread.interrupted();
                synchronized (submissionMonitor) {
                    task = pending.pollFirst();
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void dispatch(Key key, Binding binding, WakeTransport.Connection connection) {
        Optional<Input> current = liveInput(key, binding);
        if (current.isEmpty()) {
            return;
        }
        Input target = current.orElseThrow();
        if (!connectionReady(binding, connection)
                || messages.countClaimable(target.address().sessionRef(), target.address().actorId(), clock.instant()) == 0) {
            return;
        }
        // Lazy until the prepared business store is actually used; a web-only application startup must not query an unprepared store.
        reservations.recover(target.sessionRef(), target.generation(), serverId);
        Optional<WakeReservation> reserved = reservations.reserve(target.sessionRef(), target.generation(), serverId);
        if (reserved.isEmpty()) {
            return;
        }
        WakeReservation reservation = reserved.orElseThrow();
        synchronized (binding) {
            if (closing || binding.closed || binding.active || bindings.get(key) != binding || binding.connection != connection || !binding.actorCurrent.getAsBoolean()
                    || Thread.currentThread().isInterrupted()) {
                reservations.cancelReserved(target.sessionRef(), target.generation());
                return;
            }
            // The durable CAS precedes the offer. Session end cancels the attempt, and a handed-off marker remains uncertain until observed.
            if (!reservations.start(reservation)) {
                return;
            }
        }
        State outcome = State.UNKNOWN;
        try {
            if (!Thread.currentThread().isInterrupted()) {
                String signal = marker(reservation.nonce());
                WakeTransport.Outcome attempted = binding.managed == null ? connection.wake(key.session(), signal)
                        : connection.wakeManaged(key.session(), signal, () -> bindings.get(key) == binding && binding.actorCurrent.getAsBoolean() && !binding.active);
                outcome = switch (attempted) {
                    case WakeTransport.Outcome.Submitted ignored -> State.SUBMITTED;
                    case WakeTransport.Outcome.NotSent ignored -> State.NOT_SENT;
                    case WakeTransport.Outcome.Unknown ignored -> State.UNKNOWN;
                };
            }
        } finally {
            // A marker can arrive before the process returns. WHERE state=STARTED preserves OBSERVED and never overwrites the winning claim.
            reservations.finish(reservation, outcome);
        }
    }

    private static boolean connectionReady(Binding binding, WakeTransport.Connection connection) {
        return binding.managed == null ? connection.live() : connection.managedReady();
    }

    public Optional<Advisory> outlook(String host, String session) {
        return outlook(host, session, null);
    }

    Optional<Advisory> outlook(String host, String session, String actorId) {
        Binding binding = addressedBinding(host, session, actorId);
        if (binding == null || closing || !binding.actorCurrent.getAsBoolean()) {
            return Optional.empty();
        }
        synchronized (binding) {
            if (binding.closed) {
                return Optional.empty();
            }
            RootStatus status = binding.rootStatus();
            if (status.relay() == RelayState.OWNER_LOST) {
                return Optional.empty();
            }
            if (binding.managed == null && status.relay() != RelayState.CLOSED) {
                if (status.report() == RootReport.DISABLED) {
                    return Optional.of(new Advisory(WakeOutlook.State.UNSUPPORTED, WakeOutlook.Reason.NO_IDLE_WAKE));
                }
                if (status.report() == RootReport.MISSING) {
                    return Optional.of(new Advisory(WakeOutlook.State.DEGRADED, WakeOutlook.Reason.WAKE_PREREQUISITE_MISSING));
                }
            }
            if (binding.connection == null || !connectionReady(binding, binding.connection)) {
                return Optional.of(new Advisory(WakeOutlook.State.NO_LIVE_RELAY, WakeOutlook.Reason.RELAY_LEASE_MISSING));
            }
            if (binding.active) {
                return Optional.of(new Advisory(WakeOutlook.State.DEGRADED, WakeOutlook.Reason.RECIPIENT_ACTIVE));
            }
        }
        return liveInput(new Key(host, actorId == null ? session : actorId), binding).map(target -> {
            Optional<WakeReservation> prior = reservations.status(target.sessionRef()).filter(row -> row.generation() == target.generation());
            if (prior.isPresent()) {
                switch (prior.orElseThrow().state()) {
                    case RESERVED, STARTED -> {
                        return new Advisory(WakeOutlook.State.DEGRADED, WakeOutlook.Reason.WAKE_IN_FLIGHT);
                    }
                    case SUBMITTED, UNKNOWN -> {
                        return new Advisory(WakeOutlook.State.DEGRADED, WakeOutlook.Reason.WAKE_UNANSWERED);
                    }
                    case NOT_SENT -> {
                        if (prior.orElseThrow().retryAfter().isAfter(clock.instant())) {
                            return new Advisory(WakeOutlook.State.DEGRADED, WakeOutlook.Reason.RETRY_BACKOFF);
                        }
                    }
                    default -> {
                    }
                }
            }
            return new Advisory(WakeOutlook.State.AVAILABLE, WakeOutlook.Reason.RELAY_LIVE);
        }).or(() -> Optional.of(new Advisory(WakeOutlook.State.NO_LIVE_RELAY, WakeOutlook.Reason.RELAY_LEASE_MISSING)));
    }

    @EventListener
    public void on(HookActorObserved observed) {
        Key key = new Key(observed.host().sessionHost(), observed.conversation());
        Binding binding = bindings.get(key);
        if (binding != null && !binding.actorCurrent.getAsBoolean() && bindings.remove(key, binding)) {
            close(key, binding);
        }
    }

    @EventListener
    public void on(HookSessions.SessionLeft left) {
        Key key = new Key(left.host(), left.hostSessionId());
        Binding binding = bindings.remove(key);
        if (binding != null) {
            close(key, binding);
        }
    }

    @EventListener
    public void on(SessionClosed closed) {
        bindings.forEach((key, binding) -> owner(key, binding)
                .filter(owner -> owner.sessionRef().equals(closed.sessionRef()) && owner.generation() == closed.generation())
                .ifPresent(owner -> {
                    if (bindings.remove(key, binding)) {
                        close(key, binding);
                    }
                }));
    }

    @Scheduled(fixedDelay = 5_000)
    void reapRetiredRelays() {
        bindings.values().forEach(binding -> {
            WakeTransport.Connection retired;
            synchronized (binding) {
                if (binding.managed == null) {
                    binding.managedConnections.keySet().removeIf(connection -> connection.rootStatus().relay() != RelayState.OPEN);
                }
                if (binding.connection == null) {
                    return;
                }
                RootStatus status = binding.rootStatus();
                if (status.relay() == RelayState.OPEN) {
                    return;
                }
                // The identity remains usable for a marker offered before the connection expired.
                retired = binding.connection;
                binding.disconnectedStatus = status;
                binding.connection = null;
            }
            if (binding.managed != null) {
                releaseManaged(binding.managed.rootBinding(), retired);
            }
        });
    }

    private void close(Key key, Binding binding) {
        WakeTransport.Connection connection;
        FutureTask<Void> task;
        synchronized (binding) {
            if (binding.closed) {
                return;
            }
            binding.closed = true;
            binding.requestedAgain = false;
            connection = binding.connection;
            binding.connection = null;
            if (binding.managed == null) {
                if (connection != null && !binding.managedConnections.containsKey(connection)) {
                    connection.close();
                }
                binding.managedConnections.keySet().forEach(WakeTransport.Connection::close);
                binding.managedConnections.clear();
            }
            task = binding.attempt;
            if (task != null) {
                task.cancel(true);
            }
        }
        synchronized (submissionMonitor) {
            pending.remove(task);
        }
        if (binding.managed == null) {
            hooks.ownership(key.host(), key.session()).ifPresent(owner -> reservations.cancelReserved(owner.sessionRef(), owner.generation()));
            bindings.forEach((childKey, child) -> {
                if (child.managed != null && child.managed.rootBinding() == binding && bindings.remove(childKey, child)) {
                    close(childKey, child);
                }
            });
        } else {
            if (connection != null) {
                releaseManaged(binding.managed.rootBinding(), connection);
            }
            reservations.cancelReserved(binding.managed.childRef(), binding.managed.owner().generation());
        }
    }

    @EventListener
    public void on(ContextClosedEvent closed) {
        closing = true;
        bindings.forEach(this::close);
        bindings.clear();
        synchronized (submissionMonitor) {
            pending.clear();
        }
    }

    private static Key key(HostContext context) {
        return new Key(context.hostLabel().sessionHost(), context.sessionId());
    }

    private static boolean root(HostContext context) {
        return context.actor().rootEstablished();
    }

    private BooleanSupplier actorCurrent(HostContext context) {
        HostCallerAdapter adapter = callers.get(context.hostLabel().sessionHost());
        SessionActor initial = adapter == null ? null : adapter.currentHookActor(context.sessionId());
        if (initial == null) {
            return WakeTransport.Target.HOOK_ACTOR;
        }
        // A new observation after cache loss may establish the same actor with a new evidence instance.
        return () -> context.actor() == initial && adapter.currentHookActor(context.sessionId()) == initial;
    }
}
