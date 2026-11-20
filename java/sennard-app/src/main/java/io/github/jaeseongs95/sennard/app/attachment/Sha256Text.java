// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.attachment;

import java.util.regex.Pattern;

/**
 * The text form of a sha256 the tools and the records use, {@code sha256:} and 64 lower-case hex digits (the design's 4.2), and the bare hex the tables hold. The one place the prefix
 * is written.
 */
public final class Sha256Text {
    /** The prefix of the text form. */
    public static final String PREFIX = "sha256:";
    /** The pattern a text form has; the tool contracts state the same one. */
    public static final String PATTERN = "^sha256:[0-9a-f]{64}$";
    private static final Pattern FORM = Pattern.compile(PATTERN);
    private static final Pattern BARE = Pattern.compile("[0-9a-f]{64}");

    private Sha256Text() {
    }

    /** Whether the text is a sha256 in the text form. */
    public static boolean isText(String text) {
        return text != null && FORM.matcher(text).matches();
    }

    /** The text form of 64 hex digits. */
    public static String of(String hex) {
        return PREFIX + hex;
    }

    /** The 64 hex digits of a text form; the text is returned as it is when it is already bare hex. A text that is neither is refused with an {@code IllegalArgumentException}. */
    public static String hex(String text) {
        if (isText(text)) {
            return text.substring(PREFIX.length());
        }
        if (text != null && BARE.matcher(text).matches()) {
            return text;
        }
        throw new IllegalArgumentException("not a sha256: " + text);
    }
}
