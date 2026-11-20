// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook.host;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.hookclient.HookClientCall;
import io.github.jaeseongs95.sennard.hookclient.HookClientCall.FailureAnswer;
import io.github.jaeseongs95.sennard.server.hook.HookInstallation;
import io.github.jaeseongs95.sennard.server.hook.HostContext.Basis;
import io.github.jaeseongs95.sennard.server.hook.HostContext.SessionActor;
import io.github.jaeseongs95.sennard.server.hook.HostHookAdapter;
import io.github.jaeseongs95.sennard.server.hook.HostId;
import io.github.jaeseongs95.sennard.server.hook.HostRegistrationFile;
import tools.jackson.databind.json.JsonMapper;

/**
 * Claude Code: stdin has {@code agent_id} only for a subagent, and {@code agent_type} for a subagent and for every hook of a session started with
 * {@code --agent} (code.claude.com/docs/en/hooks, common input fields); a turn continues through additional context.
 *
 * <p>Every hook is a command, the Sennard hook client in exec form. Claude Code could post a hook itself ({@code type: "http"}), but an HTTP hook's {@code url} takes no
 * environment variable (code.claude.com/docs/en/hooks: only {@code headers} substitute them), and the server's port is the operating system's choice at each start, so no
 * definition can write the address; the hook client reads the port from the file where the server announces it. Two of the events were commands before that, which the host's own
 * documentation fixes: {@code SessionStart} ("Only {@code type: "command"} and {@code type: "mcp_tool"} hooks are supported"; an {@code mcp_tool} hook is skipped on launch),
 * where the hook client also finds the host's process id and starts the server; and the idle wake, because {@code asyncRewake} is a field of a command hook. An {@code mcp_tool}
 * hook (the other form the host documents) was not taken for the rest: it has no {@code onFailure} and takes its {@code input} from named fields only, so the gate's failure
 * behaviour and the reduction of the host's input would be left to measure.
 *
 * <p>The MCP server is registered as the Sennard MCP relay, a stdio server, in the plugin's {@code .mcp.json}; the name is the one the plugin's tools were always called by.
 */
@Component
public final class ClaudeHookAdapter implements HostHookAdapter, SessionIdSchemaAdapter<ClaudeHookInput> {
    private static final HostId HOST = ClaudeHost.ID;
    /** The plugin's hook definition and its MCP registration (Claude Code reads {@code .mcp.json} at the root of a plugin). */
    private static final Path HOOKS_FILE = Path.of("hooks", "hooks.json");
    private static final Path MCP_FILE = Path.of(".mcp.json");
    private static final Set<String> CONTENT_TOOLS = Set.of("Read", "Grep", "Glob", "NotebookRead", "WebFetch", "WebSearch");
    private static final String SENNARD_TOOLS = "^mcp__plugin_sennard_sennard__.+$";
    private static final String NOT_SENNARD_TOOL = "^(?!mcp__plugin_sennard_sennard__).+$";
    private static final String COMPACT = "^(manual|auto)$";

    /**
     * How long one wake hook waits, in seconds: a day, a minute above the server's hold (sennard.hooks.wake-hold-ms) so the server answers first.
     * The host enforces a hook's timeout on this kind of hook, so that is a minute above this. ceiling: a session idle for longer is woken
     * again only after its next turn; measured on Linux with the earlier one-minute hold, where the wake ended with it.
     */
    private static final int WAKE_MAX_TIME_SECONDS = 86_400;
    /** Set by Claude Code for its hooks: 1 in an interactive session (see {@link #wake}). */
    private static final String ATTENDED = "CLAUDE_CODE_SESSION_ATTENDED";

    private record Hook(SessionIdEvent event, String matcher, String module, int hostTimeout, boolean blocksOnFailure) {
    }

    private static Hook hook(SessionIdEvent event, String matcher, String module) {
        return new Hook(event, matcher, module, 10, false);
    }

