// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.execution;

import java.time.Instant;

/**
 * Which operating system process an execution is: its process id and the time it started. An id alone is reused by the system after the process ended; the two together name one
 * process, which is what a restarted server compares a record with.
 *
 * @param pid the process id
 * @param startedAt when the process started, as the JVM reports it; {@code null} when the system gives none ({@link #withoutStartTime}), and then the process cannot be told from
 *     another that has the same id
 */
public record ProcessIdentity(long pid, Instant startedAt) {
    /** A process whose start time the system does not give. */
    public static ProcessIdentity withoutStartTime(long pid) {
        return new ProcessIdentity(pid, null);
    }

    /** Whether the identity can tell this process from another that has the same id. */
    public boolean verifiable() {
        return startedAt != null;
    }
}
