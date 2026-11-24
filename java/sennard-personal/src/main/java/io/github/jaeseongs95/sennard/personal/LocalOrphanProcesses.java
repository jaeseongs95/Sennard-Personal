// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.server.execution.OrphanProcesses;
import io.github.jaeseongs95.sennard.server.execution.ProcessIdentity;

/**
 * Finds a process that a server before this one started, by its id and its start time ({@link ProcessTree#aliveWithId}), and ends it with what it started ({@link ProcessTree#kill()},
 * the same kill the runner uses). A process with the id but another start time is another process, and is left alone. The start times are compared to the millisecond, which is what
 * the JVM reports them in and what a record keeps.
 *
 * <p>The processes the orphan started are listed before it is killed: once it is gone its children have lost their parent on most systems and are no longer found through it. A child
 * that had lost its parent before is not found (the same limit as {@link LocalProcessRunner}).
 */
@Component
public class LocalOrphanProcesses implements OrphanProcesses {
    private static final Logger LOG = LoggerFactory.getLogger(LocalOrphanProcesses.class);

    @Override
    public Outcome terminate(ProcessIdentity process) {
        Optional<ProcessTree> found = ProcessTree.aliveWithId(process.pid());
        if (found.isEmpty()) {
            return Outcome.GONE;
        }
        ProcessTree tree = found.get();
        Optional<Instant> started = tree.startedAt();
        if (!process.verifiable() || started.isEmpty()) {
            LOG.warn("The process {} is alive and cannot be told from the process of a record: its start time is not known on one side; it is not touched.", process.pid());
            return Outcome.UNVERIFIABLE;
        }
        if (!started.get().truncatedTo(ChronoUnit.MILLIS).equals(process.startedAt().truncatedTo(ChronoUnit.MILLIS))) {
            return Outcome.GONE;
        }
        if (!tree.kill()) {
            LOG.warn("The process {} of an execution that a server before this one left running was still alive after it was killed.", process.pid());
            return Outcome.SURVIVED;
        }
        return Outcome.KILLED;
    }
}
