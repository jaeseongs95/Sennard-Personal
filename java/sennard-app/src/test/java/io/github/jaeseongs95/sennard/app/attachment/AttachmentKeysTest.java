// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.attachment;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

import io.github.jaeseongs95.sennard.domain.error.InputCode;
import io.github.jaeseongs95.sennard.domain.error.IntegrityCode;
import io.github.jaeseongs95.sennard.domain.error.IntegrityException;
import io.github.jaeseongs95.sennard.domain.error.InvalidInputException;
import io.github.jaeseongs95.sennard.domain.error.UnavailableCode;
import io.github.jaeseongs95.sennard.domain.error.UnavailableException;
import io.github.jaeseongs95.sennard.domain.secret.SecretValue;
import io.github.jaeseongs95.sennard.service.secret.PrivateStorage;
import io.github.jaeseongs95.sennard.service.secret.TestPrivateStorage;

/**
 * The key files of the attachments (design 4.3) and the file part of the key replacement, with real files and the real {@link SecureRandom}.
 *
 * <h2>Test cases (design test numbers of section 9)</h2>
 * <pre>
 * 14  k1  key file         a key file is named by its key id and holds exactly 32 bytes (256 bits); two keys differ
 * 14  k2  first start      the first start makes the key folder and the first key (primary) when the folder holds none, and leaves the keys that are there; a start whose active key is
 *                          not there fails with ARTIFACT_KEY_MISSING (naming the key) instead of the first upload; the start reads the active key and judges it like a request (edition 11):
 *                          a folder in its place is ARTIFACT_STORAGE_UNSAFE (nothing is made over it), an empty file or one that is not 256 bits is ARTIFACT_KEY_MISSING, a key of 256 bits passes
 * 14  k3  no replacing     a key that exists is never replaced: keygen of it is an input error (VALIDATION_FAILED, --id), not ARTIFACT_STORAGE_UNSAFE; an id that is no file name is the same
 * 14  k4  key ids          an id is letters, digits, underscore and hyphen up to 64 characters; a path or a dot is no id (ARTIFACT_KEY_MISSING, reason invalid)
 * 14  k5  a file of the wrong size is no key    ARTIFACT_KEY_MISSING, reason wrong-length, or empty for an empty file (edition 11); a folder where a key file belongs: reason key-not-a-file;
 *                          only the kind of the path and the size are judged, never who owns the key file or who may read it (edition 10)
 * 14  k6  key replacement, file part   a file written with key A is read after the active key is B; a new file is written with B and does not open with A; the file of A is not touched
 * 14  k7  key folder not set   ARTIFACT_STORAGE_UNSAFE, reason key-directory-not-set
 * </pre>
 */
class AttachmentKeysTest {
    @TempDir
    Path scratch;

    private final TestPrivateStorage storage = new TestPrivateStorage();

    private AttachmentKeys keysOf(AttachmentProperties properties) {
        return new AttachmentKeys(properties, new StorageFolders(storage), storage, new SecureRandom());
    }

    private AttachmentProperties properties(String activeKeyId) {
        return AttachmentTestKit.properties(scratch, activeKeyId, DataSize.ofMegabytes(64), AttachmentTestKit.NO_MARGIN);
    }

    @Test
    void aKeyFileHoldsExactly256BitsAndTwoKeysDiffer() throws IOException {
        AttachmentKeys keys = keysOf(properties("a"));
        new StorageFolders(storage).ensureOwnerOnlyFolder(properties("a").keyDirectory());

        keys.create("a");
        keys.create("b");

        Path file = properties("a").keyDirectory().resolve("a");
        assertEquals(32, Files.size(file), "AES-256: 256 random bits and nothing else");
        assertEquals(AttachmentKeys.KEY_BYTES, keys.key("a").length);
        assertEquals(32, keys.key("b").length);
        assertFalse(Arrays.equals(keys.key("a"), keys.key("b")), "two random keys");
        assertArrayEquals(Files.readAllBytes(file), keys.key("a"), "what is read is what the file holds");
        assertEquals(List.of("a", "b"), keys.ids());
    }

