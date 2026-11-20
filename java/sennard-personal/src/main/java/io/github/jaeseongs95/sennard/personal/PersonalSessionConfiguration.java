// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal;

import java.nio.file.Path;
import java.security.SecureRandom;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

import io.github.jaeseongs95.sennard.service.secret.PrivateStorage;
import io.github.jaeseongs95.sennard.service.identity.CallAdmissions;
import io.github.jaeseongs95.sennard.service.identity.LeasePolicy;
import io.github.jaeseongs95.sennard.service.identity.LeaseService;
import io.github.jaeseongs95.sennard.service.identity.SessionKeys;
import io.github.jaeseongs95.sennard.service.identity.SessionRegistry;
import io.github.jaeseongs95.sennard.service.personal.LocalFileSealer;
import io.github.jaeseongs95.sennard.service.personal.local.SessionPepper;
import io.github.jaeseongs95.sennard.service.secret.SecretSealer;
import tools.jackson.databind.json.JsonMapper;

/**
 * The session services of the personal edition and the keys they seal with. The guard filter checks the request's headers (origin, host, protocol version, content type) and its
 * body size; it does not ask who connected. What keeps the connections local is the server's loopback-only listening address ({@code LoopbackBinding}); another OS user of the same
 * PC is outside what the edition guards against. The session pepper and the JEV sealing key live in this PC's state folder.
 */
@Configuration(proxyBeanMethods = false)
@Import({SessionRegistry.class, LeaseService.class, CallAdmissions.class, PersonalPrivateStorage.class})
public class PersonalSessionConfiguration {
    /** Seals a JEV key the user registered on the admin page. Its own key file: this one's loss must not orphan the session rows. */
    // @Bean: LocalFileSealer is made once per key file (the session pepper has its own, below), so its key file is a setting made here.
    @Bean
    SecretSealer jevKeySealer(PersonalProperties properties, SecureRandom random, PrivateStorage storage) {
        return new LocalFileSealer(properties.stateDir().resolve("service").resolve("jev-seal.key"), random, storage);
    }

    // @Bean: the value LeasePolicy.LOCAL of sennard-core's LeasePolicy, a constant and not a component.
    @Bean
    LeasePolicy leasePolicy() {
        return LeasePolicy.LOCAL;
    }

    /** A pepper file that exists and cannot be read stops the start: a new pepper would orphan every session row made under the old one. */
    // @Bean: SessionKeys is a class of sennard-core (no annotation of ours on it), made from the pepper this PC's state folder holds.
    @Bean
    SessionKeys sessionKeys(PersonalProperties properties, JsonMapper json, SecureRandom random, PrivateStorage storage) {
        Path service = properties.stateDir().resolve("service");
        return new SessionKeys(new SessionPepper(json, random, storage).loadOrCreate(service.resolve("session-pepper.json"), new LocalFileSealer(service.resolve("session-pepper.key"), random, storage)), random);
    }
}
