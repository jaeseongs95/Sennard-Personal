// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jaeseongs95.sennard.app.journal.BoundedScanner;
import io.github.jaeseongs95.sennard.app.journal.BoundedScanner.FileStamp;
import io.github.jaeseongs95.sennard.app.journal.BoundedScanner.LookSink;
import io.github.jaeseongs95.sennard.app.journal.BoundedScanner.StopReason;
import io.github.jaeseongs95.sennard.app.journal.JournalScanner.FailureReason;

/**
 * A path that a look could not read, and everything below it, is neither created nor deleted: what a look did not see is not claimed to have been absent, and not claimed to have been
 * there. The looks are real records of the walk, and the last two tests make a real folder unreadable.
 *
 * <pre>
 * u1  after             the look after could not open a folder that the look before saw into: what was in it is not deleted, the folder itself is not deleted, and the result is incomplete
 * u2  before            the look before could not open a folder that the look after sees into: what is in it is not created, the folder itself is not created
 * u3  half a folder     a folder whose listing failed halfway: the children after the failure are not claimed in either direction
 * u4  the root          a root that cannot be resolved at one look is one failure on {@code .}: nothing is created or deleted
 * u5  siblings          the rule is for the path and what is below it: a sibling that has the unreadable one's name as its beginning is compared as ever
 * u6  every kind        files, folders and links below an unreadable folder are all left out of created and deleted, and nothing else is
 * u7  a real folder     a folder that is made unreadable on a real file system, whichever look it is at
 * u8  modified below    a file that both looks saw and that differs is modified, even below a folder whose listing failed halfway (at the look before or after) and when the folder
 *                       itself, {@code .}, failed halfway: the files of what is unreadable are not created or deleted, the ones both saw are compared
 * u9  handed twice      a path that the walk hands over a second time is the first again: a path that was there is not created, a new one is reported once and leased once, a modified file
 *                       or an unreadable path likewise, whatever kind it is
 * </pre>
 */
class WorkFolderUnreadableTest {
    private static final FileStamp STAMP = new FileStamp(1, 1);

    /** What one look found, written down in the order the walk hands it over, and how it ended. */
    private static final class Look {
        private final List<Consumer<LookSink>> events = new ArrayList<>();
        private StopReason stoppedBy;

        Look file(String... paths) {
            for (String path : paths) {
                events.add(sink -> sink.file(path, STAMP));
            }
            return this;
        }

        Look directory(String... paths) {
            for (String path : paths) {
                events.add(sink -> sink.directoryEntered(path));
            }
            return this;
        }

        Look link(String path) {
            events.add(sink -> sink.failure(path, FailureReason.LINK_OR_SPECIAL_SKIPPED));
            return this;
        }

        Look unreadable(String path) {
            events.add(sink -> sink.failure(path, FailureReason.ACCESS_DENIED));
            return this;
        }

        /** A folder that was entered, whose listing gave the children and then failed. */
        Look failedListing(String directory, String... listed) {
            return failedListingOf(directory, STAMP, listed);
        }

        /** The same, with the files handed over as they were stamped. */
        Look failedListingOf(String directory, FileStamp stamp, String... listed) {
            events.add(sink -> sink.directoryEntered(directory));
            for (String path : listed) {
                fileWith(path, stamp);
            }
            events.add(sink -> sink.directoryFailed(directory, FailureReason.IO_ERROR));
            return this;
        }

        Look fileWith(String path, FileStamp stamp) {
            events.add(sink -> sink.file(path, stamp));
            return this;
        }

        Look cut(StopReason reason) {
            this.stoppedBy = reason;
            return this;
        }

        BoundedScanner.Walked replay(LookSink sink) {
            long looked = 0;
            for (Consumer<LookSink> event : events) {
                event.accept(sink);
                looked++;
            }
            return new BoundedScanner.Walked(stoppedBy, looked);
        }
    }

    /** A scanner that hands the sink what the look says, in place of walking a folder: the records are the ones a walk makes, and the look before and after are the real ones. */
    private static final class ScriptedScanner extends BoundedScanner {
        private final Look look;

        ScriptedScanner(Look look) {
            this.look = look;
        }

        @Override
        public Walked walk(Path root, Bounds bounds, LookSink sink) {
            return look.replay(sink);
        }
    }

    private static Look look() {
        return new Look();
    }

    private static final Path FOLDER = Path.of("ignored-by-the-scripted-scanner");
    private static final BoundedScanner.Bounds BOUNDS = new BoundedScanner.Bounds(Long.MAX_VALUE, Duration.ofDays(1), Set.of());

    /** What the two looks say, and how many entries of the lease the observation held at the end of the look after. */
    private record Measured(WorkFolderChanges changes, long held) {
    }

