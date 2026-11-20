// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.operation;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** The receipts. The {@code Repository} marker: only what is declared here is offered (see {@code BoardSessionRepository}). */
public interface OperationReceiptRepository extends Repository<OperationReceipt, OperationReceiptKey> {
    Optional<OperationReceipt> findByKeyToolAndKeyScopeAndKeyOperationKey(String tool, String scope, String operationKey);

    /** Receipts written before {@code before} (UTC); returns how many were removed. */
    long deleteByCreatedAtLessThan(Instant before);

    /** The first write of a key, one INSERT statement; a second one for the same (tool, scope, key) meets the primary key. */
    @Modifying
    @Query("INSERT INTO sennard.operation_receipt (tool, scope, operation_key, request_digest, response, created_at) VALUES (:tool, :scope, :operationKey, :requestDigest, :response, :createdAt)")
    int insert(@Param("tool") String tool, @Param("scope") String scope, @Param("operationKey") String operationKey, @Param("requestDigest") String requestDigest, @Param("response") String response,
            @Param("createdAt") Instant createdAt);

    /** Replaces the response of the receipt of this operation (the same tool, scope, key and content); returns how many rows it changed, one when the receipt is there. */
    @Modifying
    @Query("UPDATE sennard.operation_receipt SET response = :response WHERE tool = :tool AND scope = :scope AND operation_key = :operationKey AND request_digest = :requestDigest")
    int replaceResponse(@Param("tool") String tool, @Param("scope") String scope, @Param("operationKey") String operationKey, @Param("requestDigest") String requestDigest,
            @Param("response") String response);
}
