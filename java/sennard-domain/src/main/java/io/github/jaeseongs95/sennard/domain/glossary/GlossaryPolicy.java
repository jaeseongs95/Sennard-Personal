// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.domain.glossary;

import java.util.Set;

import com.fasterxml.jackson.annotation.JsonProperty;

/** What the glossary asks of a form, and which kinds of form an entry under that policy may list. The JSON value is the lower-case word; the database column holds the constant's name. */
public enum GlossaryPolicy {
    @JsonProperty("protect") PROTECT(true, Set.of(GlossaryFormKind.CANONICAL)), @JsonProperty("prefer") PREFER(false, Set.of(GlossaryFormKind.ALIAS)),
    @JsonProperty("allow") ALLOW(true, Set.of(GlossaryFormKind.CANONICAL, GlossaryFormKind.ALIAS)), @JsonProperty("avoid") AVOID(false, Set.of(GlossaryFormKind.DISCOURAGED));

    private final boolean listsCanonicalForm;
    private final Set<GlossaryFormKind> kinds;

    GlossaryPolicy(boolean listsCanonicalForm, Set<GlossaryFormKind> kinds) {
        this.listsCanonicalForm = listsCanonicalForm;
        this.kinds = kinds;
    }

    /** Whether an entry under this policy may list a form of this kind. */
    public boolean allows(GlossaryFormKind kind) {
        return kinds.contains(kind);
    }

    /** Whether the entry's canonical spelling is one of its own forms (protect, allow) rather than the spelling its forms are measured against (prefer, avoid). */
    public boolean listsCanonicalForm() {
        return listsCanonicalForm;
    }
}
