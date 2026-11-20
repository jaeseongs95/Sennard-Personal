// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.settings;

import java.nio.file.Path;

/** The checks the records of this package make on their components: a pure value check, throwing {@link SettingsCode#INVALID_SETTING} with the field's name. */
final class SettingsChecks {
    private SettingsChecks() {
    }

    /** A text that is there, not blank and at most {@code max} characters. */
    static String text(String field, String value, int max) {
        if (value == null || value.isBlank()) {
            throw new SettingsException(SettingsCode.INVALID_SETTING, field + " is required.");
        }
        if (value.length() > max) {
            throw new SettingsException(SettingsCode.INVALID_SETTING, field + " is longer than " + max + " characters.");
        }
        return value;
    }

    /** A text that may be absent; when there it is checked like {@link #text}. */
    static String textOrNull(String field, String value, int max) {
        return value == null ? null : text(field, value, max);
    }

    static <T> T required(String field, T value) {
        if (value == null) {
            throw new SettingsException(SettingsCode.INVALID_SETTING, field + " is required.");
        }
        return value;
    }

    /** A path that is there and absolute: a relative one means another place for every working folder. */
    static Path absolute(String field, Path value) {
        if (required(field, value).isAbsolute()) {
            return value;
        }
        throw new SettingsException(SettingsCode.INVALID_SETTING, field + " is not an absolute path.");
    }
}
