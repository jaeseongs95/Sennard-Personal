// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

import io.github.jaeseongs95.sennard.app.board.BoardService;
import io.github.jaeseongs95.sennard.server.entrance.ToolEntrance;
import io.github.jaeseongs95.sennard.server.mcp.SessionBinding;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import tools.jackson.databind.JsonNode;

/**
 * The tool entrance of the rewritten areas ({@code ToolEntrance}, tools {@code update_session_status} and {@code list_session_status}) through a real MCP client
 * against the assembled server on a real PostgreSQL. The contract tests (ToolContractServerTest, ToolRulesServerTest) hold the contract's names, inputs and
 * the board's rules for every tool; these cases hold what the entrance itself does.
 *
 * <pre>
 * e1  flat keys           the tools are called with the contract's flat keys (schemaVersion, summary, role) and answer by them; the published input schema has those
 *                         keys at the top, not one object named by a parameter (the shape McpToolInputShapeTest measured for a record parameter)
 * e2  contract violation  a missing summary, a wrong schemaVersion, a number for the summary, an empty role and an argument the contract does not declare
 *                         are INVALID_INPUT
 * e3  declared constraint a summary of only blanks passes the contract's schema and is stopped by the input record's Jakarta Validation:
 *                         INVALID_INPUT with reasonCode VALIDATION_FAILED and the violations listed
 * e4  two lines           the two-line summary is INVALID_INPUT; which layer stops it (the contract's pattern or the board rule) is asserted as it is
 * e5  service errors      on the real database, through a tool declared with the entrance: a rule's refusal (two-line summary) is INVALID_INPUT with
 *                         reasonCode SUMMARY_INVALID, the catalog key and the limit as an argument and its English sentence; a foreign key violation
 *                         (a wake path of a session no one registered) is INTEGRITY_FAILED with reasonCode REFERENCE_VIOLATION
 * e5b optional session    a tool declared with toolWithOptionalSession runs without a session and is handed an Unbound binding (a Bound one with a session); a tool declared with
 *                         the ordinary tool(...) is still BINDING_REQUIRED without a session
 * e6  published contract  every tool the server's own configuration declares with the entrance is one the contract guard publishes by its contract file:
 *                         its tools/list input schema has every property of the contract with the same constraints (maxLength, minLength, pattern, const) and
 *                         the added sessionRef
 * </pre>
 */
@Tag("business-db")
class ToolEntranceTest {
    private static final String ONE = "1.0.0";
    private static ToolServerSupport sennard;

    /** Tools of the test's own, declared with the entrance like the real ones, to reach the answers no public call of the board can provoke. */
    @Configuration(proxyBeanMethods = false)
    static class Probes {
        record ProbeInput(String schemaVersion) {
        }

        @Bean
        List<SyncToolSpecification> entranceProbeTools(ToolEntrance entrance, BoardService board, @Qualifier("store") DataSource store) {
            // Spring's own translator, as the application layer's repositories have it: the foreign key violation arrives as a DataIntegrityViolationException.
            JdbcClient jdbc = JdbcClient.create(store);
            ToolAnnotations hints = ToolAnnotations.builder().readOnlyHint(false).idempotentHint(false).destructiveHint(false).openWorldHint(false).build();
            return List.of(
                    entrance.tool("entrance_probe_rule", ProbeInput.class, hints, (session, input) -> board.setSummary(session.host(), "probe-session", "two\nlines", null, OperationKeys.operation("update_session_status", session.sessionRef()))),
                    entrance.tool("entrance_probe_reference", ProbeInput.class, hints, (session, input) -> {
                        // A row that names a session no one registered: the foreign key of the wake path stops it.
                        jdbc.sql("INSERT INTO sennard.wake_paths (session_ref, instance_id, birth_generation, updated_at, version) VALUES (?, 'probe', 'probe', ?, 0)")
                                .params("no-such-session-ref", Timestamp.from(Instant.now())).update();
                        return Map.of();
                    }),
                    // A tool declared with an optional session: it answers what binding the entrance handed it.
                    entrance.toolWithOptionalSession("entrance_probe_optional", ProbeInput.class, hints, (binding, input) -> switch (binding) {
                        case SessionBinding.Bound bound -> Map.of("binding", "Bound", "host", bound.session().host());
                        case SessionBinding.Unbound unbound -> Map.of("binding", "Unbound", "reason", unbound.reason());
                    }));
        }
    }

