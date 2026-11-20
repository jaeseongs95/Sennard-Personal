// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.domain.glossary;

/**
 * Where the reviewed Korean terms are kept: the one thing the glossary asks of its store, so the lookup does not know which database holds them.
 * An implementation throws when it cannot read its store; the lookup answers "unavailable" for that.
 */
public interface GlossaryCatalog {
    GlossaryContent load();
}
