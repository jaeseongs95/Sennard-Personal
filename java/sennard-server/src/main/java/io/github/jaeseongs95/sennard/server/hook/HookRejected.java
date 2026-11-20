// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook;

/** A hook call the server will not answer. The status is above 299, so the host's own failure behaviour applies instead of a silent empty answer. */
public final class HookRejected extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final HookRejection rejection;
    private final String detail;

    public HookRejected(HookRejection rejection) {
        super(rejection.name());
        this.rejection = rejection;
        this.detail = null;
    }

    public HookRejected(HookRejection rejection, Throwable cause) {
        super(rejection.name(), cause);
        this.rejection = rejection;
        this.detail = null;
    }

    /**
     * @param detail the code of the layer that failed, which is what the answer's reason says in place of the rejection's own name
     * @param cause what that layer threw
     */
    public HookRejected(HookRejection rejection, String detail, Throwable cause) {
        super(detail, cause);
        this.rejection = rejection;
        this.detail = detail;
    }

    public HookRejection rejection() {
        return rejection;
    }

    public int status() {
        return rejection.status();
    }

    /** What the answer's {@code reason} says: the failed layer's code when there is one, else the rejection's name. */
    public String reason() {
        return detail == null ? rejection.name() : detail;
    }
}
