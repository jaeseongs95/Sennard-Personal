// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.context;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.UUID;

import io.github.jaeseongs95.sennard.service.settings.PasskeyAlgorithm;
import io.github.jaeseongs95.sennard.service.settings.PasskeyRegistration;

/** A WebAuthn credential in software (an EC P-256 key): the assertion a browser passkey returns for an intent, with the parts a test bends. */
final class AdminPasskey {
    static final String ORIGIN = "http://localhost:47615";
    final String credentialId = UUID.randomUUID().toString().replace("-", "");
    final KeyPair pair;

    AdminPasskey() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        pair = generator.generateKeyPair();
    }

    static String b64(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static byte[] sha256(byte[] value) throws GeneralSecurityException {
        return MessageDigest.getInstance("SHA-256").digest(value);
    }

    /** The SPKI key as the page sends it at registration. */
    String publicKey() {
        return b64(pair.getPublic().getEncoded());
    }

    /** The fingerprint a signed registration carries in place of the key. */
    String fingerprint() {
        return AdminWebAuthn.fingerprint(publicKey());
    }

    /** The registration of this key as the registry takes it (ES256). */
    PasskeyRegistration registration(String label) {
        return new PasskeyRegistration(credentialId, publicKey(), PasskeyAlgorithm.ES256, label);
    }

    /** The signed change that registers this key. */
    AdminChange.KeyAdd keyAdd(String label) {
        return new AdminChange.KeyAdd(credentialId, fingerprint(), -7, label);
    }

    /** The authenticator data: the hash of the relying party, the flags (5: present and verified) and a counter. */
    static byte[] authenticatorData(String rpId, int flags) throws GeneralSecurityException {
        return ByteBuffer.allocate(37).put(sha256(rpId.getBytes(StandardCharsets.UTF_8))).put((byte) flags).putInt(1).array();
    }

    /** The assertion over this intent, written as the browser writes it. */
    AdminWebAuthn.Assertion assertion(String intent) throws GeneralSecurityException {
        String client = "{\"type\":\"webauthn.get\",\"challenge\":\"" + AdminWebAuthn.intentChallenge(intent) + "\",\"origin\":\"" + ORIGIN + "\",\"crossOrigin\":false}";
        return assertion(client, authenticatorData("localhost", 0x05));
    }

    /** The assertion over these exact client data and authenticator data. */
    AdminWebAuthn.Assertion assertion(String clientDataJson, byte[] authenticatorData) throws GeneralSecurityException {
        byte[] client = clientDataJson.getBytes(StandardCharsets.UTF_8);
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(pair.getPrivate());
        signer.update(authenticatorData);
        signer.update(sha256(client));
        return new AdminWebAuthn.Assertion(credentialId, b64(authenticatorData), b64(client), b64(signer.sign()));
    }
}
