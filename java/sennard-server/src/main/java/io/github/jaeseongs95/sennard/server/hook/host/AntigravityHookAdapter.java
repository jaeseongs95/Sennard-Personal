// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook.host;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
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
import tools.jackson.databind.node.ObjectNode;

/**
 * Antigravity: conversation identifiers instead of session identifiers, one wrapper tool for every MCP call, and {@code {"decision":"allow"}}
 * as the only allow its PreToolUse accepts ({@code {}} is taken as a refusal, measured).
 */
@Component
public final class AntigravityHookAdapter implements HostHookAdapter {
    private static final HostId HOST = AntigravityHost.ID;
    /** The hook definition and the MCP registration, both files of their own at the root of the package. */
    private static final Path HOOKS_FILE = Path.of("hooks.json");
    private static final Path MCP_FILE = Path.of("mcp_config.json");
    private static final Pattern MODEL_AND_EFFORT = Pattern.compile("^(.+)-(low|medium|high)$");
    /** Antigravity names the server it was given ({@code sennard}, {@link HookDefinition#MCP_SERVER}) with its own prefix: {@code sennard_sennard} is what it sends as {@code ServerName} (measured). */
    private static final Pattern SENNARD_SERVER = Pattern.compile("^(?:sennard_)?sennard$");
    /** The same name as one tool name, {@code mcp_<server>_<tool>}, with or without the host's {@code default_api:} prefix. */
    private static final Pattern SENNARD_DIRECT_TOOL = Pattern.compile("^(?:default_api:)?mcp_(?:sennard_)?sennard_(.+)$");
    private static final String MCP_WRAPPER = "call_mcp_tool";
    private static final String HOST_TOOL_PREFIX = "default_api:";
    private static final Set<String> NON_CONTINUABLE_STOP_REASONS = Set.of("USER_CANCELED", "ERROR", "MAX_INVOCATIONS", "MAX_FORCED_INVOCATIONS", "MAX_TOKEN_BUDGET_EXCEEDED",
            "TERMINAL_STEP_TYPE", "TERMINAL_CUSTOM_HOOK");

    private final JsonMapper mapper;
    private final Function<String, SessionActor> hookActor;

    public AntigravityHookAdapter(JsonMapper mapper) {
        this(mapper, ignored -> AntigravityCallerAdapter.unclassifiedHook());
    }

    @Autowired
    public AntigravityHookAdapter(JsonMapper mapper, AntigravityCallerAdapter callers) {
        this(mapper, callers::currentHookActor);
    }

    private AntigravityHookAdapter(JsonMapper mapper, Function<String, SessionActor> hookActor) {
        this.mapper = mapper;
        this.hookActor = hookActor;
    }

    @Override
    public HostId host() {
        return HOST;
    }

    @Override
    public Class<AntigravityHookInput> inputType() {
        return AntigravityHookInput.class;
    }

    @Override
    public HostEvent read(String eventName, HookBody body) {
        AntigravityHookInput input = body.bind(mapper, AntigravityHookInput.class);
        String conversation = HookJson.requiredSession(input.conversationId());
        AntigravityEvent event = AntigravityEvent.named(eventName).orElseThrow(() -> new HookRejected(HookRejection.HOOK_EVENT_UNKNOWN));
        HostContext context = context(conversation, input);
        return switch (event) {
            case SESSION_START -> new HostEvent.SessionStarted(context, HostEvent.StartKind.STARTUP, false);
            case PRE_INVOCATION -> starting(context, input);
            // The invocation boundary takes injected steps (see respond).
            case POST_INVOCATION -> new HostEvent.ModelCallFinished(context, true);
            case PRE_TOOL_USE -> new HostEvent.ToolRequested(context, tool(input));
            // PostToolUse carries no tool output and an empty error even when the command failed; the next model call is the step boundary.
            case POST_TOOL_USE -> new HostEvent.ToolFinished(context, finishedTool(input), null, HookJson.present(input.error()).isPresent(), false);
            case STOP -> new HostEvent.TurnEnded(context, false, !nonContinuable(input));
        };
    }

    private static HostEvent starting(HostContext context, AntigravityHookInput input) {
        if (!input.firstInvocation()) {
            return new HostEvent.ModelCallStarting(context);
        }
        return context.actor().rootEstablished() && input.lastUserInput() != null
                ? new HostEvent.UserPromptSubmitted(context, input.lastUserInput(), HostEvent.PromptOrigin.USER, true)
                : new HostEvent.TurnStarted(context, context.actor().basis() == Basis.PROVISIONAL ? input.lastUserInput() : null, true);
    }

