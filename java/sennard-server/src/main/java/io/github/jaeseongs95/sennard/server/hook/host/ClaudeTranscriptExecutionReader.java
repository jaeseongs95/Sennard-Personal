// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook.host;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.server.hook.HostContext;
import io.github.jaeseongs95.sennard.server.hook.host.ClaudeTranscriptFile.Tail;
import io.github.jaeseongs95.sennard.server.hook.host.ClaudeTranscriptFile.TailLine;
import io.github.jaeseongs95.sennard.server.mcp.SelfSession;
import io.github.jaeseongs95.sennard.server.mcp.SessionExecutionReader;

/**
 * When the host's hook omits the model, the host's own record of the session names it. Two records of the transcript are read, in this order:
 *
 * <ol>
 * <li>The assistant line that issued the call: its {@code message.model} is what the model that wrote the call is called ({@link SelfSession.Execution.Observed.Evidence#CALL_RECORD}).
 * <li>Failing that, the request the host sent to the model, which produced the call ({@link SelfSession.Execution.Observed.Evidence#REQUEST_RECORD}). The assistant line of a message with
 * several tool calls is not written until its tools have finished, and the tool that asks for it is one of them, so waiting for that line can never end well. The requests are read from
 * the end of the file backwards and the first one of the caller's own thread is the candidate. A thread is told by the request's {@code querySource} ({@link ClaudeEntry.RequestThread}):
 * {@code repl_main_thread} (also with an output style after a colon) and {@code sdk} are the main agent's, {@code agent:...} and {@code hook_agent} are a subagent's, whose own file also
 * names its {@code agentId}. A request of the other kind of thread is passed over, and so is one of the host's own side work ({@code compact}, {@code agent_summary},
 * {@code generate_session_title}, {@code auto_mode}, ...), since the caller's own request may be before it; when the lines read hold no request of any thread, only such side work, the
 * look ends on {@link Reason#REQUEST_ONLY_UNRECOGNIZED_SOURCES}. A candidate of the main thread that carries an {@code agentId} or {@code isSidechain} true, or a subagent's request that
 * carries none, is not the caller's ({@link Reason#REQUEST_THREAD_MISMATCH}). A candidate followed by a line that opens a new turn of the same thread (the user's own input, the notice of
 * a background task, a compaction, a line of another {@code promptId}) is not the request that produced the call ({@link Reason#REQUEST_STALE}). A result line after the candidate is no
 * sign of an ended answer: the first tool of the message may have finished before the later one asks. The model of an earlier <em>answer</em> is never used. The model is then said to come
 * from the request record: {@code MODEL_FROM_REQUEST_RECORD} in the policy notes of the context.
 * </ol>
 *
 * <p><b>The reasoning effort</b> is the one the hook reported for this call ({@code effort.level} of the PreToolUse input, which the host ties to the call by its {@code tool_use_id}) when
 * it reported one, on both paths; the effort the record names is only the fallback when the hook reported none. A line of the call that names another effort than the hook did is no reason
 * to refuse, since the call is tied to its line by its id: the hook's is taken. A request record that names another effort than the hook did is refused ({@link Reason#REQUEST_EFFORT_MISMATCH}),
 * since nothing ties it to the call; one that names none, or the same, takes the hook's.
 * The hook does not report the model (the host gives it only to SessionStart and the model-switch events), so there is no model of the hook to compare a request's with.
 *
 * <p><b>Not tied to the call.</b> Nothing the host writes ties the call's {@code tool_use} id to the request that produced it: a request record holds the conversation sent, not the answer, and
 * the line that holds the call is the one that is not yet written. The host queues a request's line right after the response's headers arrive and a timer writes the queue out about every
 * {@link #HOST_QUEUE_FLUSH_INTERVAL}; a hook does not wait for it. So when the call has no line, the file is read <em>once more</em> after {@link #REREAD_DELAY} (more than twice that
 * interval) and only then is the newest request of the same thread, which may be the one before the request that produced the call, taken. It names the model and effort of the earlier
 * request of the same answer chain, which differ from the call's only if the model or effort was switched between two requests of one turn; the hook's effort is what refuses the
 * difference in effort. A call whose line is in any one of the files at the first look (a subagent's own file included, whatever the line says) waits for nothing; so does a look where no file could be read. This is a known limit of the look, not a confirmed link.
 *
 * <p><b>What is trusted.</b> The look believes the file the host wrote. Whoever can append a line to the file (the agent that is observed included) can write a request record that this
 * reader takes; it is no defence against a writer on the same PC. The hook's effort refuses such a line only when its effort differs from the one the host gave.
 *
 * <p>The file's lines are read by {@link ClaudeTranscriptFile}, the same reader that {@link ClaudeTranscript} reads a turn back with. A subagent's lines are in its own file, beside the
 * session's ({@code <session>/subagents/agent-<agentId>.jsonl}) or, for a subagent of a workflow, in {@code <session>/subagents/workflows/<runId>/agent-<agentId>.jsonl}; its requests carry
 * the {@code agentId}. The ids are made into file names only when they are of {@code [A-Za-z0-9_-]+}, the form the host gives them.
 *
 * <p>When the model is not found the answer is an {@link SelfSession.Execution.Unobserved} that says which of the {@link Reason}s the look ended on, how many lines it saw and how long it
 * waited ({@code waitedMs}: {@link #REREAD_DELAY} when it read again, otherwise 0); for {@link Reason#REQUEST_ONLY_UNRECOGNIZED_SOURCES} also the {@code requestSource} (the newest of them) it saw, cleaned to
 * {@code [A-Za-z0-9_:.-]} and cut to {@value #REQUEST_SOURCE_LIMIT} characters. Only codes and numbers are given out, never a line of the transcript.
 */
