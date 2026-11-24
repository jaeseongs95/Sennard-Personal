// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.execution;

import static io.github.jaeseongs95.sennard.personal.RunnerFixture.awaitFile;
import static io.github.jaeseongs95.sennard.personal.RunnerFixture.java;
import static io.github.jaeseongs95.sennard.personal.RunnerFixture.program;
import static io.github.jaeseongs95.sennard.personal.RunnerFixture.request;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jaeseongs95.sennard.app.journal.BoundedScanner;
import io.github.jaeseongs95.sennard.app.journal.BoundedScanner.StopReason;
import io.github.jaeseongs95.sennard.personal.ReleasedFolder;
import io.github.jaeseongs95.sennard.personal.RunnerFixture;
import io.github.jaeseongs95.sennard.server.execution.WorkFolderChanges.Incomplete;
import io.github.jaeseongs95.sennard.server.execution.WorkFolderChanges.Look;

/**
 * The observation of the working folder of real programs, started by the real runner of the personal edition, with the real register, the real scanner and real folders: an observed
 * execution does not wait for another that is observed in the same folder (or above or below it) and both name each other; a look has a bound and says when it reached it; the state
 * folder of the product is not walked. No mock, no pause of a chosen length: what is waited for is a file a program writes or the end of an execution.
 *
 * <pre>
 * m1  no waiting       an execution whose program has not ended does not hold up a second one in the same folder: it is observed and run at once, and each names the other
 * m2  above and below  an execution in a folder and one in the folder above it name each other, and one in a sibling names neither; one that ended before another began is not named
 * m3  left behind      a program that could not be started, one that is cancelled and one whose observation is closed leave nothing registered
 * m4  entry bound      a look that meets the bound of entries says ENTRY_LIMIT and how many entries it saw; the execution is not refused and ends as it would
 * m5  time bound       a look that meets the bound of time says TIME_LIMIT; the execution is not refused
 * m6  one look cut     with the look before whole and the look after cut (and the other way round) only the paths both saw are compared: nothing is reported as created or deleted;
 *                      the folder a look was stopped inside of, and its ancestors (the folder itself first), were there at both looks and are no folder created or deleted; a path that was
 *                      a folder at one look and a file at the other is reported on both sides
 * m7  the state folder a state folder inside the observed folder is not walked: the log files of the execution are not its changes, and the result says it was not observed
 * m8  all together    the entries that all the observations hold are bounded as one: a look that finds none left stops there with TOTAL_ENTRY_LIMIT and the observation is not refused; the
 *                      entries come back when an observation ends, by finish or by close, and the folder can be observed whole again
 * m9  what it holds    an observation holds the entries of its folder and what changed in it, not twice the entries: the look after takes none for what is as it was (a folder of N entries
 *                      is observed whole with a total of N), one for each path that is new or a file that is modified, and none for what is deleted; a change that finds none left stops
 *                      the look after with TOTAL_ENTRY_LIMIT, and what was found as new is not claimed
 * </pre>
 */
class WorkFolderObservationLimitsTest {
    private static final Duration LONG = Duration.ofMinutes(2);
    private static final Duration DEADLINE = Duration.ofSeconds(60);
    /** Does not end by itself. */
    private static final String WAITER = """
            public class Waiter {
                public static void main(String[] args) throws Exception {
                    java.nio.file.Files.writeString(java.nio.file.Path.of("started.txt"), "started");
                    Thread.sleep(600_000);
                }
            }
            """;
    /** Makes a file and ends. */
    private static final String WRITES = """
            public class Writes {
                public static void main(String[] args) throws Exception {
                    java.nio.file.Files.writeString(java.nio.file.Path.of("made.txt"), "made");
                }
            }
            """;
    /** Makes thirty files and ends. */
    private static final String MANY = """
            public class Many {
                public static void main(String[] args) throws Exception {
                    for (int number = 0; number < 30; number++) {
                        java.nio.file.Files.writeString(java.nio.file.Path.of("new-" + number + ".txt"), "n");
                    }
                }
            }
            """;
    /** Deletes every file whose name starts with f but two. */
    private static final String PRUNE = """
            public class Prune {
                public static void main(String[] args) throws Exception {
                    try (var files = java.nio.file.Files.list(java.nio.file.Path.of("."))) {
                        for (java.nio.file.Path file : files.toList()) {
                            String name = file.getFileName().toString();
                            if (name.startsWith("f") && !name.equals("f0.txt") && !name.equals("f1.txt")) {
                                java.nio.file.Files.delete(file);
                            }
                        }
                    }
                }
            }
            """;

