// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import org.junit.jupiter.params.provider.Arguments;

import tools.jackson.databind.JsonNode;

/**
 * Sites where the answer would change under another regular expression engine or another collation: the pattern anchor {@code $} must match only at the very end
 * (while {@code java.util.regex} also matches before a final line terminator), the role name limit counts code points, and blank is what {@link String#isBlank()} says
 * (the rule of {@code MessageRules.body}, not a copy of a JavaScript trim).
 */
@Tag("business-db")
class ConformanceLocaleRegexServerTest {
    private static final String ONE = "1.0.0";
    private static final String DIGEST = "sha256:" + "a".repeat(64);
    private static final String EMOJI = "😀";

    private static ToolServerSupport sennard;

    @BeforeAll
    static void start() throws Exception {
        sennard = ToolServerSupport.start();
    }

    @AfterAll
    static void stop() {
        sennard.close();
    }

    private static Map<String, Object> args(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("schemaVersion", ONE);
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private static void assertAnswer(JsonNode answer, boolean ok) {
        assertEquals(ok, answer.path("ok").asBoolean(), answer.toString());
        if (!ok) {
            assertEquals("INVALID_INPUT", answer.path("error").path("code").asString(), answer.toString());
        }
    }

    static List<Arguments> digests() {
        return List.of(Arguments.of("lowercase-hex-accepted", DIGEST, true), Arguments.of("trailing-lf-rejected", DIGEST + "\n", false), Arguments.of("trailing-crlf-rejected", DIGEST + "\r\n", false),
                Arguments.of("uppercase-hex-rejected", "sha256:" + "A".repeat(64), false), Arguments.of("fullwidth-digit-rejected", "sha256:" + "０".repeat(64), false), Arguments.of("short-rejected", "sha256:" + "a".repeat(63), false));
    }

    @ParameterizedTest(name = "pattern.digest.{0}")
    @MethodSource("digests")
    void anEvidenceDigestPattern(String id, String digest, boolean ok) throws Exception {
        String session = sennard.session();
        JsonNode loaded = sennard.call("load_context", args(), session);
        String token = loaded.path("data").path("stateToken").asString();
        JsonNode answer = sennard.call("checkpoint_context", args("operationKey", "regex-" + UUID.randomUUID(), "expectedStateToken", token, "status", "ACTIVE",
                "core", Map.of("objective", "regex", "completionCriteria", List.of(), "constraints", List.of(), "decisions", List.of(), "progress", List.of(), "blockers", List.of(), "nextActions", List.of()),
                "evidenceRefs", List.of(Map.of("artifactId", "a", "locator", "loc", "digest", digest, "verified", true))), session);
        assertAnswer(answer, ok);
    }

    private static Map<String, Object> frameCall(String digest, String workspace) {
        Map<String, Object> task = args("taskId", "pattern-task", "objective", "x", "scope", Map.of("included", List.of("f"), "excluded", List.of()), "acceptanceCriteria", List.of("a"), "riskLevel", "low",
                "workUnits", List.of(Map.of("id", "u", "objective", "o", "dependencies", List.of(), "writeTargets", List.of("f.md"))), "requiredCapabilities", List.of("task-decomposition"), "constraints", List.of("n"),
                "authorization", Map.of("allowedActions", List.of("t"), "prohibitedActions", List.of("d"), "approvalRequired", List.of()), "decision", Map.of("complexity", "simple", "hasConflicts", false),
                "orchestration", Map.of("requested", true, "mcpAvailable", true));
        Map<String, Object> frame = args("workspace", Map.of("workspaceId", "w", "locator", workspace), "controlArtifacts", List.of(Map.of("artifactId", "c", "role", "pass-condition", "locator", "c.md", "digest", digest)),
                "targetArtifacts", List.of(Map.of("artifactId", "t", "role", "target", "locator", "f.md", "digest", DIGEST)), "operationalSettings", Map.of("maxAttemptsPerEpoch", 3, "maxEpochs", 2, "leaseTtlSeconds", 3600));
        Map<String, Object> call = args("parentRootId", null, "taskEnvelope", task, "frame", frame, "userApprovalRefs", List.of());
        return call;
    }

    @Test
    void aFrameDigestPattern(@TempDir Path workspace, @TempDir Path other) throws Exception {
        assertAnswer(sennard.call("open_convergence_root", frameCall(DIGEST, workspace.toString()), sennard.session()), true);
        assertAnswer(sennard.call("open_convergence_root", frameCall(DIGEST + "\n", other.toString()), sennard.session()), false);
    }

    @ParameterizedTest(name = "length.role-name.{0}")
    @CsvSource({"100-astral-code-points-are-well-formed,100,ROLE_NOT_REGISTERED", "101-astral-code-points-rejected,101,ROLE_NAME_INVALID"})
    void aRoleNameIsCountedInCodePoints(String id, int count, String reason) throws Exception {
        JsonNode answer = sennard.call("bind_session_role", args("role", EMOJI.repeat(count)), sennard.session());
        assertAnswer(answer, false);
        assertTrue(answer.path("error").toString().contains(reason), "the refusal names why: " + answer);
    }

    /** Blank is what String.isBlank says (Java whitespace): the no-break space and the byte order mark are bodies, the four information separators are blank. */
    static List<Arguments> bodies() {
        return List.of(Arguments.of("nbsp-only-is-a-body", " ", false), Arguments.of("ideographic-space-only-is-blank", "　", true), Arguments.of("bom-only-is-a-body", "﻿", false),
                Arguments.of("line-separator-only-is-blank", " ", true), Arguments.of("information-separators-only-are-blank", "\u001C\u001D\u001E\u001F", true),
                Arguments.of("zero-width-space-is-not-blank", "​", false), Arguments.of("next-line-control-is-not-blank", "\u0085", false));
    }

    @ParameterizedTest(name = "trim.message-body.{0}")
    @MethodSource("bodies")
    void aBlankMessageBodyIsWhatStringIsBlankSaysIsBlank(String id, String body, boolean blank) throws Exception {
        // A live target, so that "not blank" is an accepted body.
        JsonNode answer = sennard.call("prepare_session_message", args("targetHost", "codex", "targetSessionId", sennard.session(), "body", body), sennard.session());
        assertEquals(!blank, answer.path("ok").asBoolean(), answer.toString());
        if (blank) {
            assertEquals("INVALID_INPUT", answer.path("error").path("code").asString(), answer.toString());
            assertTrue(answer.path("error").toString().toLowerCase().contains("body"), answer.toString());
        }
    }

    @Test
    void rolesAreOrderedByTheHostLocaleAndNotByUtf16() throws Exception {
        ToolServerSupport.Passkey owner = sennard.owner();
        List<String> names = new ArrayList<>(List.of("zulu", "Alpha", "가나", "étude"));
        for (String name : names) {
            sennard.sign(owner, ToolServerSupport.roleAdd("role-" + UUID.randomUUID(), name));
        }
        List<String> shown = new ArrayList<>();
        sennard.state().path("roles").forEach(role -> shown.add(role.path("name").asString()));
        shown.retainAll(names);

        Collator collator = Collator.getInstance(Locale.getDefault());
        names.sort(collator::compare);
        assertEquals(names, shown, "ordered by " + Locale.getDefault());
        List<String> utf16 = new ArrayList<>(names);
        utf16.sort(Comparator.naturalOrder());
        assertNotEquals(utf16, shown, "the set tells collation from UTF-16 order");
        assertFalse(shown.isEmpty());
    }
}
