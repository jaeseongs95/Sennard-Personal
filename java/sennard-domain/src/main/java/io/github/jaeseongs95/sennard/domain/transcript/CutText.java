// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.domain.transcript;

import java.util.Objects;

/**
 * A text of a transcript entry cut to the size an entry may carry ({@link TranscriptEntry#TEXT_MAX_CHARACTERS} characters), and whether it was cut. The size is counted in
 * characters (code points), and the cut never falls inside a surrogate pair, so what is kept is always valid text.
 */
record CutText(String text, boolean truncated) {
    CutText {
        Objects.requireNonNull(text, "text");
        if (text.length() > TranscriptEntry.TEXT_MAX_CHARACTERS && text.codePointCount(0, text.length()) > TranscriptEntry.TEXT_MAX_CHARACTERS) {
            throw new IllegalArgumentException("a transcript entry keeps at most " + TranscriptEntry.TEXT_MAX_CHARACTERS + " characters of a text");
        }
    }

    /** The first {@link TranscriptEntry#TEXT_MAX_CHARACTERS} characters of the text, marked cut when there was more. */
    static CutText of(String whole) {
        Objects.requireNonNull(whole, "whole");
        if (whole.length() <= TranscriptEntry.TEXT_MAX_CHARACTERS || whole.codePointCount(0, whole.length()) <= TranscriptEntry.TEXT_MAX_CHARACTERS) {
            return new CutText(whole, false);
        }
        return new CutText(whole.substring(0, whole.offsetByCodePoints(0, TranscriptEntry.TEXT_MAX_CHARACTERS)), true);
    }

    /** The text of an entry built from parts that are already cut: it must be within the size, and is not marked cut unless the caller says so. */
    static String within(String text) {
        return new CutText(text, false).text();
    }
}