    private final WorkFolderOverlaps overlaps = new WorkFolderOverlaps();

    /** The entries of the server's observations: far more than any test of the bounds of one look uses. */
    private final ObservedEntries entries = new ObservedEntries(10_000_000);

    private WorkFolderObserver observer(Path stateDirectory, long maxEntries, Duration maxTime) {
        return observer(entries, stateDirectory, maxEntries, maxTime);
    }

    private WorkFolderObserver observer(ObservedEntries shared, Path stateDirectory, long maxEntries, Duration maxTime) {
        return new WorkFolderObserver(new BoundedScanner(), overlaps, shared, stateDirectory, maxEntries, maxTime);
    }

    /** An observation of a program of the runner: what a caller of both does, in its order (the observation begins, the program starts, it ends, the observation finishes). */
    private record Observed(String id, WorkFolderObservation observation, ExecutionHandle handle) {
        WorkFolderChanges end() throws InterruptedException {
            handle.await(LONG);
            return observation.finish();
        }
    }

    private Observed run(WorkFolderObserver observer, RunnerFixture fixture, Path folder, String className) throws Exception {
        String id = ExecutionIds.next();
        WorkFolderObservation observation = observer.begin(id, folder);
        ExecutionHandle handle = fixture.runner.start(id, java(folder, LONG, className));
        return new Observed(id, observation, handle);
    }

    private static void files(Path folder, String prefix, int count) throws IOException {
        for (int number = 0; number < count; number++) {
            Files.writeString(folder.resolve(prefix + number + ".txt"), "x");
        }
    }

    @Test
    void anExecutionThatHasNotEndedDoesNotHoldUpASecondOneInTheSameFolderAndTheyNameEachOther(@TempDir Path work, @TempDir Path state) throws Exception {
        program(work, "Waiter", WAITER);
        program(work, "Writes", WRITES);
        try (RunnerFixture fixture = new RunnerFixture(state)) {
            WorkFolderObserver observer = observer(null, 100_000, Duration.ofMinutes(1));
            Observed first = run(observer, fixture, work, "Waiter");
            awaitFile(work.resolve("started.txt"));

            // the first has not ended: a lock would hold this one for ever (the deadline of the test ends it)
            Observed second = assertTimeoutPreemptively(DEADLINE, () -> run(observer, fixture, work, "Writes"));
            WorkFolderChanges ofSecond = second.end();
            first.handle().cancel();
            WorkFolderChanges ofFirst = first.observation().finish();

            assertEquals(List.of(first.id()), ofSecond.overlappingExecutions());
            assertEquals(List.of(second.id()), ofFirst.overlappingExecutions());
            assertFalse(ofSecond.attributionCertain());
            assertFalse(ofFirst.attributionCertain());
            assertTrue(ofSecond.created().contains("made.txt"), "what the second made is in its result: " + ofSecond);
            assertTrue(ofFirst.created().contains("made.txt"), "and in the first's, which was going on meanwhile: the result does not say who made it, the overlap does: " + ofFirst);
            assertEquals(0, overlaps.registered());
        }
        ReleasedFolder.await(work);
    }