    private static Measured measure(Look before, Look after) {
        ObservedEntries.Lease lease = new ObservedEntries(1_000_000).lease();
        LookBefore lookBefore = LookBefore.take(new ScriptedScanner(before), FOLDER, BOUNDS, lease);
        WorkFolderChanges changes = LookAfter.take(new ScriptedScanner(after), FOLDER, BOUNDS, lookBefore, lease).changes(Set.of());
        return new Measured(changes, lease.held());
    }

    private static WorkFolderChanges between(Look before, Look after) {
        return measure(before, after).changes();
    }

    private static final FileStamp CHANGED = new FileStamp(2, 2);

    @Test
    void whatWasInAFolderThatTheLookAfterCouldNotOpenIsNotDeleted() {
        Look before = look().directory(".", "D", "E").file("D/x", "D/y", "E/z", "top.txt");
        Look after = look().directory(".", "E").unreadable("D").file("E/z", "top.txt");

        WorkFolderChanges changes = between(before, after);

        assertEquals(List.of(), changes.deleted(), "D/x and D/y were not seen to go: " + changes);
        assertEquals(List.of(), changes.deletedDirectories(), "and D is not seen to go either");
        assertEquals(List.of("D (ACCESS_DENIED)"), changes.unreadable());
        assertFalse(changes.complete());
    }

    @Test
    void whatIsInAFolderThatTheLookBeforeCouldNotOpenIsNotCreated() {
        Look before = look().directory(".", "E").unreadable("D").file("E/z", "top.txt");
        Look after = look().directory(".", "D", "E").file("D/x", "D/y", "E/z", "top.txt").directory("D/sub");

        WorkFolderChanges changes = between(before, after);

        assertEquals(List.of(), changes.created(), "D/x and D/y were not seen to come: " + changes);
        assertEquals(List.of(), changes.createdDirectories(), "and neither were D and D/sub");
        assertEquals(List.of("D (ACCESS_DENIED)"), changes.unreadable());
        assertFalse(changes.complete());
    }

    @Test
    void theChildrenAfterAFailureInTheMiddleOfAListingAreNotClaimedEitherWay() {
        // the walk listed D/a, then the listing failed: D is the failure and D/b, D/c are not seen
        Look listed = look().directory(".").failedListing("D", "D/a");
        Look whole = look().directory(".", "D").file("D/a", "D/b", "D/c");

        WorkFolderChanges gone = between(whole, listed);
        assertEquals(List.of(), gone.deleted(), "D/b and D/c were not seen to go: " + gone);
        assertEquals(List.of(), gone.deletedDirectories());

        WorkFolderChanges came = between(listed, whole);
        assertEquals(List.of(), came.created(), "D/b and D/c were not seen to come: " + came);
        assertEquals(List.of(), came.createdDirectories());
    }

    @Test
    void aRootThatCannotBeResolvedAtOneLookLeavesNothingCreatedOrDeleted() {
        Look whole = look().directory(".", "sub").file("a.txt", "sub/b.txt");
        Look root = look().unreadable(".");

        WorkFolderChanges gone = between(whole, root);
        assertEquals(List.of(), gone.deleted(), "every file of the folder was not seen to go: " + gone);
        assertEquals(List.of(), gone.deletedDirectories());
        assertEquals(List.of(". (ACCESS_DENIED)"), gone.unreadable());
        assertFalse(gone.complete());

        WorkFolderChanges came = between(root, whole);
        assertEquals(List.of(), came.created(), "and every file was not seen to come: " + came);
        assertEquals(List.of(), came.createdDirectories());
    }

    @Test
    void aSiblingWhoseNameBeginsWithTheUnreadableOnesIsComparedAsEver() {
        Look before = look().directory(".", "D", "D2").file("D/x", "D2/x", "D2/y");
        Look after = look().directory(".", "D2").unreadable("D").file("D2/y", "D2/new");

        WorkFolderChanges changes = between(before, after);

        assertEquals(List.of("D2/x"), changes.deleted(), "D2 is not D: its files are compared, and D2/x is gone");
        assertEquals(List.of("D2/new"), changes.created());
        assertEquals(List.of(), changes.deletedDirectories(), "D is left out, D2 is still there");

        WorkFolderChanges reverse = between(after, before);
        assertEquals(List.of("D2/x"), reverse.created());
        assertEquals(List.of("D2/new"), reverse.deleted());
        assertEquals(List.of(), reverse.createdDirectories());
    }

