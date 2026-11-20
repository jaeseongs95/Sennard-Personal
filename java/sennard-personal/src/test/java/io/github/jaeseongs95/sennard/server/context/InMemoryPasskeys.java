// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.context;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.jaeseongs95.sennard.service.settings.PasskeyCredential;
import io.github.jaeseongs95.sennard.service.settings.PasskeyRegistration;
import io.github.jaeseongs95.sennard.service.settings.PasskeyRegistry;
import io.github.jaeseongs95.sennard.service.settings.PasskeyState;
import io.github.jaeseongs95.sennard.service.settings.SettingsCode;
import io.github.jaeseongs95.sennard.service.settings.SettingsException;

/**
 * A {@link PasskeyRegistry} held in memory, for the tests of the log's verification that need the registry's keys and not the settings database (which the server tests use):
 * it keeps the contract the H2 registry keeps (an id registered again with the same key is answered with the held key, with another key it is refused; a revoked key is found and listed; a key revoked again is answered as revoked).
 */
final class InMemoryPasskeys implements PasskeyRegistry {
    private static final Instant AT = Instant.parse("2026-11-11T00:00:00Z");

    private final Map<String, PasskeyCredential> keys = new LinkedHashMap<>();

    @Override
    public PasskeyCredential register(PasskeyRegistration registration) {
        PasskeyCredential held = keys.get(registration.credentialId());
        if (held != null) {
            if (held.registration().publicKey().equals(registration.publicKey()) && held.registration().algorithm() == registration.algorithm()) {
                return held;
            }
            throw new SettingsException(SettingsCode.PASSKEY_EXISTS, "The credential id is registered with another key.");
        }
        PasskeyCredential credential = new PasskeyCredential(registration, PasskeyState.ACTIVE, AT, null);
        keys.put(registration.credentialId(), credential);
        return credential;
    }

    @Override
    public Optional<PasskeyCredential> find(String credentialId) {
        return Optional.ofNullable(keys.get(credentialId));
    }

    @Override
    public List<PasskeyCredential> list() {
        return new ArrayList<>(keys.values());
    }

    @Override
    public PasskeyCredential revoke(String credentialId) {
        PasskeyCredential held = keys.get(credentialId);
        if (held == null) {
            throw new SettingsException(SettingsCode.PASSKEY_NOT_FOUND, "No passkey has this credential id.");
        }
        if (held.state() == PasskeyState.REVOKED) {
            return held;
        }
        PasskeyCredential revoked = new PasskeyCredential(held.registration(), PasskeyState.REVOKED, held.registeredAt(), AT);
        keys.put(credentialId, revoked);
        return revoked;
    }

    @Override
    public void clear() {
        keys.clear();
    }
}
