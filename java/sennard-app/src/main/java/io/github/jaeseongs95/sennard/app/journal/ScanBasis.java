// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.journal;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a scan of a workflow's write roots starts from, as one read-only use case of {@link ChangeJournalService} read it: the roots to walk, the files of the plan that is current,
 * the last observation of every file, and the paths whose last read failed. {@code version} is the version of the journal at that read; the write use case that is given the scan's
 * result names it in its conditional {@code UPDATE}, so a write whose basis is out of date changes nothing and fails.
 *
 * @param workflowId   the workflow whose journal this is
 * @param version      the journal's version when the basis was read
 * @param roots        the write roots to walk, as the journal spells them
 * @param plannedFiles the files of the current plan revision (they are hashed at the baseline)
 * @param previous     the last observation of each file that existed, by path (empty at the baseline)
 * @param failedPaths  the paths whose last read failed (they are read again whatever their metadata says)
 */
public record ScanBasis(String workflowId, long version, List<Path> roots, Set<Path> plannedFiles, Map<Path, JournalScanner.Metadata> previous, Set<Path> failedPaths) {
    public ScanBasis {
        roots = List.copyOf(roots);
        plannedFiles = Set.copyOf(plannedFiles);
        previous = Map.copyOf(previous);
        failedPaths = Set.copyOf(failedPaths);
    }
}
