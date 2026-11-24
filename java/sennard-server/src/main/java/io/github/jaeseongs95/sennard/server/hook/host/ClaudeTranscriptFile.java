// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook.host;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

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
 * <p>The end of a file is read by {@link #tail}: the last lines inside a fixed window, and the one longer line that begins before it only for the few fields a reader of requests relies on
 * ({@link ClaudeLineDigest}), never whole.
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
    /** The longest line that is read at all when it is longer than the end of the file that is read whole; the host's request line holds the conversation sent. */
    static final long OVERSIZED_LINE_BYTES = 32L * 1024 * 1024;
    private static final int SCAN_BYTES = 64 * 1024;

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

    /**
     * One line of the end of a file. A line is held whole ({@link #text}) when it lies inside what is read of the end; the one line that begins before it (a request line holds the
     * conversation sent, which can be longer than that) is not cut off but read once, from its own beginning, for the small fields of it that a reader relies on
     * ({@link ClaudeLineDigest}, {@link #digest}). A line that is longer than {@code OVERSIZED_LINE_BYTES} is {@link #tooLarge}; one whose digest could not be read (it is still
     * being written, or is not JSON) is {@link #unreadable}.
     */
    record TailLine(String text, JsonNode digest, boolean tooLarge) {
        static TailLine whole(String text) {
            return new TailLine(text, null, false);
        }

        static TailLine digested(JsonNode digest) {
            return new TailLine(null, digest, false);
        }

        static TailLine unreadable() {
            return new TailLine(null, null, false);
        }

        static TailLine oversized() {
            return new TailLine(null, null, true);
        }

        /** Whether the line is empty or blank. A line that was not held whole is not blank. */
        boolean blank() {
            return text != null && text.isBlank();
        }

        /** Whether the text of the line holds the fragment: a cheap test before a line is parsed. A digest holds the fields it kept, so those are what it can be asked. */
        boolean mentions(String fragment) {
            if (text != null) {
                return text.contains(fragment);
            }
            return digest != null && digest.toString().contains(fragment);
        }

        /** Whether the line is held but cannot be read as an entry. */
        boolean unreadableEntry() {
            return text == null && digest == null && !tooLarge;
        }
    }

    /**
     * The end of a file.
     *
     * @param lines the lines of the end, oldest first
     * @param olderUnread whether the file holds lines older than these that were not read, because of the limits of what is read
     */
    record Tail(List<TailLine> lines, boolean olderUnread) {
    }

    /** The entry of a line of {@link #tail}, or none for a line that is not one. */
    Optional<ClaudeEntry> entry(TailLine line, Path file) {
        if (line.text() != null) {
            return parse(line.text(), file);
        }
        return Optional.ofNullable(line.digest()).map(digest -> new ClaudeEntry(digest, instantOf(digest, file)));
    }

    /**
     * The end of the file: the last lines of at most the last {@code TAIL_BYTES} bytes, at most {@code TAIL_LINES} of them (where a call that was just made is written), and the line
     * that begins before them, if one does. Never more than the window and one line of at most {@code OVERSIZED_LINE_BYTES} is read, and a line is never held whole beyond the window.
     */
    Tail tail(Path file) throws IOException {
        try (FileChannel in = FileChannel.open(file, StandardOpenOption.READ)) {
            long size = in.size();
            int length = (int) Math.min(size, TAIL_BYTES);
            long windowStart = size - length;
            byte[] window = readAt(in, windowStart, length);
            boolean beginsInsideALine = windowStart > 0 && readAt(in, windowStart - 1, 1)[0] != '\n';
            TailLine partial = null;
            long oldestRead = windowStart;
            int skip = 0;
            if (beginsInsideALine) {
                int newline = indexOfNewline(window);
                long lineEnd = newline < 0 ? size : windowStart + newline;
                long lineStart = lineStartBefore(in, windowStart);
                partial = lineStart < 0 || lineEnd - lineStart > OVERSIZED_LINE_BYTES ? TailLine.oversized() : digestOf(in, lineStart, lineEnd - lineStart);
                oldestRead = lineStart < 0 ? windowStart : lineStart;
                skip = newline < 0 ? length : newline + 1;
            }
            List<TailLine> lines = new ArrayList<>();
            if (partial != null) {
                lines.add(partial);
            }
            for (String line : new String(window, skip, length - skip, StandardCharsets.UTF_8).lines().toList()) {
                lines.add(TailLine.whole(line));
            }
            boolean olderUnread = oldestRead > 0;
            if (lines.size() > TAIL_LINES) {
                lines = new ArrayList<>(lines.subList(lines.size() - TAIL_LINES, lines.size()));
                olderUnread = true;
            }
            return new Tail(List.copyOf(lines), olderUnread);
        }
    }

    /** The folders under a folder, in name order: where the runs of a workflow keep the files of their subagents. */
    List<Path> subfolders(Path folder) throws IOException {
        try (Stream<Path> entries = Files.list(folder)) {
            return entries.filter(Files::isDirectory).sorted().toList();
        }
    }

    private TailLine digestOf(FileChannel in, long from, long length) throws IOException {
        in.position(from);
        InputStream line = new BoundedStream(Channels.newInputStream(in), length);
        return ClaudeLineDigest.of(mapper, line).map(TailLine::digested).orElseGet(TailLine::unreadable);
    }

    /** Where the line that holds the byte before {@code position} begins: the byte after the newline before it, or 0; -1 when that is more than {@code OVERSIZED_LINE_BYTES} back. */
    private static long lineStartBefore(FileChannel in, long position) throws IOException {
        long end = position;
        while (end > 0 && position - end <= OVERSIZED_LINE_BYTES) {
            int chunk = (int) Math.min(SCAN_BYTES, end);
            byte[] bytes = readAt(in, end - chunk, chunk);
            for (int i = chunk - 1; i >= 0; i--) {
                if (bytes[i] == '\n') {
                    return end - chunk + i + 1;
                }
            }
            end -= chunk;
        }
        return end == 0 ? 0 : -1;
    }

    private static byte[] readAt(FileChannel in, long position, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length);
        while (buffer.hasRemaining()) {
            int read = in.read(buffer, position + buffer.position());
            if (read < 0) {
                throw new EOFException("the file is shorter than it was");
            }
        }
        return buffer.array();
    }

    private static int indexOfNewline(byte[] bytes) {
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == '\n') {
                return i;
            }
        }
        return -1;
    }

    /** The first {@code limit} bytes of a stream: a line of a file is read to its end and not past it. */
    private static final class BoundedStream extends FilterInputStream {
        private long remaining;

        BoundedStream(InputStream in, long limit) {
            super(in);
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int value = super.read();
            if (value >= 0) {
                remaining--;
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int read = super.read(buffer, offset, (int) Math.min(length, remaining));
            if (read > 0) {
                remaining -= read;
            }
            return read;
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
