// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.service.store.StoreCode;
import io.github.jaeseongs95.sennard.service.store.StoreException;
import io.github.jaeseongs95.sennard.service.store.modules.WorkflowStore;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;

/** Reads the persisted workflow facts used by session continuity. Store availability errors stay on the caller's error path. */
@Component
public class WorkflowDocuments {
    public sealed interface Read permits Readable, Unavailable {
    }

    public record Readable(ConvergenceRoot root, WorkflowReceipt latestRun, List<RunHeader> runs,
            List<AttemptLease> leases, List<AttemptOutcome> outcomes) implements Read {
    }

    public record Unavailable(String rootId, String runId, RuntimeException cause) implements Read {
    }

    private final WorkflowStore store;
    private final JsonMapper mapper;

    public WorkflowDocuments(WorkflowStore store, JsonMapper mapper) {
        this.store = store;
        this.mapper = mapper;
    }

    public Read read(String rootId) {
        return read(rootId, true);
    }

    /** Reads the root and, only while it is active, its current receipt. */
    public Read readCurrent(String rootId) {
        return read(rootId, false);
    }

    private Read read(String rootId, boolean includeHistory) {
        String runId = null;
        try {
            WorkflowStore.Snapshot snapshot = includeHistory ? store.getConvergenceSnapshot(rootId) : null;
            String rootJson = includeHistory ? (snapshot == null ? null : snapshot.rootJson()) : store.getRootDocument(rootId);
            ConvergenceRoot root = root(rootId, rootJson);
            if (!includeHistory && !root.state().isActive()) {
                return new Readable(root, null, List.of(), List.of(), List.of());
            }
            List<String> runIds = includeHistory ? snapshot.workflowRunIds() : store.workflowRunIds(rootId);
            String latestRunId = runIds.isEmpty() ? null : runIds.getLast();
            List<String> inspectedRuns = includeHistory || latestRunId == null ? runIds : List.of(latestRunId);
            List<RunHeader> runs = new ArrayList<>(inspectedRuns.size());
            WorkflowReceipt latest = null;
            for (String linkedRunId : inspectedRuns) {
                runId = linkedRunId;
                String json = store.getRun(runId);
                require(json != null, "linked run");
                RunHeader run = mapper.readValue(json, RunHeader.class);
                require(run != null && runId.equals(run.runId()) && run.state() != null, "run header");
                WorkflowReceipt receipt = receipt(json);
                runs.add(run);
                if (runId.equals(latestRunId)) {
                    latest = receipt;
                }
            }
            if (!includeHistory) {
                return new Readable(root, latest, List.copyOf(runs), List.of(), List.of());
            }
            runId = null;
            List<AttemptLease> leases = snapshot.leases().stream().map(json -> lease(rootId, json)).toList();
            List<AttemptOutcome> outcomes = new ArrayList<>(snapshot.outcomes().size());
            for (String json : snapshot.outcomes()) {
                require(json != null, "outcome document");
                AttemptOutcome outcome = mapper.readValue(json, AttemptOutcome.class);
                require(outcome != null && rootId.equals(outcome.rootId()), "outcome root identity");
                if (outcome.leaseId() == null || outcome.state() == null || !outcome.state().isOutcome() || outcome.epoch() <= 0 || outcome.ordinal() <= 0) {
                    String linkedRunId = outcome.workflowRunId();
                    // An id claimed by a damaged document must match the document stored for that run.
                    if (linkedRunId != null && runIds.contains(linkedRunId)) {
                        WorkflowStore.GuardedBinding binding = store.getGuardedRunBinding(linkedRunId);
                        if (binding != null && json.equals(binding.outcomeJson())) {
                            runId = linkedRunId;
                        }
                    }
                    throw new StoreException(StoreCode.INTEGRITY_FAILED, "Stored workflow outcome has invalid identity or state.");
                }
                outcomes.add(outcome);
            }
            return new Readable(root, latest, List.copyOf(runs), leases, List.copyOf(outcomes));
        } catch (JacksonException failure) {
            return new Unavailable(rootId, runId, failure);
        } catch (StoreException failure) {
            if (failure.code() != StoreCode.INTEGRITY_FAILED) {
                throw failure;
            }
            return new Unavailable(rootId, runId, failure);
        }
    }

    private ConvergenceRoot root(String rootId, String json) {
        require(json != null, "root document");
        ConvergenceRoot root = mapper.readValue(json, ConvergenceRoot.class);
        require(root != null && rootId.equals(root.rootId()) && root.state() != null && root.updatedAt() != null, "root identity or state");
        TaskEnvelope task = root.taskEnvelope();
        require(task != null && task.objective() != null && task.scope() != null && task.authorization() != null, "root task");
        return root;
    }

    private WorkflowReceipt receipt(String json) {
        // The state was read strictly in the header. Optional, newer artifact check constants do not verify evidence on this server.
        WorkflowReceipt run = mapper.readerFor(WorkflowReceipt.class).with(EnumFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL).readValue(json);
        require(run != null && run.plan() != null, "run plan");
        if (run.stageResults() != null) {
            for (StageResult stage : run.stageResults()) {
                require(stage != null && stage.stageId() != null && stage.state() != null, "stage result identity or state");
                if (stage.output() != null && stage.output().artifacts() != null) {
                    for (ProviderArtifact artifact : stage.output().artifacts()) {
                        require(artifact != null && artifact.artifactId() != null && artifact.attachmentId() != null && artifact.digest() != null, "run artifact");
                    }
                }
            }
        }
        return run;
    }

    private AttemptLease lease(String rootId, String json) {
        require(json != null, "lease document");
        AttemptLease lease = mapper.readValue(json, AttemptLease.class);
        require(lease != null && rootId.equals(lease.rootId()) && lease.leaseId() != null && lease.state() != null && lease.epoch() > 0 && lease.ordinal() > 0, "lease identity or state");
        return lease;
    }

    private static void require(boolean present, String part) {
        if (!present) {
            throw new StoreException(StoreCode.INTEGRITY_FAILED, "Stored workflow document is missing required " + part + ".");
        }
    }
}
