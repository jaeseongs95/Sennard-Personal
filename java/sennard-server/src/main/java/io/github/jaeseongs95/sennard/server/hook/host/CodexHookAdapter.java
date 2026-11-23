// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook.host;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.hookclient.HookClientCall;
import io.github.jaeseongs95.sennard.hookclient.HookClientCall.FailureAnswer;
import io.github.jaeseongs95.sennard.server.hook.HookInstallation;
import io.github.jaeseongs95.sennard.server.hook.HookRejected;
import io.github.jaeseongs95.sennard.server.hook.HookRejection;
import io.github.jaeseongs95.sennard.server.hook.HostContext.Basis;
import io.github.jaeseongs95.sennard.server.hook.HostContext.SessionActor;
import io.github.jaeseongs95.sennard.server.hook.HostEvent;
import io.github.jaeseongs95.sennard.server.hook.HostHookAdapter;
import io.github.jaeseongs95.sennard.server.hook.HostId;
import io.github.jaeseongs95.sennard.server.hook.HostRegistrationFile;
import tools.jackson.databind.json.JsonMapper;

/**
 * Codex: a turn continues through {@code decision:block}.
 *
 * <p>{@code SubagentStart} and {@code SubagentStop} reach the server through {@link CodexHookTool} on the stdio MCP relay. An {@code mcp_tool} hook may name only fields
 * that its event always carries. The hook client instead reads the whole event from standard input, preserving the optional {@code agent_id} for prompt, tool, compaction and
 * model events. {@code SessionEnd} supports command handlers, and {@code SessionStart} can run before an MCP server is ready. The {@code SessionStart} command finds
 * the host process and starts the server when needed. The {@code Stop} command also binds the host so an eligible Stop can register a local wake worker. Both handler forms
 * use the same output contract.
 */
@Component
public final class CodexHookAdapter implements HostHookAdapter, SessionIdSchemaAdapter<CodexHookInput> {
    private static final HostId HOST = CodexHost.ID;
    /** The plugin's hook definition and its MCP registration ({@code .mcp.json}, the Sennard relay as a stdio server) at the root of the package. */
    private static final Path HOOKS_FILE = Path.of("hooks", "hooks.json");
    private static final Path MCP_FILE = Path.of(".mcp.json");
    /** The MCP server the plugin registers and its tool that takes the hook. */
    static final String MCP_SERVER = HookDefinition.MCP_SERVER;
    static final String MCP_TOOL = "codex_hook";
    private static final String COMPACT = "^(manual|auto)$";
    /** What the host waits for an event's hook, in seconds, whether it is a tool call or a command. */
    private static final int HOOK_LIMIT_SECONDS = 10;
    /**
     * The fields every event of the host carries (openai/codex, codex-rs/hooks/src/schema.rs and events/model_request.rs: {@code session_id}, {@code transcript_path} as a string or
     * null, {@code cwd}, {@code model}). The host fills an {@code mcp_tool} placeholder by walking the event's JSON and <b>fails the whole hook</b> when the field is not there
     * ("hook input placeholder `${agent_id}` was not found", codex-rs/hooks/src/engine/mcp_runner.rs); there is no default and no optional form, and a field of null is found.
     * So a placeholder may name only a field that the event always has.
     */
    private static final List<String> COMMON = List.of("session_id", "transcript_path", "cwd", "model");
    /** A subagent's id: always in {@code SubagentStart} and {@code SubagentStop}, but left out of every other event (it is skipped when the event is not a subagent's), and never in {@code Stop}. */
    private static final String AGENT_ID = "agent_id";

    /**
     * @param fields the fields of the host's event the tool receives, each as {@code ${field}}: an mcp_tool hook gets only what its input names, not the whole event; null for a
     *        command, which gets the whole event on its standard input
     */
    private record Hook(SessionIdEvent event, String matcher, String module, List<String> fields, FailureAnswer onFailure) {
        boolean isCommand() {
            return fields == null;
        }
    }

    /** A hook as a call of the Sennard tool: every field named is one that the event always has. */
    private static Hook tool(SessionIdEvent event, String matcher, String module, String... fields) {
        List<String> all = new ArrayList<>(COMMON);
        all.addAll(List.of(fields));
        return new Hook(event, matcher, module, List.copyOf(all), FailureAnswer.FAIL);
    }