    @Test
    void theFirstStartMakesTheFolderAndThePrimaryKeyAndLeavesTheKeysThatAreThere() {
        AttachmentKeys keys = keysOf(properties(null));

        keys.provision();
        assertEquals(List.of("primary"), keys.ids());
        byte[] first = keys.key("primary");
        keys.provision();
        assertArrayEquals(first, keys.key("primary"), "a second start keeps the key");
        assertEquals("primary", keys.activeKeyId());

        AttachmentKeys more = keysOf(properties("primary"));
        more.create("second");
        more.provision();
        assertEquals(List.of("primary", "second"), more.ids(), "a folder that holds keys gets no new one");
    }

    @Test
    void aStartWhoseActiveKeyIsNotThereFailsAtTheStartNamingTheKey() {
        AttachmentKeys keys = keysOf(properties("not-made"));

        UnavailableException failure = assertThrows(UnavailableException.class, keys::provision);
        assertEquals(UnavailableCode.ARTIFACT_KEY_MISSING, failure.code());
        assertEquals("not-made", failure.details().get("keyId"));
        assertEquals(Boolean.FALSE, failure.details().get("retryable"), "the owner has to bring the key file back");
    }

    @Test
    void aStartWhoseActiveKeyFileIsAFolderIsRefusedAsUnsafeAndNothingIsMadeOverIt() throws IOException {
        AttachmentKeys keys = keysOf(properties("a"));
        new StorageFolders(storage).ensureOwnerOnlyFolder(properties("a").keyDirectory());
        keys.create("b"); // the folder holds a key already, so the start makes none
        Path place = properties("a").keyDirectory().resolve("a");
        Files.createDirectory(place);

        UnavailableException failure = assertThrows(UnavailableException.class, keys::provision);
        assertEquals(UnavailableCode.ARTIFACT_STORAGE_UNSAFE, failure.code(), "a folder where a key file belongs is not a regular file: the same judgement as a request's (edition 11)");
        assertEquals("key-not-a-file", failure.details().get("reason"));
        assertEquals(Boolean.FALSE, failure.details().get("retryable"));
        assertTrue(Files.isDirectory(place), "the folder is as it was");

        // the first key's place alone holds a folder: that is not a folder with no key, and no key is made over it
        Path first = properties(null).keyDirectory().resolve(AttachmentProperties.FIRST_KEY_ID);
        Files.delete(properties(null).keyDirectory().resolve("b"));
        Files.delete(place);
        Files.createDirectory(first);
        UnavailableException primary = assertThrows(UnavailableException.class, () -> keysOf(properties(null)).provision());
        assertEquals(UnavailableCode.ARTIFACT_STORAGE_UNSAFE, primary.code());
        assertEquals("key-not-a-file", primary.details().get("reason"));
        assertTrue(Files.isDirectory(first));
    }

    @Test
    void aStartWhoseActiveKeyFileIsEmptyOrNot256BitsIsRefusedAsAMissingKeyAndAKeyOf256BitsPasses() throws IOException {
        AttachmentKeys keys = keysOf(properties("a"));
        new StorageFolders(storage).ensureOwnerOnlyFolder(properties("a").keyDirectory());
        keys.create("b");
        Path place = properties("a").keyDirectory().resolve("a");

        Files.write(place, new byte[0]);
        UnavailableException empty = assertThrows(UnavailableException.class, keys::provision);
        assertEquals(UnavailableCode.ARTIFACT_KEY_MISSING, empty.code(), "an empty key file passes no start: it would only fail at the first request (edition 11)");
        assertEquals("empty", empty.details().get("reason"));
        assertEquals("a", empty.details().get("keyId"));
        assertEquals(Boolean.FALSE, empty.details().get("retryable"));

        Files.write(place, new byte[16]);
        UnavailableException shortKey = assertThrows(UnavailableException.class, keys::provision);
        assertEquals(UnavailableCode.ARTIFACT_KEY_MISSING, shortKey.code());
        assertEquals("wrong-length", shortKey.details().get("reason"));
        assertEquals("a", shortKey.details().get("keyId"));

        Files.write(place, new byte[AttachmentKeys.KEY_BYTES]);
        assertDoesNotThrow(keys::provision, "control: a file of 256 bits is a key");
    }

