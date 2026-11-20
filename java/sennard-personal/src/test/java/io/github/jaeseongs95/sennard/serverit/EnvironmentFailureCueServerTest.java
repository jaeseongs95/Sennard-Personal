// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.UUID;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.node.ObjectNode;

/**
 * AGS3-D-0169: the run check after a tool is said only for a call the host reported as failed. The words of a missing program, path or permission in the result of a call the host
 * reported as successful are what the call printed or read, and say nothing about its run. Each case is the recorded hook of the host with the tool's result changed, posted to the
 * assembled server.
 */
@Tag("business-db")
class EnvironmentFailureCueServerTest {
    private static final String CUE = "[Sennard run check]";
    private static RunningServer server;

    @BeforeAll
    static void start() throws Exception {
        server = SessionServerSupport.started(SessionServerSupport.sources());
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    private static String say(String event, String fixture, String host, String session, Consumer<ObjectNode> edit) throws IOException {
        RawHttp.Response response = DomainServerSupport.hookFrom(fixture, server.port, "windows", host, event, null, session, edit);
        assertEquals(200, response.status(), DomainServerSupport.text(response));
        return DomainServerSupport.text(response);
    }

    private static String printed() {
        return "ls: cannot access 'x-" + UUID.randomUUID() + "': No such file or directory (ENOENT); Permission denied";
    }

    @Test
    void aClaudeShellCallTheHostReportedAsSuccessfulGetsNoRunCheckWhateverItPrinted() throws IOException {
        String text = printed();
        String answer = say("PostToolUse", "PostToolUse", "claude", SessionServerSupport.uniqueSession(), body -> {
            body.put("tool_name", "Bash");
            body.putObject("tool_input").put("command", "grep ENOENT notes.txt");
            body.putObject("tool_response").put("stdout", text).put("stderr", "");
        });
        assertFalse(answer.contains(CUE), answer);
    }

    @Test
    void aClaudeCallTheHostReportedAsFailedStillGetsTheRunCheckOnceForTheSameFailure() throws IOException {
        String text = printed();
        String session = SessionServerSupport.uniqueSession();
        Consumer<ObjectNode> failed = body -> {
            body.remove("tool_response");
            body.put("tool_name", "Bash");
            body.putObject("tool_input").put("command", "cat missing.txt");
            body.put("error", text);
        };
        assertTrue(say("PostToolUseFailure", "PostToolUse", "claude", session, failed).contains(CUE));
        assertFalse(say("PostToolUseFailure", "PostToolUse", "claude", session, failed).contains(CUE), "once per distinct failure");
    }

    @Test
    void aCodexMcpResultWithIsErrorFalseGetsNoRunCheckAndWithIsErrorTrueGetsIt() throws IOException {
        String text = printed();
        String ok = say("PostToolUse", "PostToolUse", "codex", SessionServerSupport.uniqueSession(), body -> {
            body.putObject("tool_response").put("isError", false).putArray("content").addObject().put("type", "text").put("text", text);
        });
        assertFalse(ok.contains(CUE), ok);

        String failed = say("PostToolUse", "PostToolUse", "codex", SessionServerSupport.uniqueSession(), body -> {
            body.putObject("tool_response").put("isError", true).putArray("content").addObject().put("type", "text").put("text", text);
        });
        assertTrue(failed.contains(CUE), failed);
    }

    /** This host's PostToolUse carries no tool output, so there is nothing to tell a missing program or path from: it says nothing, reported failure or not. */
    @Test
    void anAgyToolEndHasNoOutputToJudgeAndSaysNothingEitherWay() throws IOException {
        String text = printed();
        assertFalse(say("PostToolUse", "PostToolUse", "agy", SessionServerSupport.uniqueSession(), body -> body.put("error", "")).contains(CUE));
        assertFalse(say("PostToolUse", "PostToolUse", "agy", SessionServerSupport.uniqueSession(), body -> body.put("error", text)).contains(CUE));
    }
}
