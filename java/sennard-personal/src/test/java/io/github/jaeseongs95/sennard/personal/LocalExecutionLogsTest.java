// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.annotation.Isolation;

import io.github.jaeseongs95.sennard.server.execution.ExecutionLogStore;

/**
 * The files of the log folders on a real state folder: how much a folder takes, deleting it, and what is never deleted whatever a record says. The folders are named as the ids of
 * executions are, since a folder that is not is not this server's.
 *
 * <pre>
 * l1  size       the bytes of the files of the folder; 0 for a folder that is gone
 * l2  delete     the files and the folder go; deleting again is not an error
 * l3  failure    something that cannot be deleted is thrown after the rest was deleted, and a later delete, once it can, finishes
 * l4  not mine   a location that is not directly under the log folder of this state folder (the state folder itself, another folder, a deeper one, a link) is refused and nothing is touched
 * l5  listing    the folders directly in the log folder, by name (the execution id) and location, without a stray file and without a link; nothing when there is no log folder
 * l6  owns       a folder directly in the log folder is this server's; any other place, and a text that is no path at all, is not (and nothing is thrown)
 * l7  measure    the bytes and the newest write of the folder or of a file in it, as the file system says; a folder that is gone is a failure, not a size of 0
 * l8  a link     a link of any kind in the place of a folder of the log folder (a symbolic link; on Windows a junction, which the JDK does not call a link) is not a folder of this server:
 *                it is not listed, not owned, not measured and not deleted, and what it points to is not touched; so is a link to another folder of the log folder
 * l9  the name   a folder whose name is not written as an execution id is not this server's: not listed, not owned, not measured and not deleted
 * l10 inside     a link in a folder of this server is deleted as the link, never through it, and takes nothing in the size; what it points to is not touched
 * l11 a file     a file that has the name of an execution id is not a folder of this server: not listed, not owned, not measured and not deleted (and what is in it is not touched)
 * l12 nowhere    a link that points nowhere (its target is gone) is not this server's either, and that is a definite answer: "not ours", not "cannot be told now"
 * l13 a loop     links that point at each other are links like any other: "not ours" for sure, not "cannot be told now"; not listed (the others are), not measured, not deleted;
 *                only a system that makes a symbolic link in a test can run it
 * l14 undecided  a folder that the file system does not answer for (the log folder cannot be searched) is "cannot be told now" with the reason, and is not taken for "not there" (which would
 *                make it ours) nor for "not ours"; nothing is deleted; only a system with permissions that hold the user back (not root) can run it
 * </pre>
 *
 * <p>l8, l10 and l12 are made with the kind of link the system lets a test make: a junction on Windows ({@code mklink /J}: the JDK has no call that makes one, and a symbolic link needs a
 * privilege there), a symbolic link elsewhere. The two tests of a case differ only in how the link is made; the other system's test is not run.
 */
class LocalExecutionLogsTest {
    private static final ExecutionLogStore.Ownership OURS = new ExecutionLogStore.Ownership.Ours();
    private static final ExecutionLogStore.Ownership NOT_OURS = new ExecutionLogStore.Ownership.NotOurs();
    private static final String ONE = "11111111-1111-4111-8111-111111111111";
    private static final String TWO = "22222222-2222-4222-8222-222222222222";
    private static final String MINE = "33333333-3333-4333-8333-333333333333";
    private static final String OTHER = "44444444-4444-4444-8444-444444444444";
    private static final String LINK = "55555555-5555-4555-8555-555555555555";

    private static LocalExecutionLogs logs(Path state) {
        return new LocalExecutionLogs(new PersonalProperties(state, Path.of("."), new PersonalProperties.Store(false, Isolation.READ_COMMITTED)));
    }

    private static Path folder(Path state, String id) throws IOException {
        Path folder = state.resolve("executions").resolve(id);
        Files.createDirectories(folder);
        return folder;
    }

    /** Makes {@code link} point at the folder {@code target}. */
    @FunctionalInterface
    private interface LinkFactory {
        void create(Path link, Path target) throws IOException;
    }

