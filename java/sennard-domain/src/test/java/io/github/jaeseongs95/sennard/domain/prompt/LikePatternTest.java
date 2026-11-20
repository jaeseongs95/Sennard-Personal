// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.domain.prompt;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The search text as a standard SQL pattern with the escape character backslash.
 *
 * <pre>
 * p1  plain text         "abc" -> "%abc%"
 * p2  the wildcards      "100%" -> "%100\%%"; "a_b" -> "%a\_b%"; "%_" -> "%\%\_%"
 * p3  the escape itself  "a\b" -> "%a\\b%"; "\%" -> "%\\\%%" (the backslash first, then the percent)
 * p4  empty and Korean   "" -> "%%"; "가나" -> "%가나%" (nothing but the three special characters is touched)
 * </pre>
 */
class LikePatternTest {
    @Test
    void plainTextIsWrappedInTheWildcards() {
        assertEquals("%abc%", LikePattern.contains("abc"));
    }

    @Test
    void theWildcardsAreEscapedSoTheyMeanThemselves() {
        assertEquals("%100\\%%", LikePattern.contains("100%"));
        assertEquals("%a\\_b%", LikePattern.contains("a_b"));
        assertEquals("%\\%\\_%", LikePattern.contains("%_"));
    }

    @Test
    void theEscapeCharacterIsEscapedFirstOfAll() {
        assertEquals("%a\\\\b%", LikePattern.contains("a\\b"));
        assertEquals("%\\\\\\%%", LikePattern.contains("\\%"));
    }

    @Test
    void nothingElseIsTouched() {
        assertEquals("%%", LikePattern.contains(""));
        assertEquals("%가나%", LikePattern.contains("가나"));
        assertEquals("%it's \"x\" [a]%", LikePattern.contains("it's \"x\" [a]"));
    }
}
