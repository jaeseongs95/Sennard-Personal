// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal;

import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.server.trust.TrustService;

/**
 * Once the store is open, the per-database signing key of the source receipts exists: {@link TrustService#createSigningKeyIfAbsent} reads it and makes it when there is none, in one
 * transaction of its own, and a hook's claim only reads it. This task opens no transaction. It is a {@link StoreStartTask}: the store is not ready before the key is.
 */
@Component
class TrustKeyTask implements StoreStartTask {
    private final TrustService trust;

    TrustKeyTask(TrustService trust) {
        this.trust = trust;
    }

    @Override
    public void beforeStoreReady() {
        trust.createSigningKeyIfAbsent();
    }
}
