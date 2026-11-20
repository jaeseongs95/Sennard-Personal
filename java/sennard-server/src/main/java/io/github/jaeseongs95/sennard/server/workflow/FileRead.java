// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import java.io.UncheckedIOException;
import java.util.function.Supplier;

/**
 * The outcome of one read: its value, or the failure the read raised. The entry reads before the use case's transaction and the use case judges after; a failure is kept
 * and raised by {@link #get()} where the use case takes the value, so a refusal comes in the order the use case's own checks give, as it did when the file was read there.
 * A failure that is not a contract failure of the read (an unforeseen one) is not kept: it ends the entry's call.
 */
record FileRead<T>(T value, RuntimeException failure) {
    static <T> FileRead<T> of(Supplier<T> read) {
        try {
            return new FileRead<>(read.get(), null);
        } catch (WorkflowFailure | UncheckedIOException refused) {
            return new FileRead<>(null, refused);
        }
    }

    T get() {
        if (failure != null) {
            throw failure;
        }
        return value;
    }
}
