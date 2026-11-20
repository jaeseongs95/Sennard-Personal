// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.attachment;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import io.github.jaeseongs95.sennard.domain.error.IntegrityCode;
import io.github.jaeseongs95.sennard.domain.error.IntegrityException;

/**
 * One segment of an attachment file (design 4.3), with the real JDK cipher and no database.
 *
 * <h2>Test cases (design test numbers of section 9)</h2>
 * <pre>
 * 4/5  s1  segment format     a segment is a 4-byte big-endian length, a 12-byte IV, the ciphertext and a 16-byte tag; the length counts what follows it; the end mark has no plain bytes
 * 6    s2  fresh IV           two seals of the same plain bytes under the same place differ, in the IV and in the ciphertext
 * 4/5  s3  AAD binds place    the storage key, the number of the segment and the last mark are authenticated: another value opens nothing (ARTIFACT_CORRUPTED, reason segment-tag)
 * 4    s4  changed byte        one changed bit in the IV, the ciphertext or the tag does not open
 * 4    s5  wrong key           another key does not open
 * 4    s6  short body          a body shorter than IV and tag is ARTIFACT_CORRUPTED, reason segment-short
 * 1    s7  round trip          plain bytes of 0, 1 and the largest chunk (262144 bytes) come back
 * </pre>
 */
class SegmentCipherTest {
    private static final String STORAGE_KEY = "0123456789abcdef0123456789abcdef";
    private static final int IV_FROM = SegmentCipher.LENGTH_BYTES;
    private static final int CIPHERTEXT_FROM = SegmentCipher.LENGTH_BYTES + SegmentCipher.IV_BYTES;

    private final SecureRandom random = new SecureRandom();
    private final SegmentCipher cipher = new SegmentCipher(random);

    private byte[] key() {
        byte[] key = new byte[AttachmentKeys.KEY_BYTES];
        random.nextBytes(key);
        return key;
    }

    private byte[] plain(int length) {
        byte[] plain = new byte[length];
        random.nextBytes(plain);
        return plain;
    }

    private static byte[] body(byte[] segment) {
        return Arrays.copyOfRange(segment, SegmentCipher.LENGTH_BYTES, segment.length);
    }

    private static void assertCorrupted(String reason, Runnable open) {
        IntegrityException failure = assertThrows(IntegrityException.class, open::run);
        assertEquals(IntegrityCode.ARTIFACT_CORRUPTED, failure.code());
        assertEquals(reason, failure.details().get("reason"));
        assertEquals(Boolean.FALSE, failure.details().get("retryable"), "a damaged file is not mended by sending the request again");
    }

    @Test
    void aSegmentIsALengthAnIvTheCiphertextAndATag() {
        byte[] key = key();
        byte[] plain = plain(100);
        byte[] segment = cipher.seal(key, STORAGE_KEY, 3, false, plain);

        assertEquals(SegmentCipher.LENGTH_BYTES + SegmentCipher.IV_BYTES + plain.length + SegmentCipher.TAG_BYTES, segment.length);
        assertEquals(SegmentCipher.OVERHEAD + plain.length, segment.length, "what a segment costs beyond its plain bytes is the length prefix, the IV and the tag");
        assertEquals(segment.length - SegmentCipher.LENGTH_BYTES, ByteBuffer.wrap(segment).getInt(), "the length counts the IV, the ciphertext and the tag, not itself");
        assertEquals(4 + 12 + 16, SegmentCipher.OVERHEAD, "the design's 4-byte length, 12-byte IV and 16-byte tag");
        assertArrayEquals(plain, cipher.open(key, STORAGE_KEY, 3, false, body(segment)));
        assertFalse(SegmentCipherTest.contains(segment, plain), "the plain bytes are not in the segment");
    }

    @Test
    void theEndMarkHasNoPlainBytesAndNothingBeyondTheOverhead() {
        byte[] key = key();
        byte[] mark = cipher.seal(key, STORAGE_KEY, 5, true, new byte[0]);

        assertEquals(SegmentCipher.OVERHEAD, mark.length);
        assertEquals(0, SegmentCipher.plainLength(ByteBuffer.wrap(mark).getInt()), "its length prefix says it holds no plain bytes");
        assertEquals(0, cipher.open(key, STORAGE_KEY, 5, true, body(mark)).length);
    }

