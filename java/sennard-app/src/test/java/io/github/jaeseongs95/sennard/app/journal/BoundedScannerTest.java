// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jaeseongs95.sennard.app.journal.BoundedScanner.Bounds;
import io.github.jaeseongs95.sennard.app.journal.BoundedScanner.FileStamp;
import io.github.jaeseongs95.sennard.app.journal.BoundedScanner.LookSink;
import io.github.jaeseongs95.sennard.app.journal.BoundedScanner.StopReason;
import io.github.jaeseongs95.sennard.app.journal.BoundedScanner.Walked;
import io.github.jaeseongs95.sennard.app.journal.JournalScanner.FailureReason;
import io.github.jaeseongs95.sennard.app.journal.JournalScanner.Scan;

/**
 * The bounded walk of {@link BoundedScanner}, on real folders: it stops at the number of entries or the time it was given, or where the sink that it hands the entries to takes no more,
 * and says which and how many entries it saw; it does not enter a folder it was told to leave; every path it hands over is relative to the folder; and the walks of the change journal
 * (which are not bounded, and are another class) are the same as before.
 *
 * <pre>
 * b1  whole       a folder within the bounds is seen whole: every file and folder is handed over, no stop, no content read
 * b2  entries     at the bound of entries the walk stops, names ENTRY_LIMIT and the entries it saw (the folder and the files and folders in it count)
 * b3  time        a bound of no time stops it at the first entry with TIME_LIMIT and no entry seen
 * b4  excluded    a folder in the excluded set is not entered: it and what is in it are not handed over, it is named as not walked, and it counts no entry
 * b5  inside      the folders the walk was inside when it stopped were handed over when it entered them: they exist
 * b6  not bounded the walk of the journal (scan) sees the whole folder whatever its size: the bounds are of the bounded walk only
 * b7  the bounds  are not negative
 * b8  the sink    a walk asks its sink once for every entry it looks at, and for none that it does not look at; where the sink says no it stops with TOTAL_ENTRY_LIMIT, after the entries
 *                 it took, and the entry it refused is not counted
 * b9  paths       every path handed over is relative to the folder, written with / (the folder itself is .), and the folder's own path is nowhere in it
 * b10 the root    a folder that cannot be resolved is one failure on the folder and nothing else
 * b11 the folder above   of a path as a walk writes it is the path without its last name; the folder itself has none, and a name at the top is under the folder itself
 * </pre>
 */
class BoundedScannerTest {
    private final BoundedScanner scanner = new BoundedScanner();
    private final JournalScanner journal = new JournalScanner(new FileDigest());
    private static final Duration LONG = Duration.ofMinutes(5);

    /** What the walk handed over, kept as it came; it takes as many entries as it is given leave to. */
    private static final class Taken implements LookSink {
        private long leave;
        private long asked;
        private final Map<String, FileStamp> files = new HashMap<>();
        private final Map<String, FailureReason> failures = new HashMap<>();
        private final Set<String> directories = new HashSet<>();
        private final Set<String> notWalked = new HashSet<>();
        private final List<String> order = new ArrayList<>();

        Taken() {
            this(Long.MAX_VALUE);
        }

        Taken(long leave) {
            this.leave = leave;
        }

        private boolean take() {
            asked++;
            if (leave == 0) {
                return false;
            }
            leave--;
            return true;
        }

        @Override
        public boolean file(String path, FileStamp stamp) {
            if (!take()) {
                return false;
            }
            files.put(path, stamp);
            order.add(path);
            return true;
        }

        @Override
        public boolean failure(String path, FailureReason reason) {
            if (!take()) {
                return false;
            }
            failures.put(path, reason);
            return true;
        }

        @Override
        public boolean directoryEntered(String path) {
            if (!take()) {
                return false;
            }
            directories.add(path);
            order.add(path);
            return true;
        }

        @Override
        public boolean directoryFailed(String path, FailureReason reason) {
            directories.remove(path);
            failures.put(path, reason);
            return true;
        }

        @Override
        public void notWalked(String path) {
            notWalked.add(path);
        }
    }

