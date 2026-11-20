// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.attachment;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Embedded;
import org.springframework.data.relational.core.mapping.Table;

/**
 * One row of {@code sennard.attachment_storage}: a stored copy of one file of a bundle, a record that is inserted and never changed. The key is the triple (attachment, seq, rev) with
 * no surrogate key and no foreign key. {@code rev} 0 is the copy the finish closed; a re-encryption writes the next number. The current copy of a file is the row of its highest
 * {@code rev} (the order is the number, not the time: two rows may share a time).
 */
@Table(schema = "sennard", value = "attachment_storage")
public record AttachmentStorage(@Id @Embedded(onEmpty = Embedded.OnEmpty.USE_NULL) Key key, String storageKey, String keyId, Instant createdAt) {
    /** The key of a row: the attachment, the number of the file in its bundle, then the number of the copy. */
    public record Key(String attachmentId, int seq, int rev) {
    }

    public String attachmentId() {
        return key.attachmentId();
    }

    public int seq() {
        return key.seq();
    }

    public int rev() {
        return key.rev();
    }
}
