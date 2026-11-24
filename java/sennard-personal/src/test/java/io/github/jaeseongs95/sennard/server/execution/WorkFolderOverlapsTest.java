// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The register of the observations that are going on, with real folders and real threads. It holds nobody back: a second observation of a folder that is observed already is
 * registered at once, and the two name each other. No time is asserted (a deadline only makes a wait that should not be there end the test).
 *
 * <pre>
 * o1  no waiting     a second observation of the same folder is registered while the first is going on, and each names the other when it ends
 * o2  the relation   the same folder named another way, a folder above and a folder below are related; a sibling, and a folder whose name only starts like the other's, are not
 * o3  both ways      one that was going on when the other began and one that began while the other was going on are both named; one that had ended before is not
 * o4  none           an observation that overlapped none names none
 * o5  left behind    nothing is registered after release or close (twice is once), after a folder that does not exist, and after an id that is registered already
 * o6  at once        many observations of one folder registered from many threads, all going on together: each names every other and no one else
 * </pre>
 */
class WorkFolderOverlapsTest {
    private static final Duration DEADLINE = Duration.ofSeconds(60);
    private final WorkFolderOverlaps overlaps = new WorkFolderOverlaps();

    private static String id(int number) {
        return "e" + number;
    }

    @Test
    void aSecondObservationOfTheSameFolderIsRegisteredAtOnceAndTheTwoNameEachOther(@TempDir Path work) throws Exception {
        WorkFolderOverlaps.Registration first = overlaps.register(id(1), work);
        WorkFolderOverlaps.Registration second = assertTimeoutPreemptively(DEADLINE, () -> overlaps.register(id(2), work));

        assertEquals(work.toRealPath(), first.folder());
        assertEquals(2, overlaps.registered());
        assertEquals(Set.of(id(2)), first.release());
        assertEquals(Set.of(id(1)), second.release());
        assertEquals(0, overlaps.registered());
    }

    @Test
    void foldersAreRelatedWhenTheyAreTheSameOneAboveOrOneBelowAndNotWhenTheyAreSiblings(@TempDir Path work) throws Exception {
        Files.createDirectories(work.resolve("a/deep"));
        Files.createDirectories(work.resolve("ab"));
        Files.createDirectories(work.resolve("b"));
        WorkFolderOverlaps.Registration a = overlaps.register(id(1), work.resolve("a"));
        WorkFolderOverlaps.Registration sameAnotherWay = overlaps.register(id(2), work.resolve("b").resolve("..").resolve("a").resolve("."));
        WorkFolderOverlaps.Registration below = overlaps.register(id(3), work.resolve("a/deep"));
        WorkFolderOverlaps.Registration above = overlaps.register(id(4), work);
        WorkFolderOverlaps.Registration sibling = overlaps.register(id(5), work.resolve("b"));
        WorkFolderOverlaps.Registration likeA = overlaps.register(id(6), work.resolve("ab"));

        assertEquals(Set.of(id(2), id(3), id(4)), a.release());
        assertEquals(Set.of(id(1), id(3), id(4)), sameAnotherWay.release());
        assertEquals(Set.of(id(1), id(2), id(4)), below.release());
        assertEquals(Set.of(id(1), id(2), id(3), id(5), id(6)), above.release(), "the folder above all of them");
        assertEquals(Set.of(id(4)), sibling.release(), "a sibling is related to nothing but the folder above");
        assertEquals(Set.of(id(4)), likeA.release(), "a name that only starts like another's is another folder");
        assertEquals(0, overlaps.registered());
    }

    @Test
    void anObservationThatBeganWhileAnotherWasGoingOnIsNamedAndOneThatHadEndedIsNot(@TempDir Path work) throws Exception {
        WorkFolderOverlaps.Registration ended = overlaps.register(id(1), work);
        WorkFolderOverlaps.Registration running = overlaps.register(id(2), work);
        assertEquals(Set.of(id(2)), ended.release());

        WorkFolderOverlaps.Registration later = overlaps.register(id(3), work);

        assertEquals(Set.of(id(1), id(3)), running.release(), "it names the one that began while it was going on, and the one it began beside (which ended since)");
        assertEquals(Set.of(id(2)), later.release(), "it was going on when this began");
    }

    @Test
    void anObservationThatOverlappedNoneNamesNone(@TempDir Path work, @TempDir Path other) throws Exception {
        WorkFolderOverlaps.Registration alone = overlaps.register(id(1), work);
        WorkFolderOverlaps.Registration elsewhere = overlaps.register(id(2), other);

        assertEquals(Set.of(), alone.release());
        assertEquals(Set.of(), elsewhere.release());
        assertEquals(Set.of(), overlaps.register(id(3), work).release());
    }

    @Test
    void nothingIsLeftRegisteredAfterAReleaseACloseAFolderThatIsNotThereOrAnIdThatIsRegistered(@TempDir Path work) throws Exception {
        WorkFolderOverlaps.Registration released = overlaps.register(id(1), work);
        released.release();
        assertEquals(Set.of(), released.release(), "twice is once, and it answers the same");
        WorkFolderOverlaps.Registration closed = overlaps.register(id(2), work);
        closed.close();
        closed.close();
        assertEquals(0, overlaps.registered());

        assertThrows(NoSuchFileException.class, () -> overlaps.register(id(3), work.resolve("missing")));
        assertEquals(0, overlaps.registered(), "a folder that is not there registers nothing");

        WorkFolderOverlaps.Registration present = overlaps.register(id(4), work);
        assertThrows(IllegalArgumentException.class, () -> overlaps.register(id(4), work));
        assertThrows(IllegalArgumentException.class, () -> overlaps.register(" ", work));
        assertEquals(1, overlaps.registered(), "a refused registration is not registered, and it is not named by the one that is");
        assertEquals(Set.of(), present.release());
        assertEquals(0, overlaps.registered());
    }

    @Test
    void manyObservationsOfOneFolderRegisteredFromManyThreadsAllNameEveryOtherAndNoOneElse(@TempDir Path work) throws Exception {
        int count = 12;
        CountDownLatch allRegistered = new CountDownLatch(count);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Set<String>>> answers = new ArrayList<>();
            for (int number = 0; number < count; number++) {
                int mine = number;
                answers.add(pool.submit(() -> {
                    WorkFolderOverlaps.Registration registration = overlaps.register(id(mine), work);
                    allRegistered.countDown();
                    allRegistered.await(); // they are all going on at once
                    return registration.release();
                }));
            }
            for (int number = 0; number < count; number++) {
                Set<String> others = new java.util.HashSet<>();
                for (int other = 0; other < count; other++) {
                    if (other != number) {
                        others.add(id(other));
                    }
                }
                assertEquals(others, answers.get(number).get(DEADLINE.toSeconds(), java.util.concurrent.TimeUnit.SECONDS), "the observation " + id(number));
            }
        }
        assertEquals(0, overlaps.registered());
    }
}