@Component
class ClaudeTranscriptExecutionReader implements SessionExecutionReader, ServerContextOnly {
    private static final Logger LOG = LoggerFactory.getLogger(ClaudeTranscriptExecutionReader.class);
    private static final String DEFAULT_UNOBSERVED = "HOOK_MODEL_NOT_REPORTED";
    private static final int REQUEST_SOURCE_LIMIT = 64;
    private static final Pattern NOT_A_SOURCE_CHARACTER = Pattern.compile("[^A-Za-z0-9_:.-]");
    /** The form of the ids the host gives a session and a subagent (a subagent of a workflow is {@code wf_<hex>-<hex>-<n>}): what may be made into a file name. */
    private static final Pattern HOST_ID = Pattern.compile("[A-Za-z0-9_-]+");
    private static final String REQUEST_MARK = "api-request";

    /**
     * How often Claude Code 2.1.330 writes the lines it has queued to the session's file: its writer drains the queue on a timer of this interval (read from the program;
     * a request's line is queued right after the response's headers arrive).
     */
    static final Duration HOST_QUEUE_FLUSH_INTERVAL = Duration.ofMillis(100);
    /** How long a call whose line is not in the file waits before the file is read once more: more than twice {@link #HOST_QUEUE_FLUSH_INTERVAL}, so that a line queued before the call is written. */
    static final Duration REREAD_DELAY = Duration.ofMillis(250);

    /** How the reader waits, so that a test does not have to. */
    @FunctionalInterface
    interface Pause {
        void pause(Duration duration) throws InterruptedException;
    }

    /** Why the model was not found, ordered from the furthest the look got to the least. */
    enum Reason {
        /** The line that issued the call is there, in this thread, and names no model. */
        MODEL_FIELD_MISSING,
        /** The line that issued the call is there but is another thread's (another session, or a subagent's for the main agent and the reverse). */
        OTHER_THREAD,
        /** The caller's latest request is there, and a line that opens a new turn of the same thread follows it: it did not produce this call. */
        REQUEST_STALE,
        /** The caller's latest request is there and names no model. */
        REQUEST_MODEL_MISSING,
        /** The caller's latest request is there and names another reasoning effort than the hook gave for this call. */
        REQUEST_EFFORT_MISMATCH,
        /** The caller's latest request is there but names another session, or none. */
        REQUEST_SESSION_MISMATCH,
        /** A request of the caller's kind of thread that carries the marks of another (the main thread's with an agent id or as a sidechain, a subagent's with no agent id). */
        REQUEST_THREAD_MISMATCH,
        /** The requests in the lines read are all of the host's own side work (a {@code querySource} that is neither a main thread's nor a subagent's): none of the caller's kind of thread is there. */
        REQUEST_ONLY_UNRECOGNIZED_SOURCES,
        /** The last line of the file may be a request record that does not say which thread sent it, or cannot be read (it is being written), so no earlier request is taken in its place. */
        REQUEST_THREAD_UNKNOWN,
        /** A line longer than what is read at all ({@link ClaudeTranscriptFile#OVERSIZED_LINE_BYTES}) is the oldest line read and may be the caller's request. */
        REQUEST_LINE_TOO_LARGE,
        /** No request of the caller's thread is in what was read, and the file has older lines than the limits of what is read. */
        REQUEST_BEYOND_READ_LIMIT,
        /** The folder of the workflow runs of the subagents cannot be listed, so the file of the subagent may be there and unseen. */
        WORKFLOW_RUNS_UNLISTABLE,
        /** The call has no line in the transcript and the transcript holds no request of the caller's thread. */
        REQUEST_RECORD_NOT_FOUND,
        /** The transcript is not a file or could not be read. */
        TRANSCRIPT_UNREADABLE,
        /** The path the hook gave for the transcript is not a path. */
        TRANSCRIPT_PATH_INVALID,
        /** The hook gave no transcript path. */
        TRANSCRIPT_PATH_MISSING,
        /** The session or subagent id is not of the form the host gives, so no file is named after it. */
        CALLER_ID_INVALID,
        /** The host gave no id for the call. */
        CALL_ID_MISSING
    }

