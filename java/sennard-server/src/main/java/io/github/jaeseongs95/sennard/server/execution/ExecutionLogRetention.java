// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.execution;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.app.execution.ExecutionLogCandidates.Candidate;
import io.github.jaeseongs95.sennard.app.execution.ExecutionLogCandidates.Position;
import io.github.jaeseongs95.sennard.app.execution.ExecutionRecord;
import io.github.jaeseongs95.sennard.app.execution.ExecutionRecords;
import io.github.jaeseongs95.sennard.server.config.SennardProperties;
import io.github.jaeseongs95.sennard.server.workflow.HostIdentity;

/**
 * Deletes the log files of the executions of this server installation when their retention is over ({@code sennard.run.log-retention} and {@code sennard.run.log-capacity}). It is
 * done at the start of the server and when a recorded execution has ended.
 *
 * <p>What is judged is every folder of the log folder of this server: those of the executions that have a record of this installation, and the folders that no record names (the
 * executions that were never recorded: a skill's {@code run}, a record that could not be written, a record of a database that was replaced). {@link LogRetentionPolicy} chooses
 * from both, in one order. A record's folder is deleted and the record says when ({@code log_removed_at}; the record stays and its log completeness stays); a folder without a record
 * is only deleted. A folder that no record names gets its age from the file system's last write in it.
 *
 * <p>What is never deleted: the files of an execution that is running in this server now (the runner says which: {@link ProcessRunner#runningIds()}), of one whose record is being
 * written ({@link ExecutionsBeingRecorded}: it may have ended already and has no record yet), of a record that is running, and of the executions the caller names
 * ({@link #cleanUp(Set)}: the one that has just ended, whose caller is told where its log is). They count in the total. A folder is deleted only when the store says it is a folder of
 * this server ({@link ExecutionLogStore#ownershipOf}: really directly in this server's log folder, whatever kind of link may be on the way, and named as an execution id); a record that
 * names another place (the state folder was moved, a setting was changed) is noted as out of reach ({@code log_unreachable_at}), once, and its files are not touched. When the store
 * cannot tell now ({@link ExecutionLogStore.Ownership.Undecided}: the real path could not be had), nothing is noted and nothing is deleted: that is logged and the next pass looks again.
 *
 * <p>What it costs: a record's bytes are the ones noted when it ended, so no folder of a record is measured (only the files of the executions running now, folders no record names,
 * and a record that noted no size: measured once and noted in the record). The records are read a page at a time, oldest first, with the sum of their bytes asked once; a pass in
 * which nothing is old and nothing is over reads one page.
 *
 * <p>A failure of one folder or record (a file that cannot be deleted, a location that is no path) is logged with its execution and does not stop the pass; the next pass tries it again.
 * Passes do not overlap: one that finds another running waits for it, then looks again.
 */
@Component
public class ExecutionLogRetention {
    private static final Logger LOG = LoggerFactory.getLogger(ExecutionLogRetention.class);
    /** How many records one query reads. */
    public static final int PAGE = 500;

    /** What one pass did: how many log folders were deleted, how many could not be, and how many records were noted as out of this server's reach. */
    public record Result(int removed, int failed, int unreachable) {
    }

    /** A folder that may be deleted: with a record of this installation, or without one. */
    private record Entry(String id, String location, long bytes, Instant agedAt, boolean recorded) {
        boolean notAfter(Position position) {
            int byTime = agedAt.compareTo(position.agedAt());
            return byTime < 0 || (byTime == 0 && id.compareTo(position.id()) <= 0);
        }
    }

    private final ExecutionRecords records;
    private final ExecutionLogStore store;
    private final HostIdentity host;
    private final ProcessRunner runner;
    private final ExecutionsBeingRecorded recording;
    private final LogRetentionPolicy policy;
    private final Clock clock;
    private final int page;

    @Autowired
    public ExecutionLogRetention(ExecutionRecords records, ExecutionLogStore store, HostIdentity host, ProcessRunner runner, ExecutionsBeingRecorded recording, SennardProperties settings,
            Clock clock) {
        this(records, store, host, runner, recording, settings, clock, PAGE);
    }

    /** @param page how many records one query reads */
    public ExecutionLogRetention(ExecutionRecords records, ExecutionLogStore store, HostIdentity host, ProcessRunner runner, ExecutionsBeingRecorded recording, SennardProperties settings,
            Clock clock, int page) {
        if (page < 1) {
            throw new IllegalArgumentException("A page is at least one record: " + page);
        }
        this.records = records;
        this.store = store;
        this.host = host;
        this.runner = runner;
        this.recording = recording;
        this.policy = new LogRetentionPolicy(settings.run().logRetention(), settings.run().logCapacity().toBytes());
        this.clock = clock;
        this.page = page;
    }

