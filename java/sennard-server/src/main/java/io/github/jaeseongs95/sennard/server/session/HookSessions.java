// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.session;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.domain.error.ConflictCode;
import io.github.jaeseongs95.sennard.server.config.SennardProperties;
import io.github.jaeseongs95.sennard.service.identity.HostWatch;
import io.github.jaeseongs95.sennard.service.session.LeaseOutcome;
import io.github.jaeseongs95.sennard.server.session.SessionLifecycle.Registered;
import io.github.jaeseongs95.sennard.service.session.SessionKind;

/**
 * What a host's hooks say about its sessions, and the owner of the sessions whose host process a hook named: the session start hook
 * carries the process ids above the Sennard launcher that ran it ({@code X-Sennard-Host-Pids}, the nearest first), this class watches the host's process
 * through {@link HostWatch} and ends the session the moment it ends, without waiting for an end hook or for the lease to lapse. Everything it asks of
 * the server goes through {@link SessionLifecycle}. A session whose hook named no process, or whose processes cannot be watched, stays an unowned
 * generation kept alive by its hooks and tool calls.
 *
 * <p>Which of the candidates is the host is decided after a grace time: the host started the hook, so a shell the host ran it through, or a launcher
 * of the Sennard client that sits above it, ends together with the hook, and the host is the nearest candidate that still lives then. The session is
 * registered at once as an unowned generation and becomes owned when that is decided; if every candidate ended, the host ended and so does the session.
 *
 * <p>The map is only what this JVM is watching; every fact that must survive a restart is in the session tables, and the restart reads it back
 * ({@link #restoreAfterStart()}). Removing an entry from the map is the claim on ending it, so a hook's end and the host's exit that arrive
 * together end the session once.
 */
@Component
public class HookSessions implements DisposableBean {
    private static final Logger LOG = LoggerFactory.getLogger(HookSessions.class);
    private static final String PROCESS_KEY_PREFIX = "local-v1:";
    private static final String TRANSPORT = "http";
    // ceiling: no host can be woken through this column yet; the wake routes come with the messaging tools, which set it per host.
    private static final String NO_DELIVERY = "{\"wakeVisibility\":\"none\",\"canWakeSilently\":false,\"idleWake\":\"none\",\"supportedInjection\":[]}";

    private record Key(String host, String hostSessionId) {
    }

    /** A session ended here, by its host's end hook or because the host's process ended. */
    public record SessionLeft(String host, String hostSessionId) {
    }

    /** The actual locally watched owner, not an inferred hook generation. */
    public record Ownership(String sessionRef, long generation) {
    }

    public record OwnerReady(String host, String hostSessionId) {
    }

    public Optional<Ownership> ownership(String host, String hostSessionId) {
        return Optional.ofNullable(watched.get(new Key(host, hostSessionId))).map(entry -> new Ownership(entry.sessionRef(), entry.generation()));
    }

    /** One session this JVM owns the lease of. */
    private record Watched(String sessionRef, long generation, UUID relayId, String processKey, HostWatch watch) {
    }

    /** A process the start hook named, watched until the owner is decided. */
    private record Candidate(long pid, HostWatch watch) {
    }

    /** The wait for the owner of one session: its candidates, and the decision that is scheduled. */
    private static final class Undecided {
        final List<Candidate> candidates;
        final long receivedAtMs;
        volatile ScheduledFuture<?> decision;

        Undecided(List<Candidate> candidates, long receivedAtMs) {
            this.candidates = candidates;
            this.receivedAtMs = receivedAtMs;
        }

        /** Drops the wait: the decision is cancelled (one that already runs finds the wait gone) and every candidate stops being watched. */
        void abandon() {
            ScheduledFuture<?> scheduled = decision;
            if (scheduled != null) {
                scheduled.cancel(false);
            }
            candidates.forEach(candidate -> candidate.watch().close());
        }
    }

    private final Map<Key, Watched> watched = new ConcurrentHashMap<>();
    /** Whoever removes or replaces an entry owns the cleanup of its candidates. */
    private final Map<Key, Undecided> undecided = new ConcurrentHashMap<>();
    /** The kind of each session this JVM last recorded, so the store is asked once per answer and not once per hook. */
    private final Map<Key, SessionKind> kinds = new ConcurrentHashMap<>();
    /** Fences the owner of the previous run: its relay ids are no longer the current ones (R2). */
    private final UUID instance = UUID.randomUUID();
    private final SessionLifecycle lifecycle;
    private final ApplicationEventPublisher events;
    private final TaskScheduler scheduler;
    private final Clock clock;
    private final Duration grace;

