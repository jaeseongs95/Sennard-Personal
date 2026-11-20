// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.secret;

/** The stored form of a sealed value, text only so it fits a text column. The format is the sealer's own. */
public record SealedValue(String text) {
    public SealedValue {
        if (text == null || text.isEmpty()) {
            throw new IllegalArgumentException("A sealed value is not empty");
        }
    }
}
