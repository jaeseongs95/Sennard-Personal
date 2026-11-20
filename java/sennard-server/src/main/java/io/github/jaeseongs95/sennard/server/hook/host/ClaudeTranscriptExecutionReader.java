// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook.host;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.server.hook.HostContext;
import io.github.jaeseongs95.sennard.server.mcp.SelfSession;
import io.github.jaeseongs95.sennard.server.mcp.SessionExecutionReader;

/**
 * When the host's hook omits the model, the assistant message that issued the call can identify it in the session's transcript. The
 * message may still be streaming when the call arrives, so the read waits for it. The file's lines are read by {@link ClaudeTranscriptFile}, the same reader that
 * {@link ClaudeTranscript} reads a turn back with.
 *
 * <p>When the model is not found the answer is an {@link SelfSession.Execution.Unobserved} that says which of the {@link Reason}s the last look ended on, how long the read waited
 * and how many lines it saw. Only codes and numbers are given out, never a line of the transcript.
 */
@Component
class ClaudeTranscriptExecutionReader implements SessionExecutionReader, ServerContextOnly {
    private static final Logger LOG = LoggerFactory.getLogger(ClaudeTranscriptExecutionReader.class);
    private static final Duration MAX_WAIT = Duration.ofSeconds(5);
    private static final Duration POLL = Duration.ofMillis(100);
    private static final String DEFAULT_UNOBSERVED = "HOOK_MODEL_NOT_REPORTED";

    /** Why the model was not found, ordered from the furthest the look got to the least. */
    enum Reason {
        /** The line that issued the call is there, in this thread, and names no model. */
        MODEL_FIELD_MISSING,
        /** The line that issued the call is there but is another thread's (another session, or a subagent's for the main agent and the reverse). */
        OTHER_THREAD,
        /** The transcript was read and no assistant line issues the call. */
        CALL_LINE_NOT_FOUND,
        /** The transcript is not a file or could not be read. */
        TRANSCRIPT_UNREADABLE,
        /** The hook gave no transcript path. */
        TRANSCRIPT_PATH_MISSING,
        /** The host gave no id for the call. */
        CALL_ID_MISSING
    }

    /** Waits between two looks; replaced by a test so that no time passes. */
    @FunctionalInterface
    interface Pause {
        void pause(Duration duration) throws InterruptedException;
    }

    /** One look at one file: what it found, or why not, and how many lines it saw. */
    private record Look(SelfSession.Execution.Observed observed, Reason reason, int lines) {
        Optional<SelfSession.Execution.Observed> found() {
            return Optional.ofNullable(observed);
        }
    }

    private final ClaudeTranscriptFile files;
    private final Clock clock;
    private final Pause pause;
    private final Duration maxWait;

    @Autowired
    ClaudeTranscriptExecutionReader(ClaudeTranscriptFile files, Clock clock) {
        this(files, clock, Thread::sleep, MAX_WAIT);
    }

    ClaudeTranscriptExecutionReader(ClaudeTranscriptFile files, Clock clock, Pause pause, Duration maxWait) {
        this.files = files;
        this.clock = clock;
        this.pause = pause;
        this.maxWait = maxWait;
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
            return notFound(unobserved, Reason.TRANSCRIPT_PATH_MISSING, 0, 0);
        }
        if (session.callId() == null) {
            return notFound(unobserved, Reason.CALL_ID_MISSING, 0, 0);
        }
        String sessionId = session.hostSessionId() != null ? session.hostSessionId() : session.sessionRef();
        String agentId = session.actor().role() == HostContext.Role.CHILD ? session.actor().agentKey() : null;
        Path transcript = Path.of(session.transcriptPath());
        List<Path> candidates = new ArrayList<>(List.of(transcript));
        if (agentId != null) {
            candidates.add(transcript.resolveSibling(sessionId).resolve("subagents").resolve("agent-" + agentId + ".jsonl"));
        }
        SelfSession.Execution execution = search(candidates, session.callId(), sessionId, agentId, unobserved);
        if (execution instanceof SelfSession.Execution.Observed issuer && issuer.effort() == null && pending.effort() != null) {
            return new SelfSession.Execution.Observed(issuer.model(), pending.effort(), issuer.turnId(), "transcript-tool-use+hook");
        }
        return execution;
    }

    private SelfSession.Execution search(List<Path> candidates, String callId, String sessionId, String agentId, String unobserved) {
        long started = clock.millis();
        long deadline = started + maxWait.toMillis();
        while (true) {
            Reason furthest = Reason.TRANSCRIPT_UNREADABLE;
            int lines = 0;
            for (Path candidate : candidates) {
                Look look = findIn(candidate, callId, sessionId, agentId);
                if (look.found().isPresent()) {
                    return look.found().get();
                }
                lines += look.lines();
                if (look.reason().compareTo(furthest) < 0) {
                    furthest = look.reason();
                }
            }
            long now = clock.millis();
            if (now >= deadline) {
                return notFound(unobserved, furthest, now - started, lines);
            }
            try {
                pause.pause(POLL);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return notFound(unobserved, furthest, clock.millis() - started, lines);
            }
        }
    }

    private static SelfSession.Execution notFound(String unobserved, Reason reason, long waitedMs, int lines) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("transcriptReason", reason.name());
        facts.put("waitedMs", waitedMs);
        facts.put("linesSeen", lines);
        LOG.warn("the model behind the call was not found in the transcript: transcriptReason={} waitedMs={} linesSeen={}", reason, waitedMs, lines);
        return new SelfSession.Execution.Unobserved(unobserved, facts);
    }

    /** ceiling: an unreadable transcript is the same as an absent issuer: the call stays unobserved and the caller is told it needs an attestation. */
    private Look findIn(Path file, String callId, String sessionId, String agentId) {
        if (!Files.isRegularFile(file)) {
            return new Look(null, Reason.TRANSCRIPT_UNREADABLE, 0);
        }
        List<String> lines;
        try {
            lines = files.tail(file);
        } catch (IOException unreadable) {
            LOG.debug("the transcript cannot be read, so the call stays unobserved: {}", file, unreadable);
            return new Look(null, Reason.TRANSCRIPT_UNREADABLE, 0);
        }
        for (int i = lines.size() - 1; i >= 0; i--) {
            String line = lines.get(i);
            if (line.isEmpty() || !line.contains(callId)) {
                continue;
            }
            Optional<ClaudeEntry> issuer = files.parse(line, file).filter(entry -> entry.kind() == ClaudeEntry.Kind.ASSISTANT && entry.issuesToolUse(callId));
            if (issuer.isPresent()) {
                // The first line from the end that issued the call is the one: whether it is this thread's and names a model decides, and nothing further back is looked at.
                return observation(issuer.get(), sessionId, agentId, lines.size());
            }
        }
        return new Look(null, Reason.CALL_LINE_NOT_FOUND, lines.size());
    }

    private static Look observation(ClaudeEntry entry, String sessionId, String agentId, int lines) {
        boolean wrongThread = entry.sessionId().filter(entrySession -> !entrySession.equals(sessionId)).isPresent() || (agentId != null ? !agentId.equals(entry.agentId()) : entry.sidechain());
        if (wrongThread) {
            return new Look(null, Reason.OTHER_THREAD, lines);
        }
        return entry.model().map(model -> new Look(new SelfSession.Execution.Observed(model, entry.effort(), null, "transcript-tool-use"), null, lines))
                .orElseGet(() -> new Look(null, Reason.MODEL_FIELD_MISSING, lines));
    }
}
