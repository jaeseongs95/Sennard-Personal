// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The tool inputs that no schema checks before they are bound ({@code start_workflow}, the skills of {@code start_guarded_workflow}, and
 * {@code release_convergence_root}) keep to what their published schemas state. Jackson would bind {@code 5} to a text and {@code "3"} to a number, so the
 * fields take only the JSON type the schema names; a rule of the schema that is not a type is a constraint of the record.
 */
class StartInputTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String VALID = """
            {"goal":"  Fix the build  ","stages":["minimal-implementation"],"skills":[{"skillId":"plugin:minimal-implementation","reason":" the smallest change "}],
            "acceptanceCriteria":["The build passes"],"writeTargets":["src/a.java"]}""";

    private static Validator rules;

    @BeforeAll
    static void wire() {
        LocalValidatorFactoryBean factory = new LocalValidatorFactoryBean();
        factory.afterPropertiesSet();
        rules = factory;
    }

    private static MinimalStartRequest bound(String json) {
        return MinimalStartRequest.reader(MAPPER).readValue(json);
    }

    private static Set<ConstraintViolation<MinimalStartRequest>> violations(String json) {
        return rules.validate(bound(json));
    }

    @Test
    void aStartInputThatKeepsToTheToolSchemaIsBoundWithItsOptionalFieldsAbsent() {
        MinimalStartRequest request = bound(VALID);
        assertTrue(rules.validate(request).isEmpty());
        assertEquals("  Fix the build  ", request.goal());
        assertEquals(List.of("minimal-implementation"), request.stages());
        assertNull(request.workspace());
        assertNull(request.rootId());
        assertNull(request.priorFailure());
        assertNull(request.classificationOperationKey());
        SkillDeclaration stripped = request.skills().get(0).stripped();
        assertEquals("plugin:minimal-implementation", stripped.skillId());
        assertEquals("the smallest change", stripped.reason());
        assertEquals("minimal-implementation", stripped.bareId());
    }

    @Test
    void aStartInputThatContinuesARootCarriesItsPriorFailureWithAllFourFields() {
        MinimalStartRequest request = bound("""
                {"goal":"g","stages":["a"],"skills":[{"skillId":"s","reason":"r"}],"acceptanceCriteria":["c"],"writeTargets":["w"],"workspace":"/w","rootId":"root-1","classificationOperationKey":"op-1",
                "priorFailure":{"hypothesis":"h","changeSummary":"c","discriminator":"d","evidenceRefs":["88888888-8888-4888-8888-888888888888"]}}""");
        assertTrue(rules.validate(request).isEmpty());
        assertEquals("root-1", request.rootId());
        // The evidence of a prior failure is named by the attachment ids of the calling session; the start input holds them as the opaque strings they are (the server looks them up when it judges the start).
        assertEquals(List.of("88888888-8888-4888-8888-888888888888"), request.priorFailure().evidenceRefs());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"goal\":5,\"stages\":[\"a\"],\"skills\":[{\"skillId\":\"s\",\"reason\":\"r\"}],\"acceptanceCriteria\":[\"c\"],\"writeTargets\":[\"w\"]}",
            "{\"goal\":true,\"stages\":[\"a\"],\"skills\":[{\"skillId\":\"s\",\"reason\":\"r\"}],\"acceptanceCriteria\":[\"c\"],\"writeTargets\":[\"w\"]}",
            "{\"goal\":\"g\",\"stages\":[7],\"skills\":[{\"skillId\":\"s\",\"reason\":\"r\"}],\"acceptanceCriteria\":[\"c\"],\"writeTargets\":[\"w\"]}",
            "{\"goal\":\"g\",\"stages\":\"a\",\"skills\":[{\"skillId\":\"s\",\"reason\":\"r\"}],\"acceptanceCriteria\":[\"c\"],\"writeTargets\":[\"w\"]}",
            "{\"goal\":\"g\",\"stages\":[\"a\"],\"skills\":[{\"skillId\":5,\"reason\":\"r\"}],\"acceptanceCriteria\":[\"c\"],\"writeTargets\":[\"w\"]}",
            "{\"goal\":\"g\",\"stages\":[\"a\"],\"skills\":[{\"skillId\":\"s\",\"reason\":\"r\",\"extra\":1}],\"acceptanceCriteria\":[\"c\"],\"writeTargets\":[\"w\"]}",
            "{\"goal\":\"g\",\"stages\":[\"a\"],\"skills\":[{\"skillId\":\"s\",\"reason\":\"r\"}],\"acceptanceCriteria\":[\"c\"],\"writeTargets\":[\"w\"],\"workspace\":null}",
            "{\"goal\":\"g\",\"stages\":[\"a\"],\"skills\":[{\"skillId\":\"s\",\"reason\":\"r\"}],\"acceptanceCriteria\":[\"c\"],\"writeTargets\":[\"w\"],\"workspace\":4}",
            "{\"goal\":\"g\",\"stages\":[\"a\"],\"skills\":[{\"skillId\":\"s\",\"reason\":\"r\"}],\"acceptanceCriteria\":[\"c\"],\"writeTargets\":[\"w\"],\"rootId\":null}",
            "{\"goal\":\"g\",\"stages\":[\"a\"],\"skills\":[{\"skillId\":\"s\",\"reason\":\"r\"}],\"acceptanceCriteria\":[\"c\"],\"writeTargets\":[\"w\"],\"surprise\":1}"})
    void aFieldOfAnotherJsonTypeThanTheToolSchemaNamesOrAKeyItDoesNotTakeIsRefusedWhenBinding(String json) {
        assertThrows(JacksonException.class, () -> bound(json), json);
    }

    @Test
    void aPriorFailureThatIsNullIsReadAsOneThatIsLeftOut() {
        MinimalStartRequest request = bound("{\"goal\":\"g\",\"stages\":[\"a\"],\"skills\":[{\"skillId\":\"s\",\"reason\":\"r\"}],\"acceptanceCriteria\":[\"c\"],\"writeTargets\":[\"w\"],\"priorFailure\":null}");
        assertNull(request.priorFailure());
        assertTrue(rules.validate(request).isEmpty());
    }

    @Test
    void theFieldAFailingValueIsAtIsNamedByTheBindingFailure() {
        DatabindException wrongType = assertThrows(DatabindException.class, () -> bound("{\"goal\":5}"));
        assertEquals("goal", wrongType.getPath().getFirst().getPropertyName());
        DatabindException inList = assertThrows(DatabindException.class, () -> bound("{\"goal\":\"g\",\"stages\":[7]}"));
        assertEquals("stages", inList.getPath().getFirst().getPropertyName());
        UnrecognizedPropertyException unknown = assertThrows(UnrecognizedPropertyException.class, () -> bound("{\"surprise\":1}"));
        assertEquals("surprise", unknown.getPropertyName());
    }

    private static List<String> fieldsOf(String json) {
        List<String> fields = new ArrayList<>();
        for (ConstraintViolation<MinimalStartRequest> violation : violations(json)) {
            List<String> names = new ArrayList<>();
            violation.getPropertyPath().forEach(node -> {
                if (node.getKind() == jakarta.validation.ElementKind.PROPERTY) {
                    names.add(node.getName());
                }
            });
            fields.add(String.join(".", names) + "=" + violation.getMessage());
        }
        return fields.stream().sorted().toList();
    }

    @Test
    void aRuleOfTheToolSchemaThatIsNotATypeIsAConstraintThatNamesTheFieldAndTheCatalogSentence() {
        String base = "\"stages\":[\"a\"],\"skills\":[{\"skillId\":\"s\",\"reason\":\"r\"}],\"acceptanceCriteria\":[\"c\"],\"writeTargets\":[\"w\"]";
        assertEquals(List.of("goal=" + MinimalStartRequest.GOAL), fieldsOf("{\"goal\":\"   \"," + base + "}"));
        assertEquals(List.of("goal=" + MinimalStartRequest.GOAL), fieldsOf("{\"goal\":\"" + "g".repeat(2001) + "\"," + base + "}"));
        assertTrue(violations("{\"goal\":\"" + "g".repeat(2000) + "\"," + base + "}").isEmpty());
        assertEquals(List.of("writeTargets=" + MinimalStartRequest.LIST), fieldsOf("{\"goal\":\"g\",\"stages\":[\"a\"],\"skills\":[{\"skillId\":\"s\",\"reason\":\"r\"}],\"acceptanceCriteria\":[\"c\"]}"));
        assertEquals(List.of("stages=" + MinimalStartRequest.LIST), fieldsOf("{\"goal\":\"g\",\"stages\":[\"a\",\"a\"],\"skills\":[{\"skillId\":\"s\",\"reason\":\"r\"}],\"acceptanceCriteria\":[\"c\"],\"writeTargets\":[\"w\"]}"));
        // The size of stages is the kind's to decide (a kind with no files of its own plans no stage, and its start sends an empty list), so the record takes an empty list; the change's one-or-more
        // is WorkflowService#assertKindInput, which refuses it with the same catalog sentence and is tested through the tool (WorkflowToolsServerTest).
        assertTrue(violations("{\"goal\":\"g\",\"stages\":[],\"skills\":[{\"skillId\":\"s\",\"reason\":\"r\"}],\"acceptanceCriteria\":[\"c\"],\"writeTargets\":[\"w\"]}").isEmpty());
        assertEquals(List.of("acceptanceCriteria=" + MinimalStartRequest.LIST), fieldsOf("{\"goal\":\"g\",\"stages\":[\"a\"],\"skills\":[{\"skillId\":\"s\",\"reason\":\"r\"}],\"acceptanceCriteria\":[\" \"],\"writeTargets\":[\"w\"]}"));
        assertEquals(List.of("classificationOperationKey=" + MinimalStartRequest.OPERATION), fieldsOf("{\"goal\":\"g\"," + base + ",\"classificationOperationKey\":\"" + "o".repeat(257) + "\"}"));
        assertEquals(List.of("rootId=" + MinimalStartRequest.ROOT), fieldsOf("{\"goal\":\"g\"," + base + ",\"rootId\":\"  \"}"));
        assertEquals(List.of("priorFailure.evidenceRefs=" + MinimalStartRequest.LIST),
                fieldsOf("{\"goal\":\"g\"," + base + ",\"rootId\":\"r\",\"priorFailure\":{\"hypothesis\":\"h\",\"changeSummary\":\"c\",\"discriminator\":\"d\",\"evidenceRefs\":[]}}"));
        assertEquals(List.of("priorFailure.hypothesis=" + MinimalStartRequest.PRIOR),
                fieldsOf("{\"goal\":\"g\"," + base + ",\"rootId\":\"r\",\"priorFailure\":{\"hypothesis\":\" \",\"changeSummary\":\"c\",\"discriminator\":\"d\",\"evidenceRefs\":[\"88888888-8888-4888-8888-888888888888\"]}}"));
    }

    @Test
    void aChangePlanIsOneToTwoHundredFiftySixDistinctPathsAndIsLeftOutForAStartInThePlanningPhase() {
        String base = "{\"goal\":\"g\",\"stages\":[\"a\"],\"skills\":[{\"skillId\":\"s\",\"reason\":\"r\"}],\"acceptanceCriteria\":[\"c\"],\"writeTargets\":[\"w\"]";
        assertNull(bound(base + "}").changePlan());
        assertNull(bound(base + ",\"changePlan\":null}").changePlan());
        assertEquals(List.of("w/a.ts"), bound(base + ",\"changePlan\":[\"w/a.ts\"]}").changePlan());
        assertTrue(violations(base + ",\"changePlan\":[\"w/a.ts\"]}").isEmpty());
        assertEquals(List.of("changePlan=" + MinimalStartRequest.LIST), fieldsOf(base + ",\"changePlan\":[]}"));
        assertEquals(List.of("changePlan=" + MinimalStartRequest.LIST), fieldsOf(base + ",\"changePlan\":[\"w/a.ts\",\"w/a.ts\"]}"));
        assertEquals(List.of("changePlan=" + MinimalStartRequest.LIST), fieldsOf(base + ",\"changePlan\":[\" \"]}"));
        assertThrows(DatabindException.class, () -> bound(base + ",\"changePlan\":[7]}"));
    }

    @Test
    void theSkillsOfAStartAreOneToThirtyTwoNamedSkillsWithAReason() {
        assertTrue(rules.validate(MAPPER.readValue("{\"skills\":[{\"skillId\":\"s\",\"reason\":\"r\"}],\"other\":1}", SkillDeclaration.Declared.class)).isEmpty(), "the other keys of the input are not the skills' business");
        for (String json : List.of("{}", "{\"skills\":[]}", "{\"skills\":[null]}", "{\"skills\":[{\"skillId\":\"s\"}]}", "{\"skills\":[{\"skillId\":\"s\",\"reason\":\" \"}]}",
                "{\"skills\":[{\"reason\":\"r\"}]}", "{\"skills\":[{\"skillId\":\":::\",\"reason\":\"r\"}]}",
                "{\"skills\":[{\"skillId\":\"" + "s".repeat(201) + "\",\"reason\":\"r\"}]}", "{\"skills\":[{\"skillId\":\"s\",\"reason\":\"" + "r".repeat(2001) + "\"}]}")) {
            assertFalse(rules.validate(MAPPER.readValue(json, SkillDeclaration.Declared.class)).isEmpty(), json);
        }
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 33; i++) {
            many.add("{\"skillId\":\"s" + i + "\",\"reason\":\"r\"}");
        }
        assertTrue(rules.validate(MAPPER.readValue("{\"skills\":[" + String.join(",", many.subList(0, 32)) + "]}", SkillDeclaration.Declared.class)).isEmpty());
        assertFalse(rules.validate(MAPPER.readValue("{\"skills\":[" + String.join(",", many) + "]}", SkillDeclaration.Declared.class)).isEmpty());
    }

    @Test
    void thePrefixOfASkillIdIsNotTheSkillsName() {
        assertEquals("skill", new SkillDeclaration("plugin:skill", "r").bareId());
        assertEquals("skill", new SkillDeclaration("a:b:skill:", "r").bareId());
        assertEquals("skill", new SkillDeclaration(" skill ", "r").bareId());
        assertEquals("plugin", new SkillDeclaration("plugin:", "r").bareId(), "a trailing separator is not part of the name");
        assertEquals("", new SkillDeclaration(":::", "r").bareId());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"rootId\":\"root-1\",\"expectedRevision\":\"3\",\"reason\":\"r\"}",
            "{\"rootId\":\"root-1\",\"expectedRevision\":3.0,\"reason\":\"r\"}",
            "{\"rootId\":\"root-1\",\"expectedRevision\":null,\"reason\":\"r\"}",
            "{\"rootId\":5,\"expectedRevision\":3,\"reason\":\"r\"}",
            "{\"rootId\":\"root-1\",\"expectedRevision\":3,\"reason\":7}",
            "{\"rootId\":\"root-1\",\"expectedRevision\":3,\"reason\":\"r\",\"userApprovalRefs\":[1]}"})
    void aReleaseInputOfAnotherJsonTypeThanTheToolSchemaNamesIsRefusedInsteadOfBeingConverted(String json) {
        assertThrows(JacksonException.class, () -> ReleaseRequest.reader(MAPPER).readValue(json), json);
    }

    @Test
    void aReleaseRevisionThatIsAnIntegerIsBoundAsItIs() {
        ReleaseRequest request = ReleaseRequest.reader(MAPPER).readValue("{\"rootId\":\"root-1\",\"expectedRevision\":3,\"reason\":\"r\"}");
        assertEquals(3L, request.expectedRevision());
        assertTrue(rules.validate(request).isEmpty());
    }
}
