// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.attachment;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.domain.Digests;

/**
 * The bodies of the attachments, as files (the design's 4.3 and 4.4): the one implementation of writing, reading, closing, moving and deleting them, for uploads and for their
 * re-encryption. It knows no row; a use case hands it the facts of the row (storage key, key id, the bytes stored so far) and it answers what the disk says.
 *
 * <p>A file is a run of {@link SegmentCipher segments}. A chunk is appended while a lock is held on the one byte beyond the end of the file ({@code tryLock(Long.MAX_VALUE - 1, 1, false)}),
 * so those who append exclude each other, a reader of the data (a read, a status) is not excluded, and Windows, which stops a read of a locked range, has nothing locked
 * in the data. The lock is tried once, never waited for: ARTIFACT_BUSY. After a lock the file size is compared with what the row has taken in: the same size appends; a longer file has a tail that an
 * earlier request appended and the row has not yet taken in (a lost transaction), which is the same chunk or a conflict; a shorter file is corrupted.
 *
 * <p>Nothing here opens a transaction. A lost write is found by the sizes and the tags, so no part of it is retried.
 */
@Component
public class AttachmentFileStore {
    /** The size of a chunk: a constant of the contract, not a setting (the design's 3.1), so the chunk of a client of any product fits. */
    public static final int MAX_CHUNK_BYTES = 262144;
    /** The quarantine folder, beside the 256 folders of the file names. */
    static final String ORPHANS = "orphans";
    private static final Logger LOG = LoggerFactory.getLogger(AttachmentFileStore.class);
    private static final Pattern STORAGE_KEY = Pattern.compile("[0-9a-f]{32}");

    /** A file the server wrote whole (the re-encrypted file of an attachment), not yet a row. */
    public record Written(String storageKey, String keyId, long size, String sha256) {
    }

    /** What a finish found in a file and closed: how long the file is now, how many plain bytes it holds and their sha256 (lower-case hex). */
    public record Closed(long storedBytes, long plainBytes, String sha256) {
    }

    /** One page of a read: the plain bytes from {@code offset}, the plain size of the whole file, and whether the page reaches its end (the end mark was checked). */
    public record Page(byte[] data, long offset, long size, boolean end) {
    }

    private final AttachmentProperties properties;
    private final StorageFolders folders;
    private final AttachmentKeys keys;
    private final SegmentCipher cipher;
    private final SecureRandom random;
    private AttachmentSettings settings;

