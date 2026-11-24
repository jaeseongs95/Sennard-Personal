// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.attachment;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.domain.error.SennardException;

/**
 * The upkeep of the attachment folder and its rows (the design's 4.3 and 7), which no request is waiting for: the start check, the cleaner, the comparison of the folder with the
 * rows, the re-encryption and the keys. It is an entrance: it does the disk work itself and calls the use cases of {@link AttachmentService} one at a time, each its own transaction.
 *
 * <p>Two jobs run on their own and are kept apart, because they cost differently. The cleaner ({@link #reap}, every {@code reaper-interval}, one minute by default) only asks the database
 * for expired staging rows and deletes them: it never lists the folder and asks no per-file question. Completed rows keep the original begin result. The comparison of the folder with the rows
 * ({@link #reconcileFolder}) looks at every file of the folder with two to four lookups each. It runs once when the store is ready after the start, every
 * {@code reconcile-interval} (24 hours by default) and when {@code sennard attachments purge-orphans} is run without {@code --yes}.
 *
 * <ul>
 * <li>An upload whose time is over is deleted by one conditional statement; its file only after that statement deleted exactly one row.</li>
 * <li>A file that no row names is not deleted: after {@code orphan-grace} the comparison moves it to the quarantine folder, because a database restored from an older backup makes sound
 * files look like that. A quarantined file whose storage key has a row again is moved back. Emptying the quarantine folder is the explicit {@link #purgeQuarantine}.</li>
 * <li>A file that an attachment's later file replaced (after a re-encryption) is quarantined the same way, {@code orphan-grace} after the later file was written, so a read that began with
 * the old row does not meet a file that is gone.</li>
 * <li>An attachment whose file is missing or fails its tags, hash or size is reported, never deleted.</li>
 * </ul>
 */
@Component
public class AttachmentMaintenance {
    /** A batch of the cleaner's: how many uploads it looks at in one round before it takes the rest in the next. */
    static final int BATCH = 100;
    private static final Logger LOG = LoggerFactory.getLogger(AttachmentMaintenance.class);

    /** The number of expired staging rows the cleaner deleted with their files. */
    public record Reaped(int expiredUploads) {
    }

    /** What a comparison of the folder with the rows did: the files it moved to the quarantine folder and the quarantined files it moved back. */
    public record Reconciled(int quarantined, int restored) {
    }

