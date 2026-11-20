// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.settings;

/**
 * A refusal or failure of the settings store with a fixed code. The message never carries a stored value (a public key, a URL, a path), only the name of the field.
 */
public final class SettingsException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final SettingsCode code;

    public SettingsException(SettingsCode code, String message) {
        super(code + ": " + message);
        this.code = code;
    }

    public SettingsException(SettingsCode code, String message, Throwable cause) {
        super(code + ": " + message, cause);
        this.code = code;
    }

    public SettingsCode code() {
        return code;
    }
}
