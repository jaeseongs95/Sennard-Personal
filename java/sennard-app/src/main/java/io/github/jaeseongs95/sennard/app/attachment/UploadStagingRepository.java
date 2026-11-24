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
 * A row per file of a bundle, kept after completion to replay the original begin. The {@code Repository} marker: only what is declared here is offered. Every change is one conditional statement, and what
 * decides a race is the number of rows it changed, never a lock taken by hand: the repository joins the transaction of the service method that calls it and opens none.
 */
public interface UploadStagingRepository extends Repository<UploadStaging, String> {
    String COLUMNS = "id, attachment_id, seq, owner, operation_key, file_name, media_type, declared_size, declared_sha256, received_bytes, stored_bytes, next_sequence, last_chunk_sha256, "
            + "storage_key, key_id, state, expires_at, created_at, updated_at";

    Optional<UploadStaging> findById(String id);

    /** The files one begin made, in the order of their numbers: the rows of one operation key of an author. */
    @Query("SELECT " + COLUMNS + " FROM sennard.upload_staging WHERE owner = :owner AND operation_key = :operationKey ORDER BY seq")
    List<UploadStaging> findByOwnerAndOperationKey(@Param("owner") String owner, @Param("operationKey") String operationKey);

    /** The files of the bundle an attachment id names, in the order of their numbers. */
    @Query("SELECT " + COLUMNS + " FROM sennard.upload_staging WHERE attachment_id = :attachmentId ORDER BY seq")
    List<UploadStaging> findByAttachmentId(@Param("attachmentId") String attachmentId);

    /** The bundles in progress of an author: what {@code max-open-per-owner} counts (a bundle of several files is one). */
    @Query("SELECT COUNT(DISTINCT attachment_id) FROM sennard.upload_staging WHERE owner = :owner AND state = 'STAGING'")
    long countOpen(@Param("owner") String owner);

    /** The sizes the open uploads declared, together: what {@code staging-budget-bytes} and {@code attachment-budget-bytes} count. */
    @Query("SELECT COALESCE(SUM(declared_size), 0) FROM sennard.upload_staging WHERE state = 'STAGING'")
    long declaredOpenBytes();

    @Modifying
    @Query("INSERT INTO sennard.upload_staging (id, attachment_id, seq, owner, operation_key, file_name, media_type, declared_size, declared_sha256, received_bytes, stored_bytes, "
            + "next_sequence, last_chunk_sha256, storage_key, key_id, state, expires_at, created_at, updated_at) "
            + "VALUES (:id, :attachmentId, :seq, :owner, :operationKey, :fileName, :mediaType, :declaredSize, :declaredSha256, 0, 0, 0, NULL, :storageKey, :keyId, 'STAGING', :expiresAt, :now, :now)")
    int insert(@Param("id") String id, @Param("attachmentId") String attachmentId, @Param("seq") int seq, @Param("owner") String owner, @Param("operationKey") String operationKey,
            @Param("fileName") String fileName, @Param("mediaType") String mediaType, @Param("declaredSize") long declaredSize, @Param("declaredSha256") String declaredSha256,
            @Param("storageKey") String storageKey, @Param("keyId") String keyId, @Param("expiresAt") Instant expiresAt, @Param("now") Instant now);

    /**
     * Takes a chunk into the row (the design's 4.4, step 4): applied only when the row is still waiting for this chunk. The expiry is counted again from now.
     *
     * @return 1 when this caller took the chunk in; 0 when another did, or the upload is gone or done
     */
    @Modifying
    @Query("UPDATE sennard.upload_staging SET stored_bytes = :storedBytes, received_bytes = :receivedBytes, next_sequence = :nextSequence, last_chunk_sha256 = :chunkSha256, "
            + "expires_at = :expiresAt, updated_at = :now WHERE id = :id AND next_sequence = :expectedSequence AND state = 'STAGING'")
    int takeInChunk(@Param("id") String id, @Param("expectedSequence") long expectedSequence, @Param("storedBytes") long storedBytes, @Param("receivedBytes") long receivedBytes,
            @Param("nextSequence") long nextSequence, @Param("chunkSha256") String chunkSha256, @Param("expiresAt") Instant expiresAt, @Param("now") Instant now);

    /**
     * Counts the expiry of every file of the bundle again from now: a bundle lives and dies as a whole, so a file that was sent long ago does not expire, and is not cleaned away, while
     * the others are still being sent.
     */
    @Modifying
    @Query("UPDATE sennard.upload_staging SET expires_at = :expiresAt, updated_at = :now WHERE attachment_id = :attachmentId AND state = 'STAGING'")
    int extendBundle(@Param("attachmentId") String attachmentId, @Param("expiresAt") Instant expiresAt, @Param("now") Instant now);

    /** The file now holds the end mark: {@code stored_bytes} is its whole length. Applied only while the upload is in progress and has not expired. */
    @Modifying
    @Query("UPDATE sennard.upload_staging SET state = 'COMPLETED', stored_bytes = :storedBytes, updated_at = :now WHERE id = :id AND state = 'STAGING' AND expires_at > :now")
    int complete(@Param("id") String id, @Param("storedBytes") long storedBytes, @Param("now") Instant now);

    /** The uploads in progress, for the check that compares the rows with the folder. */
    @Query("SELECT " + COLUMNS + " FROM sennard.upload_staging WHERE state = 'STAGING' ORDER BY id")
    List<UploadStaging> open();

    /**
     * The cleaner's delete of an expired upload (the design's 7): the whole condition is looked at again in this statement, so an upload that a chunk gave more time, or that a finish
     * completed, since it was listed is not deleted. The file is deleted only after a 1.
     */
    @Modifying
    @Query("DELETE FROM sennard.upload_staging WHERE id = :id AND state = 'STAGING' AND expires_at <= :now")
    int deleteExpired(@Param("id") String id, @Param("now") Instant now);

    @Query("SELECT COUNT(*) FROM sennard.upload_staging WHERE storage_key = :storageKey AND state = 'STAGING'")
    long countStagingByKey(@Param("storageKey") String storageKey);

    /** The key ids the uploads in progress are written with: the start check needs every one of their key files. */
    @Query("SELECT DISTINCT key_id FROM sennard.upload_staging WHERE state = 'STAGING' ORDER BY key_id")
    List<String> openKeyIds();
}
