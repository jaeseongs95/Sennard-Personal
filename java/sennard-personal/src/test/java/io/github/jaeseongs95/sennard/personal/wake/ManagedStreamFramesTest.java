// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.wake;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;

import org.junit.jupiter.api.Test;

class ManagedStreamFramesTest {
    @Test
    void acceptsOneMebibyteOfUtf8BytesFollowedByLf() throws IOException {
        String payload = "é".repeat(512 * 1024);
        var frames = frames(payload + "\nnext\n", OutputStream.nullOutputStream());

        assertArrayEquals(payload.getBytes(UTF_8), frames.read());
        assertArrayEquals("next".getBytes(UTF_8), frames.read());
        assertNull(frames.read());
    }

    @Test
    void rejectsTheFirstBytePastOneMebibyte() {
        var frames = frames("é".repeat(512 * 1024) + "x\n", OutputStream.nullOutputStream());

        assertEquals("frame too large", assertThrows(IOException.class, frames::read).getMessage());
    }

    @Test
    void distinguishesABlankLineAndCleanEofFromAnIncompleteFrame() throws IOException {
        var frames = frames("\n", OutputStream.nullOutputStream());
        assertArrayEquals(new byte[0], frames.read());
        assertNull(frames.read());

        var partial = frames("\r", OutputStream.nullOutputStream());
        assertEquals("incomplete frame", assertThrows(EOFException.class, partial::read).getMessage());
    }

    @Test
    void writesExactUtf8BytesAndLfAndFlushes() throws IOException {
        var output = new ByteArrayOutputStream();
        var frames = frames("", new BufferedOutputStream(output));

        frames.write("한글".getBytes(UTF_8));
        frames.write(new byte[0]);

        assertArrayEquals("한글\n\n".getBytes(UTF_8), output.toByteArray());
    }

    private static ManagedStreamFrames frames(String input, OutputStream output) {
        return new ManagedStreamFrames(new ByteArrayInputStream(input.getBytes(UTF_8)), output,
                "frame too large", () -> new EOFException("incomplete frame"));
    }
}