    @Test
    void theStartCheckOfOneKeyReadsItAndJudgesItLikeARequestAndKeepsNothing() throws IOException {
        AttachmentKeys keys = keysOf(properties(null));
        keys.provision();
        keys.create("a");
        Path file = properties(null).keyDirectory().resolve("a");

        keys.requireUsable("a");
        assertTrue(storage.calls().contains("readSecret " + file), "the key file is really read, not only looked for");

        Files.delete(file);
        assertEquals(UnavailableCode.ARTIFACT_KEY_MISSING, assertThrows(UnavailableException.class, () -> keys.requireUsable("a")).code());
        Files.createDirectory(file);
        assertEquals(UnavailableCode.ARTIFACT_STORAGE_UNSAFE, assertThrows(UnavailableException.class, () -> keys.requireUsable("a")).code());
        assertEquals(UnavailableCode.ARTIFACT_KEY_MISSING, assertThrows(UnavailableException.class, () -> keys.requireUsable("../outside")).code(), "an id that is no file name is no key");
    }

    @Test
    void aKeyFileIsMadeAndReadThroughThePort() {
        AttachmentKeys keys = keysOf(properties(null));
        Path file = properties(null).keyDirectory().resolve("a");

        keys.provision(); // the first key, primary, in a folder the port makes
        keys.create("a");
        keys.key("a");

        assertTrue(storage.calls().contains("createFolder " + properties(null).keyDirectory()), "the key folder is made by the port");
        assertTrue(storage.calls().contains("createFile " + file), "the key file is made by the port, private from its first instant");
        assertTrue(storage.calls().contains("readSecret " + file), "the key file is read by the port");
    }

    @Test
    void aKeyThatExistsIsNeverReplaced() {
        AttachmentKeys keys = keysOf(properties(null));
        keys.provision(); // the folder and primary
        keys.create("a");
        byte[] before = keys.key("a");

        InvalidInputException failure = assertThrows(InvalidInputException.class, () -> keys.create("a"));
        assertEquals(InputCode.VALIDATION_FAILED, failure.code(), "keygen of an id that exists is a misuse of the command, not a storage path that cannot be used");
        assertEquals(List.of("--id: the key folder already has a path of this id; a key is never replaced"), failure.details().get("violations"));
        assertEquals(Boolean.FALSE, failure.details().get("retryable"));
        assertArrayEquals(before, keys.key("a"), "the key is as it was");
    }

    @Test
    void keygenOfAnIdThatIsNoFileNameIsAnInputErrorAndMakesNothing() {
        AttachmentKeys keys = keysOf(properties(null));
        keys.provision();

        for (String bad : new String[] {"../outside", "a/b", "a.b", "", "A".repeat(65)}) {
            InvalidInputException failure = assertThrows(InvalidInputException.class, () -> keys.create(bad), bad);
            assertEquals(InputCode.VALIDATION_FAILED, failure.code(), bad);
            assertEquals(List.of("--id: a key id is letters, digits, underscore and hyphen, 1 to 64 characters"), failure.details().get("violations"), bad);
        }
        assertEquals(List.of("primary"), keys.ids(), "nothing was made");
    }

    @Test
    void aKeyIdIsAFileNameAndNeverAPath() {
        assertTrue(AttachmentKeys.validId("primary"));
        assertTrue(AttachmentKeys.validId("key_2026-11"));
        assertTrue(AttachmentKeys.validId("A".repeat(64)));
        assertFalse(AttachmentKeys.validId("A".repeat(65)));
        assertFalse(AttachmentKeys.validId(""));
        assertFalse(AttachmentKeys.validId(null));
        assertFalse(AttachmentKeys.validId("../outside"));
        assertFalse(AttachmentKeys.validId("a/b"));
        assertFalse(AttachmentKeys.validId("a.b"));
        assertFalse(AttachmentKeys.validId("a b"));

        AttachmentKeys keys = keysOf(properties(null));
        keys.provision();
        UnavailableException failure = assertThrows(UnavailableException.class, () -> keys.key("../outside"));
        assertEquals(UnavailableCode.ARTIFACT_KEY_MISSING, failure.code());
        assertEquals("invalid", failure.details().get("keyId"));
        assertEquals(List.of("primary"), keys.ids(), "a path is no key id: nothing was found or made outside the key folder");
    }

