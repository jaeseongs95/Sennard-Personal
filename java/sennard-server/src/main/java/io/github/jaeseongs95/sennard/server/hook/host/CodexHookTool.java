// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook.host;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.server.hook.HostId;
import io.modelcontextprotocol.spec.McpSchema;

/** Describes the tool Codex's {@code mcp_tool} hooks call and the fields supplied by those hooks. */
@Component
public final class CodexHookTool implements McpHookRegistration {
    public static final String NAME = "codex_hook";
    /** The keys of the tool's input that say which endpoint the call is; every other key is a field of the host's event. */
    static final String EVENT = "event";
    static final String MODULE = "module";
    private static final List<String> TEXT_FIELDS = List.of(MODULE, "session_id", "transcript_path", "cwd", "model", "agent_id", "turn_id", "prompt", "tool_name", "tool_use_id", "status");

    @Override
    public HostId host() {
        return CodexHost.ID;
    }

    @Override
    public McpSchema.Tool tool() {
        return McpSchema.Tool.builder(NAME, schema())
                .description("Receives one Codex hook event. For the Codex host's mcp_tool hooks only; not for a model to call.")
                .annotations(McpSchema.ToolAnnotations.builder().readOnlyHint(false).idempotentHint(false).destructiveHint(false).openWorldHint(false).build()).build();
    }

    /** Every field a hook's input may name; each is optional but the event and the session. */
    private static Map<String, Object> schema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(EVENT, Map.of("type", "string"));
        // A field the host's event carries as null (transcript_path, error) arrives as null, which is the same as absent to the adapter, so no schema refuses it.
        TEXT_FIELDS.forEach(name -> properties.put(name, Map.of("type", List.of("string", "null"))));
        properties.put("tool_input", Map.of("type", List.of("object", "null")));
        properties.put("tool_response", Map.of());
        properties.put("error", Map.of());
        properties.put("stop_hook_active", Map.of("type", List.of("boolean", "null")));
        // Every level is an ordered map: the published tool list is compared byte for byte by a golden file, and Map.of orders its keys differently on each run.
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of(EVENT, "session_id"));
        schema.put("additionalProperties", false);
        return schema;
    }
}