    /** One look at one file: what it found, or why not, how many lines it saw, and for an unrecognized thread kind the (cleaned) {@code querySource}. */
    private record Look(SelfSession.Execution.Observed observed, Reason reason, int lines, String requestSource) {
        Look(SelfSession.Execution.Observed observed, Reason reason, int lines) {
            this(observed, reason, lines, null);
        }
    }

    /** The files a caller's lines can be in, and whether the folder of the workflow runs, which may hold one, could not be listed. */
    private record Sources(List<Path> files, boolean runsUnlistable) {
    }

    /** A file read once: its end, or none when it could not be read. */
    private record Loaded(Path file, Tail tail) {
        int lines() {
            return tail == null ? 0 : tail.lines().size();
        }
    }

    /**
     * Whose request is wanted: the main agent's ({@code agentId} null) or the subagent's of this id, and the reasoning effort the hook gave for the call.
     */
    private record Caller(String sessionId, String agentId, String hookEffort) {
        boolean main() {
            return agentId == null;
        }

        /**
         * Whether the line, written after {@code request}, opens a new turn of this thread: the answer to the request has ended. It is not what the host injected, not another thread's,
         * not a tool's result. A new turn is the user's own input, the notice of a background task, a compaction (the line that continues the conversation after it), or a line of
         * a conversation turn that belongs to another {@code promptId} than the request's.
         */
        boolean opensNewTurnAfter(ClaudeEntry request, ClaudeEntry line) {
            boolean sameSession = line.sessionId().map(sessionId::equals).orElse(true);
            boolean sameThread = main() ? !line.sidechain() && line.agentId().isEmpty() : agentId.equals(line.agentId());
            if (!sameSession || !sameThread) {
                return false;
            }
            boolean input = line.kind() == ClaudeEntry.Kind.PROMPT && (!line.meta() || line.announcesBackgroundTask());
            boolean otherPrompt = line.kind() != ClaudeEntry.Kind.OTHER && request.promptId().isPresent() && line.promptId().filter(id -> !id.equals(request.promptId().get())).isPresent();
            return input || line.compactBoundary() || otherPrompt;
        }
    }

    private final ClaudeTranscriptFile files;
    private final Pause pause;

    @Autowired
    ClaudeTranscriptExecutionReader(ClaudeTranscriptFile files) {
        this(files, duration -> Thread.sleep(duration));
    }

    ClaudeTranscriptExecutionReader(ClaudeTranscriptFile files, Pause pause) {
        this.files = files;
        this.pause = pause;
    }

    @Override
    public String host() {
        return ClaudeTranscriptFile.HOST;
    }