    @Test
    void anExecutionAboveAndOneBelowNameEachOtherASiblingAndOneThatEndedBeforeNameNeither(@TempDir Path work, @TempDir Path state) throws Exception {
        Path below = Files.createDirectories(work.resolve("below"));
        Path sibling = Files.createDirectories(work.resolve("sibling"));
        for (Path folder : List.of(work, below, sibling)) {
            program(folder, "Waiter", WAITER);
            program(folder, "Writes", WRITES);
        }
        try (RunnerFixture fixture = new RunnerFixture(state)) {
            WorkFolderObserver observer = observer(null, 100_000, Duration.ofMinutes(1));
            Observed earlier = run(observer, fixture, below, "Writes");
            WorkFolderChanges ofEarlier = earlier.end();
            assertEquals(List.of(), ofEarlier.overlappingExecutions());
            assertTrue(ofEarlier.attributionCertain());

            Observed inBelow = run(observer, fixture, below, "Waiter");
            awaitFile(below.resolve("started.txt"));
            Observed inSibling = run(observer, fixture, sibling, "Waiter");
            awaitFile(sibling.resolve("started.txt"));
            Observed inAbove = run(observer, fixture, work, "Writes");
            WorkFolderChanges ofAbove = inAbove.end();
            inBelow.handle().cancel();
            inSibling.handle().cancel();
            WorkFolderChanges ofBelow = inBelow.observation().finish();
            WorkFolderChanges ofSibling = inSibling.observation().finish();

            assertEquals(List.of(inBelow.id(), inSibling.id()).stream().sorted().toList(), ofAbove.overlappingExecutions(), "the folder above is related to both below it");
            assertEquals(List.of(inAbove.id()), ofBelow.overlappingExecutions(), "the one below is related to the one above and not to its sibling, nor to the one that had ended");
            assertEquals(List.of(inAbove.id()), ofSibling.overlappingExecutions());
            assertTrue(ofAbove.created().contains("made.txt"));
            assertEquals(0, overlaps.registered());
        }
        ReleasedFolder.await(work);
    }

    @Test
    void aProgramThatCouldNotBeStartedACancelledOneAndAClosedObservationLeaveNothingRegistered(@TempDir Path work, @TempDir Path state) throws Exception {
        program(work, "Waiter", WAITER);
        try (RunnerFixture fixture = new RunnerFixture(state)) {
            WorkFolderObserver observer = observer(null, 100_000, Duration.ofMinutes(1));

            String notStartedId = ExecutionIds.next();
            WorkFolderObservation notStarted = observer.begin(notStartedId, work);
            try {
                ExecutionHandle handle = fixture.runner.start(notStartedId, request(List.of(work.resolve("no-such-program").toString()), work, LONG));
                assertEquals(ExecutionState.NOT_STARTED, handle.snapshot().state());
            } finally {
                notStarted.close();
            }
            assertEquals(0, overlaps.registered(), "a program that was not started: the caller closes the observation");

            Observed cancelled = run(observer, fixture, work, "Waiter");
            awaitFile(work.resolve("started.txt"));
            assertEquals(1, overlaps.registered());
            cancelled.handle().cancel();
            WorkFolderChanges ofCancelled = cancelled.observation().finish();
            assertEquals(0, overlaps.registered(), "a cancelled execution ended its observation");
            assertEquals(List.of(), ofCancelled.overlappingExecutions(), "and the one that was not started left nothing behind to be named");
        }
        ReleasedFolder.await(work);
    }

    @Test
    void aLookThatMeetsTheBoundOfEntriesSaysSoAndHowManyItSawAndTheExecutionIsNotRefused(@TempDir Path work, @TempDir Path state) throws Exception {
        program(work, "Writes", WRITES);
        files(work, "f", 30);
        try (RunnerFixture fixture = new RunnerFixture(state)) {
            Observed observed = run(observer(null, 10, Duration.ofMinutes(1)), fixture, work, "Writes");

            WorkFolderChanges changes = observed.end();

            assertEquals(ExecutionState.EXITED, observed.handle().snapshot().state(), "the program ran as it would have");
            assertEquals(List.of(new Incomplete(Look.BEFORE, StopReason.ENTRY_LIMIT, 10), new Incomplete(Look.AFTER, StopReason.ENTRY_LIMIT, 10)), changes.incomplete());
            assertFalse(changes.complete());
            assertEquals(List.of(), changes.created(), "what one look did not see is not reported as created: " + changes);
        }
    }

