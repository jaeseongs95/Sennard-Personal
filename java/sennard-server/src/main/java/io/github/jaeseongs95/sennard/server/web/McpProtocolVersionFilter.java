// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.web;

import java.io.IOException;
import java.util.Collections;
import java.util.Set;

import org.springframework.http.HttpStatus;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * The guard's check of the {@code MCP-Protocol-Version} header, which only the MCP endpoint reads: {@link GuardConfiguration} registers this filter for the endpoint's URL
 * pattern, so the filter does not look at the path (the container matches the decoded path with its dot segments resolved and its parameters removed).
 */
final class McpProtocolVersionFilter extends GuardStage {
    private static final String PROTOCOL_VERSION_HEADER = "MCP-Protocol-Version";

    private final Set<String> protocolVersions;

    McpProtocolVersionFilter(Set<String> protocolVersions, FilterRefusals refusals) {
        super(refusals);
        this.protocolVersions = Set.copyOf(protocolVersions);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        if (!protocolVersions.containsAll(Collections.list(request.getHeaders(PROTOCOL_VERSION_HEADER)))) {
            reject(request, response, HttpStatus.BAD_REQUEST, GuardRefusal.UNSUPPORTED_PROTOCOL_VERSION);
            return;
        }
        chain.doFilter(request, response);
    }
}
