// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;

import io.github.jaeseongs95.sennard.server.hook.host.CodexHookTool;
import io.github.jaeseongs95.sennard.server.hook.host.McpHookRegistration;
import io.modelcontextprotocol.spec.McpSchema;
import tools.jackson.databind.JsonNode;

/**
 * Every tool of {@code tools/list} answers with the one envelope ({@code ApiResult}: schemaVersion, ok, data, error), except registered native hook tools, whose
 * host reads the result as the hook's own JSON. The tool names are read from the list the server publishes, so a tool that is added is held to the envelope without this
 * test being told. Each tool is called three ways that no tool is made for, so that the answer is a refusal and not what the tool does: with no arguments, with an argument its
 * schema does not declare, and with an object where its first declared argument is not an object. Whatever the tool is, its answer is the envelope, and {@code isError} is the envelope's
 * {@code ok}, not its opposite.
 */
@Tag("business-db")
class ToolEnvelopeServerTest {
    /**
     * The tools the test server adds to the product's ({@link ServerFixtures.Tools}, {@link SessionServerSupport.SessionProbe}): they answer with plain text and are not what this test is about. Read from their
     * {@code @McpTool} declarations, so a fixture tool that is added to those classes is left out without this test being told.
     */
    private static final Set<String> FIXTURE_TOOLS = fixtureTools(ServerFixtures.Tools.class, SessionServerSupport.SessionProbe.class);
    private static final Set<String> ENVELOPE_MEMBERS =Set.of("schemaVersion", "ok", "data", "error");

    private static ToolServerSupport sennard;

    @BeforeAll
    static void start() throws Exception {
        sennard = ToolServerSupport.start();
    }

    @AfterAll
    static void stop() {
        sennard.close();
    }

    private static Set<String> fixtureTools(Class<?>... holders) {
        Set<String> names = new TreeSet<>();
        for (Class<?> holder : holders) {
            for (Method method : holder.getDeclaredMethods()) {
                McpTool declared = method.getAnnotation(McpTool.class);
                if (declared != null) {
                    names.add(declared.name());
                }
            }
        }
        return Set.copyOf(names);
    }

    private static List<McpSchema.Tool> published() {
        List<McpSchema.Tool> tools = sennard.listTools();
        assertFalse(tools.isEmpty(), "tools/list published no tool");
        return tools;
    }

    private static Set<String> nativeHookTools() {
        return sennard.server.context.getBeansOfType(McpHookRegistration.class).values().stream().map(registration -> registration.tool().name())
                .collect(Collectors.toUnmodifiableSet());
    }

    @Test
    void registeredNativeHookToolsArePublished() {
        Set<String> names = new TreeSet<>();
        published().forEach(tool -> names.add(tool.name()));
        Set<String> nativeHooks = nativeHookTools();
        assertTrue(nativeHooks.contains(CodexHookTool.NAME), "the Codex registration is still present");
        assertTrue(names.containsAll(nativeHooks), "native hook tool registrations are published: " + names);
    }

    @Test
    void everyOtherToolAnswersWithTheEnvelopeWhateverItIsCalledWith() {
        List<String> failures = new ArrayList<>();
        int calls = 0;
        Set<String> nativeHooks = nativeHookTools();
        for (McpSchema.Tool tool : published()) {
            if (nativeHooks.contains(tool.name()) || FIXTURE_TOOLS.contains(tool.name())) {
                continue;
            }
            for (Map.Entry<String, Map<String, Object>> sent : wrongCalls(tool).entrySet()) {
                calls++;
                String problem = problemWith(sennard.callRaw(tool.name(), sent.getValue(), Map.of()));
                if (problem != null) {
                    failures.add(tool.name() + " (" + sent.getKey() + "): " + problem);
                }
            }
        }
        assertTrue(calls > 0, "no tool was called");
        assertEquals(List.of(), failures, "tools that do not answer with the envelope");
    }

    /** The three calls made to a tool: none of them is one the tool is made for. */
    private static Map<String, Map<String, Object>> wrongCalls(McpSchema.Tool tool) {
        Map<String, Map<String, Object>> calls = new LinkedHashMap<>();
        calls.put("no arguments", Map.of());
        calls.put("an undeclared argument", Map.of("undeclaredArgumentOfTheEnvelopeTest", 1));
        Object properties = tool.inputSchema() == null ? null : tool.inputSchema().get("properties");
        if (properties instanceof Map<?, ?> declared && !declared.isEmpty()) {
            calls.put("an object for its first argument", Map.of(String.valueOf(declared.keySet().iterator().next()), Map.of("notWhatIsDeclared", 1)));
        }
        return calls;
    }

    /** What is wrong with the answer, or null when it is the envelope. */
    private static String problemWith(McpSchema.CallToolResult result) {
        if (result.content() == null || result.content().isEmpty()) {
            return "no content";
        }
        if (!(result.content().getFirst() instanceof McpSchema.TextContent text)) {
            return "the first content is " + result.content().getFirst().getClass().getSimpleName() + ", not text";
        }
        if (!text.text().startsWith("{")) {
            return "the text is not a JSON object: " + text.text();
        }
        JsonNode body;
        try {
            body = ToolServerSupport.MAPPER.readTree(text.text());
        } catch (RuntimeException unreadable) {
            return "the text is not JSON: " + text.text();
        }
        Set<String> members = new TreeSet<>();
        body.propertyNames().forEach(members::add);
        if (!members.equals(new TreeSet<>(ENVELOPE_MEMBERS))) {
            return "the members are " + members + ": " + text.text();
        }
        if (!"1.0.0".equals(body.path("schemaVersion").asString(""))) {
            return "schemaVersion is not 1.0.0: " + text.text();
        }
        if (!body.path("ok").isBoolean()) {
            return "ok is not a boolean: " + text.text();
        }
        boolean ok = body.path("ok").asBoolean();
        if (Boolean.TRUE.equals(result.isError()) == ok) {
            return "isError is " + result.isError() + " and ok is " + ok + ": " + text.text();
        }
        JsonNode error = body.path("error");
        if (ok) {
            return error.isNull() ? null : "ok is true and error is not null: " + text.text();
        }
        if (!error.isObject() || !error.path("code").isString() || error.path("code").asString().isEmpty() || !error.path("message").isString() || error.path("message").asString().isEmpty()) {
            return "a failure has no code and message: " + text.text();
        }
        return null;
    }

    @Test
    void theCheckerRefusesAnAnswerThatIsNotTheEnvelope() {
        // The test's own oracle: a result of the shapes the two tools used to answer with is found, so a tool that answers so again fails the test above.
        assertInstanceOf(String.class, problemWith(McpSchema.CallToolResult.builder().addTextContent("{\"status\":\"ERROR\",\"code\":\"INVALID_INPUT\",\"message\":\"x\"}").isError(true).build()));
        assertInstanceOf(String.class, problemWith(McpSchema.CallToolResult.builder().addTextContent("{\"exitCode\":0,\"value\":{}}").isError(false).build()));
        assertInstanceOf(String.class, problemWith(McpSchema.CallToolResult.builder().addTextContent("{\"schemaVersion\":\"1.0.0\",\"ok\":true,\"data\":null,\"error\":null}").isError(true).build()));
        assertEquals(null, problemWith(McpSchema.CallToolResult.builder().addTextContent("{\"schemaVersion\":\"1.0.0\",\"ok\":true,\"data\":{},\"error\":null}").isError(false).build()));
    }
}
