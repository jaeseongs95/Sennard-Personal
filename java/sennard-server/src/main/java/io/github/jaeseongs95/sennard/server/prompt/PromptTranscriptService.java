// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.prompt;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.github.jaeseongs95.sennard.app.prompt.ArchivedSession;
import io.github.jaeseongs95.sennard.app.prompt.PromptArchiveReader;
import io.github.jaeseongs95.sennard.app.prompt.PromptSource;
import io.github.jaeseongs95.sennard.app.prompt.TranscriptAnchorService;
import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.domain.error.InputCode;
import io.github.jaeseongs95.sennard.domain.error.InvalidInputException;
import io.github.jaeseongs95.sennard.domain.transcript.Transcript;
import io.github.jaeseongs95.sennard.domain.transcript.TranscriptAccess;
import io.github.jaeseongs95.sennard.domain.transcript.TranscriptEntry;
import io.github.jaeseongs95.sennard.domain.transcript.TranscriptItem;
import io.github.jaeseongs95.sennard.domain.transcript.TranscriptLocation;
import io.github.jaeseongs95.sennard.domain.transcript.TranscriptLookup;
import io.github.jaeseongs95.sennard.domain.transcript.TranscriptQuery;
import io.github.jaeseongs95.sennard.domain.transcript.TranscriptRead;
import io.github.jaeseongs95.sennard.domain.transcript.UnreadableReason;
import io.github.jaeseongs95.sennard.server.mcp.SelfSession;
import io.github.jaeseongs95.sennard.server.message.MessageRef;
import io.github.jaeseongs95.sennard.server.message.Messages;

/**
 * {@code read_prompt_transcript}: what the agent did after one of the user's messages, read from the host's own transcript, for the session that heard the message and for no
 * other. The adapter between the tool and the transcript boundary ({@link Transcript}): it takes the prompt's row from the archive (the host, the session, the text and the
 * transcript path the hook stored: nothing of the file's location comes from the caller), asks the pure policy whether this caller may read it ({@link TranscriptAccess}),
 * finds the entry (the first time by searching the transcript, afterwards from the position the archive kept, {@link TranscriptAnchorService}) and reads the entries after it.
 *
 * <p>The answer says which of four things happened ({@link Status}); a host this server does not read, a transcript that is not there yet and a transcript that cannot be read are
 * told apart, not answered with nothing. Nothing of the transcript is stored: only the position is. The archive is read and the anchor written in their own use cases, each one transaction; the file
 * is read outside any of them. This class cannot write a prompt: it has no way to reach the writer, and the machine check ({@code PromptArchiveRules}) holds the tool to it.
 */
@Service
public class PromptTranscriptService {
    private static final Logger LOG = LoggerFactory.getLogger(PromptTranscriptService.class);

    /** What a read found; the wire shows the names. */
    public enum Status {
        /** The prompt's entry was found; {@code entries} are what follows it. */
        LOCATED,
        /** The transcript is there but has no entry of this prompt yet. */
        NOT_YET_WRITTEN,
        /** This server reads no transcript of the prompt's host. */
        UNSUPPORTED,
        /** The transcript cannot be read; {@code reason} says why. */
        UNREADABLE
    }

    /** The kind of an entry, as the wire names it. */
    public enum EntryKind {
        USER_MESSAGE, AGENT_MESSAGE, TOOL_CALL, TOOL_RESULT
    }

    /** One entry of the answer, with the fields of its kind; {@code key} is the host's identifier of the entry, which the next call names as {@code after} to read on from it. */
    public sealed interface EntryView {
        /** A message of the user or of the agent; {@code truncated} when its text was cut at 2000 characters. */
        record MessageView(EntryKind kind, String key, String text, boolean truncated) implements EntryView {
        }

        record ToolCallView(EntryKind kind, String key, String callId, String toolName) implements EntryView {
        }

        record ToolResultView(EntryKind kind, String key, String callId, String text, boolean truncated) implements EntryView {
        }
    }

