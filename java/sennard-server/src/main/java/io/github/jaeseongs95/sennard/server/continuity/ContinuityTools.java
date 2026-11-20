// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.continuity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.mcp.annotation.McpMeta;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.domain.error.UnavailableCode;
import io.github.jaeseongs95.sennard.domain.error.UnavailableException;
import io.github.jaeseongs95.sennard.server.mcp.OperationKeys;
import io.github.jaeseongs95.sennard.server.mcp.SelfSession;
import io.github.jaeseongs95.sennard.server.tools.ToolCalls;
import io.github.jaeseongs95.sennard.server.workflow.WorkflowCall;
import io.github.jaeseongs95.sennard.service.store.MemoryStatus;
import io.github.jaeseongs95.sennard.service.store.StoreException;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * The continuity and state cleanup tools. Each method only binds the call to a session and delegates; the request contracts are enforced
 * before the method is reached, and the descriptions published come from the catalog ({@code ContractGuard}).
 */
@Component
public class ContinuityTools {
    public record Core(String objective, List<String> completionCriteria, List<String> constraints, List<String> decisions, List<String> progress, List<String> blockers,
            List<String> nextActions) {
    }

    public record EvidenceRef(String artifactId, String locator, String digest, boolean verified) {
    }

    private final ToolCalls calls;
    private final ContinuityService continuity;
    private final StateCleanupService cleanup;
    private final TurnCheckService turnChecks;
    private final OperationKeys keys;

    public ContinuityTools(ToolCalls calls, ContinuityService continuity, StateCleanupService cleanup, TurnCheckService turnChecks, OperationKeys keys) {
        this.keys = keys;
        this.turnChecks = turnChecks;
        this.calls = calls;
        this.continuity = continuity;
        this.cleanup = cleanup;
    }

