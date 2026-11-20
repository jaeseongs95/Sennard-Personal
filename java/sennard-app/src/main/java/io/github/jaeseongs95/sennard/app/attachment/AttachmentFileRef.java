// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.attachment;

/**
 * What a record that points at one file of an attachment holds: the id of the attachment (a bundle of files) and the number of the file in it, from 0, in the order of the files of
 * {@code artifact_begin}. It is the whole key of a file; an attachment of one file is pointed at with {@code seq} 0.
 */
public record AttachmentFileRef(String attachmentId, int seq) {
}
