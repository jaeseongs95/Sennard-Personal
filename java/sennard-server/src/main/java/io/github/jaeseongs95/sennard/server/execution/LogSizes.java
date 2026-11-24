// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.execution;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** What the log files of an execution that has just ended take, to be noted in its record: the retention adds these up and does not measure the folders again. */
final class LogSizes {
    private static final Logger LOG = LoggerFactory.getLogger(LogSizes.class);

    private LogSizes() {
    }

    /** The bytes of the folder, {@code null} when there is none or it could not be measured (that is logged, and the record then says no size). */
    static Long of(ExecutionLogStore store, String executionId, String logLocation) {
        if (logLocation == null) {
            return null;
        }
        try {
            return store.sizeOf(logLocation);
        } catch (IOException | RuntimeException unmeasured) {
            LOG.error("The log files of execution {} in {} could not be measured; its record notes no size.", executionId, logLocation, unmeasured);
            return null;
        }
    }
}