    @Test
    void aLookThatMeetsTheBoundOfTimeSaysSoAndTheExecutionIsNotRefused(@TempDir Path work, @TempDir Path state) throws Exception {
        program(work, "Writes", WRITES);
        files(work, "f", 5);
        try (RunnerFixture fixture = new RunnerFixture(state)) {
            // a bound of no time at all is met at the first entry: no pause of a chosen length is needed to make a look too slow
            Observed observed = run(observer(null, 100_000, Duration.ZERO), fixture, work, "Writes");

            WorkFolderChanges changes = observed.end();

            assertEquals(ExecutionState.EXITED, observed.handle().snapshot().state());
            assertEquals(List.of(new Incomplete(Look.BEFORE, StopReason.TIME_LIMIT, 0), new Incomplete(Look.AFTER, StopReason.TIME_LIMIT, 0)), changes.incomplete());
            assertFalse(changes.complete());
            assertTrue(changes.unchanged(), "nothing was seen at either look, and nothing is claimed: " + changes);
        }
    }

    @Test
    void withTheLookAfterCutOnlyThePathsBothSawAreComparedAndNothingIsReportedAsCreated(@TempDir Path work, @TempDir Path state) throws Exception {
        program(work, "Many", MANY);
        files(work, "f", 5); // the look before sees the folder, five files and the program's two files: eight entries of the ten
        try (RunnerFixture fixture = new RunnerFixture(state)) {
            Observed observed = run(observer(null, 10, Duration.ofMinutes(1)), fixture, work, "Many");

            WorkFolderChanges changes = observed.end();

            assertEquals(List.of(new Incomplete(Look.AFTER, StopReason.ENTRY_LIMIT, 10)), changes.incomplete(), "only the look after was cut");
            assertEquals(List.of(), changes.created(), "the program made thirty files and the look after saw some of them: without the rule they would be reported");
            assertEquals(List.of(), changes.deleted());
            assertEquals(List.of(), changes.deletedDirectories(), "the folder was in the look before, whole, and in the look after, where it was stopped inside of: it was there at both: " + changes);
            assertEquals(List.of(), changes.createdDirectories());
        }
    }

    @Test
    void withTheLookBeforeCutOnlyThePathsBothSawAreComparedAndNothingIsReportedAsDeleted(@TempDir Path work, @TempDir Path state) throws Exception {
        program(work, "Prune", PRUNE);
        files(work, "f", 30);
        try (RunnerFixture fixture = new RunnerFixture(state)) {
            Observed observed = run(observer(null, 10, Duration.ofMinutes(1)), fixture, work, "Prune");

            WorkFolderChanges changes = observed.end();

            assertEquals(List.of(new Incomplete(Look.BEFORE, StopReason.ENTRY_LIMIT, 10)), changes.incomplete(), "only the look before was cut: two files are left in the folder");
            assertEquals(List.of(), changes.deleted(), "the program deleted twenty-eight files and the look before saw some of them: without the rule they would be reported");
            assertEquals(List.of(), changes.created());
            assertEquals(List.of(), changes.createdDirectories(), "the folder was stopped inside of at the look before and whole at the look after: it was there at both: " + changes);
            assertEquals(List.of(), changes.deletedDirectories());
            assertFalse(Files.exists(work.resolve("f2.txt")), "the program did delete them");
        }
    }

