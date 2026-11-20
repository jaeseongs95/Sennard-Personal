// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.ServiceLoader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import io.github.jaeseongs95.sennard.server.hook.ShellPaths;

/**
 * Paths of the POSIX shell on Windows, as text: the same on every OS, because nothing here touches a file system. They were the cases of the static
 * {@code MsysPaths}; the implementation is the one {@link ShellPaths} has, and the cases are what its users (the admin log's exempt paths, the edit gate, the
 * shell command reader) decide by. Case table (input; expected):
 * <ol>
 * <li>a path is normalized before it is compared; it is under a root on whole segments only; one that climbs out is compared where it lands; one of the shell's
 * own root names no drive</li>
 * <li>a drive is spelled as the file system spells it; {@code /d/x}, {@code D:\x}, {@code //unc} and a relative path are told apart as before</li>
 * <li>names: the last name and the directory of a path of either separator; a place under a directory; a path names a directory when it ends with a slash;
 * {@code /dev} holds no file</li>
 * <li>where the shell puts a path: the place it names, the place it has under the shell's directory, none for a drive, a share, or a relative path with no directory
 * known</li>
 * <li>the implementation is the one {@link ServiceLoader} finds where there is no Spring context</li>
 * </ol>
 */
class MsysShellPathsTest {
    private final ShellPaths shell = new MsysShellPaths();

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"/tmp|/tmp", "/tmp/|/tmp", "/tmp/a/b|/tmp/a/b", "/tmp//a|/tmp/a", "/tmp/./a|/tmp/a", "/tmp/../etc|/etc", "/tmp/a/../../..|/", "/../x|/x", "/|/", "\\tmp\\a|/tmp/a", "/tmp/*/a|/tmp/*/a"})
    void aPathIsNormalizedBeforeItIsCompared(String given, String expected) {
        assertEquals(expected, shell.normalizeRooted(given));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"/tmp|/tmp|true", "/tmp/a|/tmp|true", "/tmp/a/b/c|/tmp|true", "/tmpx|/tmp|false", "/tmpx/a|/tmp|false", "/tm|/tmp|false", "/|/tmp|false", "/etc|/tmp|false",
            "/anything/at/all|/|true", "/tmp/a/b|/tmp/*|true", "/tmp|/tmp/*|false", "/var/tmp/a|/*/tmp|true", "/var/other/a|/*/tmp|false"})
    void aPathIsUnderARootOnWholeSegmentsOnly(String path, String root, boolean under) {
        assertEquals(under, shell.within(path, root));
    }

    @Test
    void aPathThatClimbsOutOfTheRootBeforeItIsComparedIsComparedWhereItLands() {
        assertFalse(shell.within(shell.normalizeRooted("/tmp/../etc/passwd"), "/tmp"));
        assertTrue(shell.within(shell.normalizeRooted("/etc/../tmp/a"), "/tmp"));
        assertFalse(shell.within(shell.normalizeRooted("/tmp/../../etc"), "/tmp"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"/tmp/x|true", "/etc|true", "/d/x|false", "/d|false", "//server/share|false", "x/y|false", "D:/x|false", "/dx|true", "/|true"})
    void aPathOfTheShellsOwnRootNamesNoDrive(String path, boolean rootRelative) {
        assertEquals(rootRelative, shell.rootRelative(path));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '^', value = {"/d/x|D:\\x", "/D/x|D:\\x", "/d|D:\\", "//server/share/x|//server/share/x", "D:\\x|D:\\x", "x/y|x/y", "/tmp/x|/tmp/x"})
    void aDriveIsSpelledAsTheFileSystemSpellsItAndNothingElseChanges(String given, String expected) {
        assertEquals(expected, shell.windowsSpelling(given));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"/a/b/c|c", "a\\b|b", "/a/b/|b", "c|c", "/|''", "a//|a", "C:\\tools\\git.exe|git.exe"})
    void theLastNameIsTheOneAfterTheLastSeparatorOfEitherKind(String path, String name) {
        assertEquals("''".equals(name) ? "" : name, shell.baseName(path));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"/a/b/c|/a/b", "a/b|a", "c|.", "/c|/", "a/b/|a", "a\\b|a", "C:\\x\\y|C:/x"})
    void theDirectoryIsWhatPrecedesTheLastName(String path, String directory) {
        assertEquals(directory, shell.dirName(path));
    }

    @Test
    void aNameInADirectoryIsNormalizedAndAPathNamesADirectoryOnlyWithATrailingSlash() {
        assertEquals("/d/ws/x", shell.childOf("/d/ws", "x"));
        assertEquals("/d/y", shell.childOf("/d/ws/", "../y"));
        assertTrue(shell.namesDirectory("a/"));
        assertFalse(shell.namesDirectory("a"));
        assertFalse(shell.namesDirectory("a\\"));
        assertTrue(shell.holdsNoFile("/dev"));
        assertTrue(shell.holdsNoFile("/dev/null"));
        assertFalse(shell.holdsNoFile("/devx"));
        assertFalse(shell.holdsNoFile("/tmp"));
    }

    @Test
    void theShellPutsAPathInItsOwnTreeOnlyWhereItIsOneOfTheShellsPaths() {
        assertEquals(Optional.of("/x"), shell.placeOf("/tmp/../x", null, false));
        assertEquals(Optional.of("/d/ws/x"), shell.placeOf("/d/ws/x", "/tmp", true), "a path from the root is placed whatever the file system says");
        assertEquals(Optional.empty(), shell.placeOf("//server/share", null, false), "a share is not in the shell's tree");
        assertEquals(Optional.of("/d/ws/rel"), shell.placeOf("rel", "/d/ws", false));
        assertEquals(Optional.of("/d/rel"), shell.placeOf("../rel", "/d/ws", false));
        assertEquals(Optional.empty(), shell.placeOf("rel", "/d/ws", true), "a path this file system takes as absolute is not the shell's to place");
        assertEquals(Optional.empty(), shell.placeOf("rel", null, false), "the shell's directory is not known in its tree");
        assertEquals(Optional.empty(), shell.placeOf("rel", "//server/share", false), "a share is not a directory of the shell's tree");
        assertEquals(Optional.empty(), shell.placeOf("D:\\x", null, true), "a Windows drive path is not in the shell's tree");
    }

    @Test
    void theImplementationIsTheOneTheServiceLoaderFindsWhereThereIsNoSpringContext() {
        assertEquals(MsysShellPaths.class, ServiceLoader.load(ShellPaths.class).findFirst().orElseThrow().getClass());
    }
}
