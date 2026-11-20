// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.github.jaeseongs95.sennard.service.store.RootState;

/**
 * A convergence root ({@code convergence-root.v1.schema.json}): the task and the frame that attempts converge on, the state the root is in and the
 * revision every change to it moves by one. The store keeps it as JSON text and reads its state, epoch and frame digest from the row; the service reads and
 * writes the record through Jackson, and the schema is checked on the way out only.
 *
 * <p>The schema requires {@code parentRootId} as a key that is null when the root replaced none. {@code retryRejections} and {@code release} are written only when
 * there is something to say: no rejection yet, no release yet. A record is immutable, so a change of a root is a new record ({@link #bumped},
 * {@link #withState}, {@link #withRelease}, {@link #withRetryRejection}, {@link #nextEpoch}).
 */
public record ConvergenceRoot(String schemaVersion, String rootId, @JsonInclude(JsonInclude.Include.ALWAYS) String parentRootId, long revision, RootState state, long currentEpoch,
        TaskEnvelope taskEnvelope, ConvergenceFrame frame, String taskDigest, String frameDigest, String workspaceDigest, String controlDigest, String targetDigest, String operationalDigest,
        List<String> userApprovalRefs, Instant createdAt, Instant updatedAt, @JsonInclude(JsonInclude.Include.NON_EMPTY) List<RetryRejection> retryRejections,
        @JsonInclude(JsonInclude.Include.NON_NULL) Release release) {

    /** A root stored without {@code retryRejections} (the schema makes it optional) read as one that has rejected no retry. */
    public ConvergenceRoot {
        retryRejections = retryRejections == null ? List.of() : retryRejections;
    }

    /**
     * A retry the root refused, with the evidence it cited. The evidence stays burnt for the whole root, so a rejected retry cannot return it as new.
     * {@code expectedFingerprint} is written as {@code null} when the latest outcome carried none, and {@code actorId} is left out when no caller was verified.
     */
    public record RetryRejection(long epoch, RetryRejectionReason reason, @JsonInclude(JsonInclude.Include.NON_NULL) String actorId,
            @JsonInclude(JsonInclude.Include.ALWAYS) String expectedFingerprint, String targetDigest, List<String> evidenceRefs, Instant rejectedAt) {

        /** The rejection with another time: two rejections that differ only in when they happened are the same rejection. */
        RetryRejection at(Instant time) {
            return new RetryRejection(epoch, reason, actorId, expectedFingerprint, targetDigest, evidenceRefs, time);
        }
    }

    /**
     * How and why a root was released. {@code releasedBy}, {@code ownerPresence} and {@code closedRunId} are written as {@code null} when they do not apply;
     * {@code presenceObservedAt} is the presence reader's own text of when it looked, and is left out when it did not look (the owner released).
     */
    public record Release(Instant releasedAt, @JsonInclude(JsonInclude.Include.ALWAYS) String releasedBy, boolean releasedByOwner, RootState previousState,
            @JsonInclude(JsonInclude.Include.ALWAYS) PresenceState ownerPresence, @JsonInclude(JsonInclude.Include.NON_NULL) String presenceObservedAt, List<String> ownerActors,
            List<String> userApprovalRefs, String reason, @JsonInclude(JsonInclude.Include.ALWAYS) String closedRunId) {
    }

    /** The digests of the root's inputs, as the fields hold them. */
    ConvergenceDigests digests() {
        return new ConvergenceDigests(frameDigest, workspaceDigest, controlDigest, targetDigest, operationalDigest, taskDigest);
    }

    /** The root after one more change: revision one higher, last change at the given time. */
    ConvergenceRoot bumped(Instant updatedAt) {
        return new ConvergenceRoot(schemaVersion, rootId, parentRootId, revision + 1, state, currentEpoch, taskEnvelope, frame, taskDigest, frameDigest, workspaceDigest, controlDigest, targetDigest,
                operationalDigest, userApprovalRefs, createdAt, updatedAt, retryRejections, release);
    }

    ConvergenceRoot withState(RootState next) {
        return new ConvergenceRoot(schemaVersion, rootId, parentRootId, revision, next, currentEpoch, taskEnvelope, frame, taskDigest, frameDigest, workspaceDigest, controlDigest, targetDigest,
                operationalDigest, userApprovalRefs, createdAt, updatedAt, retryRejections, release);
    }

    ConvergenceRoot withRelease(Release released) {
        return new ConvergenceRoot(schemaVersion, rootId, parentRootId, revision, state, currentEpoch, taskEnvelope, frame, taskDigest, frameDigest, workspaceDigest, controlDigest, targetDigest,
                operationalDigest, userApprovalRefs, createdAt, updatedAt, retryRejections, released);
    }

    ConvergenceRoot withRetryRejection(RetryRejection rejection) {
        List<RetryRejection> next = new ArrayList<>(retryRejections);
        next.add(rejection);
        return new ConvergenceRoot(schemaVersion, rootId, parentRootId, revision, state, currentEpoch, taskEnvelope, frame, taskDigest, frameDigest, workspaceDigest, controlDigest, targetDigest,
                operationalDigest, userApprovalRefs, createdAt, updatedAt, List.copyOf(next), release);
    }

    /** The root one epoch later, open again, on the frame a review approved. */
    ConvergenceRoot nextEpoch(ConvergenceFrame approved, ConvergenceDigests approvedDigests) {
        return new ConvergenceRoot(schemaVersion, rootId, parentRootId, revision, RootState.OPEN, currentEpoch + 1, taskEnvelope, approved, approvedDigests.taskDigest(), approvedDigests.frameDigest(),
                approvedDigests.workspaceDigest(), approvedDigests.controlDigest(), approvedDigests.targetDigest(), approvedDigests.operationalDigest(), userApprovalRefs, createdAt, updatedAt,
                retryRejections, release);
    }
}