    /** A Windows junction: the JDK has no call to make one, so the shell's {@code mklink /J} prepares this fixture. */
    private static void createJunction(Path junction, Path target) throws IOException {
        Process process = new ProcessBuilder("cmd", "/c", "mklink", "/J", junction.toString(), target.toString()).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "junction fixture creation timed out");
            assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while a junction was created", interrupted);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    @Test
    void theSizeIsTheBytesOfTheFilesAndAFolderThatIsGoneTakesNothing(@TempDir Path state) throws Exception {
        Path folder = folder(state, ONE);
        Files.write(folder.resolve("stdout.log"), new byte[1000]);
        Files.write(folder.resolve("stderr.log"), new byte[24]);

        assertEquals(1024, logs(state).sizeOf(folder.toString()));
        assertEquals(0, logs(state).sizeOf(state.resolve("executions").resolve(TWO).toString()));
    }

    @Test
    void deleteRemovesTheFilesAndTheFolderAndTwiceIsOnce(@TempDir Path state) throws Exception {
        Path folder = folder(state, ONE);
        Files.writeString(folder.resolve("stdout.log"), "out");
        Files.writeString(folder.resolve("stderr.log"), "err");
        Path other = folder(state, OTHER);
        Files.writeString(other.resolve("stdout.log"), "kept");

        logs(state).delete(folder.toString());
        logs(state).delete(folder.toString());

        assertFalse(Files.exists(folder));
        assertTrue(Files.exists(other.resolve("stdout.log")), "another execution's files are not touched");
    }

    @Test
    void whatCannotBeDeletedIsThrownAfterTheRestWasDeletedAndALaterDeleteFinishes(@TempDir Path state) throws Exception {
        Path folder = folder(state, ONE);
        Files.writeString(folder.resolve("stdout.log"), "out");
        Path stuck = Files.createDirectories(folder.resolve("not-a-log").resolve("deeper"));

        IOException failed = assertThrows(IOException.class, () -> logs(state).delete(folder.toString()));

        assertTrue(failed instanceof DirectoryNotEmptyException, "the reason is thrown, not swallowed: " + failed);
        assertFalse(Files.exists(folder.resolve("stdout.log")), "the file that could be deleted was");
        assertTrue(Files.exists(stuck));

        Files.delete(stuck);
        Files.delete(stuck.getParent());
        logs(state).delete(folder.toString());
        assertFalse(Files.exists(folder), "the repeat finishes what the first could not");
    }

    @Test
    void aLocationThatIsNotAFolderOfAnExecutionOfThisServerIsRefusedAndNothingIsTouched(@TempDir Path state, @TempDir Path elsewhere) throws Exception {
        Path mine = folder(state, MINE);
        Files.writeString(mine.resolve("stdout.log"), "x");
        Path nested = Files.createDirectories(mine.resolve("nested"));
        Files.writeString(elsewhere.resolve("precious.txt"), "precious");
        LocalExecutionLogs logs = logs(state);

        for (Path notLogFolder : new Path[] {state, state.resolve("executions"), elsewhere, nested, state.resolve("executions").resolve("..").resolve("..")}) {
            assertThrows(IOException.class, () -> logs.delete(notLogFolder.toString()), notLogFolder.toString());
            assertThrows(IOException.class, () -> logs.sizeOf(notLogFolder.toString()), notLogFolder.toString());
        }
        assertTrue(Files.exists(elsewhere.resolve("precious.txt")));
        assertTrue(Files.exists(mine.resolve("stdout.log")));
        assertTrue(Files.exists(nested));
    }

    @Test
    void theFoldersAreTheOnesDirectlyInTheLogFolderWithoutAStrayFileAndWithoutALinkAndNoneWhenThereIsNoLogFolder(@TempDir Path state, @TempDir Path elsewhere) throws Exception {
        assertEquals(List.of(), logs(state).folders(), "no log folder yet");
        Path one = folder(state, ONE);
        Path two = folder(state, TWO);
        Files.writeString(state.resolve("executions").resolve("stray.txt"), "not a folder");
        Files.writeString(state.resolve("executions").resolve(OTHER), "a file named as an execution id");
        Path link = state.resolve("executions").resolve(LINK);
        try {
            Files.createSymbolicLink(link, elsewhere);
        } catch (UnsupportedOperationException | IOException noLinks) {
            link = null;
        }

        List<ExecutionLogStore.Folder> found = logs(state).folders();

        assertEquals(new TreeSet<>(List.of(ONE, TWO)), new TreeSet<>(found.stream().map(ExecutionLogStore.Folder::id).toList()), link == null ? "folders" : "folders, and not the link");
        assertEquals(new TreeSet<>(List.of(one.toString(), two.toString())), new TreeSet<>(found.stream().map(ExecutionLogStore.Folder::location).toList()));
        assertEquals(OURS, logs(state).ownershipOf(found.getFirst().location()), "what the listing gives is what the other methods take");
    }