    private static void files(Path folder, String prefix, int count) throws IOException {
        for (int number = 0; number < count; number++) {
            Files.writeString(folder.resolve(prefix + number + ".txt"), "x");
        }
    }

    @Test
    void aFolderWithinTheBoundsIsSeenWholeAndNoContentIsRead(@TempDir Path work) throws Exception {
        files(work, "a", 3);
        Files.createDirectories(work.resolve("sub/deeper"));
        files(work.resolve("sub"), "b", 2);
        Taken taken = new Taken();

        Walked look = scanner.walk(work, new Bounds(100, LONG, Set.of()), taken);

        assertTrue(look.complete());
        assertNull(look.stoppedBy());
        assertEquals(8L, look.entries(), "the folder, sub, deeper and five files");
        assertEquals(Set.of("a0.txt", "a1.txt", "a2.txt", "sub/b0.txt", "sub/b1.txt"), taken.files.keySet());
        assertEquals(Set.of(".", "sub", "sub/deeper"), taken.directories);
        assertEquals(Files.size(work.resolve("a0.txt")), taken.files.get("a0.txt").size());
        assertEquals(Files.getLastModifiedTime(work.resolve("a0.txt")).toInstant().toEpochMilli(), taken.files.get("a0.txt").modifiedMillis(), "the size and the time are what is handed over");
        assertEquals(Set.of(), taken.notWalked);
        assertEquals(Map.of(), taken.failures);
    }

    @Test
    void atTheBoundOfEntriesTheWalkStopsAndSaysSoAndHowManyEntriesItSaw(@TempDir Path work) throws Exception {
        files(work, "f", 30);
        Taken taken = new Taken();

        Walked look = scanner.walk(work, new Bounds(10, LONG, Set.of()), taken);

        assertFalse(look.complete());
        assertEquals(StopReason.ENTRY_LIMIT, look.stoppedBy());
        assertEquals(10L, look.entries(), "the folder itself and nine files");
        assertEquals(9, taken.files.size());

        Walked exact = scanner.walk(work, new Bounds(31, LONG, Set.of()), new Taken());
        assertTrue(exact.complete(), "the folder and thirty files are thirty-one entries: a bound of thirty-one is not met");
        assertEquals(31L, exact.entries());
    }

    @Test
    void aBoundOfNoTimeStopsAtTheFirstEntry(@TempDir Path work) throws Exception {
        files(work, "f", 5);
        Taken taken = new Taken();

        Walked look = scanner.walk(work, new Bounds(100, Duration.ZERO, Set.of()), taken);

        assertEquals(StopReason.TIME_LIMIT, look.stoppedBy());
        assertEquals(0L, look.entries());
        assertEquals(0L, taken.asked, "the sink was not asked about an entry that was not looked at");
        assertEquals(Map.of(), taken.files);
    }

    @Test
    void anExcludedFolderIsNotEnteredAndCountsNoEntry(@TempDir Path work) throws Exception {
        Files.createDirectories(work.resolve("state/executions/e1"));
        files(work.resolve("state/executions/e1"), "log", 3);
        files(work, "f", 2);
        Path excluded = work.resolve("state").toRealPath();
        Taken taken = new Taken();

        Walked look = scanner.walk(work, new Bounds(100, LONG, Set.of(excluded)), taken);

        assertTrue(look.complete());
        assertEquals(Set.of("f0.txt", "f1.txt"), taken.files.keySet());
        assertEquals(Set.of("."), taken.directories, "state and what is in it are not handed over");
        assertEquals(Set.of("state"), taken.notWalked);
        assertEquals(3L, look.entries(), "the folder and its two files");
    }

    @Test
    void theFoldersTheWalkWasInsideWhenItStoppedWereHandedOverWhenItEnteredThem(@TempDir Path work) throws Exception {
        Path inner = Files.createDirectories(work.resolve("a/b/c"));
        files(inner, "f", 20);
        Taken taken = new Taken();

        Walked look = scanner.walk(work, new Bounds(8, LONG, Set.of()), taken);

        assertEquals(StopReason.ENTRY_LIMIT, look.stoppedBy());
        assertEquals(Set.of(".", "a", "a/b", "a/b/c"), taken.directories, "the walk was inside all four: they exist, though it did not see them whole");
        assertEquals(4, taken.files.size());
    }

