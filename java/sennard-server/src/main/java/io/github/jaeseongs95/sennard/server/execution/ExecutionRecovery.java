// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.execution;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.app.execution.ExecutionRecord;
import io.github.jaeseongs95.sennard.app.execution.ExecutionRecords;
import io.github.jaeseongs95.sennard.app.execution.ExecutionStatus;
import io.github.jaeseongs95.sennard.app.execution.LogCompleteness;
import io.github.jaeseongs95.sennard.server.workflow.HostIdentity;

/**
 * Accounts for the executions a server before this one left running. At the start every record of this server installation ({@link HostIdentity}) that is still RUNNING belongs to a
 * process that no runner of this server holds. The records of other installations are not looked at: several servers on several computers may share one database, and their
 * processes are on their computers.
 * <ul>
 * <li>the same process (id and start time) is alive: it and what it started are killed and the record is closed as KILLED, ended now;</li>
 * <li>no such process: the record is closed as LOST. How that execution ended is not known and is not made up as a success or a failure, and neither is when it ended: a LOST
 * record has no end time;</li>
 * <li>a process that cannot be told from another (no recorded start time), or a record with no process: LOST, nothing is touched. The process may still be alive;</li>
 * <li>the process was found and did not end within the implementation's grace: the record stays RUNNING, and the next start looks again.</li>
 * </ul>
 * A record closed here has its log marked TRUNCATED: what the process wrote last may not have reached the files.
 *
 * <p>It runs once at the start, before any request can start an execution; otherwise it would take an execution of this server for an orphan.
 */
@Component
public class ExecutionRecovery {
    private static final Logger LOG = LoggerFactory.getLogger(ExecutionRecovery.class);

    /** How many records were closed as what, and how many were left running because their process survived. */
    public record Result(int killed, int lost, int stillRunning) {
    }

    private final ExecutionRecords records;
    private final OrphanProcesses orphans;
    private final HostIdentity host;
    private final Clock clock;
    private final ExecutionLogStore logs;

    public ExecutionRecovery(ExecutionRecords records, OrphanProcesses orphans, HostIdentity host, Clock clock, ExecutionLogStore logs) {
        this.records = records;
        this.orphans = orphans;
        this.host = host;
        this.clock = clock;
        this.logs = logs;
    }

    public Result recover() {
        int killed = 0;
        int lost = 0;
        int surviving = 0;
        for (ExecutionRecord record : records.running(host.id())) {
            ExecutionStatus end = endOf(record);
            if (end == null) {
                surviving++;
                LOG.error("The process {} of execution {} was still alive after it was killed; its record stays running.", record.pid(), record.id());
                continue;
            }
            // what became of a lost execution, and when, is not known: the record has no end time
            if (records.finish(record.id(), end, null, end == ExecutionStatus.LOST ? null : clock.instant(), LogCompleteness.TRUNCATED, LogSizes.of(logs, record.id(), record.logLocation()))) {
                if (end == ExecutionStatus.KILLED) {
                    killed++;
                } else {
                    lost++;
                }
                LOG.info("The execution {} that a server before this one left running is now {}.", record.id(), end);
            }
        }
        return new Result(killed, lost, surviving);
    }

    /** How the record ends, or null when it cannot end yet. */
    private ExecutionStatus endOf(ExecutionRecord record) {
        if (record.pid() == null) {
            return ExecutionStatus.LOST;
        }
        return switch (orphans.terminate(new ProcessIdentity(record.pid(), record.processStartedAt()))) {
            case KILLED -> ExecutionStatus.KILLED;
            case GONE, UNVERIFIABLE -> ExecutionStatus.LOST;
            case SURVIVED -> null;
        };
    }
}