    @Test
    void everySealMakesANewIvAndSoANewCiphertext() {
        byte[] key = key();
        byte[] plain = plain(64);
        byte[] first = cipher.seal(key, STORAGE_KEY, 0, false, plain);
        byte[] second = cipher.seal(key, STORAGE_KEY, 0, false, plain);

        assertFalse(Arrays.equals(Arrays.copyOfRange(first, IV_FROM, CIPHERTEXT_FROM), Arrays.copyOfRange(second, IV_FROM, CIPHERTEXT_FROM)), "the IV is new for each segment");
        assertFalse(Arrays.equals(first, second));
        assertArrayEquals(plain, cipher.open(key, STORAGE_KEY, 0, false, body(first)));
        assertArrayEquals(plain, cipher.open(key, STORAGE_KEY, 0, false, body(second)));
    }

    @Test
    void theAdditionalDataBindsTheStorageKeyTheNumberAndTheLastMark() {
        byte[] key = key();
        byte[] body = body(cipher.seal(key, STORAGE_KEY, 2, false, plain(40)));

        assertArrayEquals(cipher.open(key, STORAGE_KEY, 2, false, body), cipher.open(key, STORAGE_KEY, 2, false, body), "control: its own place opens it");
        assertCorrupted("segment-tag", () -> cipher.open(key, "fedcba9876543210fedcba9876543210", 2, false, body)); // another file's place
        assertCorrupted("segment-tag", () -> cipher.open(key, STORAGE_KEY, 3, false, body)); // another number: segments in another order
        assertCorrupted("segment-tag", () -> cipher.open(key, STORAGE_KEY, 1, false, body));
        assertCorrupted("segment-tag", () -> cipher.open(key, STORAGE_KEY, 2, true, body)); // a segment is not the end mark of a file cut short at it
        byte[] mark = body(cipher.seal(key, STORAGE_KEY, 2, true, new byte[0]));
        assertCorrupted("segment-tag", () -> cipher.open(key, STORAGE_KEY, 2, false, mark)); // an end mark is not a data segment
    }

    @Test
    void aChangedByteInTheIvTheCiphertextOrTheTagDoesNotOpen() {
        byte[] key = key();
        byte[] segment = cipher.seal(key, STORAGE_KEY, 0, false, plain(50));
        int[] places = {IV_FROM, CIPHERTEXT_FROM, CIPHERTEXT_FROM + 49, segment.length - 1};

        for (int place : places) {
            byte[] changed = AttachmentTestKit.flipped(segment, place);
            assertCorrupted("segment-tag", () -> cipher.open(key, STORAGE_KEY, 0, false, body(changed)));
        }
    }

    @Test
    void anotherKeyDoesNotOpenASegment() {
        byte[] body = body(cipher.seal(key(), STORAGE_KEY, 0, false, plain(10)));

        assertCorrupted("segment-tag", () -> cipher.open(key(), STORAGE_KEY, 0, false, body));
    }

    @Test
    void aBodyShorterThanTheIvAndTheTagIsRefusedAsShort() {
        byte[] key = key();

        assertCorrupted("segment-short", () -> cipher.open(key, STORAGE_KEY, 0, false, new byte[SegmentCipher.IV_BYTES + SegmentCipher.TAG_BYTES - 1]));
        assertCorrupted("segment-short", () -> cipher.open(key, STORAGE_KEY, 0, false, new byte[0]));
    }

    @Test
    void plainBytesOfAnyLengthUpToTheLargestChunkComeBack() {
        byte[] key = key();
        int[] lengths = {0, 1, 255, 256, AttachmentFileStore.MAX_CHUNK_BYTES};

        for (int length : lengths) {
            byte[] plain = plain(length);
            byte[] segment = cipher.seal(key, STORAGE_KEY, 7, false, plain);
            assertEquals(SegmentCipher.OVERHEAD + length, segment.length);
            assertArrayEquals(plain, cipher.open(key, STORAGE_KEY, 7, false, body(segment)), "length " + length);
        }
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        return AttachmentTestKit.contains(haystack, needle);
    }
}
