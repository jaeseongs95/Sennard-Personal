// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.jaeseongs95.sennard.server.mcp.SelfSession;
import io.github.jaeseongs95.sennard.service.store.RunState;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * What the server does with the model, its class and its reasoning effort as a host reports them: it records them as spelled, whatever they are,
 * and it asks for no floor. Each case runs the real contract files and the real observation code.
 */
class ExecutionAssuranceTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final Path REPO = Path.of(System.getProperty("sennard.repo.root"));
    private static final Instant NOW = Instant.parse("2026-11-11T09:00:00.500Z");

    private static ContractValidator validator;
    private static ExecutionAssurance assurance;

    @BeforeAll
    static void wire() throws java.io.IOException {
        validator = new ContractValidator(MAPPER, new ContentRoot(REPO));
        assurance = new ExecutionAssurance(Clock.fixed(NOW, ZoneOffset.UTC), MAPPER);
    }

    private static WorkflowCall call(SelfSession.Execution execution, String callId) {
        return new WorkflowCall("codex", "session-1", "codex:actor-1", new ObservedCaller("codex:actor-1", List.of(), Provenance.UNAVAILABLE), execution, callId, "en");
    }

    private static ExecutionContext observed(String model, String effort) {
        SelfSession.Execution execution = new SelfSession.Execution.Observed(model, effort, "turn-1", SelfSession.Execution.Observed.Evidence.HOST_REPORT);
        ExecutionContext context = assurance.observe(call(execution, "call-0000000000000001"), ExecutionAssurance.Binding.bootstrap("task-1"));
        assertNotNull(context);
        return context;
    }

    /** The context as a host-reported observation with other facts, for the cases that need a class or an effort the observation did not carry. */
    private static ExecutionContext with(ExecutionContext context, String modelClass, String effort) {
        return new ExecutionContext(context.schemaVersion(), context.model(), modelClass, effort, context.policyNotes(), context.source(), context.observedAt(), context.observationId(), context.taskId(),
                context.runId(), context.stageId(), context.revision(), context.actorId(), context.expiresAt());
    }

    private static final ExecutionRequirement SEMANTIC = ExecutionRequirement.SEMANTIC;

    @ParameterizedTest
    @ValueSource(strings = {"none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra", "turbo-9", "ULTRA-MAX", "Ultra Max 2", "x", "思考-9"})
    void anEffortOfAnySpellingIsRecordedAsTheHostReportedIt(String effort) {
        ExecutionContext context = observed("a-model-nobody-listed", effort);
        assertEquals(effort, context.reasoningEffort());
        assertEquals(List.of("MODEL_NOT_IN_CATALOG"), context.policyNotes());
        assertDoesNotThrow(() -> validator.assertValid("execution-context.v1.schema.json", context));
        assertDoesNotThrow(() -> assurance.assertContext(SEMANTIC, context, ExecutionAssurance.BOOTSTRAP));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void noEffortIsRecordedAsNoneReportedAndIsNotRefused(String effort) {
        ExecutionContext context = observed("a-model-nobody-listed", effort);
        assertNull(context.reasoningEffort());
        assertEquals(List.of("HOST_EFFORT_UNREPORTED", "MODEL_NOT_IN_CATALOG"), context.policyNotes());
        assertDoesNotThrow(() -> validator.assertValid("execution-context.v1.schema.json", context));
        assertDoesNotThrow(() -> assurance.assertContext(SEMANTIC, context, ExecutionAssurance.BOOTSTRAP));
    }

    @ParameterizedTest
    @ValueSource(strings = {"lightweight", "frontier", "mythic", "tier 7", "x"})
    void aModelClassNoListNamesIsRecordedAndAccepted(String modelClass) {
        ExecutionContext context = with(observed("a-model-nobody-listed", "high"), modelClass, "high");
        assertDoesNotThrow(() -> validator.assertValid("execution-context.v1.schema.json", context));
        assertDoesNotThrow(() -> assurance.assertContext(SEMANTIC, context, ExecutionAssurance.BOOTSTRAP));
    }

    @Test
    void aClassOrEffortThatIsPresentButEmptyIsNotAnObservation() {
        ExecutionContext base = observed("a-model-nobody-listed", "high");
        for (String field : List.of("modelClass", "reasoningEffort")) {
            ExecutionContext context = "modelClass".equals(field) ? with(base, "", "high") : with(base, null, "");
            assertThrows(Exception.class, () -> validator.assertValid("execution-context.v1.schema.json", context), field);
            WorkflowFailure failure = assertThrows(WorkflowFailure.class, () -> assurance.assertContext(SEMANTIC, context, ExecutionAssurance.BOOTSTRAP), field);
            assertEquals(WorkflowCode.BINDING_INVALID, failure.code(), field);
        }
    }

    @Test
    void aModelTakenFromARequestRecordSaysSoInThePolicyNotes() {
        SelfSession.Execution execution = new SelfSession.Execution.Observed("a-request-model", "high", null, SelfSession.Execution.Observed.Evidence.REQUEST_RECORD);
        ExecutionContext context = assurance.observe(call(execution, "call-0000000000000002"), ExecutionAssurance.Binding.bootstrap("task-1"));
        assertNotNull(context);
        assertEquals(List.of("MODEL_NOT_IN_CATALOG", "MODEL_FROM_REQUEST_RECORD"), context.policyNotes());
        assertDoesNotThrow(() -> validator.assertValid("execution-context.v1.schema.json", context));
    }

    @Test
    void aModelFromTheLineOfTheCallOrTheHostsOwnReportHasNoRequestRecordNote() {
        for (SelfSession.Execution.Observed.Evidence source : new SelfSession.Execution.Observed.Evidence[] {SelfSession.Execution.Observed.Evidence.CALL_RECORD, SelfSession.Execution.Observed.Evidence.HOST_REPORT, null}) {
            SelfSession.Execution execution = new SelfSession.Execution.Observed("a-model", "high", null, source);
            ExecutionContext context = assurance.observe(call(execution, "call-0000000000000003"), ExecutionAssurance.Binding.bootstrap("task-1"));
            assertEquals(List.of("MODEL_NOT_IN_CATALOG"), context.policyNotes(), String.valueOf(source));
        }
    }

    @Test
    void anUnseenModelIsRecordedWithItsClassUnknown() {
        ExecutionContext context = observed("a-model-that-did-not-exist-yesterday", "high");
        assertEquals("a-model-that-did-not-exist-yesterday", context.model());
        assertNull(context.modelClass());
        assertEquals(List.of("MODEL_NOT_IN_CATALOG"), context.policyNotes());
    }

    /**
     * AGS3-D-0168: a subagent whose call the host has not yet given a model for is refused with the way on (call again; the agent that started it records),
     * where the plain refusal left the run open with no word of either. Another caller and another subject keep the plain refusal.
     */
    @Test
    void aSubagentsStageThatIsNotObservedIsToldWhatToDoNext() {
        ObservedCaller child = new ObservedCaller("claude-code:actor-2", List.of("claude-code:actor-1"), Provenance.HOST_OBSERVED);
        SelfSession.Execution unobserved = new SelfSession.Execution.Unobserved("HOOK_MODEL_NOT_REPORTED");
        ExecutionAssurance.Binding stage = new ExecutionAssurance.Binding("stage", "task-1", "run-1", "stage-01", 0L);

        WorkflowFailure refused = assertThrows(WorkflowFailure.class,
                () -> assurance.observeTrusted(new WorkflowCall("claude-code", "session-1", "claude-code:actor-2", child, unobserved, "toolu_0000000000000001", "en"), stage, ExecutionAssurance.stageSubject("stage-01")));
        assertEquals(WorkflowCode.BINDING_REQUIRED, refused.code());
        assertEquals("workflow.service.subagentStageUnobserved", refused.ref().key());

        WorkflowFailure rootRefused = assertThrows(WorkflowFailure.class, () -> assurance.observeTrusted(call(unobserved, "toolu_0000000000000002"), stage, ExecutionAssurance.stageSubject("stage-01")));
        assertEquals("workflow.service.requiresTrustedAttestation", rootRefused.ref().key());
        WorkflowFailure bootstrapRefused = assertThrows(WorkflowFailure.class,
                () -> assurance.observeTrusted(new WorkflowCall("claude-code", "session-1", "claude-code:actor-2", child, unobserved, "toolu_0000000000000003", "en"), ExecutionAssurance.Binding.bootstrap("task-1"),
                        ExecutionAssurance.BOOTSTRAP));
        assertEquals("workflow.service.requiresTrustedAttestation", bootstrapRefused.ref().key());

        SelfSession.Execution seen = new SelfSession.Execution.Observed("a-model", "high", "turn-1", SelfSession.Execution.Observed.Evidence.CALL_RECORD);
        assertNotNull(assurance.observeTrusted(new WorkflowCall("claude-code", "session-1", "claude-code:actor-2", child, seen, "toolu_0000000000000004", "en"), stage, ExecutionAssurance.stageSubject("stage-01")),
                "the next call of the same subagent is observed anew");
    }

    @Test
    void aRequirementNamesNoFloor() {
        for (ExecutionRequirement kind : List.of(ExecutionRequirement.SEMANTIC, ExecutionRequirement.DETERMINISTIC)) {
            JsonNode requirement = MAPPER.valueToTree(kind);
            assertEquals(new TreeSet<>(List.of("kind", "observationRequired", "policyId")), new TreeSet<>(new ArrayList<>(requirement.propertyNames())), requirement.toString());
            assertDoesNotThrow(() -> validator.assertValid("execution-requirement.v1.schema.json", kind));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"minimumModelClass", "minimumReasoningEffort"})
    void theRemovedFloorFieldsAreNotPartOfTheContractAnyMore(String field) {
        ObjectNode requirement = MAPPER.valueToTree(ExecutionRequirement.SEMANTIC);
        requirement.putNull(field);
        assertThrows(Exception.class, () -> validator.assertValid("execution-requirement.v1.schema.json", requirement));
    }

    /** A deterministic stage is one a skill's check alone decides, so it asks for no observation; a semantic one asks for it. */
    @Test
    void aDeterministicRequirementAsksForNoObservationAndASemanticOneDoes() {
        assertEquals(ExecutionKind.DETERMINISTIC, ExecutionRequirement.DETERMINISTIC.kind());
        assertEquals(false, ExecutionRequirement.DETERMINISTIC.observationRequired());
        assertEquals(ExecutionKind.SEMANTIC, ExecutionRequirement.SEMANTIC.kind());
        assertEquals(true, ExecutionRequirement.SEMANTIC.observationRequired());
        assertDoesNotThrow(() -> assurance.assertContext(ExecutionRequirement.DETERMINISTIC, null, ExecutionAssurance.stageSubject("stage-01")), "no observation, no refusal");
        WorkflowFailure failure = assertThrows(WorkflowFailure.class, () -> assurance.assertContext(SEMANTIC, null, ExecutionAssurance.stageSubject("stage-01")));
        assertEquals(WorkflowCode.BINDING_REQUIRED, failure.code());
    }

    /** The contract ties the observation to the kind, and so does the record: neither says a deterministic stage needs it or a semantic one does not. */
    @Test
    void aRequirementThatContradictsItsKindIsRefusedByTheContractAndByTheRecord() {
        for (String contradiction : List.of("{\"policyId\":\"semantic-execution-assurance-v1\",\"kind\":\"deterministic\",\"observationRequired\":true}",
                "{\"policyId\":\"semantic-execution-assurance-v1\",\"kind\":\"semantic\",\"observationRequired\":false}")) {
            JsonNode requirement = MAPPER.readTree(contradiction);
            assertThrows(Exception.class, () -> validator.assertValid("execution-requirement.v1.schema.json", requirement), contradiction);
            assertThrows(Exception.class, () -> MAPPER.treeToValue(requirement, ExecutionRequirement.class), contradiction);
        }
        for (String missing : List.of("kind", "observationRequired")) {
            ObjectNode requirement = MAPPER.valueToTree(ExecutionRequirement.SEMANTIC);
            requirement.remove(missing);
            assertThrows(Exception.class, () -> validator.assertValid("execution-requirement.v1.schema.json", requirement), missing);
        }
    }

    @Test
    void anObservationOfAnyModelPassesWhateverTheEffortWasBeforeNoFloorExists() {
        // Before: a high-risk stage asked for class "general" and effort "high", and a lighter observation was noted below it. Now the stage asks for neither.
        for (String effort : List.of("none", "minimal", "low")) {
            ExecutionContext context = observed("any-model-at-all", effort);
            assertDoesNotThrow(() -> assurance.assertContext(SEMANTIC, context, ExecutionAssurance.stageSubject("stage-01")));
        }
    }

    @Test
    void anObservationIsBoundToTheTaskTheRunTheStageAndTheRevisionItWasMadeFor() {
        ExecutionAssurance.Binding stage = new ExecutionAssurance.Binding("stage", "task-1", "run-1", "stage-01", 4L);
        SelfSession.Execution execution = new SelfSession.Execution.Observed("m", "high", "turn-1", SelfSession.Execution.Observed.Evidence.HOST_REPORT);
        ExecutionContext context = assurance.observe(call(execution, "call-0000000000000001"), stage);
        assertEquals("run-1", context.runId());
        assertEquals(4L, context.revision());
        ExecutionAssurance.Subject subject = ExecutionAssurance.stageSubject("stage-01");
        assertDoesNotThrow(() -> assurance.assertTrusted(SEMANTIC, context, subject, stage));
        for (ExecutionAssurance.Binding other : List.of(new ExecutionAssurance.Binding("stage", "task-2", "run-1", "stage-01", 4L), new ExecutionAssurance.Binding("stage", "task-1", "run-2", "stage-01", 4L),
                new ExecutionAssurance.Binding("stage", "task-1", "run-1", "stage-02", 4L), new ExecutionAssurance.Binding("stage", "task-1", "run-1", "stage-01", 5L),
                new ExecutionAssurance.Binding("stage", "task-1", "run-1", "stage-01", null))) {
            WorkflowFailure refused = assertThrows(WorkflowFailure.class, () -> assurance.assertTrusted(SEMANTIC, context, subject, other));
            assertEquals(WorkflowCode.BINDING_INVALID, refused.code(), other.toString());
        }
    }

    @Test
    void anObservationFromTheFutureBeyondTheClockSkewIsRefused() {
        ExecutionAssurance.Binding bootstrap = ExecutionAssurance.Binding.bootstrap("task-1");
        ExecutionContext fresh = observed("m", "high");
        assertDoesNotThrow(() -> assurance.assertTrusted(SEMANTIC, fresh, ExecutionAssurance.BOOTSTRAP, bootstrap));
        ExecutionContext future = new ExecutionContext(fresh.schemaVersion(), fresh.model(), fresh.modelClass(), fresh.reasoningEffort(), fresh.policyNotes(), fresh.source(), NOW.plusSeconds(60),
                fresh.observationId(), fresh.taskId(), fresh.runId(), fresh.stageId(), fresh.revision(), fresh.actorId(), null);
        assertEquals(WorkflowCode.BINDING_INVALID, assertThrows(WorkflowFailure.class, () -> assurance.assertTrusted(SEMANTIC, future, ExecutionAssurance.BOOTSTRAP, bootstrap)).code());
    }

    @Test
    void aMissingContextRequiresAnObservationAndATreeThatIsNoContextIsRefusedWhereItIsBound() {
        assertEquals(WorkflowCode.BINDING_REQUIRED, assertThrows(WorkflowFailure.class, () -> assurance.assertContext(SEMANTIC, null, ExecutionAssurance.BOOTSTRAP)).code());
        // A context is read where a document that holds one is bound (a plan, a stored receipt), and one that is no context is refused there, with the reading failure as the cause.
        JsonNode good = MAPPER.valueToTree(observed("m", "high"));
        assertEquals("m", validator.bind("execution-context.v1.schema.json", good, ExecutionContext.class).model());
        ObjectNode badSource = (ObjectNode) good.deepCopy();
        badSource.put("source", "somewhere");
        WorkflowFailure unreadable = assertThrows(WorkflowFailure.class, () -> validator.bind("execution-context.v1.schema.json", badSource, ExecutionContext.class));
        assertEquals(WorkflowCode.INVALID_INPUT, unreadable.code());
        assertNotNull(unreadable.getCause());
        ObjectNode badTime = (ObjectNode) good.deepCopy();
        badTime.put("observedAt", "yesterday");
        assertEquals(WorkflowCode.INVALID_INPUT, assertThrows(WorkflowFailure.class, () -> validator.bind("execution-context.v1.schema.json", badTime, ExecutionContext.class)).code());
        assertEquals(WorkflowCode.INVALID_INPUT, assertThrows(WorkflowFailure.class, () -> validator.read("execution-context.v1.schema.json", badSource, ExecutionContext.class)).code(), "the schema refuses it first");
    }

    /** A deterministic stage is the Korean prose finalization: a stage a plan makes without a requirement is allowed there and nowhere else. */
    @Test
    void aStrictPlanAllowsAStageWithoutARequirementOnlyWhereACheckAloneDecidesIt() throws java.io.IOException {
        PlanSamples.Wiring wiring = PlanSamples.wire();
        ExecutionContext bootstrap = PlanSamples.bootstrap("task-1");
        WorkflowPlan semantic = wiring.planner().build(PlanSamples.task("task-1", RiskLevel.LOW, List.of("minimal-implementation"), true), wiring.registry().read(), null, bootstrap, null, PlanSamples.KEY);
        WorkflowPlan.Stage stage = semantic.stages().getFirst();
        assertEquals(ExecutionKind.SEMANTIC, stage.executionRequirement().kind());
        WorkflowPlan withoutRequirement = withStage(semantic, PlanSamples.withRequirement(stage, null));
        assertEquals(WorkflowCode.BINDING_REQUIRED, assertThrows(WorkflowFailure.class, () -> assurance.assertStrictPlan(withoutRequirement, "guardedStart")).code(),
                "a stage that needs the observation carries a requirement");

        WorkflowPlan.Stage finalization = new WorkflowPlan.Stage(stage.stageId(), stage.order(), "korean-prose-finalization", stage.evaluationAuditPurpose(), stage.requiredAuditPhase(),
                List.of("korean-prose-finalization"), "korean-prose-editor", stage.phase(), stage.selectionReason(), stage.state(), null, stage.artifactContract(), stage.requiredArtifacts(),
                stage.riskGate(), stage.providerKey(), stage.executionClass(), stage.phaseOrder(), stage.requiredInputArtifacts(), stage.inputBindings(), stage.producedArtifacts(), stage.outputSchema(),
                stage.resultSchema(), stage.stateMapping(), stage.gate(), stage.receiptPolicy());
        assertDoesNotThrow(() -> assurance.assertStrictPlan(withStage(semantic, finalization), "guardedStart"), "a check alone decides this stage");
    }

    private static WorkflowPlan withStage(WorkflowPlan plan, WorkflowPlan.Stage stage) {
        return new WorkflowPlan(plan.schemaVersion(), plan.taskId(), plan.taskDigest(), plan.integrityToken(), plan.executionMode(), plan.state(), plan.selectedSkills(), List.of(stage), plan.currentStageId(),
                plan.nextStageId(), plan.errors(), plan.bootstrapExecution());
    }

    /** The gate asks a stage for the observed model only when its requirement is the semantic one; a stage the check alone decides passes without it. */
    @Test
    void aStageGateAsksForTheObservedModelOfASemanticStageAndNotOfADeterministicOne() {
        WorkflowPlan.Stage plain = PlanSamples.stage("stage-01", "minimal-implementation", List.of(), PlanSamples.openGate(), null);
        StageResult passedWithoutContext = new StageResult("1.0.0", "run-1", "stage-01", 0, RunState.PASSED,
                new ProviderResult("1.0.0", OutputKind.OUTPUT, MAPPER.createObjectNode(), List.of(), null), List.of(), List.of(), List.of(), null, null, null, null, null);
        StageGates gates = new StageGates(validator, MAPPER, null, null);
        WorkflowFailure semantic = assertThrows(WorkflowFailure.class, () -> gates.assertExecutionAssurance(PlanSamples.withRequirement(plain, ExecutionRequirement.SEMANTIC), passedWithoutContext, assurance));
        assertEquals(WorkflowCode.BINDING_REQUIRED, semantic.code());
        assertDoesNotThrow(() -> gates.assertExecutionAssurance(PlanSamples.withRequirement(plain, ExecutionRequirement.DETERMINISTIC), passedWithoutContext, assurance));
    }

    /** The claim that the observed model is unverified is made for a semantic stage and not for a deterministic one, which has none to verify. */
    @Test
    void anUnverifiedExecutionProfileIsClaimedOnlyForASemanticStage() {
        WorkflowPlan.Stage plain = PlanSamples.stage("stage-01", "minimal-implementation", List.of(), PlanSamples.openGate(), null);
        ExecutionContext context = PlanSamples.bootstrap("task-1");
        StageResult passed = new StageResult("1.0.0", "run-1", "stage-01", 0, RunState.PASSED,
                new ProviderResult("1.0.0", OutputKind.OUTPUT, MAPPER.createObjectNode(), List.of(), null), List.of(), List.of(), List.of(), null, context, null, null, null);
        List<UnverifiedClaimEntry> semantic = UnverifiedClaims.forStage(PlanSamples.withRequirement(plain, ExecutionRequirement.SEMANTIC), passed, false, List.of(), false, false);
        List<UnverifiedClaimEntry> deterministic = UnverifiedClaims.forStage(PlanSamples.withRequirement(plain, ExecutionRequirement.DETERMINISTIC), passed, false, List.of(), false, false);
        assertTrue(semantic.stream().anyMatch(entry -> entry.claim() == UnverifiedClaim.EXECUTION_PROFILE), semantic.toString());
        assertFalse(deterministic.stream().anyMatch(entry -> entry.claim() == UnverifiedClaim.EXECUTION_PROFILE), deterministic.toString());
    }

    @Test
    void aStrictPlanNeedsItsBootstrapObservationAndARequirementOnEveryStage() throws java.io.IOException {
        PlanSamples.Wiring wiring = PlanSamples.wire();
        TaskEnvelope task = PlanSamples.task("task-1", RiskLevel.LOW, List.of("minimal-implementation"), true);
        ExecutionContext bootstrap = PlanSamples.bootstrap("task-1");
        WorkflowPlan strict = wiring.planner().build(task, wiring.registry().read(), null, bootstrap, null, PlanSamples.KEY);
        assertDoesNotThrow(() -> assurance.assertStrictPlan(strict, "guardedStart"));
        WorkflowPlan direct = wiring.planner().build(PlanSamples.task("task-1", RiskLevel.LOW, List.of("minimal-implementation"), false), wiring.registry().read(), null, null, null, PlanSamples.KEY);
        assertEquals(WorkflowCode.BINDING_REQUIRED, assertThrows(WorkflowFailure.class, () -> assurance.assertStrictPlan(direct, "guardedStart")).code());
        WorkflowPlan otherTask = wiring.planner().build(PlanSamples.task("task-2", RiskLevel.LOW, List.of("minimal-implementation"), true), wiring.registry().read(), null, bootstrap, null, PlanSamples.KEY);
        assertEquals(WorkflowCode.BINDING_INVALID, assertThrows(WorkflowFailure.class, () -> assurance.assertStrictPlan(otherTask, "guardedStart")).code(), "the bootstrap is bound to another task");
    }
}
