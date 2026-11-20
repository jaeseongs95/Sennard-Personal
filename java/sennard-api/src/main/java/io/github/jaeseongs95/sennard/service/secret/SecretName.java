// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.secret;

/** The name a sealed value is bound to, so a value cannot be moved to another name unnoticed. */
public record SecretName(String value) {
    public SecretName {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("A secret name is required");
        }
    }
}
