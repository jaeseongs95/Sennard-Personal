// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One log file of an execution: the bytes of one output stream, written as they are read, up to {@code limit} bytes. What comes after the limit is not written and the file is marked
 * truncated; the program is not stopped for it. A write that fails (the disk is full, the folder was removed) ends the writing the same way: the failure is logged once, the file is
 * marked truncated and the bytes read from then on are dropped, so a failing disk neither stops the program nor makes the reader wait.
 *
 * <p>Nothing is buffered here: a reader of the file (a tool that shows the output of a running program) sees every byte that was read from the pipe. Its one writer is the reader of
 * the pipe ({@link ProcessOutput}), which closes it when the stream ends.
 */
final class LogFile implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(LogFile.class);

    private final Path path;
    private final long limit;
    private OutputStream out;
    private long written;
    private boolean truncated;

    private LogFile(Path path, long limit, OutputStream out) {
        this.path = path;
        this.limit = limit;
        this.out = out;
    }

    /**
     * Makes the file; it must not exist.
     *
     * @throws IOException when the file cannot be made
     */
    static LogFile create(Path path, long limit) throws IOException {
        return new LogFile(path, limit, Files.newOutputStream(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE));
    }

    /** Writes the first {@code length} bytes of {@code data}, or as many of them as the limit and the disk allow. */
    synchronized void write(byte[] data, int length) {
        if (length == 0) {
            return;
        }
        if (out == null) {
            truncated = true;
            return;
        }
        int take = (int) Math.min(limit - written, length);
        if (take > 0) {
            try {
                out.write(data, 0, take);
                written += take;
            } catch (IOException failed) {
                LOG.warn("The log file {} could not be written after {} bytes; the rest of the output is not logged.", path, written, failed);
                closeStream();
                truncated = true;
                return;
            }
        }
        if (take < length) {
            truncated = true;
        }
    }

    /** Whether some of what was read is not in the file. */
    synchronized boolean truncated() {
        return truncated;
    }

    @Override
    public synchronized void close() {
        closeStream();
    }

    private void closeStream() {
        if (out == null) {
            return;
        }
        try {
            out.close();
        } catch (IOException failed) {
            LOG.warn("The log file {} could not be closed.", path, failed);
            truncated = true;
        } finally {
            out = null;
        }
    }
}
