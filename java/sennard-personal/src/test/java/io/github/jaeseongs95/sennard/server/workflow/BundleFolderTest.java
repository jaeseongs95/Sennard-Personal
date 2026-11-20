// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The folder the entry puts the attachments of a review bundle in (AGS3-D-0264): every byte in it came out of an attachment, each file is at the path its file name says, and what is not
 * a safe relative path of the portable form the skills' manifests use is refused before anything is written. No database: the folder takes the file name and the bytes of an attachment, as
 * the entry reads them. The cases:
 *
 * <pre>
 * f1  put        each file is written at its relative path (nested folders are made) and the path is returned; an empty file is a file
 * f2  portable   the paths that are not portable are refused (PATH_NOT_PORTABLE, INVALID_INPUT, key workflow.bundle.fileNotUsable) and nothing is written; the portable ones are taken
 * f3  duplicate  a path used twice is refused (DUPLICATE_PATH), a difference of letter case only is the same path; the first file is not overwritten
 * f4  size       the files hold at most MAX_BYTES together: exactly that is taken, one byte more is refused (TOO_LARGE) and not written
 * f5  escape     a path that would leave the folder (by .. in any place) is refused and nothing is written outside the folder
 * f6  close      closing removes the folder and the files in it, nested folders included
 * </pre>
 */
class BundleFolderTest {
    private static final String ID = "11111111-1111-4111-8111-111111111111";
    private static final byte[] BYTES = "bytes of the file".getBytes(StandardCharsets.UTF_8);

