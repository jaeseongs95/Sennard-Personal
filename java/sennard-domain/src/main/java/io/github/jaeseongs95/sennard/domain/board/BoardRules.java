// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.domain.board;

import java.util.Map;

import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.domain.error.InputCode;
import io.github.jaeseongs95.sennard.domain.error.InvalidInputException;

/** What a session board line may be: one line of text, trimmed, within a length. A pure judgement; the lengths come from the caller (settings). */
public final class BoardRules {
    private BoardRules() {
    }

    /** The summary as stored: trimmed, one non-empty line of at most {@code maxLength} characters. */
    public static String summary(String value, int maxLength) {
        String line = oneLine(value, maxLength);
        if (line == null) {
            throw new InvalidInputException(InputCode.SUMMARY_INVALID, SennardException.args("maxLength", maxLength));
        }
        return line;
    }

    /** The role as stored, by the same rule. */
    public static String role(String value, int maxLength) {
        String line = oneLine(value, maxLength);
        if (line == null) {
            throw new InvalidInputException(InputCode.ROLE_INVALID, Map.of("maxLength", maxLength));
        }
        return line;
    }

    private static String oneLine(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String line = value.strip();
        return !line.isEmpty() && line.indexOf('\r') < 0 && line.indexOf('\n') < 0 && line.length() <= maxLength ? line : null;
    }
}
