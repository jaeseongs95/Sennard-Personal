// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.domain.glossary;

/** One spelling of an entry and what it is to that entry. */
public record GlossaryForm(String form, GlossaryFormKind kind) {
}
