// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.attachmentcli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jaeseongs95.sennard.domain.error.InputCode;
import io.github.jaeseongs95.sennard.domain.error.InvalidInputException;

/**
 * What {@code sennard attachments prepare} measures of a file ({@link PreparedFile}), without any server.
 *
 * <pre>
 * p1  measure     size, sha256 as {@code sha256:<hex>} of the bytes (a known vector, and a file larger than the read buffer against the JDK's own digest), the file's own name
 * p2  media type  a type the platform does not know is application/octet-stream; a known extension gives some {@code type/subtype} (which one is the platform's)
 * p3  name        a name given by the caller replaces the file's own name and nothing else changes
 * p4  empty       a file of no bytes has size 0 and the sha256 of no bytes
 * p5  refused     a directory and a file that is not there are InvalidInputException VALIDATION_FAILED whose violation starts with {@code <file>:}
 * p6  stable      measure of an unchanged file is the same each time, and it is what of() reports; a file changed between two measures gives another measure
 * </pre>
 *
 * A file that changes <em>while</em> it is read (the refusal "the file changed while it was read") is not tested here: the read of a file of a test's size is over before another writer
 * can be made to run inside it, and a stand-in for the file system would test the stand-in. That refusal is judged by reading the code only.
 */
class PreparedFileTest {
    private static final String ABC_SHA256 = "sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
    private static final String EMPTY_SHA256 = "sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private static final String UNKNOWN_TYPE = "application/octet-stream";
    /** An extension no platform knows. */
    private static final String UNKNOWN_EXTENSION = ".sennard-test-unknown";
    /** Larger than the read buffer of the measure (262144 bytes), and not a multiple of it. */
    private static final int LARGER_THAN_THE_BUFFER = 600_000;

    private static Path write(Path folder, String name, byte[] bytes) throws IOException {
        return Files.write(folder.resolve(name), bytes);
    }

    private static String sha256Of(byte[] bytes) throws NoSuchAlgorithmException {
        return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static byte[] bytesOf(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    @Test
    void aFileIsMeasuredByItsSizeItsSha256AndItsOwnName(@TempDir Path temp) throws IOException {
        Path file = write(temp, "notes" + UNKNOWN_EXTENSION, bytesOf("abc"));

        PreparedFile prepared = PreparedFile.of(file, null);

        assertEquals("notes" + UNKNOWN_EXTENSION, prepared.fileName());
        assertEquals(3, prepared.size());
        assertEquals(ABC_SHA256, prepared.sha256());
    }

    @Test
    void aFileLargerThanTheReadBufferIsMeasuredWhole(@TempDir Path temp) throws Exception {
        byte[] bytes = new byte[LARGER_THAN_THE_BUFFER];
        new Random(20261116L).nextBytes(bytes);
        Path file = write(temp, "large" + UNKNOWN_EXTENSION, bytes);

        PreparedFile prepared = PreparedFile.of(file, null);

        assertEquals(LARGER_THAN_THE_BUFFER, prepared.size());
        assertEquals(sha256Of(bytes), prepared.sha256());
    }

    @Test
    void aMediaTypeThePlatformDoesNotKnowIsTheUnknownTypeAndAKnownExtensionGivesATypeAndSubtype(@TempDir Path temp) throws IOException {
        assertEquals(UNKNOWN_TYPE, PreparedFile.of(write(temp, "a" + UNKNOWN_EXTENSION, bytesOf("abc")), null).mediaType());

        String known = PreparedFile.of(write(temp, "a.txt", bytesOf("abc")), null).mediaType();
        assertTrue(known.matches("[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]*/[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]*"), "a type/subtype, which one is the platform's: " + known);
    }

    @Test
    void aNameGivenByTheCallerReplacesTheFilesOwnNameAndNothingElse(@TempDir Path temp) throws IOException {
        Path file = write(temp, "notes" + UNKNOWN_EXTENSION, bytesOf("abc"));

        PreparedFile named = PreparedFile.of(file, "docs/part-1/notes.txt");
        PreparedFile own = PreparedFile.of(file, null);

        assertEquals("docs/part-1/notes.txt", named.fileName());
        assertEquals(own.size(), named.size());
        assertEquals(own.sha256(), named.sha256());
        assertEquals(own.mediaType(), named.mediaType(), "the media type is the file's, not the name's");
    }

    @Test
    void anEmptyFileHasNoBytesAndTheSha256OfNoBytes(@TempDir Path temp) throws IOException {
        Path file = write(temp, "empty" + UNKNOWN_EXTENSION, new byte[0]);

        PreparedFile prepared = PreparedFile.of(file, null);

        assertEquals(0, prepared.size());
        assertEquals(EMPTY_SHA256, prepared.sha256());
        assertEquals(new PreparedFile.Measure(0, EMPTY_SHA256), PreparedFile.measure(file));
    }

    private static void assertRefusedAsAFile(InvalidInputException refused) {
        assertEquals(InputCode.VALIDATION_FAILED, refused.code());
        List<?> violations = assertInstanceOf(List.class, refused.details().get("violations"), refused.details().toString());
        assertEquals(1, violations.size(), violations.toString());
        String violation = assertInstanceOf(String.class, violations.get(0));
        assertTrue(violation.startsWith("<file>:"), violation);
    }

    @Test
    void aDirectoryIsRefusedAsAFile(@TempDir Path temp) {
        assertRefusedAsAFile(assertThrows(InvalidInputException.class, () -> PreparedFile.of(temp, null)));
        assertRefusedAsAFile(assertThrows(InvalidInputException.class, () -> PreparedFile.measure(temp)));
    }

    @Test
    void aFileThatIsNotThereIsRefusedAsAFile(@TempDir Path temp) {
        Path missing = temp.resolve("missing.txt");

        assertRefusedAsAFile(assertThrows(InvalidInputException.class, () -> PreparedFile.of(missing, null)));
        assertRefusedAsAFile(assertThrows(InvalidInputException.class, () -> PreparedFile.measure(missing)));
    }

    @Test
    void theMeasureOfAnUnchangedFileIsTheSameEachTimeAndIsWhatPrepareReports(@TempDir Path temp) throws IOException {
        Path file = write(temp, "stable" + UNKNOWN_EXTENSION, bytesOf("abc"));

        PreparedFile.Measure first = PreparedFile.measure(file);

        assertEquals(first, PreparedFile.measure(file));
        assertEquals(first, PreparedFile.measure(file));
        PreparedFile prepared = PreparedFile.of(file, null);
        assertEquals(first, new PreparedFile.Measure(prepared.size(), prepared.sha256()));
    }

    @Test
    void aFileChangedBetweenTwoMeasuresGivesAnotherMeasure(@TempDir Path temp) throws IOException {
        Path file = write(temp, "changed" + UNKNOWN_EXTENSION, bytesOf("abc"));
        PreparedFile.Measure before = PreparedFile.measure(file);

        Files.write(file, bytesOf("abd"));
        PreparedFile.Measure sameSize = PreparedFile.measure(file);
        Files.write(file, bytesOf("abcd"));
        PreparedFile.Measure otherSize = PreparedFile.measure(file);

        assertEquals(before.size(), sameSize.size());
        assertNotEquals(before.sha256(), sameSize.sha256(), "the same size, other bytes: the sha256 tells");
        assertEquals(4, otherSize.size());
        assertNotEquals(before, otherSize);
    }
}