    private static final List<Hook> HOOKS = List.of(
            hook(SessionIdEvent.SUBAGENT_START, null, null), hook(SessionIdEvent.SUBAGENT_STOP, null, null),
            hook(SessionIdEvent.PRE_COMPACT, COMPACT, null), hook(SessionIdEvent.POST_COMPACT, COMPACT, null),
            hook(SessionIdEvent.POST_MODEL_SWITCH, null, null),
            new Hook(SessionIdEvent.PRE_TOOL_USE, SENNARD_TOOLS, "governance", 10, true),
            new Hook(SessionIdEvent.PRE_TOOL_USE, NOT_SENNARD_TOOL, "continuity", 10, true),
            new Hook(SessionIdEvent.PRE_TOOL_USE, ClaudeWorkFacts.NATIVE_PEER_SEND.pattern(), "native-peer", 10, true),
            hook(SessionIdEvent.USER_PROMPT_SUBMIT, null, null), hook(SessionIdEvent.POST_TOOL_USE_FAILURE, NOT_SENNARD_TOOL, null),
            hook(SessionIdEvent.POST_TOOL_USE, null, null), hook(SessionIdEvent.STOP, null, null),
            new Hook(SessionIdEvent.SESSION_END, null, null, 5, false));

    private final JsonMapper mapper;

