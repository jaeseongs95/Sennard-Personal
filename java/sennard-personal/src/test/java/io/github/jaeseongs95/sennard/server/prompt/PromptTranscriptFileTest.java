// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.prompt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.nio.file.Path;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import io.github.jaeseongs95.sennard.app.prompt.PromptSource;

/**
 * What the archive's row of a prompt says about its transcript file, as {@code PromptTranscriptService} tells it apart: a path on this computer, no path at all, and a text that is
 * no path here are three answers of one sealed type, not an empty result that the caller has to look into again.
 *
 * <h2>Case table (target, input, expected result, how it is judged)</h2>
 * <pre>
 * f1  a path          a stored path -> Named, holding the path as the file system reads it
 * f2  none named      a null path, an empty text and a blank text -> NoneNamed (the host named no file)
 * f3  not a path here a text with a NUL character, which no file system takes as a path -> NotLocal, a different answer from f2 (the host did name one)
 * </pre>
 */
class PromptTranscriptFileTest {
    private static PromptSource row(String transcriptPath) {
        return new PromptSource("prompt-1", "claude-code", "session-1", Instant.parse("2026-11-12T10:00:00Z"), "a prompt", false, transcriptPath);
    }

    @Test
    void aStoredPathIsNamed() {
        PromptTranscriptService.TranscriptFile file = PromptTranscriptService.fileOf(row("transcripts/session-1.jsonl"));
        assertEquals(Path.of("transcripts/session-1.jsonl"), assertInstanceOf(PromptTranscriptService.TranscriptFile.Named.class, file).path());
    }

    @Test
    void noPathNamedIsNoneNamed() {
        for (String stored : new String[] {null, "", "   "}) {
            assertInstanceOf(PromptTranscriptService.TranscriptFile.NoneNamed.class, PromptTranscriptService.fileOf(row(stored)), "stored: " + stored);
        }
    }

    @Test
    void aTextThatIsNoPathHereIsNotLocalAndNotTheSameAsNoneNamed() {
        assertInstanceOf(PromptTranscriptService.TranscriptFile.NotLocal.class, PromptTranscriptService.fileOf(row("transcripts/bad\u0000name.jsonl")));
    }
}
