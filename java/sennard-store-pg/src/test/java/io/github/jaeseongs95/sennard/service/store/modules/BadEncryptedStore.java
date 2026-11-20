// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.modules;

import io.github.jaeseongs95.sennard.service.store.Encrypted;

/**
 * A store with a misdeclared row (an {@code @Encrypted} component that is not a String), for {@code EncryptedRecordsTest}: its start must fail. A class of its own, because the records a store
 * is checked for are those of its nest, which for a nested class is the whole of the outer class.
 */
final class BadEncryptedStore extends ModuleStore {
    record Bad(String id, @Encrypted(scope = "test.bad", name = "id") int count) {
    }

    BadEncryptedStore() {
        super(null);
    }
}