    public ClaudeHookAdapter(JsonMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public JsonMapper mapper() {
        return mapper;
    }

    @Override
    public Class<ClaudeHookInput> inputType() {
        return ClaudeHookInput.class;
    }

    @Override
    public HostId host() {
        return HOST;
    }

    /** Claude Code has no model-request events. */
    @Override
    public boolean takes(SessionIdEvent event) {
        return !event.isModelRequest();
    }

    @Override
    public SessionActor actor(SessionIdEvent event, ClaudeHookInput input) {
        if (input.agentType() != null && HookJson.present(input.agentType()).isEmpty()) {
            return SessionActor.indeterminate("CLAUDE_NATIVE_AGENT_TYPE_INVALID");
        }
        if (input.agentId() != null) {
            return HookJson.present(input.agentId()).map(id -> SessionActor.child(id, "CLAUDE_NATIVE_CHILD_AGENT_ID"))
                    .orElseGet(() -> SessionActor.indeterminate("CLAUDE_NATIVE_AGENT_ID_INVALID"));
        }
        return switch (event) {
            case SUBAGENT_START -> SessionActor.indeterminate("CLAUDE_NATIVE_SUBAGENT_START_AGENT_ID_INVALID");
            case SUBAGENT_STOP -> SessionActor.indeterminate("CLAUDE_NATIVE_SUBAGENT_STOP_AGENT_ID_INVALID");
            // The host sets agent_type on every hook of a session started with --agent, and agent_id only inside a subagent.
            case SESSION_START, SESSION_END, USER_PROMPT_SUBMIT, PRE_TOOL_USE, POST_TOOL_USE, POST_TOOL_USE_FAILURE, STOP, PRE_COMPACT, POST_COMPACT, POST_MODEL_SWITCH,
                    PRE_MODEL_REQUEST, POST_MODEL_REQUEST -> input.agentType() != null ? SessionActor.presetAgentRoot(Basis.INFERRED, "CLAUDE_NATIVE_ROOT_PRESET_AGENT")
                            : SessionActor.root(Basis.INFERRED, "CLAUDE_NATIVE_ROOT_AGENT_ID_ABSENT");
        };
    }

    @Override
    public String effort(ClaudeHookInput input) {
        return input.effort() == null ? null : HookJson.presentOrNull(input.effort().level());
    }

    @Override
    public boolean keepsToolResult(String toolName, boolean failed) {
        // A file tool's success carries the file it wrote (Write: its whole content), which is text the user's file holds, not an outcome of a run.
        return !CONTENT_TOOLS.contains(toolName) && (failed || !ClaudeWorkFacts.EDIT_TOOLS.contains(toolName));
    }

    @Override
    public SessionIdOutput continueTurn(String eventName, String instruction) {
        return SessionIdOutput.additionalContext(eventName, instruction);
    }

    @Override
    public List<HostRegistrationFile> registrationFiles(HookInstallation installation) {
        Map<String, List<HookDefinition.Entry>> events = new LinkedHashMap<>();
        // The session start tells the server which process the host is, starts the server when none is running, and shows the guidance on stderr (exit 2: shown to the user, blocks nothing) when the server is down.
        HookClientCall start = HookClientCall.of(HOST.label(), SessionIdEvent.SESSION_START.hostName(), FailureAnswer.GUIDANCE_STDERR).bindingHost()
                .startingServer(HookClientCall.START_SERVER_SECONDS);
        List<HookDefinition.Entry> sessionStart = entriesOf(events, SessionIdEvent.SESSION_START);
        sessionStart.add(HookDefinition.Entry.of(null, command(installation, start, HookClientCall.SESSION_START_LIMIT_SECONDS, null, null)));
        sessionStart.add(HookDefinition.Entry.of(null, wake(installation, SessionIdEvent.SESSION_START)));
        for (Hook hook : HOOKS) {
            entriesOf(events, hook.event()).add(HookDefinition.Entry.of(hook.matcher(), eventHook(installation, hook)));
        }
        // A wake hook ends when it wakes the session, so the end of every turn starts the next one (the session is idle from there).
        entriesOf(events, SessionIdEvent.STOP).add(HookDefinition.Entry.of(null, wake(installation, SessionIdEvent.STOP)));
        return List.of(new HostRegistrationFile.WholeFile(HOOKS_FILE, mapper().valueToTree(new HookDefinition.ByEvent(events))),
                new HostRegistrationFile.WholeFile(MCP_FILE, mapper().valueToTree(HookDefinition.sennardServer(installation, HOST.label()))));
    }

    @Override
    public List<HostRegistrationFile.WholeFile> shippedFiles() {
        return List.of(new HostRegistrationFile.WholeFile(HOOKS_FILE, mapper().valueToTree(HookDefinition.noHooks())),
                new HostRegistrationFile.WholeFile(MCP_FILE, mapper().valueToTree(HookDefinition.noServers())));
    }

    /**
     * Tool gates use exit 2 when the client cannot obtain a server decision. {@code onFailure: "block"} also covers a command the host cannot run.
     * The HTTP read timeout is one second shorter than the host timeout.
     */
    private static HookDefinition.Command eventHook(HookInstallation installation, Hook hook) {
        FailureAnswer failure = hook.blocksOnFailure() ? FailureAnswer.BLOCK_STDERR : FailureAnswer.FAIL;
        HookClientCall call = HookClientCall.of(HOST.label(), hook.event().hostName(), failure).withTimeout(hook.hostTimeout() - 1);
        return command(installation, hook.module() == null ? call : call.inModule(hook.module()), hook.hostTimeout(), null, hook.blocksOnFailure() ? "block" : null);
    }

    private static List<HookDefinition.Entry> entriesOf(Map<String, List<HookDefinition.Entry>> events, SessionIdEvent event) {
        return events.computeIfAbsent(event.hostName(), key -> new ArrayList<>());
    }

    /**
     * The idle wake: this hook waits on the server, and a message makes it end with exit 2 and the message on stderr, which wakes the session.
     * Any failure, a timeout or no message included, ends it with exit 0 and does not wake (measured on Linux with no message).
     *
     * <p>Only an attended session waits. {@code claude -p} does not start until an asyncRewake SessionStart hook ends, and a Stop
     * wake keeps it from exiting (measured with Claude Code 2.1.319 on Linux: init came after 90.664 s behind a 90 s hook, and a wake hook held
     * it until a message arrived). Claude Code gives hooks {@code CLAUDE_CODE_SESSION_ATTENDED}: 1 in an interactive session, 0 for every
     * {@code -p} form measured; a {@code claude -p} started with CLAUDE_CODE_SESSION_ATTENDED=1 in its parent's environment still gave its
     * SessionStart hook 0 (same version, Linux). Sessions started through the SDK were not measured. The variable is not in Claude Code's documentation,
     * which offers no other way for a hook to tell the two apart. So the hook client waits only for exactly 1 and otherwise ends at once with no request: if the
     * variable disappears or is renamed, automatic wake turns off and {@code -p} still does not hang.
     *
     * @param event the point the session is idle from: its start, or the end of a turn
     */
    private static HookDefinition.Command wake(HookInstallation installation, SessionIdEvent event) {
        HookClientCall call = HookClientCall.of(HOST.label(), event.hostName(), FailureAnswer.FAIL).asWake(WAKE_MAX_TIME_SECONDS, ATTENDED + "=1");
        return command(installation, call, WAKE_MAX_TIME_SECONDS + 60, Boolean.TRUE, null);
    }

    /** A command hook in exec form: no shell between the host and the hook client, so no quoting and no process of a shell in the way. */
    private static HookDefinition.Command command(HookInstallation installation, HookClientCall call, int timeout, Boolean asyncRewake, String onFailure) {
        return new HookDefinition.Command(null, installation.hookExecutable(), installation.hookArguments(call.arguments()), timeout, asyncRewake, null, onFailure);
    }
}
