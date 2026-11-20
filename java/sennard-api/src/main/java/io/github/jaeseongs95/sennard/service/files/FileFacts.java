// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.files;

import java.util.List;
import java.util.Optional;

/**
 * The only way a request value reaches a file (I8, ARCH-FS-1). The implementation opens the file on this
 * PC.
 * Every path check and size bound lives in the implementation; a caller cannot open a path around it.
 */
public interface FileFacts {
    /** The bytes of a regular file named by an absolute local path. UNC and device paths are refused. */
    byte[] read(String absolutePath, int maxBytes);

    /** The bytes of a regular file under a real directory root, reached through no symbolic link. */
    byte[] readUnderRoot(String root, String relative, int maxBytes);

    /** Whether anything exists at the relative path, without following a final symbolic link. */
    boolean existsUnderRoot(String root, String relative);

    /** A directory's entries with no symbolic link in the way, or empty when the directory does not exist. */
    Optional<List<DirectoryEntry>> list(String directory);

    /** Whether an absolute path names a directory with no symbolic link at any component. */
    boolean isRealDirectory(String absolutePath);

    /** An existing path's real location and what it itself is (a link is reported as a link), or empty when it does not exist or is a broken link. */
    Optional<PathState> stat(String absolutePath);

    /** What a path under a root is now, not following a final link, with a whole-file SHA-256 of any size; empty when nothing is there. */
    Optional<PathFingerprint> fingerprint(String root, String relative);

    enum EntryType { FILE, DIRECTORY, LINK, OTHER }

    record DirectoryEntry(String name, EntryType type, long size) {
    }

    record PathState(EntryType type, String realPath, long size) {
    }

    /** {@code sha256} is lower-case hexadecimal of a file's bytes or a link's target, null for anything else. */
    record PathFingerprint(EntryType type, String sha256, long size) {
    }
}
