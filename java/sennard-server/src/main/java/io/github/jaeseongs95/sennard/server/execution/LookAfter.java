// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.execution;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import io.github.jaeseongs95.sennard.app.journal.BoundedScanner;
import io.github.jaeseongs95.sennard.app.journal.BoundedScanner.FileStamp;
import io.github.jaeseongs95.sennard.app.journal.BoundedScanner.LookSink;
import io.github.jaeseongs95.sennard.app.journal.BoundedScanner.Walked;
import io.github.jaeseongs95.sennard.app.journal.JournalScanner.FailureReason;
import io.github.jaeseongs95.sennard.server.execution.LookBefore.Taken;
import io.github.jaeseongs95.sennard.server.execution.WorkFolderChanges.Incomplete;
import io.github.jaeseongs95.sennard.server.execution.WorkFolderChanges.Look;

/**
 * The look at a working folder after the program ran, compared with the look before ({@link LookBefore}) as the walk meets each entry: an entry that is as it was is taken out of the look
 * before (marked as met, not copied), so it costs the observation no entry of its lease besides the one the look before holds for it. Only what differs is kept, each with an entry of the
 * lease, and when the lease has none left the look stops where it is ({@code TOTAL_ENTRY_LIMIT}). What the look before still holds unmarked when the walk has ended is what the look after
 * did not find. An observation of a folder of N entries holds N entries of the lease and one more for each change, not 2N. The entries of the look before are not given back as they are
 * met, so the lease is an upper bound of what is kept, never less than it.
 *
 * <p>What is reported follows what each look saw (the rules are in {@link WorkFolderChanges}):
 * <ul>
 * <li>A path that was a file at one look and a folder or a link at the other (a kind that changed) was seen at both looks, whatever else is known, and is reported when the look ended
 * whole or not: those are the {@code certain} changes, with the files that both looks saw and that differ.
 * <li>A path that one look saw and the other did not is created or deleted only when both looks saw the whole folder: the {@code plain} changes. They are kept while the look after is
 * walked, and dropped when it turns out that it did not see the whole folder; they are not kept at all when the look before did not.
 * <li>A path that could not be read at either look, and everything below it, is in none of the lists of created and deleted: it is not known to be there or not to be there. A file that
 * both looks saw is compared all the same, even below such a path (a listing that failed halfway handed it over before it failed): it is in the {@code modified} ones when it differs.
 * </ul>
 *
 * <p>Called by the walking thread; the changes are made when the walk has ended ({@link #changes}), by the same thread.
 */
final class LookAfter implements LookSink {
    /** The four kinds of path a look can find new or gone. */
    private static final class Paths {
        private final Set<String> created = new HashSet<>();
        private final Set<String> deleted = new HashSet<>();
        private final Set<String> createdDirectories = new HashSet<>();
        private final Set<String> deletedDirectories = new HashSet<>();
    }

    private final LookBefore before;
    private final ObservedEntries.Lease entries;
    private final boolean beforeComplete;
    private final Paths certain = new Paths();
    private final Paths plain = new Paths();
    private final Set<String> modified = new HashSet<>();
    private final Map<String, FailureReason> unreadable = new HashMap<>();
    private final Set<String> notWalked = new HashSet<>();
    private Walked walked;

    private LookAfter(LookBefore before, ObservedEntries.Lease entries) {
        this.before = before;
        this.entries = entries;
        this.beforeComplete = before.complete();
    }

    /** Takes the look: the walk of {@code root} within {@code bounds}, compared with {@code before}, which it takes over (it is not to be used by anyone else after this). */
    static LookAfter take(BoundedScanner scanner, Path root, BoundedScanner.Bounds bounds, LookBefore before, ObservedEntries.Lease entries) {
        LookAfter look = new LookAfter(before, entries);
        look.walked = scanner.walk(root, bounds, look);
        return look;
    }

    /*
     * A path the walk hands over a second time (a folder that changes while it is listed may give an entry twice) is the first time again: what the look before had of it was marked as met
     * the first time and is found marked, a new one is in the changes already, and neither is kept again.
     */

    @Override
    public boolean file(String path, FileStamp stamp) {
        FileStamp was = before.takeFile(path);
        if (was == LookBefore.MET) {
            return true;
        }
        if (was != null) {
            return was.equals(stamp) || keep(modified, path);
        }
        Taken directory = before.takeDirectory(path);
        if (directory != Taken.NOT_SEEN) {
            return directory == Taken.BEFORE || deletedDirectoryCreatedAs(path, certain.created);
        }
        Taken link = before.takeLink(path);
        if (link != Taken.NOT_SEEN) {
            return link == Taken.BEFORE || deletedFileCreatedAs(path, certain.created);
        }
        return isNew(path) ? keepNew(plain.created, path) : true;
    }

    @Override
    public boolean directoryEntered(String path) {
        Taken directory = before.takeDirectory(path);
        if (directory != Taken.NOT_SEEN) {
            return true;
        }
        FileStamp file = before.takeFile(path);
        if (file == LookBefore.MET) {
            return true;
        }
        if (file != null) {
            return deletedFileCreatedAs(path, certain.createdDirectories);
        }
        Taken link = before.takeLink(path);
        if (link != Taken.NOT_SEEN) {
            return link == Taken.BEFORE || deletedFileCreatedAs(path, certain.createdDirectories);
        }
        return isNew(path) ? keepNew(plain.createdDirectories, path) : true;
    }

