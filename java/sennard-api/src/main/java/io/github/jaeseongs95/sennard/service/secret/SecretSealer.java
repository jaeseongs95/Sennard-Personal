// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.secret;

import java.util.Map;

/**
 * Storage encryption for values the server itself opens. The additional data binds a value
 * to scope, scope id, name and fingerprint: opening it with other data fails. No call returns a key.
 */
public interface SecretSealer {
    SealedValue seal(SecretName name, byte[] plaintext, Map<String, String> aad);

    /** Throws {@code SecretSealException} when the value does not open under this name and data. */
    byte[] open(SealedValue sealed, SecretName name, Map<String, String> aad);
}
