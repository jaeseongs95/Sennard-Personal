// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.hookclient;

import java.io.IOException;

/**
 * Why a hook got no answer from the server: no server, a refusal, an input the server would refuse, a server that could not be started. The client gives the
 * host the call's failure answer for it, and says only the reason's code (this program has no message source: the sentence for a code is the server's and the documentation's).
 * The three {@code HOOK_BODY_} reasons are named as the server's rejections are, so that one name means one thing in both.
 */
public final class HookNoAnswer extends IOException {
    private static final long serialVersionUID = 1L;

    /** The reasons; each name is the code written on stderr. */
    public enum Reason {
        /** The connection failed or the server did not answer in time. */
        SERVER_NOT_REACHABLE,
        /** The server answered with a status above 299. */
        SERVER_REFUSED,
        /** The server was not running and could not be started in the time the call allowed. */
        SERVER_NOT_STARTED,
        /** The client's own list of the host's input fields could not be read from its jar: not the server's fault, and a broken installation. */
        HOOK_FIELDS_UNREADABLE,
        /** The MCP relay's request got a successful answer that is not a JSON message or a stream of them (the relay only: a hook's answer is passed on as it came). */
        MCP_ANSWER_UNREADABLE,
        /** The hook's input is empty. */
        HOOK_BODY_EMPTY,
        /** The hook's input is not UTF-8. */
        HOOK_BODY_NOT_UTF8,
        /** The hook's input is not a JSON object. */
        HOOK_BODY_NOT_A_JSON_OBJECT
    }

    private static final int FIRST_REFUSAL_STATUS = 400;

    private final Reason reason;
    private final String detail;
    private final int status;

    private HookNoAnswer(Reason reason, String detail, Throwable cause, int status) {
        super(reason.name() + (detail == null ? "" : " " + detail), cause);
        this.reason = reason;
        this.detail = detail;
        this.status = status;
    }

    /** @param detail a word or number that says more (a status, an exit code, an exception's class), or null */
    public HookNoAnswer(Reason reason, String detail, Throwable cause) {
        this(reason, detail, cause, 0);
    }

    public HookNoAnswer(Reason reason, String detail) {
        this(reason, detail, null);
    }

    /** The server answered with this status, which is not 2xx. */
    public static HookNoAnswer refused(int status) {
        return new HookNoAnswer(Reason.SERVER_REFUSED, String.valueOf(status), null, status);
    }

    /** @return whether the server answered with a status of 400 or above (a 3xx is not followed and is no refusal); only a refusal can be a gate's deny */
    public boolean isRefusal() {
        return reason == Reason.SERVER_REFUSED && status >= FIRST_REFUSAL_STATUS;
    }

    public Reason reason() {
        return reason;
    }

    /** @return the detail, or null when there is none */
    public String detail() {
        return detail;
    }
}