    /** The host's terminal and canceled stops do not accept continuations. */
    private static boolean nonContinuable(AntigravityHookInput input) {
        return HookJson.present(input.error()).isPresent() || Boolean.FALSE.equals(input.fullyIdle())
                || NON_CONTINUABLE_STOP_REASONS.contains(HookJson.text(input.terminationReason()));
    }

    private HostContext context(String conversation, AntigravityHookInput input) {
        String modelName = HookJson.presentOrNull(input.modelName());
        Matcher named = modelName == null ? null : MODEL_AND_EFFORT.matcher(modelName);
        boolean withEffort = named != null && named.matches();
        String model = withEffort ? named.group(1) : modelName;
        String effort = withEffort ? named.group(2) : null;
        return new HostContext(HOST, conversation, hookActor.apply(conversation), input.firstWorkspacePath(),
                HookJson.presentOrNull(input.transcriptPath()), model, effort, null);
    }

    /** A finished call with the host's artifact folder, which only this host's facts read. */
    private ToolCall finishedTool(AntigravityHookInput input) {
        ToolCall call = tool(input);
        String folder = HookJson.text(input.artifactDirectoryPath());
        if (folder.isEmpty() || !(call.input() instanceof ObjectNode arguments)) {
            return call;
        }
        return new ToolCall(call.callId(), call.name(), call.sennardTool(), arguments.deepCopy().put(AntigravityTurnEvidence.ARTIFACT_DIRECTORY, folder));
    }

    /** The dispatched MCP request does not carry the hook's tool ID. */
    private ToolCall tool(AntigravityHookInput input) {
        AntigravityHookInput.ToolCall call = input.toolCall();
        String name = call == null ? "" : HookJson.text(call.name());
        JsonNode arguments = HookJson.objectOrEmpty(call == null ? null : call.args());
        String local = name.startsWith(HOST_TOOL_PREFIX) ? name.substring(HOST_TOOL_PREFIX.length()) : name;
        if (MCP_WRAPPER.equals(local)) {
            AntigravityHookInput.McpWrapperArguments wrapped = mapper.treeToValue(arguments, AntigravityHookInput.McpWrapperArguments.class);
            String tool = HookJson.text(wrapped.toolName());
            if (SENNARD_SERVER.matcher(HookJson.text(wrapped.serverName())).matches() && !tool.isEmpty()) {
                return new ToolCall(null, name, tool, HookJson.objectOrEmpty(wrapped.arguments()));
            }
        }
        Matcher direct = SENNARD_DIRECT_TOOL.matcher(name);
        if (!direct.matches()) {
            return new ToolCall(null, name, null, arguments);
        }
        // Antigravity displays this summary in the hook and strips it when dispatching to MCP.
        if (arguments instanceof ObjectNode object && object.has("toolSummary")) {
            ObjectNode mcpArguments = object.deepCopy();
            mcpArguments.remove("toolSummary");
            arguments = mcpArguments;
        }
        return new ToolCall(null, name, direct.group(1), arguments);
    }

    @Override
    public boolean defersStopReason() {
        return true;
    }

    @Override
    public HookOutput respond(String eventName, HostEvent read, HookVerdict verdict) {
        return switch (verdict) {
            case HookVerdict.Proceed ignored -> read instanceof HostEvent.ToolRequested ? AntigravityHookOutput.ALLOW : AntigravityHookOutput.NOTHING;
            case HookVerdict.Refuse refuse when read instanceof HostEvent.ToolRequested -> AntigravityHookOutput.decision("deny", refuse.reason());
            // This host has no prompt-submit event to stop a prompt at.
            case HookVerdict.Refuse ignored -> throw new HookRejected(HookRejection.HOOK_VERDICT_NOT_DELIVERABLE);
            // The host reports when a stop is already terminal.
            case HookVerdict.ContinueTurn ignored when read instanceof HostEvent.TurnEnded ended && !ended.continuable() -> AntigravityHookOutput.NOTHING;
            case HookVerdict.ContinueTurn turn -> AntigravityHookOutput.decision("continue", turn.instruction());
            // Stop can let the loop end but can only deliver its reason when it asks the host to continue.
            case HookVerdict.TerminateTurn ignored -> AntigravityHookOutput.NOTHING;
            case HookVerdict.AddContext context when read instanceof HostEvent.TurnEnded -> AntigravityHookOutput.decision("continue", context.blocks().getFirst().text());
            // Injected steps reach the model only from the invocation boundaries, not from a finished tool.
            case HookVerdict.AddContext ignored when read instanceof HostEvent.ToolFinished -> throw new HookRejected(HookRejection.HOOK_VERDICT_NOT_DELIVERABLE);
            case HookVerdict.AddContext context -> injectSteps(context.blocks());
        };
    }

