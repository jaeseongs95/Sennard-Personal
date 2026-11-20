// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import static io.github.jaeseongs95.sennard.server.message.MessageRef.msg;
import static io.github.jaeseongs95.sennard.server.workflow.WorkflowFailure.details;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import io.github.jaeseongs95.sennard.service.store.RunState;
import tools.jackson.core.JsonPointer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The persistence policy a provider descriptor declares for its stage results, enforced before a result reaches the store. What the policy judges is free text the
 * provider put in its output, which is a tree whose shape the skill owns; the rest is the stage result's own record.
 */
final class ReceiptPolicy {
    static final Pattern DIGEST = Pattern.compile("^(?:sha256:)?[a-f0-9]{64}$");
    private static final Pattern UUID = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    private static final Pattern RFC3339 = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?(?:Z|[+-]\\d{2}:\\d{2})$");
    private static final Pattern REFERENCE = Pattern.compile("^(?:artifact|digest|schema|urn|run|stage|commit|test|file|document|tool):(?://)?[A-Za-z0-9][A-Za-z0-9._~:/?#@!$&'()*+,;=%-]{7,}$");
    private static final String NIL_UUID = "00000000-0000-0000-0000-000000000000";
    private static final Set<String> ERROR_DETAIL_KEYS = Set.of("actorId", "actorIdPointer", "actual", "artifactId", "artifactIds", "code", "conflictingStageId", "digest", "digests",
            "expected", "locator", "locators", "mode", "policy", "reference", "references", "runId", "schemaId", "stageId", "targetDigest");
    private static final Set<String> PROTOCOL_TOKENS = Set.of(ContractValidator.CONTRACT_VERSION,
            "output", "adapter-error", "user-input", "file", "test", "document", "tool", "reference-only", "verified",
            "COOPERATIVE_PROVENANCE_ASSERTIONS", "JSON_JSONL_ONLY_V1");

    private ReceiptPolicy() {
    }

    /** The run states are protocol tokens too, and RunState is their one definition. */
    private static boolean runStateToken(String value) {
        return Arrays.stream(RunState.values()).anyMatch(state -> Objects.equals(state.name(), value));
    }

    /** The public error codes are protocol tokens too, and WorkflowCode is their one definition. */
    private static boolean errorCodeToken(String value) {
        return Arrays.stream(WorkflowCode.values()).anyMatch(code -> Objects.equals(code.name(), value));
    }

    private static boolean opaque(String value) {
        return DIGEST.matcher(value).matches() || UUID.matcher(value).matches() || REFERENCE.matcher(value).matches();
    }

