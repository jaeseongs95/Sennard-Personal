// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.domain.messaging;

/** What a claimer asks for at most; a null field takes the default. {@code maxBodyChars} counts UTF-16 code units of the bodies together. */
public record ClaimLimits(Integer maxMessages, Integer maxBodyChars) {
    /** Both defaults. */
    public static final ClaimLimits DEFAULTS = new ClaimLimits(null, null);
}
