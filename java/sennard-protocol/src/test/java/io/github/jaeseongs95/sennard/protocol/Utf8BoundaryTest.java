// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The two OS boundaries with real processes. The failure this guards against exists only where the system code page is not UTF-8,
 * which is what these see on a Korean Windows (ACP 949); where it is UTF-8 they still check the contract.
 */
class Utf8BoundaryTest {
    @TempDir
    Path temporary;

    private ProcessBuilder probe(String mode) {
        return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx" + System.getProperty("sennard.test.childHeap"), "-cp", System.getProperty("sennard.test.classpath"),
                Utf8BoundaryProbe.class.getName(), mode);
    }

    private static String strictUtf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static String encodableNonAscii(Charset charset) {
        var encoder = charset.newEncoder();
        for (int point = 128; point <= Character.MAX_CODE_POINT; point++) {
            if (!Character.isLetter(point)) continue;
            String text = new String(Character.toChars(point));
            if (encoder.canEncode(text)) return text;
        }
        throw new IllegalStateException("No encodable non-ASCII character in " + charset.name());
    }

    @Test
    void bothStandardStreamsAreUtf8WhateverTheSystemCodePageIs() throws IOException, InterruptedException {
        Path out = temporary.resolve("out");
        Path err = temporary.resolve("err");
        Process process = probe("streams").redirectOutput(out.toFile()).redirectError(err.toFile()).start();
        assertEquals(0, process.waitFor());
        assertEquals(Utf8BoundaryProbe.TEXT, strictUtf8(Files.readAllBytes(out)));
        assertEquals(Utf8BoundaryProbe.TEXT, strictUtf8(Files.readAllBytes(err)));
    }

    @Test
    void aToolsOutputInTheSystemCodePageIsReadAsTheTextItIs() throws IOException, InterruptedException {
        Charset nativeCharset = Charset.forName(System.getProperty("native.encoding"));
        String expected = encodableNonAscii(nativeCharset);
        ProcessBuilder builder = probe("native");
        builder.command().add(Integer.toString(expected.codePointAt(0)));
        Process process = builder.start();
        String text = Utf8Boundary.toolText(process.getInputStream().readAllBytes(), nativeCharset);
        assertEquals(0, process.waitFor());
        assertEquals(expected, text);
    }

    @Test
    void selectsNonAsciiTextForDifferentCodePagesAndRejectsAsciiOnly() {
        for (Charset charset : new Charset[] { Charset.forName("MS949"), Charset.forName("windows-1252"), StandardCharsets.UTF_8 }) {
            String text = encodableNonAscii(charset);
            assertTrue(text.codePointAt(0) > 127 && Character.isLetter(text.codePointAt(0)) && charset.newEncoder().canEncode(text));
        }
        assertThrows(IllegalStateException.class, () -> encodableNonAscii(StandardCharsets.US_ASCII));
    }

    @Test
    void anUndecodableByteBecomesAReplacementInsteadOfFailing() {
        assertEquals("�ok", Utf8Boundary.toolText(new byte[] {(byte) 0xff, 'o', 'k'}, StandardCharsets.UTF_8));
        assertEquals("�ok", Utf8Boundary.toolText(new byte[] {(byte) 0xff, 'o', 'k'}, Charset.forName("MS949")));
    }

    @Test
    void aByteTheCodePageDefinesIsThatCodePagesLetter() {
        assertEquals("ÿok", Utf8Boundary.toolText(new byte[] {(byte) 0xff, 'o', 'k'}, Charset.forName("windows-1252")));
    }
}
