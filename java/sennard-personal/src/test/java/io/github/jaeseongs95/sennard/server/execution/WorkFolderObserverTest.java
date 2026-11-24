// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jaeseongs95.sennard.app.journal.BoundedScanner;

/**
 * What the look before and the look after a program ran in a working folder report, on real folders and the real {@link JournalScanner} (no mock): the whole folder, hidden folders
 * included, no list of exceptions, links not followed.
 *
 * <pre>
 * w1  nothing changed     an empty report (unchanged and complete), whatever is in the folder
 * w2  a file created      is reported, with its folder path relative and written with / on every system
 * w3  a file modified     by a different size, and by a different time with the same size; the content is not read
 * w4  a file deleted      is reported
 * w5  folders             an empty folder created or deleted is reported (a folder with files in it is reported with them)
 * w6  hidden              a hidden folder and a file in it are looked at like any other
 * w7  links               a link is not followed (what changes behind it is not reported); one that appears or goes is reported as created or deleted
 * w8  the lists           are sorted, and each change is in one list only
 * w9  the end             finish() twice is refused; close() ends the observation without a look; a path that is not a folder is refused and nothing is registered
 * w10 the folder          the look after sees what was changed through the path of another spelling of the same folder
 * w11 the look before     is referred to by the observation until it ends, by finish or by close, and by nothing the observation holds after it
 * </pre>
 */
class WorkFolderObserverTest {
    private final WorkFolderOverlaps overlaps = new WorkFolderOverlaps();
    private final ObservedEntries entries = new ObservedEntries(1_000_000);
    private final WorkFolderObserver observer = new WorkFolderObserver(new BoundedScanner(), overlaps, entries, null, 100_000, Duration.ofMinutes(1));