    private static void safeString(String value, Set<String> fixed, String location, boolean allowEmpty) {
        if ((allowEmpty && value.isEmpty()) || fixed.contains(value) || PROTOCOL_TOKENS.contains(value) || errorCodeToken(value) || runStateToken(value) || opaque(value) || RFC3339.matcher(value).matches()) {
            return;
        }
        throw new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg("workflow.receiptPolicy.referenceReceiptPolicyRejectedFree"), details("location", location));
    }

    /** Every text in the provider's output is a fixed token or an opaque reference, however deep it sits. */
    static void safeValue(JsonNode value, Set<String> fixed, String location) {
        if (value == null) {
            return;
        }
        if (value.isString()) {
            safeString(value.asString(), fixed, location, false);
        } else if (value.isArray()) {
            for (int i = 0; i < value.size(); i++) {
                safeValue(value.get(i), fixed, JsonPointer.compile(location).appendIndex(i).toString());
            }
        } else if (value.isObject()) {
            // The location is a JSON Pointer (RFC 6901); the pointer escapes a key that holds a "/" or a "~", which joining the text would not.
            value.properties().forEach(entry -> safeValue(entry.getValue(), fixed, JsonPointer.compile(location).appendProperty(entry.getKey()).toString()));
        }
    }

    private static void safeError(RecordedError error, Set<String> fixed, String location) {
        if (error == null) {
            return;
        }
        safeString(error.message(), fixed, location + "/message", false);
        JsonNode errorDetails = error.details();
        if (errorDetails != null && errorDetails.isObject()) {
            errorDetails.properties().forEach(entry -> {
                if (!ERROR_DETAIL_KEYS.contains(entry.getKey())) {
                    throw new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg("workflow.receiptPolicy.referenceErrorDetailsContainUndeclared"), details("location", location + "/details/" + entry.getKey()));
                }
                safeValue(entry.getValue(), fixed, location + "/details/" + entry.getKey());
            });
        }
    }

    /** The text a JSON Pointer names in a provider's result, which the skill's descriptor declared; null where it names none or names something that is not text. */
    private static String stringAt(ProviderResult result, String pointer, JsonMapper mapper) {
        JsonNode value = result.at(pointer, mapper);
        return value.isString() ? value.asString() : null;
    }

    private static boolean validActor(String actorId) {
        return actorId != null && !NIL_UUID.equals(actorId) && UUID.matcher(actorId).matches();
    }

    static void enforce(WorkflowReceipt receipt, WorkflowPlan plan, WorkflowPlan.Stage stage, StageResult result, Set<String> outputFixedTokens, JsonMapper mapper) {
        StageReceiptPolicy policy = stage.receiptPolicy();
        if (policy == null) {
            return;
        }
        String stageId = stage.stageId();
        Set<String> fixed = new HashSet<>(outputFixedTokens);
        fixed.addAll(stage.satisfiedCapabilities());
        fixed.addAll(stage.requiredInputArtifacts());
        fixed.addAll(stage.producedArtifacts());
        fixed.addAll(stage.requiredArtifacts());
        ProviderResult output = result.output();
        safeValue(output.output(), fixed, "/output/output");

        Set<String> allowed = new HashSet<>(stage.requiredInputArtifacts());
        allowed.addAll(stage.producedArtifacts());
        allowed.addAll(stage.requiredArtifacts());
        List<ProviderArtifact> artifacts = output.artifacts();
        for (int i = 0; i < artifacts.size(); i++) {
            ProviderArtifact artifact = artifacts.get(i);
            String artifactId = artifact.artifactId();
            if (!allowed.contains(artifactId)) {
                throw new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg("workflow.receiptPolicy.referenceReceiptContainsUndeclaredArtifact"), details("stageId", stageId, "artifactId", artifactId));
            }
            safeString(artifact.schemaId(), fixed, "/output/artifacts/" + i + "/schemaId", false);
            safeString(artifact.attachmentId(), fixed, "/output/artifacts/" + i + "/attachmentId", false);
            if (!DIGEST.matcher(artifact.digest()).matches() || !DIGEST.matcher(artifact.targetDigest()).matches()) {
                throw new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg("workflow.receiptPolicy.referenceArtifactDigestsOpaqueSHA"), details("stageId", stageId, "artifactId", artifactId));
            }
        }
        List<Evidence> evidence = result.evidence();
        for (int i = 0; i < evidence.size(); i++) {
            Evidence item = evidence.get(i);
            if (!allowed.contains(item.artifactId())) {
                throw new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg("workflow.receiptPolicy.referenceReceiptContainsUndeclaredEvidence"), details("stageId", stageId, "artifactId", item.artifactId()));
            }
            safeString(item.attachmentId(), fixed, "/evidence/" + i + "/attachmentId", false);
            safeString(item.note(), fixed, "/evidence/" + i + "/note", true);
        }
        List<String> findings = result.findings();
        for (int i = 0; i < findings.size(); i++) {
            safeString(findings.get(i), fixed, "/findings/" + i, false);
        }
        List<String> blockers = result.blockers();
        for (int i = 0; i < blockers.size(); i++) {
            safeString(blockers.get(i), fixed, "/blockers/" + i, false);
        }
        safeError(output.error(), fixed, "/output/error");
        safeError(result.error(), fixed, "/error");

        List<WorkflowPlan.Stage> planned = plan.stages();
        List<String> missing = new ArrayList<>();
        for (String artifactId : stage.requiredInputArtifacts()) {
            boolean producedInRun = planned.stream().anyMatch(candidate -> !Objects.equals(candidate.stageId(), stageId) && candidate.producedArtifacts().contains(artifactId));
            if (!producedInRun && evidence.stream().noneMatch(item -> Objects.equals(item.artifactId(), artifactId) && item.verified() && opaque(item.attachmentId()))) {
                missing.add(artifactId);
            }
        }
        if (!missing.isEmpty()) {
            throw new WorkflowFailure(WorkflowCode.MISSING_EVIDENCE, msg("workflow.receiptPolicy.referenceStagePrerequisitesRequireVerified"), details("stageId", stageId, "missingInputs", missing));
        }

        String actorIdsPointer = policy.actorIdsPointer();
        // The plan schema allows actorIdsMatch only as the constant "prior-policy-actors", so its presence is the rule.
        if (policy.listsPriorActors()) {
            List<WorkflowPlan.Stage> prior = planned.stream().filter(candidate -> candidate.order() < stage.order() && candidate.receiptPolicy() != null && candidate.receiptPolicy().actorIdPointer() != null)
                    .sorted(Comparator.comparingInt(WorkflowPlan.Stage::order)).toList();
            List<String> expected = new ArrayList<>();
            for (WorkflowPlan.Stage priorStage : prior) {
                String actorId = receipt.resultOf(priorStage.stageId()).map(priorResult -> stringAt(priorResult.output(), priorStage.receiptPolicy().actorIdPointer(), mapper)).filter(ReceiptPolicy::validActor)
                        .orElseThrow(() -> new WorkflowFailure(WorkflowCode.MISSING_EVIDENCE, msg("workflow.receiptPolicy.priorPolicyStageValidActor"), details("stageId", stageId, "conflictingStageId", priorStage.stageId())));
                expected.add(actorId);
            }
            JsonNode actorIds = output.at(actorIdsPointer, mapper);
            boolean matches = actorIds.isArray() && actorIds.size() == expected.size();
            List<String> listed = new ArrayList<>();
            for (int i = 0; matches && i < actorIds.size(); i++) {
                matches = actorIds.get(i).isString() && Objects.equals(expected.get(i), actorIds.get(i).asString());
                listed.add(actorIds.get(i).asString());
            }
            matches = matches && new HashSet<>(listed).size() == actorIds.size();
            if (!matches) {
                throw new WorkflowFailure(WorkflowCode.GATE_FAILED, msg("workflow.receiptPolicy.receiptActorIDsExactlyMatch"), details("stageId", stageId, "actorIdsPointer", actorIdsPointer));
            }
        }

        String actorIdPointer = policy.actorIdPointer();
        if (actorIdPointer == null) {
            return;
        }
        String actorId = stringAt(output, actorIdPointer, mapper);
        if (!validActor(actorId)) {
            throw new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg("workflow.receiptPolicy.receiptActorIDNonemptyCanonical"), details("stageId", stageId, "actorIdPointer", actorIdPointer));
        }
        // The plan schema allows uniqueness only as the constant "run", so its presence is the rule.
        if (!policy.requiresUniqueActor()) {
            return;
        }
        for (StageResult priorResult : receipt.stageResults()) {
            if (Objects.equals(priorResult.stageId(), stageId)) {
                continue;
            }
            plan.stage(priorResult.stageId()).map(WorkflowPlan.Stage::receiptPolicy).map(StageReceiptPolicy::actorIdPointer).filter(pointer -> Objects.equals(actorId, stringAt(priorResult.output(), pointer, mapper)))
                    .ifPresent(pointer -> {
                        throw new WorkflowFailure(WorkflowCode.GATE_FAILED, msg("workflow.receiptPolicy.receiptActorIDUniqueAcross"), details("stageId", stageId, "conflictingStageId", priorResult.stageId()));
                    });
        }
    }
}
