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

import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import io.github.jaeseongs95.sennard.domain.Digests;
import io.github.jaeseongs95.sennard.server.contract.CanonicalJson;
import io.github.jaeseongs95.sennard.service.store.RunState;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * The plan documents are records that Jackson writes and reads: the plan, its stages, the execution context and requirement, and the skill providers the planner
 * reads from the registry. Each case runs against the contract files and the registry of the repository:
 * <ol>
 * <li>a plan built for every capability the registry offers passes the plan schema, and reading it and writing it again gives the same canonical form, so the
 * signature and the digests that are made over that form do not change with the record;</li>
 * <li>the signature a plan carries is the one its record gives after a round trip, and a plan that was changed or has a forged token is refused;</li>
 * <li>the keys the plan has always written are written: the pointers as {@code null}, the bootstrap only for a plan that has one;</li>
 * <li>a time is written as the text {@code Instant.toString()} gives and read back as the same instant;</li>
 * <li>the default of a state mapping is the text {@code reject} or a rule, and any other text is refused;</li>
 * <li>the registry is read into providers that carry the digest of each schema file they name.</li>
 * </ol>
 */
class PlanDocumentsTest {
    private static PlanSamples.Wiring wiring;
    private static List<SkillProvider> providers;

    @BeforeAll
    static void wire() throws IOException {
        wiring = PlanSamples.wire();
        providers = wiring.registry().read();
    }

    static List<String> capabilities() throws IOException {
        Set<String> all = new LinkedHashSet<>();
        PlanSamples.wire().registry().read().forEach(provider -> all.addAll(provider.capabilities()));
        return new ArrayList<>(all);
    }

    private static WorkflowPlan planOf(String capability, boolean orchestrated) {
        TaskEnvelope task = PlanSamples.task("task-1", RiskLevel.LOW, List.of(capability), orchestrated);
        ExecutionContext context = orchestrated ? PlanSamples.bootstrap("task-1") : null;
        return wiring.planner().build(task, providers, EvaluationAuditPurpose.QUALITY_OR_RELEASE, context, AuditPhase.POST_EXECUTION, PlanSamples.KEY);
    }

    private static JsonNode tree(Object document) {
        return PlanSamples.MAPPER.valueToTree(document);
    }

    private static void assertRoundTrip(WorkflowPlan plan) {
        String text = PlanSamples.MAPPER.writeValueAsString(plan);
        WorkflowPlan read = PlanSamples.MAPPER.readValue(text, WorkflowPlan.class);
        assertEquals(CanonicalJson.of(PlanSamples.MAPPER.readTree(text)), CanonicalJson.of(tree(read)));
        assertEquals(CanonicalJson.digest(tree(plan)), CanonicalJson.digest(tree(read)));
        assertEquals(plan.integrityToken(), wiring.planner().sign(read, PlanSamples.KEY), "the signature of the plan read back is the one the plan carries");
        assertDoesNotThrow(() -> wiring.planner().assertIntegrity(read, PlanSamples.KEY));
    }

    @ParameterizedTest
    @MethodSource("capabilities")
    void aPlanForEachCapabilityOfTheRegistryPassesItsSchemaAndRoundTripsToTheSameCanonicalFormAndSignature(String capability) {
        for (boolean orchestrated : new boolean[] {true, false}) {
            WorkflowPlan plan = planOf(capability, orchestrated);
            assertDoesNotThrow(() -> wiring.validator().workflowPlan(plan), capability);
            assertTrue(plan.integrityToken().matches("^[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]$"), plan.integrityToken());
            assertRoundTrip(plan);
            plan.stages().forEach(stage -> assertEquals(orchestrated, stage.executionRequirement() != null, stage.stageId()));
        }
    }

