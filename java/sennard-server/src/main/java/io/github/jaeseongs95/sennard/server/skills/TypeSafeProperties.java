// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.skills;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The settings of the TypeSafe adapter ({@code sennard.typesafe.*}): they belong to this provider and to no other, so they are not under {@code sennard.jev}. The one address of the
 * provider is read here by everything that talks to it or tells the user where a call goes ({@link TypeSafeClientConfiguration} builds the HTTP clients from it,
 * {@link TypeSafeProvider#endpoint()} shows it on the admin page), so what the page shows is what is called.
 *
 * <p>The sealed JEV key is sent to this address as a bearer token, so the address must be {@code https}. A plain {@code http} address is accepted only when
 * {@code allow-loopback-http} is set and the host is this computer's loopback: that is what a test needs to stand a local HTTP server in for the provider, and it keeps the key
 * on this computer. The setting is off by default. Any other address is refused when the settings are bound, so the server does not start with it.
 *
 * @param baseUrl the provider's API address ({@code sennard.typesafe.base-url}); a trailing slash is dropped
 * @param allowLoopbackHttp whether an {@code http} address on the loopback host is accepted ({@code sennard.typesafe.allow-loopback-http}); for tests only
 */
@ConfigurationProperties("sennard.typesafe")
public record TypeSafeProperties(@DefaultValue(TypeSafeProperties.DEFAULT_BASE_URL) String baseUrl, @DefaultValue("false") boolean allowLoopbackHttp) {
    public static final String DEFAULT_BASE_URL = "https://api.typesafe.ai/v1";

    private static final Set<String> SECURE_SCHEMES = Set.of("https");
    private static final Set<String> PLAIN_SCHEMES = Set.of("http");
    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

    public TypeSafeProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("sennard.typesafe.base-url must not be blank");
        }
        baseUrl = baseUrl.strip().replaceFirst("/+$", "");
        if (!keyMayBeSentTo(baseUrl, allowLoopbackHttp)) {
            throw new IllegalArgumentException("sennard.typesafe.base-url must be an https address (an http address on the loopback host only with sennard.typesafe.allow-loopback-http), "
                    + "without user information: " + baseUrl.replaceFirst("//[^/]*@", "//"));
        }
    }

    /** Whether the key may be sent to this address: {@code https}, or {@code http} to the loopback host when that is allowed; and no credentials inside the address. */
    private static boolean keyMayBeSentTo(String baseUrl, boolean allowLoopbackHttp) {
        URI address;
        try {
            address = new URI(baseUrl);
        } catch (URISyntaxException unreadable) {
            throw new IllegalArgumentException("sennard.typesafe.base-url is not an address: " + unreadable.getReason(), unreadable);
        }
        if (address.getHost() == null || address.getRawUserInfo() != null) {
            return false;
        }
        String scheme = address.getScheme() == null ? "" : address.getScheme().toLowerCase(Locale.ROOT);
        if (SECURE_SCHEMES.contains(scheme)) {
            return true;
        }
        return allowLoopbackHttp && PLAIN_SCHEMES.contains(scheme) && LOOPBACK_HOSTS.contains(address.getHost().toLowerCase(Locale.ROOT));
    }
}
