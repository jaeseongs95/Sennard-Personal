// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.attachment;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * The files of the bundles. A record: rows are inserted and read, never updated or deleted. The repository joins the transaction of the service method that calls it.
 */
public interface AttachmentFileRepository extends Repository<AttachmentFile, AttachmentFile.Key> {
    String COLUMNS = "attachment_id, seq, file_name, media_type, size_bytes, content_sha256";

    @Modifying
    @Query("INSERT INTO sennard.attachment_file (attachment_id, seq, file_name, media_type, size_bytes, content_sha256) "
            + "VALUES (:attachmentId, :seq, :fileName, :mediaType, :sizeBytes, :contentSha256)")
    int insert(@Param("attachmentId") String attachmentId, @Param("seq") int seq, @Param("fileName") String fileName, @Param("mediaType") String mediaType,
            @Param("sizeBytes") long sizeBytes, @Param("contentSha256") String contentSha256);

    /** The files of a bundle, in the order of their numbers. */
    @Query("SELECT " + COLUMNS + " FROM sennard.attachment_file WHERE attachment_id = :attachmentId ORDER BY seq")
    List<AttachmentFile> ofAttachment(@Param("attachmentId") String attachmentId);

    /** One file of a bundle, by its number. */
    @Query("SELECT " + COLUMNS + " FROM sennard.attachment_file WHERE attachment_id = :attachmentId AND seq = :seq")
    Optional<AttachmentFile> at(@Param("attachmentId") String attachmentId, @Param("seq") int seq);

    /** Every file of every attachment, in the order of the attachment and the number, for the check that compares the rows with the folder. */
    @Query("SELECT " + COLUMNS + " FROM sennard.attachment_file ORDER BY attachment_id, seq")
    List<AttachmentFile> all();
}
