// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.domain.glossary;

/** Whether the glossary uses an entry. A retired entry stays in the store (its source and its reason are kept) and is never looked up. The database column holds the constant's name. */
public enum GlossaryEntryStatus {
    ACTIVE, RETIRED
}
