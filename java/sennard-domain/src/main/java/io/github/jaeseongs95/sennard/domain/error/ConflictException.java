// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.domain.error;

import java.util.Map;

/** {@link Category#CONFLICT}: the call meets another state or another writer. */
public final class ConflictException extends SennardException {
    private static final long serialVersionUID = 1L;

    public ConflictException(ConflictCode code, Map<String, Object> arguments, Throwable cause) {
        super(code, arguments, Map.of(), cause);
    }

    /** With what the wire's {@code error.details} shows besides the reason (the state token the caller should have sent, the one that is current). */
    public ConflictException(ConflictCode code, Map<String, Object> arguments, Map<String, Object> details) {
        super(code, arguments, details, null);
    }

    /** With the details of the wire and the failure that the answer replaces (the cause is for the log and is never shown). */
    public ConflictException(ConflictCode code, Map<String, Object> arguments, Map<String, Object> details, Throwable cause) {
        super(code, arguments, details, cause);
    }

    public ConflictException(ConflictCode code, Map<String, Object> arguments) {
        this(code, arguments, Map.of());
    }

    public ConflictException(ConflictCode code) {
        this(code, Map.of(), Map.of());
    }

    /** Whether this is the conflict of an operationKey (the content differs, or the same request raced): every write tool shows it as {@code REQUEST_CONFLICT} with {@code details.reason}. */
    public boolean isOperationKey() {
        return code() instanceof ConflictCode conflict && conflict.isOperationKey();
    }
}