    /**
     * What the tool answers.
     *
     * @param promptId the prompt asked about
     * @param host the host the prompt was said in
     * @param status what was found
     * @param entries what follows the prompt, in order; empty unless {@code LOCATED}
     * @param more whether the turn goes on beyond these entries (the limit stopped the read, or the transcript ends before the user's next message)
     * @param reason why the transcript is unsupported or unreadable; absent otherwise
     */
    public record Transcribed(String promptId, String host, Status status, List<EntryView> entries, boolean more, @JsonInclude(JsonInclude.Include.NON_NULL) String reason) {
    }

    static final String KEY_UNSUPPORTED = "server.promptTranscript.unsupported";
    static final String KEY_NONE_NAMED = "server.promptTranscript.unreadable.noneNamed";
    static final String KEY_PATH_NOT_LOCAL = "server.promptTranscript.unreadable.pathNotLocal";

    private final PromptArchiveReader archive;
    private final TranscriptAnchorService anchors;
    private final TranscriptHosts hosts;
    private final ArchiveSessionKeys keys;
    private final Messages messages;

    PromptTranscriptService(PromptArchiveReader archive, TranscriptAnchorService anchors, TranscriptHosts hosts, ArchiveSessionKeys keys, Messages messages) {
        this.archive = archive;
        this.anchors = anchors;
        this.hosts = hosts;
        this.keys = keys;
        this.messages = messages;
    }

    /**
     * @param limit the most entries to answer; null is {@link Transcript#DEFAULT_LIMIT}
     * @param after the key of the entry (from an earlier answer for this prompt) to read on after; null reads from the start of the turn
     * @throws SennardException {@link InputCode#PROMPT_NOT_FOUND} when the archive has no prompt of this identifier, {@link InputCode#TRANSCRIPT_OTHER_SESSION} when it was said in another
     *     session, {@link InputCode#PROMPT_SESSION_UNHOOKED} when the calling session is not one the hooks observe, {@link InputCode#TRANSCRIPT_AFTER_NOT_IN_TURN} when {@code after} is
     *     not an entry of this prompt's turn
     */
    public Transcribed read(SelfSession session, String promptId, Integer limit, String after) {
        ArchivedSession caller = keys.of(session);
        PromptSource source = archive.source(promptId).orElseThrow(() -> new InvalidInputException(InputCode.PROMPT_NOT_FOUND));
        switch (new TranscriptAccess(caller.host(), caller.sessionId()).decide(source.host(), source.sessionId())) {
            case TranscriptAccess.Decision.Granted granted -> {
                // The caller is the session the prompt was said in.
            }
            case TranscriptAccess.Decision.Refused refused -> throw new InvalidInputException(InputCode.TRANSCRIPT_OTHER_SESSION, SennardException.args(), SennardException.args("reason", refused.reason().name()));
        }
        if (!hosts.reads(source.host())) {
            return unsupported(promptId, source.host());
        }
        int size = limit == null ? Transcript.DEFAULT_LIMIT : limit;
        return switch (fileOf(source)) {
            case TranscriptFile.Named named -> readFrom(source, named.path(), size, after);
            case TranscriptFile.NoneNamed none -> unreadable(promptId, source.host(), KEY_NONE_NAMED);
            case TranscriptFile.NotLocal notLocal -> unreadable(promptId, source.host(), KEY_PATH_NOT_LOCAL);
        };
    }

    /** Finds the prompt's entry (from the kept position, or by searching the file the first time) and reads what follows it, or what follows the entry of the key {@code after} when it is not null. */
    private Transcribed readFrom(PromptSource source, Path file, int size, String after) {
        String promptId = source.userPromptId();
        Optional<String> kept = anchors.find(promptId);
        TranscriptLookup lookup = kept.<TranscriptLookup>map(key -> new TranscriptLookup.Located(new TranscriptLocation(source.host(), source.sessionId(), file, key)))
                .orElseGet(() -> hosts.locate(new TranscriptQuery(source.host(), source.sessionId(), file, source.promptText(), source.receivedAt(), source.truncated())));
        return switch (lookup) {
            case TranscriptLookup.Located located -> {
                TranscriptLocation location = located.location();
                if (kept.isEmpty()) {
                    // The first position found is kept: when the prompt has one by now, that one is read.
                    location = new TranscriptLocation(location.host(), location.sessionId(), location.file(), anchors.remember(promptId, location.entryKey()));
                }
                yield answer(promptId, source.host(), after == null ? hosts.readAfter(location, size) : hosts.continueAfter(location, after, size));
            }
            case TranscriptLookup.NotYetWritten notYet -> new Transcribed(promptId, source.host(), Status.NOT_YET_WRITTEN, List.of(), false, null);
            case TranscriptLookup.Unsupported unsupported -> unsupported(promptId, unsupported.host());
            case TranscriptLookup.Unreadable unreadable -> unreadable(promptId, source.host(), keyOf(unreadable.reason()));
        };
    }