    /**
     * One step per block. This host puts the steps' texts one after another with nothing between them (6.0.0 real-host run: the end of one
     * block ran into the first word of the next), so every block but the last ends with a line break.
     */
    private static AntigravityHookOutput injectSteps(List<ContextBlock> blocks) {
        List<AntigravityHookOutput.Step> steps = new ArrayList<>(blocks.size());
        for (int index = 0; index < blocks.size(); index++) {
            ContextBlock block = blocks.get(index);
            String text = index < blocks.size() - 1 && !block.text().endsWith("\n") ? block.text() + "\n" : block.text();
            steps.add(block.repeatsEachTurn() ? AntigravityHookOutput.Step.ephemeral(text) : AntigravityHookOutput.Step.system(text));
        }
        return AntigravityHookOutput.inject(steps);
    }

    @Override
    public List<HostRegistrationFile> registrationFiles(HookInstallation installation) {
        HookDefinition.Lifecycle lifecycle = new HookDefinition.Lifecycle(true,
                List.of(handler(installation, HookClientCall.of(HOST.label(), AntigravityEvent.SESSION_START.hostName(), FailureAnswer.GUIDANCE_STEPS).bindingHost()
                        .startingServer(HookClientCall.START_SERVER_SECONDS), HookClientCall.SESSION_START_LIMIT_SECONDS)),
                List.of(handler(installation, HookClientCall.of(HOST.label(), AntigravityEvent.PRE_INVOCATION.hostName(), FailureAnswer.FAIL))),
                List.of(handler(installation, HookClientCall.of(HOST.label(), AntigravityEvent.POST_INVOCATION.hostName(), FailureAnswer.FAIL))),
                List.of(handler(installation, HookClientCall.of(HOST.label(), AntigravityEvent.STOP.hostName(), FailureAnswer.FAIL).bindingHost())));
        String preToolUse = AntigravityEvent.PRE_TOOL_USE.hostName();
        HookClientCall continuity = HookClientCall.of(HOST.label(), preToolUse, FailureAnswer.DENY_DECISION).denyingRefusal();
        HookClientCall nativePeer = HookClientCall.of(HOST.label(), preToolUse, FailureAnswer.DENY_DECISION)
                .inModule("native-peer").withTimeout(5);
        HookDefinition.Boundary boundary = new HookDefinition.Boundary(true,
                List.of(HookDefinition.Entry.of("*", handler(installation, continuity)),
                        HookDefinition.Entry.of(AntigravityWorkFacts.NATIVE_PEER_SEND.pattern(), handler(installation, nativePeer))),
                List.of(HookDefinition.Entry.of("*", handler(installation, HookClientCall.of(HOST.label(), AntigravityEvent.POST_TOOL_USE.hostName(), FailureAnswer.FAIL)))));
        return List.copyOf(HookDefinition.wholeRegistrationFiles(mapper, HOOKS_FILE, mapper.valueToTree(new HookDefinition.AntigravityFile(lifecycle, boundary)),
                MCP_FILE, HookDefinition.sennardServer(installation, HOST.label())));
    }

    /** What the package ships: no hook (an empty object, the file has no member to switch off) and no MCP server. */
    @Override
    public List<HostRegistrationFile.WholeFile> shippedFiles() {
        return HookDefinition.wholeRegistrationFiles(mapper, HOOKS_FILE, mapper.createObjectNode(), MCP_FILE, HookDefinition.noServers());
    }

    /**
     * The host documents only command handlers (antigravity.google/docs/hooks). The client prints the configured JSON answer on failure.
     * Both PreToolUse commands deny when the client cannot obtain a server decision. A server's allow delegates to the next hook or default policy.
     *
     * <p>On Windows this host wraps the command in {@code cmd /c "..."} and writes each inner {@code "} as {@code \"} (measured), so a program path that
     * needs quotes does not survive there; the installer's definition holds the path as it is, and a path with a space is left to the real-host run.
     */
    private static HookDefinition.Command handler(HookInstallation installation, HookClientCall call) {
        return handler(installation, call, 10);
    }

    private static HookDefinition.Command handler(HookInstallation installation, HookClientCall call, int timeoutSeconds) {
        return new HookDefinition.Command(null, installation.hookCommandLine(call.arguments()), null, timeoutSeconds, null, null, null);
    }
}
