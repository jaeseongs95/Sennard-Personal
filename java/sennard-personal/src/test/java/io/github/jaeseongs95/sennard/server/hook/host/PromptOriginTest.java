// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.jaeseongs95.sennard.server.hook.HookBody;
import io.github.jaeseongs95.sennard.server.hook.HostEvent;
import io.github.jaeseongs95.sennard.serverit.HookAdapters;

/**
 * Who wrote a prompt is the host's own field ({@code source} of Claude Code's UserPromptSubmit: user, sdk, system, loop_wakeup, schedule_wakeup, poll_event, and a payload may leave it
 * out), never the text: a user can paste a host notice, and it is still the user's request. Only Claude Code's {@code system} is the host's own text; a host that does not say who wrote
 * a prompt has every prompt counted as the user's, whatever fields its input has.
 */
class PromptOriginTest {
    /** The shape Claude Code writes for UserPromptSubmit (a recorded fixture's fields), with the documented {@code source} added. */
    private static HostEvent.UserPromptSubmitted claude(String sourceField, String prompt) {
        String json = "{\"session_id\":\"10000001-1111-4111-8111-000000000001\",\"transcript_path\":\"/home/user/.claude/projects/p/10000001-1111-4111-8111-000000000001.jsonl\","
                + "\"cwd\":\"/home/user/work\",\"prompt_id\":\"10000002-1111-4111-8111-000000000002\",\"permission_mode\":\"default\",\"hook_event_name\":\"UserPromptSubmit\","
                + sourceField + "\"prompt\":" + HookAdapters.MAPPER.writeValueAsString(prompt) + "}";
        return assertInstanceOf(HostEvent.UserPromptSubmitted.class, HookAdapters.claude().read("UserPromptSubmit", HookBody.posted(json.getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void aPromptTheHostSaysItInjectedItselfStartsARequest() {
        HostEvent.UserPromptSubmitted notice = claude("\"source\":\"system\",", "<task-notification><task-id>b1</task-id><status>completed</status></task-notification>");
        assertEquals(HostEvent.PromptOrigin.HOST, notice.origin());
        assertTrue(notice.startsTurn());
        assertTrue(notice.startsRequest(), "a host input starts a request");
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"source\":\"user\",", "\"source\":\"sdk\",", "\"source\":\"loop_wakeup\",", "\"source\":\"schedule_wakeup\",", "\"source\":\"poll_event\",", "",
            "\"source\":\"\",", "\"source\":\"SYSTEM\",", "\"source\":\"system \",", "\"source\":null,", "\"source\":7,", "\"source\":\"unknown_kind\","})
    void everyOtherSourceAndAnAbsentOneIsTheUsersRequest(String sourceField) {
        HostEvent.UserPromptSubmitted prompt = claude(sourceField, "Please fix the build");
        assertEquals(HostEvent.PromptOrigin.USER, prompt.origin(), sourceField);
        assertTrue(prompt.startsRequest(), sourceField);
    }

    /** The text decides nothing: a user who pastes the host's notice (the host says source user) still makes a request. */
    @Test
    void aPastedNoticeIsStillTheUsersRequest() {
        String notice = "<task-notification><task-id>b1</task-id><status>completed</status></task-notification>";
        assertEquals(HostEvent.PromptOrigin.USER, claude("\"source\":\"user\",", notice).origin());
        assertEquals(HostEvent.PromptOrigin.USER, claude("", notice).origin());
    }

    /** A host that defines no such field has every prompt counted as the user's, even when its input carries a {@code source} of another meaning. */
    @Test
    void otherHostsPromptsAreTheUsersWhateverTheirFieldsSay() {
        String codex = "{\"session_id\":\"s\",\"turn_id\":\"t\",\"source\":\"system\",\"prompt\":\"p\",\"hook_event_name\":\"UserPromptSubmit\"}";
        assertEquals(HostEvent.PromptOrigin.USER,
                assertInstanceOf(HostEvent.UserPromptSubmitted.class, HookAdapters.codex().read("UserPromptSubmit", HookBody.posted(codex.getBytes(StandardCharsets.UTF_8)))).origin());
        String gemini = "{\"session_id\":\"g\",\"cwd\":\"/w\",\"hook_event_name\":\"BeforeAgent\",\"source\":\"system\",\"prompt\":\"p\"}";
        HostEvent.UserPromptSubmitted read = assertInstanceOf(HostEvent.UserPromptSubmitted.class, HookAdapters.gemini().read("BeforeAgent", HookBody.posted(gemini.getBytes(StandardCharsets.UTF_8))));
        assertEquals(HostEvent.PromptOrigin.USER, read.origin());
    }
}
