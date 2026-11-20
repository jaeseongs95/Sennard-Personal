// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.journal;

import java.nio.file.Path;
import java.util.Map;

/**
 * What the read of one file an edit tool changed starts from: the write root that holds the file, the file as the journal spells it, and the last observation of every file. As with
 * {@link ScanBasis}, {@code version} is the journal's version at the read, which the write use case names in its conditional {@code UPDATE}.
 *
 * @param workflowId the workflow whose journal this is
 * @param version    the journal's version when the basis was read
 * @param root       the write root that holds {@code file}
 * @param file       the file, normalized
 * @param previous   the last observation of each file that existed, by path
 */
public record HookBasis(String workflowId, long version, Path root, Path file, Map<Path, JournalScanner.Metadata> previous) {
    public HookBasis {
        previous = Map.copyOf(previous);
    }
}
