// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.message;

import java.util.regex.Pattern;

/**
 * Text a session wrote that a hook gives back to sessions (a role context, a saved memory, a workflow's goal). A hook's own blocks open with an {@code [Sennard ...]} header line, and a
 * session that writes such a header, with the sentence that the user approved it, would make its text read as a block of the server. {@link #quoted} takes that opener out of the
 * text; the frame around the block (a header that says who wrote it and an end line that carries a value made when the text was saved) is what tells where the text ends.
 */
public final class SessionText {
    /** The opener of every block header the hooks write, in any case and with white space after the bracket. */
    private static final Pattern HEADER_OPENER = Pattern.compile("\\[\\s*Sennard", Pattern.CASE_INSENSITIVE);

    private SessionText() {
    }

    /** The text with every header opener written as {@code [quoted Sennard}: its words stay, it no longer opens a block. */
    public static String quoted(String text) {
        return HEADER_OPENER.matcher(text).replaceAll("[quoted Sennard");
    }
}