    @Override
    public SelfSession.Execution read(SelfSession session) {
        SelfSession.Execution.Unobserved pending = session.execution() instanceof SelfSession.Execution.Unobserved was
                ? was : new SelfSession.Execution.Unobserved(DEFAULT_UNOBSERVED);
        String unobserved = pending.reason();
        if (session.transcriptPath() == null) {
            return notFound(unobserved, Reason.TRANSCRIPT_PATH_MISSING, 0, 0, null);
        }
        if (session.callId() == null) {
            return notFound(unobserved, Reason.CALL_ID_MISSING, 0, 0, null);
        }
        String sessionId = session.hostSessionId() != null ? session.hostSessionId() : session.sessionRef();
        String agentId = session.actor().role() == HostContext.Role.CHILD ? session.actor().agentKey() : null;
        if (agentId != null && !(HOST_ID.matcher(agentId).matches() && HOST_ID.matcher(sessionId).matches())) {
            return notFound(unobserved, Reason.CALLER_ID_INVALID, 0, 0, null);
        }
        Sources sources;
        try {
            Path transcript = Path.of(session.transcriptPath());
            sources = agentId == null ? new Sources(List.of(transcript), false) : subagentSources(transcript, sessionId, agentId);
        } catch (InvalidPathException notAPath) {
            LOG.debug("the transcript path the hook gave is not a path", notAPath);
            return notFound(unobserved, Reason.TRANSCRIPT_PATH_INVALID, 0, 0, null);
        }
        Caller caller = new Caller(sessionId, agentId, pending.effort());
        SelfSession.Execution execution = search(sources, session.callId(), caller, unobserved);
        if (execution instanceof SelfSession.Execution.Observed seen && caller.hookEffort() != null && !caller.hookEffort().equals(seen.effort())) {
            return new SelfSession.Execution.Observed(seen.model(), caller.hookEffort(), seen.turnId(), seen.evidence());
        }
        return execution;
    }

    /**
     * Where a subagent's own file can be: {@code agent-<agentId>.jsonl} in the session's {@code subagents} folder, then the same name in each run's folder of
     * {@code subagents/workflows/<runId>/} (a subagent of a workflow; the run is not in what the hook gives; the names of the runs are the file system's own). Only these two levels are
     * looked at, never deeper. The session's own file is the first candidate, then the subagent's own, which is a candidate even when it is absent (a file that is not there is the reason {@link Reason#TRANSCRIPT_UNREADABLE}); a run's file is a candidate
     * only where it is. A folder of runs that cannot be listed is told ({@link Reason#WORKFLOW_RUNS_UNLISTABLE}) and not taken for a folder with no run.
     */
    private Sources subagentSources(Path transcript, String sessionId, String agentId) {
        Path subagents = transcript.resolveSibling(sessionId).resolve("subagents");
        String name = "agent-" + agentId + ".jsonl";
        List<Path> candidates = new ArrayList<>(List.of(transcript, subagents.resolve(name)));
        Path workflows = subagents.resolve("workflows");
        if (!Files.isDirectory(workflows)) {
            return new Sources(candidates, false);
        }
        try {
            files.subfolders(workflows).stream().map(run -> run.resolve(name)).filter(Files::isRegularFile).forEach(candidates::add);
            return new Sources(candidates, false);
        } catch (IOException | UncheckedIOException unlistable) {
            LOG.debug("the folder of the workflow runs cannot be listed: {}", workflows, unlistable);
            return new Sources(candidates, true);
        }
    }

    /**
     * The line of the call in any of the files first. Only when none of them has it (and not when a line of the call is there, in any file, and says something else) and at least one file
     * could be read, the files are read once more
     * after {@link #REREAD_DELAY}, since the line of the call or of the request that produced it may be queued still; then the line of the call again, then the newest request of the caller's
     * thread.
     */
    private SelfSession.Execution search(Sources sources, String callId, Caller caller, String unobserved) {
        List<Loaded> loaded = new ArrayList<>(sources.files().stream().map(this::load).toList());
        List<Look> looks = new ArrayList<>(loaded.stream().map(each -> issuerLook(each, callId, caller)).toList());
        long waited = 0;
        // Waits only when no file has a line of the call (a line that says something else is a line of the call) and at least one file could be read: a transcript that is not there
        // is not known to be being made (nothing the host facts say), so it is not waited for.
        boolean lineFound = looks.stream().anyMatch(look -> look != null);
        boolean somethingRead = loaded.stream().anyMatch(each -> each.tail() != null);
        if (!lineFound && somethingRead && waitBeforeReading()) {
            waited = REREAD_DELAY.toMillis();
            for (int i = 0; i < loaded.size(); i++) {
                if (looks.get(i) == null) {
                    loaded.set(i, load(loaded.get(i).file()));
                    looks.set(i, issuerLook(loaded.get(i), callId, caller));
                }
            }
        }
        for (int i = 0; i < loaded.size(); i++) {
            if (looks.get(i) == null) {
                Loaded each = loaded.get(i);
                looks.set(i, each.tail() == null ? new Look(null, Reason.TRANSCRIPT_UNREADABLE, 0) : latestRequest(each.tail(), caller, each.file()));
            }
        }
        Look furthest = sources.runsUnlistable() ? new Look(null, Reason.WORKFLOW_RUNS_UNLISTABLE, 0) : null;
        int lines = 0;
        for (Look look : looks) {
            if (look.observed() != null) {
                return look.observed();
            }
            lines += look.lines();
            if (furthest == null || look.reason().compareTo(furthest.reason()) < 0) {
                furthest = look;
            }
        }
        return notFound(unobserved, furthest.reason(), waited, lines, furthest.requestSource());
    }

