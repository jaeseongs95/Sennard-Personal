// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.messaging;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.hookclient.NativeWakeProtocol;
import io.github.jaeseongs95.sennard.hookclient.NativeWakeProtocol.Capability;
import io.github.jaeseongs95.sennard.hookclient.NativeWakeProtocol.Launch;
import io.github.jaeseongs95.sennard.hookclient.NativeWakeProtocol.LaunchReply;
import io.github.jaeseongs95.sennard.hookclient.NativeWakeProtocol.LaunchResult;
import io.github.jaeseongs95.sennard.hookclient.NativeWakeProtocol.RootUnavailable;
import io.github.jaeseongs95.sennard.server.hook.HostId;
import io.github.jaeseongs95.sennard.server.messaging.WakeTransport.RelayState;
import io.github.jaeseongs95.sennard.server.messaging.WakeTransport.RootReport;
import io.github.jaeseongs95.sennard.server.messaging.WakeTransport.RootStatus;

/** One volatile relay per accepted client registration. The durable reservation lives in IdleWakeService. */
@Component
public final class NativeWakeRelay {
    private static final Duration POLL_HOLD = Duration.ofSeconds(10);
    private static final Duration CONTACT_LEASE = Duration.ofSeconds(30);
    private static final Duration OUTCOME_WAIT = Duration.ofSeconds(15);
    private final Map<String, Channel> channels = new ConcurrentHashMap<>();
    private final LongSupplier nanoTime;

    public NativeWakeRelay() {
        this(System::nanoTime);
    }

    NativeWakeRelay(LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
    }

    public WakeTransport transport(HostId host) {
        return transport(host, false);
    }

    public WakeTransport transport(HostId host, boolean preassignedChildId) {
        return new WakeTransport() {
            @Override
            public HostId host() {
                return host;
            }

            @Override
            public Availability connect(String registration, Target target) {
                if (!NativeWakeProtocol.validKey(registration)) {
                    return new Availability.Missing("CLIENT_REGISTRATION_MISSING");
                }
                Channel channel = new Channel(registration, host.sessionHost(), target, preassignedChildId);
                return channels.putIfAbsent(registration, channel) == null ? new Availability.Ready(channel)
                        : new Availability.Missing("CLIENT_REGISTRATION_IN_USE");
            }
        };
    }

    Channel find(String key) {
        Channel channel = NativeWakeProtocol.validKey(key) ? channels.get(key) : null;
        return channel != null && channel.open() ? channel : null;
    }

    enum Poll {
        CLOSED, BUSY, FIRST_CONTACT, CONTINUING
    }

    record Reply(boolean closed, String marker, String childId, Launch launch) {
    }

    private static final class Offer<T> {
        final Reply reply;
        final BooleanSupplier authorized;
        final Predicate<T> acceptResult;
        boolean ready;
        boolean taken;
        boolean completing;
        T result;

        Offer(Reply reply, BooleanSupplier authorized, Predicate<T> acceptResult) {
            this.reply = reply;
            this.authorized = authorized;
            this.acceptResult = acceptResult;
        }
    }

    final class Channel implements WakeTransport.Connection {
        private final String key;
        private final String host;
        private final String session;
        private final BooleanSupplier actorCurrent;
        private final boolean preassignedChildId;
        private RelayState relayState = RelayState.OPEN;
        private boolean polling;
        private Capability capability;
        private RootReport rootReport = RootReport.UNREPORTED;
        private long contactAt = nanoTime.getAsLong();
        private Offer<NativeWakeProtocol.Result> pendingWake;
        private Offer<LaunchReply> pendingLaunch;

        private Channel(String key, String host, WakeTransport.Target target, boolean preassignedChildId) {
            this.key = key;
            this.host = host;
            this.session = target.hostSessionId();
            this.actorCurrent = target.actorCurrent();
            this.preassignedChildId = preassignedChildId;
        }

        String host() {
            return host;
        }

        String session() {
            return session;
        }

        boolean actorCurrent() {
            return actorCurrent.getAsBoolean();
        }

        @Override
        public synchronized boolean live() {
            return registered() && capability.rootWake();
        }

        @Override
        public synchronized boolean registered() {
            return open() && capability != null && capability != Capability.NONE;
        }

        @Override
        public synchronized boolean managedReady() {
            return registered() && capability.managedChild();
        }

        @Override
        public synchronized RootStatus rootStatus() {
            open(); // Retire an expired or superseded registration at the point it is observed.
            return new RootStatus(relayState, rootReport);
        }

