// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import io.modelcontextprotocol.spec.McpSchema;
import tools.jackson.databind.JsonNode;

/**
 * The tool contract of the server, asserted through a real MCP client against the assembled application: each tool's name, its inputs
 * and the error codes a caller can provoke. {@code sessionRef} is the one input added on purpose:
 * the optional address a host without session metadata uses.
 */
@Tag("business-db")
class ToolContractServerTest {
    private record Contract(String tool, Set<String> required, Set<String> optional, boolean session) {
    }

    private static final Set<String> SESSION_REF = Set.of("sessionRef");
    private static final List<Contract> CONTRACTS = List.of(
            new Contract("get_skill_inventory", Set.of(), Set.of("sessionRef", "skillIds"), true),
            new Contract("classify_skills", Set.of("schemaVersion", "requestId", "operationKey", "originalPrompt", "confirmedContext", "contextSources", "explicitSkillIds", "ruleRequiredSkillIds",
                    "vendorContext", "publicSynthetic"), SESSION_REF, true),
            new Contract("record_skill_selection", Set.of("schemaVersion", "operationKey", "decision"), Set.of("selectionSource", "sessionRef"), true),
            new Contract("lookup_korean_prose_terms", Set.of("schemaVersion", "sourceText", "sourceDigest"), Set.of(), false),
            new Contract("check_for_updates", Set.of(), Set.of("force"), false),
            new Contract("get_trust_capabilities", Set.of(), Set.of(), false),
            new Contract("validate_collaboration_decision", Set.of("decision"), SESSION_REF, true),
            new Contract("update_session_status", Set.of("schemaVersion", "operationKey", "summary"), Set.of("role", "sessionRef"), true),
            new Contract("list_session_status", Set.of("schemaVersion"), SESSION_REF, true),
            new Contract("checkpoint_global_context", Set.of("schemaVersion", "operationKey", "expectedStateToken", "body"), SESSION_REF, true),
            new Contract("bind_session_role", Set.of("schemaVersion", "operationKey", "role"), SESSION_REF, true),
            new Contract("checkpoint_role_context", Set.of("schemaVersion", "operationKey", "expectedStateToken", "body"), SESSION_REF, true),
            new Contract("load_shared_context", Set.of("schemaVersion"), SESSION_REF, true));

    private static ToolServerSupport sennard;
    private static String session;

    @BeforeAll
    static void start() throws Exception {
        sennard = ToolServerSupport.start();
        session = sennard.session();
    }

    @AfterAll
    static void stop() {
        sennard.close();
    }

    private static Stream<Contract> contracts() {
        return CONTRACTS.stream();
    }

