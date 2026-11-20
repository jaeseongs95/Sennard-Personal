// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.context;

import java.util.Collection;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import io.github.jaeseongs95.sennard.service.settings.PasskeyCredential;
import io.github.jaeseongs95.sennard.service.settings.PasskeyRegistry;
import io.github.jaeseongs95.sennard.service.settings.PasskeyState;
import io.github.jaeseongs95.sennard.service.settings.SettingsException;

/**
 * Keeps the passkey registry (the settings database) in step with the signed log (the business database) for revocations. The log decides when a key was revoked and the
 * registry holds the revoked state: the two databases are not one transaction, so the registry is revoked after the log's transaction commits ({@link #whenRevoked}), and a
 * key the log revoked that the registry still holds as active (the registry could not be written then) is revoked when the admin state is next read ({@link #reconcile}). In
 * the meantime that key cannot sign anything, because the log no longer lists it as registered.
 */
@Component
public final class PasskeyRevocations {
    private static final Logger LOG = LoggerFactory.getLogger(PasskeyRevocations.class);

    private final PasskeyRegistry passkeys;

    public PasskeyRevocations(PasskeyRegistry passkeys) {
        this.passkeys = passkeys;
    }

    /**
     * After the commit of the revoking record. A failure here must not fail the request that already committed: it is logged, and {@link #reconcile} revokes the key at the next
     * read of the admin state.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void whenRevoked(PasskeyRevoked revoked) {
        try {
            revoke(revoked.credentialId());
        } catch (SettingsException | DataAccessException | TransactionException unavailable) {
            // The registry's write failed (its own refusal, the database's, or its transaction's). Not swallowed: the key stays revoked in the log, which is what stops it, and the next read of the admin state repeats this revocation.
            LOG.warn("The passkey {} was revoked in the signed log, but the registry could not revoke it; the next read of the admin state does", revoked.credentialId(), unavailable);
        }
    }

    /**
     * Revokes the key in the registry; a key that is revoked already (or that the registry does not hold) is left as it is. Returns whether the key was active. The registry's revoke
     * is idempotent, so a caller that revokes the key at the same moment (the listener of a commit and a read of the admin state) is no failure.
     */
    private boolean revoke(String credentialId) {
        Optional<PasskeyCredential> held = passkeys.find(credentialId);
        if (held.isEmpty() || held.get().state() == PasskeyState.REVOKED) {
            return false;
        }
        passkeys.revoke(credentialId);
        return true;
    }

    /**
     * Revokes in the registry every key the log revoked that the registry still holds as active; each one is warned about, because it means an earlier revocation did not reach the
     * registry. Called by the entrance that shows the admin state ({@code AdminPageData}), after the read of the log has ended: the read is a read-only transaction of the business
     * database and writes nothing, and this is the registry's own write (a service method of the settings database).
     */
    public void reconcile(Collection<String> revokedInLog) {
        for (String credentialId : revokedInLog) {
            if (revoke(credentialId)) {
                LOG.warn("The passkey {} is revoked in the signed log and was still active in the registry; it is revoked there now", credentialId);
            }
        }
    }
}
