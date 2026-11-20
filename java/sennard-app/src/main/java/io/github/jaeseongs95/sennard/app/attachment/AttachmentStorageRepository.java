// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.attachment;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * The stored copies of the files. A record: rows are inserted and read, never updated or deleted. The current copy of a file is the row of its highest {@code rev}; the order is the
 * number, not the time (two rows may share a time). The repository joins the transaction of the service method that calls it.
 */
public interface AttachmentStorageRepository extends Repository<AttachmentStorage, AttachmentStorage.Key> {
    String COLUMNS = "attachment_id, seq, rev, storage_key, key_id, created_at";
    /** The columns of the current copy of every file, and the rest of the statement up to its condition: a row {@code f} whose rev is the highest of its file. */
    String CURRENT = "f.attachment_id, f.seq, f.rev, f.storage_key, f.key_id, f.created_at FROM sennard.attachment_storage f "
            + "WHERE f.rev = (SELECT MAX(g.rev) FROM sennard.attachment_storage g WHERE g.attachment_id = f.attachment_id AND g.seq = f.seq)";

    @Modifying
    @Query("INSERT INTO sennard.attachment_storage (attachment_id, seq, rev, storage_key, key_id, created_at) VALUES (:attachmentId, :seq, :rev, :storageKey, :keyId, :createdAt)")
    int insert(@Param("attachmentId") String attachmentId, @Param("seq") int seq, @Param("rev") int rev, @Param("storageKey") String storageKey, @Param("keyId") String keyId,
            @Param("createdAt") Instant createdAt);

    /** The highest {@code rev} of a file, -1 when it has no copy. */
    @Query("SELECT COALESCE(MAX(rev), -1) FROM sennard.attachment_storage WHERE attachment_id = :attachmentId AND seq = :seq")
    int highestRev(@Param("attachmentId") String attachmentId, @Param("seq") int seq);

    /** The copy the file is read from: its row of the highest {@code rev}. */
    @Query("SELECT " + COLUMNS + " FROM sennard.attachment_storage WHERE attachment_id = :attachmentId AND seq = :seq "
            + "AND rev = (SELECT MAX(g.rev) FROM sennard.attachment_storage g WHERE g.attachment_id = :attachmentId AND g.seq = :seq)")
    Optional<AttachmentStorage> current(@Param("attachmentId") String attachmentId, @Param("seq") int seq);

    /** One copy of a file, by its number: the row after a replaced copy says when the replacement was written. */
    @Query("SELECT " + COLUMNS + " FROM sennard.attachment_storage WHERE attachment_id = :attachmentId AND seq = :seq AND rev = :rev")
    Optional<AttachmentStorage> at(@Param("attachmentId") String attachmentId, @Param("seq") int seq, @Param("rev") int rev);

    @Query("SELECT " + COLUMNS + " FROM sennard.attachment_storage WHERE storage_key = :storageKey")
    Optional<AttachmentStorage> byStorageKey(@Param("storageKey") String storageKey);

    /** The current copy of every file of every attachment. */
    @Query("SELECT " + CURRENT + " ORDER BY f.attachment_id, f.seq")
    List<AttachmentStorage> currents();

    /** The current copies written with a key: what a re-encryption from that key has to do. */
    @Query("SELECT " + CURRENT + " AND f.key_id = :keyId ORDER BY f.attachment_id, f.seq")
    List<AttachmentStorage> currentsOfKey(@Param("keyId") String keyId);

    /** The key ids the current copies are written with: the start check needs every one of their key files. */
    @Query("SELECT DISTINCT f.key_id FROM sennard.attachment_storage f "
            + "WHERE f.rev = (SELECT MAX(g.rev) FROM sennard.attachment_storage g WHERE g.attachment_id = f.attachment_id AND g.seq = f.seq) ORDER BY f.key_id")
    List<String> currentKeyIds();

    @Query("SELECT COUNT(*) FROM sennard.attachment_storage WHERE storage_key = :storageKey")
    long countByKey(@Param("storageKey") String storageKey);
}
