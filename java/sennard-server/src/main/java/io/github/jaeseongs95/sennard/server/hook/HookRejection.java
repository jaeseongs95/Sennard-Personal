// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook;

/**
 * Why the server will not answer a hook call, with the HTTP status the transport answers it with. The name is the reason a host's hook client (or an MCP
 * error result) shows; {@link #HOOK_HANDLER_FAILED} is the one a layer below the hook path reports through, carrying that layer's own code as the detail
 * of the {@link HookRejected}.
 */
public enum HookRejection {
    HOOK_HOST_UNKNOWN(404),
    HOOK_BODY_EMPTY(400),
    HOOK_BODY_NOT_UTF8(400),
    HOOK_BODY_NOT_A_JSON_OBJECT(400),
    HOOK_EVENT_UNKNOWN(400),
    HOOK_SESSION_ID_MISSING(400),
    HOOK_MANAGED_IDENTITY_INVALID(409),
    HOOK_WAKE_EVENT_INVALID(400),
    HOOK_HANDLER_UNAVAILABLE(503),
    HOOK_HANDLER_FAILED(503),
    /** The call lost a first creation (a session, its task) to another request at the same moment, on the key of the row: the server works, and the call is not run again. */
    HOOK_HANDLER_CONFLICT(409),
    HOOK_VERDICT_NOT_APPLICABLE(500),
    HOOK_VERDICT_NOT_DELIVERABLE(500),
    /** The ends of a wake request that has no message to give: {@code WakeController} refuses with them as it does a refused call, and the one error advice answers. */
    HOOK_WAKE_NO_MESSAGE(408),
    HOOK_WAKE_REPLACED(409),
    HOOK_WAKE_SESSION_NOT_IDLE(409),
    HOOK_WAKE_SERVER_STOPPING(503);

    private final int status;

    HookRejection(int status) {
        this.status = status;
    }

    public int status() {
        return status;
    }
}
