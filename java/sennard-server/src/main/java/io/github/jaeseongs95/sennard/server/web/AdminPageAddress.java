// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.web;

import org.apache.catalina.connector.Connector;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Where the admin page is: a second connector of the same server, on the loopback address, on a port the operating system gives when the server starts. Sennard does not choose the
 * port and no setting holds it, so a program that already uses a port can never stop the server or the page. The port is known once the connector is bound; every place that
 * needs it (the guard, the page's routes, the origin its passkey signatures are checked against and every text that tells the user where the page is) reads it here, so no two
 * of them can name different ports.
 *
 * <p>A passkey is bound to the host name {@value #HOST}, not to a port, so one that was registered on an earlier start still works on the page of this one.
 */
@Component
public final class AdminPageAddress implements WebServerFactoryCustomizer<TomcatServletWebServerFactory> {
    /** The host name of the page: WebAuthn does not accept an IP address as a relying party. */
    public static final String HOST = "localhost";

    /** The largest request header the page's connector takes; the page sends a few small ones. */
    private static final int MAX_HEADER_BYTES = 16 * 1024;

    private final Connector connector;

    public AdminPageAddress() {
        this.connector = new Connector("HTTP/1.1");
        connector.setPort(0);
        connector.setProperty("address", "127.0.0.1");
        connector.setProperty("maxHttpRequestHeaderSize", Integer.toString(MAX_HEADER_BYTES));
    }

    /** Adds the page's connector to the server's Tomcat, as Spring Boot's customizer hook for the embedded web server. */
    @Override
    public void customize(TomcatServletWebServerFactory factory) {
        factory.addAdditionalConnectors(connector);
    }

    /** The port the page is listening on; a refusal before the server has bound it, which no request can precede. */
    public int port() {
        int port = connector.getLocalPort();
        if (port <= 0) {
            throw new IllegalStateException("The admin page's connector is not listening yet.");
        }
        return port;
    }

    /** Whether a request was received by the page's connector; false before it is bound. */
    public boolean owns(int localPort) {
        return localPort > 0 && localPort == connector.getLocalPort();
    }

    /** The origin of the page, {@code http://localhost:<port>}: the origin a request or a passkey signature must carry, and the address the user is told to open. */
    public String origin() {
        return UriComponentsBuilder.newInstance().scheme("http").host(HOST).port(port()).build().toUriString();
    }
}
