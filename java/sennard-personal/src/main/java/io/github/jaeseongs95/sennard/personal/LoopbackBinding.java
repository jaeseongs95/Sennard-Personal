// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal;

import java.net.InetAddress;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * The personal edition listens on loopback only: the guard's peer check is about processes on this machine and means nothing for a connection from elsewhere.
 * Every listener the application opens is checked, not only the main one: {@code server.address}, and the actuator's {@code management.server.address}, which Boot applies when
 * {@code management.server.port} opens a listener of its own (the actuator endpoints, {@code shutdown} among them, are served there and not by the main server). An unset address
 * binds every interface and is refused like any other; a management port that is the main one, or negative (management over HTTP disabled), opens no listener of its own, so
 * only an address that is set must be loopback there.
 */
@Component
final class LoopbackBinding {
    /** Boot's default for {@code server.port}, which {@code management.server.port} is compared with when the main one is not set. */
    private static final int DEFAULT_SERVER_PORT = 8080;

    /** The management settings are read from the environment: the actuator's own properties bean is not there in every context (a packaged image, for one). */
    LoopbackBinding(ServerProperties server, Environment environment) {
        requireLoopback("server.address", server.getAddress());
        ManagementListener management = Binder.get(environment).bindOrCreate("management.server", ManagementListener.class);
        if (management.address() != null || listensApart(management.port(), server.getPort())) {
            requireLoopback("management.server.address", management.address());
        }
    }

    /** The two {@code management.server.*} settings this check reads, bound by Boot's binder; a setting that is not there is null (an unset address is refused above where it matters). */
    record ManagementListener(InetAddress address, Integer port) {
    }

    /** Boot serves the management endpoints on a listener of their own for any port that is set, not negative, and either 0 (a free one) or not the main port. */
    private static boolean listensApart(Integer managementPort, Integer serverPort) {
        if (managementPort == null || managementPort < 0) {
            return false;
        }
        int mainPort = serverPort == null ? DEFAULT_SERVER_PORT : serverPort;
        return managementPort == 0 || managementPort != mainPort;
    }

    private static void requireLoopback(String setting, InetAddress address) {
        if (address == null || !address.isLoopbackAddress()) {
            throw new IllegalStateException(setting + " must be a loopback address in the personal edition.");
        }
    }
}
