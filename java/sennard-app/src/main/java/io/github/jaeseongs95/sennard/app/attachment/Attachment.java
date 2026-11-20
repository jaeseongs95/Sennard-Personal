// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.attachment;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * One row of {@code sennard.attachment}: a kept attachment, which is a bundle of one or more files, a record that is inserted and never changed. {@code createdBy} is the author. A
 * record that points at attachments carries this id only (and the number of a file when it points at one file of the bundle). The files are the rows of {@link AttachmentFile} and the
 * stored copies of each file the rows of {@link AttachmentStorage}; there is no column for the bytes.
 */
@Table(schema = "sennard", value = "attachment")
public record Attachment(@Id String id, String createdBy, Instant createdAt) {
}
