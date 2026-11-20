// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.files;

import java.util.Map;

/** A refused or failed file fact: a fixed code and a catalog key with its parameters, never a raw exception text. */
public final class FileFactsException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String code;
    private final String messageKey;
    private final transient Map<String, Object> params;

    public FileFactsException(String code, String messageKey, Map<String, Object> params) {
        super(code + ": " + messageKey);
        this.code = code;
        this.messageKey = messageKey;
        this.params = params;
    }

    public String code() {
        return code;
    }

    public String messageKey() {
        return messageKey;
    }

    public Map<String, Object> params() {
        return params;
    }
}
