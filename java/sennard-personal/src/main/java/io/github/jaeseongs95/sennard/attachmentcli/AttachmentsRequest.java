// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.attachmentcli;

/** What {@code sennard attachments} is asked to do: one record per action, with what that action takes. The command line is read by the CLI; this is what its context runs. */
public sealed interface AttachmentsRequest {
    /** Compares the rows with the folder; nothing is deleted or moved. */
    record Check() implements AttachmentsRequest {
    }

    /** Writes every current file that was written with {@code fromKeyId} again under the active key. */
    record Rekey(String fromKeyId) implements AttachmentsRequest {
    }

    /** Makes a new key file named {@code keyId}. */
    record Keygen(String keyId) implements AttachmentsRequest {
    }

    /**
     * Without {@code confirmed}: compares the folder with the rows (files past the grace with no row, or replaced, move to the quarantine folder; files that got their rows back move
     * back) and lists the quarantine folder's files. With {@code confirmed}: empties the quarantine folder and lists what it deleted, without comparing first.
     */
    record PurgeOrphans(boolean confirmed) implements AttachmentsRequest {
    }
}
