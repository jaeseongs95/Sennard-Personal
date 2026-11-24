// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.execution;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Which log files are to be deleted: those that are older than the retention, then, while all the log files together take more than the capacity, the oldest of the others. It only
 * chooses: it reads no file and no clock (the caller gives it the time, the sizes and the age of each), so what it does is the same on every computer.
 *
 * @param retention how long after its age counts from the log files of an execution are kept
 * @param capacityBytes how many bytes all the log files may take together, the ones that are never chosen included
 */
public record LogRetentionPolicy(Duration retention, long capacityBytes) {
    /**
     * The log files of one execution that may be deleted.
     *
     * @param id the id of the execution, or of the folder when no record names it
     * @param endedAt the time its retention counts from: its end for an execution with a record (its start for one that has no end time), and for a folder that no record names the
     *     last time a file in it was written, as the file system says
     * @param bytes how much its log files take
     */
    public record Ended(String id, Instant endedAt, long bytes) {
    }

    public LogRetentionPolicy {
        if (retention == null || retention.isNegative() || retention.isZero()) {
            throw new IllegalArgumentException("The retention is longer than zero: " + retention);
        }
        if (capacityBytes < 1) {
            throw new IllegalArgumentException("The capacity is at least one byte: " + capacityBytes);
        }
    }

    /**
     * @param ended the log files that may be deleted
     * @param otherBytes how much the log files take that are not in {@code ended} and are never chosen (those of the running executions, of the one that has just ended, and the ones
     *     outside this list); they count in the total, so they make more of the listed ones go
     * @param now the moment the retention is judged at
     * @return the ones to delete, the oldest first
     */
    public List<Ended> select(List<Ended> ended, long otherBytes, Instant now) {
        List<Ended> oldestFirst = ended.stream().sorted(Comparator.comparing(Ended::endedAt).thenComparing(Ended::id)).toList();
        Instant oldestKept = now.minus(retention);
        List<Ended> chosen = new ArrayList<>();
        List<Ended> kept = new ArrayList<>();
        long total = otherBytes;
        for (Ended each : oldestFirst) {
            if (each.endedAt().isAfter(oldestKept)) {
                kept.add(each);
                total += each.bytes();
            } else {
                chosen.add(each);
            }
        }
        for (Ended each : kept) {
            if (total <= capacityBytes) {
                break;
            }
            chosen.add(each);
            total -= each.bytes();
        }
        return List.copyOf(chosen);
    }
}
