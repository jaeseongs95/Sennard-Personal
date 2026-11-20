// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.web;

import java.util.Map;

import org.springframework.http.HttpStatusCode;

/** The reasons the guard refuses a request; the name is the public {@code code} of the problem. The sentence is fixed and echoes no request value. */
public enum GuardRefusal {
    REQUEST_ORIGIN_OR_HOST_NOT_ALLOWED, TIME_ZONE_NOT_VALID, UNSUPPORTED_PROTOCOL_VERSION, CONTENT_TYPE_NOT_JSON, BODY_TOO_LARGE;

    RefusedRequest withStatus(HttpStatusCode status) {
        return new RefusedRequest(status, name(), "The request was refused by the server's guard.", Map.of());
    }
}