    /** The grace time ({@code sennard.hooks.host-grace-ms}) is how long after a session start the host is decided among the candidates; the shells and launchers above the hook end within it. */
    public HookSessions(SessionLifecycle lifecycle, ApplicationEventPublisher events, TaskScheduler scheduler, Clock clock, SennardProperties properties) {
        this.lifecycle = lifecycle;
        this.events = events;
        this.scheduler = scheduler;
        this.clock = clock;
        this.grace = properties.hooks().hostGraceMs();
    }

    /**
     * A hook says the session started, or a compaction or resume restarted it. The session is registered or kept alive as an unowned generation at
     * once. When the hook named processes above its launcher, those that can be watched are watched and the host is decided among them after the
     * grace time ({@link #decideOwner}); a wait that an earlier start of the same session left is replaced. One that names none, or none that can be
     * watched, leaves the session as it is.
     *
     * @param hostCandidates the process ids above the launcher that ran the hook, the nearest first; empty when the hook named none
     * @param receivedAtMs when the server received the hook
     */
    public void started(String host, String hostSessionId, List<Long> hostCandidates, long receivedAtMs) {
        Key key = new Key(host, hostSessionId);
        abandonUndecided(key);
        lifecycle.noteHook(new SessionLifecycle.HookSeen(host, hostSessionId, receivedAtMs));
        List<Candidate> bound = new ArrayList<>();
        for (long pid : hostCandidates) {
            try {
                bound.add(new Candidate(pid, HostWatch.bind(pid, receivedAtMs, this::checkExits)));
            } catch (HostWatch.BindFailed cannotWatch) {
                LOG.warn("HOST_WATCH_UNAVAILABLE {}", cannotWatch.reason());
            }
        }
        if (bound.isEmpty()) {
            return;
        }
        Undecided waiting = new Undecided(List.copyOf(bound), receivedAtMs);
        Undecided replaced = undecided.put(key, waiting);
        if (replaced != null) {
            replaced.abandon();
        }
        waiting.decision = scheduler.schedule(() -> decideOwner(key, waiting), scheduler.getClock().instant().plus(grace));
    }

    private void abandonUndecided(Key key) {
        Undecided waiting = undecided.remove(key);
        if (waiting != null) {
            waiting.abandon();
        }
    }

    /**
     * The host is the nearest candidate that still lives: the shell and the launcher end with the hook. It is registered as the owner of the
     * session. When every candidate ended the host is gone and the session ends.
     */
    private void decideOwner(Key key, Undecided waiting) {
        if (!undecided.remove(key, waiting)) {
            return;
        }
        Optional<Candidate> owner = waiting.candidates.stream().filter(candidate -> candidate.watch().endReason().isEmpty()).findFirst();
        waiting.candidates.stream().filter(candidate -> owner.filter(chosen -> chosen == candidate).isEmpty()).forEach(candidate -> candidate.watch().close());
        owner.ifPresentOrElse(chosen -> registerOwner(key, waiting, chosen), () -> endWithoutOwner(key, HostWatch.PROCESS_ENDED));
    }

    private void registerOwner(Key key, Undecided waiting, Candidate owner) {
        String processKey = PROCESS_KEY_PREFIX + owner.pid() + ":" + owner.watch().startToken();
        Registered registered;
        try {
            registered = register(key, waiting.receivedAtMs, processKey);
        } catch (SennardException refused) {
            owner.watch().close();
            if (!refusesOwnership(refused)) {
                // Not the registry's refusal of the generation: a failure of something else is not "this process does not own the session", and goes up.
                throw refused;
            }
            // The registry refuses the registration (another process owns the generation, a start older than the current one): this host process does not own the session.
            LOG.info("SESSION_NOT_OWNED {}", refused.code().name());
            return;
        } catch (RuntimeException failure) {
            // Boundary (scheduled task top: the owner decision): nothing above it can handle a failure; the watch is released and the failure is logged with its cause.
            owner.watch().close();
            LOG.warn("SESSION_OWNER_NOT_REGISTERED, the session stays unowned", failure);
            return;
        }
        Watched entry = new Watched(registered.sessionRef(), registered.generation(), registered.relayId(), processKey, owner.watch());
        Watched previous = watched.put(key, entry);
        if (previous != null) {
            previous.watch().close();
        }
        events.publishEvent(new OwnerReady(key.host(), key.hostSessionId()));
        // The host may have ended before the entry existed, when nobody was told.
        owner.watch().endReason().ifPresent(reason -> end(key, entry, reason));
    }