    /** Begins the observation of a new execution (a fresh id: the observations of one test are of different executions). */
    private WorkFolderObservation begin(Path folder) throws IOException {
        return observer.begin(ExecutionIds.next(), folder);
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    @Test
    void nothingChangedIsAnEmptyReportAndTheFolderIsFreeAgain(@TempDir Path work) throws Exception {
        write(work.resolve("a.txt"), "a");
        write(work.resolve("sub/b.txt"), "b");
        Files.createDirectories(work.resolve("empty"));

        WorkFolderObservation observation = begin(work);
        WorkFolderChanges changes = observation.finish();

        assertEquals(new WorkFolderChanges(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()), changes);
        assertTrue(changes.unchanged());
        assertTrue(changes.complete());
        assertTrue(changes.attributionCertain());
        assertEquals(0, overlaps.registered(), "nothing is registered after finish");
        assertEquals(entries.total(), entries.free(), "and the entries of both looks are given back");
    }

    @Test
    void aCreatedFileIsReportedWithItsPathRelativeToTheFolder(@TempDir Path work) throws Exception {
        write(work.resolve("old.txt"), "old");
        WorkFolderObservation observation = begin(work);

        write(work.resolve("new.txt"), "new");
        write(work.resolve("deep/er/still.txt"), "x");
        WorkFolderChanges changes = observation.finish();

        assertEquals(List.of("deep/er/still.txt", "new.txt"), changes.created());
        assertEquals(List.of("deep", "deep/er"), changes.createdDirectories());
        assertEquals(List.of(), changes.modified());
        assertEquals(List.of(), changes.deleted());
        assertFalse(changes.unchanged());
    }

    @Test
    void aModifiedFileIsReportedByAnotherSizeAndByAnotherTimeWithTheSameSize(@TempDir Path work) throws Exception {
        write(work.resolve("size.txt"), "short");
        write(work.resolve("time.txt"), "same");
        write(work.resolve("untouched.txt"), "same");
        Files.setLastModifiedTime(work.resolve("time.txt"), FileTime.from(Instant.parse("2026-01-01T00:00:00Z")));
        Files.setLastModifiedTime(work.resolve("untouched.txt"), FileTime.from(Instant.parse("2026-01-01T00:00:00Z")));
        WorkFolderObservation observation = begin(work);

        write(work.resolve("size.txt"), "a much longer text");
        write(work.resolve("time.txt"), "SAME");
        Files.setLastModifiedTime(work.resolve("time.txt"), FileTime.from(Instant.parse("2026-02-02T00:00:00Z")));
        WorkFolderChanges changes = observation.finish();

        assertEquals(List.of("size.txt", "time.txt"), changes.modified());
        assertEquals(List.of(), changes.created());
        assertEquals(List.of(), changes.deleted());
    }

    @Test
    void aDeletedFileIsReported(@TempDir Path work) throws Exception {
        write(work.resolve("keep.txt"), "k");
        write(work.resolve("gone.txt"), "g");
        write(work.resolve("sub/gone2.txt"), "g");
        WorkFolderObservation observation = begin(work);

        Files.delete(work.resolve("gone.txt"));
        Files.delete(work.resolve("sub/gone2.txt"));
        WorkFolderChanges changes = observation.finish();

        assertEquals(List.of("gone.txt", "sub/gone2.txt"), changes.deleted());
        assertEquals(List.of(), changes.created());
        assertEquals(List.of(), changes.modified());
    }

    @Test
    void anEmptyFolderCreatedOrDeletedIsReported(@TempDir Path work) throws Exception {
        Files.createDirectories(work.resolve("vanishes/inner"));
        WorkFolderObservation observation = begin(work);

        Files.delete(work.resolve("vanishes/inner"));
        Files.delete(work.resolve("vanishes"));
        Files.createDirectories(work.resolve("appears"));
        WorkFolderChanges changes = observation.finish();

        assertEquals(List.of("appears"), changes.createdDirectories());
        assertEquals(List.of("vanishes", "vanishes/inner"), changes.deletedDirectories());
        assertEquals(List.of(), changes.created(), "no file was created");
        assertEquals(List.of(), changes.deleted(), "no file was deleted");
        assertFalse(changes.unchanged());
    }

    @Test
    void aHiddenFolderAndAFileInItAreLookedAtLikeAnyOther(@TempDir Path work) throws Exception {
        write(work.resolve(".hidden/existing.txt"), "e");
        write(work.resolve(".git/config"), "c");
        WorkFolderObservation observation = begin(work);

        write(work.resolve(".hidden/added.txt"), "a");
        write(work.resolve(".git/config"), "changed config");
        write(work.resolve(".cache/new.txt"), "n");
        WorkFolderChanges changes = observation.finish();

        assertEquals(List.of(".cache/new.txt", ".hidden/added.txt"), changes.created());
        assertEquals(List.of(".git/config"), changes.modified());
        assertEquals(List.of(".cache"), changes.createdDirectories());
    }

    @Test
    void aLinkIsNotFollowedAndOneThatAppearsOrGoesIsReported(@TempDir Path work, @TempDir Path outside) throws Exception {
        write(outside.resolve("target/file.txt"), "t");
        Path link = work.resolve("link");
        try {
            Files.createSymbolicLink(link, outside.resolve("target"));
        } catch (IOException | UnsupportedOperationException | SecurityException notAllowed) {
            assumeTrue(false, "this account may not make a symbolic link here (Windows needs a privilege for it): " + notAllowed);
        }
        WorkFolderObservation observation = begin(work);

        write(outside.resolve("target/file.txt"), "changed behind the link");
        write(outside.resolve("target/other.txt"), "made behind the link");
        Files.delete(outside.resolve("target/other.txt"));
        WorkFolderChanges nothing = observation.finish();
        assertTrue(nothing.unchanged(), "what changes behind a link is not in the folder: " + nothing);
        assertTrue(nothing.complete());

        WorkFolderObservation second = begin(work);
        Files.delete(link);
        Files.createSymbolicLink(work.resolve("another-link"), outside.resolve("target"));
        WorkFolderChanges moved = second.finish();
        assertEquals(List.of("another-link"), moved.created());
        assertEquals(List.of("link"), moved.deleted());
    }

    @Test
    void theListsAreSortedAndAChangeIsInOneListOnly(@TempDir Path work) throws Exception {
        write(work.resolve("m.txt"), "m");
        write(work.resolve("k.txt"), "k");
        WorkFolderObservation observation = begin(work);

        write(work.resolve("z.txt"), "z");
        write(work.resolve("b.txt"), "b");
        write(work.resolve("a/b/c.txt"), "c");
        Files.delete(work.resolve("m.txt"));
        write(work.resolve("k.txt"), "k longer");
        WorkFolderChanges changes = observation.finish();

        assertEquals(List.of("a/b/c.txt", "b.txt", "z.txt"), changes.created());
        assertEquals(List.of("k.txt"), changes.modified());
        assertEquals(List.of("m.txt"), changes.deleted());
        assertEquals(List.of("a", "a/b"), changes.createdDirectories());
    }

    @Test
    void theObservationEndsOnceAndCloseEndsItWithoutALook(@TempDir Path work) throws Exception {
        WorkFolderObservation first = begin(work);
        first.finish();
        assertThrows(IllegalStateException.class, first::finish);
        first.close();
        assertEquals(0, overlaps.registered());

        WorkFolderObservation closed = begin(work);
        assertEquals(1, overlaps.registered());
        assertEquals(entries.total() - 1, entries.free(), "the look before holds the folder, which is one entry, until the observation ends");
        closed.close();
        closed.close();
        assertEquals(0, overlaps.registered(), "close ended the observation, twice is once");
        assertEquals(entries.total(), entries.free(), "the entries of the look before are given back, once");
        assertThrows(IllegalStateException.class, closed::finish, "and a closed observation does not look");
    }

    @Test
    void aPathThatIsNotAFolderIsRefusedAndNothingIsRegistered(@TempDir Path work) throws Exception {
        write(work.resolve("file.txt"), "f");

        assertThrows(NotDirectoryException.class, () -> begin(work.resolve("file.txt")));
        assertThrows(NotDirectoryException.class, () -> begin(work.resolve("missing")));
        assertEquals(0, overlaps.registered());
        assertThrows(NoSuchFileException.class, () -> overlaps.register(ExecutionIds.next(), work.resolve("missing")));
    }

    /** A scanner whose look fails with an Error from its n-th call on, after the walk has taken its entries (the failure is injected; the folder, the register and the entries are real). */
    private static final class DyingScanner extends BoundedScanner {
        private final int lookThatDies;
        private int looks;

        DyingScanner(int lookThatDies) {
            this.lookThatDies = lookThatDies;
        }

        @Override
        public Walked walk(Path root, Bounds bounds, LookSink sink) {
            Walked look = super.walk(root, bounds, sink);
            if (++looks >= lookThatDies) {
                throw new LookFailed();
            }
            return look;
        }
    }

    private static final class LookFailed extends Error {
        private static final long serialVersionUID = 1L;
    }

    @Test
    void anErrorInTheLookBeforeOrAfterStillEndsTheRegistration(@TempDir Path work) throws Exception {
        WorkFolderObserver diesBefore = new WorkFolderObserver(new DyingScanner(1), overlaps, entries, null, 100_000, Duration.ofMinutes(1));
        assertThrows(LookFailed.class, () -> diesBefore.begin(ExecutionIds.next(), work));
        assertEquals(0, overlaps.registered(), "an Error is not a RuntimeException, and the registration is ended all the same");
        assertEquals(entries.total(), entries.free(), "the look took its entries before it died: they are given back");

        WorkFolderObserver diesAfter = new WorkFolderObserver(new DyingScanner(2), overlaps, entries, null, 100_000, Duration.ofMinutes(1));
        WorkFolderObservation observation = diesAfter.begin(ExecutionIds.next(), work);
        assertEquals(1, overlaps.registered());
        assertThrows(LookFailed.class, observation::finish);
        assertEquals(0, overlaps.registered(), "the look after died, nothing is registered");
        assertEquals(entries.total(), entries.free(), "and the entries of both looks are given back");
        assertEquals(List.of(), begin(work).finish().created(), "and the folder can be observed again");
    }

    @Test
    void theLookAfterSeesTheFolderWhateverTheSpellingOfItsPath(@TempDir Path work) throws Exception {
        Files.createDirectories(work.resolve("sub"));
        WorkFolderObservation observation = begin(work.resolve("sub").resolve("..").resolve("sub"));

        write(work.resolve("sub/made.txt"), "m");
        WorkFolderChanges changes = observation.finish();

        assertEquals(work.resolve("sub").toAbsolutePath().normalize(), observation.folder());
        assertEquals(List.of("made.txt"), changes.created());
    }

    /** Whether anything that the observation holds is the look before (it holds it in a reference that is emptied when it ends). */
    private static boolean refersToALookBefore(WorkFolderObservation observation) throws IllegalAccessException {
        for (Field field : WorkFolderObservation.class.getDeclaredFields()) {
            field.setAccessible(true);
            Object value = field.get(observation);
            if (value instanceof AtomicReference<?> reference) {
                value = reference.get();
            }
            if (value instanceof LookBefore) {
                return true;
            }
        }
        return false;
    }

    @Test
    void anObservationThatHasEndedRefersToNoLookBeforeWhetherItEndedByFinishOrByClose(@TempDir Path work) throws Exception {
        write(work.resolve("a.txt"), "a");
        WorkFolderObservation finished = begin(work);
        WorkFolderObservation closed = begin(work);
        assertTrue(refersToALookBefore(finished), "control: while the observation is going on it holds the look before");
        assertTrue(refersToALookBefore(closed));

        finished.finish();
        closed.close();

        assertFalse(refersToALookBefore(finished), "the look before was handed to the look after and is not kept");
        assertFalse(refersToALookBefore(closed), "and a closed observation dropped it");
    }
}
