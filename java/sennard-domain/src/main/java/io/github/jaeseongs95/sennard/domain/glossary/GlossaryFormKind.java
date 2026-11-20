// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.domain.glossary;

import com.fasterxml.jackson.annotation.JsonProperty;

/** What a form is to its entry: its canonical spelling, an accepted or preferred variant, or one to avoid. The JSON value is the lower-case word; the database column holds the constant's name. */
public enum GlossaryFormKind {
    @JsonProperty("canonical") CANONICAL, @JsonProperty("alias") ALIAS, @JsonProperty("discouraged") DISCOURAGED
}
