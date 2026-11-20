// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.messaging;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * The columns of {@code sennard.session_ref} that session messaging reads and keeps: the session's host (for the sender's status), the drafts and receipts the
 * session made as a sender, and how many messages wait in its queue (an upper bound the lease renewal reports: the identity area reads it). The table belongs to the
 * identity area (sennard-core); this class maps only those columns. {@code next_message_seq} of the same table is not kept any
 * more: the order is the {@code seq} of the queue rows, made in Java. The key is the address of the session ({@code session_ref}).
 */
@Table(schema = "sennard", value = "session_ref")
public record SessionRef(@Id String sessionRef, String host, long senderDrafts, long senderReceipts, long pendingCount) {
}
