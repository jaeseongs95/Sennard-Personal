// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jaeseongs95.sennard.server.hook.HostContext.Basis;
import io.github.jaeseongs95.sennard.server.hook.HostContext.SessionActor;
import io.github.jaeseongs95.sennard.server.mcp.SelfSession;
import tools.jackson.databind.json.JsonMapper;

/**
 * The reader of the model behind a Claude Code tool call, after it was changed to read the transcript through the shared {@code ClaudeTranscriptFile} (the same reader of the
 * lines that {@code ClaudeTranscript} uses). Its behaviour is held by these cases on synthetic files and, on the assembled server, by {@code WorkflowToolsServerTest}
 * ({@code claudeRecordsEffortWhenTheModelComesFromTheTranscript}).
 *
 * <h2>Case table (target, input, expected result, how it is judged)</h2>
 * <pre>
 * e1  the main agent's call   the assistant line that holds the tool_use of the call's id, with a model and an effort -> Observed(model, effort, source "transcript-tool-use")
 * e2  a subagent's call       a call of a child (agent id A): the line is in the file of the subagent, beside the session's (subagents/agent-A.jsonl) -> Observed
 * e3  lines that are not JSON blank lines and half lines around it are skipped; the call is found
 * e4  nothing to read         no transcript path, or no call id -> empty at once (the call stays unobserved)
 * e5  another thread's line   a root's call whose line is a subagent's (isSidechain), or whose session id is another -> not observed (empty after the wait the reader has always had)
 * e6  no model                the line holds the call and has no model -> empty
 * </pre>
 */
class ClaudeTranscriptExecutionReaderTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String SESSION = "s1";

    @TempDir
    Path dir;

    private final ClaudeTranscriptExecutionReader reader = new ClaudeTranscriptExecutionReader(new ClaudeTranscriptFile(MAPPER), Clock.systemUTC());

    private static Map<String, Object> issuer(String callId, String model, Object... extra) {
        Map<String, Object> message = new LinkedHashMap<>();
        if (model != null) {
            message.put("model", model);
        }
        message.put("content", List.of(Map.of("type", "tool_use", "id", callId, "name", "start_workflow", "input", Map.of())));
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("type", "assistant");
        line.put("sessionId", SESSION);
        line.put("isSidechain", false);
        line.put("effort", "high");
        line.put("message", message);
        for (int index = 0; index + 1 < extra.length; index += 2) {
            line.put((String) extra[index], extra[index + 1]);
        }
        return line;
    }

    private Path write(Path file, String... lines) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, String.join("\n", lines) + "\n");
        return file;
    }

    private static SelfSession session(Path transcript, String callId, SessionActor actor) {
        return new SelfSession("ref", 1, "claude-code", SESSION, SelfSession.Provenance.HOST_OBSERVED, actor, null, callId,
                new SelfSession.Execution.Unobserved("none"), transcript == null ? null : transcript.toString());
    }

    private static SessionActor root() {
        return SessionActor.root(Basis.INFERRED, "test");
    }

    @Test
    void theMainAgentsCallIsObservedFromTheLineThatIssuedIt() throws Exception {
        Path transcript = write(dir.resolve(SESSION + ".jsonl"), "{\"type\":\"user\"}", MAPPER.writeValueAsString(issuer("toolu_1", "a-model-on-no-list")));
        SelfSession.Execution read = reader.read(session(transcript, "toolu_1", root()));
        assertEquals(new SelfSession.Execution.Observed("a-model-on-no-list", "high", null, "transcript-tool-use"), read);
        assertEquals("claude-code", reader.host());
    }

    @Test
    void aSubagentsCallIsReadFromTheFileOfTheSubagent() throws Exception {
        Path transcript = write(dir.resolve(SESSION + ".jsonl"), "{\"type\":\"user\"}");
        write(dir.resolve(SESSION).resolve("subagents").resolve("agent-A.jsonl"), MAPPER.writeValueAsString(issuer("toolu_2", "child-model", "isSidechain", true, "agentId", "A")));
        SelfSession.Execution read = reader.read(session(transcript, "toolu_2", SessionActor.child("A", "test")));
        assertEquals(new SelfSession.Execution.Observed("child-model", "high", null, "transcript-tool-use"), read);
    }

    @Test
    void linesThatAreNotJsonAreSkipped() throws Exception {
        Path transcript = write(dir.resolve(SESSION + ".jsonl"), "", "{\"type\":\"assistant\",\"toolu_3 half", MAPPER.writeValueAsString(issuer("toolu_3", "m")), "garbage toolu_3");
        assertInstanceOf(SelfSession.Execution.Observed.class, reader.read(session(transcript, "toolu_3", root())));
    }

    /** A clock that only moves when the reader pauses, so that the five seconds of waiting pass without any time passing. */
    private static final class SteppedClock extends Clock {
        private long millis;
        private int pauses;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }

        void advance(Duration by) {
            pauses++;
            millis += by.toMillis();
        }
    }

    private SteppedClock stepped;

    private ClaudeTranscriptExecutionReader steppedReader() {
        stepped = new SteppedClock();
        return new ClaudeTranscriptExecutionReader(new ClaudeTranscriptFile(MAPPER), stepped, stepped::advance, Duration.ofSeconds(5));
    }

    private SelfSession.Execution.Unobserved unobserved(SelfSession session) {
        return assertInstanceOf(SelfSession.Execution.Unobserved.class, steppedReader().read(session));
    }

    private static void assertReason(String reason, long waitedMs, int lines, SelfSession.Execution.Unobserved unobserved) {
        assertEquals("none", unobserved.reason(), "the reason the hook gave stays");
        assertEquals(Map.of("transcriptReason", reason, "waitedMs", waitedMs, "linesSeen", lines), unobserved.facts());
    }

    @Test
    void withoutATranscriptPathOrACallIdNothingIsReadAtOnce() throws Exception {
        Path transcript = write(dir.resolve(SESSION + ".jsonl"), MAPPER.writeValueAsString(issuer("toolu_4", "m")));
        assertReason("TRANSCRIPT_PATH_MISSING", 0, 0, unobserved(session(null, "toolu_4", root())));
        assertReason("CALL_ID_MISSING", 0, 0, unobserved(session(transcript, null, root())));
        assertEquals(0, stepped.pauses, "nothing was waited for");
    }

    @Test
    void aTranscriptThatIsNotThereIsUnreadableAfterTheFullWait() {
        SelfSession.Execution.Unobserved unobserved = unobserved(session(dir.resolve("absent.jsonl"), "toolu_7", root()));
        assertReason("TRANSCRIPT_UNREADABLE", 5000, 0, unobserved);
        assertEquals(50, stepped.pauses, "polled every 100 ms for the five seconds the reader has always waited");
    }

    @Test
    void aTranscriptWithoutTheCallsLineIsCallLineNotFound() throws Exception {
        Path transcript = write(dir.resolve("other.jsonl"), "{\"type\":\"user\"}", MAPPER.writeValueAsString(issuer("toolu_other", "m")));
        assertReason("CALL_LINE_NOT_FOUND", 5000, 2, unobserved(session(transcript, "toolu_8", root())));
    }

    @Test
    void aLineOfAnotherThreadIsOtherThread() throws Exception {
        Path sidechain = write(dir.resolve("side.jsonl"), MAPPER.writeValueAsString(issuer("toolu_5", "m", "isSidechain", true)));
        assertReason("OTHER_THREAD", 5000, 1, unobserved(session(sidechain, "toolu_5", root())));
        Path otherSession = write(dir.resolve("other-session.jsonl"), MAPPER.writeValueAsString(issuer("toolu_9", "m", "sessionId", "another")));
        assertReason("OTHER_THREAD", 5000, 1, unobserved(session(otherSession, "toolu_9", root())));
    }

    @Test
    void aLineWithoutAModelIsModelFieldMissing() throws Exception {
        Path noModel = write(dir.resolve("none.jsonl"), MAPPER.writeValueAsString(issuer("toolu_6", null)));
        assertReason("MODEL_FIELD_MISSING", 5000, 1, unobserved(session(noModel, "toolu_6", root())));
    }

    @Test
    void aLineThatAppearsDuringTheWaitIsFoundWithoutWaitingOutTheRest() throws Exception {
        Path transcript = write(dir.resolve(SESSION + ".jsonl"), "{\"type\":\"user\"}");
        SteppedClock clock = new SteppedClock();
        ClaudeTranscriptExecutionReader late = new ClaudeTranscriptExecutionReader(new ClaudeTranscriptFile(MAPPER), clock, duration -> {
            clock.advance(duration);
            if (clock.pauses == 3) {
                try {
                    write(transcript, "{\"type\":\"user\"}", MAPPER.writeValueAsString(issuer("toolu_10", "late-model")));
                } catch (IOException failed) {
                    throw new java.io.UncheckedIOException(failed);
                }
            }
        }, Duration.ofSeconds(5));
        assertEquals(new SelfSession.Execution.Observed("late-model", "high", null, "transcript-tool-use"), late.read(session(transcript, "toolu_10", root())));
        assertEquals(3, clock.pauses);
    }
}
