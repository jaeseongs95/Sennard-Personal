// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.execution;

import java.util.Objects;

/**
 * What the server keeps in memory of one output stream of an execution: the first {@value #HEAD_BYTES} bytes and the last {@value #TAIL_BYTES}, as UTF-8 text (malformed bytes are
 * replaced). The whole output is in the execution's log file, up to its limit.
 *
 * <p>When nothing was left out, {@code head} is the whole output and {@code tail} is empty; the two are never decoded apart in that case, so a character is not cut where they meet.
 * When something was left out ({@code omittedBytes} is more than 0), {@code head} and {@code tail} are the two ends and the middle is only in the log file.
 *
 * @param head the first bytes (the whole output when {@code omittedBytes} is 0)
 * @param tail the last bytes; empty when nothing was left out
 * @param totalBytes how many bytes the program wrote to this stream
 * @param omittedBytes how many bytes are in neither {@code head} nor {@code tail}
 * @param logTruncated whether the log file stopped taking this stream's bytes at its limit (the program was not stopped for it; the bytes after the limit are only in {@code tail})
 */
public record StreamOutput(String head, String tail, long totalBytes, long omittedBytes, boolean logTruncated) {
    /** How many bytes from the start of a stream are kept in memory. */
    public static final int HEAD_BYTES = 64 * 1024;
    /** How many bytes from the end of a stream are kept in memory. */
    public static final int TAIL_BYTES = 256 * 1024;
    /** A stream that was never written to, or that belongs to an execution that was not started. */
    public static final StreamOutput EMPTY = new StreamOutput("", "", 0, 0, false);

    public StreamOutput {
        Objects.requireNonNull(head, "head");
        Objects.requireNonNull(tail, "tail");
        if (totalBytes < 0 || omittedBytes < 0 || omittedBytes > totalBytes) {
            throw new IllegalArgumentException("The byte counts of an output are inconsistent: total " + totalBytes + ", omitted " + omittedBytes);
        }
    }

    /** Whether some of the output is in neither end kept in memory. */
    public boolean omitted() {
        return omittedBytes > 0;
    }
}
