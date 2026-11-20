// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook.host;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.domain.transcript.UnreadableReason;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one place a Claude Code transcript file is read: the JSON Lines of the file, the entry each line is ({@link ClaudeEntry}), and the end of a file. The two readers of the
 * transcript, the one that finds the model behind a tool call ({@link ClaudeTranscriptExecutionReader}) and the one that reads a turn back for the agent
 * ({@link ClaudeTranscript}), both go through here, so a change of what the host writes is changed once.
 *
 * <p>A file is read front to back one line at a time ({@link Reading}); the whole file is never held in memory. A line that is not JSON is skipped and counted, since a
 * transcript that is being written may end in half a line; what a caller makes of a file with nothing but such lines is the caller's to say.
 */
@Component
class ClaudeTranscriptFile {
    private static final Logger LOG = LoggerFactory.getLogger(ClaudeTranscriptFile.class);
    /** The name the sessions of this host are stored under. */
    static final String HOST = ClaudeHost.ID.sessionHost();
    private static final int TAIL_BYTES = 2 * 1024 * 1024;
    private static final int TAIL_LINES = 4000;

    private final JsonMapper mapper;

    ClaudeTranscriptFile(JsonMapper mapper) {
        this.mapper = mapper;
    }

    /** Opens the file for a read from its first line. */
    Reading open(Path file) throws IOException {
        return new Reading(file, new BufferedReader(new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8)));
    }

    /**
     * The entry a line is, or none for a line that is blank or not a JSON object (the second kind is logged and counted by the caller's {@link Reading}).
     *
     * @param file only for the log
     */
    Optional<ClaudeEntry> parse(String line, Path file) {
        if (line.isBlank()) {
            return Optional.empty();
        }
        JsonNode node;
        try {
            node = mapper.readTree(line);
        } catch (JacksonException notJson) {
            LOG.debug("a line of the transcript that is not JSON is skipped: {}", file, notJson);
            return Optional.empty();
        }
        if (!node.isObject()) {
            LOG.debug("a line of the transcript that is not a JSON object is skipped: {}", file);
            return Optional.empty();
        }
        return Optional.of(new ClaudeEntry(node, instantOf(node, file)));
    }

    /** The last lines of the file, at most {@code TAIL_LINES} of at most the last {@code TAIL_BYTES} bytes: where a call that was just made is written. */
    List<String> tail(Path file) throws IOException {
        try (RandomAccessFile in = new RandomAccessFile(file.toFile(), "r")) {
            long size = in.length();
            int length = (int) Math.min(size, TAIL_BYTES);
            byte[] buffer = new byte[length];
            in.seek(size - length);
            in.readFully(buffer);
            String content = new String(buffer, StandardCharsets.UTF_8);
            if (size > length) {
                int newline = content.indexOf('\n');
                content = newline < 0 ? "" : content.substring(newline + 1);
            }
            List<String> lines = content.lines().toList();
            return lines.size() > TAIL_LINES ? lines.subList(lines.size() - TAIL_LINES, lines.size()) : lines;
        }
    }

    /** Why a file cannot be read, as a value (the words are the server's catalog; the cause is logged by the reader that failed). */
    static UnreadableReason reasonOf(IOException failure) {
        return switch (failure) {
            case NoSuchFileException missing -> UnreadableReason.FILE_MISSING;
            case AccessDeniedException denied -> UnreadableReason.FILE_DENIED;
            default -> UnreadableReason.FILE_IO;
        };
    }

    private static Instant instantOf(JsonNode node, Path file) {
        JsonNode stamp = node.path("timestamp");
        if (!stamp.isString()) {
            return null;
        }
        try {
            return Instant.parse(stamp.stringValue());
        } catch (DateTimeParseException notAnInstant) {
            LOG.debug("a line of the transcript with a timestamp that is not an instant: {}", file, notAnInstant);
            return null;
        }
    }

    /**
     * One pass over a file, front to back, in the manner of a result set: {@link #advance()} moves to the next entry and {@link #current()} is it. Closing it closes the file.
     */
    final class Reading implements AutoCloseable {
        private final Path file;
        private final BufferedReader reader;
        private ClaudeEntry current;
        private int entries;
        private int skipped;

        private Reading(Path file, BufferedReader reader) {
            this.file = file;
            this.reader = reader;
        }

        /** Moves to the next line that is an entry; blank lines and lines that are not JSON are skipped. False at the end of the file. */
        boolean advance() throws IOException {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                if (take(line)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Moves to the next line that holds this text and is an entry. A line without the text is passed over without being parsed (a long file is mostly lines that are not
         * wanted), so such lines are not counted. False at the end of the file.
         */
        boolean advanceToLineContaining(String fragment) throws IOException {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                if (line.contains(fragment) && take(line)) {
                    return true;
                }
            }
            return false;
        }

        /** The entry {@link #advance()} moved to. */
        ClaudeEntry current() {
            return current;
        }

        /** Whether the file held lines that were read and none of them was an entry: a file that is not a transcript. */
        boolean heldNoEntry() {
            return entries == 0 && skipped > 0;
        }

        private boolean take(String line) {
            Optional<ClaudeEntry> entry = parse(line, file);
            entry.ifPresent(parsed -> current = parsed);
            if (entry.isPresent()) {
                entries++;
                return true;
            }
            if (!line.isBlank()) {
                skipped++;
            }
            return false;
        }

        @Override
        public void close() throws IOException {
            reader.close();
        }
    }
}
