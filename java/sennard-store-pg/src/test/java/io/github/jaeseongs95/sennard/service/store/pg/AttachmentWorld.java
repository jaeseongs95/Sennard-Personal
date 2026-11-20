// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.pg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.function.Executable;
import org.springframework.transaction.PlatformTransactionManager;

import io.github.jaeseongs95.sennard.app.attachment.AttachmentFileStore;
import io.github.jaeseongs95.sennard.app.attachment.AttachmentKeys;
import io.github.jaeseongs95.sennard.app.attachment.AttachmentMaintenance;
import io.github.jaeseongs95.sennard.app.attachment.AttachmentService;
import io.github.jaeseongs95.sennard.app.attachment.AttachmentUploads;
import io.github.jaeseongs95.sennard.app.attachment.Sha256Text;
import io.github.jaeseongs95.sennard.app.attachment.StorageFolders;
import io.github.jaeseongs95.sennard.app.attachment.UploadStaging;
import io.github.jaeseongs95.sennard.app.attachment.UploadStagingRepository;
import io.github.jaeseongs95.sennard.domain.Digests;
import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.domain.error.ErrorCode;

/**
 * The attachment beans of a Spring context of the application on the real business database ({@link RewrittenAreasContext}, the one assembly of the other rewritten areas), with the
 * attachment folder and the key folder in a temporary folder of the test and the test's clock ({@link RewrittenAreasContext.TestClock}, the {@code Clock} bean every attachment class
 * takes). Nothing is replaced: {@link AttachmentUploads}, {@link AttachmentService}, {@link AttachmentMaintenance} and the file store are the product's own beans.
 *
 * <p>A world is made per test (the settings differ from test to test) and closed with it; closing a context does not close the shared pool. Two worlds over the same folders and the
 * same database make two runs of the application with other settings, one after the other.
 */
final class AttachmentWorld implements AutoCloseable {
    static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    static final String ALICE = "alice";
    static final String BOB = "bob";

    private final RewrittenAreasContext context;
    private final Path directory;
    private final Path keyDirectory;
    private final Path scratch;
    private final SecureRandom random = new SecureRandom();

    private AttachmentWorld(RewrittenAreasContext context, Path scratch, Path directory, Path keyDirectory) {
        this.context = context;
        this.scratch = scratch;
        this.directory = directory;
        this.keyDirectory = keyDirectory;
    }

    /**
     * Starts the application over the folders under {@code scratch}. {@code settings} are {@code sennard.attachments.*} keys without the prefix (the disk margin is none unless a test says
     * otherwise). The key files {@code keyIds} are made first (a start whose active key is not the first one needs its key made by someone: the owner); with none, the first start makes
     * the first key.
     */
    static AttachmentWorld open(Path scratch, Map<String, String> settings, String... keyIds) {
        Path directory = scratch.resolve("attachments");
        Path keyDirectory = scratch.resolve("secrets").resolve("attachment-keys");
        Map<String, Object> all = new LinkedHashMap<>();
        all.put("sennard.attachments.directory", directory.toString());
        all.put("sennard.attachments.key-directory", keyDirectory.toString());
        all.put("sennard.attachments.disk-margin-bytes", "0B");
        for (Map.Entry<String, String> setting : settings.entrySet()) {
            all.put("sennard.attachments." + setting.getKey(), setting.getValue());
        }
        RewrittenAreasContext context = RewrittenAreasContext.appConnection(T0, all);
        AttachmentWorld world = new AttachmentWorld(context, scratch, directory, keyDirectory);
        if (keyIds.length > 0) {
            world.folders().ensureOwnerOnlyFolder(keyDirectory);
            for (String keyId : keyIds) {
                if (!world.keys().ids().contains(keyId)) {
                    world.keys().create(keyId);
                }
            }
        }
        world.maintenance().prepare();
        return world;
    }

    static AttachmentWorld open(Path scratch) {
        return open(scratch, Map.of());
    }

    @Override
    public void close() {
        context.close();
    }

    // ------------------------------------------------------------------------------------------------------------ beans

    RewrittenAreasContext.TestClock clock() {
        return context.clock();
    }

    AttachmentUploads uploads() {
        return context.bean(AttachmentUploads.class);
    }

    AttachmentService service() {
        return context.bean(AttachmentService.class);
    }

    AttachmentFileStore store() {
        return context.bean(AttachmentFileStore.class);
    }

    AttachmentMaintenance maintenance() {
        return context.bean(AttachmentMaintenance.class);
    }

    AttachmentKeys keys() {
        return context.bean(AttachmentKeys.class);
    }

    StorageFolders folders() {
        return context.bean(StorageFolders.class);
    }

    <T> T bean(Class<T> type) {
        return context.bean(type);
    }

