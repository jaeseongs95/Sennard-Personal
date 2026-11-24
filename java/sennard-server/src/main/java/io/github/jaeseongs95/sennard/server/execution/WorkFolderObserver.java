// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.execution;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.app.journal.BoundedScanner;
import io.github.jaeseongs95.sennard.server.config.SennardProperties;

/**
 * Looks at a working folder before and after a program ran in it and says what changed. The whole folder is looked at (hidden folders too), with no list of exceptions but one: the
 * product's own state folder, if it lies in the folder, is not walked (the log files of the executions are written there by the server, and they are not the program's changes); the
 * result says it was not observed. The caller of this part decides what a change means, this part only reports it. Links are not followed ({@link BoundedScanner}).
 *
 * <p>Nothing waits for anything: executions observed in the same folder, or in folders above and below one another, run at the same time and each reports the others' ids as
 * overlapping ({@link WorkFolderOverlaps}). So this guarantees only that the look sees every change of the folder between its two moments, whoever made it: another observed
 * execution (named in the result), a program that is not observed, the host's own editing tools and the user change the folder as well, and what is reported may include their changes.
 *
 * <p>Each look costs at most what {@code sennard.run.observation-max-entries} and {@code sennard.run.observation-max-time} allow: a folder that is too large or too slow is looked at as
 * far as the bound and the result says it was not looked at whole ({@link WorkFolderChanges#incomplete()}); the execution is not refused for it. All the observations of the server
 * together hold at most {@code sennard.run.observation-max-total-entries} entries in memory ({@link ObservedEntries}): the look before holds every entry it saw, the look after only
 * what differs from it, so an observation leases the entries of its folder and one more for each change (an upper bound of the entries kept, as the entries of the look before stay leased when the look after meets them); a look that finds none left stops where it is and says so in the same way.
 */
@Component
public class WorkFolderObserver {
    private final BoundedScanner scanner;
    private final WorkFolderOverlaps overlaps;
    private final ObservedEntries entries;
    private final Path stateDirectory;
    private final long maxEntries;
    private final Duration maxTime;

    @Autowired
    public WorkFolderObserver(BoundedScanner scanner, WorkFolderOverlaps overlaps, ObservedEntries entries, SennardProperties settings) {
        this(scanner, overlaps, entries, settings.stateDir(), settings.run().observationMaxEntries(), settings.run().observationMaxTime());
    }

    /**
     * @param entries how many entries all the observations of the server hold together, at most
     * @param stateDirectory the product's state folder, which is not walked when it lies in an observed folder; null when there is none
     * @param maxEntries how many entries one look takes in
     * @param maxTime how long one look may take
     */
    public WorkFolderObserver(BoundedScanner scanner, WorkFolderOverlaps overlaps, ObservedEntries entries, Path stateDirectory, long maxEntries, Duration maxTime) {
        this.scanner = scanner;
        this.overlaps = overlaps;
        this.entries = entries;
        this.stateDirectory = stateDirectory;
        this.maxEntries = maxEntries;
        this.maxTime = maxTime;
    }

    /**
     * Registers the observation and takes the look before. The caller starts the program after this returns and calls {@link WorkFolderObservation#finish()} when the program has ended
     * (or {@link WorkFolderObservation#close()} when it never ran).
     *
     * @param executionId the execution that is to run in the folder; the other observations that overlap this one name it
     * @param folder the working folder; it must exist
     * @throws NotDirectoryException when the path is not a folder
     * @throws IOException when the folder cannot be resolved
     */
    public WorkFolderObservation begin(String executionId, Path folder) throws IOException {
        Path root = folder.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new NotDirectoryException(root.toString());
        }
        WorkFolderOverlaps.Registration registration = overlaps.register(executionId, root);
        ObservedEntries.Lease lease = entries.lease();
        boolean handedOver = false;
        try {
            BoundedScanner.Bounds bounds = new BoundedScanner.Bounds(maxEntries, maxTime, excluded());
            LookBefore before = LookBefore.take(scanner, root, bounds, lease);
            WorkFolderObservation observation = new WorkFolderObservation(scanner, registration, lease, root, bounds, before);
            handedOver = true;
            return observation;
        } finally {
            // every way out that does not hand the registration and the entries to the observation (an exception, an Error) ends the one and gives back the other
            if (!handedOver) {
                try {
                    registration.close();
                } finally {
                    lease.close();
                }
            }
        }
    }

    /**
     * The real path of the state folder as the walk meets it, so that it is recognized. A state folder that is not there yet is named as it is written (ceiling: when it is made after
     * this and the observed folder is reached through a link, the walk does not know it by that name; the server makes its state folder at its start, before any execution).
     */
    private Set<Path> excluded() throws IOException {
        if (stateDirectory == null) {
            return Set.of();
        }
        return Set.of(Files.exists(stateDirectory) ? stateDirectory.toRealPath() : stateDirectory.toAbsolutePath().normalize());
    }
}
