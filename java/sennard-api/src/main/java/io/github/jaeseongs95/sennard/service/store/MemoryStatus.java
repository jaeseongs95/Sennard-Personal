// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store;

/**
 * The status an agent gives its task memory in {@code checkpoint_context}. Only an {@code ACTIVE} memory is kept by the state cleanup; the
 * checkpoint contract, the stored memory and the restore summaries all spell the constant's name.
 */
public enum MemoryStatus {
    ACTIVE(true),
    PAUSED(false),
    COMPLETED(false);

    private final boolean kept;

    MemoryStatus(boolean kept) {
        this.kept = kept;
    }

    /** The cleanup keeps the memory of a task in this status however old it is. */
    public boolean isKept() {
        return kept;
    }
}
