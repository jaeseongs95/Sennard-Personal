// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.domain.error;

import java.util.Map;

/** {@link Category#INTEGRITY}: data contradicts itself or a declared constraint of the store. */
public final class IntegrityException extends SennardException {
    private static final long serialVersionUID = 1L;

    public IntegrityException(IntegrityCode code, Map<String, Object> arguments, Throwable cause) {
        super(code, arguments, Map.of(), cause);
    }

    /** With what the wire's {@code error.details} shows besides the reason (the layer whose stored data failed its check). */
    public IntegrityException(IntegrityCode code, Map<String, Object> arguments, Map<String, Object> details) {
        super(code, arguments, details, null);
    }

    public IntegrityException(IntegrityCode code, Map<String, Object> arguments) {
        this(code, arguments, Map.of());
    }

    public IntegrityException(IntegrityCode code) {
        this(code, Map.of());
    }
}
