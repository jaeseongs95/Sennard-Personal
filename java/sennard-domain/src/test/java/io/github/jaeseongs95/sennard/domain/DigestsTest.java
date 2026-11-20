// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.domain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;

/** The one digest helper. The expected values are the published test vectors (FIPS 180-4 for SHA-256, RFC 4231 case 2 for HMAC-SHA256), not output of this class. */
class DigestsTest {
    @Test
    void sha256OfTheTextIsTheStandardVectorInLowerCaseHex() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Digests.sha256Hex("abc"));
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Digests.sha256Hex(""));
    }

    @Test
    void sha256TakesTheUtf8BytesOfTheText() {
        String korean = "한글";
        assertEquals(HexFormat.of().formatHex(Digests.sha256(korean.getBytes(StandardCharsets.UTF_8))), Digests.sha256Hex(korean));
    }

    @Test
    void hmacOfTheStandardVectorIsTheOneTheRfcPublishes() {
        byte[] key = "Jefe".getBytes(StandardCharsets.UTF_8);
        byte[] data = "what do ya want for nothing?".getBytes(StandardCharsets.UTF_8);
        String expected = "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843";
        assertEquals(expected, HexFormat.of().formatHex(Digests.hmacSha256(key, data)));
        assertEquals(expected, Digests.hmacSha256Hex("Jefe", "what do ya want for nothing?"));
    }

    @Test
    void sha256OfBytesAndOfAStreamAreTheStandardVectors() throws IOException {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Digests.sha256Hex("abc".getBytes(StandardCharsets.US_ASCII)));
        // FIPS 180-4's long message: one million 'a', which is read in more than one block.
        byte[] million = new byte[1_000_000];
        Arrays.fill(million, (byte) 'a');
        assertEquals("cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0", HexFormat.of().formatHex(Digests.sha256(new ByteArrayInputStream(million))));
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", HexFormat.of().formatHex(Digests.sha256(InputStream.nullInputStream())));
    }

    @Test
    void aDigestFedInPiecesIsTheDigestOfTheWhole() {
        MessageDigest digest = Digests.newSha256();
        digest.update((byte) 'a');
        digest.update("bc".getBytes(StandardCharsets.US_ASCII));
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", HexFormat.of().formatHex(digest.digest()));
    }

    @Test
    void hmacOfRfc4231CaseOneIsThePublishedValue() {
        byte[] key = new byte[20];
        Arrays.fill(key, (byte) 0x0b);
        assertEquals("b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7", HexFormat.of().formatHex(Digests.hmacSha256(key, "Hi There".getBytes(StandardCharsets.US_ASCII))));
    }

    @Test
    void hmacOverPartsIsTheHmacOverTheirConcatenation() {
        byte[] key = {1, 2, 3};
        byte[] host = "claude".getBytes(StandardCharsets.UTF_8);
        byte[] id = "session-1".getBytes(StandardCharsets.UTF_8);
        byte[] joined = "claude\0session-1".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(Digests.hmacSha256(key, joined), Digests.hmacSha256(key, host, new byte[] {0}, id));
    }
}
