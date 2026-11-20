// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.domain.glossary;

import java.util.List;

/** What a catalog holds: the glossary's id and version, and its entries in no particular order. */
public record GlossaryContent(String id, String version, List<GlossaryEntry> entries) {
}