    static String sha256(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    static Map<String, Object> decision() {
        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("schemaVersion", "1.2.0");
        decision.put("sourceOriginKind", "peer");
        decision.put("sourceReceiptId", null);
        decision.put("authorityEffect", "none");
        decision.put("userDirective", "unspecified");
        decision.put("netBenefitCriteria", Map.of("independentlyCompletable", false, "parallelBottleneckReduced", false, "limitedContextSufficient", false, "singleWriterOwnership", false,
                "netBenefitAfterOverhead", false));
        decision.put("auditSeparationRequired", false);
        decision.put("route", "direct");
        return decision;
    }

    static Map<String, Object> classifyInput(String operationKey, String requestId, String prompt) {
        Map<String, Object> confirmed = new LinkedHashMap<>();
        for (String field : List.of("taskRevision", "objective", "actions", "targets", "constraints", "prohibitedActions", "background")) {
            confirmed.put(field, null);
        }
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("schemaVersion", "1.0.0");
        input.put("requestId", requestId);
        input.put("operationKey", operationKey);
        input.put("originalPrompt", prompt);
        input.put("confirmedContext", confirmed);
        input.put("contextSources", List.of());
        input.put("explicitSkillIds", List.of());
        input.put("ruleRequiredSkillIds", List.of());
        input.put("vendorContext", Map.of("vendorId", "codex", "reference", "session"));
        input.put("publicSynthetic", false);
        return input;
    }

    static Map<String, Object> selection(String operationKey) {
        String zero = "sha256:" + "0".repeat(64);
        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("schemaVersion", "1.0.0");
        decision.put("classificationResponseRef", zero);
        decision.put("requestDigest", zero);
        decision.put("inventoryDigest", zero);
        decision.put("taskRevision", null);
        decision.put("configRevision", "unconfigured");
        decision.put("profileRevision", "unconfigured");
        decision.put("explicitSkillIds", List.of());
        decision.put("ruleRequiredSkillIds", List.of());
        decision.put("agentSelectedSkillIds", List.of());
        decision.put("selectionReasons", List.of());
        decision.put("applicabilityChecks", List.of());
        decision.put("unresolvedSkillReferences", List.of());
        decision.put("selectionStatus", "PARTIAL");
        decision.put("adviceApplied", false);
        decision.put("hostReceipt", null);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("schemaVersion", "1.0.0");
        input.put("operationKey", operationKey);
        input.put("decision", decision);
        return input;
    }

    /** The input the tool accepts, with an operationKey when the tool takes one. */
    private static Map<String, Object> valid(String tool) throws Exception {
        return new HashMap<>(OperationKeys.withKey(tool, base(tool)));
    }

    private static Map<String, Object> base(String tool) throws Exception {
        return switch (tool) {
            case "classify_skills" -> classifyInput("op-" + UUID.randomUUID(), "req-" + UUID.randomUUID(), "contract probe");
            case "record_skill_selection" -> selection("op-unknown-operation-key");
            case "lookup_korean_prose_terms" -> new HashMap<>(Map.of("schemaVersion", "1.0.0", "sourceText", "가", "sourceDigest", sha256("가")));
            case "validate_collaboration_decision" -> new HashMap<>(Map.of("decision", decision()));
            case "update_session_status" -> new HashMap<>(Map.of("schemaVersion", "1.0.0", "summary", "probe"));
            case "list_session_status", "load_shared_context" -> new HashMap<>(Map.of("schemaVersion", "1.0.0"));
            case "checkpoint_global_context", "checkpoint_role_context" -> {
                Map<String, Object> input = new HashMap<>(Map.of("schemaVersion", "1.0.0", "body", "probe"));
                input.put("expectedStateToken", null);
                yield input;
            }
            case "bind_session_role" -> {
                Map<String, Object> input = new HashMap<>(Map.of("schemaVersion", "1.0.0"));
                input.put("role", null);
                yield input;
            }
            default -> new HashMap<>();
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> inputSchema(String tool) {
        McpSchema.Tool listed = sennard.bean(io.modelcontextprotocol.server.McpStatelessSyncServer.class).listTools().stream().filter(item -> item.name().equals(tool)).findFirst()
                .orElseThrow(() -> new AssertionError("Not listed: " + tool));
        return (Map<String, Object>) sennard.inTransaction(() -> ToolServerSupport.MAPPER.convertValue(listed.inputSchema(), Map.class));
    }

    @ParameterizedTest
    @MethodSource("contracts")
    void aToolKeepsTheNameAndInputsOfTheContract(Contract contract) {
        Map<String, Object> schema = inputSchema(contract.tool());
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.getOrDefault("properties", Map.of());
        Set<String> declared = Stream.concat(contract.required().stream(), contract.optional().stream()).collect(Collectors.toSet());
        assertEquals(declared, properties.keySet(), contract.tool());
        assertEquals(contract.required(), Set.copyOf((List<?>) schema.getOrDefault("required", List.of())), contract.tool());
    }

    @Test
    void anExpectedTokenAndARoleAreRequiredYetMayBeNull() {
        for (String[] pair : List.of(new String[] {"checkpoint_global_context", "expectedStateToken"}, new String[] {"checkpoint_role_context", "expectedStateToken"}, new String[] {"bind_session_role", "role"})) {
            @SuppressWarnings("unchecked")
            Map<String, Object> property = (Map<String, Object>) ((Map<String, Object>) inputSchema(pair[0]).get("properties")).get(pair[1]);
            assertTrue(String.valueOf(property.get("type")).contains("null"), pair[0] + "." + pair[1] + " " + property);
        }
    }

    /** Every tool that takes an operationKey publishes it as required with the one pattern the server refuses by, whichever way the tool declares its schema. */
    @Test
    void everyToolThatTakesAnOperationKeyPublishesItsRequirementAndTheCommonPattern() {
        List<McpSchema.Tool> keyed = sennard.listTools().stream().filter(tool -> tool.inputSchema() != null && tool.inputSchema().get("properties") instanceof Map<?, ?> properties
                && properties.containsKey("operationKey")).toList();
        Set<String> names = keyed.stream().map(McpSchema.Tool::name).collect(Collectors.toSet());
        assertTrue(names.containsAll(OperationKeys.WORKFLOW_WRITE_TOOLS), names.toString());
        assertTrue(names.containsAll(Set.of("bind_session_role", "checkpoint_global_context", "checkpoint_role_context", "open_session", "run_skill_check", "checkpoint_context", "send_session_message")), names.toString());
        for (McpSchema.Tool tool : keyed) {
            Map<?, ?> schema = tool.inputSchema();
            Map<?, ?> key = (Map<?, ?>) ((Map<?, ?>) schema.get("properties")).get("operationKey");
            assertEquals(io.github.jaeseongs95.sennard.app.operation.OperationKey.SHAPE.pattern(), key.get("pattern"), tool.name());
            if (tool.name().equals("resume_workflow")) {
                assertEquals(List.of("runId"), schema.get("required"), tool.name());
                assertEquals(Map.of("expectedRevision", List.of("operationKey"), "operationKey", List.of("expectedRevision")), schema.get("dependentRequired"), tool.name());
            } else {
                assertTrue(((List<?>) schema.get("required")).contains("operationKey"), tool.name());
            }
        }
    }

    /**
     * No tool's description is longer than 4096 characters, the same limit for every tool. The limit is Claude Code's: it cuts an MCP tool's description at
     * {@code CLAUDE_CODE_MAX_MCP_DESCRIPTION_LENGTH}, whose default is 4096 (https://code.claude.com/docs/en/env-vars). The MCP specification sets no limit on a description, so this is the
     * limit of the host that reads the most of what is published, not a rule of the protocol.
     */
    @Test
    void everyToolDescriptionFitsTheLimitOfAHostThatCutsAt4096Characters() {
        List<McpSchema.Tool> tools = sennard.listTools();
        assertFalse(tools.isEmpty());
        for (McpSchema.Tool tool : tools) {
            assertNotNull(tool.description(), tool.name());
            assertTrue(tool.description().length() <= 4096, tool.name() + " has " + tool.description().length() + " characters");
        }
    }

    @ParameterizedTest
    @MethodSource("contracts")
    void anUndeclaredArgumentIsRefused(Contract contract) throws Exception {
        Map<String, Object> arguments = valid(contract.tool());
        arguments.put("_sessionBinding", Map.of("host", "x", "sessionId", "y"));
        JsonNode answer = sennard.call(contract.tool(), arguments, session);
        assertFalse(answer.path("ok").asBoolean(true), answer.toString());
        assertEquals("INVALID_INPUT", answer.path("error").path("code").asString(), answer.toString());
    }

    @ParameterizedTest
    @MethodSource("contracts")
    void aMissingRequiredArgumentIsAnInputError(Contract contract) throws Exception {
        for (String name : contract.required()) {
            Map<String, Object> arguments = valid(contract.tool());
            arguments.remove(name);
            // As sent: the helper that gives a write tool its key would give the key back when the key is what is missing.
            JsonNode answer = sennard.callAsSent(contract.tool(), arguments, session);
            assertEquals("INVALID_INPUT", answer.path("error").path("code").asString(), contract.tool() + " without " + name + ": " + answer);
        }
    }

    @ParameterizedTest
    @MethodSource("contracts")
    void aToolAboutTheSessionRefusesACallItCannotBindAndOthersAnswer(Contract contract) throws Exception {
        JsonNode answer = sennard.callUnobserved(contract.tool(), valid(contract.tool()));
        if (contract.session()) {
            assertEquals("BINDING_REQUIRED", answer.path("error").path("code").asString(), contract.tool() + ": " + answer);
        } else {
            assertTrue(answer.path("ok").asBoolean(), contract.tool() + ": " + answer);
        }
    }

    @ParameterizedTest
    @MethodSource("contracts")
    void everyAnswerIsTheEnvelope(Contract contract) throws Exception {
        JsonNode answer = sennard.call(contract.tool(), valid(contract.tool()), session);
        assertEquals("1.0.0", answer.path("schemaVersion").asString(), answer.toString());
        assertTrue(answer.has("ok") && answer.has("data") && answer.has("error"), answer.toString());
        if (!answer.path("ok").asBoolean()) {
            for (String field : List.of("code", "message", "messageKey", "params", "details")) {
                assertNotNull(answer.path("error").get(field), field + " in " + answer);
            }
        }
    }

    @Test
    void withJevOffClassificationAsksNobodyAndAnUnavailableResultCannotCompleteASelection() throws Exception {
        String me = sennard.session();
        sennard.inTransaction(() -> {
            sennard.bean(io.github.jaeseongs95.sennard.app.board.BoardService.class).recordInput("codex", me, "/w", true);
            return null;
        });
        String op = "op-" + UUID.randomUUID();
        JsonNode classified = sennard.call("classify_skills", classifyInput(op, "req-" + op, "프롬프트"), me).path("data");
        assertEquals("PROFILE_UNAVAILABLE", classified.path("result").path("response").path("error").path("code").asString(), classified.toString());
        assertEquals(0, sennard.remote.jevCalls.get(), "nothing left this computer");
        JsonNode inventory = sennard.call("get_skill_inventory", new HashMap<>(), me).path("data");
        Map<String, Object> selection = selection(op);
        @SuppressWarnings("unchecked")
        Map<String, Object> decision = (Map<String, Object>) selection.get("decision");
        decision.put("classificationResponseRef", classified.path("classificationResponseRef").asString());
        decision.put("requestDigest", classified.path("result").path("response").path("requestDigest").asString());
        decision.put("inventoryDigest", inventory.path("inventoryDigest").asString());
        decision.put("selectionStatus", "SELECTED");
        JsonNode refused = sennard.call("record_skill_selection", selection, me).path("data");
        assertFalse(refused.path("valid").asBoolean(true), refused.toString());
        assertTrue(refused.path("errors").toString().contains("CLASSIFICATION_UNAVAILABLE_CANNOT_COMPLETE_SELECTION"), refused.toString());
    }

    /** The bytes a host sends, past any client library: a JSON null is an argument, and a required one that is null is present. */
    @Test
    void aJsonNullOnTheWireReachesTheTool() throws Exception {
        String other = sennard.session();
        byte[] body = ("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{\"name\":\"bind_session_role\",\"arguments\":{\"schemaVersion\":\"1.0.0\",\"operationKey\":\"wire-null-operation-key\",\"role\":null},\"_meta\":"
                + ToolServerSupport.MAPPER.writeValueAsString(SessionServerSupport.meta("windows", "codex", other, "none")) + "}}").getBytes(StandardCharsets.UTF_8);
        RawHttp.Response response = RawHttp.exchange(sennard.server.port, "POST", "/mcp", Map.of("Accept", "application/json, text/event-stream", "Content-Type", "application/json",
                "Content-Length", Integer.toString(body.length), "Sennard-Host-Label", "codex", "MCP-Protocol-Version", "2025-11-25"), body);
        assertEquals(200, response.status(), response.text());
        JsonNode envelope = ToolServerSupport.MAPPER.readTree(ToolServerSupport.MAPPER.readTree(response.body()).path("result").path("content").get(0).path("text").asString());
        assertTrue(envelope.path("ok").asBoolean(), envelope.toString());
        assertTrue(envelope.path("data").path("pending").asBoolean(), envelope.toString());
    }

    @Test
    void anArgumentOfTheWrongTypeIsAnInputErrorInTheEnvelope() {
        JsonNode answer = sennard.call("validate_collaboration_decision", new HashMap<>(Map.of("decision", "not an object")), session);
        assertEquals("INVALID_INPUT", answer.path("error").path("code").asString(), answer.toString());
    }

    @Test
    void aSchemaVersionOtherThanTheContractsIsAnInputError() throws Exception {
        for (String tool : List.of("lookup_korean_prose_terms", "update_session_status", "list_session_status", "checkpoint_global_context", "bind_session_role", "checkpoint_role_context", "load_shared_context")) {
            Map<String, Object> arguments = valid(tool);
            arguments.put("schemaVersion", "9.9.9");
            assertEquals("INVALID_INPUT", sennard.call(tool, arguments, session).path("error").path("code").asString(), tool);
        }
    }
}
