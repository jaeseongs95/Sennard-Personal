// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.pg;

import java.util.UUID;

import io.github.jaeseongs95.sennard.app.operation.OperationKey;
import io.github.jaeseongs95.sennard.domain.Digests;

/**
 * The operation of one call of a write use case that a test makes directly (not through a tool): the scope of the caller and a key of its own, as a client makes one for each
 * operation, so that no two calls share a receipt (AGS3-D-0267). The public entry of every such use case takes the {@link OperationKey}; there is no entry without one.
 */
final class Operations {
    /** The scope of a call whose caller the test leaves out (a blank or missing session): a tool's call always has the session the server settled, so the operation is made with some scope, and the service's own validation of its arguments is what the test judges. */
    private static final String UNSET_CALLER = "as1_TESTCALLERNOTSET";

    private Operations() {
    }

    /** A new key for the caller {@code scope}; the digest is the key's own, so two operations never have the same content either. */
    static OperationKey fresh(String tool, String scope) {
        String key = "store-test-" + UUID.randomUUID();
        return new OperationKey(tool, scope == null || scope.isBlank() ? UNSET_CALLER : scope, key, Digests.sha256Hex(key));
    }
}
