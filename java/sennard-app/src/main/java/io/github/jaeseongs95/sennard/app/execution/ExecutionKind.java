// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.execution;

/** The kind of workflow an execution was asked for in: what the {@code kind} column holds (a CHECK lists it). */
public enum ExecutionKind {
    /** An operation workflow: what the program changes in the working folder is recorded and reported. */
    OPERATION
}