    /**
     * A hook as a command of the Sennard hook client. The events whose {@code agent_id} is optional (it tells a subagent's prompt, tool, compaction and model request from the
     * main session's) cannot be an {@code mcp_tool} hook, which has no way to take a field that may be absent; a command reads the host's whole event, and the adapter reads
     * the {@code agent_id} in it when there is one.
     */
    private static Hook command(SessionIdEvent event, String matcher, String module) {
        return new Hook(event, matcher, module, null, FailureAnswer.FAIL);
    }

    private static final List<Hook> HOOKS = List.of(
            command(SessionIdEvent.USER_PROMPT_SUBMIT, null, null),
            tool(SessionIdEvent.SUBAGENT_START, null, null, AGENT_ID),
            tool(SessionIdEvent.SUBAGENT_STOP, null, null, AGENT_ID, "stop_hook_active"),
            command(SessionIdEvent.PRE_COMPACT, COMPACT, null),
            command(SessionIdEvent.POST_COMPACT, COMPACT, null),
            command(SessionIdEvent.PRE_TOOL_USE, "^mcp__codex_app__wait_threads$", "peer-wait"),
            // Windows PowerShell can turn a native command's exit 2 into exit 1. Codex reads the JSON denial on exit 0.
            new Hook(SessionIdEvent.PRE_TOOL_USE, null, "continuity", null, FailureAnswer.DENY_PRE_TOOL_USE),
            new Hook(SessionIdEvent.PRE_TOOL_USE, CodexWorkFacts.NATIVE_PEER_SEND.pattern(), "native-peer", null, FailureAnswer.DENY_PRE_TOOL_USE),
            command(SessionIdEvent.POST_TOOL_USE, null, null),
            // Stop carries the root turn. Use the command boundary so it can renew the local wake registration.
            command(SessionIdEvent.STOP, null, null),
            command(SessionIdEvent.PRE_MODEL_REQUEST, null, null),
            command(SessionIdEvent.POST_MODEL_REQUEST, null, null));

    private final JsonMapper mapper;

