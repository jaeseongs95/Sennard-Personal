// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.skills;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * D-0223: the sealed JEV key is sent to {@code sennard.typesafe.base-url} as a bearer token, so the address is bound the way the server binds it (Boot's binder, the record's
 * constructor): {@code https} always, {@code http} only on the loopback host and only when {@code allow-loopback-http} is set (a test's stand-in for the provider), and no user
 * information in the address. Any other address is refused when the settings are bound, so the server does not start with it.
 */
class TypeSafePropertiesTest {
    private static TypeSafeProperties bind(Map<String, String> settings) {
        return new Binder(new MapConfigurationPropertySource(settings)).bindOrCreate("sennard.typesafe", Bindable.of(TypeSafeProperties.class));
    }

    private static TypeSafeProperties bind(String baseUrl, boolean allowLoopbackHttp) {
        return bind(Map.of("sennard.typesafe.base-url", baseUrl, "sennard.typesafe.allow-loopback-http", Boolean.toString(allowLoopbackHttp)));
    }

    @Test
    void theDefaultIsTheProvidersHttpsAddressAndTheLoopbackExceptionIsOff() {
        TypeSafeProperties properties = bind(Map.of());
        assertEquals(TypeSafeProperties.DEFAULT_BASE_URL, properties.baseUrl());
        assertFalse(properties.allowLoopbackHttp());
        assertTrue(TypeSafeProperties.DEFAULT_BASE_URL.startsWith("https://"));
        assertEquals("https", URI.create(properties.baseUrl()).getScheme());
    }

    @Test
    void anHttpsAddressIsAcceptedAndItsTrailingSlashIsDropped() {
        assertEquals("https://provider.example/v1", bind("https://provider.example/v1/", false).baseUrl());
        assertEquals("https://provider.example/v1", bind("https://provider.example/v1", true).baseUrl());
    }

    @Test
    void anHttpAddressIsRefusedUnlessItIsTheLoopbackAndTheTestSettingIsOn() {
        assertThrows(BindException.class, () -> bind("http://provider.example/v1", false));
        assertThrows(BindException.class, () -> bind("http://127.0.0.1:8080/v1", false), "the loopback exception is off by default");
        assertThrows(BindException.class, () -> bind("http://provider.example/v1", true), "the setting allows the loopback host only");
        assertThrows(BindException.class, () -> bind("http://127.0.0.1.provider.example/v1", true), "a name that only starts like the loopback address is another host");
        assertEquals("http://127.0.0.1:8080/v1", bind("http://127.0.0.1:8080/v1", true).baseUrl());
        assertEquals("http://localhost:8080/v1", bind("http://localhost:8080/v1", true).baseUrl());
        assertEquals("http://[::1]:8080/v1", bind("http://[::1]:8080/v1", true).baseUrl());
    }

    @Test
    void anAddressThatIsNotAnHttpOneOrCarriesUserInformationOrCannotBeReadIsRefused() {
        assertThrows(BindException.class, () -> bind("ftp://provider.example/v1", true));
        assertThrows(BindException.class, () -> bind("provider.example/v1", true), "no scheme");
        assertThrows(BindException.class, () -> bind("https://user:secret@provider.example/v1", false));
        assertThrows(BindException.class, () -> bind("http://127.0.0.1@provider.example/v1", true), "the host is what follows the @");
        assertThrows(BindException.class, () -> bind("https://provider example/v1", false), "not a URI");
        assertThrows(BindException.class, () -> bind("  ", false));
    }

    @Test
    void theRefusalNamesTheSettingAndDoesNotRepeatTheSecretOfTheAddress() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> new TypeSafeProperties("https://user:secret@provider.example/v1", false));
        assertTrue(refused.getMessage().contains("sennard.typesafe.base-url"), refused.getMessage());
        assertFalse(refused.getMessage().contains("secret"), "the message does not carry the password");
    }
}
