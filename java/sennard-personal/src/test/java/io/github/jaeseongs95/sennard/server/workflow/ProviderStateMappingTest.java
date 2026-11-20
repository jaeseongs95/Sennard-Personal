// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import io.github.jaeseongs95.sennard.service.personal.LocalPrivateStorage;
import io.github.jaeseongs95.sennard.service.store.RunState;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The state mapping of the registry's own providers, judged by the same gate a recorded stage result passes: a provider whose output is freeform states its
 * status in {@code output.status}, and a real failure is recorded as failed, never as the pass a default would give it.
 */
class ProviderStateMappingTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final Path REPO = Path.of(System.getProperty("sennard.repo.root"));
    private static final String PANEL = "independent-deliberation";
    private static final String COORDINATION = "subagent-coordination";
    private static final String BASELINE_CAPTURE = "change-scope-baseline-capture";
    private static final String MODEL_SELECTION = "subagent-model-selection";

    private static List<SkillProvider> providers;
    private static StageGates gates;
    private static WorkflowFileReader reader;

    @BeforeAll
    static void wire() throws IOException {
        ContentRoot content = new ContentRoot(REPO);
        ContractValidator validator = new ContractValidator(MAPPER, content);
        SkillRegistry registry = new SkillRegistry(content, validator, MAPPER);
        providers = registry.read();
        ObjectProvider<SkillChecks> none = new StaticListableBeanFactory().getBeanProvider(SkillChecks.class);
        gates = new StageGates(validator, MAPPER, new StageBundleChecks(validator, MAPPER), new ProviderSemantics(none, MAPPER));
        // The entry reads the files a use case judges on before its transaction; no host identity is asked for here, so its state folder is never touched.
        // The results here name no attachment and no output file, so the requests the entry answers are the schemas only and the attachment reads are never asked for.
        reader = new WorkflowFileReader(validator, registry, none, new HostIdentity(Path.of("unused-state"), new LocalPrivateStorage()), MAPPER, null);
    }

    /** What the entry reads for recording this result of the stage, as the use case asks for it. */
    private static FileReads readsOf(WorkflowPlan.Stage stage, StageResult result) {
        return reader.read(new ReadPlan(-1, gates.requestsToRecord(stage, result, "sha256:digest")));
    }

    private static SkillProvider provider(String capability) {
        return providers.stream().filter(candidate -> candidate.capabilities().contains(capability)).findFirst().orElseThrow();
    }

    /** The stage the registry's provider of this capability makes, as a plan carries it. */
    private static WorkflowPlan.Stage stage(String capability) {
        SkillProvider provider = provider(capability);
        return new WorkflowPlan.Stage("stage-01", 1, capability, null, null, provider.capabilities(), provider.skillId(), provider.phase(), "a reason", RunState.READY, null, null, List.of(),
                provider.gate().policy(), provider.providerKey(), provider.executionClass(), provider.phaseOrder(), provider.requiredInputArtifacts(), provider.inputBindings(),
                provider.producedArtifacts(), provider.outputSchema(), provider.resultSchema(), provider.stateMapping(), provider.gate(), provider.receiptPolicy());
    }

    private static StageResult result(RunState state, JsonNode output, WorkflowCode errorCode) {
        RecordedError error = errorCode == null ? null : new RecordedError(errorCode, "The run did not succeed.", null);
        OutputKind kind = output == null ? OutputKind.ADAPTER_ERROR : OutputKind.OUTPUT;
        ProviderResult provided = new ProviderResult("1.0.0", kind, output, List.of(), error);
        List<Evidence> evidence = List.of(new Evidence("observed", EvidenceKind.TEST, "33333333-3333-4333-8333-333333333333", "sha256:" + "d".repeat(64), true, "Synthetic fixture."));
        return new StageResult("1.0.0", "run-1", "stage-01", 0, state, provided, evidence, List.of(), List.of(), error, null, null, null, null);
    }

    private static ObjectNode status(String value) {
        return MAPPER.createObjectNode().put("status", value);
    }

    private static WorkflowCode code(String capability, StageResult result) {
        WorkflowPlan.Stage stage = stage(capability);
        FileReads reads = readsOf(stage, result);
        return assertThrows(WorkflowFailure.class, () -> gates.assertResultSemantics(stage, result, "task-1", "sha256:digest", reads)).code();
    }

    private static void record(String capability, StageResult result) {
        WorkflowPlan.Stage stage = stage(capability);
        FileReads reads = readsOf(stage, result);
        assertDoesNotThrow(() -> gates.assertResultSemantics(stage, result, "task-1", "sha256:digest", reads));
    }

    @ParameterizedTest
    @ValueSource(strings = {PANEL, COORDINATION})
    void aFailedRunOfAFreeformProviderIsRecordedAsFailed(String capability) {
        record(capability, result(RunState.FAILED, status("failed"), WorkflowCode.GATE_FAILED));
        // The provider says failed, so neither a pass nor a failure without its error is recorded.
        assertEquals(WorkflowCode.INVALID_TRANSITION, code(capability, result(RunState.PASSED, status("failed"), WorkflowCode.GATE_FAILED)));
        assertEquals(WorkflowCode.INVALID_TRANSITION, code(capability, result(RunState.FAILED, status("failed"), WorkflowCode.MISSING_EVIDENCE)));
        assertEquals(WorkflowCode.INVALID_TRANSITION, code(capability, result(RunState.FAILED, status("passed"), null)));
    }

    @ParameterizedTest
    @ValueSource(strings = {PANEL, COORDINATION})
    void aBlockedRunAndAPassedRunAreRecordedAsTheyAre(String capability) {
        record(capability, result(RunState.BLOCKED, status("blocked"), WorkflowCode.MISSING_EVIDENCE));
        record(capability, result(RunState.PASSED, status("passed"), null));
    }

    @ParameterizedTest
    @ValueSource(strings = {PANEL, COORDINATION})
    void aResultWithoutAStatusOrWithAnUnknownOneIsNotRecordedAsPassed(String capability) {
        assertEquals(WorkflowCode.INVALID_TRANSITION, code(capability, result(RunState.PASSED, MAPPER.createObjectNode(), null)));
        assertEquals(WorkflowCode.INVALID_TRANSITION, code(capability, result(RunState.PASSED, MAPPER.createObjectNode().put("status", 1), null)));
        assertEquals(WorkflowCode.INVALID_TRANSITION, code(capability, result(RunState.PASSED, MAPPER.createObjectNode().put("other", "passed"), null)));
        assertEquals(WorkflowCode.INVALID_TRANSITION, code(capability, result(RunState.PASSED, status("done"), null)));
    }

    @Test
    void aPanelWithoutConsensusWaitsForTheDecisionOwnerAndACoordinationRunHasNoSuchState() {
        record(PANEL, result(RunState.NEEDS_INPUT, status("needs_input"), null));
        assertEquals(WorkflowCode.INVALID_TRANSITION, code(PANEL, result(RunState.PASSED, status("needs_input"), null)));
        assertEquals(WorkflowCode.INVALID_TRANSITION, code(COORDINATION, result(RunState.NEEDS_INPUT, status("needs_input"), null)));
    }

    @Test
    void aBaselineThatCannotBeCapturedIsRecordedAsBlockedAndNeverAsPassedOrFailed() {
        ObjectNode none = null;
        record(BASELINE_CAPTURE, result(RunState.BLOCKED, none, WorkflowCode.INVALID_INPUT));
        record(BASELINE_CAPTURE, result(RunState.BLOCKED, none, WorkflowCode.MISSING_EVIDENCE));
        assertEquals(WorkflowCode.INVALID_TRANSITION, code(BASELINE_CAPTURE, result(RunState.FAILED, none, WorkflowCode.INVALID_INPUT)));
        assertEquals(WorkflowCode.INVALID_TRANSITION, code(BASELINE_CAPTURE, result(RunState.PASSED, none, WorkflowCode.INVALID_INPUT)));
    }

    @Test
    void onlyTheBaselineCaptureMapsWithoutAStatusBecauseItsOutputIsTheBaselineDocument() {
        List<String> withoutSelector = providers.stream().filter(candidate -> candidate.stateMapping().selector() == null).map(candidate -> candidate.capabilities().getFirst()).toList();
        assertEquals(List.of(BASELINE_CAPTURE), withoutSelector);
    }

    @Test
    void everyProviderThatNamesAStatusRejectsTheValuesItHasNoRuleFor() {
        for (SkillProvider candidate : providers) {
            WorkflowPlan.StateMapping mapping = candidate.stateMapping();
            if (mapping.selector() != null) {
                assertNotNull(mapping.values(), candidate.providerKey());
                assertEquals(WorkflowPlan.DefaultMapping.REJECT, mapping.fallback(), candidate.providerKey());
            }
        }
    }

    @Test
    void theModelAdviceStageMapsABlockedAdviceToBlockedAndTheOtherAdviceToPassed() {
        WorkflowPlan.StateMapping mapping = provider(MODEL_SELECTION).stateMapping();
        assertEquals("/output/status", mapping.selector());
        assertEquals(List.of("BLOCKED", "INHERIT_UNVERIFIED", "READY"), mapping.values().keySet().stream().sorted().toList());
        assertEquals(RunState.PASSED, mapping.values().get("READY").state());
        assertEquals(RunState.PASSED, mapping.values().get("INHERIT_UNVERIFIED").state());
        assertEquals(false, mapping.values().get("READY").errorRequired());
        assertEquals(false, mapping.values().get("INHERIT_UNVERIFIED").errorRequired());
        WorkflowPlan.MappingRule blocked = mapping.values().get("BLOCKED");
        assertEquals(RunState.BLOCKED, blocked.state());
        assertEquals(true, blocked.errorRequired());
        assertEquals(List.of(WorkflowCode.MISSING_EVIDENCE), blocked.allowedErrorCodes());
        assertEquals(WorkflowPlan.DefaultMapping.REJECT, mapping.fallback());
    }

    /** A SubagentModelAdvice.v1 of the given status that the provider's output schema accepts (the stage's output is judged against it before the state is mapped). */
    private static ObjectNode advice(String advised) {
        ObjectNode advice = MAPPER.createObjectNode().put("schemaVersion", "1.0.0").put("status", advised).put("authorityEffect", "none");
        advice.putObject("assignment").put("role", "general-implementation").put("difficulty", "routine").put("riskLevel", "low").put("verification", "direct").putArray("signals").add("fixture");
        advice.putObject("capabilitySource").put("hostId", "host").put("reference", "fixture:capabilities");
        advice.putObject("routingOptions").put("provider", "fixture").put("role", "general-implementation").put("highRisk", false).putArray("supported");
        ObjectNode plan = advice.putObject("selectionPlan");
        plan.putNull("requested");
        switch (advised) {
            case "READY" -> {
                plan.putObject("selection").put("model", "fixture-model");
                plan.put("delivery", "explicit").putNull("fallbackReason");
            }
            case "INHERIT_UNVERIFIED" -> {
                plan.putNull("selection");
                plan.put("delivery", "inherited").put("fallbackReason", "The host cannot take a model.");
            }
            default -> {
                plan.putNull("selection");
                plan.put("delivery", "blocked").put("fallbackReason", "The host offers no usable combination.");
            }
        }
        return advice;
    }

    @Test
    void aBlockedModelAdviceIsRecordedAsBlockedAndNeverAsPassed() {
        record(MODEL_SELECTION, result(RunState.BLOCKED, advice("BLOCKED"), WorkflowCode.MISSING_EVIDENCE));
        // The advice says BLOCKED, so neither a pass nor a blocked record without its error is accepted, and a failure is not the state either.
        assertEquals(WorkflowCode.INVALID_TRANSITION, code(MODEL_SELECTION, result(RunState.PASSED, advice("BLOCKED"), null)));
        assertEquals(WorkflowCode.INVALID_TRANSITION, code(MODEL_SELECTION, result(RunState.PASSED, advice("BLOCKED"), WorkflowCode.MISSING_EVIDENCE)));
        assertEquals(WorkflowCode.INVALID_TRANSITION, code(MODEL_SELECTION, result(RunState.BLOCKED, advice("BLOCKED"), null)));
        assertEquals(WorkflowCode.INVALID_TRANSITION, code(MODEL_SELECTION, result(RunState.FAILED, advice("BLOCKED"), WorkflowCode.GATE_FAILED)));
        record(MODEL_SELECTION, result(RunState.PASSED, advice("READY"), null));
        record(MODEL_SELECTION, result(RunState.PASSED, advice("INHERIT_UNVERIFIED"), null));
        assertEquals(WorkflowCode.INVALID_TRANSITION, code(MODEL_SELECTION, result(RunState.BLOCKED, advice("READY"), WorkflowCode.MISSING_EVIDENCE)));
    }
}
