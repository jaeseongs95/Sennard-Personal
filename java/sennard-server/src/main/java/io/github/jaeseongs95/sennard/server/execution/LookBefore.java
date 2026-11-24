// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.execution;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import io.github.jaeseongs95.sennard.app.journal.BoundedScanner;
import io.github.jaeseongs95.sennard.app.journal.BoundedScanner.FileStamp;
import io.github.jaeseongs95.sennard.app.journal.BoundedScanner.LookSink;
import io.github.jaeseongs95.sennard.app.journal.BoundedScanner.StopReason;
import io.github.jaeseongs95.sennard.app.journal.BoundedScanner.Walked;
import io.github.jaeseongs95.sennard.app.journal.JournalScanner.FailureReason;

/**
 * The look at a working folder before the program ran: everything the walk saw of it, one entry of the observation's lease for each. It is kept until the program has ended, and then the
 * look after ({@link LookAfter}) takes it over: what the look after finds as it was is marked as met in this one (the entry stays, with the mark in place of its stamp, so that a path the
 * look after is handed a second time is known to have been met and is not taken for a new one), and what is not marked when the look after has ended is what it did not find.
 *
 * <p>A path is one thing at a look: a file, a folder (one that was finished and one that the walk was inside of when it stopped are alike, both exist), a link or special file that was
 * skipped, or a path that could not be read. Only the walking thread changes it, while the look is taken; after that it belongs to the look after, which is called by one thread.
 */
final class LookBefore implements LookSink {
    private final ObservedEntries.Lease entries;
    /** The stamp of a file that the look after has met: compared by identity, and never a stamp the walk made. */
    static final FileStamp MET = new FileStamp(-1, -1);

    /** What taking a folder or a link out of the look found. */
    enum Taken {
        /** The look did not see the path as that kind. */
        NOT_SEEN,
        /** It did, and the look after takes it now. */
        NOW,
        /** It did, and the look after took it before. */
        BEFORE
    }

    private final Map<String, FileStamp> files = new HashMap<>();
    /** The folders and the links: true while the look after has not met them. */
    private final Map<String, Boolean> directories = new HashMap<>();
    private final Map<String, Boolean> links = new HashMap<>();
    private final Map<String, FailureReason> unreadable = new HashMap<>();
    private final Set<String> notWalked = new HashSet<>();
    private Walked walked;

    private LookBefore(ObservedEntries.Lease entries) {
        this.entries = entries;
    }

    /** Takes the look: the walk of {@code root} within {@code bounds}, kept with the entries of the lease. */
    static LookBefore take(BoundedScanner scanner, Path root, BoundedScanner.Bounds bounds, ObservedEntries.Lease entries) {
        LookBefore look = new LookBefore(entries);
        look.walked = scanner.walk(root, bounds, look);
        return look;
    }

    @Override
    public boolean file(String path, FileStamp stamp) {
        if (!entries.take()) {
            return false;
        }
        files.put(path, stamp);
        return true;
    }

    @Override
    public boolean failure(String path, FailureReason reason) {
        if (!entries.take()) {
            return false;
        }
        if (reason == FailureReason.LINK_OR_SPECIAL_SKIPPED) {
            links.put(path, Boolean.TRUE);
        } else {
            unreadable.put(path, reason);
        }
        return true;
    }

    @Override
    public boolean directoryEntered(String path) {
        if (!entries.take()) {
            return false;
        }
        directories.put(path, Boolean.TRUE);
        return true;
    }

    /** The folder was one entry and is still one: it is a path that could not be read now. */
    @Override
    public boolean directoryFailed(String path, FailureReason reason) {
        directories.remove(path);
        unreadable.put(path, reason);
        return true;
    }

    @Override
    public void notWalked(String path) {
        notWalked.add(path);
    }

    /** Whether the look saw the whole folder. */
    boolean complete() {
        return walked.complete();
    }

    /** Why the look stopped early; null when it did not. */
    StopReason stoppedBy() {
        return walked.stoppedBy();
    }

    /** How many entries the look looked at. */
    long entriesSeen() {
        return walked.entries();
    }

    /** Marks a file as met: its stamp when the look saw it as a file, {@link #MET} when the look after met it before, null when the look did not see it as a file. */
    FileStamp takeFile(String path) {
        return files.replace(path, MET);
    }

    /** Marks a folder as met. */
    Taken takeDirectory(String path) {
        return taken(directories, path);
    }

    /** Marks a link (or special file) as met. */
    Taken takeLink(String path) {
        return taken(links, path);
    }

    private static Taken taken(Map<String, Boolean> kind, String path) {
        Boolean left = kind.replace(path, Boolean.FALSE);
        if (left == null) {
            return Taken.NOT_SEEN;
        }
        return left ? Taken.NOW : Taken.BEFORE;
    }

    /** The paths that could not be read, and why. */
    Map<String, FailureReason> unreadable() {
        return unreadable;
    }

    /** The folders that were not walked by design. */
    Set<String> notWalked() {
        return notWalked;
    }

    /** The files not met: the ones the look after did not find, once it has ended. */
    Set<String> files() {
        return files.entrySet().stream().filter(entry -> entry.getValue() != MET).map(Map.Entry::getKey).collect(Collectors.toSet());
    }

    /** The folders not met. */
    Set<String> directories() {
        return notMet(directories);
    }

    /** The links not met. */
    Set<String> links() {
        return notMet(links);
    }

    private static Set<String> notMet(Map<String, Boolean> kind) {
        return kind.entrySet().stream().filter(Map.Entry::getValue).map(Map.Entry::getKey).collect(Collectors.toSet());
    }
}
