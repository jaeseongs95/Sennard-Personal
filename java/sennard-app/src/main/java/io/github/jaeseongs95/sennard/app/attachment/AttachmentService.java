// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.attachment;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.IdGenerator;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The use cases of the attachment tables (the design's 6): each public method is one transaction, and the repositories only join it. What a use case judges from a file was read or
 * written by the entrance ({@link AttachmentUploads}, {@link AttachmentMaintenance}) before or after it and is handed over as a value; no method opens, reads
 * or writes a file, and none locks anything by hand or runs again after a lost race. The winner of a race is told by the number of rows one conditional statement changed.
 *
 * <p>An attachment is a bundle of files. One begin takes the list of files and makes one upload per file, all under the id of the attachment the finish will make (the upload rows
 * carry it and the file's number in the bundle); the finish checks and closes every file and then, in one transaction, makes the attachment, its files and their first stored copies
 * together or not at all. The id of the attachment is known from the begin on, so a finish that is sent again after the upload rows are gone finds the attachment by the same id and
 * no table has a column that points from one to the other.
 */
@Service
@Validated
public class AttachmentService {
    /** The most files one attachment may have: a limit of the contract, like the size of a chunk, and not a setting. */
    public static final int MAX_FILES = 64;

    /** One file of a begin. */
    public record BeginFile(
            @NotBlank @Size(max = 512) String fileName,
            @NotBlank @Size(max = 255) String mediaType,
            @Min(0) long size,
            @NotNull @Pattern(regexp = Sha256Text.PATTERN) String sha256) {
    }

    /** The input of {@code artifact_begin}; the constraints are the contract's. The files are numbered 0, 1, 2 ... in the order of the list. */
    public record BeginRequest(
            @NotNull @Pattern(regexp = "^[A-Za-z0-9_.:-]{16,128}$") String operationKey,
            @NotEmpty List<@NotNull @Valid BeginFile> files,
            String purpose) {
    }

    /** The upload of one file of the bundle a begin made. */
    public record BegunFile(int seq, String uploadId) {
    }

    /** What {@code artifact_begin} answers: the id of the attachment the finish makes, the upload of each file, the largest chunk and the expiry. */
    public record Begun(String attachmentId, List<BegunFile> files, int maxChunkBytes, Instant expiresAt) {
    }

    /** The stored segment number and the number of plain bytes the upload holds. */
    public record Progress(String uploadId, long sequence, long received) {
    }

    /** What a chunk does to an upload (the design's 4.4, step 1): it is already in ({@link Replayed}), or it is the next one ({@link Next}). */
    public sealed interface ChunkPlan {
        /** The chunk was taken in before; this is the answer of the first time. */
        record Replayed(Progress progress) implements ChunkPlan {
        }

        /** The next chunk of the upload: the row it is appended to. */
        record Next(UploadStaging row) implements ChunkPlan {
        }
    }

    /** What a finish does to a bundle: it was done before ({@link Done}), or the files are to be checked and closed ({@link Pending}, the rows in the order of the files' numbers). */
    public sealed interface FinishPlan {
        record Done(Kept kept) implements FinishPlan {
        }

        record Pending(List<UploadStaging> rows) implements FinishPlan {
        }
    }

    /** A file of a kept attachment as the tools answer it: its number, name, media type, size and sha256 in the text form. */
    public record KeptFile(int seq, String fileName, String mediaType, long size, String sha256) {
    }

    /** What {@code artifact_finish} answers: the attachment and its files. */
    public record Kept(String attachmentId, List<KeptFile> files) {
    }

    /** One file of an attachment with the attachment it is in. */
    public record Described(Attachment attachment, AttachmentFile file) {
    }

    /** One file of an attachment with the copy it is read from now. */
    public record FileView(Attachment attachment, AttachmentFile file, AttachmentStorage current) {
    }

    /** What {@code artifact_status} answers for an upload in progress: one file of a bundle. Nothing in it is a path or a storage key. */
    public record UploadStatus(String uploadId, String attachmentId, int seq, String state, String fileName, String mediaType, long size, String sha256, Instant expiresAt, long received,
            long sequence) {
    }

    /** What {@code artifact_status} answers for a kept attachment: its files. */
    public record AttachmentStatus(String attachmentId, List<KeptFile> files) {
    }

    /** What the storage key of a file is to the rows. */
    public enum UseKind {
        /** An upload in progress, or the current copy of a file, has it. */
        IN_USE,
        /** A copy of a file that a later copy replaced (after a re-encryption). */
        REPLACED,
        /** No row has it. */
        UNREFERENCED
    }

    /** The use of a storage key, and for a replaced copy when the copy that replaced it was written. */
    public record KeyUse(UseKind kind, Instant replacedAt) {
    }

    private final UploadStagingRepository uploads;
    private final AttachmentRepository attachments;
    private final AttachmentFileRepository files;
    private final AttachmentStorageRepository storages;
    private final UploadCandidates candidates;
    private final AttachmentProperties properties;
    private AttachmentSettings settings;
    private final AttachmentFileStore store;
    private final IdGenerator ids;
    private final Clock clock;

    public AttachmentService(UploadStagingRepository uploads, AttachmentRepository attachments, AttachmentFileRepository files, AttachmentStorageRepository storages,
            UploadCandidates candidates, AttachmentProperties properties, AttachmentFileStore store, IdGenerator ids, Clock clock) {
        this.uploads = uploads;
        this.attachments = attachments;
        this.files = files;
        this.storages = storages;
        this.candidates = candidates;
        this.properties = properties;
        this.settings = new AttachmentSettings(properties);
        this.store = store;
        this.ids = ids;
        this.clock = clock;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AttachmentService(UploadStagingRepository uploads, AttachmentRepository attachments, AttachmentFileRepository files, AttachmentStorageRepository storages,
            UploadCandidates candidates, AttachmentProperties properties, AttachmentFileStore store, IdGenerator ids, Clock clock, AttachmentSettings settings) {
        this(uploads, attachments, files, storages, candidates, properties, store, ids, clock);
        this.settings = settings;
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    // ------------------------------------------------------------------------------------------------------------ begin

    /** Reads a completed begin before the entrance checks available disk space. A completed bundle needs no further writes. */
    @Transactional(readOnly = true)
    public Optional<Begun> replayCompletedBegin(@NotBlank String owner, @Valid @NotNull BeginRequest request) {
        List<UploadStaging> existing = uploads.findByOwnerAndOperationKey(owner, request.operationKey());
        if (existing.isEmpty() || existing.stream().anyMatch(row -> row.state() != UploadStaging.State.COMPLETED)) {
            return Optional.empty();
        }
        requireSupportedPurpose(request);
        return Optional.of(replayBegin(existing, request.files()));
    }

    /**
     * Starts the uploads of a bundle, or answers the ones the same author began with the same operationKey and the same files (the design's 3). The limits are looked at here and a
     * request over one is refused whole, never cut: the number of files, the size of a file, the bundles the author has open, the budget of the open uploads, the budget of the
     * attachments (which counts the kept attachments and the size every open upload declared, because an open upload becomes an attachment when its bundle ends).
     */
    @Transactional
    public Begun begin(@NotBlank String owner, @Valid @NotNull BeginRequest request) {
        requireSupportedPurpose(request);
        List<BeginFile> declared = request.files();
        List<UploadStaging> existing = uploads.findByOwnerAndOperationKey(owner, request.operationKey());
        if (!existing.isEmpty()) {
            return replayBegin(existing, declared);
        }
        if (declared.size() > MAX_FILES) {
            throw ArtifactErrors.limit(ArtifactErrors.Limit.FILE_COUNT, declared.size(), MAX_FILES);
        }
        AttachmentSettings.Snapshot policy = settings.current();
        long maxFile = policy.maxFileBytes();
        long total = 0;
        for (BeginFile file : declared) {
            if (file.size() > maxFile) {
                throw ArtifactErrors.limit(ArtifactErrors.Limit.FILE_SIZE, file.size(), maxFile);
            }
            total += file.size();
        }
        long open = uploads.countOpen(owner);
        if (open >= policy.maxOpenPerOwner()) {
            throw ArtifactErrors.openUploads(open, policy.maxOpenPerOwner());
        }
        long declaredOpen = uploads.declaredOpenBytes();
        long stagingBudget = policy.stagingBudgetBytes();
        if (declaredOpen + total > stagingBudget) {
            throw ArtifactErrors.limit(ArtifactErrors.Limit.STAGING_BUDGET, declaredOpen + total, stagingBudget);
        }
        long kept = attachments.keptBytes();
        long attachmentBudget = policy.attachmentBudgetBytes();
        if (kept + declaredOpen + total > attachmentBudget) {
            throw ArtifactErrors.limit(ArtifactErrors.Limit.ATTACHMENT_BUDGET, kept + declaredOpen + total, attachmentBudget);
        }
        Instant now = now();
        String attachmentId = ids.generateId().toString();
        Instant expiresAt = now.plus(properties.uploadTtl());
        List<BegunFile> made = new ArrayList<>();
        for (int seq = 0; seq < declared.size(); seq++) {
            BeginFile file = declared.get(seq);
            String uploadId = ids.generateId().toString();
            uploads.insert(uploadId, attachmentId, seq, owner, request.operationKey(), file.fileName(), file.mediaType(), file.size(), Sha256Text.hex(file.sha256()), store.newStorageKey(),
                    properties.activeKey(), expiresAt, now);
            made.add(new BegunFile(seq, uploadId));
        }
        return new Begun(attachmentId, List.copyOf(made), AttachmentFileStore.MAX_CHUNK_BYTES, expiresAt);
    }

    private static void requireSupportedPurpose(BeginRequest request) {
        ArtifactPurpose purpose = request.purpose() == null ? ArtifactPurpose.EVIDENCE
                : ArtifactPurpose.of(request.purpose()).orElseThrow(() -> ArtifactErrors.unsupportedPurpose(request.purpose()));
        if (!purpose.supported()) {
            throw ArtifactErrors.unsupportedPurpose(purpose.wire());
        }
    }

    private static Begun replayBegin(List<UploadStaging> rows, List<BeginFile> declared) {
        if (rows.size() != declared.size()) {
            throw ArtifactErrors.operationConflict();
        }
        List<BegunFile> made = new ArrayList<>();
        for (int seq = 0; seq < declared.size(); seq++) {
            UploadStaging row = rows.get(seq);
            BeginFile file = declared.get(seq);
            if (row.seq() != seq || !row.fileName().equals(file.fileName()) || !row.mediaType().equals(file.mediaType())
                    || row.declaredSize() != file.size() || !row.declaredSha256().equals(Sha256Text.hex(file.sha256()))) {
                throw ArtifactErrors.operationConflict();
            }
            made.add(new BegunFile(seq, row.id()));
        }
        return new Begun(rows.get(0).attachmentId(), List.copyOf(made), AttachmentFileStore.MAX_CHUNK_BYTES, rows.get(0).expiresAt());
    }

    // ------------------------------------------------------------------------------------------------------------ chunk

    /**
     * The upload an id names. A chunk is bound to its upload by the upload's id alone (the design's editions 9 and 12): the id is a random value that only the author's begin was
     * given, so it is the capability to continue the upload, and a client outside the model (the upload client of {@code sennard attachments send}) that was handed the id continues it
     * without being the author's session. The author is the row's {@code owner}, set by the begin, and is the attachment's author when the bundle ends.
     */
    private UploadStaging upload(String uploadId) {
        return uploads.findById(uploadId).orElseThrow(ArtifactErrors::notFound);
    }

    /**
     * What the chunk of this number does to the upload (the design's 4.4, step 1). The number after the last one taken in is the next chunk. The last one taken in, sent again with the
     * same sha256, is answered again as it was; with another sha256 it is a conflict, and so is any other number. A chunk that would take the upload past the size it declared is
     * refused as a wrong size, before anything is appended.
     */
    @Transactional(readOnly = true)
    public ChunkPlan planChunk(@NotBlank String uploadId, @Min(0) long sequence, @NotNull String chunkSha256, @Min(0) int chunkBytes) {
        UploadStaging row = upload(uploadId);
        if (row.state() != UploadStaging.State.STAGING) {
            throw ArtifactErrors.chunkConflict("completed");
        }
        if (!now().isBefore(row.expiresAt())) {
            throw ArtifactErrors.expired();
        }
        String chunkHex = Sha256Text.hex(chunkSha256);
        if (row.nextSequence() > 0 && sequence == row.nextSequence() - 1) {
            if (Objects.equals(row.lastChunkSha256(), chunkHex)) {
                return new ChunkPlan.Replayed(new Progress(row.id(), sequence, row.receivedBytes()));
            }
            throw ArtifactErrors.chunkConflict("received-other-content");
        }
        if (sequence != row.nextSequence()) {
            throw ArtifactErrors.chunkConflict("order");
        }
        if (row.receivedBytes() + chunkBytes > row.declaredSize()) {
            throw ArtifactErrors.hashMismatch("size");
        }
        return new ChunkPlan.Next(row);
    }

    /**
     * Takes a chunk, which the entrance has appended to the file, into the row, in one conditional statement (the design's 4.4, step 4), and counts the expiry of the whole bundle
     * again from now (a bundle lives and dies as a whole). Nothing is run again: when the statement changes no row, the row is read, and the chunk is answered as taken in when the
     * row has it (the same chunk was taken in by another request), else it is a conflict.
     */
    @Transactional
    public Progress takeInChunk(@NotBlank String uploadId, @Min(0) long sequence, @Min(0) long storedBytes, @Min(0) long receivedBytes, @NotNull String chunkSha256) {
        Instant now = now();
        String chunkHex = Sha256Text.hex(chunkSha256);
        Instant expiresAt = now.plus(properties.uploadTtl());
        int changed = uploads.takeInChunk(uploadId, sequence, storedBytes, receivedBytes, sequence + 1, chunkHex, expiresAt, now);
        if (changed == 1) {
            uploads.extendBundle(upload(uploadId).attachmentId(), expiresAt, now);
            return new Progress(uploadId, sequence, receivedBytes);
        }
        UploadStaging row = uploads.findById(uploadId).orElseThrow(ArtifactErrors::notFound);
        if (row.state() == UploadStaging.State.STAGING && row.nextSequence() == sequence + 1 && Objects.equals(row.lastChunkSha256(), chunkHex)) {
            return new Progress(uploadId, sequence, row.receivedBytes());
        }
        throw ArtifactErrors.chunkConflict("lost-race");
    }

    // ------------------------------------------------------------------------------------------------------------ finish

    /**
     * What a finish has to do: a bundle that is done is answered with its attachment again, one in progress has its files checked and closed. A bundle whose uploads have expired is
     * expired; the rows of a bundle share their expiry, so a bundle is never half cleaned away and a finish never meets some of its files.
     */
    @Transactional(readOnly = true)
    public FinishPlan planFinish(@NotBlank String attachmentId) {
        List<UploadStaging> rows = uploads.findByAttachmentId(attachmentId);
        if (rows.isEmpty()) {
            // Older cleaners removed completed upload rows. The attachment they made has the same id.
            return new FinishPlan.Done(kept(attachments.findById(attachmentId).orElseThrow(ArtifactErrors::notFound)));
        }
        if (rows.get(0).state() == UploadStaging.State.COMPLETED) {
            return new FinishPlan.Done(kept(attachments.findById(attachmentId).orElseThrow(ArtifactErrors::notFound)));
        }
        Instant now = now();
        for (int seq = 0; seq < rows.size(); seq++) {
            if (rows.get(seq).seq() != seq || !now.isBefore(rows.get(seq).expiresAt())) {
                throw ArtifactErrors.expired();
            }
        }
        return new FinishPlan.Pending(rows);
    }

    private Kept kept(Attachment attachment) {
        return new Kept(attachment.id(), files.ofAttachment(attachment.id()).stream().map(AttachmentService::keptFile).toList());
    }

    private static KeptFile keptFile(AttachmentFile file) {
        return new KeptFile(file.seq(), file.fileName(), file.mediaType(), file.sizeBytes(), Sha256Text.of(file.contentSha256()));
    }

    /**
     * Ends a bundle whose files the entrance has checked and closed (the design's 6, finish): in this one transaction every file's checked size and sha256 are compared with the
     * declared ones, every upload row is marked COMPLETED where it is still in progress, and the attachment, its files and their first stored copies (rev 0) are inserted, so that
     * either all of it is there or none. When a row is no longer in progress, a bundle that expired and was removed is expired, and one that another finish completed is answered with
     * the same attachment.
     *
     * @param closed what the entrance found of each file, in the order of the files' numbers
     */
    @Transactional
    public Kept completeFinish(@NotBlank String attachmentId, @NotEmpty List<AttachmentFileStore.Closed> closed) {
        List<UploadStaging> rows = uploads.findByAttachmentId(attachmentId);
        if (rows.isEmpty()) {
            // The rows are gone: a bundle that was made has its attachment under the same id; one that expired and was cleaned away has none.
            if (attachments.findById(attachmentId).isPresent()) {
                return alreadyKept(attachmentId);
            }
            throw ArtifactErrors.expired();
        }
        if (rows.get(0).state() == UploadStaging.State.COMPLETED) {
            return alreadyKept(attachmentId);
        }
        if (rows.size() != closed.size()) {
            throw ArtifactErrors.expired();
        }
        for (int seq = 0; seq < rows.size(); seq++) {
            UploadStaging row = rows.get(seq);
            AttachmentFileStore.Closed file = closed.get(seq);
            if (row.declaredSize() != file.plainBytes()) {
                throw ArtifactErrors.hashMismatch("size");
            }
            if (!row.declaredSha256().equals(file.sha256())) {
                throw ArtifactErrors.hashMismatch("sha256");
            }
        }
        Instant now = now();
        for (int seq = 0; seq < rows.size(); seq++) {
            if (uploads.complete(rows.get(seq).id(), closed.get(seq).storedBytes(), now) != 1) {
                List<UploadStaging> current = uploads.findByAttachmentId(attachmentId);
                if (!current.isEmpty() && current.get(0).state() == UploadStaging.State.COMPLETED) {
                    return alreadyKept(attachmentId);
                }
                throw ArtifactErrors.expired();
            }
        }
        attachments.insert(attachmentId, rows.get(0).owner(), now);
        for (int seq = 0; seq < rows.size(); seq++) {
            UploadStaging row = rows.get(seq);
            AttachmentFileStore.Closed file = closed.get(seq);
            files.insert(attachmentId, seq, row.fileName(), row.mediaType(), file.plainBytes(), file.sha256());
            storages.insert(attachmentId, seq, 0, row.storageKey(), row.keyId(), now);
        }
        return kept(new Attachment(attachmentId, rows.get(0).owner(), now));
    }

    private Kept alreadyKept(String attachmentId) {
        return kept(attachments.findById(attachmentId).orElseThrow(ArtifactErrors::notFound));
    }

    // ------------------------------------------------------------------------------------------------------------ reads

    /** One file of an attachment and the copy it is read from now. */
    @Transactional(readOnly = true)
    public Optional<FileView> view(@NotBlank String attachmentId, @Min(0) int seq) {
        return attachments.findById(attachmentId).flatMap(attachment -> files.at(attachmentId, seq)
                .flatMap(file -> storages.current(attachmentId, seq).map(current -> new FileView(attachment, file, current))));
    }

    /** One file of an attachment, without where it is stored: what a record that points at the file needs to judge the pointer (the attachment's author, the file's sha256). */
    @Transactional(readOnly = true)
    public Optional<Described> describe(@NotBlank String attachmentId, @Min(0) int seq) {
        return attachments.findById(attachmentId).flatMap(attachment -> files.at(attachmentId, seq).map(file -> new Described(attachment, file)));
    }

    /** The files of an attachment in the order of their numbers; empty when no attachment has the id. */
    @Transactional(readOnly = true)
    public List<AttachmentFile> filesOf(@NotBlank String attachmentId) {
        return files.ofAttachment(attachmentId);
    }

    /** The attachment: its author and its time. */
    @Transactional(readOnly = true)
    public Optional<Attachment> attachment(@NotBlank String attachmentId) {
        return attachments.findById(attachmentId);
    }

    /** The state of an upload in progress (a file of a bundle). Nothing in it is a path or a storage key. */
    @Transactional(readOnly = true)
    public Optional<UploadStatus> uploadStatus(@NotBlank String uploadId) {
        return uploads.findById(uploadId).map(row -> new UploadStatus(row.id(), row.attachmentId(), row.seq(), row.state().name(), row.fileName(), row.mediaType(), row.declaredSize(),
                Sha256Text.of(row.declaredSha256()), row.expiresAt(), row.receivedBytes(), row.nextSequence()));
    }

    /** The facts of a kept attachment: its files. */
    @Transactional(readOnly = true)
    public Optional<AttachmentStatus> attachmentStatus(@NotBlank String attachmentId) {
        return attachments.findById(attachmentId).map(attachment -> new AttachmentStatus(attachment.id(), files.ofAttachment(attachment.id()).stream().map(AttachmentService::keptFile).toList()));
    }

    // ------------------------------------------------------------------------------------------------------------ the cleaner

    /** At most {@code limit} uploads in progress whose time is over. */
    @Transactional(readOnly = true)
    public List<UploadCandidates.Candidate> expiredUploads(@Min(1) int limit) {
        return candidates.expired(now(), limit);
    }

    /** The cleaner's delete of an expired upload: the whole condition is looked at again by the statement. True only for the caller whose statement deleted the row. */
    @Transactional
    public boolean deleteExpired(@NotBlank String uploadId) {
        return uploads.deleteExpired(uploadId, now()) == 1;
    }

    /**
     * What the rows say a storage key is, for the comparison of the folder with the rows (the design's 7). A copy row decides first: the file is the current copy of its file, or a
     * replaced copy. Completed upload rows keep the begin result. Only a staging row owns a key that has no copy row.
     */
    @Transactional(readOnly = true)
    public KeyUse useOf(@NotBlank String storageKey) {
        Optional<AttachmentStorage> copy = storages.byStorageKey(storageKey);
        if (copy.isEmpty()) {
            if (uploads.countStagingByKey(storageKey) > 0) {
                return new KeyUse(UseKind.IN_USE, null);
            }
            // A finish can commit the first copy between the two reads at READ COMMITTED.
            copy = storages.byStorageKey(storageKey);
            if (copy.isEmpty()) {
                return new KeyUse(UseKind.UNREFERENCED, null);
            }
        }
        AttachmentStorage row = copy.get();
        Optional<AttachmentStorage> current = storages.current(row.attachmentId(), row.seq());
        if (current.isPresent() && current.get().storageKey().equals(storageKey)) {
            return new KeyUse(UseKind.IN_USE, null);
        }
        Instant replacedAt = storages.at(row.attachmentId(), row.seq(), row.rev() + 1).map(AttachmentStorage::createdAt).orElse(row.createdAt());
        return new KeyUse(UseKind.REPLACED, replacedAt);
    }

    // ------------------------------------------------------------------------------------------------------------ keys and the check

    /** The key ids the start check needs a key file for: those of the uploads in progress and of the current copy of every file. */
    @Transactional(readOnly = true)
    public Set<String> keyIdsInUse() {
        Set<String> keyIds = new LinkedHashSet<>(uploads.openKeyIds());
        keyIds.addAll(storages.currentKeyIds());
        return Set.copyOf(keyIds);
    }

    /** A file of an attachment with its current copy, for the check of the rows against the folder. {@code file} and {@code current} are null where the row is missing, which is a damage. */
    public record Stored(Attachment attachment, AttachmentFile file, AttachmentStorage current) {
    }

    /**
     * Every file of every attachment with its current copy. An attachment with no file row is listed once with a null file; a file with no copy row is listed with a null copy (both
     * are damages).
     */
    @Transactional(readOnly = true)
    public List<Stored> stored() {
        Map<AttachmentFileRef, AttachmentStorage> currents = new HashMap<>();
        for (AttachmentStorage copy : storages.currents()) {
            currents.put(new AttachmentFileRef(copy.attachmentId(), copy.seq()), copy);
        }
        Map<String, List<AttachmentFile>> byAttachment = new HashMap<>();
        for (AttachmentFile file : files.all()) {
            byAttachment.computeIfAbsent(file.attachmentId(), id -> new ArrayList<>()).add(file);
        }
        List<Stored> all = new ArrayList<>();
        for (Attachment attachment : attachments.all()) {
            List<AttachmentFile> own = byAttachment.getOrDefault(attachment.id(), List.of());
            if (own.isEmpty()) {
                all.add(new Stored(attachment, null, null));
            }
            for (AttachmentFile file : own) {
                all.add(new Stored(attachment, file, currents.get(new AttachmentFileRef(file.attachmentId(), file.seq()))));
            }
        }
        return List.copyOf(all);
    }

    /** The uploads in progress. */
    @Transactional(readOnly = true)
    public List<UploadStaging> openUploads() {
        return uploads.open();
    }

    /** The current copies written with a key, for a re-encryption from it. */
    @Transactional(readOnly = true)
    public List<AttachmentStorage> currentCopiesOfKey(@NotBlank String keyId) {
        return storages.currentsOfKey(keyId);
    }

    /**
     * Adds the copy a re-encryption wrote to a file (the design's 4.3): a row of the number after the highest, read in this transaction. Two re-encryptions that read the same highest
     * number write the same key triple, and the second ends in the translated duplicate-key conflict; nothing is run again. The copy it replaces is no longer current.
     *
     * @return the number of the new copy
     */
    @Transactional
    public int addCopy(@NotBlank String attachmentId, @Min(0) int seq, @NotBlank String storageKey, @NotBlank String keyId) {
        int highest = storages.highestRev(attachmentId, seq);
        if (highest < 0) {
            throw ArtifactErrors.notFound();
        }
        storages.insert(attachmentId, seq, highest + 1, storageKey, keyId, now());
        return highest + 1;
    }
}
