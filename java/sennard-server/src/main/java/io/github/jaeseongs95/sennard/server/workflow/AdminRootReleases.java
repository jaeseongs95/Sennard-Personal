// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.jaeseongs95.sennard.server.approval.RootApprovalService;
import io.github.jaeseongs95.sennard.server.approval.RootReleaseService;
import io.github.jaeseongs95.sennard.service.store.modules.ContinuityStore;
import io.github.jaeseongs95.sennard.service.store.modules.WorkflowStore;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the admin page knows of the open roots, and its way to release one. The listing only reads: the roots, the newest run of each, who bound it and whether that owner session
 * is online (the same reading the tool's release takes, {@link RootOwnerPresence}). The release is {@link WorkflowService#releaseByAdmin}, so a root is closed by one piece of code
 * whoever asks for it.
 */
@Service
class AdminRootReleases implements RootReleaseService {
    /** How much of an owner's opaque correlation the page shows: enough to tell two owners apart, never a session id. */
    private static final int OWNER_REF_LENGTH = 12;

    private final WorkflowStore store;
    private final ContinuityStore continuityStore;
    private final WorkflowContinuity continuity;
    private final WorkflowService workflow;
    private final JsonMapper mapper;

    AdminRootReleases(WorkflowStore store, ContinuityStore continuityStore, WorkflowContinuity continuity, WorkflowService workflow, JsonMapper mapper) {
        this.store = store;
        this.continuityStore = continuityStore;
        this.continuity = continuity;
        this.workflow = workflow;
        this.mapper = mapper;
    }

    @Override
    @Transactional(readOnly = true)
    public List<OpenRoot> openRoots() {
        return store.readActiveRoots().stream().map(row -> mapper.readValue(row.rootJson(), ConvergenceRoot.class)).sorted(Comparator.comparing(ConvergenceRoot::createdAt)
                .thenComparing(ConvergenceRoot::rootId)).map(this::openRoot).toList();
    }

    private OpenRoot openRoot(ConvergenceRoot root) {
        List<String> runIds = store.workflowRunIds(root.rootId());
        Run run = runIds.isEmpty() ? null : run(runIds.getLast());
        Instant lastActivity = run == null || Instant.parse(run.updatedAt()).isBefore(root.updatedAt()) ? root.updatedAt() : Instant.parse(run.updatedAt());
        RootOwnerPresence.View presence = workflow.ownerPresence(root.rootId());
        List<Owner> owners = continuityStore.rootOwners(root.rootId()).stream().map(correlation -> owner(correlation, presence)).toList();
        return new OpenRoot(root.rootId(), root.revision(), root.state(), root.taskEnvelope().objective(), root.createdAt().toString(), lastActivity.toString(), run, presence.ownerBound(),
                presence.state().wire(), owners);
    }

    private Run run(String runId) {
        RunHeader header = mapper.readValue(store.getRun(runId), RunHeader.class);
        return new Run(runId, header.state(), store.getRunUpdatedAt(runId));
    }

    /**
     * An owner as the page shows it: the first characters of its correlation's digest, and the host when one of its sessions is online now. A correlation is
     * {@code <scheme>:<hex digest>} ({@code hmac-sha256:...}), and the scheme alone is already {@value #OWNER_REF_LENGTH} characters long, so the reference is taken after it:
     * cutting the correlation itself would show the scheme and none of the digest.
     */
    private Owner owner(String correlation, RootOwnerPresence.View presence) {
        String digest = correlation.substring(correlation.indexOf(':') + 1);
        String ref = digest.substring(0, Math.min(OWNER_REF_LENGTH, digest.length()));
        for (RootOwnerPresence.OwnerSession session : presence.sessions()) {
            if (continuity.correlate(session.sessionId()).equals(correlation)) {
                return new Owner(ref, session.host());
            }
        }
        return new Owner(ref, null);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Refusal> refusal(String rootId, long expectedRevision) {
        return workflow.releaseRefusal(rootId, expectedRevision);
    }

    @Override
    @Transactional
    public Result release(String rootId, long expectedRevision, String reason, RootApprovalService.Source source) {
        return workflow.releaseByAdmin(rootId, expectedRevision, reason, source);
    }
}