    public CodexHookAdapter(JsonMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public JsonMapper mapper() {
        return mapper;
    }

    @Override
    public Class<CodexHookInput> inputType() {
        return CodexHookInput.class;
    }

    @Override
    public HostId host() {
        return HOST;
    }

    /** Codex takes every name of the shared format, and has the model-request events besides. */
    @Override
    public boolean takes(SessionIdEvent event) {
        return true;
    }

    @Override
    public SessionActor actor(SessionIdEvent event, CodexHookInput input) {
        return switch (event) {
            case SUBAGENT_START -> HookJson.present(input.agentId()).map(id -> SessionActor.child(id, "CODEX_NATIVE_CHILD_AGENT_ID"))
                    .orElseGet(() -> SessionActor.indeterminate("CODEX_NATIVE_SUBAGENT_START_AGENT_ID_INVALID"));
            case SUBAGENT_STOP -> HookJson.present(input.agentId()).map(id -> SessionActor.child(id, "CODEX_NATIVE_CHILD_AGENT_ID"))
                    .orElseGet(() -> SessionActor.indeterminate("CODEX_NATIVE_SUBAGENT_STOP_AGENT_ID_INVALID"));
            case SESSION_START, SESSION_END, USER_PROMPT_SUBMIT, PRE_TOOL_USE, POST_TOOL_USE, POST_TOOL_USE_FAILURE, STOP, PRE_COMPACT, POST_COMPACT, POST_MODEL_SWITCH,
                    PRE_MODEL_REQUEST, POST_MODEL_REQUEST -> input.agentId() == null ? SessionActor.root(Basis.INFERRED, "CODEX_ROOT_INFERRED_CHILD_GATE_DEFERRED")
                            : HookJson.present(input.agentId()).map(id -> SessionActor.child(id, "CODEX_NATIVE_CHILD_AGENT_ID"))
                                    .orElseGet(() -> SessionActor.indeterminate("CODEX_NATIVE_AGENT_ID_INVALID"));
        };
    }

    /**
     * Codex registers its tool hook for the success event only, and an MCP tool's response carries {@code isError} (recorded in the probe's PostToolUse fixture). A shell tool's
     * response shape for a nonzero exit is not recorded, so it is not read here.
     */
    @Override
    public boolean reportsFailureInResponse(CodexHookInput input) {
        return input.toolResponse() != null && input.toolResponse().path("isError").asBoolean(false);
    }

    /** Codex's Stop output takes only {@code decision} and {@code reason}. */
    @Override
    public SessionIdOutput continueTurn(String eventName, String instruction) {
        return SessionIdOutput.block(instruction);
    }

    /** The documentation gives a model request's hook no place for text, so a verdict that adds some there is refused instead of rendered as something. */
    @Override
    public SessionIdOutput addContext(String eventName, HostEvent read, String text) {
        if (read instanceof HostEvent.ModelCallStarting || read instanceof HostEvent.ModelCallFinished) {
            throw new HookRejected(HookRejection.HOOK_VERDICT_NOT_DELIVERABLE);
        }
        return SessionIdOutput.additionalContext(eventName, text);
    }

    @Override
    public List<HostRegistrationFile> registrationFiles(HookInstallation installation) {
        Map<String, List<HookDefinition.Entry>> events = new LinkedHashMap<>();
        HookClientCall start = HookClientCall.of(HOST.label(), SessionIdEvent.SESSION_START.hostName(), FailureAnswer.GUIDANCE_STDOUT).bindingHost()
                .startingServer(HookClientCall.START_SERVER_SECONDS);
        entriesOf(events, SessionIdEvent.SESSION_START).add(HookDefinition.Entry.of(null, command(installation, start, HookClientCall.SESSION_START_LIMIT_SECONDS, true)));
        for (Hook hook : HOOKS) {
            entriesOf(events, hook.event()).add(HookDefinition.Entry.of(hook.matcher(), hook.isCommand() ? eventCommand(installation, hook) : mcpTool(hook)));
        }
        // Codex gives this event one second (three at most): the hook client's request waits one, and the host's limit is its maximum.
        HookClientCall end = HookClientCall.of(HOST.label(), SessionIdEvent.SESSION_END.hostName(), FailureAnswer.FAIL).withTimeout(1);
        entriesOf(events, SessionIdEvent.SESSION_END).add(HookDefinition.Entry.of(null, command(installation, end, 3, false)));
        return List.of(new HostRegistrationFile.WholeFile(HOOKS_FILE, mapper().valueToTree(new HookDefinition.ByEvent(events))),
                new HostRegistrationFile.WholeFile(MCP_FILE, mapper().valueToTree(HookDefinition.sennardServer(installation, HOST.label()))));
    }

    @Override
    public List<HostRegistrationFile.WholeFile> shippedFiles() {
        return List.of(new HostRegistrationFile.WholeFile(HOOKS_FILE, mapper().valueToTree(HookDefinition.noHooks())),
                new HostRegistrationFile.WholeFile(MCP_FILE, mapper().valueToTree(HookDefinition.noServers())));
    }

    private static List<HookDefinition.Entry> entriesOf(Map<String, List<HookDefinition.Entry>> events, SessionIdEvent event) {
        return events.computeIfAbsent(event.hostName(), key -> new ArrayList<>());
    }

    /** The command form: Codex runs the string through the shell of its choice, so the program's path is the one thing that may need quotes. */
    private static HookDefinition.Command command(HookInstallation installation, HookClientCall call, int timeout, boolean contextLimit) {
        return new HookDefinition.Command(null, installation.hookCommandLine(call.arguments()), null, timeout, null, contextLimit ? 8192 : null, null);
    }

    /** The event's command uses the tool form's ten-second limit. The native peer gate blocks on a client failure using stderr and exit 2. */
    private static HookDefinition.Command eventCommand(HookInstallation installation, Hook hook) {
        HookClientCall call = HookClientCall.of(HOST.label(), hook.event().hostName(), hook.onFailure()).withTimeout(HOOK_LIMIT_SECONDS - 1);
        if (hook.event() == SessionIdEvent.STOP) {
            call = call.bindingHost();
        }
        return command(installation, hook.module() == null ? call : call.inModule(hook.module()), HOOK_LIMIT_SECONDS, false);
    }

    /**
     * The hook as a call of the Sennard tool. {@code event} and {@code module} say which endpoint it is; every other key is a field of the host's event,
     * which the host fills from the event ({@code ${field}}; a placeholder that fills a whole value keeps its JSON type).
     */
    private static HookDefinition.McpTool mcpTool(Hook hook) {
        Map<String, String> input = new LinkedHashMap<>();
        input.put(CodexHookTool.EVENT, hook.event().hostName());
        if (hook.module() != null) {
            input.put(CodexHookTool.MODULE, hook.module());
        }
        hook.fields().forEach(field -> input.put(field, "${" + field + "}"));
        return new HookDefinition.McpTool(MCP_SERVER, MCP_TOOL, input, HOOK_LIMIT_SECONDS);
    }
}
