// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.wake;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.function.Supplier;

/** LF-delimited bytes over borrowed process streams. The connection owns the process lifetime. */
final class ManagedStreamFrames {
    private static final int MAX_FRAME_BYTES = 1024 * 1024;
    private final BufferedInputStream source;
    private final OutputStream sink;
    private final String limitMessage;
    private final Supplier<IOException> incompleteFrame;

    // Each process exchange has its own streams and protocol diagnostics.
    ManagedStreamFrames(InputStream source, OutputStream sink, String limitMessage, Supplier<IOException> incompleteFrame) {
        this.source = new BufferedInputStream(source);
        this.sink = sink;
        this.limitMessage = limitMessage;
        this.incompleteFrame = incompleteFrame;
    }

    /** Returns null on clean EOF and an empty array for a blank line. */
    byte[] read() throws IOException {
        var frame = new ByteArrayOutputStream();
        for (int octet = source.read(); octet != -1; octet = source.read()) {
            if (octet == '\n') {
                return frame.toByteArray();
            }
            if (frame.size() == MAX_FRAME_BYTES) {
                throw new IOException(limitMessage);
            }
            frame.write(octet);
        }
        if (frame.size() != 0) {
            throw incompleteFrame.get();
        }
        return null;
    }

    void write(byte[] frame) throws IOException {
        sink.write(frame);
        sink.write('\n');
        sink.flush();
    }
}
