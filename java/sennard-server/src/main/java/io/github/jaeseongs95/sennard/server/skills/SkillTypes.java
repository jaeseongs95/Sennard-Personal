// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.skills;

import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonValue;

import tools.jackson.databind.JsonNode;

/** The values classification reads and writes. They are JSON on the wire and in every digest, so the optional fields stay out when absent. */
public final class SkillTypes {
    private SkillTypes() {
    }

    /** Why the inventory reports an issue; the name is the code on the wire. */
    public enum IssueCode {
        INVALID_FRONTMATTER, MISSING_DESCRIPTION, MISSING_FRONTMATTER_FIELD, DUPLICATE_FRONTMATTER_FIELD, SOURCE_CONFLICT, SOURCE_PATH_INVALID, SOURCE_CHANGED_DURING_LOAD,
        MISSING_OR_INVALID_METADATA, INVALID_REGISTRY_DESCRIPTOR, MISSING_OR_INVALID_REGISTRY, DUPLICATE_ID, REGISTRY_SKILL_MISSING, TAXONOMY_REVISION_CONFLICT, MISSING_CAPABILITY_OR_VERSION,
        STALE_SOURCE, SOURCE_RANGE_INVALID, MISSING_APPLICABILITY_OR_EXCLUSION, DEPENDENCY_UNAVAILABLE
    }

    /** Why a classification attempt, a provider call or a judgment is refused or left unresolved; the name is the code on the wire. */
    public enum ReasonCode {
        TRANSPORT_UNAVAILABLE, RATE_LIMITED, SERVER_UNAVAILABLE, INVALID_PROVIDER_RESPONSE, PROVIDER_TIMEOUT, OUTPUT_TOO_LONG, PROVIDER_RESPONSE_TOO_LARGE, AUTH_UNAVAILABLE, API_UNAVAILABLE,
        CREDENTIAL_UNAVAILABLE, CREDENTIAL_MALFORMED, INVALID_PROVIDER_REQUEST, INPUT_TOO_LONG, CANCELLED, REMOTE_CONTENT_NOT_APPROVED, STALE_CLASSIFICATION, BUDGET_UNAVAILABLE,
        PROFILE_UNAVAILABLE, REQUEST_ID_CONFLICT, JEV_MATERIAL_FACT_UNRESOLVED
    }

    /** What the server knows of the provider call when it answers: how far the call got. */
    public enum DispatchState {
        NOT_STARTED("not-started"), UNKNOWN("unknown"), STARTED("started");

        private final String wire;

        DispatchState(String wire) {
            this.wire = wire;
        }

        @JsonValue
        public String wire() {
            return wire;
        }
    }

    public enum ProviderKind {
        JEV("jev");

        private final String wire;

        ProviderKind(String wire) {
            this.wire = wire;
        }

        @JsonValue
        public String wire() {
            return wire;
        }
    }

    /** The helper's answer for one skill. The constants stay in this order: the provider's distribution is summed in it. */
    public enum Verdict {
        NEEDED("needed"), NOT_NEEDED("not-needed"), UNCERTAIN("uncertain");

        private final String wire;

        Verdict(String wire) {
            this.wire = wire;
        }

        @JsonValue
        public String wire() {
            return wire;
        }
    }

    public enum ResponseStatus {
        SUCCESS, PARTIAL, UNCERTAIN, UNAVAILABLE, INVALID;

        /** The helper gave advice (a response with an error is no advice, whatever its status). */
        public boolean advised() {
            return switch (this) {
                case SUCCESS, PARTIAL, UNCERTAIN -> true;
                case UNAVAILABLE, INVALID -> false;
            };
        }

        /** No usable answer at all. */
        public boolean failed() {
            return !advised();
        }
    }

    public enum SelectionStatus {
        PROPOSED, SELECTED, PARTIAL, NEEDS_INPUT
    }

