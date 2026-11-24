// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
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
 * The reader of the model behind a Claude Code tool call. It reads the transcript through the shared {@code ClaudeTranscriptFile} (the same reader of the lines that
 * {@code ClaudeTranscript} uses), once and without waiting. Its behaviour is held by these cases on synthetic files and, on the assembled server, by {@code WorkflowToolsServerTest}
 * ({@code claudeRecordsEffortWhenTheModelComesFromTheTranscript}, {@code aParallelCallIsObservedFromTheRequestRecord}).
 *
 * <h2>Case table (target, input, expected result, how it is judged)</h2>
 * <pre>
 * e1  the main agent's call   the assistant line that holds the tool_use of the call's id, with a model and an effort -> Observed(model, effort, evidence CALL_RECORD)
 * e2  a subagent's call       a call of a child (agent id A): the line is in the file of the subagent, beside the session's (subagents/agent-A.jsonl) -> Observed
 * e3  lines that are not JSON blank lines and half lines around it are skipped; the call is found
 * e4  nothing to read         no transcript path, or no call id -> not observed at once
 * e5  another thread's line   a root's call whose line is a subagent's (isSidechain), or whose session id is another -> not observed
 * e6  no model                the line holds the call and has no model -> not observed
 * r1  parallel call           the line of the call is not written (the message holds several tool calls), the request record of the main thread is -> Observed(request model, effort, REQUEST_RECORD)
 * r2  both records            the line of the call and a request record name different models -> the line of the call wins
 * r3  not this thread's       the request records are of another session, or of another thread -> not observed; another thread's newer record does not displace the main thread's
 * r4  two requests            A then B (with the answer to A between) -> B; the model of the answer is never used
 * r5  no record at all        no line of the call and no request record -> REQUEST_RECORD_NOT_FOUND at once
 * r6  thread unknown          a request record that does not say its thread (or cannot be read) -> not used, and no older request in its place
 * r7  a subagent's call       a child whose line is not in the files: the main thread's request records are another thread's and are not used (REQUEST_RECORD_NOT_FOUND)
 * r8  effort                  a request without an effort takes the hook's effort; one with an effort keeps it
 * s1  a new input after it    the chosen request is followed by the user's new input of the same thread -> REQUEST_STALE
 * s2  not stale               after the chosen request: a line the host injected (isMeta), a subagent's input and tool result, a result of a call issued before it -> Observed
 * s3  an earlier tool's result the first tool of the request's message has finished and its result line is written, the call's own line is not -> Observed (the original case of the defect)
 * u1  auxiliary requests      a request whose querySource is neither a main thread's nor a subagent's (compact, agent_summary, generate_session_title, auto_mode ...) is passed over: the caller's own request before it is taken
 * u1b only auxiliary          no request of a main thread or a subagent in the lines read, only auxiliary ones -> REQUEST_ONLY_UNRECOGNIZED_SOURCES with the newest source (cut and cleaned); with a request of the other kind of thread present -> REQUEST_RECORD_NOT_FOUND
 * u1c main sources           repl_main_thread, repl_main_thread:outputStyle:x and sdk are the main thread's; agent:custom:x and hook_agent are a subagent's
 * u2  main source, other      a request of the main thread that carries an agentId or isSidechain true -> REQUEST_THREAD_MISMATCH
 * w1  workflow subagent       a child whose file is subagents/workflows/&lt;runId&gt;/agent-A.jsonl (one level of run folders under subagents/workflows, no deeper) -> Observed
 * c1  a child's request       a child (agent id A) takes the latest agent: request that names A, in the subagent's file -> Observed (evidence REQUEST_RECORD)
 * c2  other agents            a request of another agent id is passed over; one without an agentId -> REQUEST_THREAD_MISMATCH; the main thread's requests are another thread's
 * c3  a child's new input     the child's request followed by a new input of the child -> REQUEST_STALE
 * h1  the hook's effort       the effort the hook gave is the effort; a request that names another is REQUEST_EFFORT_MISMATCH (also for a child, a forged line, a long line); none or the same takes the hook's
 * t1  the second look         no line of the call -> one pause of REREAD_DELAY, then the file again: the line of the call written meanwhile wins, else the newest request (also one written meanwhile); a line of the call in any one of the files (a subagent's own, a run's) at the first look, whatever it says, or no file that can be read: no pause; no line in any file: one pause whatever the number of files; an interrupted pause ends the waiting
 * n1  a new turn              a notice of a background task (also when isMeta), a compaction (logicalParentUuid), a line of another promptId -> REQUEST_STALE; an isMeta reminder, the same promptId, another thread's line -> not
 * l1  a long line             a request line (and a call line) longer than the end read whole, alone or followed by lines, a child's too, is read by its digest; longer than 32 MiB -> REQUEST_LINE_TOO_LARGE; older than the lines read -> REQUEST_BEYOND_READ_LIMIT
 * p1  a half-written line     only the last line that cannot be read ends the look (also when blank lines follow); one in the middle is passed over; api-request-resumed is no request
 * f1  runs not listable       the folder of the workflow runs cannot be listed -> WORKFLOW_RUNS_UNLISTABLE, unless the subagent's own file holds the request
 * i1  ids                     an id that is not [A-Za-z0-9_-]+ (a colon, a slash, a dot-dot, empty, a NUL) -> CALLER_ID_INVALID without a file named after it; a path that is not a path -> TRANSCRIPT_PATH_INVALID
 * </pre>
 *
 * <p>Not covered, by decision:a request record that is not yet written when the call arrives. Nothing in what the host writes (the documented fields, the records measured, these
 * fixtures) ties the call's {@code tool_use} id to the request that produced it, so the newest request of the thread stays the candidate (the class comment of the reader says so).
 */
class ClaudeTranscriptExecutionReaderTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String SESSION = "s1";
    private static final SelfSession.Execution.Observed.Evidence REQUEST_RECORD = SelfSession.Execution.Observed.Evidence.REQUEST_RECORD;
    private static final SelfSession.Execution.Observed.Evidence CALL_RECORD = SelfSession.Execution.Observed.Evidence.CALL_RECORD;

    @TempDir
    Path dir;

    /** What the reader does instead of waiting: it is told how long it was asked to wait, and then does what a test says the host did meanwhile. */
    private final List<Duration> pauses = new ArrayList<>();
    private IoAction whilePaused = () -> {
    };

    @FunctionalInterface
    private interface IoAction {
        void run() throws IOException;
    }

    private final ClaudeTranscriptExecutionReader reader = new ClaudeTranscriptExecutionReader(new ClaudeTranscriptFile(MAPPER), duration -> {
        pauses.add(duration);
        try {
            whilePaused.run();
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    });

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
        line.put("message", message);
        return with(line, extra);
    }

    /**
     * A request record with the fields the reader relies on and the host facts of Claude Code 2.1.330 confirm: {@code type}, {@code querySource} (the thread that sends it), {@code sessionId},
     * {@code timestamp}, and the {@code params} the request is sent with ({@code model}, {@code output_config.effort}). A subagent's adds {@code agentId} and {@code isSidechain}. The real
     * line holds more (the request's body); fields the facts do not give are not made up here.
     */
    private static Map<String, Object> request(String model, String effort, Object... extra) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (model != null) {
            params.put("model", model);
        }
        if (effort != null) {
            params.put("output_config", Map.of("effort", effort));
        }
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("type", "api-request");
        line.put("querySource", "repl_main_thread");
        line.put("params", params);
        line.put("sessionId", SESSION);
        line.put("timestamp", "2026-01-01T00:00:00.000Z");
        return with(line, extra);
    }

    /** An assistant line of a message that holds another tool call than the one asked for: the model that answered. */
    private static Map<String, Object> answer(String model) {
        return issuer("toolu_not_the_call", model);
    }

    private static Map<String, Object> with(Map<String, Object> line, Object... extra) {
        for (int index = 0; index + 1 < extra.length; index += 2) {
            if (extra[index + 1] == null) {
                line.remove((String) extra[index]);
            } else {
                line.put((String) extra[index], extra[index + 1]);
            }
        }
        return line;
    }

    private static String json(Map<String, Object> line) throws IOException {
        return MAPPER.writeValueAsString(line);
    }

    private Path write(Path file, String... lines) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, String.join("\n", lines) + "\n");
        return file;
    }

    private Path transcript(String... lines) throws IOException {
        return write(dir.resolve(SESSION + ".jsonl"), lines);
    }

    private static SelfSession session(Path transcript, String callId, SessionActor actor) {
        return session(transcript, callId, actor, new SelfSession.Execution.Unobserved("none"));
    }

    private static SelfSession session(Path transcript, String callId, SessionActor actor, SelfSession.Execution execution) {
        return new SelfSession("ref", 1, "claude-code", SESSION, SelfSession.Provenance.HOST_OBSERVED, actor, null, callId, execution, transcript == null ? null : transcript.toString());
    }

    private static SessionActor root() {
        return SessionActor.root(Basis.INFERRED, "test");
    }

    @Test
    void theMainAgentsCallIsObservedFromTheLineThatIssuedIt() throws Exception {
        Path transcript = write(dir.resolve(SESSION + ".jsonl"), "{\"type\":\"user\"}", MAPPER.writeValueAsString(issuer("toolu_1", "a-model-on-no-list")));
        SelfSession.Execution read = reader.read(session(transcript, "toolu_1", root()));
        assertEquals(new SelfSession.Execution.Observed("a-model-on-no-list", null, null, CALL_RECORD), read);
        assertEquals(List.of(), pauses, "a call whose line is in the file at the first look waits for nothing");
        assertEquals("claude-code", reader.host());
    }

    @Test
    void aSubagentsCallIsReadFromTheFileOfTheSubagent() throws Exception {
        Path transcript = write(dir.resolve(SESSION + ".jsonl"), "{\"type\":\"user\"}");
        write(dir.resolve(SESSION).resolve("subagents").resolve("agent-A.jsonl"), MAPPER.writeValueAsString(issuer("toolu_2", "child-model", "isSidechain", true, "agentId", "A")));
        SelfSession.Execution read = reader.read(session(transcript, "toolu_2", SessionActor.child("A", "test")));
        assertEquals(new SelfSession.Execution.Observed("child-model", null, null, CALL_RECORD), read);
        assertEquals(List.of(), pauses, "the line of the call is in the subagent's file: nothing is waited for, though the session's own file has no line of it");
    }

    @Test
    void aSubagentsCallWhoseLineIsInItsFileWaitsForNothingWhateverItSays() throws Exception {
        Path transcript = write(dir.resolve(SESSION + ".jsonl"), "{\"type\":\"user\"}");
        Path own = dir.resolve(SESSION).resolve("subagents").resolve("agent-A.jsonl");
        write(own, MAPPER.writeValueAsString(issuer("toolu_sub", null, "isSidechain", true, "agentId", "A")));
        SelfSession.Execution.Unobserved noModel = unobserved(session(transcript, "toolu_sub", SessionActor.child("A", "test")));
        assertEquals(Map.of("transcriptReason", "MODEL_FIELD_MISSING", "waitedMs", 0L, "linesSeen", 2), noModel.facts());
        write(own, MAPPER.writeValueAsString(issuer("toolu_sub", "m", "isSidechain", true, "agentId", "B")));
        assertEquals("OTHER_THREAD", unobserved(session(transcript, "toolu_sub", SessionActor.child("A", "test"))).facts().get("transcriptReason"));
        write(dir.resolve(SESSION).resolve("subagents").resolve("workflows").resolve("run-9").resolve("agent-A.jsonl"),
                MAPPER.writeValueAsString(issuer("toolu_sub", "wf-model", "isSidechain", true, "agentId", "A")));
        write(own, "{\"type\":\"user\"}");
        assertEquals(new SelfSession.Execution.Observed("wf-model", null, null, CALL_RECORD), reader.read(session(transcript, "toolu_sub", SessionActor.child("A", "test"))));
        assertEquals(List.of(), pauses, "a line of the call in any one of the files ends the look without waiting");
    }

    @Test
    void linesThatAreNotJsonAreSkipped() throws Exception {
        Path transcript = write(dir.resolve(SESSION + ".jsonl"), "", "{\"type\":\"assistant\",\"toolu_3 half", MAPPER.writeValueAsString(issuer("toolu_3", "m")), "garbage toolu_3");
        assertInstanceOf(SelfSession.Execution.Observed.class, reader.read(session(transcript, "toolu_3", root())));
    }

    private SelfSession.Execution.Unobserved unobserved(SelfSession session) {
        return assertInstanceOf(SelfSession.Execution.Unobserved.class, reader.read(session));
    }

    /** Reasons the look ends on without reading again: the call line is there in some file (it says something else), or there is no file to read (nothing says one is being made). */
    private static final List<String> WITHOUT_WAITING = List.of("MODEL_FIELD_MISSING", "OTHER_THREAD", "TRANSCRIPT_UNREADABLE", "TRANSCRIPT_PATH_MISSING", "TRANSCRIPT_PATH_INVALID", "CALLER_ID_INVALID", "CALL_ID_MISSING");

    private static void assertReason(String reason, int lines, SelfSession.Execution.Unobserved unobserved) {
        long waited = WITHOUT_WAITING.contains(reason) ? 0 : ClaudeTranscriptExecutionReader.REREAD_DELAY.toMillis();
        assertEquals("none", unobserved.reason(), "the reason the hook gave stays");
        assertEquals(Map.of("transcriptReason", reason, "waitedMs", waited, "linesSeen", lines), unobserved.facts());
    }

    @Test
    void withoutATranscriptPathOrACallIdNothingIsReadAtOnce() throws Exception {
        Path transcript = write(dir.resolve(SESSION + ".jsonl"), MAPPER.writeValueAsString(issuer("toolu_4", "m")));
        assertReason("TRANSCRIPT_PATH_MISSING", 0, unobserved(session(null, "toolu_4", root())));
        assertReason("CALL_ID_MISSING", 0, unobserved(session(transcript, null, root())));
    }

    @Test
    void aTranscriptThatIsNotThereIsUnreadable() {
        assertReason("TRANSCRIPT_UNREADABLE", 0, unobserved(session(dir.resolve("absent.jsonl"), "toolu_7", root())));
    }

    @Test
    void aLineOfAnotherThreadIsOtherThread() throws Exception {
        Path sidechain = write(dir.resolve("side.jsonl"), MAPPER.writeValueAsString(issuer("toolu_5", "m", "isSidechain", true)));
        assertReason("OTHER_THREAD", 1, unobserved(session(sidechain, "toolu_5", root())));
        Path otherSession = write(dir.resolve("other-session.jsonl"), MAPPER.writeValueAsString(issuer("toolu_9", "m", "sessionId", "another")));
        assertReason("OTHER_THREAD", 1, unobserved(session(otherSession, "toolu_9", root())));
    }

    @Test
    void aLineWithoutAModelIsModelFieldMissing() throws Exception {
        Path noModel = write(dir.resolve("none.jsonl"), MAPPER.writeValueAsString(issuer("toolu_6", null)));
        assertReason("MODEL_FIELD_MISSING", 1, unobserved(session(noModel, "toolu_6", root())));
    }

    // ---- r1: the line of the call is not written yet

    @Test
    void aParallelCallIsObservedFromTheRequestRecordWhenItsLineIsNotWritten() throws Exception {
        // The message of the call holds three blocks; the host writes its blocks 0 and 2 and the call's block when the tools have finished, so the call's line is not in the file.
        Path transcript = transcript("{\"type\":\"user\"}", json(request("request-model", "high")),
                json(answer("answer-model-0")), json(answer("answer-model-2")));
        SelfSession.Execution read = reader.read(session(transcript, "toolu_parallel", root()));
        assertEquals(new SelfSession.Execution.Observed("request-model", "high", null, REQUEST_RECORD), read);
    }

    @Test
    void theLineOfTheCallWinsOverTheRequestRecord() throws Exception {
        Path transcript = transcript(json(request("request-model", "xhigh")), json(issuer("toolu_both", "line-model")));
        SelfSession.Execution read = reader.read(session(transcript, "toolu_both", root()));
        assertEquals(new SelfSession.Execution.Observed("line-model", null, null, CALL_RECORD), read);
    }

    // ---- r3: only this thread's, this session's request is taken

    @Test
    void aRequestOfAnotherSessionOrAnotherThreadIsNotUsed() throws Exception {
        Path otherSession = write(dir.resolve("a.jsonl"), json(request("m", "high", "sessionId", "another")));
        assertReason("REQUEST_SESSION_MISMATCH", 1, unobserved(session(otherSession, "toolu_x", root())));
        Path noSession = write(dir.resolve("b.jsonl"), json(request("m", "high", "sessionId", null)));
        assertReason("REQUEST_SESSION_MISMATCH", 1, unobserved(session(noSession, "toolu_x", root())));
        Path subagentOnly = write(dir.resolve("c.jsonl"), json(request("m", "high", "querySource", "agent:builtin:general-purpose")));
        assertReason("REQUEST_RECORD_NOT_FOUND", 1, unobserved(session(subagentOnly, "toolu_x", root())));
    }

    @Test
    void aNewerRequestOfAnotherThreadDoesNotDisplaceTheMainThreadsRequest() throws Exception {
        Path transcript = transcript(json(request("main-model", "high")), json(request("side-model", "low", "querySource", "agent:builtin:general-purpose")));
        assertEquals(new SelfSession.Execution.Observed("main-model", "high", null, REQUEST_RECORD), reader.read(session(transcript, "toolu_x", root())));
    }

    // ---- r4: the latest request, never an answer's model

    @Test
    void theLatestRequestIsTheOneAndTheModelOfAnEarlierAnswerIsNeverUsed() throws Exception {
        Path transcript = transcript(json(request("model-A", "low")), json(answer("answer-to-A")), "{\"type\":\"user\"}", json(request("model-B", "medium")));
        assertEquals(new SelfSession.Execution.Observed("model-B", "medium", null, REQUEST_RECORD), reader.read(session(transcript, "toolu_x", root())));
    }

    @Test
    void anAnswerWithoutARequestRecordIsNotTakenForTheRequest() throws Exception {
        Path transcript = transcript(json(answer("answer-to-A")));
        assertReason("REQUEST_RECORD_NOT_FOUND", 1, unobserved(session(transcript, "toolu_x", root())));
    }

    // ---- r5

    @Test
    void withNeitherTheLineNorARequestRecordTheCallIsNotObservedAtOnce() throws Exception {
        Path transcript = transcript("{\"type\":\"user\"}", json(issuer("toolu_other", "m")));
        assertReason("REQUEST_RECORD_NOT_FOUND", 2, unobserved(session(transcript, "toolu_x", root())));
    }

    // ---- r6

    @Test
    void aRequestThatDoesNotSayWhichThreadSentItIsNotUsedAndNoOlderRequestTakesItsPlace() throws Exception {
        Path transcript = transcript(json(request("model-A", "low")), json(request("model-B", "low", "querySource", null)));
        assertReason("REQUEST_THREAD_UNKNOWN", 2, unobserved(session(transcript, "toolu_x", root())));
        Path blankSource = write(dir.resolve("blank.jsonl"), json(request("model-C", "low", "querySource", " ")));
        assertReason("REQUEST_THREAD_UNKNOWN", 1, unobserved(session(blankSource, "toolu_x", root())));
        Path cannotBeRead = write(dir.resolve("cut.jsonl"), json(request("model-D", "low")), "{\"type\":\"api-request\",\"querySource\":\"repl_main");
        assertReason("REQUEST_THREAD_UNKNOWN", 2, unobserved(session(cannotBeRead, "toolu_x", root())));
    }

    @Test
    void aRequestWithoutAModelIsNotUsedAndNoOlderRequestTakesItsPlace() throws Exception {
        Path transcript = transcript(json(request("model-A", "low")), json(request(null, "low")));
        assertReason("REQUEST_MODEL_MISSING", 2, unobserved(session(transcript, "toolu_x", root())));
    }

    // ---- r7

    @Test
    void aSubagentsCallIsNotObservedFromTheMainThreadsRequestRecords() throws Exception {
        Path transcript = transcript(json(request("main-model", "high")));
        assertReason("REQUEST_RECORD_NOT_FOUND", 1, unobserved(session(transcript, "toolu_x", SessionActor.child("A", "test"))));
    }

    // ---- s1, s2: the request is the one that produced the call only while its answer has not ended

    private static Map<String, Object> userInput(Object... extra) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("type", "user");
        line.put("sessionId", SESSION);
        line.put("message", Map.of("role", "user", "content", "the next thing the user typed"));
        return with(line, extra);
    }

    private static Map<String, Object> toolResult(String callId, Object... extra) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("type", "user");
        line.put("sessionId", SESSION);
        line.put("message", Map.of("role", "user", "content", List.of(Map.of("type", "tool_result", "tool_use_id", callId, "content", "done"))));
        return with(line, extra);
    }

    @Test
    void aRequestFollowedByTheUsersNewInputIsStale() throws Exception {
        Path transcript = transcript(json(request("model-A", "high")), json(answer("answer-A")), json(userInput()));
        assertReason("REQUEST_STALE", 3, unobserved(session(transcript, "toolu_x", root())));
    }

    /** The result of a tool that came earlier in the same message is written before the later tool's call line: this is the parallel call the defect is about, so it is not a sign of an ended answer. */
    @Test
    void aRequestFollowedByTheResultOfAnEarlierToolOfItsMessageIsStillTaken() throws Exception {
        Path transcript = transcript(json(request("model-A", "high")), json(issuer("toolu_sibling", "answer-A")), json(toolResult("toolu_sibling")));
        assertEquals(new SelfSession.Execution.Observed("model-A", "high", null, REQUEST_RECORD), reader.read(session(transcript, "toolu_x", root())));
    }

    @Test
    void aRequestIsNotStaleForLinesThatDoNotEndItsAnswer() throws Exception {
        Path transcript = transcript(json(issuer("toolu_before", "older-answer")), json(request("model-A", "high")),
                json(userInput("isMeta", true)),
                json(userInput("isSidechain", true, "agentId", "A")),
                json(issuer("toolu_sub", "sub-model", "isSidechain", true, "agentId", "A")), json(toolResult("toolu_sub", "isSidechain", true, "agentId", "A")),
                json(toolResult("toolu_before")), json(answer("answer-A")));
        assertEquals(new SelfSession.Execution.Observed("model-A", "high", null, REQUEST_RECORD), reader.read(session(transcript, "toolu_x", root())));
    }

    // ---- u1: a request of the host's own side work (a summary, a title) is passed over; it is neither the main thread's nor a subagent's

    @Test
    void anAuxiliaryRequestIsPassedOverAndTheCallersOwnRequestBeforeItIsTaken() throws Exception {
        for (String auxiliary : List.of("compact", "agent_summary", "generate_session_title", "auto_mode")) {
            Path transcript = write(dir.resolve(auxiliary + ".jsonl"), json(request("model-A", "high")), json(request("aux-model", "low", "querySource", auxiliary)));
            assertEquals(new SelfSession.Execution.Observed("model-A", "high", null, REQUEST_RECORD), reader.read(session(transcript, "toolu_x", root())), auxiliary);
        }
    }

    @Test
    void aChildPassesOverAnAuxiliaryRequestToo() throws Exception {
        Path transcript = transcript(json(request("main-model", "high")));
        childFile(json(childRequest("child-model-1")), json(childRequest("aux-model", "querySource", "compact")));
        assertEquals(new SelfSession.Execution.Observed("child-model-1", "low", null, REQUEST_RECORD), reader.read(session(transcript, "toolu_x", SessionActor.child("A", "test"))));
    }

    @Test
    void aTranscriptWithOnlyAuxiliaryRequestsSaysSoAndGivesTheNewestSourceCutAndCleaned() throws Exception {
        Path transcript = transcript(json(request("model-A", "high", "querySource", "agent_summary")), json(request("model-B", "high", "querySource", "compact")));
        assertEquals(Map.of("transcriptReason", "REQUEST_ONLY_UNRECOGNIZED_SOURCES", "waitedMs", 250L, "linesSeen", 2, "requestSource", "compact"), unobserved(session(transcript, "toolu_x", root())).facts());
        Path odd = write(dir.resolve("odd.jsonl"), json(request("model-C", "high", "querySource", "ab c\n<script>" + "x".repeat(200))));
        assertEquals("ab?c??script?" + "x".repeat(64 - 13), unobserved(session(odd, "toolu_x", root())).facts().get("requestSource"));
        Path child = write(dir.resolve("child-main.jsonl"), json(request("main-model", "high")));
        childFile(json(childRequest("m", "querySource", "compact")));
        assertEquals("REQUEST_ONLY_UNRECOGNIZED_SOURCES", unobserved(session(child, "toolu_x", SessionActor.child("A", "test"))).facts().get("transcriptReason"));
    }

    @Test
    void anAuxiliaryRequestBesideARequestOfTheOtherKindOfThreadIsNotTheOnlyKind() throws Exception {
        Path transcript = transcript(json(request("side-model", "low", "querySource", "agent:builtin:general-purpose")), json(request("model-B", "high", "querySource", "compact")));
        assertReason("REQUEST_RECORD_NOT_FOUND", 2, unobserved(session(transcript, "toolu_x", root())));
    }

    // ---- u1c: the values of querySource that the host writes for a main thread and for a subagent

    @Test
    void theMainThreadsQuerySourcesIncludeOutputStylesAndSdk() throws Exception {
        for (String source : List.of("repl_main_thread", "repl_main_thread:outputStyle:Learning", "repl_main_thread:outputStyle:x", "sdk")) {
            Path transcript = write(dir.resolve(source.replace(':', '_') + ".jsonl"), json(request("model-of-" + source, "high", "querySource", source)));
            assertEquals(new SelfSession.Execution.Observed("model-of-" + source, "high", null, REQUEST_RECORD), reader.read(session(transcript, "toolu_x", root())), source);
        }
    }

    @Test
    void aNameThatOnlyBeginsLikeTheMainThreadIsNotTheMainThread() throws Exception {
        for (String source : List.of("repl_main_threadX", "sdk_other", "agent", "hook_agent2")) {
            Path transcript = write(dir.resolve(source + ".jsonl"), json(request("m", "high", "querySource", source)));
            assertEquals("REQUEST_ONLY_UNRECOGNIZED_SOURCES", unobserved(session(transcript, "toolu_x", root())).facts().get("transcriptReason"), source);
        }
    }

    @Test
    void aSubagentsQuerySourcesIncludeCustomAgentsAndHookAgents() throws Exception {
        Path transcript = transcript(json(request("main-model", "high")));
        for (String source : List.of("agent:custom:x", "agent:custom", "agent:default", "hook_agent")) {
            childFile(json(childRequest("model-of-" + source, "querySource", source)));
            assertEquals(new SelfSession.Execution.Observed("model-of-" + source, "low", null, REQUEST_RECORD), reader.read(session(transcript, "toolu_x", SessionActor.child("A", "test"))), source);
        }
    }

    @Test
    void aHookAgentsRequestIsASubagentsAndIsPassedOverForTheMainAgent() throws Exception {
        Path transcript = transcript(json(request("model-A", "high")), json(request("hook-model", "low", "querySource", "hook_agent")));
        assertEquals(new SelfSession.Execution.Observed("model-A", "high", null, REQUEST_RECORD), reader.read(session(transcript, "toolu_x", root())));
    }

    // ---- u2: a main-thread request that carries a subagent's marks is not the main thread's

    @Test
    void aMainThreadRequestThatCarriesAnAgentIdOrSidechainIsAMismatch() throws Exception {
        Path withAgent = write(dir.resolve("agent.jsonl"), json(request("model-A", "high")), json(request("model-B", "high", "agentId", "A")));
        assertReason("REQUEST_THREAD_MISMATCH", 2, unobserved(session(withAgent, "toolu_x", root())));
        Path sidechain = write(dir.resolve("side.jsonl"), json(request("model-C", "high", "isSidechain", true)));
        assertReason("REQUEST_THREAD_MISMATCH", 1, unobserved(session(sidechain, "toolu_x", root())));
    }

    // ---- c1 to c3: a child

    private Path childFile(String... lines) throws IOException {
        return write(dir.resolve(SESSION).resolve("subagents").resolve("agent-A.jsonl"), lines);
    }

    private static Map<String, Object> childRequest(String model, Object... extra) {
        return with(request(model, "low", "querySource", "agent:builtin:general-purpose", "agentId", "A"), extra);
    }

    @Test
    void aChildTakesTheLatestRequestThatNamesItsAgentId() throws Exception {
        Path transcript = transcript(json(request("main-model", "high")));
        childFile(json(userInput("agentId", "A", "isSidechain", true)), json(childRequest("child-model-1")), json(childRequest("child-model-2")),
                json(childRequest("other-agent-model", "agentId", "B")));
        assertEquals(new SelfSession.Execution.Observed("child-model-2", "low", null, REQUEST_RECORD), reader.read(session(transcript, "toolu_x", SessionActor.child("A", "test"))));
    }

    @Test
    void aChildDoesNotTakeAnotherAgentsOrTheMainThreadsRequestAndAnAgentRequestWithoutAnAgentIdIsAMismatch() throws Exception {
        Path transcript = transcript(json(request("main-model", "high")));
        childFile(json(childRequest("other-agent-model", "agentId", "B")), json(request("main-model", "high")));
        assertReason("REQUEST_RECORD_NOT_FOUND", 3, unobserved(session(transcript, "toolu_x", SessionActor.child("A", "test"))));
        childFile(json(childRequest("no-agent-model", "agentId", null)));
        assertReason("REQUEST_THREAD_MISMATCH", 2, unobserved(session(transcript, "toolu_x", SessionActor.child("A", "test"))));
    }

    @Test
    void aChildsRequestFollowedByTheChildsNewInputIsStale() throws Exception {
        Path transcript = transcript(json(request("main-model", "high")));
        childFile(json(childRequest("child-model-1")), json(userInput("agentId", "A", "isSidechain", true)));
        assertReason("REQUEST_STALE", 3, unobserved(session(transcript, "toolu_x", SessionActor.child("A", "test"))));
    }

    // ---- w1: a subagent of a workflow keeps its file one folder deeper, under the run

    private Path workflowFile(String run, String... lines) throws IOException {
        return write(dir.resolve(SESSION).resolve("subagents").resolve("workflows").resolve(run).resolve("agent-A.jsonl"), lines);
    }

    @Test
    void aWorkflowSubagentIsReadFromItsFileUnderTheRunsFolder() throws Exception {
        Path transcript = transcript(json(request("main-model", "high")));
        write(dir.resolve(SESSION).resolve("subagents").resolve("workflows").resolve("run-0").resolve("agent-B.jsonl"), json(childRequest("another-agents-model", "agentId", "B")));
        workflowFile("run-1", json(childRequest("workflow-child-model")));
        assertEquals(new SelfSession.Execution.Observed("workflow-child-model", "low", null, REQUEST_RECORD), reader.read(session(transcript, "toolu_x", SessionActor.child("A", "test"))));
        write(dir.resolve(SESSION).resolve("subagents").resolve("workflows").resolve("run-3").resolve("agent-C.jsonl"), json(issuer("toolu_wf", "line-model", "isSidechain", true, "agentId", "C")));
        assertEquals(new SelfSession.Execution.Observed("line-model", null, null, CALL_RECORD), reader.read(session(transcript, "toolu_wf", SessionActor.child("C", "test"))));
    }

    @Test
    void theFileUnderTheSessionsSubagentsFolderIsTakenBeforeThoseOfTheRuns() throws Exception {
        Path transcript = transcript(json(request("main-model", "high")));
        childFile(json(childRequest("direct-model")));
        workflowFile("run-1", json(childRequest("workflow-model")));
        assertEquals(new SelfSession.Execution.Observed("direct-model", "low", null, REQUEST_RECORD), reader.read(session(transcript, "toolu_x", SessionActor.child("A", "test"))));
    }

    @Test
    void aFileDeeperThanTheRunsFolderIsNotLookedFor() throws Exception {
        Path transcript = transcript(json(request("main-model", "high")));
        write(dir.resolve(SESSION).resolve("subagents").resolve("workflows").resolve("run-1").resolve("deeper").resolve("agent-A.jsonl"), json(childRequest("too-deep")));
        write(dir.resolve(SESSION).resolve("subagents").resolve("other").resolve("agent-A.jsonl"), json(childRequest("not-under-workflows")));
        assertReason("REQUEST_RECORD_NOT_FOUND", 1, unobserved(session(transcript, "toolu_x", SessionActor.child("A", "test"))));
    }

    // ---- r8

    private static final SelfSession.Execution HOOK_MEDIUM = new SelfSession.Execution.Unobserved("none", Map.of(), "medium");

    @Test
    void aRequestWithoutAnEffortTakesTheEffortOfTheHook() throws Exception {
        Path transcript = transcript(json(request("request-model", null)));
        assertEquals(new SelfSession.Execution.Observed("request-model", "medium", null, REQUEST_RECORD), reader.read(session(transcript, "toolu_x", root(), HOOK_MEDIUM)));
        assertEquals(new SelfSession.Execution.Observed("request-model", null, null, REQUEST_RECORD), reader.read(session(transcript, "toolu_x", root())));
    }

    // ---- h1: the effort of the hook is the call's own; a request that names another is refused

    @Test
    void aRequestThatNamesAnotherEffortThanTheHookIsRefusedAndOneThatNamesTheSameIsTaken() throws Exception {
        Path other = write(dir.resolve("other.jsonl"), json(request("request-model", "xhigh")));
        assertReason("REQUEST_EFFORT_MISMATCH", 1, unobserved(session(other, "toolu_x", root(), HOOK_MEDIUM)));
        Path same = write(dir.resolve("same.jsonl"), json(request("request-model", "medium")));
        assertEquals(new SelfSession.Execution.Observed("request-model", "medium", null, REQUEST_RECORD), reader.read(session(same, "toolu_x", root(), HOOK_MEDIUM)));
        assertEquals(new SelfSession.Execution.Observed("request-model", "xhigh", null, REQUEST_RECORD), reader.read(session(other, "toolu_x", root())), "no effort of the hook: the request's is what there is");
    }

    @Test
    void aChildsRequestThatNamesAnotherEffortThanTheHookIsRefusedToo() throws Exception {
        Path transcript = transcript(json(request("main-model", "high")));
        childFile(json(childRequest("child-model", "agentId", "A")));
        assertReason("REQUEST_EFFORT_MISMATCH", 2, unobserved(session(transcript, "toolu_x", SessionActor.child("A", "test"), new SelfSession.Execution.Unobserved("none", Map.of(), "max"))));
    }

    /**
     * A line appended to the file by someone who can write it (the agent observed, too) is believed when it is of this thread and session, whatever model it names: the file is what the host
     * wrote, and the look is no defence against a writer on the same PC. What the hook gave is the host's own value for this call, so a forged line whose effort differs from it is refused.
     */
    @Test
    void aForgedRequestLineIsRefusedWhenItsEffortDiffersFromTheHooksAndTakenWhenItDoesNot() throws Exception {
        Path forgedLow = transcript(json(request("real-model", "medium")), json(request("forged-model", "low")));
        assertReason("REQUEST_EFFORT_MISMATCH", 2, unobserved(session(forgedLow, "toolu_x", root(), HOOK_MEDIUM)));
        Path forgedRight = write(dir.resolve("forged.jsonl"), json(request("real-model", "medium")), json(request("forged-model", "medium")));
        assertEquals(new SelfSession.Execution.Observed("forged-model", "medium", null, REQUEST_RECORD), reader.read(session(forgedRight, "toolu_x", root(), HOOK_MEDIUM)),
                "the known limit: a forged line with the right effort cannot be told from the host's");
    }

    /** The hook's effort is the call's own on both paths; the line of the call is the same kind of record as the request: its effort, which Claude Code 2.1.330 does not write, is only the fallback. */
    @Test
    void theEffortOfTheHookIsTheEffortOfTheLineOfTheCallAndTheLinesOwnIsTakenOnlyWhenThereIsNone() throws Exception {
        Path none = write(dir.resolve("none.jsonl"), json(issuer("toolu_line", "line-model")));
        assertEquals(new SelfSession.Execution.Observed("line-model", "medium", null, CALL_RECORD), reader.read(session(none, "toolu_line", root(), HOOK_MEDIUM)));
        Path differs = write(dir.resolve("differs.jsonl"), json(issuer("toolu_line", "line-model", "effort", "low")));
        assertEquals(new SelfSession.Execution.Observed("line-model", "medium", null, CALL_RECORD), reader.read(session(differs, "toolu_line", root(), HOOK_MEDIUM)),
                "the line names another effort than the hook: the hook's, and no refusal, since the call is tied to its line by its id");
        assertEquals(new SelfSession.Execution.Observed("line-model", "low", null, CALL_RECORD), reader.read(session(differs, "toolu_line", root())), "no effort of the hook: the line's");
        Path child = childFile(json(issuer("toolu_child", "child-model", "isSidechain", true, "agentId", "A", "effort", "low")));
        assertEquals(new SelfSession.Execution.Observed("child-model", "medium", null, CALL_RECORD),
                reader.read(session(transcript(), "toolu_child", SessionActor.child("A", "test"), HOOK_MEDIUM)));
        assertEquals(true, Files.exists(child));
        assertEquals(List.of(), pauses);
    }

    // ---- t1: the file is read once more, after the host's queue has been written, only when the call has no line

    @Test
    void aCallWhoseLineIsWrittenDuringTheWaitIsObservedFromThatLine() throws Exception {
        Path transcript = transcript(json(request("request-model", "high")));
        whilePaused = () -> Files.writeString(transcript, json(issuer("toolu_late", "line-model")) + "\n", java.nio.file.StandardOpenOption.APPEND);
        assertEquals(new SelfSession.Execution.Observed("line-model", null, null, CALL_RECORD), reader.read(session(transcript, "toolu_late", root())));
        assertEquals(List.of(ClaudeTranscriptExecutionReader.REREAD_DELAY), pauses);
    }

    @Test
    void aRequestWrittenDuringTheWaitIsTheNewestRequestThatIsTaken() throws Exception {
        Path transcript = transcript(json(request("model-A", "high")));
        whilePaused = () -> Files.writeString(transcript, json(request("model-B", "high")) + "\n", java.nio.file.StandardOpenOption.APPEND);
        assertEquals(new SelfSession.Execution.Observed("model-B", "high", null, REQUEST_RECORD), reader.read(session(transcript, "toolu_x", root())));
        assertEquals(1, pauses.size());
    }

    @Test
    void aCallWhoseLineIsStillNotWrittenAfterTheWaitTakesTheNewestRequest() throws Exception {
        Path transcript = transcript(json(request("model-A", "high")));
        assertEquals(new SelfSession.Execution.Observed("model-A", "high", null, REQUEST_RECORD), reader.read(session(transcript, "toolu_x", root())));
        assertEquals(List.of(ClaudeTranscriptExecutionReader.REREAD_DELAY), pauses);
        assertEquals(250, ClaudeTranscriptExecutionReader.REREAD_DELAY.toMillis());
        assertEquals(true, ClaudeTranscriptExecutionReader.REREAD_DELAY.compareTo(ClaudeTranscriptExecutionReader.HOST_QUEUE_FLUSH_INTERVAL.multipliedBy(2)) > 0,
                "more than twice the interval at which the host writes its queue");
    }

    @Test
    void aCallWhoseLineIsThereWaitsForNothingWhateverItSays() throws Exception {
        Path transcript = transcript(json(issuer("toolu_1", "line-model")));
        reader.read(session(transcript, "toolu_1", root()));
        Path otherThread = write(dir.resolve("other.jsonl"), json(issuer("toolu_2", "m", "isSidechain", true)));
        reader.read(session(otherThread, "toolu_2", root()));
        Path noModel = write(dir.resolve("none.jsonl"), json(issuer("toolu_3", null)));
        reader.read(session(noModel, "toolu_3", root()));
        assertEquals(List.of(), pauses);
    }

    @Test
    void theFilesAreReadOnceMoreAtMostOnceWhateverTheNumberOfFilesOfTheSubagent() throws Exception {
        Path transcript = transcript(json(request("main-model", "high")));
        workflowFile("run-1", json(childRequest("child-1")));
        write(dir.resolve(SESSION).resolve("subagents").resolve("workflows").resolve("run-2").resolve("agent-A.jsonl"), json(childRequest("child-2")));
        reader.read(session(transcript, "toolu_x", SessionActor.child("A", "test")));
        assertEquals(1, pauses.size());
    }

    @Test
    void anInterruptedWaitEndsTheWaitingKeepsTheInterruptAndLooksAtWhatWasRead() throws Exception {
        Path transcript = transcript(json(request("model-A", "high")));
        ClaudeTranscriptExecutionReader interrupted = new ClaudeTranscriptExecutionReader(new ClaudeTranscriptFile(MAPPER), duration -> {
            throw new InterruptedException();
        });
        try {
            assertEquals(new SelfSession.Execution.Observed("model-A", "high", null, REQUEST_RECORD), interrupted.read(session(transcript, "toolu_x", root())));
            assertEquals(true, Thread.interrupted(), "the interrupt is kept for the caller (and cleared here)");
            Path nothing = write(dir.resolve("nothing.jsonl"), "{\"type\":\"user\"}");
            assertEquals(0L, interrupted.read(session(nothing, "toolu_x", root())) instanceof SelfSession.Execution.Unobserved u ? u.facts().get("waitedMs") : -1L, "it did not wait");
        } finally {
            Thread.interrupted();
        }
    }

    // ---- n1: what opens a new turn after the request

    private static Map<String, Object> notice(Object... extra) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("type", "user");
        line.put("sessionId", SESSION);
        line.put("isMeta", true);
        line.put("message", Map.of("role", "user", "content", List.of(Map.of("type", "text", "text", "<task-notification>\n<task-id>b1</task-id>\n</task-notification>"))));
        return with(line, extra);
    }

    @Test
    void theNoticeOfABackgroundTaskAfterTheRequestIsANewTurnEvenWhenTheHostMarksItAsInjected() throws Exception {
        Path transcript = transcript(json(request("model-A", "high")), json(notice()));
        assertReason("REQUEST_STALE", 2, unobserved(session(transcript, "toolu_x", root())));
        Path reminder = write(dir.resolve("reminder.jsonl"), json(request("model-A", "high")), json(userInput("isMeta", true)));
        assertEquals(new SelfSession.Execution.Observed("model-A", "high", null, REQUEST_RECORD), reader.read(session(reminder, "toolu_x", root())));
    }

    @Test
    void aCompactionAfterTheRequestIsANewContext() throws Exception {
        Map<String, Object> boundary = new LinkedHashMap<>();
        boundary.put("type", "system");
        boundary.put("sessionId", SESSION);
        boundary.put("logicalParentUuid", "u-before");
        Path transcript = transcript(json(request("model-A", "high")), json(boundary));
        assertReason("REQUEST_STALE", 2, unobserved(session(transcript, "toolu_x", root())));
    }

    @Test
    void aLineOfAnotherPromptIdAfterTheRequestIsANewTurnAndOneOfTheSameIsNot() throws Exception {
        Path other = transcript(json(request("model-A", "high", "promptId", "p-1")), json(answer("answer-A")), json(with(answer("answer-B"), "promptId", "p-2")));
        assertReason("REQUEST_STALE", 3, unobserved(session(other, "toolu_x", root())));
        Path same = write(dir.resolve("same.jsonl"), json(request("model-A", "high", "promptId", "p-1")), json(with(answer("answer-A"), "promptId", "p-1")));
        assertEquals(new SelfSession.Execution.Observed("model-A", "high", null, REQUEST_RECORD), reader.read(session(same, "toolu_x", root())));
        Path noPromptId = write(dir.resolve("none.jsonl"), json(request("model-A", "high")), json(with(answer("answer-A"), "promptId", "p-2")));
        assertEquals(new SelfSession.Execution.Observed("model-A", "high", null, REQUEST_RECORD), reader.read(session(noPromptId, "toolu_x", root())));
        Path subagentsLine = write(dir.resolve("sub.jsonl"), json(request("model-A", "high", "promptId", "p-1")), json(with(answer("sub"), "promptId", "p-2", "isSidechain", true, "agentId", "B")));
        assertEquals(new SelfSession.Execution.Observed("model-A", "high", null, REQUEST_RECORD), reader.read(session(subagentsLine, "toolu_x", root())), "another thread's line opens no turn of this one");
    }

    // ---- l1: a request line longer than the end of the file that is read whole

    private static String big(int length) {
        return "x".repeat(length);
    }

    private static Map<String, Object> bigRequest(String model, String effort, int bodyBytes, Object... extra) {
        Map<String, Object> line = request(model, effort, extra);
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) line.get("params");
        params.put("messages", List.of(Map.of("role", "user", "content", big(bodyBytes))));
        return line;
    }

    @Test
    void aRequestLineLongerThanTheEndOfTheFileThatIsReadWholeIsStillTheRequest() throws Exception {
        Path last = transcript("{\"type\":\"user\"}", json(bigRequest("big-model", "high", 3 * 1024 * 1024)));
        assertEquals(new SelfSession.Execution.Observed("big-model", "high", null, REQUEST_RECORD), reader.read(session(last, "toolu_x", root())));
        Path followed = write(dir.resolve("followed.jsonl"), json(bigRequest("big-model", "high", 3 * 1024 * 1024)), json(answer("answer")), json(toolResult("toolu_a")));
        assertEquals(new SelfSession.Execution.Observed("big-model", "high", null, REQUEST_RECORD), reader.read(session(followed, "toolu_x", root())));
        Path stale = write(dir.resolve("stale.jsonl"), json(bigRequest("big-model", "high", 3 * 1024 * 1024)), json(userInput()));
        assertReason("REQUEST_STALE", 2, unobserved(session(stale, "toolu_x", root())));
        Path mismatch = write(dir.resolve("mismatch.jsonl"), json(bigRequest("big-model", "low", 3 * 1024 * 1024)));
        assertReason("REQUEST_EFFORT_MISMATCH", 1, unobserved(session(mismatch, "toolu_x", root(), HOOK_MEDIUM)));
    }

    @Test
    void aSubagentsRequestLineLongerThanTheEndOfTheFileIsReadToo() throws Exception {
        Path transcript = transcript(json(request("main-model", "high")));
        childFile(json(bigRequest("child-big", "low", 3 * 1024 * 1024, "querySource", "agent:builtin:general-purpose", "agentId", "A")));
        assertEquals(new SelfSession.Execution.Observed("child-big", "low", null, REQUEST_RECORD), reader.read(session(transcript, "toolu_x", SessionActor.child("A", "test"))));
    }

    @Test
    void aLineOfTheCallLongerThanTheEndOfTheFileIsFoundByTheIdOfTheCall() throws Exception {
        Map<String, Object> call = issuer("toolu_big", "line-model");
        @SuppressWarnings("unchecked")
        Map<String, Object> message = (Map<String, Object>) call.get("message");
        message.put("content", List.of(Map.of("type", "text", "text", big(3 * 1024 * 1024)), Map.of("type", "tool_use", "id", "toolu_big", "name", "start_workflow", "input", Map.of())));
        Path transcript = transcript(json(call));
        assertEquals(new SelfSession.Execution.Observed("line-model", null, null, CALL_RECORD), reader.read(session(transcript, "toolu_big", root())));
        assertEquals(List.of(), pauses);
    }

    @Test
    void aRequestLineBeyondWhatIsReadAtAllIsToldApartFromOneThatIsNotThere() throws Exception {
        Path file = dir.resolve("huge.jsonl");
        try (java.io.BufferedWriter out = Files.newBufferedWriter(file)) {
            out.write("{\"type\":\"api-request\",\"querySource\":\"repl_main_thread\",\"sessionId\":\"" + SESSION + "\",\"params\":{\"model\":\"m\",\"messages\":\"");
            String chunk = big(1024 * 1024);
            for (long written = 0; written <= ClaudeTranscriptFile.OVERSIZED_LINE_BYTES; written += chunk.length()) {
                out.write(chunk);
            }
            out.write("\"}}\n");
        }
        assertReason("REQUEST_LINE_TOO_LARGE", 1, unobserved(session(file, "toolu_x", root())));
    }

    @Test
    void whenTheRequestIsOlderThanWhatIsReadTheLimitIsSaidAndNotThatNoRequestWasThere() throws Exception {
        List<String> lines = new ArrayList<>();
        lines.add(json(request("model-old", "high")));
        for (int i = 0; i < 4100; i++) {
            lines.add(json(toolResult("toolu_" + i)));
        }
        Path transcript = transcript(lines.toArray(String[]::new));
        assertReason("REQUEST_BEYOND_READ_LIMIT", 4000, unobserved(session(transcript, "toolu_x", root())));
        Path small = write(dir.resolve("small.jsonl"), json(toolResult("toolu_1")));
        assertReason("REQUEST_RECORD_NOT_FOUND", 1, unobserved(session(small, "toolu_x", root())));
    }

    // ---- p1: only the last line may be half written

    @Test
    void aLineInTheMiddleThatMentionsARequestAndCannotBeReadDoesNotEndTheLook() throws Exception {
        Path transcript = transcript(json(request("model-A", "high")), "{\"type\":\"api-request\",\"querySource\":\"repl_main_th", "garbage api-request garbage", json(answer("answer-A")));
        assertEquals(new SelfSession.Execution.Observed("model-A", "high", null, REQUEST_RECORD), reader.read(session(transcript, "toolu_x", root())));
    }

    @Test
    void theLastLineThatCannotBeReadEndsTheLookEvenWhenBlankLinesFollowIt() throws Exception {
        Path transcript = transcript(json(request("model-A", "high")), "{\"type\":\"api-request\",\"querySource\":\"repl_main_th", "", "   ");
        assertReason("REQUEST_THREAD_UNKNOWN", 4, unobserved(session(transcript, "toolu_x", root())));
    }

    @Test
    void aLineOfAnotherTypeThatBeginsLikeARequestIsNotARequest() throws Exception {
        Path transcript = transcript(json(request("model-A", "high")), json(request("resumed-model", "high", "type", "api-request-resumed")));
        assertEquals(new SelfSession.Execution.Observed("model-A", "high", null, REQUEST_RECORD), reader.read(session(transcript, "toolu_x", root())));
    }

    // ---- f1: a folder of runs that cannot be listed is told

    private final ClaudeTranscriptFile unlistable = new ClaudeTranscriptFile(MAPPER) {
        @Override
        List<Path> subfolders(Path folder) throws IOException {
            throw new IOException("the folder cannot be listed");
        }
    };

    @Test
    void aFolderOfWorkflowRunsThatCannotBeListedIsToldAndNotTakenForARequestThatIsNotThere() throws Exception {
        Path transcript = transcript(json(request("main-model", "high")));
        Files.createDirectories(dir.resolve(SESSION).resolve("subagents").resolve("workflows"));
        ClaudeTranscriptExecutionReader failing = new ClaudeTranscriptExecutionReader(unlistable, pauses::add);
        SelfSession.Execution.Unobserved result = assertInstanceOf(SelfSession.Execution.Unobserved.class, failing.read(session(transcript, "toolu_x", SessionActor.child("A", "test"))));
        assertEquals("WORKFLOW_RUNS_UNLISTABLE", result.facts().get("transcriptReason"));
        // The subagent's own file, when it holds the request, is still taken.
        childFile(json(childRequest("child-model")));
        assertEquals(new SelfSession.Execution.Observed("child-model", "low", null, REQUEST_RECORD), failing.read(session(transcript, "toolu_x", SessionActor.child("A", "test"))));
    }

    // ---- i1: an id is a file name only when it is of the form the host gives

    @Test
    void anIdThatIsNotOfTheFormTheHostGivesIsRefusedBeforeAFileIsNamedAfterIt() throws Exception {
        Path transcript = transcript(json(request("main-model", "high")));
        for (String id : List.of("a:b", "../x", "", "a b", "x/y", "a\\b", "agent\u0000", "C:evil")) {
            assertReason("CALLER_ID_INVALID", 0, unobserved(session(transcript, "toolu_x", SessionActor.child(id, "test"))));
        }
        SelfSession invalidSession = new SelfSession("ref", 1, "claude-code", "s:1", SelfSession.Provenance.HOST_OBSERVED, SessionActor.child("A", "test"), null, "toolu_x", new SelfSession.Execution.Unobserved("none"), transcript.toString());
        assertReason("CALLER_ID_INVALID", 0, unobserved(invalidSession));
        assertEquals(List.of(), pauses);
        // The forms the host gives pass: a subagent `a` + 16 hex, and a subagent of a workflow `wf_<8hex>-<3hex>-<n>`.
        for (String id : List.of("a0123456789abcdef", "wf_0a1b2c3d-4e5-6")) {
            assertReason("REQUEST_RECORD_NOT_FOUND", 1, unobserved(session(transcript, "toolu_x", SessionActor.child(id, "test"))));
        }
    }

    @Test
    void aTranscriptPathThatIsNotAPathIsToldAndRaisesNothing() {
        assertReason("TRANSCRIPT_PATH_INVALID", 0, unobserved(new SelfSession("ref", 1, "claude-code", SESSION, SelfSession.Provenance.HOST_OBSERVED, root(), null, "toolu_x",
                new SelfSession.Execution.Unobserved("none"), "bad\u0000path.jsonl")));
    }
}
