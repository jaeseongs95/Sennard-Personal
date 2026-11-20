// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.attachmentcli;

import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.app.attachment.AttachmentFileStore;
import io.github.jaeseongs95.sennard.app.attachment.AttachmentMaintenance;

/**
 * The entrance of {@code sennard attachments}: it hands each request to the upkeep of the attachment folder ({@link AttachmentMaintenance}), which does the disk work and calls the use
 * cases of {@code AttachmentService}, each its own transaction. This class opens no transaction. It is a component of the command's own context
 * ({@link AttachmentsConfiguration}) and is in a package that the server's component scan does not cover, so the server has no such bean.
 */
@Component
public final class AttachmentsEntrance {
    private final AttachmentMaintenance maintenance;
    private final AttachmentFileStore store;

    public AttachmentsEntrance(AttachmentMaintenance maintenance, AttachmentFileStore store) {
        this.maintenance = maintenance;
        this.store = store;
    }

    /** The switch has no default: an action added to {@link AttachmentsRequest} does not compile until it is handled here. */
    public AttachmentsResult run(AttachmentsRequest request) {
        return switch (request) {
            case AttachmentsRequest.Check check -> new AttachmentsResult.Checked(maintenance.check());
            case AttachmentsRequest.Rekey rekey -> new AttachmentsResult.Rekeyed(rekey.fromKeyId(), maintenance.rekey(rekey.fromKeyId()));
            case AttachmentsRequest.Keygen keygen -> {
                maintenance.createKey(keygen.keyId());
                yield new AttachmentsResult.KeyCreated(keygen.keyId());
            }
            // The purge is the explicit act, and the upkeep writes its audit log. It deletes exactly the files of the quarantine folder that a run without confirmation listed, so a confirmed
            // run does not compare the folder first: a file that the comparison would move now, after the listing, is not deleted unseen.
            // Without confirmation the folder is compared with the rows first (the design's 7: this command is one of the three times the comparison runs, besides the start and the
            // interval), so the list is complete: the files whose grace has passed are moved to the quarantine folder (never deleted) and the files that got their rows back are moved back.
            case AttachmentsRequest.PurgeOrphans purge -> {
                if (purge.confirmed()) {
                    yield new AttachmentsResult.Orphans(maintenance.purgeQuarantine(), true);
                }
                maintenance.reconcileFolder();
                yield new AttachmentsResult.Orphans(store.quarantinedKeys(), false);
            }
        };
    }
}
