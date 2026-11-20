// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.domain.prompt;

import java.lang.Character.UnicodeScript;

/**
 * The one-line summary an agent records for a turn (what it did after hearing the user). It is a short note, not an answer: the limit counts how much room a character takes
 * on a line, so a text in Korean, Chinese or Japanese is held to about 100 characters and a text in an alphabet to about 250.
 *
 * <p>The weight of a character is {@value #WIDE_WEIGHT} when its Unicode script is Hangul, Han, Hiragana or Katakana and {@value #NARROW_WEIGHT} for every other code point; the
 * sum may not pass {@value #MAX_WEIGHT} (100 wide characters, or 250 narrow ones, or any mix of the two). Code points are counted, not UTF-16 units, so a surrogate pair is one
 * character. The text is kept as the agent wrote it, trimmed. A {@code Recap} can only exist within the limit: {@link #judge} is the way to make one from input.
 *
 * <p>This is a pure judgement: it knows no database, no framework and no wording. The one that receives the verdict turns {@link Blank} and {@link TooLong} into its own refusal.
 */
public record Recap(String text) {
    /** The most weight a recap may carry: 100 characters of Korean, 250 of English. */
    public static final int MAX_WEIGHT = 500;
    /** What a Hangul, Han, Hiragana or Katakana character weighs. */
    public static final int WIDE_WEIGHT = 5;
    /** What any other code point weighs. */
    public static final int NARROW_WEIGHT = 2;

    /** The answer of {@link #judge}: a recap that may be kept, or why the text cannot be one. */
    public sealed interface Judgment {
    }

    /** The text is within the limit; {@code recap} holds it trimmed. */
    public record Accepted(Recap recap) implements Judgment {
    }

    /** There is nothing but white space (or nothing at all) to keep. */
    public record Blank() implements Judgment {
    }

    /** The text weighs more than {@link #MAX_WEIGHT}; {@code weight} is what it weighs. */
    public record TooLong(int weight) implements Judgment {
    }

    /** A recap is always trimmed, never blank and never over the limit; the way to make one from input is {@link #judge}. */
    public Recap {
        if (text == null || !text.equals(text.strip()) || text.isEmpty() || weightOf(text) > MAX_WEIGHT) {
            throw new IllegalArgumentException("a recap is a trimmed, non-blank text of at most " + MAX_WEIGHT + " weight");
        }
    }

    /** The verdict on a text from outside: trimmed and kept, or blank, or too long (measured on the trimmed text). */
    public static Judgment judge(String input) {
        if (input == null || input.codePoints().allMatch(Recap::isSpace)) {
            return new Blank();
        }
        String text = input.strip();
        int weight = weightOf(text);
        return weight > MAX_WEIGHT ? new TooLong(weight) : new Accepted(new Recap(text));
    }

    /** White space in the sense of {@link Character#isWhitespace} or a space separator such as the no-break space, which is not white space there. */
    private static boolean isSpace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    /** What the text weighs: the sum of its code points' weights. */
    public static int weightOf(String text) {
        return text.codePoints().map(Recap::weightOf).sum();
    }

    /** What one code point weighs. */
    public static int weightOf(int codePoint) {
        return switch (UnicodeScript.of(codePoint)) {
            case HANGUL, HAN, HIRAGANA, KATAKANA -> WIDE_WEIGHT;
            default -> NARROW_WEIGHT;
        };
    }

    /** What this recap weighs. */
    public int weight() {
        return weightOf(text);
    }
}
