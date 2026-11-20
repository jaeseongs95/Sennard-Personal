// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.attachment;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.util.unit.DataSize;

import io.github.jaeseongs95.sennard.domain.Digests;
import io.github.jaeseongs95.sennard.service.secret.TestPrivateStorage;

/**
 * What the file-level tests of the attachments share: the file store, its key folder and its cipher made the way the application makes them (the constructors the context calls),
 * over folders in the test's own temporary folder, with the real {@link SecureRandom}. Nothing here is a mock: a test that uses it writes, locks, reads and tampers with real files.
 *
 * <p>Instances are made per test and hold no static state.
 */
final class AttachmentTestKit {
    /** No margin the disk must keep: a test of the margin sets its own. */
    static final DataSize NO_MARGIN = DataSize.ofBytes(0);
    /** The name of the first key, which the first start makes. */
    static final String FIRST_KEY = AttachmentProperties.FIRST_KEY_ID;

    private final Path scratch;
    private final AttachmentProperties properties;
    private final TestPrivateStorage storage = new TestPrivateStorage();
    private final StorageFolders folders = new StorageFolders(storage);
    private final SecureRandom random = new SecureRandom();
    private final SegmentCipher cipher = new SegmentCipher(random);
    private final AttachmentKeys keys;
    private final AttachmentFileStore store;

    private AttachmentTestKit(Path scratch, AttachmentProperties properties) {
        this.scratch = scratch;
        this.properties = properties;
        this.keys = new AttachmentKeys(properties, folders, storage, random);
        this.store = new AttachmentFileStore(properties, folders, keys, cipher, random);
    }

    /** The settings of a test: the design's defaults (3.1) except the margin, which is none, and the active key. */
    static AttachmentProperties properties(Path scratch, String activeKeyId, DataSize maxFileBytes, DataSize diskMargin) {
        return new AttachmentProperties(scratch.resolve("attachments"), scratch.resolve("keys"), activeKeyId, maxFileBytes, DataSize.ofMegabytes(512), DataSize.ofGigabytes(10), 4, diskMargin,
                Duration.ofMinutes(10), Duration.ofHours(24), Duration.ofMinutes(1), Duration.ofHours(24));
    }

    /** A kit with the first key made and the folders prepared. */
    static AttachmentTestKit prepared(Path scratch) {
        return prepared(scratch, FIRST_KEY, FIRST_KEY);
    }

    /** A kit whose active key is {@code activeKeyId}, with the key files {@code keyIds} made (the active key among them) and the folders prepared. */
    static AttachmentTestKit prepared(Path scratch, String activeKeyId, String... keyIds) {
        return prepared(properties(scratch, activeKeyId, DataSize.ofMegabytes(64), NO_MARGIN), scratch, keyIds);
    }

    /** A kit over these settings, with the key files made and the folders prepared. */
    static AttachmentTestKit prepared(AttachmentProperties properties, Path scratch, String... keyIds) {
        AttachmentTestKit kit = new AttachmentTestKit(scratch, properties);
        kit.folders.ensureOwnerOnlyFolder(properties.keyDirectory());
        for (String keyId : keyIds) {
            kit.keys.create(keyId);
        }
        kit.store.prepare();
        return kit;
    }

    /** The same folders and key files read by another kit whose active key is {@code activeKeyId}: the application after the setting was changed. */
    AttachmentTestKit withActiveKey(String activeKeyId) {
        AttachmentProperties current = properties;
        AttachmentProperties changed = new AttachmentProperties(current.directory(), current.keyDirectory(), activeKeyId, current.maxFileBytes(), current.stagingBudgetBytes(),
                current.attachmentBudgetBytes(), current.maxOpenPerOwner(), current.diskMarginBytes(), current.uploadTtl(), current.orphanGrace(), current.reaperInterval(),
                current.reconcileInterval());
        return new AttachmentTestKit(scratch, changed);
    }

    AttachmentProperties properties() {
        return properties;
    }

    StorageFolders folders() {
        return folders;
    }

    AttachmentKeys keys() {
        return keys;
    }

    SegmentCipher cipher() {
        return cipher;
    }

    AttachmentFileStore store() {
        return store;
    }

    SecureRandom random() {
        return random;
    }

    Path scratch() {
        return scratch;
    }

    byte[] bytes(int length) {
        byte[] bytes = new byte[length];
        random.nextBytes(bytes);
        return bytes;
    }

    static String sha(byte[] bytes) {
        return Digests.sha256Hex(bytes);
    }

    Path fileOf(String storageKey) {
        return store.pathOf(storageKey);
    }

    // ------------------------------------------------------------------------------------------------------------ an upload as the file store sees it

    /** An upload in progress as the entrance sees it: the facts of its row, kept by the test, and the plain bytes appended so far. */
    final class UploadFile {
        private final String storageKey;
        private final String keyId;
        private final ByteArrayOutputStream plain = new ByteArrayOutputStream();
        private long stored;
        private long next;

