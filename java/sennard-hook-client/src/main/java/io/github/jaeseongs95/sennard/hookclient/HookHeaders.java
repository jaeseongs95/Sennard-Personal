// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.hookclient;

/** The names of the request headers the Sennard clients send and the server reads: written here once, by the client and by the server alike. */
public final class HookHeaders {
    /**
     * Sent by the hook client on a session start: the process ids above it, nearest first, as one comma-separated header value. The host is one of them; which one
     * is the server's decision, because a host may run its hook through a shell that ends with the hook.
     */
    public static final String HOST_PIDS = "X-Sennard-Host-Pids";
    /** The caller's time zone, an IANA region name ({@link CallerTimeZone}); every client of the server sends it when its environment names one. */
    public static final String TIME_ZONE = "X-Sennard-Time-Zone";
    /** Session-start attachment: versioned base64url UTF-8 Properties of the host resource's explicit environment allowlist. */

    private HookHeaders() {
    }
}