    @Test
    void theWalkOfTheJournalIsNotBoundedAndSeesTheWholeFolder(@TempDir Path work) throws Exception {
        files(work, "f", 200);

        Scan whole = journal.scan(List.of(work), Map.of(), Set.of(), true);

        assertEquals(200, whole.files().size());
        assertEquals(Set.of(work), whole.scannedDirectories());
        assertEquals(List.of(), whole.failures());
    }

    @Test
    void theBoundsAreNotNegative() {
        assertThrows(IllegalArgumentException.class, () -> new Bounds(-1, LONG, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new Bounds(1, Duration.ofMillis(-1), Set.of()));
    }

    @Test
    void aWalkAsksItsSinkOnceForEveryEntryItLooksAtAndStopsWhereTheSinkSaysNo(@TempDir Path work) throws Exception {
        files(work, "f", 30);
        Taken taken = new Taken(12);

        Walked look = scanner.walk(work, new Bounds(100, LONG, Set.of()), taken);

        assertEquals(StopReason.TOTAL_ENTRY_LIMIT, look.stoppedBy());
        assertEquals(12L, look.entries(), "the folder and eleven files: the sink took twelve");
        assertEquals(13L, taken.asked, "it was asked for twelve and said no to the thirteenth, which is not counted as looked at");
        assertEquals(11, taken.files.size());
        assertEquals(Set.of("."), taken.directories);

        // the bound of the walk itself comes first, and the sink is not asked about an entry that the bound refuses
        Taken own = new Taken();
        Walked bounded = scanner.walk(work, new Bounds(5, LONG, Set.of()), own);
        assertEquals(StopReason.ENTRY_LIMIT, bounded.stoppedBy());
        assertEquals(5L, own.asked, "the sink was asked about five entries, and not about the one the bound refused");
    }

    @Test
    void everyPathOfAWalkIsRelativeToTheFolderAndWrittenWithASlash(@TempDir Path work) throws Exception {
        Files.createDirectories(work.resolve("one/two"));
        Files.writeString(work.resolve("one/two/three.txt"), "t");
        Files.writeString(work.resolve("top.txt"), "t");
        Taken taken = new Taken();

        scanner.walk(work.resolve("one").resolve("..").resolve("one").resolve("..").resolve("."), new Bounds(100, LONG, Set.of()), taken);

        assertEquals(Set.of("top.txt", "one/two/three.txt"), taken.files.keySet(), "whatever the spelling of the folder's own path");
        assertEquals(Set.of(".", "one", "one/two"), taken.directories);
        String root = work.toRealPath().toString();
        assertTrue(taken.order.stream().noneMatch(path -> path.contains(root)), "the path of the folder is not in what is handed over");
    }

    @Test
    void aFolderThatCannotBeResolvedIsOneFailureOnTheFolderAndNothingElse(@TempDir Path work) throws Exception {
        Taken taken = new Taken();

        Walked look = scanner.walk(work.resolve("missing"), new Bounds(100, LONG, Set.of()), taken);

        assertEquals(Map.of(".", FailureReason.NOT_FOUND), taken.failures);
        assertEquals(0L, look.entries());
        assertEquals(Map.of(), taken.files);
        assertEquals(Set.of(), taken.directories);
    }

    @Test
    void theFolderAboveAPathIsTheOneWithoutItsLastName() {
        assertEquals("D2", BoundedScanner.parent("D2/x"));
        assertEquals("D2/sub", BoundedScanner.parent("D2/sub/y"));
        assertEquals(BoundedScanner.ROOT, BoundedScanner.parent("D"), "a name at the top is under the folder itself");
        assertNull(BoundedScanner.parent(BoundedScanner.ROOT), "the folder itself has none");
    }
}