    private static long filesIn(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile).count();
        }
    }

    private static WorkflowFailure refused(BundleFolder folder, String attachmentId, String fileName, byte[] content, String reason) {
        WorkflowFailure failure = assertThrows(WorkflowFailure.class, () -> folder.put(attachmentId, fileName, content), fileName);
        assertEquals(WorkflowCode.INVALID_INPUT, failure.code(), fileName);
        assertEquals("workflow.bundle.fileNotUsable", failure.ref().key(), fileName);
        assertEquals(reason, failure.ref().params().get("reason"), fileName);
        assertEquals(reason, failure.details().get("reason"), fileName);
        assertEquals(attachmentId, failure.details().get("attachmentId"), fileName);
        return failure;
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------------------ f1 put

    @Test
    void eachFileIsWrittenAtItsRelativePathAndThePathIsReturned() throws IOException {
        try (BundleFolder folder = BundleFolder.open()) {
            byte[] nested = "nested".getBytes(StandardCharsets.UTF_8);
            assertEquals("stage-bundle.json", folder.put(ID, "stage-bundle.json", BYTES));
            assertEquals("evidence/red/run-1.json", folder.put("22222222-2222-4222-8222-222222222222", "evidence/red/run-1.json", nested));
            assertEquals("empty.txt", folder.put("33333333-3333-4333-8333-333333333333", "empty.txt", new byte[0]));
            assertArrayEquals(BYTES, Files.readAllBytes(folder.root().resolve("stage-bundle.json")));
            assertArrayEquals(nested, Files.readAllBytes(folder.root().resolve("evidence").resolve("red").resolve("run-1.json")));
            assertTrue(Files.isRegularFile(folder.root().resolve("empty.txt")));
            assertEquals(0, Files.size(folder.root().resolve("empty.txt")));
            assertEquals(3, filesIn(folder.root()));
        }
    }

    @Test
    void theFolderIsANewEmptyDirectoryOfTheServersOwnEachTime() throws IOException {
        try (BundleFolder first = BundleFolder.open(); BundleFolder second = BundleFolder.open()) {
            assertTrue(Files.isDirectory(first.root()));
            assertTrue(first.root().isAbsolute());
            assertEquals(first.root().toRealPath(), first.root(), "the root is a real path, so a check that compares paths finds the same one");
            assertNotEquals(first.root(), second.root());
            assertEquals(0, filesIn(first.root()));
            assertEquals(0, filesIn(second.root()));
        }
    }

    @Test
    void namesThatOnlyLookLikeReservedOrOddAreTakenAndLeadingDotsAndInnerSpacesAreNames() throws IOException {
        try (BundleFolder folder = BundleFolder.open()) {
            for (String portable : new String[] { "CONSOLE.txt", "dir/aux1.txt", "dir/com10", ".hidden", "dir/a.b.c", "a b/c d.txt", "dir/\uD55C\uAE00.txt", "dir/conf.d/x" }) {
                assertEquals(portable, folder.put(ID, portable, BYTES), portable);
                assertTrue(Files.isRegularFile(folder.root().resolve(portable)), portable);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------------- f2 portable

    @ParameterizedTest
    @ValueSource(strings = { "", "/abs", "/", "a/../b", "..", ".", "./a", "a/./b", "../a", "a//b", "a\\b", "\\a", "C:/x", "c:x", "x:", "x/", "a/", "dir/CON", "dir/con", "dir/aux.txt", "dir/AUX.tar.gz", "nul",
            "dir/Com1.txt", "dir/LPT3", "dir/PRN", "a\tb", "a\nb", "a\u0001b", "a\u007fb", "a\0b", "dir/trailing.", "dir/trailing ", "dir/ /x", "a/b.", ".. /x" })
    void aPathThatIsNotPortableIsRefusedAndNothingIsWritten(String fileName) throws IOException {
        try (BundleFolder folder = BundleFolder.open()) {
            refused(folder, ID, fileName, BYTES, "PATH_NOT_PORTABLE");
            assertEquals(0, filesIn(folder.root()));
            try (Stream<Path> entries = Files.list(folder.root())) {
                assertEquals(0, entries.count(), "no folder was made for a refused path either");
            }
            // The refusal did not use the path up: a portable file is still taken afterwards.
            assertEquals("ok.txt", folder.put(ID, "ok.txt", BYTES));
        }
    }

    @Test
    void anAbsentNameIsNotPortable() throws IOException {
        assertFalse(BundleFolder.portable(null));
        try (BundleFolder folder = BundleFolder.open()) {
            refused(folder, ID, null, BYTES, "PATH_NOT_PORTABLE");
            assertEquals(0, filesIn(folder.root()));
        }
    }

    @Test
    void portableSaysWhatThePortableFormIs() {
        for (String portable : new String[] { "a", "a/b", "a/b/c.json", "stage-bundle.json", "evidence/red.json", ".hidden", "a b", "dir/CONSOLE", "dir/aux1", "\uD55C\uAE00/\u00FC.txt" }) {
            assertTrue(BundleFolder.portable(portable), portable);
        }
        for (String notPortable : new String[] { "", "/a", "a/", "a//b", "a/../b", "..", ".", "a\\b", "C:/x", "a:b", "dir/NUL", "dir/lpt9.log", "a\tb", "a\u007fb", "x.", "x /y" }) {
            assertFalse(BundleFolder.portable(notPortable), notPortable);
        }
    }

    @Test
    void thePathIsBoundedInLength() {
        String limit = "a".repeat(4096);
        assertTrue(BundleFolder.portable(limit));
        assertFalse(BundleFolder.portable(limit + "a"));
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------------ f3 duplicate

    @Test
    void aPathUsedTwiceIsRefusedAndTheFirstFileIsNotOverwritten() throws IOException {
        try (BundleFolder folder = BundleFolder.open()) {
            folder.put(ID, "a/b.txt", BYTES);
            String second = "22222222-2222-4222-8222-222222222222";
            refused(folder, second, "a/b.txt", "other".getBytes(StandardCharsets.UTF_8), "DUPLICATE_PATH");
            assertArrayEquals(BYTES, Files.readAllBytes(folder.root().resolve("a").resolve("b.txt")));
            assertEquals(1, filesIn(folder.root()));
        }
    }

    @Test
    void aDifferenceOfLetterCaseOnlyIsTheSamePath() throws IOException {
        try (BundleFolder folder = BundleFolder.open()) {
            folder.put(ID, "Dir/File.TXT", BYTES);
            String second = "22222222-2222-4222-8222-222222222222";
            refused(folder, second, "dir/file.txt", BYTES, "DUPLICATE_PATH");
            refused(folder, second, "DIR/FILE.txt", BYTES, "DUPLICATE_PATH");
            assertEquals(1, filesIn(folder.root()));
            // A different name in the same folder is not a duplicate.
            assertEquals("Dir/File2.TXT", folder.put(second, "Dir/File2.TXT", BYTES));
        }
    }

    @Test
    void aPathThatIsAFileInOnePlaceAndAFolderInTheOtherIsRefusedBothWays() throws IOException {
        try (BundleFolder folder = BundleFolder.open()) {
            String second = "22222222-2222-4222-8222-222222222222";
            folder.put(ID, "a", BYTES);
            refused(folder, second, "a/b.txt", BYTES, "DUPLICATE_PATH");
            folder.put(second, "c/d.txt", BYTES);
            refused(folder, ID, "c", BYTES, "DUPLICATE_PATH");
            assertEquals(2, filesIn(folder.root()));
        }
    }

    // ---------------------------------------------------------------------------------------------------------------------------------------------------------- f4 size

    @Test
    void theFilesMayHoldMaxBytesTogetherAndOneByteMoreIsRefused() throws IOException {
        // One array of half the limit is written twice, so the folder holds exactly the limit; one more byte is over it.
        byte[] half = new byte[(int) (BundleFolder.MAX_BYTES / 2)];
        try (BundleFolder folder = BundleFolder.open()) {
            folder.put(ID, "a.bin", half);
            assertEquals("b.bin", folder.put("22222222-2222-4222-8222-222222222222", "b.bin", half), "exactly MAX_BYTES together is taken");
            String over = "33333333-3333-4333-8333-333333333333";
            refused(folder, over, "dir/c.txt", new byte[1], "TOO_LARGE");
            assertFalse(Files.exists(folder.root().resolve("dir")), "the file that is over the limit is not written, and no folder is made for it");
            assertEquals(2, filesIn(folder.root()));
        }
    }

    // --------------------------------------------------------------------------------------------------------------------------------------------------------- f5 escape

    @ParameterizedTest
    @ValueSource(strings = { "../", "a/../../", "a/b/../../../", "x/../../", "./../" })
    void aPathThatWouldLeaveTheFolderIsRefusedAndNothingIsWrittenOutsideIt(String prefix) throws IOException {
        String name = "escaped-" + UUID.randomUUID() + ".txt";
        try (BundleFolder folder = BundleFolder.open()) {
            refused(folder, ID, prefix + name, BYTES, "PATH_NOT_PORTABLE");
            Path parent = folder.root().getParent();
            assertFalse(Files.exists(parent.resolve(name)), "nothing was written next to the folder");
            assertFalse(Files.exists(folder.root().resolve(name)));
            assertEquals(0, filesIn(folder.root()));
        }
    }

    @Test
    void anAbsolutePathIsNeverWrittenWhereItNames() throws IOException {
        Path outside = Files.createTempDirectory("sennard-bundle-outside-");
        try (BundleFolder folder = BundleFolder.open()) {
            String absolute = outside.resolve("file.txt").toString().replace('\\', '/');
            refused(folder, ID, absolute, BYTES, "PATH_NOT_PORTABLE");
            assertFalse(Files.exists(outside.resolve("file.txt")));
            assertEquals(0, filesIn(folder.root()));
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    // --------------------------------------------------------------------------------------------------------------------------------------------------------- f6 close

    @Test
    void closingRemovesTheFolderAndEverythingInIt() throws IOException {
        Path root;
        try (BundleFolder folder = BundleFolder.open()) {
            root = folder.root();
            folder.put(ID, "a.txt", BYTES);
            folder.put("22222222-2222-4222-8222-222222222222", "deep/er/b.txt", BYTES);
            assertTrue(Files.exists(root.resolve("deep").resolve("er").resolve("b.txt")));
        }
        assertFalse(Files.exists(root), "the folder is gone");
    }

    @Test
    void closingAnEmptyFolderRemovesIt() throws IOException {
        BundleFolder folder = BundleFolder.open();
        Path root = folder.root();
        assertTrue(Files.isDirectory(root));
        folder.close();
        assertFalse(Files.exists(root));
    }

    @Test
    void aFolderWhoseCheckFailedIsRemovedToo() throws IOException {
        Path[] root = new Path[1];
        assertThrows(WorkflowFailure.class, () -> {
            try (BundleFolder folder = BundleFolder.open()) {
                root[0] = folder.root();
                folder.put(ID, "a.txt", BYTES);
                folder.put(ID, "../b.txt", BYTES);
            }
        });
        assertFalse(Files.exists(root[0]), "the folder is removed when the check's work ends in a refusal");
    }
}
