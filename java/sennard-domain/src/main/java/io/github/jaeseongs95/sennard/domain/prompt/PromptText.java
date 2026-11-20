// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.domain.prompt;

import java.nio.charset.StandardCharsets;

import io.github.jaeseongs95.sennard.domain.Digests;

/**
 * What the archive keeps of one user prompt: the text (the whole of it, or its first part when it is over the size limit), and the facts about the prompt as it came, so that
 * a cut text is still known to be a cut and which prompt it was.
 *
 * <p>The size is counted in UTF-8 bytes, the way the text is stored. A text over the limit is cut at the last character that fits: never inside a character, so what is kept is
 * always valid text, and a surrogate pair is kept or dropped whole. {@code originalBytes} and {@code digest} are those of the whole prompt, not of the kept part. An empty prompt
 * is a prompt: it is kept as it is. A character in the text that is not a well-formed UTF-16 pair (a lone surrogate) takes the one byte the JDK encodes it as ({@code ?}),
 * which is the byte count the digest and {@code originalBytes} also use.
 *
 * @param text what is kept
 * @param originalBytes the UTF-8 length of the whole prompt
 * @param digest the SHA-256 of the whole prompt's UTF-8 bytes, in lower-case hex (64 characters)
 * @param truncated whether {@code text} is only the first part of the prompt
 */
public record PromptText(String text, long originalBytes, String digest, boolean truncated) {
    /**
     * @param prompt the prompt as the host gave it
     * @param maxBytes the most UTF-8 bytes the archive keeps of one prompt; at least 1
     */
    public static PromptText of(String prompt, long maxBytes) {
        if (maxBytes < 1) {
            throw new IllegalArgumentException("the archive keeps at least one byte of a prompt");
        }
        long original = prompt.getBytes(StandardCharsets.UTF_8).length;
        String digest = Digests.sha256Hex(prompt);
        if (original <= maxBytes) {
            return new PromptText(prompt, original, digest, false);
        }
        long used = 0;
        int end = 0;
        while (end < prompt.length()) {
            int codePoint = prompt.codePointAt(end);
            long width = utf8Length(codePoint);
            if (used + width > maxBytes) {
                break;
            }
            used += width;
            end += Character.charCount(codePoint);
        }
        return new PromptText(prompt.substring(0, end), original, digest, true);
    }

    /** The UTF-8 length of one code point as {@link String#getBytes} encodes it: a lone surrogate is the single byte of {@code ?}. */
    private static int utf8Length(int codePoint) {
        boolean loneSurrogate = codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE;
        if (codePoint < 0x80 || loneSurrogate) {
            return 1;
        }
        if (codePoint < 0x800) {
            return 2;
        }
        return codePoint < Character.MIN_SUPPLEMENTARY_CODE_POINT ? 3 : 4;
    }
}
