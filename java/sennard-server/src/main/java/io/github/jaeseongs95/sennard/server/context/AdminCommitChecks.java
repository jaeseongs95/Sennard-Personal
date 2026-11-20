// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.context;

import java.util.Map;

import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.domain.error.GateCode;
import io.github.jaeseongs95.sennard.domain.error.GateRefusedException;
import io.github.jaeseongs95.sennard.domain.error.InputCode;
import io.github.jaeseongs95.sennard.domain.error.InvalidInputException;
import io.github.jaeseongs95.sennard.service.store.modules.StoreValues;

/**
 * What a commit checks about the signature's origin and the intent's freshness before it looks at the log, the same for a record that goes to the business database and one
 * that goes to the settings database before the link ({@link ContextLayerService#commit}, {@link PreLinkAdminService#commit}): the page the signature was made on, the
 * intent's age and whether this server process prepared the text.
 */
@Component
final class AdminCommitChecks {
    private final AdminWebAuthn webauthn;
    private final AdminServerStart serverStart;
    private final AdminIssuedIntents issuedIntents;

    AdminCommitChecks(AdminWebAuthn webauthn, AdminServerStart serverStart, AdminIssuedIntents issuedIntents) {
        this.webauthn = webauthn;
        this.serverStart = serverStart;
        this.issuedIntents = issuedIntents;
    }

    /** @param origin the origin of the admin page of this run, null when the caller has none to compare with */
    void checkFresh(String intentText, AdminLog.Intent intent, AdminWebAuthn.Assertion assertion, String origin, long now) {
        // Only a signature made on this page: a look-alike page on another localhost port cannot collect one. Client data that cannot be
        // read is refused as an invalid signature. Reading the log later accepts any localhost port, because the port setting may change.
        String signedOrigin = origin == null ? null : webauthn.assertionOrigin(assertion.clientDataJson());
        if (signedOrigin != null && !signedOrigin.equals(origin)) {
            throw new GateRefusedException(GateCode.ADMIN_ORIGIN_REJECTED, Map.of(), Map.of("expected", origin));
        }
        long issued = StoreValues.parseIso(intent.issuedAt());
        // No allowance for an intent issued after now: the instant in it is the server's own (an intent not prepared here is refused below), so there is no other clock to
        // differ from; an allowance would only let a text written by someone else, with an instant of its choosing, pass the check of the start below.
        if (now - issued > AdminLog.INTENT_TTL_MS) {
            throw new InvalidInputException(InputCode.ADMIN_INTENT_EXPIRED);
        }
        // The page's port changes at each start, and the origin check above knows only the current one: an intent prepared by an earlier run is refused
        // however fresh it is, or a signature collected on a look-alike page at the port this run happens to get would pass.
        // The signature proves what the user signed, not that this server asked for it: a text written by another client (with any instant in it) or one a record already used is refused.
        // What decides is this process's own record of what it prepared, which a restart empties; the instant of the start only says why a text is not held. So an adjustment of
        // the clock that sets it back after the start cannot refuse an intent this process prepared.
        if (!issuedIntents.holds(intentText)) {
            throw new InvalidInputException(serverStart.precedes(issued) ? InputCode.ADMIN_INTENT_BEFORE_START : InputCode.ADMIN_INTENT_NOT_ISSUED);
        }
    }
}
