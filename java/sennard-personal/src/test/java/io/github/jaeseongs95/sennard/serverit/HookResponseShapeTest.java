// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import io.github.jaeseongs95.sennard.server.hook.HookRejected;
import io.github.jaeseongs95.sennard.server.hook.HookVerdict;
import io.github.jaeseongs95.sennard.server.hook.HookVerdict.ContextBlock;
import io.github.jaeseongs95.sennard.server.hook.HostContext;
import io.github.jaeseongs95.sennard.server.hook.HostContext.SessionActor;
import io.github.jaeseongs95.sennard.server.hook.HostEvent;
import io.github.jaeseongs95.sennard.server.hook.HostHookAdapter;
import io.github.jaeseongs95.sennard.server.hook.HostId;
import io.github.jaeseongs95.sennard.server.hook.ToolCall;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * The verdicts as each host must read them. Shapes marked measured are the ones the host measurements showed to take effect (report.md
 * 3.C): anything else here is what a host's own documentation fixed.
 */
class HookResponseShapeTest {
    private static final JsonMapper MAPPER = HookAdapters.MAPPER;
    private static final HostContext CONTEXT = new HostContext(new HostId("label"), "session", SessionActor.indeterminate("TEST"),
            null, null, null, null, null);
    private static final ToolCall TOOL = new ToolCall(null, "t", null, JsonNodeFactory.instance.objectNode());
    private static final HostEvent TOOL_REQUESTED = new HostEvent.ToolRequested(CONTEXT, TOOL);
    private static final HostEvent TOOL_FINISHED = new HostEvent.ToolFinished(CONTEXT, TOOL, null, false, false);
    private static final HostEvent PROMPT = new HostEvent.UserPromptSubmitted(CONTEXT, "p", HostEvent.PromptOrigin.USER);
    private static final HostEvent TURN_ENDED = new HostEvent.TurnEnded(CONTEXT, false, true);
    private static final HostEvent SESSION_START = new HostEvent.SessionStarted(CONTEXT, HostEvent.StartKind.STARTUP);
    private static final HookVerdict.Proceed PROCEED = new HookVerdict.Proceed();
    private static final HookVerdict.AddContext CONTEXT_ADDED = new HookVerdict.AddContext(List.of(new ContextBlock("hello", false)));