    @Test
    void aPlanThatIsReadyPointsAtItsFirstStageAndWritesTheCurrentPointerAsNull() {
        WorkflowPlan plan = planOf("minimal-implementation", true);
        assertEquals(RunState.READY, plan.state());
        assertEquals(1, plan.stages().size());
        assertEquals(plan.stages().get(0).stageId(), plan.nextStageId());
        JsonNode tree = tree(plan);
        assertTrue(tree.get("currentStageId").isNull(), tree.toString());
        assertEquals(plan.nextStageId(), tree.get("nextStageId").asString());
        assertEquals("orchestrated", tree.get("executionMode").asString());
        assertEquals("READY", tree.get("state").asString());
        assertEquals(PlanSamples.OBSERVED.toString(), tree.get("bootstrapExecution").get("context").get("observedAt").asString());
        assertEquals("semantic-execution-assurance-v1", tree.get("bootstrapExecution").get("requirement").get("policyId").asString());
        assertEquals(tree.get("bootstrapExecution").get("requirement"), tree.get("stages").get(0).get("executionRequirement"));
        assertTrue(plan.taskDigest().startsWith("sha256:"));
    }

    @Test
    void aDirectPlanWritesNoBootstrapAndNoRequirementAndNoOptionalKeyOfAStage() {
        WorkflowPlan plan = planOf("minimal-implementation", false);
        JsonNode tree = tree(plan);
        assertEquals("direct", tree.get("executionMode").asString());
        assertFalse(tree.has("bootstrapExecution"));
        JsonNode stage = tree.get("stages").get(0);
        for (String key : List.of("executionRequirement", "artifactContract", "evaluationAuditPurpose", "requiredAuditPhase", "receiptPolicy")) {
            assertFalse(stage.has(key), key);
        }
        assertTrue(stage.get("gate").get("validator").isNull() || stage.get("gate").get("validator").isString());
        assertTrue(stage.get("gate").has("validatorSchema"), "the gate always writes its validator schema, as null when it has none");
    }

    @Test
    void aPlanWithAnErrorIsBlockedAndCarriesTheErrorBodyAsTheServerRendersIt() {
        WorkflowPlan plan = planOf("no-such-capability", true);
        assertEquals(RunState.BLOCKED, plan.state());
        assertEquals(List.of(), plan.stages());
        assertNull(plan.nextStageId());
        assertEquals(1, plan.errors().size());
        assertEquals("GATE_FAILED", plan.errors().get(0).code());
        assertEquals("workflow.service.noProviderForCapability", plan.errors().get(0).messageKey());
        JsonNode tree = tree(plan);
        assertTrue(tree.get("nextStageId").isNull());
        assertEquals("no-such-capability", tree.get("errors").get(0).get("details").get("capability").asString());
        assertDoesNotThrow(() -> wiring.validator().workflowPlan(plan));
        assertRoundTrip(plan);
    }

    @Test
    void aTaskOfHighRiskGetsTheMandatoryAuditStageWithItsFrozenPhaseAndItsVerdictArtifact() {
        TaskEnvelope task = PlanSamples.task("task-1", RiskLevel.HIGH, List.of("minimal-implementation"), true);
        WorkflowPlan plan = wiring.planner().build(task, providers, null, PlanSamples.bootstrap("task-1"), AuditPhase.PRE_DEPLOY, PlanSamples.KEY);
        WorkflowPlan.Stage audit = plan.stages().stream().filter(StageGates::mandatoryAudit).findFirst().orElseThrow();
        assertEquals(AuditPhase.PRE_DEPLOY, audit.requiredAuditPhase());
        assertTrue(audit.requiredArtifacts().contains("gate-verdict"));
        assertEquals("pre-deploy", tree(audit).get("requiredAuditPhase").asString());
        assertDoesNotThrow(() -> wiring.validator().workflowPlan(plan));
        assertRoundTrip(plan);
    }