    @BeforeAll
    static void start() throws Exception {
        sennard = ToolServerSupport.startWithSources(List.of(Probes.class));
    }

    @AfterAll
    static void stop() {
        sennard.close();
    }

    private static Map<String, Object> args(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("schemaVersion", ONE);
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private static JsonNode data(JsonNode answer) {
        assertTrue(answer.path("ok").asBoolean(), answer.toString());
        return answer.path("data");
    }

    private static void assertRefused(JsonNode answer, String code) {
        assertFalse(answer.path("ok").asBoolean(true), answer.toString());
        assertEquals(code, answer.path("error").path("code").asString(), answer.toString());
    }

    private static McpSchema.Tool listed(String name) {
        return sennard.listTools().stream().filter(tool -> tool.name().equals(name)).findFirst().orElseThrow(() -> new AssertionError("Not listed: " + name));
    }

    // ------------------------------------------------------------------------------------------------------------------ e1

    @Test
    void theBoardToolsTakeAndAnswerByTheContractsFlatKeys() throws Exception {
        String session = sennard.session();
        JsonNode written = data(sennard.call("update_session_status", args("summary", "flat keys", "role", "entrance tester"), session));
        assertEquals("flat keys", written.path("summary").asString());
        assertEquals("entrance tester", written.path("role").asString());
        JsonNode listing = data(sennard.call("list_session_status", args(), session));
        JsonNode mine = null;
        for (JsonNode item : listing.path("sessions")) {
            if (session.equals(item.path("sessionId").asString())) {
                mine = item;
            }
        }
        assertNotNull(mine, listing.toString());
        assertEquals("flat keys", mine.path("summary").asString());
        assertEquals("entrance tester", mine.path("role").asString());

        JsonNode schema = ToolServerSupport.MAPPER.valueToTree(listed("update_session_status").inputSchema());
        assertEquals(Set.of("schemaVersion", "operationKey", "summary", "role", "sessionRef"), propertyNames(schema), "flat: the contract's keys at the top, no wrapping object");
        assertEquals(Set.of("schemaVersion", "operationKey", "summary"), requiredNames(schema));
    }

    private static Set<String> propertyNames(JsonNode schema) {
        Set<String> names = new TreeSet<>();
        schema.path("properties").propertyNames().forEach(names::add);
        return names;
    }

    private static Set<String> requiredNames(JsonNode schema) {
        Set<String> names = new TreeSet<>();
        schema.path("required").forEach(item -> names.add(item.asString()));
        return names;
    }

    // ------------------------------------------------------------------------------------------------------------------ e2

    @Test
    void aCallThatBreaksTheContractOrNamesAnArgumentItDoesNotDeclareIsInvalidInput() throws Exception {
        String session = sennard.session();
        Map<String, Object> missing = args();
        assertRefused(sennard.call("update_session_status", missing, session), "INVALID_INPUT");
        assertRefused(sennard.call("update_session_status", args("summary", "x", "schemaVersion", "2.0.0"), session), "INVALID_INPUT");
        assertRefused(sennard.call("update_session_status", args("summary", 5), session), "INVALID_INPUT");
        assertRefused(sennard.call("update_session_status", args("summary", "x", "role", ""), session), "INVALID_INPUT");
        assertRefused(sennard.call("update_session_status", args("summary", "x", "undeclared", 1), session), "INVALID_INPUT");
        assertRefused(sennard.call("list_session_status", args("undeclared", 1), session), "INVALID_INPUT");
    }

    // ------------------------------------------------------------------------------------------------------------------ e3, e4

    @Test
    void aSummaryOfOnlyBlanksIsStoppedByTheRecordsDeclaredConstraint() throws Exception {
        JsonNode answer = sennard.call("update_session_status", args("summary", "   "), sennard.session());
        assertRefused(answer, "INVALID_INPUT");
        assertEquals("VALIDATION_FAILED", answer.path("error").path("details").path("reasonCode").asString(), answer.toString());
        assertTrue(answer.path("error").path("details").path("violations").size() > 0, "the failed fields are listed: " + answer);
    }

    @Test
    void aTwoLineSummaryIsRefusedAsInvalidInput() throws Exception {
        JsonNode answer = sennard.call("update_session_status", args("summary", "first\nsecond"), sennard.session());
        assertRefused(answer, "INVALID_INPUT");
        // The contract's pattern (^[^\r\n]*$) stops it before the entrance: the refusal is the guard's, with the pattern's own message and no reasonCode. The board's rule
        // that would answer SUMMARY_INVALID is reached only by the probe tool of the next case (a tool with no contract file).
        assertEquals("workflow.schema.contractMismatch", answer.path("error").path("messageKey").asString(), answer.toString());
        assertTrue(answer.path("error").path("details").path("validationErrors").get(0).asString().contains("regex pattern"), answer.toString());
    }

    // ------------------------------------------------------------------------------------------------------------------ e5

    @Test
    void aRuleRefusalAndADatabaseConstraintOnTheRealStoreAreTranslatedByTheEntrance() throws Exception {
        String session = sennard.session();
        // The probe tools are not contract tools: their published schema declares no property, so the call carries no argument.
        JsonNode rule = sennard.call("entrance_probe_rule", new LinkedHashMap<>(), session);
        assertRefused(rule, "INVALID_INPUT");
        assertEquals("SUMMARY_INVALID", rule.path("error").path("details").path("reasonCode").asString(), rule.toString());
        assertEquals("error.SUMMARY_INVALID", rule.path("error").path("messageKey").asString());
        assertEquals(200, rule.path("error").path("params").path("maxLength").asInt(), rule.toString());
        assertEquals("summary must be one non-empty line of at most 200 characters.", rule.path("error").path("message").asString());

        JsonNode reference = sennard.call("entrance_probe_reference", new LinkedHashMap<>(), session);
        assertRefused(reference, "INTEGRITY_FAILED");
        assertEquals("REFERENCE_VIOLATION", reference.path("error").path("details").path("reasonCode").asString(), reference.toString());
        assertEquals("error.REFERENCE_VIOLATION", reference.path("error").path("messageKey").asString());
    }

    // ------------------------------------------------------------------------------------------------------------------ e5b

    /**
     * A tool declared with {@code toolWithOptionalSession} runs for a call that carries no session and is handed an {@code Unbound} binding (with the reason), and for a call of a session
     * it is handed the {@code Bound} one; a tool declared with the ordinary {@code tool(...)} is still refused without a session as BINDING_REQUIRED, before its handler runs.
     */
    @Test
    void aToolWithAnOptionalSessionRunsWithoutOneAndGetsAnUnboundBindingWhileAnOrdinaryToolIsRefused() throws Exception {
        JsonNode without = data(sennard.callUnobserved("entrance_probe_optional", new LinkedHashMap<>()));
        assertEquals("Unbound", without.path("binding").asString(), without.toString());
        assertFalse(without.path("reason").asString().isBlank(), "the binding says why there is no session: " + without);

        JsonNode with = data(sennard.call("entrance_probe_optional", new LinkedHashMap<>(), sennard.session()));
        assertEquals("Bound", with.path("binding").asString(), with.toString());
        assertFalse(with.path("host").asString().isBlank(), with.toString());

        JsonNode ordinary = sennard.callUnobserved("entrance_probe_rule", new LinkedHashMap<>());
        assertRefused(ordinary, "BINDING_REQUIRED");
    }

    // ------------------------------------------------------------------------------------------------------------------ e6

    /** The tools the server's own configuration builds with the entrance: a bean method of a class of the server's packages that takes a ToolEntrance. */
    private static Set<String> entranceToolNames() throws ClassNotFoundException {
        ConfigurableListableBeanFactory factory = sennard.server.context.getBeanFactory();
        Set<String> names = new TreeSet<>();
        for (String bean : factory.getBeanDefinitionNames()) {
            BeanDefinition definition = factory.getBeanDefinition(bean);
            if (!(definition instanceof AnnotatedBeanDefinition annotated) || annotated.getFactoryMethodMetadata() == null) {
                continue;
            }
            String owner = annotated.getFactoryMethodMetadata().getDeclaringClassName();
            if (!owner.startsWith("io.github.jaeseongs95.sennard.server.")) {
                continue;
            }
            for (Method method : Class.forName(owner).getDeclaredMethods()) {
                if (method.getName().equals(annotated.getFactoryMethodMetadata().getMethodName()) && List.of(method.getParameterTypes()).contains(ToolEntrance.class)) {
                    for (Object item : (List<?>) factory.getBean(bean)) {
                        names.add(((SyncToolSpecification) item).tool().name());
                    }
                }
            }
        }
        return names;
    }

    private static JsonNode contract(String tool) throws IOException {
        try (InputStream in = ToolEntranceTest.class.getResourceAsStream("/contracts/" + tool.replace('_', '-') + "-request.v1.schema.json")) {
            assertNotNull(in, "the entrance tool " + tool + " has no contract file: it would be published as a bare placeholder schema");
            return ToolServerSupport.MAPPER.readTree(in);
        }
    }

    @Test
    void everyToolDeclaredWithTheEntranceIsPublishedByItsContractFile() throws Exception {
        Set<String> names = entranceToolNames();
        assertTrue(names.containsAll(Set.of("update_session_status", "list_session_status", "prepare_session_message", "send_session_message", "acknowledge_session_messages",
                "get_session_message_status", "recall_session_message", "launch_managed_child")), "the entrance's tools are found: " + names);
        Map<String, String> problems = new TreeMap<>();
        for (String tool : names) {
            JsonNode contract = contract(tool);
            JsonNode published = ToolServerSupport.MAPPER.valueToTree(listed(tool).inputSchema());
            Set<String> expected = new TreeSet<>(propertyNames(contract));
            expected.add("sessionRef");
            if (!expected.equals(propertyNames(published))) {
                problems.put(tool, "properties " + propertyNames(published) + " are not the contract's " + expected);
                continue;
            }
            for (String property : propertyNames(contract)) {
                JsonNode wanted = contract.path("properties").path(property);
                JsonNode actual = published.path("properties").path(property);
                for (String keyword : List.of("maxLength", "minLength", "pattern", "const", "type")) {
                    if (wanted.has(keyword) && !wanted.path(keyword).equals(actual.path(keyword))) {
                        problems.put(tool + "." + property + "." + keyword, "published " + actual.path(keyword) + ", the contract has " + wanted.path(keyword));
                    }
                }
            }
        }
        assertEquals(Map.of(), problems, "a tool that is not in the guard's OwnTool is published with the placeholder schema instead of its contract");
    }

    // ------------------------------------------------------------------------------------------------------------------ e7

    /**
     * A tool that declares no annotations is published with the protocol's defaults, destructiveHint=true and openWorldHint=true, and a host
     * that asks before such a call asks for every checkpoint. Every tool the server publishes states its own nature: the continuity, cleanup,
     * session, workflow, skill and skill-check tools touch only this server's own store (and the computer), so none of them is open-world
     * except classify_skills, which asks the evaluator provider.
     */
    @Test
    void everyPublishedToolStatesItsOwnNature() {
        // name -> {readOnly, idempotent, destructive, openWorld}
        Map<String, List<Boolean>> expected = new TreeMap<>();
        expected.put("checkpoint_context", List.of(false, false, false, false));
        expected.put("inspect_context", List.of(true, true, false, false));
        expected.put("load_context", List.of(true, true, false, false));
        expected.put("suppress_context_restore", List.of(false, false, false, false));
        expected.put("purge_direct_context", List.of(false, false, true, false));
        expected.put("open_session", List.of(false, false, false, false));
        expected.put("prepare_state_cleanup", List.of(true, false, false, false));
        expected.put("execute_state_cleanup", List.of(false, false, true, false));
        expected.put("plan_workflow", List.of(false, false, false, false));
        expected.put("pause_workflow", List.of(false, false, false, false));
        expected.put("open_convergence_root", List.of(false, false, false, false));
        expected.put("claim_workflow_attempt", List.of(false, false, false, false));
        expected.put("start_guarded_workflow", List.of(false, false, false, false));
        expected.put("get_convergence_status", List.of(true, true, false, false));
        expected.put("resolve_convergence_gate", List.of(false, false, false, false));
        expected.put("start_workflow", List.of(false, false, false, false));
        // The tool of every workflow kind opens a workflow like the change's tool; resume_workflow takes an open one again (the same call twice records the same attribution).
        for (io.github.jaeseongs95.sennard.server.workflow.WorkflowKind kind : io.github.jaeseongs95.sennard.server.workflow.WorkflowKind.values()) {
            expected.put(kind.toolName(), List.of(false, false, false, false));
        }
        expected.put("resume_workflow", List.of(false, true, false, false));
        expected.put("record_stage_result", List.of(false, false, false, false));
        expected.put("submit_change_plan", List.of(false, false, false, false));
        expected.put("get_workflow_status", List.of(true, true, false, false));
        expected.put("list_session_workflows", List.of(true, true, false, false));
        expected.put("finalize_workflow", List.of(false, false, false, false));
        expected.put("abort_workflow", List.of(false, false, true, false));
        expected.put("release_convergence_root", List.of(false, false, true, false));
        expected.put("run_skill_check", List.of(false, false, true, false));
        expected.put("get_skill_inventory", List.of(true, true, false, false));
        expected.put("classify_skills", List.of(false, false, false, true));
        expected.put("record_skill_selection", List.of(false, false, false, false));
        // The attachments' tools: every one is idempotent (a repeat is answered as the first time), none deletes or replaces, none reaches outside this server; status and read only read.
        expected.put("artifact_begin", List.of(false, true, false, false));
        expected.put("artifact_finish", List.of(false, true, false, false));
        expected.put("artifact_status", List.of(true, true, false, false));
        expected.put("artifact_read", List.of(true, true, false, false));
        Map<String, String> problems = new TreeMap<>();
        Map<String, McpSchema.Tool> published = new TreeMap<>();
        sennard.listTools().forEach(tool -> published.put(tool.name(), tool));
        for (Map.Entry<String, List<Boolean>> tool : expected.entrySet()) {
            McpSchema.Tool listed = published.get(tool.getKey());
            if (listed == null) {
                problems.put(tool.getKey(), "not published");
                continue;
            }
            McpSchema.ToolAnnotations hints = listed.annotations();
            if (hints == null) {
                problems.put(tool.getKey(), "published without annotations");
                continue;
            }
            List<Boolean> actual = List.of(Boolean.TRUE.equals(hints.readOnlyHint()), Boolean.TRUE.equals(hints.idempotentHint()), Boolean.TRUE.equals(hints.destructiveHint()),
                    Boolean.TRUE.equals(hints.openWorldHint()));
            if (!tool.getValue().equals(actual)) {
                problems.put(tool.getKey(), "readOnly/idempotent/destructive/openWorld " + actual + ", wanted " + tool.getValue());
            }
        }
        // Every other published tool: it must not carry the protocol's defaults (destructive and open-world at once), and a read-only tool is never destructive.
        for (McpSchema.Tool tool : published.values()) {
            McpSchema.ToolAnnotations hints = tool.annotations();
            if (hints == null) {
                problems.put(tool.name(), "published without annotations");
            } else if (Boolean.TRUE.equals(hints.destructiveHint()) && Boolean.TRUE.equals(hints.openWorldHint())) {
                problems.put(tool.name(), "published with the defaults destructive and open-world");
            } else if (Boolean.TRUE.equals(hints.readOnlyHint()) && Boolean.TRUE.equals(hints.destructiveHint())) {
                problems.put(tool.name(), "read-only and destructive");
            }
        }
        assertEquals(Map.of(), problems);
    }
}