    @Test
    void filesFoldersAndLinksBelowAnUnreadableFolderAreAllLeftOutAndNothingElseIs() {
        Look before = look().directory(".", "D", "D/sub", "keep").file("D/x", "D/sub/y", "keep/k", "keep/gone").link("D/link");
        Look after = look().directory(".", "keep").unreadable("D").file("keep/k", "keep/made");

        WorkFolderChanges changes = between(before, after);

        assertEquals(List.of("keep/gone"), changes.deleted(), "no file and no link of D is deleted, the one beside it is");
        assertEquals(List.of("keep/made"), changes.created());
        assertEquals(List.of(), changes.deletedDirectories(), "D and D/sub are not seen to go");
        assertTrue(changes.modified().isEmpty());
    }

    @Test
    void aFileBothLooksSawIsModifiedEvenBelowAFolderWhoseListingFailedHalfwayAtTheLookBefore() {
        Look before = look().directory(".").failedListingOf("D", STAMP, "D/a");
        Look after = look().directory(".", "D").fileWith("D/a", CHANGED).file("D/b");

        WorkFolderChanges changes = between(before, after);

        assertEquals(List.of("D/a"), changes.modified(), "both looks saw D/a as a file and it differs: " + changes);
        assertEquals(List.of(), changes.created(), "D/b is below D, which the look before could not list");
        assertEquals(List.of("D (IO_ERROR)"), changes.unreadable());
    }

    @Test
    void aFileBothLooksSawIsModifiedEvenBelowAFolderWhoseListingFailedHalfwayAtTheLookAfter() {
        Look before = look().directory(".", "D").file("D/a", "D/b");
        Look after = look().directory(".").failedListingOf("D", CHANGED, "D/a");

        WorkFolderChanges changes = between(before, after);

        assertEquals(List.of("D/a"), changes.modified(), "both looks saw D/a as a file and it differs: " + changes);
        assertEquals(List.of(), changes.deleted(), "D/b is below D, which the look after could not list");
        assertEquals(List.of("D (IO_ERROR)"), changes.unreadable());
    }

    @Test
    void aFileBothLooksSawIsModifiedWhenTheListingOfTheFolderItselfFailedHalfwayAtEitherLook() {
        Look whole = look().directory(".").fileWith("a.txt", CHANGED).file("b.txt");
        Look cutRoot = look().failedListingOf(".", STAMP, "a.txt", "b.txt");

        WorkFolderChanges atBefore = between(cutRoot, whole);
        assertEquals(List.of("a.txt"), atBefore.modified(), "the folder itself could not be listed to the end at the look before: " + atBefore);

        WorkFolderChanges atAfter = between(whole, cutRoot);
        assertEquals(List.of("a.txt"), atAfter.modified(), "the folder itself could not be listed to the end at the look after: " + atAfter);
    }

    /** What one look does when the walk hands over the same path twice (a folder that changes while it is listed): the second is the first again. */
    private static void sameAsOnce(Look before, Look once, Look twice) {
        Measured one = measure(before, once);
        Measured two = measure(before, twice);
        assertEquals(one.changes(), two.changes(), "the second arrival of a path changes nothing");
        assertEquals(one.held(), two.held(), "and costs no second entry of the lease");
    }

    @Test
    void aPathTheLookAfterIsHandedTwiceIsNotACreationWhenItWasThereBefore() {
        Look before = look().directory(".", "D").file("a", "b", "D/c").link("l");

        Look twice = look().directory(".", "D").file("a", "a", "b", "D/c", "D/c").link("l").link("l");
        Measured measured = measure(before, twice);
        assertEquals(List.of(), measured.changes().created(), "a and l were there before: " + measured.changes());
        assertTrue(measured.changes().unchanged(), measured.changes().toString());
        sameAsOnce(before, look().directory(".", "D").file("a", "b", "D/c").link("l"), twice);

        sameAsOnce(before, look().directory(".", "D").file("a", "b", "D/c").link("l"), look().directory(".", "D", "D").file("a", "b", "D/c").link("l"));
    }

    @Test
    void aNewPathTheLookAfterIsHandedTwiceIsReportedOnceAndLeasedOnce() {
        Look before = look().directory(".").file("a");
        Look once = look().directory(".", "N").file("a", "n", "N/x").link("l");
        Look twice = look().directory(".", "N", "N").file("a", "n", "n", "N/x", "N/x").link("l").link("l");

        Measured measured = measure(before, twice);
        assertEquals(List.of("N/x", "l", "n"), measured.changes().created(), measured.changes().toString());
        assertEquals(List.of("N"), measured.changes().createdDirectories());
        sameAsOnce(before, once, twice);
        assertEquals(1 + 1 + 4, measured.held(), "the look before holds 2 entries, the look after one each for n, N, N/x and l");
    }

