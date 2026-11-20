// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook.host;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.domain.transcript.Transcript;
import io.github.jaeseongs95.sennard.domain.transcript.TranscriptItem;
import io.github.jaeseongs95.sennard.domain.transcript.TranscriptLocation;
import io.github.jaeseongs95.sennard.domain.transcript.TranscriptLookup;
import io.github.jaeseongs95.sennard.domain.transcript.TranscriptQuery;
import io.github.jaeseongs95.sennard.domain.transcript.TranscriptRead;
import io.github.jaeseongs95.sennard.domain.transcript.UnreadableReason;

/**
 * Claude Code's transcript behind the vendor-neutral {@link Transcript}: the host keeps one JSON Lines file per session ({@code transcript_path} of its hooks), written as the
 * conversation goes. The format is read by {@link ClaudeTranscriptFile} and {@link ClaudeEntry}; this class holds the two rules that make it a transcript of a turn.
 *
 * <ul>
 * <li>{@link #locate}: the file is read from its start, a line at a time, for the first line that is the user's own message (a {@code user} line of the main thread that is not
 * injected by the host and does not carry tool results) holding exactly the text the archive kept (a cut prompt: starting with it), written no earlier than
 * {@value #WRITTEN_BEFORE_RECEIVED_SECONDS} seconds before the hook received the prompt. The time keeps the same words said twice in one session apart. The entry's
 * {@code uuid} is the position.</li>
 * <li>{@link #readAfter}: from the entry of that {@code uuid} to the next message of the user (the end of the turn), the main thread's lines only, each reduced to the shared
 * kinds: the agent's texts, its tool calls and the tools' results. The model's private reasoning is not one of them. Each entry carries its key (see
 * {@link ClaudeEntry#toItems}), and {@link #continueAfter} reads on from the entry of a key the same way, so that a turn longer than one read is read in pages.</li>
 * </ul>
 * A file that cannot be read is answered as {@code Unreadable} and logged as a warning with its cause; it is never turned into "not there yet".
 */
@Component
class ClaudeTranscript implements Transcript {
    private static final Logger LOG = LoggerFactory.getLogger(ClaudeTranscript.class);
    /** How long before the hook received the prompt its line may have been written: the host writes the line as the prompt is submitted, the hook runs after. */
    static final int WRITTEN_BEFORE_RECEIVED_SECONDS = 5;
    private static final Duration WRITTEN_BEFORE_RECEIVED = Duration.ofSeconds(WRITTEN_BEFORE_RECEIVED_SECONDS);
    /** The "key" of a read that starts at the beginning of the turn: no entry has an empty key to continue after (a public call to continue refuses one). */
    private static final String NOT_CONTINUING = "";

    private final ClaudeTranscriptFile files;

    ClaudeTranscript(ClaudeTranscriptFile files) {
        this.files = files;
    }

    @Override
    public String host() {
        return ClaudeTranscriptFile.HOST;
    }

    @Override
    public TranscriptLookup locate(TranscriptQuery query) {
        if (!ClaudeTranscriptFile.HOST.equals(query.host())) {
            return new TranscriptLookup.Unsupported(query.host());
        }
        Instant earliest = query.receivedAt().minus(WRITTEN_BEFORE_RECEIVED);
        try (ClaudeTranscriptFile.Reading reading = files.open(query.file())) {
            while (reading.advance()) {
                ClaudeEntry entry = reading.current();
                if (!entry.uuid().isEmpty() && entry.startsTurn() && !entry.writtenBefore(earliest) && entry.holdsPrompt(query.promptText(), query.truncated())) {
                    return new TranscriptLookup.Located(new TranscriptLocation(query.host(), query.sessionId(), query.file(), entry.uuid()));
                }
            }
            if (reading.heldNoEntry()) {
                return new TranscriptLookup.Unreadable(UnreadableReason.NOT_A_TRANSCRIPT);
            }
            return new TranscriptLookup.NotYetWritten();
        } catch (IOException failure) {
            LOG.warn("The transcript cannot be read: {}", query.file(), failure);
            return new TranscriptLookup.Unreadable(ClaudeTranscriptFile.reasonOf(failure));
        }
    }

    @Override
    public TranscriptRead readAfter(TranscriptLocation location, int limit) {
        return read(location, NOT_CONTINUING, limit);
    }

    @Override
    public TranscriptRead continueAfter(TranscriptLocation location, String entryKey, int limit) {
        if (entryKey.isEmpty()) {
            throw new IllegalArgumentException("an entry to continue after has a key");
        }
        return read(location, entryKey, limit);
    }

    /** @param afterKey the key of the entry to continue after, or {@link #NOT_CONTINUING} to read from the start of the turn */
    private TranscriptRead read(TranscriptLocation location, String afterKey, int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("a read answers from 1 to " + MAX_LIMIT + " entries");
        }
        if (!ClaudeTranscriptFile.HOST.equals(location.host())) {
            return new TranscriptRead.Unsupported(location.host());
        }
        try (ClaudeTranscriptFile.Reading reading = files.open(location.file())) {
            if (!seek(reading, location.entryKey())) {
                return new TranscriptRead.Unreadable(UnreadableReason.ENTRY_GONE);
            }
            return collect(reading, afterKey, limit);
        } catch (IOException failure) {
            LOG.warn("The transcript cannot be read: {}", location.file(), failure);
            return new TranscriptRead.Unreadable(ClaudeTranscriptFile.reasonOf(failure));
        }
    }

    /** Reads up to the entry of this {@code uuid}; false when the file ends before it. The key also appears on the lines of the entry's children, so a line is confirmed by its own {@code uuid}. */
    private static boolean seek(ClaudeTranscriptFile.Reading reading, String entryKey) throws IOException {
        while (reading.advanceToLineContaining(entryKey)) {
            if (entryKey.equals(reading.current().uuid())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The entries after the position, up to the user's next message or the limit. {@code more} is false only when that next message was reached. When the read continues after the
     * entry of a key, the entries up to and including that one are passed over; if the turn ends (or the file does) before that key was seen, the key is not in the turn.
     */
    private static TranscriptRead collect(ClaudeTranscriptFile.Reading reading, String afterKey, int limit) throws IOException {
        boolean passing = !NOT_CONTINUING.equals(afterKey);
        List<TranscriptItem> items = new ArrayList<>();
        while (reading.advance()) {
            ClaudeEntry line = reading.current();
            if (line.sidechain()) {
                continue;
            }
            if (line.startsTurn()) {
                return passing ? new TranscriptRead.NotInTurn(afterKey) : new TranscriptRead.Entries(items, false);
            }
            for (TranscriptItem item : line.toItems()) {
                if (passing) {
                    passing = !afterKey.equals(item.key());
                    continue;
                }
                if (items.size() == limit) {
                    return new TranscriptRead.Entries(items, true);
                }
                items.add(item);
            }
        }
        return passing ? new TranscriptRead.NotInTurn(afterKey) : new TranscriptRead.Entries(items, true);
    }
}
