// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.pg;

import java.time.Duration;

/**
 * What one test transaction takes: whether it is a read-only snapshot and its own shorter time (the transaction timeout of Spring: a
 * statement, a lock wait included, that is still running when it ends is cut by JDBC's query timeout, whole seconds).
 */
public record TxOptions(boolean readOnlySnapshot, Integer timeoutSeconds) {
    public static TxOptions write() {
        return new TxOptions(false, null);
    }

    public static TxOptions snapshot() {
        return new TxOptions(true, null);
    }

    /** The transaction's time, rounded up to whole seconds (a transaction timeout is whole seconds). */
    public TxOptions withTimeout(Duration value) {
        return new TxOptions(readOnlySnapshot, (int) Math.max(1, (value.toMillis() + 999) / 1000));
    }
}