    @Test
    void aModifiedFileOrAnUnreadablePathHandedTwiceIsReportedOnceAndLeasedOnce() {
        Look before = look().directory(".").file("a", "b");
        sameAsOnce(before, look().directory(".").fileWith("a", CHANGED).file("b"), look().directory(".").fileWith("a", CHANGED).fileWith("a", CHANGED).file("b"));
        sameAsOnce(before, look().directory(".").file("b").unreadable("a"), look().directory(".").file("b").unreadable("a").unreadable("a"));
        assertEquals(List.of("a"), between(before, look().directory(".").fileWith("a", CHANGED).fileWith("a", CHANGED).file("b")).modified());
    }

    @Test
    void aPathThatChangedKindAndIsHandedTwiceIsReportedAsOnce() {
        Look wasFile = look().directory(".").file("p");
        sameAsOnce(wasFile, look().directory(".", "p"), look().directory(".", "p", "p"));
        Look wasFolder = look().directory(".", "p");
        sameAsOnce(wasFolder, look().directory(".").file("p"), look().directory(".").file("p", "p"));
    }

    /** What makes a folder unreadable, chosen by what the file system of the folder can do and not by the name of the system. */
    private interface Blocked {
        /** Makes the folder readable again. */
        void close() throws IOException;
    }

    private static Blocked block(Path folder) throws IOException {
        FileStore store = Files.getFileStore(folder);
        Blocked blocked;
        if (store.supportsFileAttributeView(PosixFileAttributeView.class)) {
            Set<PosixFilePermission> was = Files.getPosixFilePermissions(folder);
            Files.setPosixFilePermissions(folder, Set.of());
            blocked = () -> Files.setPosixFilePermissions(folder, was);
        } else if (store.supportsFileAttributeView(AclFileAttributeView.class)) {
            AclFileAttributeView view = Files.getFileAttributeView(folder, AclFileAttributeView.class);
            List<AclEntry> was = view.getAcl();
            List<AclEntry> denied = new ArrayList<>(was);
            denied.add(0, AclEntry.newBuilder().setType(AclEntryType.DENY).setPrincipal(view.getOwner()).setPermissions(AclEntryPermission.LIST_DIRECTORY).build());
            view.setAcl(denied);
            blocked = () -> view.setAcl(was);
        } else {
            assumeTrue(false, "the file system of " + folder + " has neither permissions nor ACL to make a folder unreadable with");
            return null;
        }
        boolean listable;
        try (DirectoryStream<Path> listing = Files.newDirectoryStream(folder)) {
            listable = listing != null;
        } catch (IOException denied) {
            listable = false; // the folder is unreadable, as it is meant to be
        }
        if (listable) {
            blocked.close();
            assumeTrue(false, "this account lists a folder it is denied (the administrator of the system does): " + folder);
        }
        return blocked;
    }

    private final WorkFolderOverlaps overlaps = new WorkFolderOverlaps();
    private final ObservedEntries entries = new ObservedEntries(1_000_000);
    private final WorkFolderObserver observer = new WorkFolderObserver(new BoundedScanner(), overlaps, entries, null, 100_000, Duration.ofMinutes(1));

    private static void populate(Path work) throws IOException {
        Files.createDirectories(work.resolve("D/sub"));
        Files.writeString(work.resolve("D/x"), "x");
        Files.writeString(work.resolve("D/sub/y"), "y");
        Files.writeString(work.resolve("top.txt"), "t");
    }

    @Test
    void aRealFolderThatCannotBeReadAtTheLookAfterHasNothingDeletedInIt(@TempDir Path work) throws Exception {
        populate(work);
        WorkFolderObservation observation = observer.begin(ExecutionIds.next(), work);

        WorkFolderChanges changes;
        Blocked blocked = block(work.resolve("D"));
        try {
            changes = observation.finish();
        } finally {
            blocked.close();
        }

        assertEquals(List.of(), changes.deleted(), "D/x and D/sub/y are still there: " + changes);
        assertEquals(List.of(), changes.deletedDirectories(), "and so are D and D/sub");
        assertEquals(1, changes.unreadable().size(), changes.toString());
        assertTrue(changes.unreadable().get(0).startsWith("D ("), changes.unreadable().get(0));
        assertFalse(changes.complete());
    }

    @Test
    void aRealFolderThatCannotBeReadAtTheLookBeforeHasNothingCreatedInIt(@TempDir Path work) throws Exception {
        populate(work);
        WorkFolderObservation observation;
        Blocked blocked = block(work.resolve("D"));
        try {
            observation = observer.begin(ExecutionIds.next(), work);
        } finally {
            blocked.close();
        }

        WorkFolderChanges changes = observation.finish();

        assertEquals(List.of(), changes.created(), "D/x and D/sub/y were there all along: " + changes);
        assertEquals(List.of(), changes.createdDirectories(), "and so were D/sub");
        assertEquals(1, changes.unreadable().size(), changes.toString());
        assertFalse(changes.complete());
    }
}
