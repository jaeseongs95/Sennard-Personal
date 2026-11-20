// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.domain.error;

import java.util.Map;

/** {@link Category#NOT_FOUND}: a thing the call names does not exist. */
public final class NotFoundException extends SennardException {
    private static final long serialVersionUID = 1L;

    public NotFoundException(NotFoundCode code, Map<String, Object> arguments) {
        super(code, arguments, Map.of(), null);
    }

    /** With what the wire's {@code error.details} shows besides the reason. */
    public NotFoundException(NotFoundCode code, Map<String, Object> arguments, Map<String, Object> details) {
        super(code, arguments, details, null);
    }

    public NotFoundException(NotFoundCode code) {
        this(code, Map.of());
    }
}