    @Test
    void anEvaluationAuditStageCarriesItsPurposeAndAPlanWithoutOneIsRefused() {
        WorkflowPlan plan = planOf("evaluation-validity-audit", true);
        assertEquals(EvaluationAuditPurpose.QUALITY_OR_RELEASE, plan.stages().get(0).evaluationAuditPurpose());
        assertEquals("quality-or-release", tree(plan.stages().get(0)).get("evaluationAuditPurpose").asString());
        TaskEnvelope task = PlanSamples.task("task-1", RiskLevel.LOW, List.of("evaluation-validity-audit"), true);
        WorkflowFailure refused = assertThrows(WorkflowFailure.class, () -> wiring.planner().build(task, providers, null, PlanSamples.bootstrap("task-1"), null, PlanSamples.KEY));
        assertEquals(WorkflowCode.INVALID_INPUT, refused.code());
    }

    @Test
    void aPlanThatWasChangedAfterItWasSignedOrCarriesAForgedTokenIsRefused() {
        WorkflowPlan plan = planOf("minimal-implementation", true);
        WorkflowPlan moved = plan.withStageState(plan.stages().get(0).stageId(), RunState.PASSED);
        assertEquals(WorkflowCode.INVALID_INPUT, assertThrows(WorkflowFailure.class, () -> wiring.planner().assertIntegrity(moved, PlanSamples.KEY)).code());
        WorkflowPlan forged = plan.withIntegrityToken("x".repeat(43));
        assertEquals(WorkflowCode.INVALID_INPUT, assertThrows(WorkflowFailure.class, () -> wiring.planner().assertIntegrity(forged, PlanSamples.KEY)).code());
        WorkflowPlan unsigned = plan.withIntegrityToken(null);
        assertEquals(WorkflowCode.INVALID_INPUT, assertThrows(WorkflowFailure.class, () -> wiring.planner().assertIntegrity(unsigned, PlanSamples.KEY)).code());
        byte[] otherKey = "abcdefghijklmnopqrstuvwxyz012345".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        assertEquals(WorkflowCode.INVALID_INPUT, assertThrows(WorkflowFailure.class, () -> wiring.planner().assertIntegrity(plan, otherKey)).code());
    }

    @Test
    void aMandatoryAuditStageWithoutItsFrozenPhaseIsRefusedEvenWhenSigned() {
        TaskEnvelope task = PlanSamples.task("task-1", RiskLevel.HIGH, List.of("minimal-implementation"), true);
        WorkflowPlan plan = wiring.planner().build(task, providers, null, PlanSamples.bootstrap("task-1"), AuditPhase.POST_EXECUTION, PlanSamples.KEY);
        List<WorkflowPlan.Stage> stages = plan.stages().stream().map(stage -> StageGates.mandatoryAudit(stage) ? withoutPhase(stage) : stage).toList();
        WorkflowPlan unframed = new WorkflowPlan(plan.schemaVersion(), plan.taskId(), plan.taskDigest(), "pending", plan.executionMode(), plan.state(), plan.selectedSkills(), stages, plan.currentStageId(),
                plan.nextStageId(), plan.errors(), plan.bootstrapExecution());
        WorkflowPlan signed = unframed.withIntegrityToken(wiring.planner().sign(unframed, PlanSamples.KEY));
        assertEquals(WorkflowCode.BINDING_REQUIRED, assertThrows(WorkflowFailure.class, () -> wiring.planner().assertIntegrity(signed, PlanSamples.KEY)).code());
    }

    private static WorkflowPlan.Stage withoutPhase(WorkflowPlan.Stage stage) {
        return new WorkflowPlan.Stage(stage.stageId(), stage.order(), stage.requiredCapability(), stage.evaluationAuditPurpose(), null, stage.satisfiedCapabilities(), stage.skillId(), stage.phase(),
                stage.selectionReason(), stage.state(), stage.executionRequirement(), stage.artifactContract(), stage.requiredArtifacts(), stage.riskGate(), stage.providerKey(), stage.executionClass(),
                stage.phaseOrder(), stage.requiredInputArtifacts(), stage.inputBindings(), stage.producedArtifacts(), stage.outputSchema(), stage.resultSchema(), stage.stateMapping(), stage.gate(),
                stage.receiptPolicy());
    }

