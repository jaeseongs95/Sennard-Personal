// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.session;

import java.time.Clock;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.jaeseongs95.sennard.app.operation.OperationKey;
import io.github.jaeseongs95.sennard.app.operation.OperationReceipts;
import io.github.jaeseongs95.sennard.service.identity.SessionRegistry;
import io.github.jaeseongs95.sennard.service.identity.SessionRegistry.RegisterRequest;
import io.github.jaeseongs95.sennard.service.identity.SessionRegistry.Registration;

/**
 * The use case of {@code open_session} that writes: it mints a session the server has no host id for and registers it, once per operationKey (AGS3-D-0267). The same key answers the
 * session the first call minted (the receipt keeps its reference), where a call with a new key mints another. The tool reads nothing from the files and holds no transaction of
 * its own; this method is the one transaction.
 */
@Service
public class SessionOpening {
    private final SessionRegistry registry;
    private final OperationReceipts receipts;
    private final Clock clock;

    public SessionOpening(SessionRegistry registry, OperationReceipts receipts, Clock clock) {
        this.registry = registry;
        this.receipts = receipts;
        this.clock = clock;
    }

    /**
     * @param host the host label the caller sent, or the unknown host
     * @param delivery the stored text of how this kind of session can be reached (not at all)
     */
    @Transactional
    public SessionTools.OpenedSession open(String host, String delivery, OperationKey operation) {
        return receipts.once(operation, SessionTools.OpenedSession.class, () -> {
            String hostSessionId = UUID.randomUUID().toString();
            Registration opened = registry.register(host, new RegisterRequest(hostSessionId, clock.millis(), null, null, null, null, "http", delivery));
            return new SessionTools.OpenedSession(opened.sessionRef(), opened.generation(), "unavailable");
        }).value();
    }
}
