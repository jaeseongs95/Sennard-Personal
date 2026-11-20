// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.context;

import org.springframework.stereotype.Component;
import io.github.jaeseongs95.sennard.server.limits.ResourceLimits;
import io.github.jaeseongs95.sennard.server.limits.ResourceLimitSource;

/** Reads verified limits directly from the log so workflow and admin services do not depend on each other. */
@Component
class SignedResourceLimits implements ResourceLimitSource {
    private final AdminLogSource logs;
    private final AdminLogVerifier verifier;
    SignedResourceLimits(AdminLogSource logs, AdminLogVerifier verifier) {
        this.logs = logs;
        this.verifier = verifier;
    }
    @Override
    public ResourceLimits current() {
        AdminLog.ResourceLimitsState signed = verifier.fold(logs.records()).resourceLimits;
        return signed == null ? ResourceLimits.DEFAULT : signed.limits();
    }
}
