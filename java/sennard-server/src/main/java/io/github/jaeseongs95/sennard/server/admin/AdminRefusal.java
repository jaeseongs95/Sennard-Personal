// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.admin;

import java.util.Map;

import org.springframework.http.HttpStatus;

import io.github.jaeseongs95.sennard.server.web.RefusedRequest;

/**
 * The refusals of the admin page's port (what the port's filters answer) and the status each has; the name is the public {@code code} the page shows a sentence for. The
 * codes of a refused signed change are the {@code ADMIN_*} constants of the code enums ({@code SennardException}).
 */
enum AdminRefusal {
    NOT_FOUND(HttpStatus.NOT_FOUND),
    HOST_REJECTED(HttpStatus.FORBIDDEN),
    ADMIN_ORIGIN_REJECTED(HttpStatus.FORBIDDEN),
    CONTENT_TYPE_REJECTED(HttpStatus.UNSUPPORTED_MEDIA_TYPE);

    private final HttpStatus status;

    AdminRefusal(HttpStatus status) {
        this.status = status;
    }

    RefusedRequest refusal() {
        return new RefusedRequest(status, name(), "The admin page's port does not take this request.", Map.of());
    }
}
