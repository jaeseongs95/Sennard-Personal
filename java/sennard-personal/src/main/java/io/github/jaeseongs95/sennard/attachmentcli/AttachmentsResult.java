// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.attachmentcli;

import java.util.List;

import io.github.jaeseongs95.sennard.app.attachment.AttachmentMaintenance.Finding;

/** What {@code sennard attachments} did: the answer of each {@link AttachmentsRequest}. A key's value is in no result: only its id. */
public sealed interface AttachmentsResult {
    /** What the check found; empty when the rows and the folder agree. */
    record Checked(List<Finding> findings) implements AttachmentsResult {
    }

    /** How many attachments were written again, from the key {@code fromKeyId} to the active key. */
    record Rekeyed(String fromKeyId, int rewritten) implements AttachmentsResult {
    }

    /** The id of the key file that was made. */
    record KeyCreated(String keyId) implements AttachmentsResult {
    }

    /** The quarantine folder's files; {@code purged} is true when they were deleted, false when they were only listed. */
    record Orphans(List<String> files, boolean purged) implements AttachmentsResult {
    }
}