    public AttachmentFileStore(AttachmentProperties properties, StorageFolders folders, AttachmentKeys keys, SegmentCipher cipher, SecureRandom random) {
        this.properties = properties;
        this.folders = folders;
        this.keys = keys;
        this.cipher = cipher;
        this.random = random;
        this.settings = new AttachmentSettings(properties);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AttachmentFileStore(AttachmentProperties properties, StorageFolders folders, AttachmentKeys keys, SegmentCipher cipher, SecureRandom random, AttachmentSettings settings) {
        this(properties, folders, keys, cipher, random);
        this.settings = settings;
    }

    // ------------------------------------------------------------------------------------------------------------ places

    private Path root() {
        Path root = settings.current().directory();
        if (root == null) {
            throw ArtifactErrors.storageUnsafe("directory-not-set");
        }
        return root;
    }

    /** Validates and prepares the selected directory before a write, never moving existing bodies. */
    public void prepareDirectory(Path directory) {
        Path root = directory.toAbsolutePath().normalize();
        Path keysRoot = properties.keyDirectory().toAbsolutePath().normalize();
        try {
            Path realRoot = resolved(root);
            Path realKeys = resolved(keysRoot);
            if (realKeys.startsWith(realRoot) || realRoot.startsWith(realKeys)) {
                throw ArtifactErrors.storageUnsafe("key-directory-overlap");
            }
        } catch (IOException cause) { throw ArtifactErrors.storageIo(cause); }
        folders.ensureOwnerOnlyFolder(root);
        folders.ensureSubfolder(root.resolve(ORPHANS));
    }

    private static Path resolved(Path path) throws IOException {
        if (Files.notExists(path)) return resolved(path.getParent()).resolve(path.getFileName());
        return path.toRealPath();
    }

    /** Makes the attachment folder (and the quarantine) private to its owner when they are made; a folder that is there is used as it is, and only a path that is not a folder is refused. */
    public void prepare() {
        prepareDirectory(root());
    }

    /** A new random storage key: 128 bits from the process's {@link SecureRandom}, in lower-case hex. It holds no extension, date, original name, id or project. */
    public String newStorageKey() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static void requireKey(String storageKey) {
        if (!STORAGE_KEY.matcher(storageKey).matches()) {
            throw ArtifactErrors.storageUnsafe("storage-key");
        }
    }

    Path pathOf(String storageKey) {
        requireKey(storageKey);
        return locate(storageKey, false);
    }

    private Path quarantined(String storageKey) {
        requireKey(storageKey);
        return locate(storageKey, true);
    }

    private Path locate(String storageKey, boolean quarantine) {
        AttachmentSettings.Snapshot policy = settings.current();
        Path found = null;
        for (Path root : policy.directories()) {
            Path candidate = quarantine ? root.resolve(ORPHANS).resolve(storageKey) : root.resolve(storageKey.substring(0, 2)).resolve(storageKey);
            if (Files.exists(candidate)) {
                if (found != null && !found.equals(candidate)) throw ArtifactErrors.storageUnsafe("duplicate-storage-key");
                found = candidate;
            }
        }
        if (found != null) return found;
        Path root = policy.directory();
        if (root == null) throw ArtifactErrors.storageUnsafe("directory-not-set");
        return quarantine ? root.resolve(ORPHANS).resolve(storageKey) : root.resolve(storageKey.substring(0, 2)).resolve(storageKey);
    }

    public boolean exists(String storageKey) {
        return Files.isRegularFile(pathOf(storageKey));
    }

    /** The size of the file in bytes, or -1 when it is not there. */
    public long sizeOf(String storageKey) {
        try {
            return Files.size(pathOf(storageKey));
        } catch (NoSuchFileException missing) {
            LOG.debug("The file {} is not there.", storageKey, missing);
            return -1;
        } catch (IOException cause) {
            throw ArtifactErrors.storageIo(cause);
        }
    }

    /** When the file was last written. */
    public Instant modifiedAt(String storageKey) {
        try {
            return Files.getLastModifiedTime(pathOf(storageKey)).toInstant();
        } catch (IOException cause) {
            throw ArtifactErrors.storageIo(cause);
        }
    }

    /** Refuses a write that would leave the disk with less than {@code disk-margin-bytes}: ARTIFACT_STORAGE_FULL. A disk that cannot be read is an I/O failure, not this. */
    public void requireSpace(long bytes) {
        requireSpace(bytes, root());
    }

    private void requireSpace(long bytes, Path directory) {
        try {
            AttachmentSettings.Snapshot policy = settings.current();
            prepareDirectory(directory);
            long usable = Files.getFileStore(directory).getUsableSpace();
            long margin = policy.diskMarginBytes();
            if (usable - bytes < margin) {
                throw ArtifactErrors.storageFull(bytes, usable, margin);
            }
        } catch (IOException cause) {
            throw ArtifactErrors.storageIo(cause);
        }
    }

    // ------------------------------------------------------------------------------------------------------------ append

    /**
     * Appends one chunk to the file of an upload, or recognises that it is already there (the design's 4.4, steps 2 and 3).
     *
     * @param number the number of the chunk, which is the number of its segment
     * @param storedBytes how long the file is according to the row
     * @param plainSha256 the sha256 of the chunk, lower-case hex: how the tail of an earlier request is told to be this chunk
     * @return how long the file is now, tail included: what the row takes in
     * @throws io.github.jaeseongs95.sennard.domain.error.ConflictException ARTIFACT_BUSY when another append holds the lock, ARTIFACT_CHUNK_CONFLICT when the tail is another chunk
     * @throws io.github.jaeseongs95.sennard.domain.error.IntegrityException ARTIFACT_CORRUPTED when the file is shorter than the row says
     */
    public long append(String storageKey, String keyId, long number, long storedBytes, byte[] plain, String plainSha256) {
        Path file = pathOf(storageKey);
        byte[] key = keys.key(keyId);
        try {
            if (storedBytes == 0 && !Files.exists(file)) {
                create(file);
            }
            try (FileChannel channel = open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                holdAppendLock(channel);
                long size = channel.size();
                if (size == storedBytes) {
                    byte[] segment = cipher.seal(key, storageKey, number, false, plain);
                    requireSpace(segment.length, file.getParent().getParent());
                    write(channel, size, segment);
                    channel.force(true);
                    return size + segment.length;
                }
                if (size < storedBytes) {
                    throw ArtifactErrors.corrupted("file-short");
                }
                return takeInTail(channel, key, storageKey, number, storedBytes, size, plainSha256);
            }
        } catch (IOException cause) {
            throw ArtifactErrors.storageIo(cause);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    /** The tail [storedBytes, size) is one segment: the same chunk when it opens at this place and its plain bytes have this hash, else another chunk (a conflict). Nothing is cut off. */
    private long takeInTail(FileChannel channel, byte[] key, String storageKey, long number, long storedBytes, long size, String plainSha256) throws IOException {
        long tailLength = size - storedBytes;
        if (tailLength > MAX_CHUNK_BYTES + SegmentCipher.OVERHEAD) {
            throw ArtifactErrors.chunkConflict("tail");
        }
        byte[] tail = readFully(channel, storedBytes, (int) tailLength);
        ByteBuffer parsed = ByteBuffer.wrap(tail);
        if (tail.length < SegmentCipher.LENGTH_BYTES || parsed.getInt() != tail.length - SegmentCipher.LENGTH_BYTES) {
            throw ArtifactErrors.chunkConflict("tail");
        }
        byte[] plain;
        try {
            plain = cipher.open(key, storageKey, number, false, Arrays.copyOfRange(tail, SegmentCipher.LENGTH_BYTES, tail.length));
        } catch (io.github.jaeseongs95.sennard.domain.error.IntegrityException notThisChunk) {
            LOG.debug("The tail of {} is not the segment of chunk {}", storageKey, number, notThisChunk);
            throw ArtifactErrors.chunkConflict("tail");
        }
        if (!sha256(plain).equals(plainSha256)) {
            throw ArtifactErrors.chunkConflict("tail");
        }
        return size;
    }

    private void create(Path file) throws IOException {
        prepareDirectory(file.getParent().getParent());
        folders.ensureSubfolder(file.getParent());
        try {
            folders.createOwnerOnlyFile(file);
        } catch (FileAlreadyExistsException made) {
            // Another request of the same upload made the file first; the lock below decides who appends.
            LOG.debug("The file {} was made by another request first.", file.getFileName(), made);
        }
    }

    // ------------------------------------------------------------------------------------------------------------ finish

    /**
     * Checks a whole file and closes it (the design's 4.4, finish): the file is read segment by segment, every tag verified, and the sha256 and the size of the plain bytes compared
     * with what the upload declared. Only when they are right is the end mark appended, and then the file is made read-only. A wrong file is not closed, so the upload can go on.
     * A file that already has the end mark (an earlier finish appended it and the transaction was lost) is checked up to it and not appended to again.
     *
     * @param storedBytes how long the file is according to the row; {@code nextSequence} how many chunks it holds, which is the number of the end mark
     * @throws io.github.jaeseongs95.sennard.domain.error.ConflictException ARTIFACT_INCOMPLETE when a tail that is not the end mark is there or the bytes are fewer than declared;
     *     ARTIFACT_BUSY when an append holds the lock
     * @throws io.github.jaeseongs95.sennard.domain.error.IntegrityException ARTIFACT_HASH_MISMATCH when the sha256 or the size is not the declared one
     */
    public Closed close(String storageKey, String keyId, long storedBytes, long nextSequence, long declaredSize, String declaredSha256) {
        Path file = pathOf(storageKey);
        byte[] key = keys.key(keyId);
        try {
            // A finish whose transaction was lost closed the file (read-only) after it added the end mark; the finish that is sent again opens it for writing once more to check it.
            if (Files.exists(file)) {
                makeWritable(file);
            } else if (storedBytes == 0) {
                // No chunk was ever sent (the file is made by the first one): the upload holds no bytes, which is an empty file to check against what was declared.
                create(file);
            }
            long storedAfter;
            String sha256;
            long plainBytes;
            try (FileChannel channel = open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                holdAppendLock(channel);
                long size = channel.size();
                if (size < storedBytes) {
                    throw ArtifactErrors.corrupted("file-short");
                }
                boolean endMarked = size > storedBytes;
                if (endMarked) {
                    requireEndMark(channel, key, storageKey, nextSequence, storedBytes, size);
                }
                MessageDigest digest = Digests.newSha256();
                long[] counted = scan(channel, key, storageKey, storedBytes, digest);
                if (counted[1] != nextSequence) {
                    throw ArtifactErrors.corrupted("segment-count");
                }
                plainBytes = counted[0];
                sha256 = HexFormat.of().formatHex(digest.digest());
                if (plainBytes < declaredSize) {
                    throw ArtifactErrors.incomplete("bytes-missing");
                }
                if (plainBytes > declaredSize) {
                    throw ArtifactErrors.hashMismatch("size");
                }
                if (!sha256.equals(declaredSha256)) {
                    throw ArtifactErrors.hashMismatch("sha256");
                }
                storedAfter = size;
                if (!endMarked) {
                    byte[] mark = cipher.seal(key, storageKey, nextSequence, true, new byte[0]);
                    requireSpace(mark.length, file.getParent().getParent());
                    write(channel, size, mark);
                    channel.force(true);
                    storedAfter = size + mark.length;
                }
            }
            makeReadOnly(file);
            return new Closed(storedAfter, plainBytes, sha256);
        } catch (IOException cause) {
            throw ArtifactErrors.storageIo(cause);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    /** The tail [storedBytes, size) must be the end mark: one segment of no plain bytes, marked last, at the number of the next chunk. */
    private void requireEndMark(FileChannel channel, byte[] key, String storageKey, long number, long storedBytes, long size) throws IOException {
        long tailLength = size - storedBytes;
        if (tailLength != SegmentCipher.OVERHEAD) {
            throw ArtifactErrors.incomplete("tail");
        }
        byte[] tail = readFully(channel, storedBytes, (int) tailLength);
        ByteBuffer parsed = ByteBuffer.wrap(tail);
        if (parsed.getInt() != tail.length - SegmentCipher.LENGTH_BYTES) {
            throw ArtifactErrors.incomplete("tail");
        }
        byte[] plain;
        try {
            plain = cipher.open(key, storageKey, number, true, Arrays.copyOfRange(tail, SegmentCipher.LENGTH_BYTES, tail.length));
        } catch (io.github.jaeseongs95.sennard.domain.error.IntegrityException notTheEnd) {
            LOG.debug("The tail of {} is not the end mark", storageKey, notTheEnd);
            throw ArtifactErrors.incomplete("tail");
        }
        if (plain.length != 0) {
            throw ArtifactErrors.incomplete("tail");
        }
    }

    /**
     * Reads the segments of [0, limit) (none of them the last), verifies every tag, and feeds the plain bytes to the digest.
     *
     * @return {the number of plain bytes, the number of segments}
     */
    private long[] scan(FileChannel channel, byte[] key, String storageKey, long limit, MessageDigest digest) throws IOException {
        long position = 0;
        long number = 0;
        long plainBytes = 0;
        while (position < limit) {
            byte[] body = readSegment(channel, position, limit);
            byte[] plain = cipher.open(key, storageKey, number, false, body);
            digest.update(plain);
            plainBytes += plain.length;
            position += SegmentCipher.LENGTH_BYTES + body.length;
            number++;
        }
        if (position != limit) {
            throw ArtifactErrors.corrupted("segment-boundary");
        }
        return new long[] {plainBytes, number};
    }

    /** The body (without the length prefix) of the segment at the position, which must lie wholly before the limit. */
    private static byte[] readSegment(FileChannel channel, long position, long limit) throws IOException {
        if (position + SegmentCipher.LENGTH_BYTES > limit) {
            throw ArtifactErrors.corrupted("segment-boundary");
        }
        int length = ByteBuffer.wrap(readFully(channel, position, SegmentCipher.LENGTH_BYTES)).getInt();
        if (length < SegmentCipher.IV_BYTES + SegmentCipher.TAG_BYTES || length > MAX_CHUNK_BYTES + SegmentCipher.IV_BYTES + SegmentCipher.TAG_BYTES
                || position + SegmentCipher.LENGTH_BYTES + length > limit) {
            throw ArtifactErrors.corrupted("segment-length");
        }
        return readFully(channel, position + SegmentCipher.LENGTH_BYTES, length);
    }

    // ------------------------------------------------------------------------------------------------------------ read

    /**
     * One page of a closed file: the segments it touches are opened (their tags verified), and when the page reaches the end of the file the whole file is walked to its end mark
     * and its plain size compared with {@code expectedSize}. It takes no lock: it reads the data range, which an append does not touch. The sha256 of the whole file is checked by the
     * finish and by {@code attachments check}, not by every page (a segment's tag already names its place).
     *
     * @throws io.github.jaeseongs95.sennard.domain.error.IntegrityException ARTIFACT_CORRUPTED for a changed byte, an order, a cut end or another file's segment
     */
    public Page read(String storageKey, String keyId, long offsetBytes, int maxBytes, long expectedSize) {
        Path file = pathOf(storageKey);
        byte[] key = keys.key(keyId);
        try (FileChannel channel = open(file, StandardOpenOption.READ)) {
            long length = channel.size();
            if (length == 0) {
                throw ArtifactErrors.corrupted("end-mark-missing");
            }
            long wanted = Math.max(0, Math.min(maxBytes, expectedSize - offsetBytes));
            long pageEnd = offsetBytes + maxBytes;
            boolean reachesEnd = pageEnd >= expectedSize;
            byte[] page = new byte[(int) wanted];
            long position = 0;
            long number = 0;
            long plainStart = 0;
            while (position < length) {
                if (!reachesEnd && plainStart >= pageEnd) {
                    break;
                }
                byte[] body = readSegment(channel, position, length);
                boolean last = position + SegmentCipher.LENGTH_BYTES + body.length == length;
                long plainLength = SegmentCipher.plainLength(body.length);
                if (last) {
                    if (plainLength != 0 || plainStart != expectedSize) {
                        throw ArtifactErrors.corrupted(plainLength != 0 ? "end-mark" : "size");
                    }
                    cipher.open(key, storageKey, number, true, body);
                } else if (plainStart + plainLength > offsetBytes && plainStart < pageEnd) {
                    byte[] plain = cipher.open(key, storageKey, number, false, body);
                    long from = Math.max(offsetBytes, plainStart);
                    long to = Math.min(Math.min(pageEnd, plainStart + plain.length), expectedSize);
                    if (to > from) {
                        System.arraycopy(plain, (int) (from - plainStart), page, (int) (from - offsetBytes), (int) (to - from));
                    }
                }
                position += SegmentCipher.LENGTH_BYTES + body.length;
                plainStart += plainLength;
                number++;
                if (last) {
                    return new Page(page, offsetBytes, expectedSize, true);
                }
            }
            if (reachesEnd) {
                throw ArtifactErrors.corrupted("end-mark-missing");
            }
            return new Page(page, offsetBytes, expectedSize, false);
        } catch (NoSuchFileException missing) {
            LOG.debug("The file {} is not there.", storageKey, missing);
            throw ArtifactErrors.corrupted("file-missing");
        } catch (IOException cause) {
            throw ArtifactErrors.storageIo(cause);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    /**
     * Walks a closed file to its end and returns what it holds, for the check that compares a file with its row: every tag verified, the end mark present, the plain size and the
     * sha256 computed. An {@code IntegrityException} says what is wrong; none says the file is whole.
     */
    public Closed verify(String storageKey, String keyId, long expectedSize, String expectedSha256) {
        Path file = pathOf(storageKey);
        byte[] key = keys.key(keyId);
        try (FileChannel channel = open(file, StandardOpenOption.READ)) {
            long length = channel.size();
            MessageDigest digest = Digests.newSha256();
            long position = 0;
            long number = 0;
            long plainBytes = 0;
            while (position < length) {
                byte[] body = readSegment(channel, position, length);
                boolean last = position + SegmentCipher.LENGTH_BYTES + body.length == length;
                byte[] plain = cipher.open(key, storageKey, number, last, body);
                if (last) {
                    if (plain.length != 0) {
                        throw ArtifactErrors.corrupted("end-mark");
                    }
                } else {
                    digest.update(plain);
                    plainBytes += plain.length;
                }
                position += SegmentCipher.LENGTH_BYTES + body.length;
                number++;
            }
            if (length == 0) {
                throw ArtifactErrors.corrupted("end-mark-missing");
            }
            String sha256 = HexFormat.of().formatHex(digest.digest());
            if (plainBytes != expectedSize || !sha256.equals(expectedSha256)) {
                throw ArtifactErrors.corrupted(plainBytes != expectedSize ? "size" : "sha256");
            }
            return new Closed(length, plainBytes, sha256);
        } catch (NoSuchFileException missing) {
            LOG.debug("The file {} is not there.", storageKey, missing);
            throw ArtifactErrors.corrupted("file-missing");
        } catch (IOException cause) {
            throw ArtifactErrors.storageIo(cause);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    // ------------------------------------------------------------------------------------------------------------ re-encryption

    /**
     * Writes the closed file of an attachment again under the active key (the design's 4.3): every segment of the old file is opened with its own key (the tags and the end mark
     * verified, the size and sha256 compared with the attachment's), and the same plain segments are sealed under the active key, in a file of a new storage key, which is forced to
     * disk and closed. The old file is not touched; it becomes a replaced file once the row of the new one is added.
     *
     * @throws io.github.jaeseongs95.sennard.domain.error.IntegrityException ARTIFACT_CORRUPTED when the old file is not whole: nothing is written
     */
    public Written rewrite(String storageKey, String keyId, long expectedSize, String expectedSha256) {
        Path source = pathOf(storageKey);
        String newKeyId = keys.activeKeyId();
        String newStorageKey = newStorageKey();
        Path target = pathOf(newStorageKey);
        byte[] oldKey = keys.key(keyId);
        byte[] newKey = keys.key(newKeyId);
        boolean written = false;
        try (FileChannel in = open(source, StandardOpenOption.READ)) {
            requireSpace(in.size());
            folders.ensureSubfolder(target.getParent());
            folders.createOwnerOnlyFile(target);
            try (FileChannel out = open(target, StandardOpenOption.WRITE)) {
                long length = in.size();
                MessageDigest digest = Digests.newSha256();
                long position = 0;
                long number = 0;
                long plainBytes = 0;
                while (position < length) {
                    byte[] body = readSegment(in, position, length);
                    boolean last = position + SegmentCipher.LENGTH_BYTES + body.length == length;
                    byte[] plain = cipher.open(oldKey, storageKey, number, last, body);
                    if (last && plain.length != 0) {
                        throw ArtifactErrors.corrupted("end-mark");
                    }
                    if (!last) {
                        digest.update(plain);
                        plainBytes += plain.length;
                    }
                    write(out, out.size(), cipher.seal(newKey, newStorageKey, number, last, plain));
                    position += SegmentCipher.LENGTH_BYTES + body.length;
                    number++;
                }
                if (length == 0) {
                    throw ArtifactErrors.corrupted("end-mark-missing");
                }
                String sha256 = HexFormat.of().formatHex(digest.digest());
                if (plainBytes != expectedSize || !sha256.equals(expectedSha256)) {
                    throw ArtifactErrors.corrupted(plainBytes != expectedSize ? "size" : "sha256");
                }
                out.force(true);
            }
            makeReadOnly(target);
            written = true;
            return new Written(newStorageKey, newKeyId, expectedSize, expectedSha256);
        } catch (IOException cause) {
            throw ArtifactErrors.storageIo(cause);
        } finally {
            Arrays.fill(oldKey, (byte) 0);
            Arrays.fill(newKey, (byte) 0);
            if (!written) {
                delete(newStorageKey);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------ the folder: delete, quarantine, list

    /** Deletes the file of a storage key when it is there (a read-only file is made writable first, which Windows requires); true when a file was deleted. */
    public boolean delete(String storageKey) {
        Path file = pathOf(storageKey);
        try {
            if (!Files.exists(file)) {
                return false;
            }
            makeWritable(file);
            return Files.deleteIfExists(file);
        } catch (IOException cause) {
            throw ArtifactErrors.storageIo(cause);
        }
    }

    /** Moves a file that has no row to the quarantine folder; the file keeps its name. True when it was moved. */
    public boolean quarantine(String storageKey) {
        Path file = pathOf(storageKey);
        try {
            if (!Files.exists(file)) {
                return false;
            }
            Path quarantine = file.getParent().getParent().resolve(ORPHANS);
            folders.ensureSubfolder(quarantine);
            Files.move(file, quarantine.resolve(storageKey), StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (IOException cause) {
            throw ArtifactErrors.storageIo(cause);
        }
    }

    /** Moves a quarantined file back to its place, for a storage key that has a row again (a restored database). True when it was moved. */
    public boolean restore(String storageKey) {
        Path file = quarantined(storageKey);
        try {
            if (!Files.exists(file)) {
                return false;
            }
            Path target = file.getParent().getParent().resolve(storageKey.substring(0, 2)).resolve(storageKey);
            folders.ensureSubfolder(target.getParent());
            Files.move(file, target, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (IOException cause) {
            throw ArtifactErrors.storageIo(cause);
        }
    }

    /** The storage keys of the files in the quarantine folder. */
    public List<String> quarantinedKeys() {
        return settings.current().directories().stream().map(root -> root.resolve(ORPHANS)).filter(path -> !Files.notExists(path))
                .flatMap(path -> namesIn(path).stream()).sorted().toList();
    }

    /** Deletes what the quarantine folder holds and returns the names of the files deleted, for the audit log of the explicit command. */
    public List<String> purgeQuarantine() {
        List<String> purged = new ArrayList<>();
        for (String name : quarantinedKeys()) {
            Path file = quarantined(name);
            try {
                makeWritable(file);
                if (Files.deleteIfExists(file)) {
                    purged.add(name);
                }
            } catch (IOException cause) {
                throw ArtifactErrors.storageIo(cause);
            }
        }
        return List.copyOf(purged);
    }

    /** The storage keys of every file in the attachment folder (the quarantine folder not counted). */
    public List<String> storedKeys() {
        List<String> names = new ArrayList<>();
        for (Path root : settings.current().directories()) {
            if (Files.notExists(root)) continue;
            try (DirectoryStream<Path> folders = Files.newDirectoryStream(root)) {
                for (Path folder : folders) {
                    if (Files.isDirectory(folder) && !folder.equals(root.resolve(ORPHANS))) names.addAll(namesIn(folder));
                }
            } catch (IOException cause) { throw ArtifactErrors.storageIo(cause); }
        }
        names.sort(String::compareTo);
        return List.copyOf(names);
    }

    private static List<String> namesIn(Path folder) {
        List<String> names = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(folder)) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                if (STORAGE_KEY.matcher(name).matches() && Files.isRegularFile(file)) {
                    names.add(name);
                }
            }
        } catch (IOException cause) {
            throw ArtifactErrors.storageIo(cause);
        }
        names.sort(String::compareTo);
        return List.copyOf(names);
    }

    // ------------------------------------------------------------------------------------------------------------ low level

    private static FileChannel open(Path file, StandardOpenOption... options) throws IOException {
        try {
            return FileChannel.open(file, options);
        } catch (NoSuchFileException missing) {
            LOG.debug("The file {} is not there.", file.getFileName(), missing);
            throw ArtifactErrors.corrupted("file-missing");
        }
    }

    /** Takes the append lock; it is held until the channel is closed (closing a channel releases its locks), so the caller keeps no lock object. */
    private static void holdAppendLock(FileChannel channel) throws IOException {
        try {
            FileLock lock = channel.tryLock(Long.MAX_VALUE - 1, 1, false);
            if (lock == null) {
                throw ArtifactErrors.busy();
            }
        } catch (OverlappingFileLockException sameJvm) {
            // The same JVM holds the byte already (another request of this server): the same answer as another process holding it.
            LOG.debug("The append lock is held by another request of this process.", sameJvm);
            throw ArtifactErrors.busy();
        }
    }

    private static void write(FileChannel channel, long position, byte[] bytes) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        long at = position;
        while (buffer.hasRemaining()) {
            at += channel.write(buffer, at);
        }
    }

    private static byte[] readFully(FileChannel channel, long position, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length);
        long at = position;
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, at);
            if (read < 0) {
                throw ArtifactErrors.corrupted("file-short");
            }
            at += read;
        }
        return buffer.array();
    }

    private static String sha256(byte[] bytes) {
        return Digests.sha256Hex(bytes);
    }

    /** Closes a file for good: nothing may write it. Whether it is closed is told by the row and the end mark, never by this attribute (a process that runs as root writes anyway). */
    private static void makeReadOnly(Path file) throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(file, PosixFileAttributeView.class);
        if (posix != null) {
            posix.setPermissions(PosixFilePermissions.fromString("r--------"));
            return;
        }
        DosFileAttributeView dos = Files.getFileAttributeView(file, DosFileAttributeView.class);
        if (dos != null) {
            dos.setReadOnly(true);
        }
    }

    private static void makeWritable(Path file) throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(file, PosixFileAttributeView.class);
        if (posix != null) {
            posix.setPermissions(PosixFilePermissions.fromString("rw-------"));
            return;
        }
        DosFileAttributeView dos = Files.getFileAttributeView(file, DosFileAttributeView.class);
        if (dos != null) {
            dos.setReadOnly(false);
        }
    }
}
