// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.execution;

import java.util.List;

import io.github.jaeseongs95.sennard.app.journal.BoundedScanner.StopReason;

/**
 * What changed in a working folder between two looks. Every path is relative to the folder, written with {@code /} whatever the file system separates names with, and each list is
 * sorted, so the same change gives the same answer everywhere.
 *
 * <p>A file is {@code modified} when its size or its modification time (to the millisecond) differs; the content is not read. Ceilings, changes that are not seen: a program that
 * rewrites a file to the same size and puts its time back; a rewrite to the same size that the file system stamps with the same time, because it happens within one tick of the file
 * system's clock (two rewrites in a row), or because that clock is coarse (a file system that stamps in whole seconds, or every two seconds as FAT does, does not tell a rewrite within
 * that resolution from the file as it was). A link is not followed and not read: one that appeared is {@code created}, one that went is {@code deleted} (ceiling: a link that is
 * pointed elsewhere is not seen). A folder that appeared or went, empty or not, is in {@code createdDirectories} or {@code deletedDirectories}.
 *
 * <p>A path that could not be read at either look cannot be compared, and neither can anything below it: a folder that could not be opened, or whose listing failed halfway, may have
 * had anything in it at the look that could not read it, and what it had after the failure point is not known. The path is in {@code unreadable} (the path and the reason), and it and
 * every path below it (files, folders and links; the names are compared whole, so {@code D2} is not below {@code D}; when the folder itself, {@code .}, could not be read, every path is) is
 * in none of the lists of created and deleted, whichever look could not read it: a change is never claimed for them and the lack of one is not claimed either ({@link #complete()} is false).
 * A file that both looks saw is compared, and reported as {@code modified} when it differs, even when it is below such a path (a listing that failed halfway handed it over before it
 * failed, and the folder itself, {@code .}, may be the one that failed).
 *
 * <p>A look that reached its bound (the number of entries or the time, {@code sennard.run.observation-max-*}, or the entries that all the looks of the server may hold together,
 * {@code sennard.run.observation-max-total-entries}) did not see the whole folder: it is in {@code incomplete}, with why and how many entries it saw, and {@link #complete()} is
 * false. Which paths a look did not see is not known, and the order a folder is walked in is not fixed, so when either look is incomplete only the paths that both looks saw are
 * compared: nothing is reported as created or deleted (a path one look did not see may be there all along), and a file that both saw is reported as modified when it is. A folder that
 * a look was stopped inside of exists, and so do the folders above it (the folder itself among them): it is not created or deleted for that look, whichever look was stopped. A path
 * that was a folder at one look and a file at the other is both seen, and is reported on both sides (the file as created or deleted, the folder as deleted or created). Absence from
 * the lists of such a result is not a finding.
 *
 * <p>A folder that was not looked at by design (the product's own state folder: the logs of the executions are written by the server, not by the program) is in
 * {@code notObserved}; it is not walked at either look, so nothing in it is reported and no list says anything of it, and it does not make the result incomplete.
 *
 * <p>The result is a plain value that the caller keeps as long as it likes, and it is not held by the observation any more once it is handed over (the entries of the server's total
 * are given back then, {@link ObservedEntries}): it holds the paths of the entries of the two looks that differ, so at most twice {@code sennard.run.observation-max-entries} paths
 * (created, modified and the folders created from the look after, deleted and the folders deleted from the look before), whatever the total of the server is.
 *
 * <p>An observation that overlapped that of another execution (the same folder, or one above or below it, at any time between the two looks) names it in
 * {@code overlappingExecutions}: a change in the lists may be that execution's ({@link #attributionCertain()} is false). The lists still say what changed, not who changed it.
 *
 * @param created files and links that did not exist before
 * @param modified files that were there before and are changed
 * @param deleted files and links that are gone
 * @param createdDirectories folders that did not exist before
 * @param deletedDirectories folders that are gone
 * @param unreadable paths that could not be read, as {@code path (REASON)}
 * @param notObserved folders that were not walked, relative to the folder
 * @param incomplete the looks that stopped at a bound
 * @param overlappingExecutions the ids of the other executions whose observation overlapped this one, sorted
 */
public record WorkFolderChanges(List<String> created, List<String> modified, List<String> deleted, List<String> createdDirectories, List<String> deletedDirectories, List<String> unreadable,
        List<String> notObserved, List<Incomplete> incomplete, List<String> overlappingExecutions) {
    /** Which of the two looks. */
    public enum Look { BEFORE, AFTER }

    /**
     * A look that did not see the whole folder.
     *
     * @param look which look
     * @param reason the bound it reached
     * @param entriesSeen how many entries (folders, files, links) it saw before it stopped
     */
    public record Incomplete(Look look, StopReason reason, long entriesSeen) {
    }

    public WorkFolderChanges {
        created = List.copyOf(created);
        modified = List.copyOf(modified);
        deleted = List.copyOf(deleted);
        createdDirectories = List.copyOf(createdDirectories);
        deletedDirectories = List.copyOf(deletedDirectories);
        unreadable = List.copyOf(unreadable);
        notObserved = List.copyOf(notObserved);
        incomplete = List.copyOf(incomplete);
        overlappingExecutions = List.copyOf(overlappingExecutions);
    }

    /** Whether nothing that could be read was created, modified or deleted. */
    public boolean unchanged() {
        return created.isEmpty() && modified.isEmpty() && deleted.isEmpty() && createdDirectories.isEmpty() && deletedDirectories.isEmpty();
    }

    /** Whether every path of the folder (but the {@code notObserved} ones) could be read, and was looked at, at both looks. */
    public boolean complete() {
        return unreadable.isEmpty() && incomplete.isEmpty();
    }

    /** Whether no other execution's observation overlapped this one, so that what changed is this execution's alone (as far as observed executions go). */
    public boolean attributionCertain() {
        return overlappingExecutions.isEmpty();
    }
}