    public enum SelectionSource {
        HELPER_ADVICE("helper-advice"), INDEPENDENT_AGENT_AFTER_HELPER_FAILURE("independent-agent-after-helper-failure");

        private final String wire;

        SelectionSource(String wire) {
            this.wire = wire;
        }

        @JsonValue
        public String wire() {
            return wire;
        }
    }

    /** The state of a later stage the selection has not reached. */
    public enum StageStatus {
        NOT_EVALUATED, NOT_OBSERVED, NOT_RUN
    }

    public enum BlockReason {
        DISABLED, NOT_INSTALLED, HOST_UNSUPPORTED, UNKNOWN_REQUIRED_SKILL
    }

    public enum QualificationStatus {
        NOT_RUN
    }

    /** COMPLETE when the tree read with no issue, INCOMPLETE when it read with issues. */
    public enum DiscoveryStatus {
        COMPLETE, INCOMPLETE
    }

    public enum DiscoveryScope {
        LOCAL_TREE("local-tree");

        private final String wire;

        DiscoveryScope(String wire) {
            this.wire = wire;
        }

        @JsonValue
        public String wire() {
            return wire;
        }
    }

    public enum ClassificationMode {
        SELECT("select");

        private final String wire;

        ClassificationMode(String wire) {
            this.wire = wire;
        }

        @JsonValue
        public String wire() {
            return wire;
        }
    }

    public enum ScoreKind {
        CHOICE_CONFIDENCE("choice_confidence");

        private final String wire;

        ScoreKind(String wire) {
            this.wire = wire;
        }

        @JsonValue
        public String wire() {
            return wire;
        }
    }

    /** The members of the confirmed context, by the name a context source cites. */
    public enum ContextField {
        TASK_REVISION("taskRevision"), OBJECTIVE("objective"), ACTIONS("actions"), TARGETS("targets"), CONSTRAINTS("constraints"), PROHIBITED_ACTIONS("prohibitedActions"),
        BACKGROUND("background");

        private final String wire;

        ContextField(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }

