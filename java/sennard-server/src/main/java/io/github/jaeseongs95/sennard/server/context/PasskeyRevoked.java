// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.context;

/**
 * Published inside the commit of a signed {@link AdminOp#KEY_REVOKE}. {@link PasskeyRevocations} revokes the key in the passkey registry after the transaction commits, so a
 * record that rolls back never revokes a key.
 */
public record PasskeyRevoked(String credentialId) {
}
