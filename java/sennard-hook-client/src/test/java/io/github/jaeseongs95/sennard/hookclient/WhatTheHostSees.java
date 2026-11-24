// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.hookclient;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import io.github.jaeseongs95.sennard.hookclient.HookClientCall.FailureAnswer;

/**
 * What a host sees of a hook client run (its exit code, stdout and stderr) and the one statement of what each failure answer must be, shared by the tests of the client and of its
 * entry point so that both say the same thing. Not a test itself.
 */
final class WhatTheHostSees {
    /** The prefix of the reason on stderr: the code of {@link HookNoAnswer.Reason}, then its detail when there is one. */
    static final String FAILED = "Sennard hook failed: ";

    record Result(int exit, String out, String err) {
    }

    private WhatTheHostSees() {
    }

    static Result run(HookClient client, HookClientCall call, byte[] stdin) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = client.run(call, new ByteArrayInputStream(stdin), out, err);
        return new Result(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    /**
     * What the host must see for a failure answer when the server did not answer, for the reason {@code reason} (the text after {@link #FAILED}: a reason's name and its detail).
     * The JSON texts are what each host reads: they are checked to be the exact text of their shape and to be read by the client's strict JSON reader as one object.
     * The switch is complete, so a failure answer that is added does not compile until this says what the host must see of it.
     */
    static void assertFailureAnswer(FailureAnswer answer, Result result, String reason, String situation) throws IOException {
        String where = answer + " / " + situation;
        boolean checked = switch (answer) {
            case FAIL -> {
                assertEquals(1, result.exit(), where);
                assertEquals("", result.out(), where);
                assertEquals(FAILED + reason, result.err(), where);
                yield true;
            }
            case BLOCK_STDERR -> {
                assertEquals(2, result.exit(), where);
                assertEquals("", result.out(), where);
                assertEquals("[Sennard] The Sennard hook could not verify this tool call (" + reason
                        + "), so the tool was not run. Check the Sennard admin page or the server log.", result.err(), where);
                yield true;
            }
            case DENY_PRE_TOOL_USE -> {
                assertEquals(0, result.exit(), where);
                assertEquals("", result.err(), where);
                assertEquals("{\"hookSpecificOutput\":{\"hookEventName\":\"PreToolUse\",\"permissionDecision\":\"deny\","
                        + "\"permissionDecisionReason\":\"[Sennard] The Sennard hook could not verify this tool call (" + reason
                        + "), so the tool was not run. Check the Sennard admin page or the server log.\"}}", result.out(), where);
                assertIsOneJsonObject(result.out(), "hookSpecificOutput", where);
                yield true;
            }
            case GUIDANCE_STDERR -> {
                assertEquals(2, result.exit(), where);
                assertEquals("", result.out(), where);
                assertEquals(HookClient.GUIDANCE, result.err(), where);
                yield true;
            }
            case GUIDANCE_STDOUT -> {
                assertEquals(0, result.exit(), where);
                assertEquals(HookClient.GUIDANCE, result.out(), where);
                assertEquals("", result.err(), where);
                yield true;
            }
            case GUIDANCE_STEPS -> {
                assertEquals(0, result.exit(), where);
                assertEquals("", result.err(), where);
                assertEquals("{\"injectSteps\":[{\"systemMessage\":{\"systemMessage\":\"" + HookClient.GUIDANCE + "\"}}]}", result.out(), where);
                assertIsOneJsonObject(result.out(), "injectSteps", where);
                yield true;
            }
            case ALLOW_DECISION -> {
                assertEquals(0, result.exit(), where);
                assertEquals("", result.err(), where);
                assertEquals("{\"decision\":\"allow\"}", result.out(), where);
                assertIsOneJsonObject(result.out(), "decision", where);
                yield true;
            }
            case DENY_DECISION -> {
                assertEquals(0, result.exit(), where);
                assertEquals("", result.err(), where);
                assertEquals("{\"decision\":\"deny\",\"reason\":\"[Sennard] The Sennard hook could not verify this tool call ("
                        + reason + "), so the tool was not run. Check the Sennard admin page or the server log.\"}", result.out(), where);
                assertIsOneJsonObject(result.out(), "decision\nreason", where);
                yield true;
            }
            case GUIDANCE_SYSTEM_MESSAGE -> {
                assertEquals(0, result.exit(), where);
                assertEquals("", result.err(), where);
                assertEquals("{\"systemMessage\":\"" + HookClient.GUIDANCE + "\"}", result.out(), where);
                assertIsOneJsonObject(result.out(), "systemMessage", where);
                yield true;
            }
        };
        assertEquals(true, checked, where);
    }

    /**
     * What a gate's host must see when the server refused the call with {@code status} (a status of 400 or above) and the call denies a refusal: the one deny both gate hosts read
     * ({@code {"decision":"deny","reason":...}}), exit 0, nothing on stderr, and the status as the refusal's code in the reason.
     */
    static void assertDenied(Result result, int status, String situation) throws IOException {
        assertEquals(0, result.exit(), situation);
        assertEquals("", result.err(), situation);
        assertEquals(true, result.out().startsWith("{\"decision\":\"deny\",\"reason\":\"[Sennard] The Sennard server refused to check this tool call (SERVER_REFUSED " + status + "), so the tool was not run."),
                situation + ": " + result.out());
        // the two members, one per line, are the reader's list of the fields kept: both are there and nothing else is
        assertIsOneJsonObject(result.out(), "decision\nreason", situation);
    }

    /** The strict reader of the client keeps the member whole and writes it compactly again: text that comes back unchanged is one object of valid JSON. */
    private static void assertIsOneJsonObject(String json, String member, String where) throws IOException {
        byte[] again = InputReducer.reduce(InputFields.parse(member), json.getBytes(StandardCharsets.UTF_8));
        assertEquals(json, new String(again, StandardCharsets.UTF_8), where + ": the text is JSON with the member " + member);
    }
}
