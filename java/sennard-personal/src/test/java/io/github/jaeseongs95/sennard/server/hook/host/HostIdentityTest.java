// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.jaeseongs95.sennard.server.hook.HostContext;
import io.github.jaeseongs95.sennard.server.hook.HostContext.Basis;
import io.github.jaeseongs95.sennard.server.hook.HostContext.SessionActor;
import io.github.jaeseongs95.sennard.server.hook.HostDefinition;
import io.github.jaeseongs95.sennard.server.hook.HostDefinitions;
import io.github.jaeseongs95.sennard.server.hook.HostHookAdapter;
import io.github.jaeseongs95.sennard.server.hook.HostId;
import io.github.jaeseongs95.sennard.server.mcp.HostCallerAdapter;
import io.github.jaeseongs95.sennard.serverit.HookAdapters;

/**
 * Which host something belongs to is one value, {@link HostId}, and a host's names are written once, in its own adapter package. Case table (input; expected):
 * <ol>
 * <li>the names the product posted its hooks under and stored its sessions under before the type existed are the names it has now: claude / claude-code, codex / codex,
 * agy / antigravity, gemini / gemini (a stored session is found by them)</li>
 * <li>every adapter of one host (hook, caller) answers the same value as the host's definition, and the label finds the definition back (round trip)</li>
 * <li>the transcript of Claude Code is stored under the same session name as its sessions</li>
 * <li>a value is a label that fits an endpoint's path and a session name that is not empty</li>
 * <li>the printed form of a context shows the label, as the adapters' golden digests read it</li>
 * </ol>
 */
class HostIdentityTest {
    private record Names(HostId id, String label, String sessionHost) {
    }

    private static final List<Names> NAMES = List.of(new Names(ClaudeHost.ID, "claude", "claude-code"), new Names(CodexHost.ID, "codex", "codex"),
            new Names(AntigravityHost.ID, "agy", "antigravity"), new Names(GeminiHost.ID, "gemini", "gemini"));

    @Test
    void theNamesAHostWasPostedAndStoredUnderAreTheOnesItHasNow() {
        for (Names host : NAMES) {
            assertEquals(host.label(), host.id().label());
            assertEquals(host.sessionHost(), host.id().sessionHost());
        }
        assertEquals(NAMES.size(), HostDefinitions.load().all().size(), "every host of the product has its definition");
    }

    @Test
    void everyAdapterOfAHostAnswersTheValueOfItsDefinition() {
        List<HostHookAdapter> hooks = List.of(HookAdapters.claude(), HookAdapters.codex(), HookAdapters.antigravity(), HookAdapters.gemini());
        for (int index = 0; index < NAMES.size(); index++) {
            assertEquals(NAMES.get(index).id(), hooks.get(index).host(), "the hook adapter of " + NAMES.get(index).label());
        }
        List<HostCallerAdapter> callers = List.of(new ClaudeCallerAdapter(), new CodexCallerAdapter(), new AntigravityCallerAdapter(HookAdapters.MAPPER));
        List<HostId> callerHosts = List.of(ClaudeHost.ID, CodexHost.ID, AntigravityHost.ID);
        for (int index = 0; index < callers.size(); index++) {
            assertSame(callerHosts.get(index), callers.get(index).host(), "the caller adapter of " + callerHosts.get(index).label());
        }
        for (HostDefinition definition : HostDefinitions.load().all()) {
            assertEquals(definition.host(), HostDefinitions.load().ofLabel(definition.host().label()).orElseThrow().host(), "the label finds the definition back");
            assertEquals(definition.host(), definition.hookAdapter(HookAdapters.MAPPER).host(), "the definition makes the adapter of its own host");
        }
    }

    @Test
    void theTranscriptOfClaudeCodeIsStoredUnderTheNameOfItsSessions() {
        assertEquals("claude-code", ClaudeTranscriptFile.HOST);
        assertEquals(ClaudeHost.ID.sessionHost(), ClaudeTranscriptFile.HOST);
    }

    @Test
    void aLabelIsAPathSegmentAndASessionNameIsNotEmpty() {
        assertEquals(new HostId("a", "a"), new HostId("a"));
        assertEquals("agy", AntigravityHost.ID.toString(), "the printed form is the label");
        for (String bad : new String[] {"", " ", "a b", "a/b", "a\tb"}) {
            assertThrows(IllegalArgumentException.class, () -> new HostId(bad), "label [" + bad + "]");
        }
        assertThrows(IllegalArgumentException.class, () -> new HostId(null, "x"));
        assertThrows(IllegalArgumentException.class, () -> new HostId("x", ""));
        assertThrows(IllegalArgumentException.class, () -> new HostId("x", null));
    }

    @Test
    void theContextPrintsTheLabelOfItsHost() {
        HostContext context = new HostContext(ClaudeHost.ID, "s", SessionActor.root(Basis.INFERRED, "X"), null, null, null, null, null);

        assertTrue(context.toString().startsWith("HostContext[hostLabel=claude, sessionId=s, "), context.toString());
    }
}
