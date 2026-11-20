// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.context;

import java.time.Clock;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * The intents this server process prepared for the page that no signed record has used yet. A commit is accepted only for an intent held here, and a record that is appended
 * spends its intent ({@link #use}), so an intent is signed for once, by the page that was shown it, and only for the change and the chain head it was prepared for: the key is the
 * intent's whole text, which names the target, the change, the head it stands on and the instant the server wrote into it.
 *
 * <p>What the passkey's signature proves is that the user signed this text. It does not prove that this server asked for it: the text is plain JSON that any client can write,
 * with an {@code issuedAt} of its own choosing, and a signature a look-alike page on a port the next start will get collected for such a text would otherwise pass the origin check
 * after a restart (the clock check alone cannot tell, because the instant is the one the writer chose). A restart empties this memory, so nothing prepared by an earlier run
 * is held by the next.
 *
 * <p>ceiling: per process, {@code PENDING_LIMIT} intents; an intent older than {@code AdminLog.INTENT_TTL_MS} is dropped when the next is prepared, and past the limit the oldest
 * is dropped first (its page prepares again).
 */
@Component
public final class AdminIssuedIntents {
    private static final int PENDING_LIMIT = 1024;

    private final Clock clock;
    /** The challenge of an intent (the SHA-256 of its text) to the instant this process prepared it, oldest first. */
    private final Map<String, Long> issuedAt = new LinkedHashMap<>();

    public AdminIssuedIntents(Clock clock) {
        this.clock = clock;
    }

    /** Remembers an intent the server has just prepared. */
    public synchronized void issue(String intent) {
        long now = clock.millis();
        Iterator<Long> oldest = issuedAt.values().iterator();
        while (oldest.hasNext() && (now - oldest.next() > AdminLog.INTENT_TTL_MS || issuedAt.size() >= PENDING_LIMIT)) {
            oldest.remove();
        }
        String key = AdminWebAuthn.intentChallenge(intent);
        // A text prepared again takes its place at the end, so that the oldest first is the order of the instants.
        issuedAt.remove(key);
        issuedAt.put(key, now);
    }

    /** Whether this exact text was prepared by this process, within the time an intent lives, and has not been used. */
    public synchronized boolean holds(String intent) {
        Long prepared = issuedAt.get(AdminWebAuthn.intentChallenge(intent));
        return prepared != null && clock.millis() - prepared <= AdminLog.INTENT_TTL_MS;
    }

    /** Spends an intent: no second signed record is made from it. */
    public synchronized void use(String intent) {
        issuedAt.remove(AdminWebAuthn.intentChallenge(intent));
    }

    /**
     * Spends the intent of a record once the transaction that appended it has committed. A transaction that rolled back, or whose commit failed, publishes the event
     * but never reaches this phase, so the intent is still held and the page commits it again.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void whenRecorded(AdminIntentSpent spent) {
        use(spent.intent());
    }
}