        /** The field a context source names, or null when it names none. */
        public static ContextField named(String wire) {
            for (ContextField field : values()) {
                if (Objects.equals(field.wire, wire)) {
                    return field;
                }
            }
            return null;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Phase(String capability, String phase, int phaseOrder, List<String> requiredInputArtifacts, List<String> producedArtifacts,
            JsonNode gate /* the registry's own gate document: classification only forwards it to the model and into the digest */,
            JsonNode inputBindings /* likewise the registry's own binding list */) {
    }

    public record SourceRef(String path, String digest) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SourceSpan(String field, String path, Integer startLine, Integer endLine, String digest) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Skill(String skillId, String version, String description, boolean enabled, boolean installed, boolean hostSupported, List<String> capabilities, List<String> actions,
            List<String> targets, List<String> constraints, List<String> applicability, List<String> exclusions, List<String> dependencies, List<Phase> phases, List<SourceRef> sourceRefs,
            List<SourceSpan> sourceMap) {
        /** What a model is shown of a skill: the descriptor without where its bytes came from. */
        SkillView view() {
            return new SkillView(skillId, version, description, enabled, installed, hostSupported, capabilities, actions, targets, constraints, applicability, exclusions, dependencies, phases);
        }

        /** What the inventory list shows of a skill: what a model chooses by, without the lines the skill applies by (those come with {@link #detail}). */
        SkillSummary summary() {
            return new SkillSummary(skillId, version, description, enabled, installed, hostSupported, capabilities, actions, targets, constraints, dependencies);
        }

        /** What the inventory shows of a skill asked for by name: all of it but the line map of its sources (their digests stay in {@code sourceRefs}). */
        Skill detail() {
            return new Skill(skillId, version, description, enabled, installed, hostSupported, capabilities, actions, targets, constraints, applicability, exclusions, dependencies, phases,
                    sourceRefs, null);
        }
    }

    /** A {@link Skill} as the inventory list shows it: the descriptor members that choose a skill, without applicability, exclusions, phases or sources. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SkillSummary(String skillId, String version, String description, boolean enabled, boolean installed, boolean hostSupported, List<String> capabilities, List<String> actions,
            List<String> targets, List<String> constraints, List<String> dependencies) {
    }

    /** A {@link Skill} without its source references; the members and their order are the first fourteen of the skill. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SkillView(String skillId, String version, String description, boolean enabled, boolean installed, boolean hostSupported, List<String> capabilities, List<String> actions,
            List<String> targets, List<String> constraints, List<String> applicability, List<String> exclusions, List<String> dependencies, List<Phase> phases) {
    }

    public record Issue(String skillId, IssueCode code, String field) {
    }

    public record Inventory(List<Skill> skills, String inventoryDigest, String taxonomyRevision, List<Issue> issues) {
    }

    public record Discovery(DiscoveryStatus status, DiscoveryScope scope) {
    }

    /** The inventory with where it came from. */
    public record ObservedInventory(Inventory inventory, Discovery discovery) {
    }

    /** How much of each skill the inventory tool's answer holds; the name is the {@code view} on the wire. */
    public enum InventoryDetail {
        SUMMARY("summary"), DETAIL("detail");

        private final String wire;

        InventoryDetail(String wire) {
            this.wire = wire;
        }

        @JsonValue
        public String wire() {
            return wire;
        }
    }

    /** The inventory tool's answer: the skills at the level {@code view} says, with the inventory's digest, its issues and its discovery beside them. */
    public sealed interface InventoryAnswer permits InventoryList, InventoryDetails {
    }

    /** Every installed skill as a {@link SkillSummary}: its size does not grow with the lines a skill applies by. */
    public record InventoryList(InventoryDetail view, List<SkillSummary> skills, String inventoryDigest, String taxonomyRevision, List<Issue> issues, Discovery discovery) implements InventoryAnswer {
    }

    /** The skills a call named, whole (see {@link Skill#detail}). */
    public record InventoryDetails(InventoryDetail view, List<Skill> skills, String inventoryDigest, String taxonomyRevision, List<Issue> issues, Discovery discovery) implements InventoryAnswer {
    }

    public record Config(boolean jevEnabled, ClassificationMode mode, String providerProfileRegistryRef, boolean externalClassificationAllowed, String configRevision, long timeoutMs) {
    }

    public record SupportedOptions(List<String> reasoningEfforts, boolean structuredOutput) {
    }

    public record Qualification(QualificationStatus status, String inventoryDigest, String taxonomyRevision, String modelRevision, String promptRevision, String validUntil,
            String profileConfigurationDigest) {
    }

    public record Profile(String profileId, ProviderKind providerKind, String vendorId, String modelId, String modelRevision, String reasoningEffort, SupportedOptions supportedOptions,
            String approvedRouteRef, String qualificationRevision, String adapterRevision, String promptRevision, long maximumInputBytes, long maximumOutputTokens, double maximumCostUsd,
            String judgmentPolicy, Qualification qualification) {
    }

    public record Registry(String schemaVersion, String profileRevision, List<Profile> profiles) {
    }

    public record JevSettings(boolean transmissionAllowed) {
    }

    public record Runtime(Config config, Registry registry, JevSettings jev) {
    }

    public record Usage(Long inputTokens, Long outputTokens, Long cachedInputTokens, Double actualCostUsd) {
        static Usage unknown() {
            return new Usage(null, null, null, null);
        }
    }

    public record Score(String skillId, double value) {
    }

    public record Diagnostics(ScoreKind scoreKind, List<Score> scores) {
    }

    /** One cited source of a confirmed-context member; the field is the caller's word and is checked against {@link ContextField} where the request is made. */
    public record ContextSource(String field, String reference) {
    }

    /** The confirmed context of a request: every member is there, null when the task does not say. */
    public record ConfirmedContext(String taskRevision, String objective, List<String> actions, List<String> targets, List<String> constraints, List<String> prohibitedActions, String background) {
        /** Whether the task says anything for this member. */
        boolean supplied(ContextField field) {
            return switch (field) {
                case TASK_REVISION -> taskRevision != null;
                case OBJECTIVE -> objective != null;
                case ACTIONS -> actions != null;
                case TARGETS -> targets != null;
                case CONSTRAINTS -> constraints != null;
                case PROHIBITED_ACTIONS -> prohibitedActions != null;
                case BACKGROUND -> background != null;
            };
        }
    }

    public record Judgment(String skillId, Verdict judgment, List<String> reasonRefs, ReasonCode uncertaintyReason) {
    }

    public record Unresolved(String skillId, ReasonCode reasonCode) {
    }

    public record ResponseError(ReasonCode code, boolean retryable, DispatchState dispatchState) {
    }

    public record Response(String schemaVersion, String requestId, String operationKey, String requestDigest, String inventoryDigest, ResponseStatus status, List<Judgment> judgments,
            List<Unresolved> unresolvedItems, ResponseError error) {
    }

    public record RateLimit(int httpStatus, String retryAfter) {
    }

    public record Attempt(ProviderKind providerKind, String profileId, String modelId, String reasoningEffort, DispatchState dispatchState, ResponseStatus status, ReasonCode errorCode,
            boolean timedOut, Usage usage, double reservedCostUsd, Diagnostics diagnostics, RateLimit rateLimitObservation) {
    }

    public record Skipped(ProviderKind providerKind, String profileId, ReasonCode reasonCode, DispatchState dispatchState) {
    }

    public record Snapshot(String taskRevision, String configRevision, String profileRevision, String inventoryDigest, String requestDigest, boolean cancelled) {
    }

    /** The request of one classification. Its digest is over the request without the digest, so the unsigned request leaves the member out. */
    public record Request(String schemaVersion, String requestId, String operationKey, String originalPrompt, String promptDigest, ConfirmedContext confirmedContext,
            List<ContextSource> contextSources, List<Skill> skills, String inventoryDigest, String taxonomyRevision, String classificationCriteriaRef,
            @JsonInclude(JsonInclude.Include.NON_NULL) String requestDigest) {
        Request signed(String digest) {
            return new Request(schemaVersion, requestId, operationKey, originalPrompt, promptDigest, confirmedContext, contextSources, skills, inventoryDigest, taxonomyRevision,
                    classificationCriteriaRef, digest);
        }
    }

    public record Result(Request request, Response response, Config config, String profileRevision, List<Attempt> attempts, List<Skipped> skipped, Snapshot snapshot) {
    }

    /**
     * What {@code classify_skills} gives back of the request: the caller's own words and the skills the server added are not repeated (the digests bind them), so the answer stays small
     * whatever the inventory holds.
     */
    public record RequestView(String schemaVersion, String requestId, String operationKey, String promptDigest, String inventoryDigest, String taxonomyRevision, String classificationCriteriaRef,
            int skillCount, String requestDigest) {
        static RequestView of(Request request) {
            return new RequestView(request.schemaVersion(), request.requestId(), request.operationKey(), request.promptDigest(), request.inventoryDigest(), request.taxonomyRevision(),
                    request.classificationCriteriaRef(), request.skills().size(), request.requestDigest());
        }
    }

    /** A {@link Result} as {@code classify_skills} answers it: the same members, the request as a {@link RequestView}. */
    public record ResultView(RequestView request, Response response, Config config, String profileRevision, List<Attempt> attempts, List<Skipped> skipped, Snapshot snapshot) {
        static ResultView of(Result result) {
            return new ResultView(RequestView.of(result.request()), result.response(), result.config(), result.profileRevision(), result.attempts(), result.skipped(), result.snapshot());
        }
    }

    /**
     * One skill's judgment with the evaluator's confidence in it, side by side. The confidence is in the judgment the evaluator gave, whatever it is: 0.98 on {@code not-needed} is
     * near certainty that the skill is not needed, and 0.19 on {@code needed} is a weak {@code needed}. It is not how much the skill is needed.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AdvisedSkill(String skillId, Verdict judgment, Double confidence) {
    }

    /** The task as the host's prompt marker shows it; {@link #NOT_OBSERVED} when the host's marker shows none (no observation of the call, no prompt yet). */
    public record TaskObservation(String taskRevision, String requestDigest, boolean cancelled, String sourceRef) {
        public static final TaskObservation NOT_OBSERVED = new TaskObservation(null, null, false, null);

        public boolean observed() {
            return this != NOT_OBSERVED;
        }
    }

    /** What the server itself saw about this call; a caller cannot supply it. */
    public record CallObservation(String actorId, String host, String observationId, String observedAt) {
    }

    /** The vendor a request names, as the tool contract states it. */
    public record VendorContext(String vendorId, String reference) {
    }

    /** The input of {@code classify_skills}: the tool's published schema has passed, and its members are exactly these. */
    public record ClassifyInput(String schemaVersion, String requestId, String operationKey, String originalPrompt, ConfirmedContext confirmedContext, List<ContextSource> contextSources,
            List<String> explicitSkillIds, List<String> ruleRequiredSkillIds, VendorContext vendorContext, boolean publicSynthetic, String sessionRef) {
    }

    /** What the digest of an intake covers: the request, the required skills, the vendor and who asked. */
    public record Intake(String requestDigest, List<String> explicitSkillIds, List<String> ruleRequiredSkillIds, VendorContext vendorContext, String actorId) {
    }

    public record SelectionReason(String skillId, String reason) {
    }

    public record ApplicabilityCheck(String skillId, Boolean applies, Boolean excluded, List<String> reasonRefs) {
    }

    public record UnresolvedReference(String reference, String reason) {
    }

    /** The receipt the server writes for what it observed; a caller's own receipt is refused. */
    public record HostReceipt(String receiptId, String host, String requestDigest, String inventoryDigest, List<String> agentSelectedSkillIds, String acceptedAt) {
    }

    /** The agent's selection. {@code hostReceipt} is null as the caller sends it and set by the server when the selection is recorded. */
    public record Decision(String schemaVersion, String classificationResponseRef, String requestDigest, String inventoryDigest, String taskRevision, String configRevision,
            String profileRevision, List<String> explicitSkillIds, List<String> ruleRequiredSkillIds, List<String> agentSelectedSkillIds, List<SelectionReason> selectionReasons,
            List<ApplicabilityCheck> applicabilityChecks, List<UnresolvedReference> unresolvedSkillReferences, SelectionStatus selectionStatus, boolean adviceApplied,
            HostReceipt hostReceipt) {
        Decision withReceipt(HostReceipt receipt) {
            return new Decision(schemaVersion, classificationResponseRef, requestDigest, inventoryDigest, taskRevision, configRevision, profileRevision, explicitSkillIds, ruleRequiredSkillIds,
                    agentSelectedSkillIds, selectionReasons, applicabilityChecks, unresolvedSkillReferences, selectionStatus, adviceApplied, receipt);
        }
    }

    /** The input of {@code record_skill_selection}: the tool's published schema has passed, and its members are exactly these. */
    public record SelectionInput(String schemaVersion, String operationKey, SelectionSource selectionSource, String sessionRef, Decision decision) {
    }

    /** The server's proof that an independent selection followed a helper failure. */
    public record IndependentSelection(SelectionSource source, String operationKey, String actorId, String taskSourceRef, String observationId, String requestDigest, String inventoryDigest,
            String runtimeDigest, String helperResultDigest, String helperResponseRef, String helperAttemptsDigest, ReasonCode helperFailureCode, String decisionDigest) {
    }

    /** A selected or required skill that cannot run, and why. */
    public record BlockedItem(String skillId, BlockReason reasonCode) {
    }
}