    /** Whether the wait took place: false when the thread was interrupted, which ends the waiting and is kept for the caller. */
    private boolean waitBeforeReading() {
        try {
            pause.pause(REREAD_DELAY);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static SelfSession.Execution notFound(String unobserved, Reason reason, long waitedMs, int lines, String requestSource) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("transcriptReason", reason.name());
        facts.put("waitedMs", waitedMs);
        facts.put("linesSeen", lines);
        if (requestSource != null) {
            facts.put("requestSource", requestSource);
        }
        LOG.warn("the model behind the call was not found in the transcript: transcriptReason={} waitedMs={} linesSeen={}", reason, waitedMs, lines);
        return new SelfSession.Execution.Unobserved(unobserved, facts);
    }

    /** ceiling: an unreadable transcript is the same as an absent issuer: the call stays unobserved and the caller is told it needs an attestation. */
    private Loaded load(Path file) {
        if (!Files.isRegularFile(file)) {
            return new Loaded(file, null);
        }
        try {
            return new Loaded(file, files.tail(file));
        } catch (IOException unreadable) {
            LOG.debug("the transcript cannot be read, so the call stays unobserved: {}", file, unreadable);
            return new Loaded(file, null);
        }
    }

    /** What the line that issued the call says, or null when the file is unreadable or has no such line (then the request is to be looked for). */
    private Look issuerLook(Loaded loaded, String callId, Caller caller) {
        if (loaded.tail() == null) {
            return null;
        }
        List<TailLine> lines = loaded.tail().lines();
        for (int i = lines.size() - 1; i >= 0; i--) {
            TailLine line = lines.get(i);
            if (!line.mentions(callId)) {
                continue;
            }
            Optional<ClaudeEntry> issuer = files.entry(line, loaded.file()).filter(entry -> entry.kind() == ClaudeEntry.Kind.ASSISTANT && entry.issuesToolUse(callId));
            if (issuer.isPresent()) {
                // The first line from the end that issued the call is the one: whether it is this thread's and names a model decides, and nothing further back is looked at.
                return observation(issuer.get(), caller, lines.size());
            }
        }
        return null;
    }

    private static Look observation(ClaudeEntry entry, Caller caller, int lines) {
        boolean wrongThread = entry.sessionId().filter(entrySession -> !entrySession.equals(caller.sessionId())).isPresent()
                || (caller.main() ? entry.sidechain() : !caller.agentId().equals(entry.agentId()));
        if (wrongThread) {
            return new Look(null, Reason.OTHER_THREAD, lines);
        }
        return entry.model().map(model -> new Look(new SelfSession.Execution.Observed(model, entry.effort(), null, SelfSession.Execution.Observed.Evidence.CALL_RECORD), null, lines))
                .orElseGet(() -> new Look(null, Reason.MODEL_FIELD_MISSING, lines));
    }

    /**
     * The request record nearest the end of the file that the caller's thread sent. A record of the other kind of thread is passed over (a subagent's for the main agent, the main
     * thread's or another subagent's for a subagent), and so is one of the host's own side work. A line in the middle of the file that cannot be read is passed over (the host writes
     * whole lines, and a line that merely mentions {@code api-request} is no request); the last line of the file that may be a record and cannot be read (it is being written), or one that
     * does not say which thread it is from, ends the look, since a newer request of the caller may be what it is.
     */
    private Look latestRequest(Tail tail, Caller caller, Path file) {
        List<TailLine> lines = tail.lines();
        int seen = lines.size();
        int last = seen - 1;
        while (last >= 0 && lines.get(last).blank()) {
            last--;
        }
        String newestAuxiliary = null;
        boolean sawAThread = false;
        for (int i = seen - 1; i >= 0; i--) {
            TailLine line = lines.get(i);
            if (line.tooLarge()) {
                return new Look(null, Reason.REQUEST_LINE_TOO_LARGE, seen);
            }
            if (line.unreadableEntry()) {
                if (i == last) {
                    return new Look(null, Reason.REQUEST_THREAD_UNKNOWN, seen);
                }
                continue;
            }
            if (!line.mentions(REQUEST_MARK)) {
                continue;
            }
            Optional<ClaudeEntry> parsed = files.entry(line, file);
            if (parsed.isEmpty()) {
                if (i == last) {
                    return new Look(null, Reason.REQUEST_THREAD_UNKNOWN, seen);
                }
                continue;
            }
            ClaudeEntry entry = parsed.get();
            if (!entry.isApiRequest()) {
                continue;
            }
            Optional<String> source = entry.requestSource();
            if (source.isEmpty()) {
                return new Look(null, Reason.REQUEST_THREAD_UNKNOWN, seen);
            }
            ClaudeEntry.RequestThread thread = ClaudeEntry.RequestThread.of(source.get());
            if (thread == ClaudeEntry.RequestThread.AUXILIARY) {
                // The host's own side work (a summary, a title) is no thread's request; the caller's own request may be before it.
                if (newestAuxiliary == null) {
                    newestAuxiliary = cleaned(source.get());
                }
                continue;
            }
            sawAThread = true;
            if (thread != (caller.main() ? ClaudeEntry.RequestThread.MAIN : ClaudeEntry.RequestThread.SUBAGENT)) {
                continue;
            }
            if (caller.main() ? !entry.agentId().isEmpty() || entry.sidechain() : entry.agentId().isEmpty()) {
                return new Look(null, Reason.REQUEST_THREAD_MISMATCH, seen);
            }
            if (!caller.main() && !caller.agentId().equals(entry.agentId())) {
                continue;
            }
            if (!entry.sessionId().filter(caller.sessionId()::equals).isPresent()) {
                return new Look(null, Reason.REQUEST_SESSION_MISMATCH, seen);
            }
            if (newTurnAfter(lines, i, entry, caller, file)) {
                return new Look(null, Reason.REQUEST_STALE, seen);
            }
            return observationOf(entry, caller, seen);
        }
        if (newestAuxiliary != null && !sawAThread) {
            return new Look(null, Reason.REQUEST_ONLY_UNRECOGNIZED_SOURCES, seen, newestAuxiliary);
        }
        return new Look(null, tail.olderUnread() ? Reason.REQUEST_BEYOND_READ_LIMIT : Reason.REQUEST_RECORD_NOT_FOUND, seen);
    }

    /** The model and effort of the caller's request. The hook's effort, which the host ties to the call, is not overridden by a request that names another. */
    private static Look observationOf(ClaudeEntry request, Caller caller, int lines) {
        Optional<String> model = request.requestModel();
        if (model.isEmpty()) {
            return new Look(null, Reason.REQUEST_MODEL_MISSING, lines);
        }
        String effort = request.requestEffort();
        if (caller.hookEffort() != null && effort != null && !caller.hookEffort().equals(effort)) {
            return new Look(null, Reason.REQUEST_EFFORT_MISMATCH, lines);
        }
        return new Look(new SelfSession.Execution.Observed(model.get(), effort, null, SelfSession.Execution.Observed.Evidence.REQUEST_RECORD), null, lines);
    }

    /** Whether a line of the caller's thread after the line at {@code index} opens a new turn: the answer to that request has ended. */
    private boolean newTurnAfter(List<TailLine> lines, int index, ClaudeEntry request, Caller caller, Path file) {
        for (int i = index + 1; i < lines.size(); i++) {
            if (files.entry(lines.get(i), file).filter(line -> caller.opensNewTurnAfter(request, line)).isPresent()) {
                return true;
            }
        }
        return false;
    }

    private static String cleaned(String source) {
        String clean = NOT_A_SOURCE_CHARACTER.matcher(source).replaceAll("?");
        return clean.length() > REQUEST_SOURCE_LIMIT ? clean.substring(0, REQUEST_SOURCE_LIMIT) : clean;
    }
}
