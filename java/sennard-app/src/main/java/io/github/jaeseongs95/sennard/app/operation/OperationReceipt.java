// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.operation;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Embedded;
import org.springframework.data.relational.core.mapping.Table;

/**
 * One row of {@code sennard.operation_receipt}: the first answer that succeeded for one operationKey of one tool and scope, with the digest of the request it answered. The row is
 * written by {@link OperationReceiptRepository#insert} and read by {@link OperationReceiptRepository#findByKeyToolAndKeyScopeAndKeyOperationKey}; no method of the aggregate is used to save it (the table has no
 * version column, and a receipt is never changed after it is written).
 *
 * <p>The time is an {@code Instant}: the column is {@code TIMESTAMP} without a time zone and holds its UTC fields ({@code UtcTimestampColumns}).
 */
@Table(schema = "sennard", value = "operation_receipt")
public record OperationReceipt(@Id @Embedded(onEmpty = Embedded.OnEmpty.USE_NULL) OperationReceiptKey key, String requestDigest, String response, Instant createdAt) {
}
