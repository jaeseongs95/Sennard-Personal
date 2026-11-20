// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.web;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

import io.github.jaeseongs95.sennard.hookclient.HookHeaders;
import io.modelcontextprotocol.server.transport.DefaultServerTransportSecurityValidator;
import io.modelcontextprotocol.server.transport.HeaderAccessor;
import io.modelcontextprotocol.server.transport.ServerHttpHeaderValidator;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * The first filter in front of every path: it decides whether a request may reach anything at all, so it sits ahead of the MCP endpoint, the hooks, Actuator and
 * the admin page alike. It checks the headers that say where the request came from (Origin, Host) and the caller's time zone. The stages after it check the protocol version
 * ({@link McpProtocolVersionFilter}) and the body ({@link BodyGuardFilter}); only an accepted request reads a body. Who may connect is not asked here: the server listens
 * on 127.0.0.1 only (the {@code LoopbackBinding} refuses any other address), and another OS user of the same PC is outside what Sennard guards against.
 */
public final class GuardFilter extends GuardStage {
    private static final Logger LOG = LoggerFactory.getLogger(GuardFilter.class);

    private final AdminPageAddress address;
    private final Map<Integer, ServerHttpHeaderValidator> validatorsByPort = new ConcurrentHashMap<>();

    public GuardFilter(AdminPageAddress address, FilterRefusals refusals) {
        super(refusals);
        this.address = address;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        try {
            validator(request.getLocalPort()).validate(new RequestHeaders(request));
        } catch (ServerTransportSecurityException rejected) {
            LOG.debug("The transport security validator refused the request", rejected);
            reject(request, response, HttpStatusCode.valueOf(rejected.getStatusCode()), GuardRefusal.REQUEST_ORIGIN_OR_HOST_NOT_ALLOWED);
            return;
        }
        String zone = request.getHeader(HookHeaders.TIME_ZONE);
        if (zone != null && !SennardLocaleResolver.isZoneName(zone.strip())) {
            reject(request, response, HttpStatus.BAD_REQUEST, GuardRefusal.TIME_ZONE_NOT_VALID);
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * The SDK's validator: a request with an Origin header is refused (the SDK answers 403 for a non-blank Origin that matches no allowed one), except on the admin
     * page's port, where the page's own origin is allowed; the Host must be this server's own port, so one validator per listening port.
     */
    private ServerHttpHeaderValidator validator(int port) {
        return validatorsByPort.computeIfAbsent(port, p -> DefaultServerTransportSecurityValidator.builder()
                .allowedOrigins(address.owns(p) ? List.of(address.origin()) : List.of())
                .allowedHost("127.0.0.1:" + p)
                .allowedHost("localhost:" + p)
                .build());
    }

    private record RequestHeaders(HttpServletRequest request) implements HeaderAccessor {
        @Override
        public List<String> getHeader(String name) {
            return Collections.list(request.getHeaders(name));
        }

        @Override
        public List<String> getHeaderNames() {
            return Collections.list(request.getHeaderNames());
        }
    }
}
