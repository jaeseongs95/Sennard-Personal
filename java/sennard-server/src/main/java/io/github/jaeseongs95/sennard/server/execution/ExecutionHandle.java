// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.execution;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/** One execution that a {@link ProcessRunner} took: asked about, waited for or cancelled by its id's holder. */
public interface ExecutionHandle {
    String id();

    /** The execution as it is now; never waits. */
    ExecutionSnapshot snapshot();

    /**
     * Waits at most {@code wait} for the execution to end and answers how it is then: the end when it came in time, {@link ExecutionState#RUNNING} when not.
     *
     * @throws InterruptedException when the calling thread is interrupted while it waits (the execution goes on)
     */
    ExecutionSnapshot await(Duration wait) throws InterruptedException;

    /** Completes with the final snapshot when the execution ends; already complete for one that is finished. It never completes exceptionally. */
    CompletableFuture<ExecutionSnapshot> ended();

    /**
     * Kills the program and everything it started, waits (bounded) for the end and answers how the execution is then. A program that had already ended when the cancel arrived is
     * left as it is, not made {@link ExecutionState#KILLED}. The wait does not stop at an interrupt of the caller (the kill is done; the answer is bounded).
     */
    ExecutionSnapshot cancel();
}
