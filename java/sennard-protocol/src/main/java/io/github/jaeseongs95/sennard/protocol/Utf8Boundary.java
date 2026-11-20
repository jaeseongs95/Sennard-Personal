// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.protocol;

import java.io.BufferedOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Sennard speaks UTF-8 at every boundary it defines; the two places where the operating system decides instead are here. In this
 * module because it is the one every process (service, client, store) already depends on.
 */
public final class Utf8Boundary {
    private Utf8Boundary() {
    }

    /**
     * Makes {@code System.out} and {@code System.err} UTF-8. The JVM otherwise encodes them in the system code page when they are
     * a pipe or a file (CP949 on a Korean Windows), and a caller reading them as UTF-8 gets replacement characters. Every
     * {@code main} calls this first; {@code EncodingBoundaryTest} keeps it so.
     */
    public static Streams fixStandardStreams() {
        Streams streams = new Streams(utf8(FileDescriptor.out), utf8(FileDescriptor.err));
        System.setOut(streams.out());
        System.setErr(streams.err());
        return streams;
    }

    /** The two UTF-8 streams {@link #fixStandardStreams} installed: a program that writes its result to the process's output takes them from here, and does not read the global streams. */
    public record Streams(PrintStream out, PrintStream err) {
    }

    private static PrintStream utf8(FileDescriptor descriptor) {
        return new PrintStream(new BufferedOutputStream(new FileOutputStream(descriptor), 8192), true, StandardCharsets.UTF_8);
    }

    /**
     * What an operating-system tool printed, as text. Windows tools (PowerShell, the PostgreSQL tools) write in the system's
     * code page, not UTF-8, and reading such bytes as UTF-8 either garbles them or, with a strict decoder, turned a finished initdb into
     * a failure. The text is only ever a diagnostic or an ASCII field, so a byte that does not decode becomes a replacement character.
     * The code page is handed in by the caller (which bytes are undecodable depends on it: 0xFF is a letter in windows-1252); this plain library has no Spring Environment
     * to ask and the JDK has no call that answers the host's code page, so it neither guesses nor reads a property.
     */
    public static String toolText(byte[] bytes, Charset codePage) {
        return new String(bytes, codePage);
    }
}
