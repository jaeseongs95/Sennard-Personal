// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import java.util.HashSet;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.json.JsonMapper;

/**
 * The input of {@code release_convergence_root}. The published tool schema is the only contract of it, so the rules that schema states are the constraints of
 * the record, which a {@code Validator} checks after Jackson bound it: a root ID and a reason that are not blank, a reason of at most 2000 characters, a
 * revision that is not negative, and at most 32 distinct, non-blank approval references of at most 2000 characters. {@link #reader} binds it: a key the tool
 * does not take is refused, and so is a field of another JSON type than the tool schema names (a number where a text is, a text or a number with a fraction
 * where the revision is an integer), which Jackson would otherwise convert.
 */
public record ReleaseRequest(@JsonDeserialize(using = StrictInput.Text.class) @NotBlank String rootId, @JsonDeserialize(using = StrictInput.Integral.class) @NotNull @PositiveOrZero Long expectedRevision,
        @JsonDeserialize(using = StrictInput.Text.class) @NotBlank @Size(max = 2000) String reason,
        @JsonDeserialize(contentUsing = StrictInput.Item.class) @Size(max = 32) List<@NotBlank @Size(max = 2000) String> userApprovalRefs) implements RootRequest {

    /** A request that cites no approval cites an empty list. */
    public ReleaseRequest {
        userApprovalRefs = userApprovalRefs == null ? List.of() : userApprovalRefs;
    }

    /** The reader that binds a release input: strict about the keys and about the revision being an integer. */
    static ObjectReader reader(JsonMapper mapper) {
        return mapper.readerFor(ReleaseRequest.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).without(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    }

    /** The approval references name each record once. */
    @JsonIgnore
    @AssertTrue
    public boolean isApprovalRefsDistinct() {
        return new HashSet<>(userApprovalRefs).size() == userApprovalRefs.size();
    }

    /** The request as the user is shown it and as it runs: the reason without the space around it. */
    ReleaseRequest stripped() {
        return new ReleaseRequest(rootId, expectedRevision, reason.strip(), userApprovalRefs);
    }
}
