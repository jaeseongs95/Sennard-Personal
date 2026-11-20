// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.trust;

import java.util.Map;

import org.springframework.ai.mcp.annotation.McpMeta;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.domain.error.InputCode;
import io.github.jaeseongs95.sennard.domain.error.InvalidInputException;
import io.github.jaeseongs95.sennard.server.mcp.ToolSupport;
import io.github.jaeseongs95.sennard.server.mcp.SelfSession;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The trust tools. */
@Component
public class TrustTools {
    private final TrustService service;
    private final ToolSupport support;
    private final JsonMapper mapper;

    public TrustTools(TrustService service, ToolSupport support, JsonMapper mapper) {
        this.service = service;
        this.support = support;
        this.mapper = mapper;
    }

    @McpTool(name = "get_trust_capabilities", description = "tools.get_trust_capabilities.description",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true, destructiveHint = false, openWorldHint = false))
    public CallToolResult capabilities() {
        return support.run(service::capabilities);
    }

    @McpTool(name = "validate_collaboration_decision", description = "tools.validate_collaboration_decision.description",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true, destructiveHint = false, openWorldHint = false))
    public CallToolResult validate(@McpToolParam(required = true, description = "The collaboration decision to check") Map<String, Object> decision,
            @McpToolParam(required = false, description = ToolSupport.SESSION_REF_DESCRIPTION) String sessionRef, CallToolRequest request, McpMeta meta, McpTransportContext context) {
        return support.run(() -> {
            // The arguments of a call are a JSON object's members, so a decision that is an object is a map; the JSON Schema validator reads it as a tree.
            if (!(request.arguments().get("decision") instanceof Map<?, ?> raw)) {
                throw new InvalidInputException(InputCode.TOOL_ARGUMENTS_MISMATCH, Map.of("name", "validateCollaborationDecisionRequest"));
            }
            JsonNode node = mapper.valueToTree(raw);
            SelfSession session = support.bind("validate_collaboration_decision", request.arguments(), meta, context, sessionRef).require();
            return service.validate(node, session, ToolSupport.sessionId(session));
        });
    }
}
