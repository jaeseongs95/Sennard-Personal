// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store;

/**
 * The shape of a stored {@code checkpoint_context} request receipt, written in its {@code kind} field by the constant's name: the answer of a
 * request that saved a memory ({@code CHECKPOINT}), or what is left of it after the memory was purged ({@code PURGED_REQUEST}).
 */
public enum RequestReceiptKind {
    CHECKPOINT,
    PURGED_REQUEST
}
