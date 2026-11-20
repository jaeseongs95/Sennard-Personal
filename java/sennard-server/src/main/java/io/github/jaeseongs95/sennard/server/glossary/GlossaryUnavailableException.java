// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.glossary;

/** The glossary's catalog could not be read, or holds a term the glossary will not hold; the cause says which. Caught once, by the lookup, which logs it. */
public class GlossaryUnavailableException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public GlossaryUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public GlossaryUnavailableException(String message) {
        super(message);
    }
}