    @Override
    public boolean failure(String path, FailureReason reason) {
        if (reason != FailureReason.LINK_OR_SPECIAL_SKIPPED) {
            return keep(path, reason);
        }
        if (before.takeLink(path) != Taken.NOT_SEEN) {
            return true;
        }
        FileStamp file = before.takeFile(path);
        if (file == LookBefore.MET) {
            return true;
        }
        if (file != null) {
            return deletedFileCreatedAs(path, certain.created);
        }
        Taken directory = before.takeDirectory(path);
        if (directory != Taken.NOT_SEEN) {
            return directory == Taken.BEFORE || deletedDirectoryCreatedAs(path, certain.created);
        }
        return isNew(path) ? keepNew(plain.created, path) : true;
    }

    /** The look before saw a file or a link there, and the look after a path of another kind: it is deleted, and the path is created. */
    private boolean deletedFileCreatedAs(String path, Set<String> created) {
        certain.deleted.add(path);
        return keep(created, path);
    }

    /** The same for a folder. */
    private boolean deletedDirectoryCreatedAs(String path, Set<String> created) {
        certain.deletedDirectories.add(path);
        return keep(created, path);
    }

    @Override
    public boolean directoryFailed(String path, FailureReason reason) {
        return keep(path, reason);
    }

    @Override
    public void notWalked(String path) {
        notWalked.add(path);
    }

    /** Whether a path that was not at the look before is new: it is when that look saw the whole folder and could read the path (and what is above it). */
    private boolean isNew(String path) {
        return beforeComplete && !isOrUnder(path, before.unreadable());
    }

    /** Keeps a path that is new, once: a path handed over again is in the changes already and takes no second entry. */
    private boolean keepNew(Set<String> changes, String path) {
        return changes.contains(path) || keep(changes, path);
    }

    /** Keeps a path of a change, for one entry of the lease; false when there is none left. */
    private boolean keep(Set<String> changes, String path) {
        if (!entries.take()) {
            return false;
        }
        changes.add(path);
        return true;
    }

    /** Keeps a path that could not be read, for one entry of the lease; false when there is none left. */
    private boolean keep(String path, FailureReason reason) {
        if (unreadable.containsKey(path)) {
            return true;
        }
        if (!entries.take()) {
            return false;
        }
        unreadable.put(path, reason);
        return true;
    }

    /**
     * What changed between the look before and this one, once the walk has ended. A path that could not be read at either look, and what is below it, is in no list of created or deleted
     * paths; a file that both looks saw and that differs is in the modified ones wherever it is.
     *
     * @param overlapping the other executions whose observation overlapped this one
     */
    WorkFolderChanges changes(Set<String> overlapping) {
        boolean both = beforeComplete && walked.complete();
        List<Incomplete> incomplete = new ArrayList<>();
        if (!beforeComplete) {
            incomplete.add(new Incomplete(Look.BEFORE, before.stoppedBy(), before.entriesSeen()));
        }
        if (!walked.complete()) {
            incomplete.add(new Incomplete(Look.AFTER, walked.stoppedBy(), walked.entries()));
        }
        // when the look before is cut or the look after is, the files of the look before that this one did not find are not known to be gone: only the plain changes depend on both
        List<String> created = readable(List.of(certain.created, both ? plain.created : Set.of()));
        // a file that both looks saw is compared whatever is unreadable around it (a listing that failed halfway handed it over before it failed): no filter of unreadable paths
        List<String> modifiedPaths = modified.stream().sorted().toList();
        List<String> deleted = readable(List.of(certain.deleted, both ? before.files() : Set.of(), both ? before.links() : Set.of()));
        List<String> createdDirectories = readable(List.of(certain.createdDirectories, both ? plain.createdDirectories : Set.of()));
        List<String> deletedDirectories = readable(List.of(certain.deletedDirectories, both ? before.directories() : Set.of()));

        Set<String> notRead = new TreeSet<>();
        before.unreadable().forEach((path, reason) -> notRead.add(path + " (" + reason + ")"));
        unreadable.forEach((path, reason) -> notRead.add(path + " (" + reason + ")"));
        Set<String> notWalkedAtAny = new TreeSet<>(before.notWalked());
        notWalkedAtAny.addAll(notWalked);

        return new WorkFolderChanges(created, modifiedPaths, deleted, createdDirectories, deletedDirectories, List.copyOf(notRead), List.copyOf(notWalkedAtAny), incomplete,
                overlapping.stream().sorted().toList());
    }

    /** The paths, sorted, but the ones that are, or are below, a path that could not be read at either look. */
    private List<String> readable(List<Collection<String>> parts) {
        return parts.stream().flatMap(Collection::stream).filter(path -> !isOrUnder(path, before.unreadable()) && !isOrUnder(path, unreadable)).distinct().sorted().toList();
    }

    /**
     * Whether the path is one of the unreadable ones or is below one: it or one of the folders above it, up to the folder itself ({@code .}), is among them. The names are compared whole
     * (D2 is not below D), by taking the folders above the path one by one.
     */
    private static boolean isOrUnder(String path, Map<String, FailureReason> unreadable) {
        if (unreadable.isEmpty()) {
            return false;
        }
        for (String at = path; at != null; at = BoundedScanner.parent(at)) {
            if (unreadable.containsKey(at)) {
                return true;
            }
        }
        return false;
    }
}
