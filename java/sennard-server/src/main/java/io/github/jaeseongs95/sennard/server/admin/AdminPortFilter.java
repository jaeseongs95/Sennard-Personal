// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.admin;

import java.io.IOException;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import io.github.jaeseongs95.sennard.server.web.AdminPageAddress;
import io.github.jaeseongs95.sennard.server.web.FilterRefusals;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Keeps the two ports apart and other web pages out of the admin page (DNS rebinding, cross-site POST). These checks are not what protects a change
 * from an agent on this computer: the passkey signature is.
 *
 * <p>A filter of this class is registered for one route of the page ({@link AdminConfiguration}: its URL pattern is the route), so no filter reads a path: the container
 * matches the path it routes, decoded, without path parameters and with its dot segments resolved, which the raw request URI would not give ({@code /%61pi/...} or
 * {@code /x/../api/...} must not reach a route of the other port). It marks the request as routed, which {@link AdminPortGate} reads to keep every other path off the page's port.
 */
abstract class AdminPortFilter extends OncePerRequestFilter {
    private static final Logger LOG = LoggerFactory.getLogger(AdminPortFilter.class);
    /** The request attribute that says a route of the page is the one being answered. */
    static final String ROUTED = AdminPortFilter.class.getName() + ".ROUTED";

    /** The values of the {@code Sec-Fetch-Site} request header (Fetch Metadata): where the request came from, as the browser states it. */
    private enum FetchSite {
        SAME_ORIGIN("same-origin"), SAME_SITE("same-site"), CROSS_SITE("cross-site"), NONE("none");

        private final String header;

        FetchSite(String header) {
            this.header = header;
        }

        /** Whether the header's text is this value. */
        boolean isSentAs(String text) {
            return header.equals(text);
        }
    }

    private final AdminPageAddress address;
    private final FilterRefusals refusals;

    AdminPortFilter(AdminPageAddress address, FilterRefusals refusals) {
        this.address = address;
        this.refusals = refusals;
    }

    static void common(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Cross-Origin-Opener-Policy", "same-origin");
        response.setHeader("Cross-Origin-Resource-Policy", "same-origin");
    }

    final void refuse(HttpServletRequest request, HttpServletResponse response, AdminRefusal refusal) {
        common(response);
        refusals.refuse(request, response, refusal.refusal());
    }

    /** What this route answers when the request names the loopback address instead of {@code localhost}: WebAuthn cannot use an IP address. */
    abstract void loopbackAddressed(HttpServletRequest request, HttpServletResponse response) throws IOException;

    private static boolean isJson(String contentType) {
        try {
            return contentType != null && MediaType.parseMediaType(contentType).equalsTypeAndSubtype(MediaType.APPLICATION_JSON);
        } catch (IllegalArgumentException unusable) {
            // Spring has no parse that reports a malformed media type as a value; the header is the client's input and a malformed one is a refusal.
            LOG.debug("The Content-Type header is not a media type", unusable);
            return false;
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        if (!address.owns(request.getLocalPort())) {
            refuse(request, response, AdminRefusal.NOT_FOUND);
            return;
        }
        request.setAttribute(ROUTED, Boolean.TRUE);
        String host = String.valueOf(request.getHeader("Host")).toLowerCase(Locale.ROOT);
        if (host.startsWith("127.0.0.1:")) {
            loopbackAddressed(request, response);
            return;
        }
        if (HttpMethod.POST.matches(request.getMethod())) {
            String site = request.getHeader("Sec-Fetch-Site");
            if (!address.origin().equals(request.getHeader("Origin")) || (site != null && !FetchSite.SAME_ORIGIN.isSentAs(site))) {
                refuse(request, response, AdminRefusal.ADMIN_ORIGIN_REJECTED);
                return;
            }
            if (!isJson(request.getContentType())) {
                refuse(request, response, AdminRefusal.CONTENT_TYPE_REJECTED);
                return;
            }
        }
        common(response);
        chain.doFilter(request, response);
    }
}
