// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import tools.jackson.databind.json.JsonMapper;

/**
 * The inputs of the tools that name a run or a root ({@code get_workflow_status}, {@code finalize_workflow}, {@code abort_workflow},
 * {@code get_convergence_status}) are records bound by {@link ToolInputs}, strict like the start inputs. The cases:
 * <ol>
 * <li>an input that keeps to the published schema is bound as it is written;</li>
 * <li>a run or a root that is left out is the one that is not there (the empty ID), as the tools always answered, while a revision that is left out is refused;</li>
 * <li>a field of another JSON type than the schema names is refused and not converted: a number for the ID, a text, a fraction or a {@code null} for the revision;</li>
 * <li>a revision below zero is refused by the record's constraint;</li>
 * <li>a key the tool does not take is refused, and every refusal is {@code INVALID_INPUT} naming the tool, the binding failure its cause.</li>
 * </ol>
 */
class RunInputTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static ToolInputs inputs;

    @BeforeAll
    static void wire() {
        LocalValidatorFactoryBean factory = new LocalValidatorFactoryBean();
        factory.afterPropertiesSet();
        inputs = new ToolInputs(MAPPER, factory);
    }

    private static RunRequest.Change change(String json) {
        return inputs.read("finalize_workflow", MAPPER.readTree(json), RunRequest.Change.class);
    }

    private static WorkflowFailure refused(String json) {
        return assertThrows(WorkflowFailure.class, () -> change(json));
    }

    @Test
    void anInputThatKeepsToTheSchemaIsBoundAsWritten() {
        RunRequest.Change change = change("{\"runId\":\"run-1\",\"expectedRevision\":3}");
        assertEquals("run-1", change.id());
        assertEquals(3L, change.expectedRevision());
        assertEquals("run-9", inputs.read("get_workflow_status", MAPPER.readTree("{\"runId\":\"run-9\"}"), RunRequest.Lookup.class).id());
        assertEquals("root-9", inputs.read("get_convergence_status", MAPPER.readTree("{\"rootId\":\"root-9\"}"), RunRequest.RootLookup.class).id());
        assertEquals(0L, change("{\"runId\":\"r\",\"expectedRevision\":0}").expectedRevision());
    }

    @Test
    void aRunOrARootThatIsLeftOutIsTheOneThatIsNotThereButARevisionThatIsLeftOutIsRefused() {
        assertEquals("", inputs.read("get_workflow_status", MAPPER.readTree("{}"), RunRequest.Lookup.class).id());
        assertEquals("", inputs.read("get_convergence_status", MAPPER.readTree("{}"), RunRequest.RootLookup.class).id());
        assertEquals("", change("{\"expectedRevision\":1}").id());
        WorkflowFailure missing = refused("{\"runId\":\"run-1\"}");
        assertEquals(WorkflowCode.INVALID_INPUT, missing.code());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"runId\":5,\"expectedRevision\":1}", "{\"runId\":true,\"expectedRevision\":1}", "{\"runId\":[\"a\"],\"expectedRevision\":1}", "{\"runId\":null,\"expectedRevision\":1}",
            "{\"runId\":\"r\",\"expectedRevision\":\"3\"}", "{\"runId\":\"r\",\"expectedRevision\":3.0}", "{\"runId\":\"r\",\"expectedRevision\":2.5}", "{\"runId\":\"r\",\"expectedRevision\":null}",
            "{\"runId\":\"r\",\"expectedRevision\":true}", "{\"runId\":\"r\",\"expectedRevision\":{\"n\":1}}"})
    void aFieldOfAnotherJsonTypeThanTheSchemaNamesIsRefusedAndNotConverted(String json) {
        WorkflowFailure failure = refused(json);
        assertEquals(WorkflowCode.INVALID_INPUT, failure.code(), json);
        assertNotNull(failure.getCause(), "the binding failure is the cause");
    }

    @Test
    void aRevisionBelowZeroIsRefusedByTheConstraint() {
        WorkflowFailure failure = refused("{\"runId\":\"r\",\"expectedRevision\":-1}");
        assertEquals(WorkflowCode.INVALID_INPUT, failure.code());
        assertEquals("workflow.schema.contractMismatch", failure.ref().key());
    }

    @Test
    void aKeyTheToolDoesNotTakeIsRefused() {
        assertEquals(WorkflowCode.INVALID_INPUT, refused("{\"runId\":\"r\",\"expectedRevision\":1,\"extra\":1}").code());
        assertEquals(WorkflowCode.INVALID_INPUT, assertThrows(WorkflowFailure.class, () -> inputs.read("get_workflow_status", MAPPER.readTree("{\"runId\":\"r\",\"detail\":\"full\"}"), RunRequest.Lookup.class)).code(),
                "the response mode is taken out of the arguments before the tool reads them");
        assertEquals(WorkflowCode.INVALID_INPUT, assertThrows(WorkflowFailure.class, () -> inputs.read("get_convergence_status", MAPPER.readTree("{\"rootId\":\"r\",\"expectedRevision\":1}"), RunRequest.RootLookup.class)).code());
    }

    @Test
    void anInputThatIsNotAnObjectIsRefused() {
        assertEquals(WorkflowCode.INVALID_INPUT, refused("[]").code());
        assertEquals(WorkflowCode.INVALID_INPUT, refused("\"run-1\"").code());
        assertEquals(WorkflowCode.INVALID_INPUT, refused("null").code());
    }
}