    @Test
    void aFolderDirectlyInTheLogFolderIsOwnedAndAnyOtherPlaceOrATextThatIsNoPathIsNotAndNothingIsThrown(@TempDir Path state, @TempDir Path elsewhere) throws Exception {
        Path mine = folder(state, MINE);
        LocalExecutionLogs logs = logs(state);

        assertEquals(OURS, logs.ownershipOf(mine.toString()));
        for (String notMine : new String[] {state.toString(), state.resolve("executions").toString(), elsewhere.toString(), mine.resolve("nested").toString(), "a" + (char) 0 + "b", ""}) {
            assertEquals(NOT_OURS, logs.ownershipOf(notMine), "[" + notMine + "]");
        }
    }

    @Test
    void theMeasureIsTheBytesAndTheNewestWriteOfTheFolderOrOfAFileInItAndAFolderThatIsGoneIsAFailure(@TempDir Path state) throws Exception {
        Path folder = folder(state, ONE);
        Path out = folder.resolve("stdout.log");
        Path err = folder.resolve("stderr.log");
        Files.write(out, new byte[10]);
        Files.write(err, new byte[5]);
        Instant early = Instant.parse("2026-01-01T00:00:00Z");
        Instant middle = Instant.parse("2026-02-01T00:00:00Z");
        Instant late = Instant.parse("2026-03-01T00:00:00Z");
        Files.setLastModifiedTime(out, FileTime.from(early));
        Files.setLastModifiedTime(err, FileTime.from(late));
        Files.setLastModifiedTime(folder, FileTime.from(middle));

        assertEquals(new ExecutionLogStore.Measure(15, late), logs(state).measure(folder.toString()), "a file is the newest");

        Files.setLastModifiedTime(err, FileTime.from(early));
        assertEquals(new ExecutionLogStore.Measure(15, middle), logs(state).measure(folder.toString()), "the folder is the newest: a file was added or deleted last");
        assertThrows(IOException.class, () -> logs(state).measure(state.resolve("executions").resolve(TWO).toString()));
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------------------------- l8

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aJunctionInThePlaceOfAFolderOfTheLogFolderIsNotAFolderOfThisServerAndWhatItPointsToIsNotTouched(@TempDir Path state, @TempDir Path elsewhere) throws Exception {
        assertALinkInThePlaceOfAFolderIsNotOneOfThisServer(state, elsewhere, LocalExecutionLogsTest::createJunction);
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void aSymbolicLinkInThePlaceOfAFolderOfTheLogFolderIsNotAFolderOfThisServerAndWhatItPointsToIsNotTouched(@TempDir Path state, @TempDir Path elsewhere) throws Exception {
        assertALinkInThePlaceOfAFolderIsNotOneOfThisServer(state, elsewhere, Files::createSymbolicLink);
    }

    private void assertALinkInThePlaceOfAFolderIsNotOneOfThisServer(Path state, Path elsewhere, LinkFactory links) throws Exception {
        Path precious = Files.writeString(elsewhere.resolve("precious.txt"), "precious");
        Path mine = folder(state, MINE);
        Files.writeString(mine.resolve("stdout.log"), "mine");
        Path link = state.resolve("executions").resolve(LINK);
        Path toMine = state.resolve("executions").resolve(OTHER);
        links.create(link, elsewhere);
        links.create(toMine, mine);
        try {
            LocalExecutionLogs logs = logs(state);

            for (Path notAFolder : new Path[] {link, toMine}) {
                assertEquals(NOT_OURS, logs.ownershipOf(notAFolder.toString()), notAFolder.toString());
                assertThrows(IOException.class, () -> logs.sizeOf(notAFolder.toString()), notAFolder.toString());
                assertThrows(IOException.class, () -> logs.measure(notAFolder.toString()), notAFolder.toString());
                assertThrows(IOException.class, () -> logs.delete(notAFolder.toString()), notAFolder.toString());
            }
            assertTrue(Files.exists(precious), "what the link points to is not touched");
            assertEquals(List.of(MINE), logs.folders().stream().map(ExecutionLogStore.Folder::id).toList(), "neither link is listed");
            assertTrue(Files.exists(mine.resolve("stdout.log")), "the folder that the other link points to is not touched through it");
            assertTrue(Files.exists(link, LinkOption.NOFOLLOW_LINKS) && Files.exists(toMine, LinkOption.NOFOLLOW_LINKS), "and the links are left as they are");
            assertEquals(OURS, logs.ownershipOf(mine.toString()), "the folder itself is this server's");
        } finally {
            Files.deleteIfExists(link);
            Files.deleteIfExists(toMine);
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------------------------- l9

    @Test
    void aFolderThatIsNotNamedAsAnExecutionIdIsNotOfThisServerNotListedNotOwnedNotMeasuredAndNotDeleted(@TempDir Path state) throws Exception {
        Path precious = folder(state, "precious-folder");
        Files.writeString(precious.resolve("file.txt"), "precious");
        Path upper = folder(state, MINE.replace('3', 'A').toUpperCase());
        Files.writeString(upper.resolve("file.txt"), "precious");
        Path mine = folder(state, MINE);
        LocalExecutionLogs logs = logs(state);

        assertEquals(List.of(MINE), logs.folders().stream().map(ExecutionLogStore.Folder::id).toList());
        for (Path notMine : new Path[] {precious, upper}) {
            assertEquals(NOT_OURS, logs.ownershipOf(notMine.toString()), notMine.toString());
            assertThrows(IOException.class, () -> logs.sizeOf(notMine.toString()), notMine.toString());
            assertThrows(IOException.class, () -> logs.measure(notMine.toString()), notMine.toString());
            assertThrows(IOException.class, () -> logs.delete(notMine.toString()), notMine.toString());
            assertTrue(Files.exists(notMine.resolve("file.txt")), "nothing of it was deleted: " + notMine);
        }
        assertEquals(OURS, logs.ownershipOf(mine.toString()));
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------------------------ l10

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aJunctionInAFolderIsDeletedAsTheJunctionNeverThroughItAndTakesNothingInTheSize(@TempDir Path state, @TempDir Path elsewhere) throws Exception {
        assertALinkInAFolderIsDeletedAsTheLink(state, elsewhere, LocalExecutionLogsTest::createJunction);
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void aSymbolicLinkInAFolderIsDeletedAsTheLinkNeverThroughItAndTakesNothingInTheSize(@TempDir Path state, @TempDir Path elsewhere) throws Exception {
        assertALinkInAFolderIsDeletedAsTheLink(state, elsewhere, Files::createSymbolicLink);
    }

    private void assertALinkInAFolderIsDeletedAsTheLink(Path state, Path elsewhere, LinkFactory links) throws Exception {
        Path precious = Files.write(elsewhere.resolve("precious.txt"), new byte[4000]);
        Path folder = folder(state, MINE);
        Files.write(folder.resolve("stdout.log"), new byte[10]);
        Path link = folder.resolve("inside-link");
        links.create(link, elsewhere);
        try {
            LocalExecutionLogs logs = logs(state);

            assertEquals(10, logs.sizeOf(folder.toString()), "the link takes nothing, and what it points to is not counted");
            assertEquals(10, logs.measure(folder.toString()).bytes());

            logs.delete(folder.toString());

            assertFalse(Files.exists(folder, LinkOption.NOFOLLOW_LINKS), "the folder and the link in it are gone");
            assertTrue(Files.exists(precious), "what the link pointed to is not touched");
            assertEquals(4000, Files.size(precious));
        } finally {
            Files.deleteIfExists(link);
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------------------------ l11

    @Test
    void aFileThatHasTheNameOfAnExecutionIdIsNotAFolderOfThisServerNotListedNotOwnedNotMeasuredAndNotDeleted(@TempDir Path state) throws Exception {
        Path mine = folder(state, MINE);
        Path file = Files.writeString(state.resolve("executions").resolve(OTHER), "a file, not a folder");
        LocalExecutionLogs logs = logs(state);

        assertEquals(NOT_OURS, logs.ownershipOf(file.toString()));
        assertThrows(IOException.class, () -> logs.sizeOf(file.toString()));
        assertThrows(IOException.class, () -> logs.measure(file.toString()));
        assertThrows(IOException.class, () -> logs.delete(file.toString()));
        assertEquals("a file, not a folder", Files.readString(file), "the file is not touched");
        assertEquals(List.of(MINE), logs.folders().stream().map(ExecutionLogStore.Folder::id).toList());
        assertEquals(OURS, logs.ownershipOf(mine.toString()));
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------------------------ l12

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aJunctionThatPointsNowhereIsNotOursForSureAndNotJustNotKnownNow(@TempDir Path state, @TempDir Path elsewhere) throws Exception {
        assertALinkThatPointsNowhereIsNotOursForSure(state, elsewhere, LocalExecutionLogsTest::createJunction);
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void aSymbolicLinkThatPointsNowhereIsNotOursForSureAndNotJustNotKnownNow(@TempDir Path state, @TempDir Path elsewhere) throws Exception {
        assertALinkThatPointsNowhereIsNotOursForSure(state, elsewhere, Files::createSymbolicLink);
    }

    private void assertALinkThatPointsNowhereIsNotOursForSure(Path state, Path elsewhere, LinkFactory links) throws Exception {
        folder(state, MINE);
        Path target = Files.createDirectories(elsewhere.resolve("gone"));
        Path link = state.resolve("executions").resolve(LINK);
        links.create(link, target);
        Files.delete(target);
        try {
            LocalExecutionLogs logs = logs(state);

            assertEquals(NOT_OURS, logs.ownershipOf(link.toString()), "a link that points nowhere is somewhere else, and that does not change");
            assertThrows(IOException.class, () -> logs.delete(link.toString()));
            assertEquals(List.of(MINE), logs.folders().stream().map(ExecutionLogStore.Folder::id).toList(), "it is not listed");
            assertTrue(Files.exists(link, LinkOption.NOFOLLOW_LINKS), "and it is left as it is");
        } finally {
            Files.deleteIfExists(link);
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------------------------ l13

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void linksThatLoopBackToEachOtherAreNotOursForSureNotUndecidedAndDoNotHideTheOthers(@TempDir Path state) throws Exception {
        folder(state, MINE);
        Path loop = state.resolve("executions").resolve(LINK);
        Path back = state.resolve("executions").resolve("loop-back");
        Files.createSymbolicLink(loop, back);
        Files.createSymbolicLink(back, loop);
        try {
            LocalExecutionLogs logs = logs(state);

            assertEquals(NOT_OURS, logs.ownershipOf(loop.toString()), "a link is not a folder of this server, and that is known without following it: not 'cannot be told now'");
            assertThrows(IOException.class, () -> logs.sizeOf(loop.toString()));
            assertThrows(IOException.class, () -> logs.measure(loop.toString()));
            assertThrows(IOException.class, () -> logs.delete(loop.toString()));
            assertEquals(List.of(MINE), logs.folders().stream().map(ExecutionLogStore.Folder::id).toList(), "it is not listed, and the folder that can be judged is");
            assertTrue(Files.isSymbolicLink(loop) && Files.isSymbolicLink(back), "and the links are left as they are");
        } finally {
            Files.deleteIfExists(loop);
            Files.deleteIfExists(back);
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------------------------ l14

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void aFolderWhoseEntryTheFileSystemDoesNotAnswerForIsNotKnownNowAndNotTakenForNothingThereOrForNotOurs(@TempDir Path state) throws Exception {
        Path mine = folder(state, MINE);
        Path executions = state.resolve("executions");
        Files.write(mine.resolve("stdout.log"), new byte[10]);
        // a log folder that cannot be searched: the entries in it can be named, not looked at, and the answer to a look is an error, not "no such file"
        Files.setPosixFilePermissions(executions, java.util.Set.of());
        try {
            assumeFalse(Files.isReadable(executions), "a user that the permissions do not hold back (root) cannot make the file system fail to answer");
            LocalExecutionLogs logs = logs(state);

            ExecutionLogStore.Ownership answer = logs.ownershipOf(mine.toString());

            ExecutionLogStore.Ownership.Undecided undecided = assertInstanceOf(ExecutionLogStore.Ownership.Undecided.class, answer, "not 'not ours' and not 'there is nothing': a later look may know");
            assertInstanceOf(java.nio.file.AccessDeniedException.class, undecided.reason(), "and the reason is the file system's");
            assertThrows(IOException.class, () -> logs.delete(mine.toString()));
        } finally {
            Files.setPosixFilePermissions(executions, java.util.EnumSet.allOf(java.nio.file.attribute.PosixFilePermission.class));
        }
        assertTrue(Files.exists(mine.resolve("stdout.log")), "nothing was deleted");
    }
}
