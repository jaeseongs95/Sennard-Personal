// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.context;

import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.server.workflow.ConvergenceLimitSource;
import io.github.jaeseongs95.sennard.server.workflow.ConvergenceLimits;

/**
 * The convergence limits the user signed on the admin page ({@link AdminOp#CONVERGENCE_LIMITS_SET}), read from the verified log, or the default when none is signed. It reads the
 * log through the log source and the verifier rather than through {@link ContextLayerService}: that service releases roots through the workflow service, which asks for these limits.
 * It reads the business table (and the settings database's records before it) and opens no transaction: inside a caller's transaction the read joins it, outside there is nothing to join.
 */
@Component
class SignedConvergenceLimits implements ConvergenceLimitSource {
    private final AdminLogSource logs;
    private final AdminLogVerifier verifier;

    SignedConvergenceLimits(AdminLogSource logs, AdminLogVerifier verifier) {
        this.logs = logs;
        this.verifier = verifier;
    }

    @Override
    public ConvergenceLimits current() {
        AdminLog.ConvergenceLimitsState signed = verifier.fold(logs.records()).convergenceLimits;
        return signed == null ? ConvergenceLimits.DEFAULT : signed.limits();
    }
}