    @Test
    void thePointersOfARunningPlanFollowItsReadyStages() {
        TaskEnvelope task = PlanSamples.task("task-1", RiskLevel.HIGH, List.of("minimal-implementation"), true);
        WorkflowPlan plan = wiring.planner().build(task, providers, null, PlanSamples.bootstrap("task-1"), AuditPhase.POST_EXECUTION, PlanSamples.KEY);
        assertTrue(plan.stages().size() >= 2, "a high-risk task plans its work and its audit");
        WorkflowPlan running = plan.withState(RunState.RUNNING).withRunningPointers();
        assertEquals(plan.stages().get(0).stageId(), running.currentStageId());
        assertEquals(plan.stages().get(1).stageId(), running.nextStageId());
        WorkflowPlan afterFirst = running.withStageState(plan.stages().get(0).stageId(), RunState.PASSED).withRunningPointers();
        assertEquals(plan.stages().get(1).stageId(), afterFirst.currentStageId());
        assertNull(afterFirst.nextStageId());
        WorkflowPlan blocked = running.blocked();
        assertEquals(RunState.BLOCKED, blocked.state());
        assertNull(blocked.currentStageId());
        assertNull(blocked.nextStageId());
        assertTrue(blocked.stage(plan.stages().get(0).stageId()).isPresent());
        assertTrue(blocked.stage("no-such-stage").isEmpty());
        assertTrue(tree(blocked).get("currentStageId").isNull());
    }

    @Test
    void anExecutionContextWritesItsTimeAsInstantToStringAndItsUnboundFieldsAsNull() {
        ExecutionContext context = PlanSamples.bootstrap("task-1");
        JsonNode tree = tree(context);
        assertEquals(Instant.parse("2026-11-11T09:00:00.123456789Z").toString(), tree.get("observedAt").asString());
        assertEquals("2026-11-11T09:00:00.123456789Z", tree.get("observedAt").asString());
        for (String key : List.of("modelClass", "runId", "stageId", "revision")) {
            assertTrue(tree.get(key).isNull(), key);
        }
        assertFalse(tree.has("expiresAt"));
        assertEquals("runtime", tree.get("source").asString());
        assertDoesNotThrow(() -> wiring.validator().assertValid("execution-context.v1.schema.json", context));
        ExecutionContext read = PlanSamples.MAPPER.readValue(PlanSamples.MAPPER.writeValueAsString(context), ExecutionContext.class);
        assertEquals(context, read);
        ExecutionContext whole = PlanSamples.MAPPER.readValue(
                "{\"schemaVersion\":\"1.0.0\",\"model\":\"m\",\"modelClass\":\"c\",\"reasoningEffort\":null,\"source\":\"spawn-result\",\"observedAt\":\"2026-11-11T09:00:00Z\",\"runId\":\"run-1\",\"stageId\":\"s\",\"revision\":4,"
                        + "\"expiresAt\":\"2026-11-11T09:05:00.5Z\"}",
                ExecutionContext.class);
        assertEquals(ExecutionSource.SPAWN_RESULT, whole.source());
        assertEquals(Instant.parse("2026-11-11T09:05:00.5Z"), whole.expiresAt());
        assertEquals(4L, whole.revision());
        assertEquals("2026-11-11T09:05:00.500Z", tree(whole).get("expiresAt").asString());
    }

    @Test
    void anExecutionContextWithASourceOrATimeTheContractDoesNotSpellCannotBeRead() {
        assertThrows(JacksonException.class, () -> PlanSamples.MAPPER.readValue("{\"schemaVersion\":\"1.0.0\",\"model\":\"m\",\"source\":\"somewhere\",\"observedAt\":\"2026-11-11T09:00:00Z\"}", ExecutionContext.class));
        assertThrows(JacksonException.class, () -> PlanSamples.MAPPER.readValue("{\"schemaVersion\":\"1.0.0\",\"model\":\"m\",\"source\":\"runtime\",\"observedAt\":\"yesterday\"}", ExecutionContext.class));
    }

