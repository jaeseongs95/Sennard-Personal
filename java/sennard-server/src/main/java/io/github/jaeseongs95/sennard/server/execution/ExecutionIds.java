// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.execution;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The one place that says what the id of an execution looks like: whoever asks for an execution makes the id here ({@link #next()}) and hands it to the runner, which checks it here, and
 * whoever finds a name that may be one (the folder of log files of an execution is named by it) asks here ({@link #isExecutionId}). A folder in the log folder whose name is not what this makes was not made by this server, so it is neither judged nor
 * deleted by the retention of the logs.
 */
public final class ExecutionIds {
    /** The canonical text of a UUID, as {@link UUID#toString()} writes it (lower case). */
    private static final Pattern FORMAT = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private ExecutionIds() {
    }

    /** A new id for an execution. */
    public static String next() {
        return UUID.randomUUID().toString();
    }

    /** Whether {@code name} is written as the ids that {@link #next()} makes. */
    public static boolean isExecutionId(String name) {
        return name != null && FORMAT.matcher(name).matches();
    }
}
