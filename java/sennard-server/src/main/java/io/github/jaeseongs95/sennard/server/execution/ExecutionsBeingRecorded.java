// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.execution;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * The ids of the executions whose record is being written: from before the program is started (the id is here before the runner makes the folder of its log files) until the write of its
 * record has ended. Such an execution may already have ended and has no record yet, so neither the runner (it is not running) nor the records (it has none) name it, and its folder would
 * look like one that no record names. {@link ExecutionTracker} puts the id here, {@link ExecutionLogRetention} asks, the way it asks the runner which executions run: the folder of such
 * an execution is never deleted.
 */
@Component
public class ExecutionsBeingRecorded {
    private final Set<String> ids = ConcurrentHashMap.newKeySet();

    /** The execution with this id is being recorded from now on. */
    void begin(String id) {
        ids.add(id);
    }

    /** Its record has been written, or could not be (and the program was ended): it is no longer here. */
    void end(String id) {
        ids.remove(id);
    }

    /** The ids of the executions whose record is being written now. */
    public Set<String> ids() {
        return Set.copyOf(ids);
    }
}