    @Test
    void aKeyFileOfAnotherSizeIsRefusedAndNeverUsedAsAKey() throws IOException {
        AttachmentKeys keys = keysOf(properties(null));
        keys.provision();
        Files.write(properties(null).keyDirectory().resolve("short"), new byte[16]);

        UnavailableException failure = assertThrows(UnavailableException.class, () -> keys.key("short"));
        assertEquals(UnavailableCode.ARTIFACT_KEY_MISSING, failure.code(), "a key of another length is no usable key (design edition 11)");
        assertEquals("wrong-length", failure.details().get("reason"));
        assertEquals("short", failure.details().get("keyId"));

        Files.write(properties(null).keyDirectory().resolve("empty"), new byte[0]);
        UnavailableException empty = assertThrows(UnavailableException.class, () -> keys.key("empty"));
        assertEquals(UnavailableCode.ARTIFACT_KEY_MISSING, empty.code());
        assertEquals("empty", empty.details().get("reason"), "an empty key file is not a key");
        assertEquals("empty", empty.details().get("keyId"));

        // The empty file is judged by the port's own code: a refusal to read a file that happens to be empty is the I/O error, not a missing key.
        PrivateStorage denied = new PrivateStorage() {
            @Override
            public void createFolder(Path folder) throws IOException {
                storage.createFolder(folder);
            }

            @Override
            public void createFile(Path file) throws IOException {
                storage.createFile(file);
            }

            @Override
            public void requireSafeFolder(Path folder) throws IOException {
                storage.requireSafeFolder(folder);
            }

            @Override
            public void requireTrustedFolder(Path folder) throws IOException {
                storage.requireTrustedFolder(folder);
            }

            @Override
            public SecretValue readSecret(Path file) throws IOException {
                throw new AccessDeniedException(file.toString());
            }
        };
        UnavailableException accessRefused = assertThrows(UnavailableException.class,
                () -> new AttachmentKeys(properties(null), new StorageFolders(denied), denied, new SecureRandom()).key("empty"));
        assertEquals(UnavailableCode.ARTIFACT_STORAGE_IO_ERROR, accessRefused.code(), "an empty file that cannot be read is not a missing key");
        assertInstanceOf(AccessDeniedException.class, accessRefused.getCause());

        Files.createDirectory(properties(null).keyDirectory().resolve("folder"));
        UnavailableException folder = assertThrows(UnavailableException.class, () -> keys.key("folder"));
        assertEquals(UnavailableCode.ARTIFACT_STORAGE_UNSAFE, folder.code());
        assertEquals("key-not-a-file", folder.details().get("reason"), "a folder where a key file belongs");
    }