    @Test
    void theRequirementIsItsPolicyItsKindAndItsObservationAndAnyOtherPolicyIsRefused() {
        JsonNode tree = tree(ExecutionRequirement.SEMANTIC);
        assertEquals(new TreeSet<>(List.of("kind", "observationRequired", "policyId")), new TreeSet<>(new ArrayList<>(tree.propertyNames())));
        assertEquals("semantic-execution-assurance-v1", tree.get("policyId").asString());
        assertEquals("semantic", tree.get("kind").asString());
        assertTrue(tree.get("observationRequired").booleanValue());
        JsonNode deterministic = tree(ExecutionRequirement.DETERMINISTIC);
        assertEquals("deterministic", deterministic.get("kind").asString());
        assertFalse(deterministic.get("observationRequired").booleanValue());
        assertDoesNotThrow(() -> wiring.validator().assertValid("execution-requirement.v1.schema.json", ExecutionRequirement.SEMANTIC));
        assertDoesNotThrow(() -> wiring.validator().assertValid("execution-requirement.v1.schema.json", ExecutionRequirement.DETERMINISTIC));
        assertThrows(JacksonException.class, () -> PlanSamples.MAPPER.readValue("{\"policyId\":\"other\",\"kind\":\"semantic\",\"observationRequired\":true}", ExecutionRequirement.class));
        assertEquals(ExecutionRequirement.DETERMINISTIC, PlanSamples.MAPPER.readValue(PlanSamples.MAPPER.writeValueAsString(ExecutionRequirement.DETERMINISTIC), ExecutionRequirement.class));
    }

    /** The skills whose stages the workflow plans again after they were taken out and put back: each is offered by the registry and planned like any other. */
    private static final List<String> SPECIALIST_SKILLS = List.of("cs-engineering", "code-review", "test-engineering", "solid-design", "ui-ux-design", "korean-prose-editor", "software-security-auditor");

    @Test
    void everySpecialistSkillOffersProvidersAndEachOfTheirCapabilitiesIsPlannedAsAStageOfThatSkill() {
        for (String skill : SPECIALIST_SKILLS) {
            List<SkillProvider> offered = providers.stream().filter(provider -> skill.equals(provider.skillId())).toList();
            assertFalse(offered.isEmpty(), skill + " offers no provider");
            for (SkillProvider provider : offered) {
                for (String capability : provider.capabilities()) {
                    WorkflowPlan plan = planOf(capability, true);
                    assertDoesNotThrow(() -> wiring.validator().workflowPlan(plan), capability);
                    if (provider.executionClass() == ExecutionClass.BOOTSTRAP) {
                        // A bootstrap provider runs before the plan is made, so a plan that names its capability is refused as the registry's other bootstrap providers are.
                        assertEquals(RunState.BLOCKED, plan.state(), capability);
                        assertEquals("workflow.service.bootstrapCapabilityInPlan", plan.errors().getFirst().messageKey(), capability);
                        continue;
                    }
                    WorkflowPlan.Stage stage = plan.stages().stream().filter(candidate -> candidate.satisfiedCapabilities().contains(capability)).findFirst()
                            .orElseThrow(() -> new AssertionError(skill + " / " + capability + " is not planned: " + plan.errors() + " " + plan.stages()));
                    assertEquals(skill, stage.skillId(), capability);
                    assertEquals(provider.providerKey(), stage.providerKey(), capability);
                    assertNotNull(stage.executionRequirement(), capability);
                }
            }
        }
    }

