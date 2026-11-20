// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import tools.jackson.databind.json.JsonMapper;

/**
 * What a tool whose input is a record receives from a real MCP client. The server is the assembled application on a real
 * Tomcat with Spring AI's stateless MCP starter, the client is the SDK's own. Three ways to declare the same tool are called with the same two
 * argument shapes, a flat object ({@code {"summary":...}}) and one wrapped under the parameter name ({@code {"input":{...}}}):
 * <ol>
 * <li>{@code @McpToolParam} on a record parameter;</li>
 * <li>{@code @McpToolParam} on flat parameters that the method puts into the record;</li>
 * <li>a {@code SyncToolSpecification} bean whose handler converts the arguments with Jackson.</li>
 * </ol>
 * The cases are in docs/contributing/tool-input-mapping.md.
 */
class McpToolInputShapeTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static RunningServer server;

    /** The one input every variant means: what update_session_status takes (summary required, role optional). */
    public record StatusInput(String summary, String role) {
    }

    /** Variant 1 and 2: annotated methods. The text answers what the method received, so a client sees which shape arrived. */
    static class AnnotatedTools {
        @McpTool(name = "shape_record_param", description = "A record parameter.")
        public String recordParam(@McpToolParam(required = true, description = "The input") StatusInput input) {
            return input == null ? "input=null" : "summary=" + input.summary() + ";role=" + input.role();
        }

        @McpTool(name = "shape_flat_params", description = "Flat parameters that become the record.")
        public String flatParams(@McpToolParam(required = true, description = "One line") String summary, @McpToolParam(required = false, description = "A role") String role) {
            StatusInput input = new StatusInput(summary, role);
            return "summary=" + input.summary() + ";role=" + input.role();
        }
    }

    /** Variant 3: the specification is the tool's own, so its schema is flat by construction and the arguments are converted into the record. */
    @Configuration(proxyBeanMethods = false)
    static class Beans {
        @Bean
        AnnotatedTools annotatedTools() {
            return new AnnotatedTools();
        }

        /** The starter collects a bean of type {@code List<SyncToolSpecification>}; a single specification bean is not published. */
        @Bean
        List<McpStatelessServerFeatures.SyncToolSpecification> shapeSpecification(JsonMapper mapper) {
            Map<String, Object> schema = Map.of("type", "object", "additionalProperties", false, "required", List.of("summary"),
                    "properties", Map.of("summary", Map.of("type", "string"), "role", Map.of("type", "string")));
            McpSchema.Tool tool = McpSchema.Tool.builder("shape_specification", schema).description("A specification bean.").build();
            return List.of(McpStatelessServerFeatures.SyncToolSpecification.builder().tool(tool).callHandler((context, request) -> {
                StatusInput input = mapper.convertValue(request.arguments(), StatusInput.class);
                return McpSchema.CallToolResult.builder().addTextContent("summary=" + input.summary() + ";role=" + input.role()).build();
            }).build());
        }
    }

    /** The class's one initialized client: a client made for each case leaves its connections open on the server. */
    private static McpSyncClient client;

    @BeforeAll
    static void start() throws Exception {
        server = RunningServer.start(List.of(Beans.class));
        client = DomainServerSupport.client(server.port, null);
    }

    @AfterAll
    static void stop() {
        try {
            client.close();
        } finally {
            server.close();
        }
    }

    private static String call(McpSyncClient client, String tool, Map<String, Object> arguments) {
        McpSchema.CallToolResult result = client.callTool(McpSchema.CallToolRequest.builder(tool).arguments(arguments).build());
        String text = result.content().stream().map(content -> ((McpSchema.TextContent) content).text()).reduce("", String::concat);
        return (Boolean.TRUE.equals(result.isError()) ? "ERROR:" : "") + text;
    }

    private static McpSchema.Tool published(McpSyncClient client, String name) {
        return client.listTools().tools().stream().filter(tool -> tool.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void aRecordParameterIsPublishedAsOneObjectNamedByTheParameter() {
        Map<String, Object> properties = properties(published(client, "shape_record_param"));
        assertEquals(List.of("input"), List.copyOf(properties.keySet()), "the schema has one property, the parameter's name: " + properties);
        Map<String, Object> inner = properties(properties.get("input"));
        assertEquals(List.of("role", "summary"), inner.keySet().stream().sorted().toList(), "the record's components are under it: " + inner);
    }

    @Test
    void aRecordParameterReceivesNothingFromFlatKeysAndTheRecordFromTheWrappedForm() {
        String flat = call(client, "shape_record_param", Map.of("summary", "s", "role", "r"));
        String wrapped = call(client, "shape_record_param", Map.of("input", Map.of("summary", "s", "role", "r")));
        assertEquals("summary=s;role=r", wrapped, "the wrapped form reaches the record");
        // The published schema has the one property "input", so the application's guard refuses the flat keys as undeclared before the method runs.
        assertTrue(flat.startsWith("ERROR:") && flat.contains("UNDECLARED_ARGUMENT"), "flat keys do not reach the record: " + flat);
    }

    @Test
    void flatParametersAreFlatInTheSchemaAndOnTheWireAndTheMethodBuildsTheRecord() {
        assertEquals(List.of("role", "summary"), properties(published(client, "shape_flat_params")).keySet().stream().sorted().toList());
        assertEquals("summary=s;role=r", call(client, "shape_flat_params", Map.of("summary", "s", "role", "r")));
        assertEquals("summary=s;role=null", call(client, "shape_flat_params", Map.of("summary", "s")));
    }

    @Test
    void aSpecificationBeanTakesFlatKeysAndJacksonBuildsTheRecord() {
        assertEquals(List.of("role", "summary"), properties(published(client, "shape_specification")).keySet().stream().sorted().toList());
        assertEquals("summary=s;role=r", call(client, "shape_specification", Map.of("summary", "s", "role", "r")));
        assertEquals("summary=s;role=null", call(client, "shape_specification", Map.of("summary", "s")));
        String wrapped = call(client, "shape_specification", Map.of("input", Map.of("summary", "s")));
        assertTrue(wrapped.startsWith("ERROR:"), "a wrapped form is not this tool's contract: " + wrapped);
        assertFalse(properties(published(client, "shape_specification")).containsKey("input"));
    }

    private static Map<String, Object> properties(McpSchema.Tool tool) {
        return properties((Object) tool.inputSchema());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> properties(Object property) {
        return (Map<String, Object>) ((Map<String, Object>) property).get("properties");
    }
}
