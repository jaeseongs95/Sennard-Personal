// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.attachment;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Embedded;
import org.springframework.data.relational.core.mapping.Table;

/**
 * One row of {@code sennard.attachment_file}: one file of a bundle, a record that is inserted and never changed. The key is the pair (attachment, seq), which is the table's composite
 * primary key with no surrogate key; there is no foreign key to the attachment. The facts are those of the file as it was sent: its name (the path under a review bundle's folder, for a
 * file of one), its media type, the number of plain bytes and their sha256 (lower-case hex). Where the bytes are stored is {@link AttachmentStorage}.
 */
@Table(schema = "sennard", value = "attachment_file")
public record AttachmentFile(@Id @Embedded(onEmpty = Embedded.OnEmpty.USE_NULL) Key key, String fileName, String mediaType, long sizeBytes, String contentSha256) {
    /** The key of a row: the attachment, then the number of the file in its bundle (the order of the begin's file list, from 0). */
    public record Key(String attachmentId, int seq) {
    }

    public String attachmentId() {
        return key.attachmentId();
    }

    public int seq() {
        return key.seq();
    }
}