    @Test
    void aFolderAndItsAncestorsThatTheLookAfterWasStoppedInsideOfAreNeitherCreatedNorDeleted(@TempDir Path work) throws Exception {
        Path inner = Files.createDirectories(work.resolve("a/b"));
        files(inner, "f", 1); // the look before sees the folder, a, b and the file: four entries of the six
        WorkFolderObservation observation = observer(null, 6, LONG).begin(ExecutionIds.next(), work);

        files(inner, "g", 10); // the look after is stopped inside b: the folder, a and b are folders it was inside of, and none of them is finished
        WorkFolderChanges changes = observation.finish();

        assertEquals(List.of(new Incomplete(Look.AFTER, StopReason.ENTRY_LIMIT, 6)), changes.incomplete());
        assertEquals(List.of(), changes.deletedDirectories(), "they are there at both looks: " + changes);
        assertEquals(List.of(), changes.createdDirectories());
        assertEquals(List.of(), changes.created());
    }

    @Test
    void aFolderAndItsAncestorsThatTheLookBeforeWasStoppedInsideOfAreNeitherCreatedNorDeleted(@TempDir Path work) throws Exception {
        Path inner = Files.createDirectories(work.resolve("a/b"));
        files(inner, "f", 20);
        WorkFolderObservation observation = observer(null, 6, LONG).begin(ExecutionIds.next(), work); // stopped inside b
        for (int number = 0; number < 20; number++) {
            Files.delete(inner.resolve("f" + number + ".txt"));
        }

        WorkFolderChanges changes = observation.finish(); // the look after sees the folder, a and b whole

        assertEquals(List.of(new Incomplete(Look.BEFORE, StopReason.ENTRY_LIMIT, 6)), changes.incomplete());
        assertEquals(List.of(), changes.createdDirectories(), "they are there at both looks: " + changes);
        assertEquals(List.of(), changes.deletedDirectories());
        assertEquals(List.of(), changes.deleted());
    }

    @Test
    void aFileThatBecameAFolderIsReportedAsBothWhenTheLookAfterWasStoppedInsideIt(@TempDir Path work) throws Exception {
        Files.writeString(work.resolve("d"), "a file");
        WorkFolderObservation observation = observer(null, 4, LONG).begin(ExecutionIds.next(), work); // the folder and the file: two entries
        Files.delete(work.resolve("d"));
        Path now = Files.createDirectories(work.resolve("d"));
        files(now, "x", 10); // the look after is stopped inside d

        WorkFolderChanges changes = observation.finish();

        assertEquals(List.of(new Incomplete(Look.AFTER, StopReason.ENTRY_LIMIT, 4)), changes.incomplete());
        assertEquals(List.of("d"), changes.deleted(), "the file is gone: d is a folder, and was seen to be one");
        assertEquals(List.of("d"), changes.createdDirectories(), "d is a folder the look after was inside of");
        assertEquals(List.of(), changes.deletedDirectories(), "and the folder itself was there all along: " + changes);
        assertEquals(List.of(), changes.created());
    }

    @Test
    void aFolderThatBecameAFileIsReportedAsBothWhenTheLookBeforeWasStoppedInsideIt(@TempDir Path work) throws Exception {
        Path folder = Files.createDirectories(work.resolve("d"));
        files(folder, "x", 10);
        WorkFolderObservation observation = observer(null, 4, LONG).begin(ExecutionIds.next(), work); // stopped inside d
        for (int number = 0; number < 10; number++) {
            Files.delete(folder.resolve("x" + number + ".txt"));
        }
        Files.delete(folder);
        Files.writeString(work.resolve("d"), "a file now");

        WorkFolderChanges changes = observation.finish();

        assertEquals(List.of(new Incomplete(Look.BEFORE, StopReason.ENTRY_LIMIT, 4)), changes.incomplete());
        assertEquals(List.of("d"), changes.created(), "the file is new: d was a folder, and was seen to be one");
        assertEquals(List.of("d"), changes.deletedDirectories(), "d is a folder the look before was inside of");
        assertEquals(List.of(), changes.createdDirectories(), "and the folder itself was there all along: " + changes);
        assertEquals(List.of(), changes.deleted());
    }

