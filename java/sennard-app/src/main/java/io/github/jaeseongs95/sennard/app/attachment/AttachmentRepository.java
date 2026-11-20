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
 * The kept attachments (the bundles). A record: rows are inserted and read, never updated or deleted (an architecture rule checks it), so there is no method that changes one. The
 * repository joins the transaction of the service method that calls it.
 */
public interface AttachmentRepository extends Repository<Attachment, String> {
    Optional<Attachment> findById(String id);

    @Modifying
    @Query("INSERT INTO sennard.attachment (id, created_by, created_at) VALUES (:id, :createdBy, :createdAt)")
    int insert(@Param("id") String id, @Param("createdBy") String createdBy, @Param("createdAt") Instant createdAt);

    /** The bytes of every file of every kept attachment: what {@code attachment-budget-bytes} counts. */
    @Query("SELECT COALESCE(SUM(size_bytes), 0) FROM sennard.attachment_file")
    long keptBytes();

    /** Every attachment, in the order of its id, for the check that compares the rows with the folder. */
    @Query("SELECT id, created_by, created_at FROM sennard.attachment ORDER BY id")
    List<Attachment> all();
}
