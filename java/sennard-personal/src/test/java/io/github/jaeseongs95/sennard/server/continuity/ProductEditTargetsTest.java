// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.continuity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jaeseongs95.sennard.server.hook.HostWorkFacts;
import io.github.jaeseongs95.sennard.server.hook.host.MsysShellPaths;

/**
 * Which edits the gate counts, on a real file system. Whether two spellings name one directory is the file system's rule; each expectation that
 * depends on it is {@link Files#isSameFile}, not a guess about the operating system.
 */
class ProductEditTargetsTest {
    @TempDir
    Path temp;

    /** A {@code *} element is no valid name on every file system, so such a root is text: the base and the elements joined with the file system's separator. */
    private static String wildcardRoot(Path base, String... elements) {
        String separator = base.getFileSystem().getSeparator();
        return base + separator + String.join(separator, elements);
    }

    private static boolean exempt(String root, Path target) {
        List<Predicate<Path>> roots = new ArrayList<>();
        ProductEditTargets.exemption(root, roots);
        return roots.stream().anyMatch(candidate -> candidate.test(target));
    }

    /** E1: absolute paths are normalized, a relative path resolves against the working directory, and a relative path of a call that names none is dropped. */
    @Test
    void pathsResolveAgainstTheWorkingDirectory() {
        Path work = temp.resolve("work");
        List<Path> resolved = ProductEditTargets.resolved(work.toString(), List.of(temp.resolve("a/../b/f.txt").toString(), "src/../Main.java", " ", "dir/./x.txt"));
        assertEquals(List.of(temp.resolve("b/f.txt"), work.resolve("Main.java"), work.resolve("dir/x.txt")), resolved);
        assertEquals(List.of(), ProductEditTargets.resolved(null, List.of("src/Main.java")));
        assertEquals(List.of(), ProductEditTargets.resolved(work.toString(), List.of("bad\u0000name")));
        assertEquals(List.of(temp.resolve("f.txt")), ProductEditTargets.resolved(null, List.of(temp.resolve("f.txt").toString(), temp.resolve("./f.txt").toString())));
    }

    /** E2: a directory exempts what lies under it on whole path elements, never a folder that only shares its first characters. */
    @Test
    void aDirectoryExemptsWholeElements() throws IOException {
        Path notes = Files.createDirectories(temp.resolve("notes"));
        assertTrue(exempt(notes.toString(), notes.resolve("a/b.md")));
        assertTrue(exempt(notes.toString(), notes));
        assertFalse(exempt(notes.toString(), temp.resolve("notes2/a.md")));
        assertFalse(exempt(notes.toString(), temp.resolve("other/a.md")));
    }

    /** E3: the directory written with {@code ..} and another case exempts exactly when the file system says it is the same directory. */
    @Test
    void anotherSpellingOfTheDirectoryExemptsExactlyWhenItIsTheSameDirectory() throws IOException {
        Path notes = Files.createDirectories(temp.resolve("notes")).toRealPath();
        Path target = notes.resolve("a.md");
        Path climbing = temp.resolve("x/../notes");
        Path upper = Path.of(notes.toString().toUpperCase());
        assertTrue(exempt(climbing.toString(), target));
        boolean same = Files.exists(upper) && Files.isSameFile(upper, notes);
        assertEquals(same, exempt(upper.toString(), target));
    }

    /** E4: a {@code *} element stands for exactly one directory; characters of the glob language elsewhere in the path stay literal. */
    @Test
    void aWildcardElementStandsForOneDirectory() throws IOException {
        Path odd = Files.createDirectories(temp.resolve("a[1]{b}"));
        String root = wildcardRoot(odd, "*", "memory");
        assertTrue(exempt(root, odd.resolve("p1/memory/x.md")));
        assertTrue(exempt(root, odd.resolve("p1/memory")));
        assertFalse(exempt(root, odd.resolve("p1/other/x.md")));
        assertFalse(exempt(root, odd.resolve("p1/q/memory/x.md")));
        assertFalse(exempt(root, temp.resolve("a1b/p1/memory/x.md")));
    }

    /** E5: an entry that is no path of this file system exempts nothing and does not fail. */
    @Test
    void anEntryThatIsNoPathExemptsNothing() {
        assertFalse(exempt("bad\u0000name", temp.resolve("a.md")));
    }

    /** The signed pattern as glob syntax spells this file system's separators: a backslash is escaped (JDK {@code FileSystem.getPathMatcher}); {@code /} is a separator on both. */
    private static String pattern(Path base, String tail) {
        return base.toString().replace("\\", "\\\\") + "/" + tail;
    }