    @McpTool(name = "checkpoint_context", description = "tools.checkpoint_context.description",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, idempotentHint = false, destructiveHint = false, openWorldHint = false))
    public CallToolResult checkpointContext(@McpToolParam(required = true) String schemaVersion, @McpToolParam(required = true) String operationKey,
            @McpToolParam(required = true) String expectedStateToken, @McpToolParam(required = true) String status, @McpToolParam(required = true) Core core,
            @McpToolParam(required = true) List<EvidenceRef> evidenceRefs, @McpToolParam(required = false) String sessionRef, McpMeta meta, McpTransportContext context) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("schemaVersion", schemaVersion);
        arguments.put("operationKey", operationKey);
        arguments.put("expectedStateToken", expectedStateToken);
        arguments.put("status", status);
        arguments.put("core", core);
        arguments.put("evidenceRefs", evidenceRefs);
        return calls.call("checkpoint_context", ToolCalls.withSessionRef(arguments, sessionRef), sessionRef, meta, context, ContinuityTools::unavailable,
                session -> continuity.checkpoint(caller(session), new ContinuityService.Checkpoint(operationKey,expectedStateToken, MemoryStatus.valueOf(status), core, evidenceRefs)));
    }

    @McpTool(name = "mark_turn_unchanged", description = "tools.mark_turn_unchanged.description",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, idempotentHint = false, destructiveHint = false, openWorldHint = false))
    public CallToolResult markTurnUnchanged(@McpToolParam(required = true) String schemaVersion, @McpToolParam(required = true) String operationKey, @McpToolParam(required = false) String sessionRef,
            McpMeta meta, McpTransportContext context) {
        Map<String, Object> arguments = Map.of("schemaVersion", schemaVersion, "operationKey", operationKey);
        return calls.call("mark_turn_unchanged", ToolCalls.withSessionRef(arguments, sessionRef), sessionRef, meta, context, ContinuityTools::unavailable,
                session -> turnChecks.markUnchanged(caller(session), keys.of("mark_turn_unchanged", session, arguments)));
    }

    @McpTool(name = "inspect_context", description = "tools.inspect_context.description",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true, destructiveHint = false, openWorldHint = false))
    public CallToolResult inspectContext(@McpToolParam(required = true) String schemaVersion, @McpToolParam(required = false) String sessionRef, McpMeta meta, McpTransportContext context) {
        return calls.call("inspect_context", ToolCalls.withSessionRef(Map.of("schemaVersion", schemaVersion), sessionRef), sessionRef, meta, context, ContinuityTools::unavailable,
                session -> continuity.inspect(caller(session)));
    }

    @McpTool(name = "load_context", description = "tools.load_context.description",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true, destructiveHint = false, openWorldHint = false))
    public CallToolResult loadContext(@McpToolParam(required = true) String schemaVersion, @McpToolParam(required = false) String candidateToken, @McpToolParam(required = false) String sessionRef,
            McpMeta meta, McpTransportContext context) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("schemaVersion", schemaVersion);
        if (candidateToken != null) {
            arguments.put("candidateToken", candidateToken);
        }
        return calls.call("load_context", ToolCalls.withSessionRef(arguments, sessionRef), sessionRef, meta, context, ContinuityTools::unavailable,
                session -> continuity.load(caller(session), candidateToken));
    }

    @McpTool(name = "suppress_context_restore", description = "tools.suppress_context_restore.description",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, idempotentHint = false, destructiveHint = false, openWorldHint = false))
    public CallToolResult suppressContextRestore(@McpToolParam(required = true) String schemaVersion, @McpToolParam(required = true) String operationKey, @McpToolParam(required = true) long expectedEpoch,
            @McpToolParam(required = false) String sessionRef, McpMeta meta, McpTransportContext context) {
        Map<String, Object> arguments = Map.of("schemaVersion", schemaVersion, "operationKey", operationKey, "expectedEpoch", expectedEpoch);
        return calls.call("suppress_context_restore", ToolCalls.withSessionRef(arguments, sessionRef), sessionRef, meta, context,
                ContinuityTools::unavailable, session -> continuity.suppress(caller(session), expectedEpoch, keys.of("suppress_context_restore", session, arguments)));
    }

    @McpTool(name = "purge_direct_context", description = "tools.purge_direct_context.description",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, idempotentHint = false, destructiveHint = true, openWorldHint = false))
    public CallToolResult purgeDirectContext(@McpToolParam(required = true) String schemaVersion, @McpToolParam(required = true) String operationKey, @McpToolParam(required = true) String expectedStateToken,
            @McpToolParam(required = false) String sessionRef, McpMeta meta, McpTransportContext context) {
        return calls.call("purge_direct_context", ToolCalls.withSessionRef(Map.of("schemaVersion", schemaVersion, "operationKey", operationKey, "expectedStateToken", expectedStateToken), sessionRef),
                sessionRef, meta, context, ContinuityTools::unavailable, session -> continuity.purge(caller(session), operationKey, expectedStateToken));
    }

    @McpTool(name = "prepare_state_cleanup", description = "tools.prepare_state_cleanup.description",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = false, destructiveHint = false, openWorldHint = false))
    public CallToolResult prepareStateCleanup(@McpToolParam(required = true) String schemaVersion) {
        return calls.call(cleanup::prepare);
    }

    @McpTool(name = "execute_state_cleanup", description = "tools.execute_state_cleanup.description",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, idempotentHint = false, destructiveHint = true, openWorldHint = false))
    public CallToolResult executeStateCleanup(@McpToolParam(required = true) String schemaVersion, @McpToolParam(required = true) String operationKey, @McpToolParam(required = true) String planToken) {
        Map<String, Object> arguments = Map.of("schemaVersion", schemaVersion, "operationKey", operationKey, "planToken", planToken);
        // The tool settles no caller (it takes no sessionRef and binds no session: the plan token, signed by the server, is what it acts on), so its key has the global scope, as before the
        // scope existed. Whether a caller is to be settled here is the lead's to decide (AGS3-D-0267, scope of the receipt).
        return calls.call(() -> cleanup.execute(planToken, keys.global("execute_state_cleanup", arguments)));
    }

    /** The public code stays {@code CONTINUITY_UNAVAILABLE}; the detail keeps the store's stable reason. */
    private static UnavailableException unavailable(RuntimeException failure) {
        return new UnavailableException(UnavailableCode.CONTINUITY_UNAVAILABLE, Map.of(), SennardException.args("reason", failure instanceof StoreException store ? store.code() : "INTERNAL_FAILURE"), failure);
    }

    /** A session the server minted has no host id; its reference is then the task key. */
    static ContinuityService.Caller caller(SelfSession session) {
        String sessionId = session.hostSessionId() != null ? session.hostSessionId() : session.sessionRef();
        return new ContinuityService.Caller(sessionId, actorId(session.host(), sessionId, session.actor().agentKey()));
    }

    /**
     * The actor key, the same value the hook path derives for this actor and the one the workflow tools record for it ({@link WorkflowCall#actorId}). A start or a resume records which agent took
     * an instance under it, and the hooks' gate looks that record up under the key it derives for the agent, so the two must be one form.
     */
    static String actorId(String host, String sessionId, String agentKey) {
        return WorkflowCall.actorId(host, sessionId, agentKey);
    }
}
