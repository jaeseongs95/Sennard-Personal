// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.context;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;

import io.github.jaeseongs95.sennard.domain.Digests;
import io.github.jaeseongs95.sennard.domain.error.SennardException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The WebAuthn check of the admin page with the JDK's own standards: base64url, URI, ByteBuffer, HexFormat and a {@code Signature}; the browser's client data is
 * read as a record.
 *
 * <pre>
 * w1  base64url    a value decodes with the JDK decoder, and what is not base64url (or is missing) is an invalid signature of that field
 * w2  fingerprint  four groups of four upper-case hex digits, the first 8 bytes of the key's SHA-256
 * w3  origin       the page's origin, with a port or without; any other scheme, host, user, path, query, empty or spelled port is not
 * w4  verify       a good assertion passes; each part a test bends is refused with its own code
 * w6  port         a signature that names any port of the page verifies: the page moves between starts, and the relying party is the host name
 * w5  client data  what the browser adds to the client data does not matter; client data that is no JSON object, or of an unknown type, is an invalid signature
 * </pre>
 */
class AdminWebAuthnTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final AdminWebAuthn WEBAUTHN = new AdminWebAuthn(MAPPER);
    private static final String INTENT = "{\"intent\":\"text\"}";

    private static SennardException refused(Runnable check) {
        return assertThrows(SennardException.class, check::run);
    }

    /** W1 */
    @Test
    void aValueIsBase64UrlOrAnInvalidSignatureOfItsField() {
        assertArrayEquals(new byte[] {(byte) 0xfb, (byte) 0xff}, AdminWebAuthn.bytes("-_8", "f"));
        assertArrayEquals(new byte[0], AdminWebAuthn.bytes("", "f"));
        for (String bad : new String[] {"a+b", "a/b", "a b", "ab\n", "!!!!", "a"}) {
            SennardException error =refused(() -> AdminWebAuthn.bytes(bad, "signature"));
            assertEquals("ADMIN_SIGNATURE_INVALID", error.code().wireCode(), bad);
            assertEquals("signature", error.details().get("field"), bad);
        }
        assertEquals("clientDataJSON", refused(() -> AdminWebAuthn.bytes(null, "clientDataJSON")).details().get("field"));
        assertEquals("AAEC", AdminWebAuthn.base64url(new byte[] {0, 1, 2}));
        assertEquals("AAE", AdminWebAuthn.base64url(new byte[] {0, 1}), "no padding");
    }

    /** W2 */
    @Test
    void aKeyFingerprintIsFourGroupsOfTheFirstEightBytesOfItsHash() {
        byte[] key = {1, 2, 3, 4, 5};
        String fingerprint = AdminWebAuthn.keyFingerprint(AdminPasskey.b64(key));
        String hex = HexFormat.of().withUpperCase().formatHex(Digests.sha256(key)).substring(0, 16);
        assertEquals(hex.substring(0, 4) + "-" + hex.substring(4, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12), fingerprint);
    }

    /**
     * W6: the page is on another port at each start, so a signature names the port of the start that made it and is still good later: the relying party is the host name
     * (its hash is the same whatever the port), and the origin check accepts the host with any port. The port a new signature must carry is checked when it is made.
     */
    @Test
    void aSignatureMadeOnAnotherPortOfThePageStillVerifies() throws GeneralSecurityException {
        AdminPasskey key = new AdminPasskey();
        String challenge = AdminWebAuthn.intentChallenge(INTENT);
        byte[] data = AdminPasskey.authenticatorData(AdminWebAuthn.RP_ID, 0x05);
        for (String origin : new String[] {"http://localhost:47615", "http://localhost:51234", "http://localhost:65535", "http://localhost:1024"}) {
            String client = "{\"type\":\"webauthn.get\",\"challenge\":\"" + challenge + "\",\"origin\":\"" + origin + "\"}";
            WEBAUTHN.verify(INTENT, assertion(key, client, data), key.publicKey(), -7);
            assertEquals(origin, WEBAUTHN.assertionOrigin(AdminPasskey.b64(client.getBytes(StandardCharsets.UTF_8))));
        }
    }

    /** W3 */
    @Test
    void onlyTheOriginOfTheAdminPageIsTheAdminOrigin() {
        for (String good : new String[] {"http://localhost", "http://localhost:47615", "http://localhost:3000", "http://localhost:1"}) {
            assertTrue(AdminWebAuthn.isAdminOrigin(good), good);
        }
        for (String bad : new String[] {"", "localhost", "https://localhost", "HTTP://localhost", "http://localhost:", "http://localhost/", "http://localhost:80/x", "http://localhost:80?x=1",
                "http://localhost#frag", "http://user@localhost", "http://localhost.evil.example", "http://127.0.0.1:47615", "http://localhost:0080", "http://localhost:abc", "http://localhost:47615 ",
                "http://[::1]:47615", "null"}) {
            assertFalse(AdminWebAuthn.isAdminOrigin(bad), bad);
        }
        assertFalse(AdminWebAuthn.isAdminOrigin(null));
    }

    /** W4 */
    @Test
    void aGoodAssertionPassesAndEachBentPartIsRefusedWithItsOwnCode() throws GeneralSecurityException {
        AdminPasskey key = new AdminPasskey();
        AdminWebAuthn.Assertion good = key.assertion(INTENT);
        WEBAUTHN.verify(INTENT, good, key.publicKey(), -7);
        assertEquals("ADMIN_CHALLENGE_MISMATCH", refused(() -> WEBAUTHN.verify("{\"other\":1}", good, key.publicKey(), -7)).code().wireCode());
        String challenge = AdminWebAuthn.intentChallenge(INTENT);
        String origin = "\"origin\":\"" + AdminPasskey.ORIGIN + "\"";
        byte[] data = AdminPasskey.authenticatorData("localhost", 0x05);
        String wrongOrigin = "{\"type\":\"webauthn.get\",\"challenge\":\"" + challenge + "\",\"origin\":\"http://localhost.evil.example\"}";
        SennardException phished =refused(() -> WEBAUTHN.verify(INTENT, assertion(key, wrongOrigin, data), key.publicKey(), -7));
        assertEquals("ADMIN_ORIGIN_REJECTED", phished.code().wireCode());
        String noOrigin = "{\"type\":\"webauthn.get\",\"challenge\":\"" + challenge + "\"}";
        assertEquals("ADMIN_ORIGIN_REJECTED", refused(() -> WEBAUTHN.verify(INTENT, assertion(key, noOrigin, data), key.publicKey(), -7)).code().wireCode());
        String client = "{\"type\":\"webauthn.get\",\"challenge\":\"" + challenge + "\"," + origin + "}";
        assertEquals("ADMIN_RPID_MISMATCH", refused(() -> WEBAUTHN.verify(INTENT, assertion(key, client, data("example.org", 0x05)), key.publicKey(), -7)).code().wireCode());
        for (int flags : new int[] {0x00, 0x01, 0x04}) {
            SennardException missing =refused(() -> WEBAUTHN.verify(INTENT, assertion(key, client, data("localhost", flags)), key.publicKey(), -7));
            assertEquals("ADMIN_USER_VERIFICATION_MISSING", missing.code().wireCode(), "flags " + flags);
            assertEquals((flags & 0x01) != 0, missing.details().get("userPresent"));
            assertEquals((flags & 0x04) != 0, missing.details().get("userVerified"));
        }
        AdminWebAuthn.Assertion shortData = new AdminWebAuthn.Assertion(good.credentialId(), AdminPasskey.b64(new byte[36]), good.clientDataJson(), good.signature());
        assertEquals("authenticatorData", refused(() -> WEBAUTHN.verify(INTENT, shortData, key.publicKey(), -7)).details().get("field"));
        AdminWebAuthn.Assertion forged = new AdminWebAuthn.Assertion(good.credentialId(), good.authenticatorData(), good.clientDataJson(), new AdminPasskey().assertion(INTENT).signature());
        assertEquals("signature", refused(() -> WEBAUTHN.verify(INTENT, forged, key.publicKey(), -7)).details().get("field"));
        AdminWebAuthn.Assertion unreadableSignature = new AdminWebAuthn.Assertion(good.credentialId(), good.authenticatorData(), good.clientDataJson(), "!!");
        assertEquals("signature", refused(() -> WEBAUTHN.verify(INTENT, unreadableSignature, key.publicKey(), -7)).details().get("field"));
        assertEquals("ADMIN_ALGORITHM_UNSUPPORTED", refused(() -> WEBAUTHN.verify(INTENT, good, key.publicKey(), -999)).code().wireCode());
        assertEquals("ADMIN_ALGORITHM_UNSUPPORTED", refused(() -> WEBAUTHN.verify(INTENT, good, key.publicKey(), -257)).code().wireCode(), "an EC key is no RSA key");
    }

    private static byte[] data(String rpId, int flags) {
        try {
            return AdminPasskey.authenticatorData(rpId, flags);
        } catch (GeneralSecurityException failed) {
            throw new IllegalStateException(failed);
        }
    }

    private static AdminWebAuthn.Assertion assertion(AdminPasskey key, String client, byte[] data) {
        try {
            return key.assertion(client, data);
        } catch (GeneralSecurityException failed) {
            throw new IllegalStateException(failed);
        }
    }

    /** W5 */
    @Test
    void whatTheBrowserAddsToTheClientDataDoesNotMatterAndClientDataThatIsNoClientDataIsRefused() throws GeneralSecurityException {
        AdminPasskey key = new AdminPasskey();
        String challenge = AdminWebAuthn.intentChallenge(INTENT);
        byte[] data = AdminPasskey.authenticatorData("localhost", 0x05);
        String extra = "{\"type\":\"webauthn.get\",\"challenge\":\"" + challenge + "\",\"origin\":\"" + AdminPasskey.ORIGIN + "\",\"crossOrigin\":false,\"topOrigin\":null,\"androidPackageName\":\"x\"}";
        WEBAUTHN.verify(INTENT, assertion(key, extra, data), key.publicKey(), -7);
        assertEquals(AdminPasskey.ORIGIN, WEBAUTHN.assertionOrigin(Base64.getUrlEncoder().withoutPadding().encodeToString(extra.getBytes(StandardCharsets.UTF_8))));

        String create = "{\"type\":\"webauthn.create\",\"challenge\":\"" + challenge + "\",\"origin\":\"" + AdminPasskey.ORIGIN + "\"}";
        assertEquals("type", refused(() -> WEBAUTHN.verify(INTENT, assertion(key, create, data), key.publicKey(), -7)).details().get("field"));
        String noType = "{\"challenge\":\"" + challenge + "\",\"origin\":\"" + AdminPasskey.ORIGIN + "\"}";
        assertEquals("type", refused(() -> WEBAUTHN.verify(INTENT, assertion(key, noType, data), key.publicKey(), -7)).details().get("field"));
        for (String bad : new String[] {"not json", "[]", "{\"type\":\"webauthn.get\"", "{\"type\":\"webauthn.other\"}", "{\"type\":[]}", "null", ""}) {
            SennardException error =refused(() -> WEBAUTHN.verify(INTENT, assertion(key, bad, data), key.publicKey(), -7));
            assertEquals("ADMIN_SIGNATURE_INVALID", error.code().wireCode(), bad);
            assertEquals("clientDataJSON", error.details().get("field"), bad);
            assertEquals("ADMIN_SIGNATURE_INVALID", refused(() -> WEBAUTHN.assertionOrigin(AdminPasskey.b64(bad.getBytes(StandardCharsets.UTF_8)))).code().wireCode(), bad);
        }
    }
}