    /** The generation (ownership) refusals of the session registry: another live process holds the session, the expected generation is not the current one, or the start is older than it. */
    private static boolean refusesOwnership(SennardException refused) {
        return refused.code() == ConflictCode.CURRENT_GENERATION_PROTECTED || refused.code() == ConflictCode.GENERATION_CHANGED || refused.code() == ConflictCode.GENERATION_STALE;
    }

    /** The host ended and no owner ever registered: the session ends through the hook's end, which is what a session without an owner has. */
    private void endWithoutOwner(Key key, String reason) {
        kinds.remove(key);
        events.publishEvent(new SessionLeft(key.host(), key.hostSessionId()));
        try {
            lifecycle.end(new SessionLifecycle.HookEnd(key.host(), key.hostSessionId(), reason, clock.millis()));
        } catch (RuntimeException failure) {
            // Boundary (scheduled task top: the owner decision's end of an unowned session). Nothing is lost: nobody keeps this generation alive any more, so its lease ends it.
            LOG.warn("SESSION_END_FAILED, the lease will end it", failure);
        }
    }

    private Registered register(Key key, long startedAtMs, String processKey) {
        return lifecycle.register(new SessionLifecycle.Registration(key.host(), key.hostSessionId(), startedAtMs, processKey, instance, TRANSPORT, NO_DELIVERY));
    }

    /**
     * Any other hook of a session: it registers a session this JVM is not watching (its start was missed, or the start named no process) and does
     * nothing for one it watches or is deciding the owner of. The hooks other than the start do not name the host's process.
     */
    public void observed(String host, String hostSessionId, long receivedAtMs) {
        Key key = new Key(host, hostSessionId);
        if (!watched.containsKey(key) && !undecided.containsKey(key)) {
            lifecycle.noteHook(new SessionLifecycle.HookSeen(host, hostSessionId, receivedAtMs));
        }
    }

    /**
     * A main agent's hook, or a hook of a session its host runs as an agent chosen at launch, says which this session is. The store is asked
     * only when this JVM has not already recorded that answer for the session, so a session's later hooks cost nothing here.
     */
    public void kindSeen(String host, String hostSessionId, SessionKind kind) {
        Key key = new Key(host, hostSessionId);
        if (kinds.get(key) == kind) {
            return;
        }
        lifecycle.markKind(new SessionLifecycle.Marking(host, hostSessionId, kind));
        kinds.put(key, kind);
    }

    /** A hook says the session ended. An owned session ends through its owner, which is this class; any other through the hook's judgment. */
    public void ended(String host, String hostSessionId, String reason, long receivedAtMs) {
        Key key = new Key(host, hostSessionId);
        kinds.remove(key);
        abandonUndecided(key);
        Watched entry = watched.get(key);
        if (entry != null) {
            end(key, entry, reason);
            return;
        }
        events.publishEvent(new SessionLeft(host, hostSessionId));
        lifecycle.end(new SessionLifecycle.HookEnd(host, hostSessionId, reason, receivedAtMs));
    }

    /** Runs on the JDK's process-exit thread, so the database work goes to a thread of its own. */
    private void checkExits() {
        Thread.startVirtualThread(this::endExited);
    }

    private void endExited() {
        watched.forEach((key, entry) -> entry.watch().endReason().ifPresent(reason -> end(key, entry, reason)));
    }

    /** Removing the entry is the claim: whoever removes it ends the session, whoever does not has nothing to do. */
    private void end(Key key, Watched entry, String reason) {
        if (!watched.remove(key, entry)) {
            return;
        }
        entry.watch().close();
        events.publishEvent(new SessionLeft(key.host(), key.hostSessionId()));
        try {
            SessionLifecycle.Ended ended = lifecycle.end(new SessionLifecycle.OwnedEnd(entry.sessionRef(), entry.generation(), entry.relayId(), reason, clock.millis()));
            if (!ended.applied()) {
                LOG.info("SESSION_END_NOT_APPLIED {}", ended.reason());
            }
        } catch (RuntimeException failure) {
            // Boundary (process-exit callback thread top, and the end hook): the host's end must not fail the callback or the hook. Nothing is lost: nobody renews this
            // generation any more, so its lease ends it.
            LOG.warn("SESSION_END_FAILED, the lease will end it", failure);
        }
    }

