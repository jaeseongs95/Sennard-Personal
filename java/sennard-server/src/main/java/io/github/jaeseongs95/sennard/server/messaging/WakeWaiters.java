// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.messaging;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import io.github.jaeseongs95.sennard.server.session.HookSessions;

/**
 * The sessions whose wake hook is holding a request open. One waiter per session: a later hook of the same session replaces the
 * earlier one, and a session that takes input or leaves releases its own. Nothing here holds a database connection, and the signal is sent after the message's transaction commits, so a woken waiter
 * finds the row. The waiter is registered before the caller looks for pending messages, which closes the gap between looking and waiting.
 *
 * <p>ceiling: a signal reaches this JVM only, and the server is the one process that holds the waiters.
 */
@Component
public class WakeWaiters {
    /** A message was committed for this session. */
    public record MessageQueued(String host, String hostSessionId, String actorId) {
        public MessageQueued(String host, String hostSessionId) {
            this(host, hostSessionId, null);
        }
    }

    /** How one wait ended. */
    public enum Outcome {
        SIGNALLED, TIMED_OUT, REPLACED, RELEASED, CLOSING
    }

    private record Key(String host, String hostSessionId) {
    }

    /** One held hook. */
    public final class Waiter implements AutoCloseable {
        private final Key key;
        private CompletableFuture<Outcome> signal = new CompletableFuture<>();

        private Waiter(Key key) {
            this.key = key;
        }

        /** Rearms for another wait; a signal that arrived during the claim is not lost, because the caller claims again before waiting. */
        public synchronized void rearm() {
            if (signal.isDone() && signal.getNow(null) == Outcome.SIGNALLED) {
                signal = new CompletableFuture<>();
            }
        }

        public Outcome await(Duration timeout) throws InterruptedException {
            CompletableFuture<Outcome> current;
            synchronized (this) {
                current = signal;
            }
            try {
                // The copy ends with TIMED_OUT when the time elapses, and the signal itself is left as it was (completing it on the timeout would be a signal that never came).
                return current.copy().completeOnTimeout(Outcome.TIMED_OUT, timeout.toMillis(), TimeUnit.MILLISECONDS).get();
            } catch (CancellationException | ExecutionException impossible) {
                throw new IllegalStateException("A wake signal only completes normally", impossible);
            }
        }

        private synchronized void complete(Outcome outcome) {
            signal.complete(outcome);
        }

        @Override
        public void close() {
            waiters.remove(key, this);
        }
    }

    private final Map<Key, Waiter> waiters = new ConcurrentHashMap<>();
    private volatile boolean closing;

    public Waiter register(String host, String hostSessionId) {
        Key key = new Key(host, hostSessionId);
        Waiter waiter = new Waiter(key);
        if (closing) {
            waiter.complete(Outcome.CLOSING);
            return waiter;
        }
        Waiter previous = waiters.put(key, waiter);
        if (previous != null) {
            previous.complete(Outcome.REPLACED);
        }
        return waiter;
    }

    /**
     * The session is no longer idle, or left: its held hook ends without waking, and the hook of the next idle point registers another.
     * While the session works, its messages go to it at the ordinary boundaries instead.
     */
    public void release(String host, String hostSessionId) {
        Waiter waiter = waiters.get(new Key(host, hostSessionId));
        if (waiter != null) {
            waiter.complete(Outcome.RELEASED);
        }
    }

    @EventListener
    public void on(HookSessions.SessionLeft left) {
        release(left.host(), left.hostSessionId());
    }

    public boolean isWaiting(String host, String hostSessionId) {
        return waiters.containsKey(new Key(host, hostSessionId));
    }

    /**
     * After the message's transaction committed: the send itself runs its own transaction in sennard-app and returns after the commit, so the event is published with none
     * open ({@code fallbackExecution}) and is signalled at once; a publisher that does hold a transaction is signalled when it commits.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(MessageQueued queued) {
        if (queued.actorId() != null) {
            return;
        }
        Waiter waiter = waiters.get(new Key(queued.host(), queued.hostSessionId()));
        if (waiter != null) {
            waiter.complete(Outcome.SIGNALLED);
        }
    }

    /** Runs before the web server stops, so a held hook answers instead of being cut. */
    @EventListener
    public void on(ContextClosedEvent closed) {
        closing = true;
        waiters.values().forEach(waiter -> waiter.complete(Outcome.CLOSING));
    }
}
