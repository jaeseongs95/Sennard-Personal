// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.operation;

/** The key of a receipt: the tool, the scope (the caller's session reference, or the global scope), then the caller's operationKey. */
public record OperationReceiptKey(String tool, String scope, String operationKey) {
}