    /** G1: a signed glob pattern exempts what it matches and everything below it; it is matched as text of the pattern, never turned into a path. */
    @Test
    void aSignedPatternExemptsWhatItMatchesAndWhatIsBelow() {
        List<Predicate<Path>> roots = new ArrayList<>();
        ProductEditTargets.globExemption(pattern(temp, "*/memory"), roots);
        assertTrue(roots.stream().anyMatch(root -> root.test(temp.resolve("p1/memory"))));
        assertTrue(roots.stream().anyMatch(root -> root.test(temp.resolve("p1/memory/x.md"))));
        assertFalse(roots.stream().anyMatch(root -> root.test(temp.resolve("p1/other/x.md"))));
        assertFalse(roots.stream().anyMatch(root -> root.test(temp.resolve("p1/q/memory/x.md"))));
        List<Predicate<Path>> alternatives = new ArrayList<>();
        ProductEditTargets.globExemption(pattern(temp, "{a,b}/notes"), alternatives);
        assertTrue(alternatives.stream().anyMatch(root -> root.test(temp.resolve("b/notes/x.md"))));
        assertFalse(alternatives.stream().anyMatch(root -> root.test(temp.resolve("c/notes/x.md"))));
    }

    /** G2: a pattern the file system refuses exempts nothing and does not fail. */
    @Test
    void aMalformedPatternExemptsNothing() {
        List<Predicate<Path>> roots = new ArrayList<>();
        ProductEditTargets.globExemption(pattern(temp, "[abc/notes"), roots);
        assertEquals(0, roots.size());
    }

    /** E7: the signed entries are sorted by what the file system says: a path with a root and a drive is a file; a relative entry names no place and is left out. */
    @Test
    void signedEntriesAreSortedByTheFileSystem() {
        String absolute = temp.resolve("notes").toString();
        String pattern = wildcardRoot(temp, "*", "memory");
        ProductEditTargets.Declared declared = ProductEditTargets.classify(List.of(absolute, "relative/dir", pattern), new MsysShellPaths());
        assertEquals(List.of(absolute), declared.files());
        assertEquals(List.of(pattern), declared.globs());
        assertEquals(List.of(), declared.shellRooted());
        boolean rootedIsAbsolute = Path.of("/tmp").isAbsolute();
        ProductEditTargets.Declared slash = ProductEditTargets.classify(List.of("/tmp"), new MsysShellPaths());
        assertEquals(rootedIsAbsolute ? List.of("/tmp") : List.of(), slash.files());
        assertEquals(rootedIsAbsolute ? List.of() : List.of("/tmp"), slash.shellRooted());
    }

    private static HostWorkFacts.EditTargets unreadIn(boolean unplaced, String... folders) {
        return new HostWorkFacts.EditTargets(List.of(), true, List.of(), List.of(folders), unplaced);
    }

    /** F1: the folders of an unread write are the shell's: absolute as they are, relative ones from the hook's working directory, {@code ""} being that directory. */
    @Test
    void anUnreadWriteIsAtTheFoldersTheShellFollowedTo() {
        Path work = temp.resolve("work");
        Path other = temp.resolve("other");
        assertEquals(List.of(work.toString()), ProductEditTargets.unreadPlaces(work.toString(), unreadIn(false, ""), false));
        assertEquals(List.of(other.toString()), ProductEditTargets.unreadPlaces(work.toString(), unreadIn(false, other.toString()), false));
        assertEquals(List.of(work.resolve("java").toString()), ProductEditTargets.unreadPlaces(work.toString(), unreadIn(false, "java"), false));
        assertEquals(List.of(other.toString()), ProductEditTargets.unreadPlaces(work.toString(), unreadIn(false, temp.resolve("work/../other").toString()), false));
        assertEquals(List.of(other.toString(), work.toString()), ProductEditTargets.unreadPlaces(work.toString(), unreadIn(false, other.toString(), ""), false));
        assertEquals(List.of(other.toString(), work.toString()), ProductEditTargets.unreadPlaces(work.toString(), unreadIn(false, other.toString()), true));
    }

    /** F2: a place that is not known is none, and none is covered by no workflow; a known folder next to it does not make it known. */
    @Test
    void anUnreadWriteWhereTheShellIsNotKnownHasNoPlace() {
        Path work = temp.resolve("work");
        assertEquals(List.of(), ProductEditTargets.unreadPlaces(work.toString(), unreadIn(true), false));
        assertEquals(List.of(), ProductEditTargets.unreadPlaces(work.toString(), unreadIn(true, work.toString()), true));
        assertEquals(List.of(), ProductEditTargets.unreadPlaces(null, unreadIn(false, ""), false));
        assertEquals(List.of(), ProductEditTargets.unreadPlaces(null, unreadIn(false, "java"), false));
        assertEquals(List.of(), ProductEditTargets.unreadPlaces(work.toString(), unreadIn(false, "bad\u0000name"), false));
        assertEquals(List.of(temp.toString()), ProductEditTargets.unreadPlaces(null, unreadIn(false, temp.toString()), false));
    }

    /** F3: a call that no shell placed (the edit tool that names no file) is at the working directory, as before. */
    @Test
    void anUnreadWriteNoShellPlacedIsAtTheWorkingDirectory() {
        Path work = temp.resolve("work");
        HostWorkFacts.EditTargets tool = new HostWorkFacts.EditTargets(List.of(), false);
        assertEquals(List.of(work.toString()), ProductEditTargets.unreadPlaces(work.toString(), tool, true));
        assertEquals(List.of(), ProductEditTargets.unreadPlaces(null, tool, true));
    }
}
