// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.domain.glossary;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One reviewed term: exactly these seven members, whichever store holds it. The content digest is made over the entry's JSON, which has always had the member
 * {@code active} (true or false); the status is how a store holds it, and {@link #active()} is what the JSON says, so the digest does not depend on the storage.
 */
public record GlossaryEntry(String entryId, String canonicalForm, GlossaryPolicy policy, String sourceRef, int priority, @JsonIgnore GlossaryEntryStatus status, List<GlossaryForm> forms) {
    /** Whether the glossary uses this entry. */
    @JsonProperty("active")
    public boolean active() {
        return status == GlossaryEntryStatus.ACTIVE;
    }
}
