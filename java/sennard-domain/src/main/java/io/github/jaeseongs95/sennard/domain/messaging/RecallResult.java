// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.domain.messaging;

/** The answer to a recall. {@code reason} is why nothing was taken back (null when the message was recalled); {@code duplicate} is a repeated recall of a recalled message. */
public record RecallResult(boolean recalled, boolean duplicate, RecallRefusal reason, MessageStatus status) {
}