    PlatformTransactionManager transactionManager() {
        return context.transactionManager();
    }

    DataSource dataSource() {
        return context.dataSource();
    }

    Path directory() {
        return directory;
    }

    Path keyDirectory() {
        return keyDirectory;
    }

    Path scratch() {
        return scratch;
    }

    // ------------------------------------------------------------------------------------------------------------ content

    byte[] bytes(int length) {
        byte[] bytes = new byte[length];
        random.nextBytes(bytes);
        return bytes;
    }

    /** The text form of the sha256 of the bytes: what the tools take. */
    static String sha(byte[] bytes) {
        return Sha256Text.of(Digests.sha256Hex(bytes));
    }

    static String hex(byte[] bytes) {
        return Digests.sha256Hex(bytes);
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream joined = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            joined.writeBytes(part);
        }
        return joined.toByteArray();
    }

    /** The operation key of an upload (the contract: 16 to 128 characters of letters, digits and {@code _.:-}). */
    static String operationKey() {
        return "op-" + UUID.randomUUID();
    }

    // ------------------------------------------------------------------------------------------------------------ the tools

    /** One file of a bundle as a test sends it: the name and the plain bytes (the media type is {@link #MEDIA_TYPE}). */
    record Item(String fileName, byte[] content) {
    }

    static final String MEDIA_TYPE = "application/octet-stream";

    /**
     * What the begin of a bundle of one file answers, as most tests read it: the id of the attachment the finish makes, the one upload id (file 0), the largest chunk and the expiry. A
     * single-file begin is a bundle with one file, so the helpers of one file keep their old shape and only the names of the ids are told apart.
     */
    record Single(String attachmentId, String uploadId, int maxChunkBytes, Instant expiresAt) {
    }

    /** The one file of a begin of a single file: asserts that it is a bundle of one file, numbered 0. */
    static Single single(AttachmentService.Begun begun) {
        assertEquals(1, begun.files().size(), "a single-file begin is a bundle of one file");
        assertEquals(0, begun.files().get(0).seq());
        return new Single(begun.attachmentId(), begun.files().get(0).uploadId(), begun.maxChunkBytes(), begun.expiresAt());
    }

    /** {@code count} files of {@code length} random bytes each, named {@code file-0.bin}, {@code file-1.bin} ... */
    List<Item> items(int count, int length) {
        List<Item> items = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            items.add(new Item("file-" + index + ".bin", bytes(length)));
        }
        return List.copyOf(items);
    }

    /** What a finish answers for the bundle of these files (the order of the list is the files' numbers). */
    static AttachmentService.Kept kept(String attachmentId, List<Item> items) {
        List<AttachmentService.KeptFile> files = new ArrayList<>();
        for (int seq = 0; seq < items.size(); seq++) {
            files.add(new AttachmentService.KeptFile(seq, items.get(seq).fileName(), MEDIA_TYPE, items.get(seq).content().length, sha(items.get(seq).content())));
        }
        return new AttachmentService.Kept(attachmentId, List.copyOf(files));
    }

    AttachmentService.BeginRequest request(String operationKey, String fileName, long size, String sha256) {
        return new AttachmentService.BeginRequest(operationKey, List.of(new AttachmentService.BeginFile(fileName, MEDIA_TYPE, size, sha256)), null);
    }

    /** The request of a bundle: one file per item, in the order of the list. */
    AttachmentService.BeginRequest bundleRequest(String operationKey, List<Item> items) {
        List<AttachmentService.BeginFile> files = new ArrayList<>();
        for (Item item : items) {
            files.add(new AttachmentService.BeginFile(item.fileName(), MEDIA_TYPE, item.content().length, sha(item.content())));
        }
        return new AttachmentService.BeginRequest(operationKey, List.copyOf(files), null);
    }

    /** Begins an upload of this content as {@code owner}, with a new operation key: a bundle of one file. */
    Single begin(String owner, String fileName, byte[] content) {
        return single(uploads().begin(owner, request(operationKey(), fileName, content.length, sha(content))));
    }

    /** Begins a bundle of these files as {@code owner}, with a new operation key. */
    AttachmentService.Begun beginBundle(String owner, List<Item> items) {
        return uploads().begin(owner, bundleRequest(operationKey(), items));
    }

    AttachmentService.Progress chunk(String uploadId, long sequence, byte[] data) {
        return uploads().chunk(uploadId, sequence, data, sha(data));
    }

    /** Sends the chunks of {@code chunkBytes} of a file's content to its upload (none for a file of no bytes: a test that keeps one sends the empty chunk itself). */
    void send(String uploadId, byte[] content, int chunkBytes) {
        long sequence = 0;
        for (int from = 0; from < content.length; from += chunkBytes) {
            chunk(uploadId, sequence++, java.util.Arrays.copyOfRange(content, from, Math.min(content.length, from + chunkBytes)));
        }
    }

    /** The whole flow of one file: begin, the chunks of {@code chunkBytes}, finish. */
    AttachmentService.Kept upload(String owner, String fileName, byte[] content, int chunkBytes) {
        Single begun = begin(owner, fileName, content);
        send(begun.uploadId(), content, chunkBytes);
        return uploads().finish(begun.attachmentId());
    }

    /** The whole flow of a bundle: one begin, the chunks of {@code chunkBytes} of each file, one finish. */
    AttachmentService.Kept uploadBundle(String owner, List<Item> items, int chunkBytes) {
        AttachmentService.Begun begun = beginBundle(owner, items);
        for (AttachmentService.BegunFile file : begun.files()) {
            send(file.uploadId(), items.get(file.seq()).content(), chunkBytes);
        }
        return uploads().finish(begun.attachmentId());
    }

    /** The whole of file 0 of an attachment, read in pages of {@code pageBytes}. */
    byte[] readAll(String attachmentId, int pageBytes) {
        return readAll(attachmentId, 0, pageBytes);
    }

    /** The whole of file {@code seq} of an attachment, read in pages of {@code pageBytes}. */
    byte[] readAll(String attachmentId, int seq, int pageBytes) {
        ByteArrayOutputStream whole = new ByteArrayOutputStream();
        long offset = 0;
        while (true) {
            AttachmentFileStore.Page page = uploads().read(attachmentId, seq, offset, pageBytes).page();
            whole.writeBytes(page.data());
            offset += page.data().length;
            if (page.end() || page.data().length == 0) {
                return whole.toByteArray();
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------ rows and files

    UploadStaging staging(String uploadId) {
        return bean(UploadStagingRepository.class).findById(uploadId).orElseThrow();
    }

    boolean hasStaging(String uploadId) {
        return bean(UploadStagingRepository.class).findById(uploadId).isPresent();
    }

    Path pathOf(String storageKey) {
        return directory.resolve(storageKey.substring(0, 2)).resolve(storageKey);
    }

    Path pathOfUpload(String uploadId) {
        return pathOf(staging(uploadId).storageKey());
    }

    /** The file of the current copy of file 0 of an attachment. */
    Path pathOfAttachment(String attachmentId) {
        return pathOfAttachment(attachmentId, 0);
    }

    /** The file of the current copy of file {@code seq} of an attachment. */
    Path pathOfAttachment(String attachmentId, int seq) {
        return pathOf(service().view(attachmentId, seq).orElseThrow().current().storageKey());
    }

    /** The upload rows of a bundle, in the order of the files' numbers. */
    List<UploadStaging> stagingOf(String attachmentId) {
        return bean(UploadStagingRepository.class).findByAttachmentId(attachmentId);
    }

    long count(String sql) throws SQLException {
        try (Connection connection = BusinessDb.connect(); Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next(), sql);
            return rows.getLong(1);
        }
    }

    // ------------------------------------------------------------------------------------------------------------ tampering with the disk

    /** Puts other bytes in the place of a file: the file is moved out of the way and a new one made at its path (a closed file is read-only). */
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

    static byte[] flipped(byte[] bytes, int index) {
        byte[] changed = bytes.clone();
        changed[index] = (byte) (changed[index] ^ 0x01);
        return changed;
    }

    /** The segments of a file's bytes, each with its length prefix (a 4-byte big-endian length of what follows). */
    static List<byte[]> segments(byte[] file) {
        ByteBuffer buffer = ByteBuffer.wrap(file);
        List<byte[]> segments = new ArrayList<>();
        while (buffer.hasRemaining()) {
            int length = buffer.getInt(buffer.position());
            byte[] segment = new byte[4 + length];
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

    // ------------------------------------------------------------------------------------------------------------ assertions

    /** The call fails with this exception type and code, and carries this {@code details.retryable}. */
    static <T extends SennardException> T refused(Class<T> type, ErrorCode code, boolean retryable, Executable call) {
        T failure = assertThrows(type, call);
        assertEquals(code, failure.code());
        assertEquals(Boolean.valueOf(retryable), failure.details().get("retryable"), code + " retryable");
        return failure;
    }

    /**
     * The rows and the folder agree (the invariants of design test 11): every upload in progress that took bytes in has its file, no attachment is without its current file, and no
     * file is damaged or missing. A file that no row names is not looked at here: the cleaner moves such a file to the quarantine folder.
     */
    void assertNoRowWithoutItsFile() {
        Set<String> tolerated = Set.of("file-without-row");
        List<AttachmentMaintenance.Finding> findings = maintenance().check().stream().filter(finding -> !tolerated.contains(finding.kind())).toList();
        assertEquals(List.of(), findings, "no row may be left without its file");
    }
}
