// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.skills;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpMeta;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;

import io.github.jaeseongs95.sennard.domain.error.InputCode;
import io.github.jaeseongs95.sennard.domain.error.InvalidInputException;
import io.github.jaeseongs95.sennard.domain.error.UnavailableCode;
import io.github.jaeseongs95.sennard.domain.error.UnavailableException;
import io.github.jaeseongs95.sennard.server.mcp.ToolSupport;
import io.github.jaeseongs95.sennard.server.mcp.SelfSession;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.ClassifyInput;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.SelectionInput;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The skill classification tools. The input of the two classification tools is checked against the published schema, because an unknown key at any
 * depth is refused, and those two tools publish that schema as it is ({@code tool-schemas/}), so their methods take the request alone. What passed the
 * schema is bound to the tool's input record.
 */
@Component
public class SkillTools {
    private static final Logger LOG = LoggerFactory.getLogger(SkillTools.class);

    @FunctionalInterface
    private interface Answer {
        Object run() throws IOException;
    }

    private final SkillClassificationService service;
    private final ToolSupport support;
    private final JsonMapper mapper;
    private final Schema classifySchema;
    private final Schema selectionSchema;

    public SkillTools(SkillClassificationService service, ToolSupport support, JsonMapper mapper) throws IOException {
        this.service = service;
        this.support = support;
        this.mapper = mapper;
        this.classifySchema = schema("classify_skills");
        this.selectionSchema = schema("record_skill_selection");
    }

    private static Schema schema(String tool) throws IOException {
        try (InputStream in = new ClassPathResource("tool-schemas/" + tool + ".json").getInputStream()) {
            return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(in);
        }
    }

    /** The request's arguments, checked against the tool's published schema (the one tree of the call) and then bound to the tool's input record. */
    private <T> T checked(CallToolRequest request, Schema schema, Class<T> input) {
        JsonNode arguments = mapper.valueToTree(request.arguments() == null ? Map.of() : request.arguments());
        if (!schema.validate(arguments).isEmpty()) {
            throw inputMismatch();
        }
        try {
            return mapper.treeToValue(arguments, input);
        } catch (JacksonException unbound) {
            LOG.warn("The input of a tool passed its published schema but does not fit {}", input.getSimpleName(), unbound);
            throw inputMismatch();
        }
    }

    private static InvalidInputException inputMismatch() {
        return new InvalidInputException(InputCode.CLASSIFICATION_INPUT_MISMATCH);
    }

    private static Object mapped(Answer answer) throws IOException {
        try {
            return answer.run();
        } catch (SkillClassificationService.Rejected rejected) {
            SkillClassificationService.Rejection reason = rejected.reason();
            if (rejected.getCause() != null) {
                LOG.warn("A classification request was refused as {} because of a failure", reason, rejected);
            } else {
                // The refusal is the caller's own input or request state and the answer carries the reason; the throwable is kept at debug so the refusal is not silent.
                LOG.debug("A classification request was refused as {}", reason, rejected);
            }
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("category", reason.category().wire());
            details.put("reasonCode", reason.name());
            details.putAll(rejected.details());
            // The sentence of the inventory's refusal names the limit; the classification's has no argument.
            Map<String, Object> arguments = reason.code() == InputCode.INVENTORY_SKILL_IDS_REJECTED ? Map.of("limit", SkillClassificationService.MAX_DETAIL_SKILLS) : Map.of();
            throw new InvalidInputException(reason.code(), arguments, details, rejected);
        } catch (IllegalStateException | IOException failed) {
            LOG.warn("The classification runtime failed", failed);
            throw new UnavailableException(UnavailableCode.CLASSIFICATION_RUNTIME_UNAVAILABLE, Map.of(), Map.of("reason", failed.getClass().getSimpleName()), failed);
        }
    }

    @McpTool(name = "get_skill_inventory", description = "tools.get_skill_inventory.description", annotations = @McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true, destructiveHint = false, openWorldHint = false))
    public CallToolResult inventory(@McpToolParam(required = false, description = ToolSupport.SESSION_REF_DESCRIPTION) String sessionRef,
            @McpToolParam(required = false) List<String> skillIds,
            CallToolRequest request, McpMeta meta, McpTransportContext context) {
        return support.run(() -> {
            support.bind("get_skill_inventory", request.arguments(), meta, context, sessionRef).require();
            return mapped(() -> service.inventory(skillIds));
        });
    }

    @McpTool(name = "classify_skills", description = "tools.classify_skills.description", annotations = @McpTool.McpAnnotations(readOnlyHint = false, idempotentHint = false, destructiveHint = false, openWorldHint = true))
    public CallToolResult classify(CallToolRequest request, McpMeta meta, McpTransportContext context) {
        return support.run(() -> {
            ClassifyInput input = checked(request, classifySchema, ClassifyInput.class);
            SelfSession session = support.bind("classify_skills", request.arguments(), meta, context, input.sessionRef()).require();
            return mapped(() -> service.classify(input, session, ToolSupport.sessionId(session)));
        });
    }

    @McpTool(name = "record_skill_selection", description = "tools.record_skill_selection.description",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, idempotentHint = false, destructiveHint = false, openWorldHint = false))
    public CallToolResult record(CallToolRequest request, McpMeta meta, McpTransportContext context) {
        return support.run(() -> {
            SelectionInput input = checked(request, selectionSchema, SelectionInput.class);
            SelfSession session = support.bind("record_skill_selection", request.arguments(), meta, context, input.sessionRef()).require();
            return mapped(() -> service.accept(input, session, ToolSupport.sessionId(session)));
        });
    }
}
