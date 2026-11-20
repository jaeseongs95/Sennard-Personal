// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.approval;

import java.util.List;
import java.util.Optional;

import io.github.jaeseongs95.sennard.service.store.RootState;
import io.github.jaeseongs95.sennard.service.store.RunState;

/**
 * The workflow side of a person releasing an open convergence root from the admin page: which roots are open and whether their owner session is still there,
 * and the release itself. When a session dies it can no longer release its root, and no other session may without the user, so the user does it here. A model cannot reach
 * this: there is no tool for it. The passkey-signed record is the only caller of {@link #release}, and nothing releases a root by itself (no timeout, no sweep). Every
 * method joins the caller's transaction, so the signed record and the release commit together.
 */
public interface RootReleaseService {
    /** Who owns a root, as the page can show it. {@code ref} is the opaque correlation of the owning task, never a session id; {@code host} is set only for a session that is online now. */
    record Owner(String ref, String host) {
    }

    /** The newest workflow run attached to a root. */
    record Run(String runId, RunState state, String updatedAt) {
    }

    /**
     * One open root as the page lists it. Times are UTC ISO-8601 text; the page converts them. {@code ownerPresence} is the presence reader's word ({@code online},
     * {@code unknown}, ...): it is never taken as offline unless it says so, and {@code ownerBound} says whether any session ever bound the root.
     *
     * @param objective what the root's task asks for
     * @param lastActivityAt the later of the root's last change and its newest run's last change
     * @param run null when no workflow run is attached
     */
    record OpenRoot(String rootId, long revision, RootState state, String objective, String createdAt, String lastActivityAt, Run run, boolean ownerBound, String ownerPresence, List<Owner> owners) {
    }

    /** Why a release cannot be made now. */
    enum Refusal {
        ROOT_NOT_FOUND,
        ROOT_NOT_ACTIVE,
        ROOT_REVISION_STALE
    }

    /**
     * What a release did.
     *
     * @param closedRunId null when no run was running
     * @param ownerPresence the owner's presence as it was read when the root was released
     * @param approvalId the decision record the release cites
     */
    record Released(String rootId, long revision, String closedRunId, String ownerPresence, String approvalId) {
    }

    sealed interface Result {
        record Done(Released released) implements Result {
        }

        record Refused(Refusal refusal) implements Result {
        }
    }

    /** The roots that are open now (open, needing review or needing the user), oldest first. */
    List<OpenRoot> openRoots();

    /** Whether the release a signature would approve can be made now; empty when it can. Writes nothing. */
    Optional<Refusal> refusal(String rootId, long expectedRevision);

    /**
     * Releases the root: it is closed, a run still in progress on it is closed as blocked, and the release records who released it (the admin page), when, why, the owner's presence
     * as read, and the decision that is written for the signed record.
     */
    Result release(String rootId, long expectedRevision, String reason, RootApprovalService.Source source);
}
