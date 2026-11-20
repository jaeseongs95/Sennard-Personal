// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.domain.prompt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * The limit of a turn recap as a pure judgement: no database, no framework. A recap is "really a summary" (user 2026-11-12: 「2000자도 많아. 정말 요약본이야. 100자.
 * (한글기준. 영어는 그에 맞춰서.)」): a code point of Hangul, Han, Hiragana or Katakana weighs 5, any other 2, and the sum may not pass 500 (100 wide, 250 narrow).
 *
 * <h2>Case table (target, input, expected result, how it is judged)</h2>
 * <pre>
 * r1  blank              null, "", spaces, tab, line breaks, ideographic space (U+3000), no-break space  -> Blank (nothing to keep)
 * r2  narrow boundary    "a" x 250 -> Accepted, weight 500; "a" x 251 -> TooLong(502)
 * r3  Hangul boundary    "가" x 100 -> Accepted, weight 500; "가" x 101 -> TooLong(505); compatibility jamo "ㄱ" weighs the same
 * r4  mixed              60 Hangul + 100 English = 500 -> Accepted; plus one more English -> TooLong(502); 99 Hangul + 3 English = 501 -> TooLong
 * r5  the other wide     Han "漢", Hiragana "あ", Katakana "ア": 100 pass, 101 do not (weight 5 each)
 * r6  the narrow rest    Latin, digits, Cyrillic, Arabic, Thai, punctuation, the CJK full stop "。" and a full-width Latin letter weigh 2 each
 * r7  surrogate pairs    "😀" (one code point, two UTF-16 units) weighs 2: 250 pass, 251 do not; "𠮷" (a supplementary Han) weighs 5: 100 pass, 101 do not;
 *                        a lone surrogate is one narrow character
 * r8  trimmed            "  가 " keeps "가" (weight 5); the white space around the text is not counted, the white space inside it is (2 each)
 * r9  the type's own     new Recap(untrimmed), new Recap(""), new Recap(over the limit) -> IllegalArgumentException: a Recap cannot exist outside the limit
 * r10 weight of a point  weightOf(cp) for 'a', '가', '漢', 'あ', 'ア', '1', ' ', U+1F600, U+20BB7
 * </pre>
 */
class RecapTest {
    private static Recap accepted(String input) {
        return assertInstanceOf(Recap.Accepted.class, Recap.judge(input), "accepted: " + input).recap();
    }

    private static int tooLong(String input) {
        return assertInstanceOf(Recap.TooLong.class, Recap.judge(input), "too long: " + input).weight();
    }

    @Test
    void nothingButWhiteSpaceIsBlank() {
        for (String blank : new String[] {null, "", " ", "   ", "\t", "\r\n", "\n\n", "　", " ", "  ", " \t\n　 "}) {
            assertInstanceOf(Recap.Blank.class, Recap.judge(blank), "blank: " + blank);
        }
    }

    @Test
    void anAlphabetHoldsTwoHundredFiftyCharacters() {
        assertEquals(500, accepted("a".repeat(250)).weight());
        assertEquals(502, tooLong("a".repeat(251)));
    }

    @Test
    void hangulHoldsOneHundredCharacters() {
        assertEquals(500, accepted("가".repeat(100)).weight());
        assertEquals(505, tooLong("가".repeat(101)));
        assertEquals(500, accepted("ㄱ".repeat(100)).weight());
        assertEquals(505, tooLong("ㄱ".repeat(101)));
    }

    @Test
    void aMixWeighsEachCharacterByItsOwnScript() {
        assertEquals(500, accepted("가".repeat(60) + "a".repeat(100)).weight());
        assertEquals(502, tooLong("가".repeat(60) + "a".repeat(101)));
        assertEquals(501, tooLong("가".repeat(99) + "abc"));
        assertEquals(499, accepted("가".repeat(99) + "ab").weight());
    }

    @Test
    void hanHiraganaAndKatakanaAreWideToo() {
        for (String one : new String[] {"漢", "あ", "ア"}) {
            assertEquals(500, accepted(one.repeat(100)).weight(), one);
            assertEquals(505, tooLong(one.repeat(101)), one);
        }
    }

    @Test
    void everyOtherScriptAndPunctuationIsNarrow() {
        for (String one : new String[] {"a", "Z", "7", "ж", "ع", "ก", ".", "。", "Ａ", "é", "ß"}) {
            assertEquals(500, accepted(one.repeat(250)).weight(), one);
            assertEquals(502, tooLong(one.repeat(251)), one);
        }
    }

    @Test
    void aSurrogatePairIsOneCharacterNotTwo() {
        assertEquals(500, accepted("😀".repeat(250)).weight());
        assertEquals(502, tooLong("😀".repeat(251)));
        assertEquals(500, accepted("𠮷".repeat(100)).weight());
        assertEquals(505, tooLong("𠮷".repeat(101)));
        assertEquals(2, Recap.weightOf("\uD800"), "a lone surrogate is one narrow character");
        assertEquals(4, Recap.weightOf("😀\uD83D"), "a pair and a lone surrogate: two characters");
    }

    @Test
    void theTextIsTrimmedAndOnlyTheInsideIsCounted() {
        Recap trimmed = accepted("  가 ");
        assertEquals("가", trimmed.text());
        assertEquals(5, trimmed.weight());
        assertEquals(500, accepted("  " + "a".repeat(250) + "\n").weight(), "white space around the text costs nothing");
        assertEquals(12, accepted("가 가").weight(), "the space inside weighs 2: 5 + 2 + 5");
    }

    @Test
    void aRecapOutsideTheLimitCannotBeMade() {
        assertThrows(IllegalArgumentException.class, () -> new Recap(" padded "));
        assertThrows(IllegalArgumentException.class, () -> new Recap(""));
        assertThrows(IllegalArgumentException.class, () -> new Recap(null));
        assertThrows(IllegalArgumentException.class, () -> new Recap("a".repeat(251)));
        assertEquals("a".repeat(250), new Recap("a".repeat(250)).text());
    }

    @Test
    void theWeightOfOneCodePoint() {
        assertEquals(2, Recap.weightOf('a'));
        assertEquals(2, Recap.weightOf('1'));
        assertEquals(2, Recap.weightOf(' '));
        assertEquals(5, Recap.weightOf('가'));
        assertEquals(5, Recap.weightOf('漢'));
        assertEquals(5, Recap.weightOf('あ'));
        assertEquals(5, Recap.weightOf('ア'));
        assertEquals(2, Recap.weightOf(0x1F600));
        assertEquals(5, Recap.weightOf(0x20BB7));
    }
}
