// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import java.util.LinkedHashMap;
import java.util.Map;

import io.github.jaeseongs95.sennard.server.message.MessageRef;

/**
 * A workflow contract error: a public code, a catalog message and sanitized details. Everything a caller may see; a failure that is not one
 * of these is a server failure and reaches the caller as {@code MCP_UNAVAILABLE}.
 */
public final class WorkflowFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final WorkflowCode code;
    private final transient MessageRef ref;
    private final transient Map<String, Object> details;

    public WorkflowFailure(WorkflowCode code, MessageRef ref, Map<String, ?> details) {
        this(code, ref, details, null);
    }

    /** A failure that answers an exception the server caught: the exception stays as the cause, and only the code, the sentence and the details reach the caller. */
    public WorkflowFailure(WorkflowCode code, MessageRef ref, Map<String, ?> details, Throwable cause) {
        super(code + ":" + ref.key(), cause);
        this.code = code;
        this.ref = ref;
        this.details = details == null ? null : new LinkedHashMap<>(details);
    }

    public WorkflowFailure(WorkflowCode code, MessageRef ref) {
        this(code, ref, null);
    }

    public WorkflowCode code() {
        return code;
    }

    public MessageRef ref() {
        return ref;
    }

    /** May be null. */
    public Map<String, Object> details() {
        return details;
    }

    static Map<String, Object> details(Object... keysAndValues) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
            out.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return out;
    }
}
