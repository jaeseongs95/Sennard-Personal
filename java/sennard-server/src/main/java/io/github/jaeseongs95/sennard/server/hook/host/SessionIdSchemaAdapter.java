// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook.host;

import java.util.stream.Collectors;

import io.github.jaeseongs95.sennard.server.hook.HookBody;
import io.github.jaeseongs95.sennard.server.hook.HookOutput;
import io.github.jaeseongs95.sennard.server.hook.HookRejected;
import io.github.jaeseongs95.sennard.server.hook.HookRejection;
import io.github.jaeseongs95.sennard.server.hook.HookVerdict;
import io.github.jaeseongs95.sennard.server.hook.HookVerdict.ContextBlock;
import io.github.jaeseongs95.sennard.server.hook.HostContext;
import io.github.jaeseongs95.sennard.server.hook.HostContext.SessionActor;
import io.github.jaeseongs95.sennard.server.hook.HostEvent;
import io.github.jaeseongs95.sennard.server.hook.ServerHookAdapter;
import io.github.jaeseongs95.sennard.server.hook.ToolCall;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Shared hooks that use {@code session_id}, {@code hook_event_name} on stdin and {@code hookSpecificOutput} on stdout.
 * Each adapter supplies its supported events, actor evidence and continuation response.
 *
 * @param <I> the host's input record
 */
interface SessionIdSchemaAdapter<I extends SessionIdInput> extends ServerHookAdapter {
    JsonMapper mapper();

    @Override
    Class<I> inputType();

    @Override
    default HostEvent read(String eventName, HookBody body) {
        I input = body.bind(mapper(), inputType());
        String sessionId = HookJson.requiredSession(input.sessionId());
        SessionIdEvent event = SessionIdEvent.named(eventName).filter(this::takes).orElseThrow(() -> new HookRejected(HookRejection.HOOK_EVENT_UNKNOWN));
        HostContext context = context(event, sessionId, input);
        return switch (event) {
            case SESSION_START -> new HostEvent.SessionStarted(context, startKind(HookJson.text(input.source())));
            case SESSION_END -> new HostEvent.SessionEnded(context, HookJson.text(input.reason()));
            case USER_PROMPT_SUBMIT -> new HostEvent.UserPromptSubmitted(context, HookJson.text(input.prompt()));
            case PRE_TOOL_USE -> new HostEvent.ToolRequested(context, tool(input));
            case POST_TOOL_USE -> toolFinished(context, input, input.toolResponse(), reportsFailureInResponse(input));
            case POST_TOOL_USE_FAILURE -> toolFinished(context, input, input.error(), true);
            case STOP -> new HostEvent.TurnEnded(context, Boolean.TRUE.equals(input.stopHookActive()), true);
            case SUBAGENT_START -> new HostEvent.SubagentStarted(context);
            case SUBAGENT_STOP -> new HostEvent.SubagentEnded(context, Boolean.TRUE.equals(input.stopHookActive()));
            case PRE_COMPACT -> new HostEvent.CompactionStarting(context);
            case POST_COMPACT -> new HostEvent.CompactionFinished(context, HookJson.presentOrNull(input.turnId()), input.error() == null || input.error().isNull());
            case POST_MODEL_SWITCH -> new HostEvent.ModelSwitched(context);
            case PRE_MODEL_REQUEST -> new HostEvent.ModelCallStarting(context);
            // The model-request events are Codex's, whose hooks have no place for text (see CodexHookAdapter#addContext): nothing said here reaches the model.
            case POST_MODEL_REQUEST -> new HostEvent.ModelCallFinished(context, false);
        };
    }

    /** Whether the host implements this event. */
    boolean takes(SessionIdEvent event);

    private HostContext context(SessionIdEvent event, String sessionId, I input) {
        String workspace = HookJson.presentOrNull(input.workspaceId());
        return new HostContext(host(), sessionId, actor(event, input), workspace != null ? workspace : HookJson.presentOrNull(input.cwd()), HookJson.presentOrNull(input.transcriptPath()),
                HookJson.presentOrNull(input.model()), effort(input), HookJson.presentOrNull(input.turnId()));
    }

    /** How this host marks a child agent. Its rule, because the markers and their absence do not mean the same on every host. */
    SessionActor actor(SessionIdEvent event, I input);

    /** The reasoning effort the host reports in this call, or null when it reports none. */
    default String effort(I input) {
        return null;
    }

    /**
     * Whether a finished tool's result is an outcome worth keeping, or only content: what it read, or the file it wrote.
     *
     * @param failed the host reported the call as failed, so what it gives is the failure and not content
     */
    default boolean keepsToolResult(String toolName, boolean failed) {
        return true;
    }

    /**
     * Whether this host reports a failed call inside the response of its success event, as a flag of the response. A host that has an event of its own for a failed call, or that
     * says nothing about failure there, does not.
     */
    default boolean reportsFailureInResponse(I input) {
        return false;
    }

    /** The host's way of continuing a turn. */
    SessionIdOutput continueTurn(String eventName, String instruction);

    private HostEvent toolFinished(HostContext context, I input, JsonNode result, boolean failed) {
        ToolCall tool = tool(input);
        return new HostEvent.ToolFinished(context, tool, keepsToolResult(tool.name(), failed) ? HookJson.tailOrNull(result) : null, failed, true);
    }

    private static HostEvent.StartKind startKind(String source) {
        return switch (source) {
            case "startup" -> HostEvent.StartKind.STARTUP;
            case "resume" -> HostEvent.StartKind.RESUME;
            case "clear" -> HostEvent.StartKind.CLEAR;
            case "compact" -> HostEvent.StartKind.COMPACT;
            case "fork" -> HostEvent.StartKind.FORK;
            default -> HostEvent.StartKind.OTHER;
        };
    }

    private static ToolCall tool(SessionIdInput input) {
        String name = HookJson.text(input.toolName());
        return new ToolCall(HookJson.presentOrNull(input.toolUseId()), name, sennardTool(name), HookJson.objectOrEmpty(input.toolInput()));
    }

    /** The local name of a Sennard tool, or null for another server's tool. */
    private static String sennardTool(String toolName) {
        int separator = toolName.lastIndexOf("__");
        if (separator <= 0) {
            return null;
        }
        return switch (toolName.substring(0, separator + 2)) {
            case "mcp__sennard__", "mcp__plugin_sennard_sennard__" -> toolName.substring(separator + 2);
            default -> null;
        };
    }

    @Override
    default HookOutput respond(String eventName, HostEvent read, HookVerdict verdict) {
        return switch (verdict) {
            case HookVerdict.Proceed ignored -> SessionIdOutput.NOTHING;
            case HookVerdict.AddContext context -> addContext(eventName, read, context.blocks().stream().map(ContextBlock::text).collect(Collectors.joining("\n\n")));
            case HookVerdict.ContinueTurn turn -> continueTurn(eventName, turn.instruction());
            case HookVerdict.TerminateTurn turn -> SessionIdOutput.terminate(turn.reason());
            case HookVerdict.Refuse refuse when read instanceof HostEvent.ToolRequested -> SessionIdOutput.denyTool(refuse.reason());
            case HookVerdict.Refuse refuse -> SessionIdOutput.block(refuse.reason());
        };
    }

    /** How this host takes text for the model at this event; a host with an event that has no such place refuses here. */
    default SessionIdOutput addContext(String eventName, HostEvent read, String text) {
        return SessionIdOutput.additionalContext(eventName, text);
    }
}
