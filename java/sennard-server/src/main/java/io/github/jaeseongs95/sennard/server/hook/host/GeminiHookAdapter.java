// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook.host;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.server.hook.HookBody;
import io.github.jaeseongs95.sennard.hookclient.HookClientCall;
import io.github.jaeseongs95.sennard.hookclient.HookClientCall.FailureAnswer;
import io.github.jaeseongs95.sennard.server.hook.HookInstallation;
import io.github.jaeseongs95.sennard.server.hook.HookOutput;
import io.github.jaeseongs95.sennard.server.hook.HookRejected;
import io.github.jaeseongs95.sennard.server.hook.HookRejection;
import io.github.jaeseongs95.sennard.server.hook.HookVerdict;
import io.github.jaeseongs95.sennard.server.hook.HookVerdict.ContextBlock;
import io.github.jaeseongs95.sennard.server.hook.HostContext;
import io.github.jaeseongs95.sennard.server.hook.HostContext.Basis;
import io.github.jaeseongs95.sennard.server.hook.HostContext.SessionActor;
import io.github.jaeseongs95.sennard.server.hook.HostEvent;
import io.github.jaeseongs95.sennard.server.hook.HostHookAdapter;
import io.github.jaeseongs95.sennard.server.hook.HostId;
import io.github.jaeseongs95.sennard.server.hook.HostRegistrationFile;
import io.github.jaeseongs95.sennard.server.hook.ToolCall;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Gemini CLI (geminicli.com/docs/hooks/reference): a hook gets the event as JSON on stdin ({@code session_id}, {@code transcript_path}, {@code cwd},
 * {@code hook_event_name}, {@code timestamp} and the event's own fields) and answers JSON on stdout; the host runs only commands, so every hook is the Sennard
 * hook client. Where the host has a place for text the model reads it is {@code hookSpecificOutput.additionalContext}, at the session start, after a prompt
 * and after a tool; a turn is continued by denying the end of the agent loop ({@code AfterAgent}: "decision: deny rejects the response and forces a retry,
 * {@code reason} becomes the new prompt"); a tool or a prompt is refused by {@code decision: deny}.
 *
 * <p>The host's event names come in as the {@link Event} enum at one place ({@link Event#named}); everything after that is an exhaustive {@code switch}.
 * Of the host's eleven events the adapter takes the ones the server has a meaning for. Not taken, with the reason (the same table is in
 * {@code docs/contributing/hooks.md}): {@code AfterModel} fires per streamed response chunk, so it is no "model call finished"; {@code BeforeToolSelection}
 * and {@code Notification} are not events the server records.
 */
@Component
public final class GeminiHookAdapter implements HostHookAdapter {
    private static final String INVOKE_AGENT = "invoke_agent";
    private static final HostId HOST = GeminiHost.ID;
    private static final Path HOOKS_FILE = Path.of("hooks", "hooks.json");
    /** The extension's manifest, and the member of it that registers the MCP servers (RFC 6901). */
    private static final Path MANIFEST_FILE = Path.of("gemini-extension.json");
    private static final String MCP_SERVERS_POINTER = "/mcpServers";
    private static final Set<String> SENNARD_SERVERS = Set.of(HookDefinition.MCP_SERVER);
    private static final List<String> SENNARD_TOOL_PREFIXES = List.of("mcp_sennard_", "sennard__");
    /** The host's time limit for a hook is in milliseconds (60 000 by default); a hook here waits for the server for nine seconds at most. */
    private static final int HOOK_TIMEOUT_MILLISECONDS = 10_000;
    private static final String WINDOWS_RESULT_HOOK = """
            $ErrorActionPreference = 'Stop';
            $clock = [Diagnostics.Stopwatch]::StartNew();
            $client = $null;
            try {
                $utf8 = [Text.UTF8Encoding]::new($false);
                [Console]::InputEncoding = $utf8; [Console]::OutputEncoding = $utf8;
                $start = [Diagnostics.ProcessStartInfo]::new();
                $start.FileName = $utf8.GetString([Convert]::FromBase64String('%s'));
                $start.Arguments = $utf8.GetString([Convert]::FromBase64String('%s'));
                $start.UseShellExecute = $false; $start.CreateNoWindow = $true;
                $start.RedirectStandardInput = $true; $start.RedirectStandardOutput = $true; $start.RedirectStandardError = $true;
                $start.StandardOutputEncoding = $utf8; $start.StandardErrorEncoding = $utf8;
                $client = [Diagnostics.Process]::Start($start);
                $output = $client.StandardOutput.ReadToEndAsync(); $errors = $client.StandardError.ReadToEndAsync();
                $body = $utf8.GetBytes([Console]::In.ReadToEnd());
                $stdin = $client.StandardInput.BaseStream;
                $sent = $stdin.WriteAsync($body, 0, $body.Length);
                if (!$sent.Wait([Math]::Max(1, %d - [int]$clock.ElapsedMilliseconds))) { throw 'Hook input deadline'; }
                $stdin.Close();
                if (!$client.WaitForExit([Math]::Max(1, %d - [int]$clock.ElapsedMilliseconds))) { throw 'Hook result deadline'; }
                $answer = $output.GetAwaiter().GetResult();
                if ($client.ExitCode -ne 0 -or [string]::IsNullOrWhiteSpace($answer)) { throw 'Hook result missing'; }
                [Console]::Out.Write($answer); [Console]::Error.Write($errors.GetAwaiter().GetResult());
            } catch {
                [Console]::Out.Write((@{ decision = 'deny'; reason = 'The Sennard hook could not process this subagent result. Use Sennard peer messaging.' } | ConvertTo-Json -Compress));
            } finally {
                if ($null -ne $client) {
                    try { if (!$client.HasExited) { $client.Kill(); } } finally { $client.Dispose(); }
                }
            };
            exit 0
            """;

    /** The events the adapter takes, by the name the host gives them. */
    enum Event implements HostEventName {
        SESSION_START("SessionStart"), SESSION_END("SessionEnd"), BEFORE_AGENT("BeforeAgent"), AFTER_AGENT("AfterAgent"), BEFORE_MODEL("BeforeModel"),
        BEFORE_TOOL("BeforeTool"), AFTER_TOOL("AfterTool"), PRE_COMPRESS("PreCompress");

        private static final Map<String, Event> BY_NAME = Map.copyOf(HostEventName.index(Event.class));

        private final String hostName;

        Event(String hostName) {
            this.hostName = hostName;
        }

        @Override
        public String hostName() {
            return hostName;
        }

        static Optional<Event> named(String hostName) {
            return Optional.ofNullable(BY_NAME.get(hostName));
        }
    }

    private final JsonMapper mapper;

    public GeminiHookAdapter(JsonMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public HostId host() {
        return HOST;
    }

    @Override
    public Class<GeminiHookInput> inputType() {
        return GeminiHookInput.class;
    }

    @Override
    public HostEvent read(String eventName, HookBody body) {
        GeminiHookInput input = body.bind(mapper, GeminiHookInput.class);
        Event event = Event.named(eventName).orElseThrow(() -> new HookRejected(HookRejection.HOOK_EVENT_UNKNOWN));
        HostContext context = context(event, input);
        return switch (event) {
            case SESSION_START -> new HostEvent.SessionStarted(context, startKind(HookJson.text(input.source())));
            case SESSION_END -> new HostEvent.SessionEnded(context, HookJson.text(input.reason()));
            case BEFORE_AGENT -> new HostEvent.UserPromptSubmitted(context, HookJson.text(input.prompt()));
            case AFTER_AGENT -> new HostEvent.TurnEnded(context, Boolean.TRUE.equals(input.stopHookActive()), true);
            case BEFORE_MODEL -> new HostEvent.ModelCallStarting(context);
            case BEFORE_TOOL -> new HostEvent.ToolRequested(context, tool(input));
            case AFTER_TOOL -> INVOKE_AGENT.equals(input.toolName()) && input.mcpContext() == null
                    ? new HostEvent.NativePeerResult(context)
                    : afterTool(context, input);
            case PRE_COMPRESS -> new HostEvent.CompactionStarting(context);
        };
    }

    private static HostContext context(Event event, GeminiHookInput input) {
        String session = HookJson.requiredSession(input.sessionId());
        // Only a model request names its model; no event names a reasoning effort or a turn. A marker-free hook is inferred to be the root: the host marks no subagent's hooks.
        String model = event == Event.BEFORE_MODEL && input.llmRequest() != null ? HookJson.presentOrNull(input.llmRequest().model()) : null;
        return new HostContext(HOST, session, SessionActor.root(Basis.INFERRED, "GEMINI_ROOT_INFERRED_NO_AGENT_MARKER"), HookJson.presentOrNull(input.cwd()),
                HookJson.presentOrNull(input.transcriptPath()), model, null, null);
    }

    /** {@code source} is startup, resume or clear. */
    private static HostEvent.StartKind startKind(String source) {
        return switch (source) {
            case "startup" -> HostEvent.StartKind.STARTUP;
            case "resume" -> HostEvent.StartKind.RESUME;
            case "clear" -> HostEvent.StartKind.CLEAR;
            default -> HostEvent.StartKind.OTHER;
        };
    }

    /** {@code tool_response} is {@code llmContent}, {@code returnDisplay} and an optional {@code error}; text added after the tool reaches the model, so each is a step boundary. */
    private static HostEvent afterTool(HostContext context, GeminiHookInput input) {
        GeminiHookInput.ToolResponse response = input.toolResponse();
        JsonNode error = response == null ? null : response.error();
        boolean failed = error != null && !error.isNull();
        String result = HookJson.tailOrNull(failed ? error : response == null ? null : response.llmContent());
        return new HostEvent.ToolFinished(context, tool(input), result, failed, true);
    }

    /** The host gives a call no identifier, so the call carries none. */
    private static ToolCall tool(GeminiHookInput input) {
        String name = HookJson.text(input.toolName());
        return new ToolCall(null, name, sennardTool(name, input.mcpContext()), HookJson.objectOrEmpty(input.toolInput()));
    }

    /** An MCP tool of the Sennard server: by the server the host names in {@code mcp_context}, or by the host's own naming of the tool; null for a tool of another server. */
    private static String sennardTool(String toolName, GeminiHookInput.McpContext mcpContext) {
        if (mcpContext != null && SENNARD_SERVERS.contains(HookJson.text(mcpContext.serverName()))) {
            String named = HookJson.presentOrNull(mcpContext.toolName());
            if (named != null) {
                return named;
            }
        }
        for (String prefix : SENNARD_TOOL_PREFIXES) {
            if (toolName.startsWith(prefix)) {
                String rest = toolName.substring(prefix.length());
                return rest.isEmpty() ? null : rest;
            }
        }
        return null;
    }

    @Override
    public HookOutput respond(String eventName, HostEvent read, HookVerdict verdict) {
        Event event = Event.named(eventName).orElseThrow(() -> new HookRejected(HookRejection.HOOK_EVENT_UNKNOWN));
        return switch (verdict) {
            case HookVerdict.Proceed ignored -> GeminiHookOutput.NOTHING;
            case HookVerdict.AddContext context -> addContext(event, context.blocks().stream().map(ContextBlock::text).collect(Collectors.joining("\n\n")));
            case HookVerdict.ContinueTurn turn -> event == Event.AFTER_AGENT ? GeminiHookOutput.deny(turn.instruction()) : notDeliverable();
            case HookVerdict.Refuse refuse -> event == Event.BEFORE_TOOL || event == Event.BEFORE_AGENT
                    || (event == Event.AFTER_TOOL && read instanceof HostEvent.NativePeerResult)
                    ? GeminiHookOutput.deny(refuse.reason()) : notDeliverable();
        };
    }

    /** The host's events that take text for the model; every other has no place for it, and the verdict is refused instead of rendered as something. */
    private static GeminiHookOutput addContext(Event event, String text) {
        return switch (event) {
            case SESSION_START, BEFORE_AGENT, AFTER_TOOL -> GeminiHookOutput.additionalContext(text);
            case SESSION_END, AFTER_AGENT, BEFORE_MODEL, BEFORE_TOOL, PRE_COMPRESS -> notDeliverable();
        };
    }

    private static GeminiHookOutput notDeliverable() {
        throw new HookRejected(HookRejection.HOOK_VERDICT_NOT_DELIVERABLE);
    }

    /**
     * The extension's {@code hooks/hooks.json}: {@code {"hooks": {<event>: [{"matcher": ..., "hooks": [<command>]}]}}}; and the registration of the MCP server, the Sennard relay as a
     * stdio server, as the {@code mcpServers} member of the extension's own manifest {@code gemini-extension.json} (which also holds the extension's name and version, so only that
     * member is Sennard's to write).
     */
    @Override
    public List<HostRegistrationFile> registrationFiles(HookInstallation installation) {
        Map<String, List<HookDefinition.Entry>> hooks = new LinkedHashMap<>();
        for (Event event : Event.values()) {
            FailureAnswer failure = switch (event) {
                case SESSION_START -> FailureAnswer.GUIDANCE_SYSTEM_MESSAGE;
                case BEFORE_TOOL -> FailureAnswer.DENY_DECISION;
                default -> FailureAnswer.FAIL;
            };
            HookClientCall call = HookClientCall.of(HOST.label(), event.hostName(), failure);
            HookClientCall sent = switch (event) {
                case SESSION_START -> call.bindingHost().startingServer(HookClientCall.START_SERVER_SECONDS);
                case BEFORE_TOOL -> call.denyingRefusal();
                case SESSION_END, BEFORE_AGENT, AFTER_AGENT, BEFORE_MODEL, AFTER_TOOL, PRE_COMPRESS -> call;
            };
            int timeout = event == Event.SESSION_START ? HookClientCall.SESSION_START_LIMIT_SECONDS * 1000 : HOOK_TIMEOUT_MILLISECONDS;
            HookDefinition.Command handler = new HookDefinition.Command("sennard-" + event.hostName(), installation.hookCommandLine(sent.arguments()), null, timeout, null, null, null);
            List<HookDefinition.Entry> entries = hooks.computeIfAbsent(event.hostName(), key -> new ArrayList<>());
            if (event == Event.AFTER_TOOL) {
                entries.add(HookDefinition.Entry.of("^(?!" + INVOKE_AGENT + "$).*$", handler));
                HookClientCall result = HookClientCall.of(HOST.label(), event.hostName(), FailureAnswer.DENY_RESULT);
                HookDefinition.Command resultHandler = new HookDefinition.Command("sennard-native-peer-result",
                        nativeResultCommand(installation, result), null, timeout, null, null, null);
                entries.add(HookDefinition.Entry.of("^" + INVOKE_AGENT + "$", resultHandler));
            } else {
                entries.add(HookDefinition.Entry.of("*", handler));
            }
        }
        return List.of(new HostRegistrationFile.WholeFile(HOOKS_FILE, mapper.valueToTree(new HookDefinition.ByEvent(hooks))),
                new HostRegistrationFile.Member(MANIFEST_FILE, MCP_SERVERS_POINTER, mapper.valueToTree(HookDefinition.sennardServer(installation, HOST.label()).mcpServers())));
    }

    /**
     * Gemini runs command hooks through PowerShell on Windows. Explicitly connect the three streams so a successful shell exit without a client decision cannot release a child result.
     * Encode the paths and native argument string to keep PowerShell quoting and Gemini's command substitutions out of the installed program words.
     */
    private static String nativeResultCommand(HookInstallation installation, HookClientCall result) {
        if (installation.platform() != HookInstallation.Platform.WINDOWS) {
            return installation.hookCommandLine(result.arguments());
        }
        // The ordinary hook registrations above have already required words with no embedded quotes.
        String arguments = installation.hookArguments(result.arguments()).stream()
                .map(word -> "\"" + word.replaceFirst("(\\\\+)$", "$1$1") + "\"")
                .collect(Collectors.joining(" "));
        int deadline = HOOK_TIMEOUT_MILLISECONDS - 1000;
        return WINDOWS_RESULT_HOOK.formatted(encoded(installation.hookExecutable()), encoded(arguments), deadline, deadline)
                .lines().map(String::strip).collect(Collectors.joining(" "));
    }

    private static String encoded(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    /** Only the hook definition is shipped: the manifest has no {@code mcpServers} member until an installation registers the relay. */
    @Override
    public List<HostRegistrationFile.WholeFile> shippedFiles() {
        return List.of(new HostRegistrationFile.WholeFile(HOOKS_FILE, mapper.valueToTree(HookDefinition.noHooks())));
    }
}
