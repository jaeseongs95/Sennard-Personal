// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.domain.error;

import java.util.Map;

/** {@link Category#BINDING}: the session or caller a call needs is missing or not valid. */
public final class BindingException extends SennardException {
    private static final long serialVersionUID = 1L;

    public BindingException(BindingCode code, Map<String, Object> arguments, Map<String, Object> details) {
        this(code, arguments, details, null);
    }

    /** With the failure it answers (a token that did not decode), kept as the cause for the log and never shown. */
    public BindingException(BindingCode code, Map<String, Object> arguments, Map<String, Object> details, Throwable cause) {
        super(code, arguments, details, cause);
    }

    public BindingException(BindingCode code) {
        this(code, Map.of(), Map.of());
    }
}