    /** Renews the leases this JVM owns. An exit whose notification was missed is noticed here as well. */
    @Scheduled(fixedDelay = 5_000)
    public void renew() {
        endExited();
        List<Map.Entry<Key, Watched>> entries = new ArrayList<>(watched.entrySet());
        if (entries.isEmpty()) {
            return;
        }
        List<SessionLifecycle.LeaseItem> items = entries.stream().map(e -> new SessionLifecycle.LeaseItem(e.getValue().sessionRef(), e.getValue().generation(), e.getValue().relayId())).toList();
        List<SessionLifecycle.LeaseResult> results;
        try {
            results = lifecycle.renew(items);
        } catch (RuntimeException failure) {
            // Boundary (scheduled task top): a failed renewal is tried again on the next round; the failure is logged with its cause.
            LOG.warn("LEASE_RENEWAL_FAILED", failure);
            return;
        }
        for (SessionLifecycle.LeaseResult result : results) {
            if (result.state() == LeaseOutcome.RENEWED) {
                continue;
            }
            entries.stream().filter(e -> e.getValue().sessionRef().equals(result.sessionRef()) && e.getValue().generation() == result.generation()).findFirst()
                    .ifPresent(e -> afterLostLease(e.getKey(), e.getValue(), result.state()));
        }
    }

    /** An expired lease is ours to take again; any other state means the generation is no longer this JVM's. */
    private void afterLostLease(Key key, Watched entry, LeaseOutcome state) {
        if (!watched.remove(key, entry)) {
            return;
        }
        if (state != LeaseOutcome.EXPIRED) {
            entry.watch().close();
            return;
        }
        try {
            Registered registered = register(key, clock.millis(), entry.processKey());
            watched.put(key, new Watched(registered.sessionRef(), registered.generation(), registered.relayId(), entry.processKey(), entry.watch()));
        } catch (RuntimeException failure) {
            // Boundary (scheduled task: one entry's re-registration must not stop the others of the round); the watch is released and the failure is logged with its cause.
            entry.watch().close();
            LOG.warn("LEASE_REACQUIRE_FAILED", failure);
        }
    }

    /**
     * After a restart: binds the host processes of the sessions that were live, in the store's order. A session whose host is gone is ended
     * here instead of waiting for its lease; one this server cannot read the key of is left to its lease.
     */
    public void restoreAfterStart() {
        lifecycle.activeOwned().forEach(this::restore);
    }

    private void restore(SessionLifecycle.OwnedSession session) {
        ProcessKey key = ProcessKey.parse(session.processKey());
        if (key == null) {
            return;
        }
        HostWatch watch;
        try {
            watch = HostWatch.bind(key.pid(), clock.millis(), this::checkExits);
        } catch (HostWatch.BindFailed gone) {
            LOG.info("SESSION_HOST_GONE_AT_RESTART {}", gone.reason());
            endRestored(session, HostWatch.PROCESS_ENDED);
            return;
        }
        if (!watch.startToken().equals(key.startToken())) {
            watch.close();
            endRestored(session, HostWatch.IDENTITY_CHANGED);
            return;
        }
        Key watchedKey = new Key(session.host(), session.hostSessionId());
        try {
            Registered registered = register(watchedKey, clock.millis(), session.processKey());
            watched.put(watchedKey, new Watched(registered.sessionRef(), registered.generation(), registered.relayId(), session.processKey(), watch));
        } catch (RuntimeException failure) {
            // Boundary (start-up task: one session's restore must not stop the others); the watch is released and the failure is logged with its cause.
            watch.close();
            LOG.warn("SESSION_RESTORE_FAILED", failure);
        }
    }

    private void endRestored(SessionLifecycle.OwnedSession session, String reason) {
        lifecycle.end(new SessionLifecycle.OwnedEnd(session.sessionRef(), session.generation(), session.relayId(), reason, clock.millis()));
    }

    @Override
    public void destroy() {
        undecided.values().forEach(Undecided::abandon);
        undecided.clear();
        watched.values().forEach(entry -> entry.watch().close());
        watched.clear();
    }

    /** The two parts of a process key this class wrote; null for anything else. */
    private record ProcessKey(long pid, String startToken) {
        static ProcessKey parse(String text) {
            if (text == null || !text.startsWith(PROCESS_KEY_PREFIX)) {
                return null;
            }
            String body = text.substring(PROCESS_KEY_PREFIX.length());
            int separator = body.indexOf(':');
            if (separator <= 0) {
                return null;
            }
            try {
                return new ProcessKey(Long.parseLong(body.substring(0, separator)), body.substring(separator + 1));
            } catch (NumberFormatException notOurs) {
                LOG.debug("not a process key this class wrote: {}", text, notOurs);
                return null;
            }
        }
    }
}