        private UploadFile(String storageKey, String keyId) {
            this.storageKey = storageKey;
            this.keyId = keyId;
        }

        String storageKey() {
            return storageKey;
        }

        String keyId() {
            return keyId;
        }

        /** How long the file is according to the row. */
        long stored() {
            return stored;
        }

        /** The number of the next chunk: how many chunks the row has taken in. */
        long next() {
            return next;
        }

        byte[] plain() {
            return plain.toByteArray();
        }

        String sha256() {
            return sha(plain());
        }

        Path path() {
            return fileOf(storageKey);
        }

        /** Appends a chunk and takes it into the facts, as a chunk request whose transaction succeeded does. */
        UploadFile append(byte[] chunk) {
            stored = store.append(storageKey, keyId, next, stored, chunk, sha(chunk));
            plain.writeBytes(chunk);
            next++;
            return this;
        }

        /** Appends a chunk without taking it into the facts: the tail a lost transaction leaves. */
        long appendLost(byte[] chunk) {
            return store.append(storageKey, keyId, next, stored, chunk, sha(chunk));
        }

        /** The finish of the file as declared by the bytes it holds. */
        AttachmentFileStore.Closed close() {
            return store.close(storageKey, keyId, stored, next, plain.size(), sha256());
        }

        AttachmentFileStore.Closed close(long declaredSize, String declaredSha256) {
            return store.close(storageKey, keyId, stored, next, declaredSize, declaredSha256);
        }
    }

    /** A new upload with a new storage key, written with the active key. */
    UploadFile upload() {
        return new UploadFile(store.newStorageKey(), keys.activeKeyId());
    }

    /** An upload of these chunks, appended, not closed. */
    UploadFile uploadOf(byte[]... chunks) {
        UploadFile upload = upload();
        for (byte[] chunk : chunks) {
            upload.append(chunk);
        }
        return upload;
    }

    /** An upload of these chunks, appended and closed. */
    UploadFile closedUploadOf(byte[]... chunks) {
        UploadFile upload = uploadOf(chunks);
        upload.close();
        return upload;
    }

    // ------------------------------------------------------------------------------------------------------------ files

    /**
     * Puts other bytes in the place of a file: the file is moved out of the way and a new one is made at its path. A closed file is read-only, so it is not written in place (a
     * read-only file cannot be opened for writing on Windows); the effect on the reader is the same as a changed disk.
     */
    void replace(Path file, byte[] bytes) {
        try {
            Path aside = Files.createTempFile(scratch, "moved", ".bin");
            Files.move(file, aside, StandardCopyOption.REPLACE_EXISTING);
            Files.write(file, bytes, StandardOpenOption.CREATE_NEW);
        } catch (IOException cause) {
            throw new UncheckedIOException(cause);
        }
    }

    /** Moves a file out of the folder, so that its place is empty. */
    void remove(Path file) {
        try {
            Path aside = Files.createTempFile(scratch, "removed", ".bin");
            Files.move(file, aside, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException cause) {
            throw new UncheckedIOException(cause);
        }
    }

    static byte[] read(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException cause) {
            throw new UncheckedIOException(cause);
        }
    }

    static long size(Path file) {
        try {
            return Files.size(file);
        } catch (IOException cause) {
            throw new UncheckedIOException(cause);
        }
    }

    /** The segments of a file's bytes, each with its length prefix. */
    static List<byte[]> segments(byte[] file) {
        ByteBuffer buffer = ByteBuffer.wrap(file);
        List<byte[]> segments = new ArrayList<>();
        while (buffer.hasRemaining()) {
            int length = buffer.getInt(buffer.position());
            byte[] segment = new byte[SegmentCipher.LENGTH_BYTES + length];
            buffer.get(segment);
            segments.add(segment);
        }
        return segments;
    }

    static byte[] join(List<byte[]> segments) {
        ByteArrayOutputStream joined = new ByteArrayOutputStream();
        for (byte[] segment : segments) {
            joined.writeBytes(segment);
        }
        return joined.toByteArray();
    }

    static byte[] concat(byte[] first, byte[] second) {
        ByteArrayOutputStream joined = new ByteArrayOutputStream();
        joined.writeBytes(first);
        joined.writeBytes(second);
        return joined.toByteArray();
    }

    /** The bytes with the byte at {@code index} changed. */
    static byte[] flipped(byte[] bytes, int index) {
        byte[] changed = bytes.clone();
        changed[index] = (byte) (changed[index] ^ 0x01);
        return changed;
    }

    /** The first {@code length} bytes. */
    static byte[] head(byte[] bytes, int length) {
        return java.util.Arrays.copyOf(bytes, length);
    }

    /** Whether the needle occurs in the haystack. */
    static boolean contains(byte[] haystack, byte[] needle) {
        for (int start = 0; start + needle.length <= haystack.length; start++) {
            int at = 0;
            while (at < needle.length && haystack[start + at] == needle[at]) {
                at++;
            }
            if (at == needle.length) {
                return true;
            }
        }
        return false;
    }
}
