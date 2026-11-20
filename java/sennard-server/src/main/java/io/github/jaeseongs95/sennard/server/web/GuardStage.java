// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.web;

import org.springframework.http.HttpStatusCode;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * What the filters of the guard share: one refusal, answered through {@link FilterRefusals} and closing the connection, because a refused request's body is
 * not read. Each stage checks one thing, and {@link GuardConfiguration} puts the stages in the order a request meets them.
 */
abstract class GuardStage extends OncePerRequestFilter {
    private final FilterRefusals refusals;

    GuardStage(FilterRefusals refusals) {
        this.refusals = refusals;
    }

    final void reject(HttpServletRequest request, HttpServletResponse response, HttpStatusCode status, GuardRefusal reason) {
        response.setHeader("Connection", "close");
        refusals.refuse(request, response, reason.withStatus(status));
    }
}
