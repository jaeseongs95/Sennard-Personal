// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.domain.messaging;

/** One acknowledged message with the claim receipt its claim was given. */
public record ClaimReceipt(String messageId, String claimReceipt) {
}
