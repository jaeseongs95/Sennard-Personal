// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.web;

import java.util.Map;

import org.springframework.http.HttpStatus;

/** The refusals the web boundary makes of any request, and the status each has; the name is the public {@code code} of the problem (the admin page has a sentence for each). */
public enum WebRefusal {
    /** The request does not have the shape its route takes: a constraint of a body, a path variable or a parameter failed, or the body is not JSON of the record's shape. */
    REQUEST_INVALID(HttpStatus.BAD_REQUEST, "The request does not match what the admin page sends."),
    /** Anything the application did not expect: a fixed sentence and no detail of what failed. */
    INTERNAL_FAILURE(HttpStatus.INTERNAL_SERVER_ERROR, "The server could not complete the request.");

    private final HttpStatus status;
    private final String detail;

    WebRefusal(HttpStatus status, String detail) {
        this.status = status;
        this.detail = detail;
    }

    public RefusedRequest because(Map<String, Object> details) {
        return new RefusedRequest(status, name(), detail, details);
    }
}