    /** Only the stage that finishes the Korean prose work is decided by a check alone; every other stage of every skill asks for the observation of the model. */
    @Test
    void onlyTheKoreanProseFinalizationStageIsDeterministicAndAsksForNoObservation() throws IOException {
        for (String capability : capabilities()) {
            WorkflowPlan plan = planOf(capability, true);
            for (WorkflowPlan.Stage stage : plan.stages()) {
                boolean finalization = stage.satisfiedCapabilities().equals(List.of("korean-prose-finalization"));
                assertEquals(finalization ? ExecutionKind.DETERMINISTIC : ExecutionKind.SEMANTIC, stage.executionRequirement().kind(), stage.stageId());
                assertEquals(!finalization, stage.executionRequirement().observationRequired(), stage.stageId());
                assertEquals(finalization, PlanBuilder.deterministic(stage.satisfiedCapabilities()), stage.stageId());
            }
        }
        JsonNode stage = tree(planOf("korean-prose-finalization", true)).get("stages").get(0);
        assertEquals("deterministic", stage.get("executionRequirement").get("kind").asString());
        assertFalse(stage.get("executionRequirement").get("observationRequired").booleanValue());
        assertEquals("semantic", tree(planOf("minimal-implementation", true)).get("stages").get(0).get("executionRequirement").get("kind").asString());
    }

    @Test
    void theDefaultOfAStateMappingIsTheTextRejectOrARuleAndAnyOtherTextIsRefused() {
        String withText = "{\"selector\":\"/output/status\",\"values\":{\"READY\":{\"state\":\"PASSED\",\"errorRequired\":false}},\"default\":\"reject\",\"adapterErrors\":[\"INVALID_INPUT\"]}";
        WorkflowPlan.StateMapping text = PlanSamples.MAPPER.readValue(withText, WorkflowPlan.StateMapping.class);
        assertNull(text.fallback().rule());
        assertEquals(RunState.PASSED, text.values().get("READY").state());
        assertNull(text.values().get("READY").allowedErrorCodes());
        assertEquals(CanonicalJson.of(PlanSamples.MAPPER.readTree(withText)), CanonicalJson.of(tree(text)));

        String withRule = "{\"default\":{\"state\":\"FAILED\",\"errorRequired\":true,\"allowedErrorCodes\":[\"GATE_FAILED\",\"INVALID_INPUT\"]},\"adapterErrors\":[]}";
        WorkflowPlan.StateMapping rule = PlanSamples.MAPPER.readValue(withRule, WorkflowPlan.StateMapping.class);
        assertEquals(RunState.FAILED, rule.fallback().rule().state());
        assertEquals(List.of(WorkflowCode.GATE_FAILED, WorkflowCode.INVALID_INPUT), rule.fallback().rule().allowedErrorCodes());
        assertNull(rule.selector());
        assertNull(rule.values());
        assertEquals(CanonicalJson.of(PlanSamples.MAPPER.readTree(withRule)), CanonicalJson.of(tree(rule)));

        assertThrows(JacksonException.class, () -> PlanSamples.MAPPER.readValue("{\"default\":\"accept\",\"adapterErrors\":[]}", WorkflowPlan.StateMapping.class));
        assertThrows(JacksonException.class, () -> PlanSamples.MAPPER.readValue("{\"default\":{\"state\":\"NO_SUCH_STATE\",\"errorRequired\":true},\"adapterErrors\":[]}", WorkflowPlan.StateMapping.class));
    }