        private synchronized boolean open() {
            if (relayState == RelayState.OPEN) {
                if (!actorCurrent()) {
                    retire(RelayState.OWNER_LOST);
                } else if (nanoTime.getAsLong() - contactAt >= CONTACT_LEASE.toNanos()) {
                    retire(RelayState.EXPIRED);
                }
            }
            return relayState == RelayState.OPEN;
        }

        Poll beginPoll(Capability advertised) {
            return beginPoll(advertised, null);
        }

        synchronized Poll beginPoll(Capability advertised, RootUnavailable unavailable) {
            if (!open()) {
                return Poll.CLOSED;
            }
            RootReport report = switch (unavailable) {
                case MISSING -> RootReport.MISSING;
                case DISABLED -> RootReport.DISABLED;
                case null -> advertised.rootWake() ? RootReport.AVAILABLE : RootReport.UNREPORTED;
            };
            if (capability != null && (capability != advertised || rootReport != report)) {
                close();
                return Poll.CLOSED;
            }
            if (polling || inFlight(pendingWake) || inFlight(pendingLaunch)) {
                return Poll.BUSY;
            }
            Poll next = capability != null && capability != Capability.NONE ? Poll.CONTINUING : Poll.FIRST_CONTACT;
            polling = true;
            capability = advertised;
            rootReport = report;
            contactAt = nanoTime.getAsLong();
            return next;
        }

        private boolean inFlight(Offer<?> offer) {
            return offer != null && offer.taken && offer.result == null;
        }

        synchronized Reply awaitOffer() throws InterruptedException {
            long deadline = System.nanoTime() + POLL_HOLD.toNanos();
            while (open()) {
                if (pendingWake != null && !pendingWake.taken && pendingWake.result == null) {
                    if (pendingWake.authorized.getAsBoolean()) {
                        pendingWake.taken = true;
                        return pendingWake.reply;
                    }
                    pendingWake.result = NativeWakeProtocol.Result.NOT_SENT;
                    notifyAll();
                }
                if (pendingLaunch != null && pendingLaunch.ready && !pendingLaunch.taken && pendingLaunch.result == null) {
                    if (pendingLaunch.authorized.getAsBoolean()) {
                        pendingLaunch.taken = true;
                        return pendingLaunch.reply;
                    }
                    pendingLaunch.result = new LaunchReply(LaunchResult.NOT_SENT, null);
                    notifyAll();
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return new Reply(false, null, null, null);
                }
                TimeUnit.NANOSECONDS.timedWait(this, remaining);
            }
            return new Reply(true, null, null, null);
        }

        synchronized void endPoll() {
            polling = false;
        }

        synchronized boolean complete(String marker, String childId, NativeWakeProtocol.Result result) {
            if (!open() || !inFlight(pendingWake) || !pendingWake.reply.marker().equals(marker)
                    || !Objects.equals(pendingWake.reply.childId(), childId)) {
                return false;
            }
            pendingWake.result = result;
            notifyAll();
            return true;
        }

        boolean completeLaunch(String id, LaunchReply result) {
            var matched = acceptLaunchCompletion(id, result);
            if (matched.isEmpty()) {
                return false;
            }
            var offer = matched.orElseThrow();
            boolean accepted = false;
            boolean confirmed;
            try {
                // Binding can use the database. Keep the poll and channel-close monitor free while it commits.
                accepted = offer.authorized.getAsBoolean() && offer.acceptResult.test(result);
            } finally {
                confirmed = publishLaunchResult(offer, result, accepted);
            }
            return confirmed;
        }

        private synchronized Optional<Offer<LaunchReply>> acceptLaunchCompletion(String id, LaunchReply result) {
            if (!open() || !inFlight(pendingLaunch) || pendingLaunch.completing
                    || !pendingLaunch.reply.launch().id().equals(id) || session.equals(result.childId())
                    || (preassignedChildId && result.result() == LaunchResult.CREATED && !id.equals(result.childId()))) {
                return Optional.empty();
            }
            pendingLaunch.completing = true;
            return Optional.of(pendingLaunch);
        }

        private synchronized boolean publishLaunchResult(Offer<LaunchReply> offer, LaunchReply result, boolean accepted) {
            if (pendingLaunch != offer) {
                return false;
            }
            boolean confirmed = accepted && open() && offer.authorized.getAsBoolean();
            offer.result = confirmed ? result : new LaunchReply(LaunchResult.UNKNOWN, null);
            notifyAll();
            return confirmed;
        }

