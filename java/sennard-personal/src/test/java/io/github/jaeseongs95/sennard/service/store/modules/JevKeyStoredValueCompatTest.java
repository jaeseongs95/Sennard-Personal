// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.modules;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import io.github.jaeseongs95.sennard.service.personal.LocalFileSealer;
import io.github.jaeseongs95.sennard.service.personal.LocalPrivateStorage;
import io.github.jaeseongs95.sennard.service.secret.SealedValue;
import io.github.jaeseongs95.sennard.service.secret.SecretName;
import io.github.jaeseongs95.sennard.service.secret.SecretSealException;
import io.github.jaeseongs95.sennard.service.secret.SecretSealer;

/**
 * A JEV key that is already stored (sealed by the hand-written call the service made before the column was declared {@code @Encrypted}: the sealer's name is the key's name and its data is
 * the scope {@code sennard.jev-key} and the fingerprint) opens through the declaration of {@code ContextLayerStore.JevKeyRow}, with the edition's own sealer ({@link LocalFileSealer}, the real
 * AES-256-GCM and key file); and a value the declaration seals opens with that same hand-written call. So no stored value is migrated.
 *
 * <pre>
 * c1  stored before          a value sealed the old way opens through the declaration, as the original
 * c2  stored now             a value the declaration seals opens the old way (name, scope and fingerprint)
 * c3  still bound            the declaration's value does not open under another name or fingerprint
 * </pre>
 */
class JevKeyStoredValueCompatTest {
    private static final String SCOPE = "sennard.jev-key";
    private static final String NAME = "JEV_COMPAT";
    private static final String FINGERPRINT = "sha256:0123456789abcdef";
    private static final String KEY = "sk-compat-fixture-value";

    /** JUnit deletes this folder after each test. */
    @TempDir
    private Path folder;

    private LocalFileSealer sealer;
    private EncryptedRecords records;

    private void start() throws Exception {
        sealer = new LocalFileSealer(Files.createTempDirectory(folder, "seal-").resolve("service").resolve("jev-seal.key"), new SecureRandom(), new LocalPrivateStorage());
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("sealer", sealer);
        records = new EncryptedRecords(beans.getBeanProvider(SecretSealer.class));
    }

    private String sealedTheOldWay(String name, String fingerprint) {
        return sealer.seal(new SecretName(name), KEY.getBytes(StandardCharsets.UTF_8), Map.of("scope", SCOPE, "fingerprint", fingerprint)).text();
    }

    @Test
    void c1AValueSealedBeforeTheDeclarationOpensThroughIt() throws Exception {
        start();
        String stored = sealedTheOldWay(NAME, FINGERPRINT);
        ContextLayerStore.JevKeyRow opened = records.open(new ContextLayerStore.JevKeyRow(NAME, FINGERPRINT, stored, LocalDateTime.of(2026, 1, 1, 0, 0)));
        assertEquals(KEY, opened.secret());
    }

    @Test
    void c2AValueTheDeclarationSealsOpensTheOldWay() throws Exception {
        start();
        Object sealed = records.columns(new ContextLayerStore.JevKeyRow(NAME, FINGERPRINT, KEY, LocalDateTime.of(2026, 1, 1, 0, 0))).get("sealed");
        assertFalse(sealed.toString().contains(KEY));
        assertArrayEquals(KEY.getBytes(StandardCharsets.UTF_8), sealer.open(new SealedValue(sealed.toString()), new SecretName(NAME), Map.of("scope", SCOPE, "fingerprint", FINGERPRINT)));
    }

    @Test
    void c3TheValueIsStillBoundToItsNameAndFingerprint() throws Exception {
        start();
        String stored = sealedTheOldWay(NAME, FINGERPRINT);
        assertEquals("SECRET_UNREADABLE", assertThrows(SecretSealException.class, () -> records.open(new ContextLayerStore.JevKeyRow("JEV_OTHER", FINGERPRINT, stored, null))).code());
        assertEquals("SECRET_UNREADABLE", assertThrows(SecretSealException.class, () -> records.open(new ContextLayerStore.JevKeyRow(NAME, "sha256:other", stored, null))).code());
    }
}
