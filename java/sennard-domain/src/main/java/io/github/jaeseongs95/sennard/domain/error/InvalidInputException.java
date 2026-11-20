// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.domain.error;

import java.util.Map;

/** {@link Category#INPUT}: the caller's input is wrong. */
public final class InvalidInputException extends SennardException {
    private static final long serialVersionUID = 1L;

    public InvalidInputException(InputCode code, Map<String, Object> arguments, Map<String, Object> details) {
        this(code, arguments, details, null);
    }

    /** With the failure it answers (a value that did not decode), kept as the cause for the log and never shown. */
    public InvalidInputException(InputCode code, Map<String, Object> arguments, Map<String, Object> details, Throwable cause) {
        super(code, arguments, details, cause);
    }

    public InvalidInputException(InputCode code, Map<String, Object> arguments) {
        this(code, arguments, Map.of());
    }

    public InvalidInputException(InputCode code) {
        this(code, Map.of());
    }
}
