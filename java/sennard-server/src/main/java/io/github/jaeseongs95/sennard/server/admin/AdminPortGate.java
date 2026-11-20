// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.admin;

import java.io.IOException;

import org.springframework.web.filter.OncePerRequestFilter;

import io.github.jaeseongs95.sennard.server.web.AdminPageAddress;
import io.github.jaeseongs95.sennard.server.web.FilterRefusals;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * The admin page's port serves the page's routes and nothing else: Actuator, the hooks and the MCP endpoint are not reachable through it. This filter is registered for every
 * path and runs after the filters of the page's routes ({@link AdminPortFilter} marks a request they took), so a request on the page's port that no route took is refused
 * without any path being compared here.
 */
final class AdminPortGate extends OncePerRequestFilter {
    private final AdminPageAddress address;
    private final FilterRefusals refusals;

    AdminPortGate(AdminPageAddress address, FilterRefusals refusals) {
        this.address = address;
        this.refusals = refusals;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        if (address.owns(request.getLocalPort()) && request.getAttribute(AdminPortFilter.ROUTED) == null) {
            AdminPortFilter.common(response);
            refusals.refuse(request, response, AdminRefusal.NOT_FOUND.refusal());
            return;
        }
        chain.doFilter(request, response);
    }
}