    public Result cleanUp() {
        return cleanUp(Set.of());
    }

    /**
     * One pass.
     *
     * @param keep ids of executions whose files are not deleted by this pass (they count in the total); the one that has just ended is named so, because the caller that was told
     *     where its log is would find nothing there
     */
    public synchronized Result cleanUp(Set<String> keep) {
        String hostId = host.id();
        Instant now = clock.instant();
        Pass pass = new Pass(hostId, now, keep);
        pass.collect();
        pass.deleteWhatIsOver();
        if (pass.removed > 0 || pass.failed > 0 || pass.unreachable > 0) {
            LOG.info("Log retention: the log files of {} execution(s) were deleted, {} could not be, {} record(s) name a place out of this server's reach.", pass.removed, pass.failed,
                    pass.unreachable);
        }
        return new Result(pass.removed, pass.failed, pass.unreachable);
    }

    /** One pass of the retention: what it has found and what it has done. */
    private final class Pass {
        private final String hostId;
        private final Instant now;
        private final Set<String> keep;
        /** The folders no record of this installation names, the oldest first. */
        private final List<Entry> unrecorded = new ArrayList<>();
        /** Everything the log files take that this pass knows of; what is deleted is taken off. */
        private long total;
        private int removed;
        private int failed;
        private int unreachable;

        Pass(String hostId, Instant now, Set<String> keep) {
            this.hostId = hostId;
            this.now = now;
            this.keep = keep;
        }

        /**
         * Lists the folders, then asks the runner what runs and what is being recorded (in that order: a folder that is in the list was made after its id was in the runner's answer,
         * and one that has ended before its record was written is in the answer of the recording), and adds up the sizes.
         */
        void collect() {
            List<ExecutionLogStore.Folder> folders = folders();
            Set<String> live = new HashSet<>(runner.runningIds());
            live.addAll(recording.ids());
            Set<String> withRecord = records.withLogFolders(hostId, folders.stream().map(ExecutionLogStore.Folder::id).toList());
            long running = 0;
            Set<String> counted = new HashSet<>();
            for (ExecutionRecord record : records.running(hostId)) {
                if (record.logLocation() != null && isOurs(record) && counted.add(record.id())) {
                    running += sizeOf(record.id(), record.logLocation());
                }
            }
            long unrecordedBytes = 0;
            for (ExecutionLogStore.Folder folder : folders) {
                if (withRecord.contains(folder.id())) {
                    continue;
                }
                if (live.contains(folder.id())) {
                    // running in this server, or being recorded, and not (yet) recorded: never deleted, and it counts
                    if (counted.add(folder.id())) {
                        running += sizeOf(folder.id(), folder.location());
                    }
                    continue;
                }
                ExecutionLogStore.Measure measure = measure(folder);
                if (measure != null) {
                    unrecorded.add(new Entry(folder.id(), folder.location(), measure.bytes(), measure.lastModified(), false));
                    unrecordedBytes += measure.bytes();
                }
            }
            unrecorded.sort(Comparator.comparing(Entry::agedAt).thenComparing(Entry::id));
            total = running + unrecordedBytes + records.loggedBytes(hostId);
        }

        /** The pages of records, oldest first, with the folders no record names placed among them by age; stops when a page has nothing to delete. */
        void deleteWhatIsOver() {
            Position after = null;
            int nextUnrecorded = 0;
            while (true) {
                List<Candidate> candidates = records.logCandidates(hostId, after, page);
                boolean more = candidates.size() == page;
                Position last = candidates.isEmpty() ? null : Position.of(candidates.getLast());
                List<Entry> window = new ArrayList<>();
                for (Candidate candidate : candidates) {
                    Entry entry = entryOf(candidate);
                    if (entry != null && !keep.contains(entry.id())) {
                        window.add(entry);
                    }
                }
                // a page is the oldest of what is left: the folders without a record that are not newer than its last record belong to it, all of them when it is the last page
                while (nextUnrecorded < unrecorded.size() && (!more || unrecorded.get(nextUnrecorded).notAfter(last))) {
                    window.add(unrecorded.get(nextUnrecorded++));
                }
                if (!window.isEmpty() && !deleteChosen(window)) {
                    return;
                }
                if (!more) {
                    return;
                }
                after = last;
            }
        }

        /** @return whether the policy chose any: when it chose none of the oldest, it chooses none of the newer */
        private boolean deleteChosen(List<Entry> window) {
            Map<String, Entry> byId = new HashMap<>();
            long windowBytes = 0;
            for (Entry entry : window) {
                byId.put(entry.id(), entry);
                windowBytes += entry.bytes();
            }
            List<LogRetentionPolicy.Ended> ended = window.stream().map(entry -> new LogRetentionPolicy.Ended(entry.id(), entry.agedAt(), entry.bytes())).toList();
            List<LogRetentionPolicy.Ended> chosen = policy.select(ended, Math.max(0, total - windowBytes), now);
            for (LogRetentionPolicy.Ended each : chosen) {
                delete(byId.get(each.id()));
            }
            return !chosen.isEmpty();
        }

