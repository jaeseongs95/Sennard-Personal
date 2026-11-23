// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import io.github.jaeseongs95.sennard.server.contract.CanonicalJson;
import io.github.jaeseongs95.sennard.server.hook.host.ForeignPathText;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** The submitted work and its requirements, independent of identifiers, observations and progress. */
final class WorkflowPlanDigest {
    private WorkflowPlanDigest() {
    }

    static String of(JsonMapper mapper, TaskEnvelope task, WorkflowPlan plan, Path workspace, Set<Path> files, List<String> writeSurface, String minimalTarget) {
        return of(mapper, WorkflowKind.CHANGE, task, plan, workspace, files, writeSurface, minimalTarget);
    }

    static String of(JsonMapper mapper, WorkflowKind kind, TaskEnvelope task, WorkflowPlan plan, Path workspace, Set<Path> files, List<String> writeSurface,
            String minimalTarget) {
        ObjectNode taskInput = mapper.valueToTree(task.withoutTaskId());
        taskInput.remove("orchestration");
        if (!kind.allowsFileEdits()) {
            // A no-file start uses an instance-specific path to isolate its root. It does not describe the work.
            ((ObjectNode) taskInput.get("scope")).remove("included");
            taskInput.path("workUnits").forEach(unit -> ((ObjectNode) unit).remove("writeTargets"));
        }
        if (minimalTarget != null) {
            // Minimal starts generate display constraints from the target and pre-start observations.
            taskInput.remove("constraints");
            taskInput.put("target", minimalTarget);
        }
        ObjectNode planInput = mapper.valueToTree(plan);
        planInput.remove(List.of("taskId", "taskDigest", "integrityToken", "state", "currentStageId", "nextStageId", "errors", "bootstrapExecution"));
        for (JsonNode stage : planInput.path("stages")) {
            ((ObjectNode) stage).remove(List.of("state", "selectionReason"));
        }
        List<String> normalized = files.stream().map(Path::normalize)
                .map(path -> path.startsWith(workspace) ? workspace.relativize(path) : path)
                .map(WorkflowPlanDigest::canonicalPath).distinct().sorted().toList();
        ObjectNode input = mapper.createObjectNode();
        input.put("version", 1);
        input.set("task", taskInput);
        input.set("plan", planInput);
        input.set("files", mapper.valueToTree(normalized));
        List<String> surface = kind.allowsFileEdits() ? writeSurface.stream().distinct().sorted().toList() : List.of();
        input.set("writeSurface", mapper.valueToTree(surface));
        return CanonicalJson.digest(input);
    }

    static String canonicalPath(Path path) {
        Path root = path.getRoot();
        // A root may contain a drive or UNC share. Individual names are already split by the provider.
        String prefix = root == null ? "" : ForeignPathText.slashed(root.toString());
        return prefix + StreamSupport.stream(path.spliterator(), false).map(Path::toString).collect(Collectors.joining("/"));
    }
}
