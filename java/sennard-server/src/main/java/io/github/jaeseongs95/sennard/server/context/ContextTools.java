// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.context;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.ai.mcp.annotation.McpMeta;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.app.operation.Receipted;
import io.github.jaeseongs95.sennard.domain.error.InputCode;
import io.github.jaeseongs95.sennard.domain.error.InvalidInputException;
import io.github.jaeseongs95.sennard.server.mcp.OperationKeys;
import io.github.jaeseongs95.sennard.server.mcp.ToolSupport;
import io.github.jaeseongs95.sennard.server.mcp.SelfSession;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * The context layer tools. An agent never changes what the user decides: a global change or a role binding is stored as a proposal and applies
 * only when the user signs it with a passkey on the local admin page. The tools read the raw values because a missing key and a null one mean
 * different things here; the typed parameters publish the input schema.
 */
@Component
public class ContextTools {
    /** The one description of the operationKey of the write tools (the contracts of the other tools say the same): the caller makes it for each call, and it belongs to the calling session. */
    private static final String OPERATION_KEY_DESCRIPTION = "Your key for this operation: make a new random one for each call (a UUID, for example), 16 to 128 characters of A-Z a-z 0-9 _ . : -. "
            + "The same key with the same content answers the first result without doing the work again; the same key with other content is REQUEST_CONFLICT. The key belongs to this session, and the result is kept for 24 hours.";
    private final ContextLayerService service;
    private final ToolSupport support;
    private final ApplicationEventPublisher events;
    private final OperationKeys keys;

    public ContextTools(ContextLayerService service, ToolSupport support, ApplicationEventPublisher events, OperationKeys keys) {
        this.keys = keys;
        this.events = events;
        this.service = service;
        this.support = support;
    }

    private static Map<String, Object> checked(String tool, CallToolRequest request, String... required) {
        Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();
        List<String> missing = new ArrayList<>();
        for (String key : required) {
            if (!args.containsKey(key)) {
                missing.add(key);
            }
        }
        if (!missing.isEmpty() || !"1.0.0".equals(args.get("schemaVersion"))) {
            throw new InvalidInputException(InputCode.CONTEXT_LAYER_ARGUMENTS_INVALID, Map.of("tool", tool), Map.of("extra", List.of(), "missing", missing));
        }
        return args;
    }

    private static ContextLayerService.Requester requester(SelfSession session) {
        return new ContextLayerService.Requester(session.host(), ToolSupport.sessionId(session), ToolSupport.actorId(session));
    }

    @McpTool(name = "load_shared_context", description = "tools.load_shared_context.description",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true, destructiveHint = false, openWorldHint = false))
    public CallToolResult load(@McpToolParam(required = true, description = "Always 1.0.0") String schemaVersion,
            @McpToolParam(required = false, description = ToolSupport.SESSION_REF_DESCRIPTION) String sessionRef, CallToolRequest request, McpMeta meta, McpTransportContext context) {
        return support.run(() -> {
            checked("load_shared_context", request, "schemaVersion");
            SelfSession session = support.bind("load_shared_context", request.arguments(), meta, context, sessionRef).require();
            return service.load(session.host(), ToolSupport.sessionId(session));
        });
    }

    @McpTool(name = "checkpoint_global_context", description = "tools.checkpoint_global_context.description",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, idempotentHint = false, destructiveHint = true, openWorldHint = false))
    public CallToolResult checkpointGlobal(@McpToolParam(required = true, description = "Always 1.0.0") String schemaVersion,
            @McpToolParam(required = true, description = OPERATION_KEY_DESCRIPTION) String operationKey,
            @McpToolParam(required = true, description = "The stateToken load_shared_context returned, or null when the layer was never written") String expectedStateToken,
            @McpToolParam(required = true, description = "The whole new body, up to 4000 UTF-8 bytes") String body,
            @McpToolParam(required = false, description = ToolSupport.SESSION_REF_DESCRIPTION) String sessionRef, CallToolRequest request, McpMeta meta, McpTransportContext context) {
        return support.run(() -> {
            Map<String, Object> args = checked("checkpoint_global_context", request, "schemaVersion", "operationKey", "expectedStateToken", "body");
            SelfSession session = support.bind("checkpoint_global_context", args, meta, context, sessionRef).require();
            return service.proposeGlobal(requester(session), args.get("expectedStateToken"), args.get("body"), keys.of("checkpoint_global_context", session, args));
        });
    }

    @McpTool(name = "bind_session_role", description = "tools.bind_session_role.description",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, idempotentHint = true, destructiveHint = false, openWorldHint = false))
    public CallToolResult bindRole(@McpToolParam(required = true, description = "Always 1.0.0") String schemaVersion,
            @McpToolParam(required = true, description = OPERATION_KEY_DESCRIPTION) String operationKey,
            @McpToolParam(required = true, description = "A name in the role registry, or null to request unbinding") String role,
            @McpToolParam(required = false, description = ToolSupport.SESSION_REF_DESCRIPTION) String sessionRef, CallToolRequest request, McpMeta meta, McpTransportContext context) {
        return support.run(() -> {
            Map<String, Object> args = checked("bind_session_role", request, "schemaVersion", "operationKey", "role");
            SelfSession session = support.bind("bind_session_role", args, meta, context, sessionRef).require();
            return service.proposeBinding(requester(session), args.get("role"), keys.of("bind_session_role", session, args));
        });
    }

    @McpTool(name = "checkpoint_role_context", description = "tools.checkpoint_role_context.description",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, idempotentHint = false, destructiveHint = false, openWorldHint = false))
    public CallToolResult checkpointRole(@McpToolParam(required = true, description = "Always 1.0.0") String schemaVersion,
            @McpToolParam(required = true, description = OPERATION_KEY_DESCRIPTION) String operationKey,
            @McpToolParam(required = true, description = "The stateToken load_shared_context returned, or null when the role has no body yet") String expectedStateToken,
            @McpToolParam(required = true, description = "The whole new body, up to 2000 UTF-8 bytes") String body,
            @McpToolParam(required = false, description = ToolSupport.SESSION_REF_DESCRIPTION) String sessionRef, CallToolRequest request, McpMeta meta, McpTransportContext context) {
        return support.run(() -> {
            Map<String, Object> args = checked("checkpoint_role_context", request, "schemaVersion", "operationKey", "expectedStateToken", "body");
            SelfSession session = support.bind("checkpoint_role_context", args, meta, context, sessionRef).require();
            Receipted<ContextLayerService.RoleView> saved = service.saveRole(session.host(), ToolSupport.sessionId(session), args.get("expectedStateToken"), args.get("body"),
                    ToolSupport.actorId(session), keys.of("checkpoint_role_context", session, args));
            // After the save has committed and outside any transaction: the turn check of the continuity module notes it (a failure to note it is this call's failure, not a silent one).
            // Only for the save this call made: a request that comes again with its key is answered with the first save, which was noted when it was made, and a second note would
            // count the repeat as a save of the turn it is sent in.
            if (saved instanceof Receipted.Fresh<?>) {
                events.publishEvent(new RoleContextSaved(session.host(), ToolSupport.sessionId(session), session.actor().agentKey()));
            }
            return saved.value();
        });
    }
}
