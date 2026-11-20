// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.domain.error;

import java.util.Map;

/** {@link Category#UNAVAILABLE}: the server cannot do this now. {@code details} is what the wire shows besides the reason (a capacity scope and when it frees). */
public final class UnavailableException extends SennardException {
    private static final long serialVersionUID = 1L;

    public UnavailableException(UnavailableCode code, Map<String, Object> arguments, Map<String, Object> details, Throwable cause) {
        super(code, arguments, details, cause);
    }

    public UnavailableException(UnavailableCode code) {
        this(code, Map.of(), Map.of(), null);
    }

    public UnavailableException(UnavailableCode code, Throwable cause) {
        this(code, Map.of(), Map.of(), cause);
    }

    public UnavailableException(UnavailableCode code, Map<String, Object> arguments) {
        this(code, arguments, Map.of(), null);
    }

    /** With the failure it answers, kept as the cause for the log and never shown. */
    public UnavailableException(UnavailableCode code, Map<String, Object> arguments, Throwable cause) {
        this(code, arguments, Map.of(), cause);
    }
}
