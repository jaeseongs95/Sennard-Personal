// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.service.store.LeaseState;
import io.github.jaeseongs95.sennard.service.store.modules.ContinuityStore;
import io.github.jaeseongs95.sennard.service.store.modules.ContinuityStore.Task;

/** The decision to advance a session's epoch, shared by prompt handling and a workflow start. */
@Component
public class WorkflowSettlement {
    private final ContinuityStore store;
    private final WorkflowDocuments documents;

    public WorkflowSettlement(ContinuityStore store, WorkflowDocuments documents) {
        this.store = store;
        this.documents = documents;
    }

    public Task settle(Task task, String now) {
        if (task.rootId() == null) {
            return task;
        }
        long epoch = task.currentEpoch();
        String correlation = task.taskCorrelation();
        return store.rotateEpoch(correlation, now, new ContinuityStore.Expected(epoch, task.rootId(),
                () -> store.boundRoots(correlation, epoch).stream().allMatch(this::rootSettled), true));
    }

    private boolean rootSettled(String rootId) {
        return switch (documents.read(rootId)) {
            case WorkflowDocuments.Readable root -> settled(root);
            case WorkflowDocuments.Unavailable unreadable -> false;
        };
    }

    private boolean settled(WorkflowDocuments.Readable documents) {
        if (documents.root().state().isActive() && documents.runs().isEmpty()
                || documents.runs().stream().anyMatch(run -> !run.state().isTerminal())) {
            return false;
        }
        Map<String, AttemptOutcome> finished = new HashMap<>();
        documents.outcomes().forEach(outcome -> finished.put(outcome.leaseId(), outcome));
        // Every consumed lease must have its own outcome, including leases from older epochs.
        for (AttemptLease lease : documents.leases()) {
            if (lease.state() != LeaseState.CONSUMED) {
                continue;
            }
            AttemptOutcome outcome = finished.get(lease.leaseId());
            if (outcome == null || outcome.epoch() != lease.epoch() || outcome.ordinal() != lease.ordinal()) {
                return false;
            }
        }
        return true;
    }
}
