// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.execution;

import java.util.Optional;
import java.util.Set;

/**
 * Starts a program of the caller on this computer, without a shell, and keeps what the caller needs to ask about it later. It knows nothing of why the program runs: whoever asks
 * (a skill's check, an execution tool of a workflow) judges the request and keeps its own records.
 *
 * <p>Everything about how a program is started is the implementation's: where the program is looked for, which arguments it cannot be given, how many run at once, how the whole tree
 * of processes is ended and what is left behind when the server stops. A caller sees an {@link ExecutionHandle}.
 */
public interface ProcessRunner {
    /**
     * Starts the program and answers at once, without waiting for it. A program that cannot be started (it does not exist, the working directory is not a folder) is not a refusal: it is
     * an execution in {@link ExecutionState#NOT_STARTED} with the id it was given.
     *
     * <p>The id is the caller's: it is made by {@link ExecutionIds#next()} before anything is done for the execution, so that a caller that has to keep the files of the execution from
     * being deleted (see {@link ExecutionsBeingRecorded}) can do so before the runner makes the first of them. The runner never makes an id of its own, and the handle it answers has the
     * id it was given. An execution that is refused, rejected or that fails to start leaves no folder of log files behind.
     *
     * @param id the id of the new execution, written as {@link ExecutionIds#isExecutionId} says
     * @throws Refused when the execution is not taken: too many are running, the server is stopping, or an argument cannot be given as written
     * @throws IllegalArgumentException when {@code id} is not written as an execution id, or is the id of an execution that the runner holds or whose log folder is there already
     */
    ExecutionHandle start(String id, ExecutionRequest request);

    /** The execution with this id, while the runner still holds it (it holds every running one and the most recent finished ones). */
    Optional<ExecutionHandle> find(String executionId);

    /**
     * The ids of the executions that are running in this server now, including one that is being started (its log folder may be there already). Whoever deletes log files must not
     * delete these. An id that is asked for after a folder was listed is not missed: the runner has the id before it makes the folder, and keeps it until {@link #start} has answered.
     * What it does not name is an execution that ended before that: whoever needs the files of such an execution kept until something else has been done (its record written) protects the
     * id itself, from before it calls {@link #start}.
     */
    Set<String> runningIds();
}