        /** Whether the folder of a running record is this server's; one whose ownership cannot be told now counts as nothing in this pass (and nothing is noted in the record). */
        private boolean isOurs(ExecutionRecord record) {
            return switch (store.ownershipOf(record.logLocation())) {
                case ExecutionLogStore.Ownership.Ours ours -> true;
                case ExecutionLogStore.Ownership.NotOurs notOurs -> false;
                case ExecutionLogStore.Ownership.Undecided undecided -> {
                    LOG.warn("Whether the log folder {} of the running execution {} is in this server's log folder cannot be told now ({}); it is not counted in this pass.",
                            record.logLocation(), record.id(), undecided.reason().toString());
                    yield false;
                }
            };
        }

        /** A candidate record as an entry; null when it is not this server's to judge (out of reach: noted) or when it could not be looked at (logged). */
        private Entry entryOf(Candidate candidate) {
            try {
                switch (store.ownershipOf(candidate.location())) {
                    case ExecutionLogStore.Ownership.Ours ours -> {
                        // judged below
                    }
                    case ExecutionLogStore.Ownership.NotOurs notOurs -> {
                        noteUnreachable(candidate);
                        return null;
                    }
                    case ExecutionLogStore.Ownership.Undecided undecided -> {
                        // not known now: nothing is noted in the record, and the next pass looks again
                        LOG.warn("Whether the log folder {} of execution {} is in this server's log folder cannot be told now ({}); the record is not touched and the next pass tries again.",
                                candidate.location(), candidate.id(), undecided.reason().toString());
                        return null;
                    }
                }
                long bytes;
                if (candidate.bytes() != null) {
                    bytes = candidate.bytes();
                } else {
                    // no size was noted when it ended: it is measured now, counts in this pass's total, and is noted in the record so that no pass measures it again and the sum has it
                    Long measured = measuredBytes(candidate.id(), candidate.location());
                    bytes = measured == null ? 0 : measured;
                    if (measured != null) {
                        records.logBytesNoted(candidate.id(), measured);
                    }
                    total += bytes;
                }
                return new Entry(candidate.id(), candidate.location(), bytes, candidate.agedAt(), true);
            } catch (RuntimeException unjudged) {
                LOG.error("The log folder {} of execution {} could not be looked at; the next pass tries again.", candidate.location(), candidate.id(), unjudged);
                return null;
            }
        }

        private void noteUnreachable(Candidate candidate) {
            if (records.logUnreachable(candidate.id(), now)) {
                unreachable++;
                total -= candidate.bytes() == null ? 0 : candidate.bytes();
                LOG.error("The log folder {} of execution {} is not in this server's log folder; it is not touched and the record is not looked at again.", candidate.location(), candidate.id());
            }
        }

        private void delete(Entry entry) {
            try {
                store.delete(entry.location());
                if (entry.recorded()) {
                    records.logRemoved(entry.id(), now);
                }
            } catch (IOException | RuntimeException notDeleted) {
                failed++;
                LOG.error("The log files of execution {} in {} could not be deleted; the next pass tries again.", entry.id(), entry.location(), notDeleted);
                return;
            }
            removed++;
            total -= entry.bytes();
        }

        private List<ExecutionLogStore.Folder> folders() {
            try {
                return store.folders();
            } catch (IOException | RuntimeException unlisted) {
                LOG.error("The log folder of this server could not be listed; the folders that no record names are not looked at in this pass.", unlisted);
                return List.of();
            }
        }

        private ExecutionLogStore.Measure measure(ExecutionLogStore.Folder folder) {
            try {
                return store.measure(folder.location());
            } catch (IOException | RuntimeException unmeasured) {
                LOG.error("The log folder {} could not be measured; it is not judged in this pass.", folder.location(), unmeasured);
                return null;
            }
        }

        /** How much the files of a folder take; one that cannot be measured is logged and counts as nothing. */
        private long sizeOf(String id, String location) {
            Long measured = measuredBytes(id, location);
            return measured == null ? 0 : measured;
        }

        /** How much the files of a folder take; {@code null} when it could not be measured (logged): nothing is known then, and nothing is noted. */
        private Long measuredBytes(String id, String location) {
            try {
                return store.sizeOf(location);
            } catch (IOException | RuntimeException unmeasured) {
                LOG.error("The log files of execution {} in {} could not be measured.", id, location, unmeasured);
                return null;
            }
        }
    }
}
