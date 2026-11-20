// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.domain.error;

import java.util.Map;

/** {@link Category#GATE}: a rule of the product refuses this step now. */
public final class GateRefusedException extends SennardException {
    private static final long serialVersionUID = 1L;

    public GateRefusedException(GateCode code, Map<String, Object> arguments, Map<String, Object> details) {
        super(code, arguments, details, null);
    }

    /** With the failure it answers, kept as the cause for the log and never shown. */
    public GateRefusedException(GateCode code, Map<String, Object> arguments, Throwable cause) {
        super(code, arguments, Map.of(), cause);
    }

    public GateRefusedException(GateCode code, Map<String, Object> arguments) {
        this(code, arguments, Map.of());
    }

    public GateRefusedException(GateCode code) {
        this(code, Map.of(), Map.of());
    }

    /** The reason as its own type: a boundary that answers a gate by its reason (the admin page's refusals are 403, not 422) switches over it, with no {@code default}. */
    @Override
    public GateCode code() {
        return (GateCode) super.code();
    }
}