    /** The value the port gave is an intermediate: the key the caller receives is a copy, and the value read from the file is overwritten once it has been judged. */
    @Test
    void theValueReadFromTheKeyFileIsDestroyedWhateverTheJudgmentIs() throws IOException {
        List<SecretValue> given = new java.util.ArrayList<>();
        PrivateStorage recording = new PrivateStorage() {
            @Override
            public void createFolder(Path folder) throws IOException {
                storage.createFolder(folder);
            }

            @Override
            public void createFile(Path file) throws IOException {
                storage.createFile(file);
            }

            @Override
            public void requireSafeFolder(Path folder) throws IOException {
                storage.requireSafeFolder(folder);
            }

            @Override
            public void requireTrustedFolder(Path folder) throws IOException {
                storage.requireTrustedFolder(folder);
            }

            @Override
            public SecretValue readSecret(Path file) throws IOException {
                SecretValue read = storage.readSecret(file);
                given.add(read);
                return read;
            }
        };
        AttachmentKeys keys = new AttachmentKeys(properties(null), new StorageFolders(recording), recording, new SecureRandom());
        keys.provision(); // the start check reads the active key too (requireUsable), so it is one of the reads that are counted below
        int readAtTheStart = given.size();
        assertTrue(readAtTheStart >= 1, "the start check reads the active key file");
        Files.write(properties(null).keyDirectory().resolve("short"), new byte[16]);

        byte[] key = keys.key("primary");
        assertEquals(AttachmentKeys.KEY_BYTES, key.length);
        assertThrows(UnavailableException.class, () -> keys.key("short"));

        assertEquals(readAtTheStart + 2, given.size(), "one read for each judgment");
        assertTrue(given.stream().allMatch(SecretValue::isDestroyed), "every value read from a key file is destroyed, at the start and in a request, the right size or not");
        assertEquals(AttachmentKeys.KEY_BYTES, keys.key("primary").length, "a later read makes a new value");
    }

    @Test
    void aKeyFolderThatOthersMayReadIsUsedAsItIsAndItsKeysRead() throws IOException {
        Path folder = Files.createDirectory(scratch.resolve("keys")); // made with the default access of the system, as an owner who made it by hand would have
        AttachmentKeys keys = keysOf(properties(null));

        keys.provision();

        assertEquals(List.of("primary"), keys.ids());
        assertEquals(32, keys.key("primary").length);
        assertTrue(Files.isDirectory(folder));
    }

    @Test
    void aKeyFolderThatIsNotSetIsRefused() {
        AttachmentProperties none = new AttachmentProperties(null, null, null, DataSize.ofMegabytes(64), DataSize.ofMegabytes(512), DataSize.ofGigabytes(10), 4, AttachmentTestKit.NO_MARGIN,
                Duration.ofMinutes(10), Duration.ofHours(24), Duration.ofMinutes(1), Duration.ofHours(24));
        AttachmentKeys keys = keysOf(none);

        UnavailableException failure = assertThrows(UnavailableException.class, keys::provision);
        assertEquals(UnavailableCode.ARTIFACT_STORAGE_UNSAFE, failure.code());
        assertEquals("key-directory-not-set", failure.details().get("reason"));
    }

    @Test
    void aFileWrittenWithKeyAIsReadAfterTheActiveKeyIsBAndANewFileIsWrittenWithB() {
        AttachmentTestKit withA = AttachmentTestKit.prepared(scratch, "A", "A", "B");
        byte[] chunk = withA.bytes(300);
        AttachmentTestKit.UploadFile old = withA.closedUploadOf(chunk);
        assertEquals("A", old.keyId());
        long oldSize = AttachmentTestKit.size(old.path());
        byte[] oldBytes = AttachmentTestKit.read(old.path());

        AttachmentTestKit withB = withA.withActiveKey("B");
        assertEquals("B", withB.keys().activeKeyId());
        AttachmentFileStore.Page page = withB.store().read(old.storageKey(), "A", 0, 1000, chunk.length);
        assertArrayEquals(chunk, page.data(), "the row's key id picks the key, not the active key");
        assertTrue(page.end());

        AttachmentTestKit.UploadFile fresh = withB.upload();
        assertEquals("B", fresh.keyId(), "a new upload is written with the active key");
        byte[] other = withB.bytes(200);
        fresh.append(other);
        fresh.close();
        assertArrayEquals(other, withB.store().read(fresh.storageKey(), "B", 0, 1000, other.length).data());
        IntegrityException wrongKey = assertThrows(IntegrityException.class, () -> withB.store().read(fresh.storageKey(), "A", 0, 1000, other.length));
        assertEquals(IntegrityCode.ARTIFACT_CORRUPTED, wrongKey.code(), "the file of B does not open with the key of A");
        assertEquals(oldSize, AttachmentTestKit.size(old.path()), "the file of A was not touched");
        assertArrayEquals(oldBytes, AttachmentTestKit.read(old.path()));
        assertNotEquals(old.storageKey(), fresh.storageKey());
    }
}
