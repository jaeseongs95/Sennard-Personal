// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * An independent review of a gated convergence root ({@code convergence-review.v1.schema.json}): what the reviewer found a proposed frame change does to the
 * task's meaning and where the root goes next. A caller supplies it without {@code observedReviewer}, which the server writes from the call; the schema is
 * checked on the caller's input, and the store keeps the record as JSON text. {@code proposedFrame} is the key the schema requires, null when the review
 * proposes none.
 *
 * <p>{@code reviewedAt} is the instant the caller named; it is written in UTC as {@code Instant.toString()} gives it, whatever offset or fraction the caller used.
 */
public record ConvergenceReview(String schemaVersion, String reviewId, String rootId, long rootRevision, long epoch, String reviewerActorId, FreshContext freshContext,
        @JsonInclude(JsonInclude.Include.NON_NULL) ObservedReviewer observedReviewer, ReviewClassification classification, Comparability comparability, ReviewRoute route,
        @JsonInclude(JsonInclude.Include.ALWAYS) ConvergenceFrame proposedFrame, List<String> evidenceRefs, List<String> userApprovalRefs, Instant reviewedAt) {

    /** Whether the reviewer declares the review was made in a fresh context, and where that is evidenced. */
    public record FreshContext(boolean confirmed, String evidenceRef) {
    }

    /** Whether the frame change leaves results comparable, and why. */
    public record Comparability(boolean comparable, String rationale) {
    }

    /** The observed caller that recorded the review, with the independence Sennard could check. */
    public record ObservedReviewer(String actor, List<String> parentChain, Provenance provenance, Independence independence) {

        static ObservedReviewer of(ObservedCaller caller, Independence independence) {
            return new ObservedReviewer(caller.actor(), caller.parentChain(), caller.provenance(), independence);
        }
    }

    ConvergenceReview withObservedReviewer(ObservedReviewer observed) {
        return new ConvergenceReview(schemaVersion, reviewId, rootId, rootRevision, epoch, reviewerActorId, freshContext, observed, classification, comparability, route, proposedFrame, evidenceRefs,
                userApprovalRefs, reviewedAt);
    }
}
