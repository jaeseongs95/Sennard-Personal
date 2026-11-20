// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.secret;

/** A sealed value could not be opened (wrong key, other name or data, tampered, unknown format) or the key store is unavailable. */
public final class SecretSealException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String code;

    public SecretSealException(String code, Throwable cause) {
        super(code, cause);
        this.code = code;
    }

    public SecretSealException(String code) {
        this(code, null);
    }

    /** SECRET_KEY_UNAVAILABLE or SECRET_UNREADABLE. */
    public String code() {
        return code;
    }
}