    private record Row(String name, HostHookAdapter adapter, String event, HostEvent read, HookVerdict verdict, String json) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Row> rendered() {
        HostHookAdapter claude = HookAdapters.claude();
        HostHookAdapter codex = HookAdapters.codex();
        HostHookAdapter antigravity = HookAdapters.antigravity();
        String deny = "{\"hookSpecificOutput\":{\"hookEventName\":\"PreToolUse\",\"permissionDecision\":\"deny\",\"permissionDecisionReason\":\"R\"}}";
        return Stream.of(
                new Row("claude allow, measured", claude, "PreToolUse", TOOL_REQUESTED, PROCEED, "{}"),
                new Row("codex allow, measured", codex, "PreToolUse", TOOL_REQUESTED, PROCEED, "{}"),
                new Row("agy allow, measured; {} would refuse the tool", antigravity, "PreToolUse", TOOL_REQUESTED, PROCEED, "{\"decision\":\"allow\"}"),
                new Row("agy proceed outside a tool boundary", antigravity, "Stop", TURN_ENDED, PROCEED, "{}"),
                new Row("agy proceed after a tool", antigravity, "PostToolUse", TOOL_FINISHED, PROCEED, "{}"),
                new Row("claude deny, measured", claude, "PreToolUse", TOOL_REQUESTED, new HookVerdict.Refuse("R"), deny),
                new Row("codex deny, measured", codex, "PreToolUse", TOOL_REQUESTED, new HookVerdict.Refuse("R"), deny),
                new Row("agy deny, measured", antigravity, "PreToolUse", TOOL_REQUESTED, new HookVerdict.Refuse("R"), "{\"decision\":\"deny\",\"reason\":\"R\"}"),
                new Row("claude context, measured", claude, "SessionStart", SESSION_START, CONTEXT_ADDED, "{\"hookSpecificOutput\":{\"hookEventName\":\"SessionStart\",\"additionalContext\":\"hello\"}}"),
                new Row("codex context, measured", codex, "UserPromptSubmit", PROMPT, CONTEXT_ADDED, "{\"hookSpecificOutput\":{\"hookEventName\":\"UserPromptSubmit\",\"additionalContext\":\"hello\"}}"),
                new Row("agy context, measured", antigravity, "SessionStart", SESSION_START, CONTEXT_ADDED, "{\"injectSteps\":[{\"systemMessage\":{\"systemMessage\":\"hello\"}}]}"),
                new Row("agy repeating context stays out of the transcript", antigravity, "PreInvocation", PROMPT,
                        new HookVerdict.AddContext(List.of(new ContextBlock("cue", true), new ContextBlock("note", false))),
                        "{\"injectSteps\":[{\"ephemeralMessage\":\"cue\\n\"},{\"systemMessage\":{\"systemMessage\":\"note\"}}]}"),
                // L4: the host runs the steps' texts together, so a block that is followed by another ends its line.
                new Row("agy ends every block but the last with a line break", antigravity, "PreInvocation", PROMPT,
                        new HookVerdict.AddContext(List.of(new ContextBlock("then load_context.", true), new ContextBlock("Sennard intake (x)", true), new ContextBlock("kept\n", false),
                                new ContextBlock("last", false))),
                        "{\"injectSteps\":[{\"ephemeralMessage\":\"then load_context.\\n\"},{\"ephemeralMessage\":\"Sennard intake (x)\\n\"},"
                                + "{\"systemMessage\":{\"systemMessage\":\"kept\\n\"}},{\"systemMessage\":{\"systemMessage\":\"last\"}}]}"),
                new Row("claude joins blocks in one context", claude, "SessionStart", SESSION_START,
                        new HookVerdict.AddContext(List.of(new ContextBlock("a", false), new ContextBlock("b", true))),
                        "{\"hookSpecificOutput\":{\"hookEventName\":\"SessionStart\",\"additionalContext\":\"a\\n\\nb\"}}"),
                new Row("claude continues a turn through context", claude, "Stop", TURN_ENDED, new HookVerdict.ContinueTurn("go"),
                        "{\"hookSpecificOutput\":{\"hookEventName\":\"Stop\",\"additionalContext\":\"go\"}}"),
                new Row("codex Stop takes only decision and reason", codex, "Stop", TURN_ENDED, new HookVerdict.ContinueTurn("go"), "{\"decision\":\"block\",\"reason\":\"go\"}"),
                new Row("agy continues a turn", antigravity, "Stop", TURN_ENDED, new HookVerdict.ContinueTurn("go"), "{\"decision\":\"continue\",\"reason\":\"go\"}"),
                new Row("agy context at a turn end continues it", antigravity, "Stop", TURN_ENDED, CONTEXT_ADDED, "{\"decision\":\"continue\",\"reason\":\"hello\"}"),
                new Row("claude refuses a prompt", claude, "UserPromptSubmit", PROMPT, new HookVerdict.Refuse("R"), "{\"decision\":\"block\",\"reason\":\"R\"}"),
                new Row("codex refuses a prompt", codex, "UserPromptSubmit", PROMPT, new HookVerdict.Refuse("R"), "{\"decision\":\"block\",\"reason\":\"R\"}"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rendered")
    void aVerdictIsRenderedInTheHostsOwnShape(Row row) {
        assertEquals(row.json(), MAPPER.writeValueAsString(row.adapter().respond(row.event(), row.read(), row.verdict())), "the host reads these bytes, in this order");
    }

    /** A verdict this host has no way to deliver must fail where it can be seen, not vanish. */
    @Test
    void aVerdictTheHostCannotDeliverIsRefusedNotDropped() {
        HostHookAdapter antigravity = HookAdapters.antigravity();
        assertEquals("HOOK_VERDICT_NOT_DELIVERABLE", assertThrows(HookRejected.class, () -> antigravity.respond("PreInvocation", PROMPT, new HookVerdict.Refuse("R"))).reason());
        assertEquals("HOOK_VERDICT_NOT_DELIVERABLE", assertThrows(HookRejected.class, () -> antigravity.respond("PostToolUse", TOOL_FINISHED, CONTEXT_ADDED)).reason());
    }

    @Test
    void aVerdictTheEventHasNoMeaningForIsReportedByTheController() {
        // Every common-code path, whichever host: only a verdict meant for the event may be rendered.
        assertEquals(false, new HookVerdict.Refuse("R").appliesTo(SESSION_START));
        assertEquals(false, new HookVerdict.ContinueTurn("x").appliesTo(TOOL_REQUESTED));
        assertEquals(false, CONTEXT_ADDED.appliesTo(new HostEvent.SessionEnded(CONTEXT, "other")));
        assertEquals(true, new HookVerdict.Refuse("R").appliesTo(TOOL_REQUESTED));
    }
}