    /** What the archive's row of a prompt says about its transcript file: a path on this computer, no path at all, or a text that is no path here. */
    sealed interface TranscriptFile {
        /** The host named a file, and the text is a path on this computer. */
        record Named(Path path) implements TranscriptFile {
        }

        /** The host named no file for this prompt. */
        record NoneNamed() implements TranscriptFile {
        }

        /** The host named a file, but the stored text is not a path on this computer. */
        record NotLocal() implements TranscriptFile {
        }
    }

    /** The file the host named for the prompt, from the archive's row only. */
    static TranscriptFile fileOf(PromptSource source) {
        String stored = source.transcriptPath();
        if (stored == null || stored.isBlank()) {
            return new TranscriptFile.NoneNamed();
        }
        try {
            return new TranscriptFile.Named(Path.of(stored));
        } catch (InvalidPathException invalid) {
            LOG.warn("The transcript path stored for a prompt is not a path on this computer: promptId={}", source.userPromptId(), invalid);
            return new TranscriptFile.NotLocal();
        }
    }

    private Transcribed answer(String promptId, String host, TranscriptRead read) {
        return switch (read) {
            case TranscriptRead.Entries entries -> new Transcribed(promptId, host, Status.LOCATED, entries.items().stream().map(PromptTranscriptService::view).toList(), entries.more(), null);
            case TranscriptRead.Unsupported unsupported -> unsupported(promptId, unsupported.host());
            case TranscriptRead.Unreadable unreadable -> unreadable(promptId, host, keyOf(unreadable.reason()));
            case TranscriptRead.NotInTurn notInTurn -> throw new InvalidInputException(InputCode.TRANSCRIPT_AFTER_NOT_IN_TURN, SennardException.args(), SennardException.args("after", notInTurn.entryKey()));
        };
    }

    private Transcribed unsupported(String promptId, String host) {
        return new Transcribed(promptId, host, Status.UNSUPPORTED, List.of(), false, messages.render(MessageRef.of(KEY_UNSUPPORTED, "host", host)));
    }

    /** @param messageKey the catalog key of the sentence that says why */
    private Transcribed unreadable(String promptId, String host, String messageKey) {
        return new Transcribed(promptId, host, Status.UNREADABLE, List.of(), false, messages.text(messageKey));
    }

    /** The catalog key of the sentence for a reason; a new reason does not compile until it has one. */
    static String keyOf(UnreadableReason reason) {
        return switch (reason) {
            case NOT_A_TRANSCRIPT -> "server.promptTranscript.unreadable.notATranscript";
            case ENTRY_GONE -> "server.promptTranscript.unreadable.entryGone";
            case FILE_MISSING -> "server.promptTranscript.unreadable.fileMissing";
            case FILE_DENIED -> "server.promptTranscript.unreadable.fileDenied";
            case FILE_IO -> "server.promptTranscript.unreadable.fileIo";
        };
    }

    private static EntryView view(TranscriptItem item) {
        String key = item.key();
        return switch (item.entry()) {
            case TranscriptEntry.UserMessage message -> new EntryView.MessageView(EntryKind.USER_MESSAGE, key, message.text(), message.truncated());
            case TranscriptEntry.AgentMessage message -> new EntryView.MessageView(EntryKind.AGENT_MESSAGE, key, message.text(), message.truncated());
            case TranscriptEntry.ToolCall call -> new EntryView.ToolCallView(EntryKind.TOOL_CALL, key, call.callId(), call.toolName());
            case TranscriptEntry.ToolResult result -> new EntryView.ToolResultView(EntryKind.TOOL_RESULT, key, result.callId(), result.text(), result.truncated());
        };
    }
}