    /**
     * One thing the check found: what is wrong, which attachment or upload it is about ({@code id}), the number of the file in the attachment ({@code seq}, null when the finding is
     * not about one file of an attachment) and which detail ({@code reason} of the failure) tells it. The attachment id and the number are kept apart, never joined into one text.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Finding(String kind, String id, Integer seq, String detail) {
        /** A finding that is not about one file of an attachment. */
        public Finding(String kind, String id, String detail) {
            this(kind, id, null, detail);
        }
    }

    private final AttachmentService service;
    private final AttachmentFileStore store;
    private final AttachmentKeys keys;
    private final AttachmentProperties properties;
    private final Clock clock;

    public AttachmentMaintenance(AttachmentService service, AttachmentFileStore store, AttachmentKeys keys, AttachmentProperties properties, Clock clock) {
        this.service = service;
        this.store = store;
        this.keys = keys;
        this.properties = properties;
        this.clock = clock;
    }

    // ------------------------------------------------------------------------------------------------------------ the start

    /**
     * The start check (the design's 4.3, edition 11): the folders are made, or checked, as the owner's only; the first key is made when there is none; and the key file of the active key
     * and of every key that a current row or an open upload points at is read and judged the way a request judges it ({@link AttachmentKeys#requireUsable}): a folder in a key's place is
     * ARTIFACT_STORAGE_UNSAFE, a file that is missing, empty or not 256 bits is ARTIFACT_KEY_MISSING. A start that cannot hold this fails here and not at the first upload or read.
     */
    public void prepare() {
        store.prepare();
        keys.provision();
        for (String keyId : service.keyIdsInUse()) {
            keys.requireUsable(keyId);
        }
    }

    // ------------------------------------------------------------------------------------------------------------ the cleaner

    /**
     * One round of the cleaner, at the time of the injected clock. Each expired staging row is deleted by a conditional statement, followed by its file. The cleaner does not list the
     * folder or compare it with the rows ({@link #reconcileFolder}).
     */
    public Reaped reap() {
        int expired = 0;
        for (UploadCandidates.Candidate candidate : service.expiredUploads(BATCH)) {
            if (service.deleteExpired(candidate.id())) {
                store.delete(candidate.storageKey());
                expired++;
            }
        }
        return new Reaped(expired);
    }

    // ------------------------------------------------------------------------------------------------------------ the comparison of the folder with the rows

    /**
     * Compares the folder with the rows (the design's 7), at the time of the injected clock: a file that no row names, and a file that a later file of its attachment replaced, is moved
     * to the quarantine folder once {@code orphan-grace} has passed (never before, never deleted); a quarantined file whose storage key has a row again is moved back. It asks the rows two
     * to four questions for every file of the folder, so it is not part of the cleaner's round: it runs when the store is ready after the start, every {@code reconcile-interval}, and by
     * {@code sennard attachments purge-orphans} without {@code --yes}.
     */
    public Reconciled reconcileFolder() {
        Instant now = clock.instant();
        int quarantined = 0;
        Duration grace = properties.orphanGrace();
        for (String storageKey : store.storedKeys()) {
            AttachmentService.KeyUse use = service.useOf(storageKey);
            Instant since = switch (use.kind()) {
                case IN_USE -> null;
                case UNREFERENCED -> store.modifiedAt(storageKey);
                case REPLACED -> use.replacedAt();
            };
            if (since != null && !now.isBefore(since.plus(grace)) && store.quarantine(storageKey)) {
                quarantined++;
            }
        }
        int restored = 0;
        for (String storageKey : store.quarantinedKeys()) {
            if (service.useOf(storageKey).kind() == AttachmentService.UseKind.IN_USE && store.restore(storageKey)) {
                restored++;
            }
        }
        return new Reconciled(quarantined, restored);
    }

    /** Empties the quarantine folder (the explicit command {@code --purge-orphans}); the names of the files deleted are written to the log as the audit of the deletion. */
    public List<String> purgeQuarantine() {
        List<String> purged = store.purgeQuarantine();
        LOG.info("ATTACHMENT_QUARANTINE_PURGED {} files: {}", purged.size(), purged);
        return purged;
    }

    // ------------------------------------------------------------------------------------------------------------ the check

    /**
     * Compares the rows with the folder (the design's 7, {@code attachments check}): every attachment's current file is opened and verified whole (tags, end mark, size, sha256), the
     * open uploads' files are looked for, and a file that no row names is listed. Nothing is deleted or moved. A restored backup passes when the list is empty.
     */
    public List<Finding> check() {
        List<Finding> findings = new ArrayList<>();
        for (AttachmentService.Stored stored : service.stored()) {
            Attachment attachment = stored.attachment();
            AttachmentFile file = stored.file();
            AttachmentStorage copy = stored.current();
            if (file == null) {
                findings.add(new Finding("attachment-without-file", attachment.id(), "no file row"));
                continue;
            }
            // A finding about a file names the attachment and the number of the file in it.
            String id = attachment.id();
            int seq = file.seq();
            if (copy == null) {
                findings.add(new Finding("attachment-without-file", id, seq, "no stored copy row"));
            } else if (!store.exists(copy.storageKey())) {
                findings.add(new Finding("file-missing", id, seq, "the current copy is not in the folder"));
            } else {
                try {
                    store.verify(copy.storageKey(), copy.keyId(), file.sizeBytes(), file.contentSha256());
                } catch (SennardException damaged) {
                    LOG.debug("ATTACHMENT_CHECK_FINDING {} file {} {}", id, seq, damaged.code().name(), damaged);
                    findings.add(new Finding("damaged", id, seq, damaged.code().name()));
                }
            }
        }
        for (UploadStaging upload : service.openUploads()) {
            long size = store.sizeOf(upload.storageKey());
            if (upload.storedBytes() > 0 && size < upload.storedBytes()) {
                findings.add(new Finding(size < 0 ? "file-missing" : "damaged", upload.id(), "the file of an upload in progress"));
            }
        }
        for (String storageKey : store.storedKeys()) {
            if (service.useOf(storageKey).kind() == AttachmentService.UseKind.UNREFERENCED) {
                findings.add(new Finding("file-without-row", storageKey, "no row names this file"));
            }
        }
        return List.copyOf(findings);
    }

    // ------------------------------------------------------------------------------------------------------------ keys

    /** Makes a new key file (the design's 4.3: a key is replaced by making a key and naming it {@code active-key-id}). */
    public void createKey(String keyId) {
        keys.create(keyId);
    }

    /**
     * Writes every current file that was written with {@code fromKeyId} again under the active key (the design's 4.3): the entrance reads and verifies the old file and writes the new one,
     * and the use case adds the row. A file that cannot be written again stops the run and is reported by its failure; what was done before it stays done (each file is its own
     * step), and the run can be made again. The old keys are not deleted: that is for the owner, once no current row points at them.
     *
     * @return how many attachments were written again
     */
    public int rekey(String fromKeyId) {
        if (fromKeyId.equals(keys.activeKeyId())) {
            // A misuse of the command, not a storage path that cannot be used: an input error (ARTIFACT_STORAGE_UNSAFE is the storage path only, design edition 11).
            throw ArtifactErrors.invalidArgument("--from", "the key is the active key; a file is written again under the active key, so name the key to leave");
        }
        int done = 0;
        for (AttachmentStorage copy : service.currentCopiesOfKey(fromKeyId)) {
            AttachmentFile file = service.filesOf(copy.attachmentId()).stream().filter(candidate -> candidate.seq() == copy.seq()).findFirst().orElseThrow(ArtifactErrors::notFound);
            AttachmentFileStore.Written written = store.rewrite(copy.storageKey(), copy.keyId(), file.sizeBytes(), file.contentSha256());
            try {
                service.addCopy(copy.attachmentId(), copy.seq(), written.storageKey(), written.keyId());
            } catch (RuntimeException notAdded) {
                // The row was not added (a lost race of two re-encryptions, or the store): the new file has no row, so it is removed now; the old row stays valid.
                LOG.warn("ATTACHMENT_REKEY_NOT_ADDED {}/{}", copy.attachmentId(), copy.seq(), notAdded);
                store.delete(written.storageKey());
                throw notAdded;
            }
            done++;
        }
        return done;
    }
}