        @Override
        public LaunchReply createChild(String model, String effort, BooleanSupplier ownerCurrent, Predicate<String> bindChild) {
            Offer<LaunchReply> offer = reserveLaunch(model, effort, ownerCurrent, bindChild);
            if (offer == null) {
                return new LaunchReply(LaunchResult.NOT_SENT, null);
            }
            boolean assigned = false;
            try {
                // A host with an assigned ID can fire SessionStart as soon as it receives the offer.
                // Commit its identity before making the offer visible, without holding the channel monitor.
                assigned = !preassignedChildId || bindChild.test(offer.reply.launch().id());
            } finally {
                if (!assigned) {
                    clearLaunch();
                }
            }
            return assigned ? awaitLaunchResult(offer) : new LaunchReply(LaunchResult.NOT_SENT, null);
        }

        private synchronized Offer<LaunchReply> reserveLaunch(String model, String effort, BooleanSupplier ownerCurrent, Predicate<String> bindChild) {
            if (!registered() || !capability.managedChild() || pendingWake != null || pendingLaunch != null || !ownerCurrent.getAsBoolean()) {
                return null;
            }
            Launch launch = new Launch(UUID.randomUUID().toString(), model, effort);
            pendingLaunch = new Offer<>(new Reply(false, null, null, launch), ownerCurrent,
                    result -> preassignedChildId || result.result() != LaunchResult.CREATED || bindChild.test(result.childId()));
            return pendingLaunch;
        }

        private synchronized LaunchReply awaitLaunchResult(Offer<LaunchReply> offer) {
            try {
                if (!registered() || !offer.authorized.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                    return new LaunchReply(LaunchResult.NOT_SENT, null);
                }
                offer.ready = true;
                awaitResult(offer);
                if (offer.result != null && offer.authorized.getAsBoolean()) {
                    return offer.result;
                }
                return new LaunchReply(offer.taken ? LaunchResult.UNKNOWN : LaunchResult.NOT_SENT, null);
            } finally {
                clearLaunch();
            }
        }

        private synchronized void clearLaunch() {
            pendingLaunch = null;
            notifyAll();
        }

        @Override
        public synchronized WakeTransport.Outcome wake(String hostSessionId, String marker) {
            if (!session.equals(hostSessionId) || !live() || pendingWake != null || pendingLaunch != null) {
                return new WakeTransport.Outcome.NotSent("CLIENT_RELAY_NOT_READY");
            }
            return submitWake(marker, null, actorCurrent);
        }

        @Override
        public synchronized WakeTransport.Outcome wakeManaged(String childId, String marker, BooleanSupplier authorized) {
            if (!NativeWakeProtocol.validConversation(childId) || session.equals(childId) || !NativeWakeProtocol.validMarker(marker)
                    || !awaitManagedSlot(authorized)) {
                return new WakeTransport.Outcome.NotSent("CLIENT_MANAGED_RELAY_NOT_READY");
            }
            return submitWake(marker, childId, authorized);
        }

        private boolean awaitManagedSlot(BooleanSupplier authorized) {
            long deadline = System.nanoTime() + OUTCOME_WAIT.toNanos();
            try {
                while (managedReady() && authorized.getAsBoolean()) {
                    if (pendingWake == null && pendingLaunch == null) {
                        return true;
                    }
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        break;
                    }
                    TimeUnit.NANOSECONDS.timedWait(this, remaining);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return false;
        }

        private WakeTransport.Outcome submitWake(String marker, String childId, BooleanSupplier authorized) {
            var offer = new Offer<NativeWakeProtocol.Result>(new Reply(false, marker, childId, null), authorized, ignored -> true);
            pendingWake = offer;
            try {
                awaitResult(offer);
            } finally {
                pendingWake = null;
                notifyAll();
            }
            if (offer.result == null) {
                return offer.taken ? new WakeTransport.Outcome.Unknown("CLIENT_OUTCOME_UNAVAILABLE")
                        : new WakeTransport.Outcome.NotSent("CLIENT_MARKER_NOT_TAKEN");
            }
            return switch (offer.result) {
                case SUBMITTED -> new WakeTransport.Outcome.Submitted();
                case NOT_SENT -> new WakeTransport.Outcome.NotSent("CLIENT_NOT_SENT");
                case UNKNOWN -> new WakeTransport.Outcome.Unknown("CLIENT_OUTCOME_UNKNOWN");
            };
        }

        private void awaitResult(Offer<?> offer) {
            notifyAll();
            long deadline = System.nanoTime() + OUTCOME_WAIT.toNanos();
            try {
                while (open() && offer.result == null) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        break;
                    }
                    TimeUnit.NANOSECONDS.timedWait(this, remaining);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public synchronized void close() {
            if (open()) {
                retire(RelayState.CLOSED);
            }
        }

        private void retire(RelayState reason) {
            relayState = reason;
            channels.remove(key, this);
            notifyAll();
        }
    }
}
