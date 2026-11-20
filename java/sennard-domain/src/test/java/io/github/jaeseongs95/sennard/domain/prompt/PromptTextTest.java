// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.domain.prompt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import io.github.jaeseongs95.sennard.domain.Digests;

/**
 * What the archive keeps of a prompt, as a pure function: whole when it fits, else its first part cut at a character boundary, with the size and the digest of the whole.
 *
 * <h2>Case table (target, input, expected result, how it is judged)</h2>
 * <pre>
 * t1  fits               "abc", limit 1 MiB -> kept whole, 3 bytes, not cut, digest = SHA-256("abc") (ba7816bf...); the digest is the JDK's, written out here as a literal
 * t2  empty prompt       "" -> kept as "", 0 bytes, not cut, digest = SHA-256 of nothing (e3b0c442...)
 * t3  the boundary       "a" x 10, limit 10 -> whole, not cut; limit 9 -> "a" x 9, cut, originalBytes still 10
 * t4  multi-byte         "가나다" (9 bytes): limit 9 whole; 8 and 7 -> "가나"; 6 -> "가나"; 5 -> "가"; 3 -> "가"; 2 and 1 -> "" (cut, nothing fits)
 * t5  surrogate pair     "a😀b" (1 + 4 + 1 bytes): limit 6 whole; 5 -> "a😀"; 4 -> "a" (the pair is not split); 1 -> "a"
 * t6  the whole's facts  cut or not, originalBytes and digest are those of the whole prompt, never of the kept part
 * t7  always valid       for every limit 1..N of a mixed prompt: the kept text is a prefix, fits the limit in UTF-8 and is the longest such prefix (the next character would not fit)
 * t8  lone surrogate     "a\uD800b" encodes as a ? b (3 bytes): limit 2 -> "a\uD800"; the bytes counted are the ones stored
 * t9  a limit below one  0 and negative -> IllegalArgumentException
 * t10 the default size   1 MiB + 1 bytes of text with a 1 MiB limit -> cut to exactly 1 MiB
 * </pre>
 */
class PromptTextTest {
    private static final String ABC_DIGEST = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
    private static final String EMPTY_DIGEST = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private static final long MEBIBYTE = 1024L * 1024L;

    private static int bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    @Test
    void aPromptThatFitsIsKeptWholeWithItsSizeAndDigest() {
        PromptText kept = PromptText.of("abc", MEBIBYTE);
        assertEquals(new PromptText("abc", 3, ABC_DIGEST, false), kept);
    }

    @Test
    void anEmptyPromptIsKeptAsItIs() {
        assertEquals(new PromptText("", 0, EMPTY_DIGEST, false), PromptText.of("", MEBIBYTE));
    }

    @Test
    void theLimitItselfFitsAndOneByteLessCutsOneCharacter() {
        String ten = "a".repeat(10);
        assertEquals(new PromptText(ten, 10, Digests.sha256Hex(ten), false), PromptText.of(ten, 10));
        assertEquals(new PromptText("a".repeat(9), 10, Digests.sha256Hex(ten), true), PromptText.of(ten, 9));
    }

    @Test
    void aMultiByteCharacterIsNeverSplit() {
        String hangul = "가나다";
        assertEquals(9, bytes(hangul));
        assertEquals("가나다", PromptText.of(hangul, 9).text());
        assertFalse(PromptText.of(hangul, 9).truncated());
        for (int limit : new int[] {8, 7, 6}) {
            assertEquals("가나", PromptText.of(hangul, limit).text(), "limit " + limit);
            assertTrue(PromptText.of(hangul, limit).truncated());
        }
        for (int limit : new int[] {5, 4, 3}) {
            assertEquals("가", PromptText.of(hangul, limit).text(), "limit " + limit);
        }
        for (int limit : new int[] {2, 1}) {
            PromptText nothing = PromptText.of(hangul, limit);
            assertEquals("", nothing.text(), "limit " + limit);
            assertTrue(nothing.truncated());
            assertEquals(9, nothing.originalBytes());
        }
    }

    @Test
    void aSurrogatePairIsKeptOrDroppedWhole() {
        String text = "a😀b";
        assertEquals(6, bytes(text));
        assertEquals(text, PromptText.of(text, 6).text());
        assertEquals("a😀", PromptText.of(text, 5).text());
        assertEquals("a", PromptText.of(text, 4).text());
        assertEquals("a", PromptText.of(text, 1).text());
    }

    @Test
    void theSizeAndTheDigestAreThoseOfTheWholePrompt() {
        String whole = "가나다라마바사";
        PromptText cut = PromptText.of(whole, 10);
        assertTrue(cut.truncated());
        assertEquals("가나다", cut.text());
        assertEquals(bytes(whole), cut.originalBytes());
        assertEquals(Digests.sha256Hex(whole), cut.digest());
        assertEquals(64, cut.digest().length());
    }

    @Test
    void whateverTheLimitWhatIsKeptIsTheLongestPrefixThatFits() {
        String prompt = "ab가😀é한\n漢z😀";
        int whole = bytes(prompt);
        for (int limit = 1; limit <= whole + 2; limit++) {
            PromptText kept = PromptText.of(prompt, limit);
            assertTrue(prompt.startsWith(kept.text()), "a prefix at " + limit);
            assertTrue(bytes(kept.text()) <= limit, "fits at " + limit);
            assertEquals(limit < whole, kept.truncated(), "cut only when it does not fit, at " + limit);
            if (kept.truncated()) {
                int next = prompt.offsetByCodePoints(kept.text().length(), 1);
                assertTrue(bytes(prompt.substring(0, next)) > limit, "the next character would not fit, at " + limit);
            }
        }
    }

    @Test
    void aLoneSurrogateTakesTheOneByteItIsStoredAs() {
        String text = "a\uD800b";
        assertEquals(3, bytes(text));
        PromptText cut = PromptText.of(text, 2);
        assertEquals("a\uD800", cut.text());
        assertEquals(3, cut.originalBytes());
        assertTrue(cut.truncated());
    }

    @Test
    void aLimitBelowOneByteIsAMistake() {
        assertThrows(IllegalArgumentException.class, () -> PromptText.of("abc", 0));
        assertThrows(IllegalArgumentException.class, () -> PromptText.of("abc", -1));
    }

    @Test
    void aMebibytePlusOneByteIsCutToAMebibyte() {
        String text = "x".repeat((int) MEBIBYTE + 1);
        PromptText cut = PromptText.of(text, MEBIBYTE);
        assertTrue(cut.truncated());
        assertEquals(MEBIBYTE, bytes(cut.text()));
        assertEquals(MEBIBYTE + 1, cut.originalBytes());
    }
}
