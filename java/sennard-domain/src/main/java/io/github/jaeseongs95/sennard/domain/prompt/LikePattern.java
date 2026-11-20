// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.domain.prompt;

/**
 * The text of a search as a pattern of the standard SQL {@code LIKE} predicate that finds it anywhere in a column, written with the escape character {@value #ESCAPE}
 * ({@code LIKE ? ESCAPE '\'}). The person's text means itself: the two wildcards ({@code %} any run, {@code _} any one character) and the escape character are escaped, so
 * searching for {@code 100%} finds "100%" and not "100" followed by anything. This is a pure function on text; the statement and its binding belong to the one that runs it.
 */
public final class LikePattern {
    /** The escape character the statement declares. */
    public static final char ESCAPE = '\\';

    private LikePattern() {
    }

    /** A pattern that matches every value containing {@code text}. */
    public static String contains(String text) {
        StringBuilder pattern = new StringBuilder(text.length() + 8).append('%');
        for (int at = 0; at < text.length(); at++) {
            char c = text.charAt(at);
            if (c == '%' || c == '_' || c == ESCAPE) {
                pattern.append(ESCAPE);
            }
            pattern.append(c);
        }
        return pattern.append('%').toString();
    }
}
