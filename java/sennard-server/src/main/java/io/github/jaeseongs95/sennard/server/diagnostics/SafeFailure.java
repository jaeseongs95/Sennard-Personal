// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.diagnostics;

import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Objects;

/** Logging-only snapshot. Exception text and application details are neither read nor retained; this is never a business exception. */
public final class SafeFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private SafeFailure capturedCause;

    private SafeFailure(Throwable source) {
        super(source.getClass().getName());
        setStackTrace(source.getStackTrace());
    }

    @Override
    public synchronized Throwable getCause() {
        return capturedCause;
    }

    /** Preserves shared nodes and cycles without retaining any original Throwable or invoking its message/toString. */
    public static SafeFailure capture(Throwable failure) {
        Objects.requireNonNull(failure);
        var snapshots = new IdentityHashMap<Throwable, SafeFailure>();
        var pending = new ArrayDeque<Throwable>();
        snapshots.put(failure, new SafeFailure(failure));
        pending.add(failure);
        while (!pending.isEmpty()) {
            Throwable source = pending.removeFirst();
            SafeFailure snapshot = snapshots.get(source);
            Throwable cause = source.getCause();
            if (cause != null) {
                snapshot.capturedCause = node(cause, snapshots, pending);
            }
            for (Throwable suppressed : source.getSuppressed()) {
                snapshot.addSuppressed(node(suppressed, snapshots, pending));
            }
        }
        return snapshots.get(failure);
    }

    private static SafeFailure node(Throwable source, IdentityHashMap<Throwable, SafeFailure> snapshots, ArrayDeque<Throwable> pending) {
        SafeFailure snapshot = snapshots.get(source);
        if (snapshot == null) {
            snapshot = new SafeFailure(source);
            snapshots.put(source, snapshot);
            pending.addLast(source);
        }
        return snapshot;
    }
}