    @Test
    void aStateFolderInsideTheObservedFolderIsNotWalkedAndItsLogsAreNotTheProgramsChanges(@TempDir Path work) throws Exception {
        program(work, "Writes", WRITES);
        Path state = Files.createDirectories(work.resolve("state"));
        try (RunnerFixture fixture = new RunnerFixture(state)) {
            Observed observed = run(observer(state, 100_000, Duration.ofMinutes(1)), fixture, work, "Writes");

            WorkFolderChanges changes = observed.end();

            assertTrue(Files.exists(Path.of(observed.handle().snapshot().logLocation()).resolve("stdout.log")), "the runner wrote the log files of the execution inside the observed folder");
            assertEquals(List.of("made.txt"), changes.created(), "the program's change is reported and the server's own logs are not: " + changes);
            assertEquals(List.of(), changes.createdDirectories());
            assertEquals(List.of("state"), changes.notObserved());
            assertTrue(changes.complete(), "a folder that is not observed by design does not make the result incomplete");
        }
    }

    @Test
    void withoutTheExclusionTheLogsOfTheExecutionAreReportedAsChangesTheControlOfTheStateFolderTest(@TempDir Path work) throws Exception {
        program(work, "Writes", WRITES);
        Path state = Files.createDirectories(work.resolve("state"));
        try (RunnerFixture fixture = new RunnerFixture(state)) {
            Observed observed = run(observer(null, 100_000, Duration.ofMinutes(1)), fixture, work, "Writes");

            WorkFolderChanges changes = observed.end();

            assertTrue(changes.created().stream().anyMatch(path -> path.startsWith("state/executions/") && path.endsWith("/stdout.log")), "the log files are in the changes: " + changes);
            assertEquals(List.of(), changes.notObserved());
        }
    }

    @Test
    void aBoundOfTenEntriesIsNotMetByTenEntriesAndIsMetByEleven(@TempDir Path work) throws Exception {
        // the control of the bound tests: the folder itself and nine files are ten entries
        files(work, "f", 9);
        WorkFolderObservation exact = observer(null, 10, Duration.ofMinutes(1)).begin(ExecutionIds.next(), work);
        assertTrue(exact.finish().complete(), "ten entries under a bound of ten");
        files(work, "g", 1);
        WorkFolderObservation over = observer(null, 10, Duration.ofMinutes(1)).begin(ExecutionIds.next(), work);
        assertEquals(List.of(Look.BEFORE, Look.AFTER), over.finish().incomplete().stream().map(Incomplete::look).toList(), "eleven entries under a bound of ten");
    }

    @Test
    void theEntriesOfAllTheObservationsTogetherAreBoundedAndComeBackWhenAnObservationEnds(@TempDir Path first, @TempDir Path second) throws Exception {
        files(first, "f", 15); // the folder and fifteen files: sixteen entries
        files(second, "g", 15);
        ObservedEntries shared = new ObservedEntries(20);
        WorkFolderObserver observer = observer(shared, null, 100_000, Duration.ofMinutes(1));

        WorkFolderObservation inFirst = observer.begin(ExecutionIds.next(), first);
        assertEquals(4, shared.free(), "the look before in the first folder holds sixteen entries of the twenty");
        WorkFolderObservation inSecond = observer.begin(ExecutionIds.next(), second);
        assertEquals(0, shared.free(), "the look before in the second folder took the four that were left and stopped there");

        WorkFolderChanges ofSecond = inSecond.finish();
        assertEquals(List.of(new Incomplete(Look.BEFORE, StopReason.TOTAL_ENTRY_LIMIT, 4)), ofSecond.incomplete(),
                "the look before was not refused: it says where it stopped and why, and that it is not the bound of a look. The look after needed no entry (nothing it saw is kept, it is "
                        + "compared with the look before as it is met) and saw the whole folder");
        assertFalse(ofSecond.complete());
        assertTrue(ofSecond.unchanged(), "only what both looks saw is compared, and none of it changed: " + ofSecond);
        assertEquals(4, shared.free(), "the second observation ended: the four it held are back, and the look after took none");
        WorkFolderChanges ofFirst = inFirst.finish();
        assertTrue(ofFirst.complete(), "the first was not touched by the second: both its looks are whole (the look after needed none of the entries that were not free): " + ofFirst);
        assertTrue(ofFirst.unchanged());
        assertEquals(20, shared.free(), "every entry is back");

        WorkFolderObservation again = observer.begin(ExecutionIds.next(), second);
        assertEquals(4, shared.free(), "with the entries back the folder is looked at whole at the look before: sixteen entries");
        again.close();
        assertEquals(20, shared.free(), "an observation that is closed without a look gives its entries back");
    }