    @Test
    void aGateWritesItsValidatorAsNullWhenItHasNoneAndAReceiptPolicyOnlyTheFieldsItDeclared() {
        WorkflowPlan.Gate open = PlanSamples.openGate();
        JsonNode gate = tree(open);
        assertTrue(gate.get("validator").isNull());
        assertTrue(gate.get("validatorSchema").isNull());
        assertEquals("none", gate.get("kind").asString());
        StageReceiptPolicy policy = PlanSamples.MAPPER.readValue("{\"mode\":\"reference-only\",\"actorIdPointer\":\"/output/actorId\",\"uniqueness\":\"run\"}", StageReceiptPolicy.class);
        assertTrue(policy.requiresUniqueActor());
        assertFalse(policy.listsPriorActors());
        assertEquals(new TreeSet<>(List.of("mode", "actorIdPointer", "uniqueness")), new TreeSet<>(new ArrayList<>(tree(policy).propertyNames())));
        StageReceiptPolicy lists = PlanSamples.MAPPER.readValue("{\"mode\":\"reference-only\",\"actorIdsPointer\":\"/output/actorIds\",\"actorIdsMatch\":\"prior-policy-actors\"}", StageReceiptPolicy.class);
        assertTrue(lists.listsPriorActors());
        assertFalse(lists.requiresUniqueActor());
    }

    @Test
    void theRegistryIsReadIntoProvidersThatCarryTheDigestOfEachSchemaFileTheyName() throws IOException {
        assertFalse(providers.isEmpty());
        Set<String> keys = new LinkedHashSet<>();
        for (SkillProvider provider : providers) {
            assertTrue(keys.add(provider.providerKey()), provider.providerKey());
            assertTrue(provider.providerKey().startsWith(provider.skillId() + ":" + provider.phase() + ":"), provider.providerKey());
            for (WorkflowPlan.SchemaReference schema : List.of(provider.outputSchema(), provider.resultSchema())) {
                assertEquals("sha256:" + Digests.sha256Hex(Files.readAllBytes(PlanSamples.REPO.resolve(schema.path()))), schema.digest(), provider.providerKey());
            }
            String validatorName = provider.gate().validator();
            if (validatorName != null && validatorName.endsWith(".schema.json")) {
                assertEquals(validatorName, provider.gate().validatorSchema().path());
                assertEquals("sha256:" + Digests.sha256Hex(Files.readAllBytes(PlanSamples.REPO.resolve(validatorName))), provider.gate().validatorSchema().digest());
            } else {
                assertNull(provider.gate().validatorSchema(), provider.providerKey());
            }
        }
        assertTrue(SkillRegistry.skillIds(providers).contains("minimal-implementation"));
    }

    @Test
    void theProviderOfACapabilityIsTheOneWithTheHighestPriorityAndTheSameOnEveryRead() {
        for (String capability : capabilitiesOf(providers)) {
            SkillProvider chosen = SkillRegistry.selectByCapability(providers, capability).orElseThrow();
            long highest = providers.stream().filter(provider -> provider.enabled() && provider.capabilities().contains(capability)).mapToLong(SkillProvider::priority).max().orElseThrow();
            assertEquals(highest, chosen.priority(), capability);
            assertEquals(chosen, SkillRegistry.selectByCapability(wiring.registry().read(), capability).orElseThrow(), capability);
        }
        assertTrue(SkillRegistry.selectByCapability(providers, "no-such-capability").isEmpty());
    }

    private static Set<String> capabilitiesOf(List<SkillProvider> all) {
        Set<String> out = new LinkedHashSet<>();
        all.forEach(provider -> out.addAll(provider.capabilities()));
        return out;
    }

    @Test
    void theSelectionReasonNamesThePriorityAsAnIntegerAndTheNumbersOfAStageAreIntegers() {
        WorkflowPlan plan = planOf("minimal-implementation", false);
        WorkflowPlan.Stage stage = plan.stages().get(0);
        SkillProvider provider = SkillRegistry.selectByCapability(providers, "minimal-implementation").orElseThrow();
        assertTrue(stage.selectionReason().endsWith(" at priority " + CanonicalJson.number(java.math.BigDecimal.valueOf(provider.priority())) + "."), stage.selectionReason());
        JsonNode tree = tree(stage);
        assertTrue(tree.get("order").isIntegralNumber());
        assertTrue(tree.get("phaseOrder").isIntegralNumber());
        assertEquals(provider.phaseOrder(), tree.get("phaseOrder").asInt());
    }
}
