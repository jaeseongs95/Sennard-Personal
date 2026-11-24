// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.execution;

/**
 * What a restarted server can do about a program that a server before it started: find out whether that very process is still alive and end it. The previous server's runner held
 * the handle; only the {@link ProcessIdentity} was kept.
 */
public interface OrphanProcesses {
    /** What became of the process asked about. */
    enum Outcome {
        /** No process has this id and this start time: it ended, or its id belongs to another process now. Nothing was touched. */
        GONE,
        /** The process was alive, was the same one, and it and the processes it started were killed. */
        KILLED,
        /** The identity has no start time, so a live process with this id cannot be told from another; nothing was touched. */
        UNVERIFIABLE,
        /** The process was the same one and was asked to end, and was still alive after the implementation's grace. */
        SURVIVED
    }

    Outcome terminate(ProcessIdentity process);
}
