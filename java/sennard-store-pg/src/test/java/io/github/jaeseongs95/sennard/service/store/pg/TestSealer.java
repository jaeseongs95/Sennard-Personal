// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.pg;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import io.github.jaeseongs95.sennard.service.secret.SealedValue;
import io.github.jaeseongs95.sennard.service.secret.SecretName;
import io.github.jaeseongs95.sennard.service.secret.SecretSealException;
import io.github.jaeseongs95.sennard.service.secret.SecretSealer;

/**
 * The sealer of the store tests, which have no edition: AES-256-GCM with a random IV per value and the name and the data as additional data, with a key of its own made when it is made, so
 * a value opens only under the name and data it was sealed with (the same contract as the edition's sealer, whose own tests are in sennard-personal). The stored text is not the edition's form.
 */
public final class TestSealer implements SecretSealer {
    /** The two codes of a failure of the sealer, as the edition's sealer names them. */
    private enum Refusal {
        SECRET_KEY_UNAVAILABLE, SECRET_UNREADABLE;

        SecretSealException failure(Throwable cause) {
            return new SecretSealException(name(), cause);
        }
    }

    private final byte[] key = new byte[32];
    private final SecureRandom random = new SecureRandom();

    public TestSealer() {
        random.nextBytes(key);
    }

    @Override
    public SealedValue seal(SecretName name, byte[] plaintext, Map<String, String> aad) {
        try {
            byte[] iv = new byte[12];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            cipher.updateAAD(additionalData(name, aad));
            Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
            return new SealedValue("t1." + encoder.encodeToString(iv) + "." + encoder.encodeToString(cipher.doFinal(plaintext)));
        } catch (GeneralSecurityException cause) {
            throw Refusal.SECRET_KEY_UNAVAILABLE.failure(cause);
        }
    }

    @Override
    public byte[] open(SealedValue sealed, SecretName name, Map<String, String> aad) {
        try {
            String[] parts = sealed.text().split("\\.", -1);
            if (parts.length != 3) {
                throw Refusal.SECRET_UNREADABLE.failure(null);
            }
            Base64.Decoder decoder = Base64.getUrlDecoder();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, decoder.decode(parts[1])));
            cipher.updateAAD(additionalData(name, aad));
            return cipher.doFinal(decoder.decode(parts[2]));
        } catch (GeneralSecurityException | IllegalArgumentException cause) {
            throw Refusal.SECRET_UNREADABLE.failure(cause);
        }
    }

    private static byte[] additionalData(SecretName name, Map<String, String> aad) {
        StringBuilder text = new StringBuilder(name.value());
        for (Map.Entry<String, String> entry : new TreeMap<>(aad).entrySet()) {
            text.append('\u0000').append(entry.getKey()).append('\u0000').append(entry.getValue());
        }
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }
}
