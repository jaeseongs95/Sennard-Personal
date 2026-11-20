// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook;

import java.util.Optional;

/**
 * How a shell the host runs its commands in spells paths, where that is not how this file system spells them: the code that decides which files a command writes
 * and whether a declared path is absolute reads a shell's paths through this interface and never by their spelling. {@link java.nio.file.Path} follows the file
 * system the program runs on, so a path the shell wrote in a spelling of its own ({@code /d/ws/f} for the drive path {@code D:\ws\f}, the shell's own root
 * {@code /tmp/x}) is text to it; what the shell's spelling means is the implementation's, one per shell.
 *
 * <p>The values are the shell's paths as text: {@code place} is a path in the shell's own tree, absolute, with {@code /} for separator and {@code ..} resolved
 * against its root.
 */
public interface ShellPaths {
    /** The file system's spelling of a path of the shell's that names a drive ({@code /d/ws/f} is {@code D:\ws\f}), or the value as it is. */
    String windowsSpelling(String value);

    /** A path of the shell's own root: it starts with one slash and names no drive, so this server cannot place it on the file system. */
    boolean rootRelative(String value);

    /** The place a path names, with separators made one kind, empty and {@code .} segments dropped and {@code ..} resolved against the root, which it never goes above. */
    String normalizeRooted(String value);

    /** Whether a normalized place is the normalized place {@code root} or lies under it, on a whole segment; a segment {@code *} of the root stands for any one segment. */
    boolean within(String place, String root);

    /** The place {@code name} has in {@code directory}, normalized. */
    String childOf(String directory, String name);

    /** The last name of a path of either separator, without the separators that end it. */
    String baseName(String value);

    /** The directory part of a path of either separator: {@code .} for a name alone, {@code /} for a name directly under the root. */
    String dirName(String value);

    /** Whether the path is written as naming a directory (it ends with a slash). */
    boolean namesDirectory(String value);

    /** Whether a place is no file but a device of the shell's own tree ({@code /dev}), so that a write to it changes no file. */
    boolean holdsNoFile(String place);

    /**
     * Where the shell puts a path, in its own tree: the place of the path when it starts with one slash; the place the path has in {@code shellDirectory} when it is
     * relative there; none for a path that is not in the shell's tree (a drive, a UNC path, or a relative path when the shell's directory is not known in it).
     *
     * @param shellDirectory the shell's directory as a place of its own tree, or null when it is not known there
     * @param fileSystemAbsolute whether this file system takes the value as an absolute path, which is then not the shell's to place
     */
    Optional<String> placeOf(String value, String shellDirectory, boolean fileSystemAbsolute);
}