    @Test
    void anObservationOfAFolderOfNEntriesIsWholeWithATotalOfNBecauseTheLookAfterKeepsWhatIsAsItWas(@TempDir Path work) throws Exception {
        files(work, "f", 15); // the folder and fifteen files: sixteen entries
        ObservedEntries exact = new ObservedEntries(16);
        WorkFolderObservation observation = observer(exact, null, 100_000, Duration.ofMinutes(1)).begin(ExecutionIds.next(), work);
        assertEquals(0, exact.free(), "the look before holds all sixteen");

        WorkFolderChanges changes = observation.finish();

        assertTrue(changes.complete(), "the look after found each of the sixteen as it was and took none (a look that kept its own map would have stopped at once): " + changes);
        assertTrue(changes.unchanged());
        assertEquals(16, exact.free());
    }

    @Test
    void theLookAfterTakesAnEntryForEachChangeItKeepsAndNoneForADeletionOrWhatIsAsItWas(@TempDir Path work) throws Exception {
        files(work, "f", 10); // the folder and ten files: eleven entries
        ObservedEntries fits = new ObservedEntries(14); // eleven and three changes
        WorkFolderObservation observation = observer(fits, null, 100_000, Duration.ofMinutes(1)).begin(ExecutionIds.next(), work);
        assertEquals(3, fits.free());
        for (int number = 0; number < 5; number++) {
            Files.delete(work.resolve("f" + number + ".txt")); // five deletions take no entry
        }
        Files.writeString(work.resolve("f5.txt"), "longer than before"); // a modified file takes one
        files(work, "n", 2); // and two new ones take one each

        WorkFolderChanges changes = observation.finish();

        assertTrue(changes.complete(), changes.toString());
        assertEquals(List.of("f0.txt", "f1.txt", "f2.txt", "f3.txt", "f4.txt"), changes.deleted());
        assertEquals(List.of("f5.txt"), changes.modified());
        assertEquals(List.of("n0.txt", "n1.txt"), changes.created());
        assertEquals(14, fits.free(), "and all of it is back when the observation ends: the result holds paths, not entries of the total");
    }

    @Test
    void aChangeThatFindsNoEntryLeftStopsTheLookAfterAndWhatWasFoundAsNewIsNotClaimed(@TempDir Path work) throws Exception {
        files(work, "f", 10); // the folder and ten files: eleven entries
        ObservedEntries tooSmall = new ObservedEntries(13); // eleven and two changes
        WorkFolderObservation observation = observer(tooSmall, null, 100_000, Duration.ofMinutes(1)).begin(ExecutionIds.next(), work);
        assertEquals(2, tooSmall.free());
        files(work, "n", 3); // the third new file finds none left

        WorkFolderChanges changes = observation.finish();

        assertEquals(1, changes.incomplete().size(), changes.toString());
        assertEquals(Look.AFTER, changes.incomplete().get(0).look());
        assertEquals(StopReason.TOTAL_ENTRY_LIMIT, changes.incomplete().get(0).reason());
        assertEquals(List.of(), changes.created(), "the look after did not see the whole folder: a file it found as new may have been there all along: " + changes);
        assertFalse(changes.complete());
        assertEquals(13, tooSmall.free());
    }
}
