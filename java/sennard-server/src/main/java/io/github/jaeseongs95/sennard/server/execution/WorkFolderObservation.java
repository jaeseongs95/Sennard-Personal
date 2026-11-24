// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.execution;

import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import io.github.jaeseongs95.sennard.app.journal.BoundedScanner;

/**
 * One working folder that is watched for changes: the look before is taken and the observation is registered ({@link WorkFolderOverlaps}) until {@link #finish()} takes the look after
 * and says what changed, or {@link #close()} ends it without looking. Whichever comes first ends it; the other does nothing after.
 *
 * <p>The entries the looks hold in memory are taken from the server's ({@link ObservedEntries}) and given back when the observation ends, whichever way it ends. The look before holds one
 * for each entry of the folder, until then. The look after is compared with it as it is walked ({@link LookAfter}) and holds one only for what differs, so the observation of a folder of
 * N entries leases N entries and one more for each change, not 2N (the entries of the look before that the look after met stay leased until the observation ends, so the lease is an
 * upper bound of the entries kept: at least as many as are kept). The look before is the observation's until it ends: {@code finish} hands it to the look after, and {@code close} drops it, so the
 * observation refers to it no more once it has ended, and what the caller is given ({@link WorkFolderChanges}) holds only the paths of the changes.
 *
 * <p>It is held by the observation and not by a thread, so the program being watched may outlive the call that started it, and whoever notices its end finishes the observation. It
 * holds nothing back: while it is going on another execution may be observed in the same folder (or above or below it), and the two name each other in what they report.
 */
public final class WorkFolderObservation implements AutoCloseable {
    private final BoundedScanner scanner;
    private final WorkFolderOverlaps.Registration registration;
    private final ObservedEntries.Lease entries;
    private final Path root;
    private final BoundedScanner.Bounds bounds;
    /** The look before until the observation ends, and null after it: whoever takes it out ends the observation. */
    private final AtomicReference<LookBefore> before;

    /** @param entries the lease the look before was taken with; this gives it back when the observation ends */
    WorkFolderObservation(BoundedScanner scanner, WorkFolderOverlaps.Registration registration, ObservedEntries.Lease entries, Path root, BoundedScanner.Bounds bounds, LookBefore before) {
        this.scanner = scanner;
        this.registration = registration;
        this.entries = entries;
        this.root = root;
        this.bounds = bounds;
        this.before = new AtomicReference<>(before);
    }

    /** The folder as the caller named it, made absolute and normalized: the paths of the changes are relative to it. */
    public Path folder() {
        return root;
    }

    /**
     * Takes the look after, ends the observation and answers what changed since the look before, and which other observations overlapped this one until now. The entries the observation
     * held are given back as the answer is handed over; the answer holds the paths of what differs (at most the entries of the two looks, each bounded by
     * {@code sennard.run.observation-max-entries}), and is not counted in the server's total.
     *
     * @throws IllegalStateException when the observation has ended already
     */
    public WorkFolderChanges finish() {
        LookBefore lookBefore = before.getAndSet(null);
        if (lookBefore == null) {
            throw new IllegalStateException("The observation of " + root + " has ended already");
        }
        try {
            LookAfter after = LookAfter.take(scanner, root, bounds, lookBefore, entries);
            // the overlap is read as the registration ends: an observation that begins after this point does not overlap this one
            Set<String> overlapping = registration.release();
            return after.changes(overlapping);
        } finally {
            end();
        }
    }

    /** Ends the observation without a look after; does nothing when it has ended. */
    @Override
    public void close() {
        if (before.getAndSet(null) != null) {
            end();
        }
    }

    /** Ends the registration and gives the entries back: the second even when the first fails. */
    private void end() {
        try {
            registration.close();
        } finally {
            entries.close();
        }
    }
}
