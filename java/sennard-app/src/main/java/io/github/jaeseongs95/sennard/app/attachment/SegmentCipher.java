// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.attachment;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * One segment of an attachment file (the design's 4.3): AES-256-GCM through the JDK's {@code AES/GCM/NoPadding}, no vendor library. A chunk is one segment, because the chunks
 * arrive one request each. A segment on disk is
 *
 * <pre>
 * 4 bytes   the length of what follows (IV + ciphertext + tag), big-endian
 * 12 bytes  IV, new for each segment
 * n bytes   ciphertext
 * 16 bytes  tag
 * </pre>
 *
 * The additional data binds a segment to its place: the storage key of the file, the number of the segment, and a mark that says whether it is the last one. Another file's segment
 * put in, segments in another order, and a file cut short (the new last segment was not written as the last) fail their tag. The last segment is an end mark with no plain bytes,
 * which a finish adds once the whole file is checked.
 */
@Component
public class SegmentCipher {
    static final int LENGTH_BYTES = 4;
    static final int IV_BYTES = 12;
    static final int TAG_BYTES = 16;
    /** What a segment costs beyond its plain bytes, the length prefix included. */
    static final int OVERHEAD = LENGTH_BYTES + IV_BYTES + TAG_BYTES;
    private static final Logger LOG = LoggerFactory.getLogger(SegmentCipher.class);
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private final SecureRandom random;

    public SegmentCipher(SecureRandom random) {
        this.random = random;
    }

    /** The bytes of the segment, length prefix included. */
    public byte[] seal(byte[] key, String storageKey, long number, boolean last, byte[] plain) {
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BYTES * 8, iv));
            cipher.updateAAD(additionalData(storageKey, number, last));
            byte[] sealed = cipher.doFinal(plain);
            ByteBuffer segment = ByteBuffer.allocate(LENGTH_BYTES + IV_BYTES + sealed.length);
            segment.putInt(IV_BYTES + sealed.length).put(iv).put(sealed);
            return segment.array();
        } catch (GeneralSecurityException cause) {
            throw ArtifactErrors.storageIo(cause);
        }
    }

    /**
     * The plain bytes of a segment given without its length prefix (IV, ciphertext and tag).
     *
     * @throws io.github.jaeseongs95.sennard.domain.error.IntegrityException ARTIFACT_CORRUPTED when the tag does not verify under this place: a changed byte, a changed order,
     *     another file's segment, a cut end
     */
    public byte[] open(byte[] key, String storageKey, long number, boolean last, byte[] body) {
        if (body.length < IV_BYTES + TAG_BYTES) {
            throw ArtifactErrors.corrupted("segment-short");
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BYTES * 8, body, 0, IV_BYTES));
            cipher.updateAAD(additionalData(storageKey, number, last));
            return cipher.doFinal(body, IV_BYTES, body.length - IV_BYTES);
        } catch (java.security.InvalidKeyException | java.security.InvalidAlgorithmParameterException | javax.crypto.NoSuchPaddingException | java.security.NoSuchAlgorithmException setup) {
            throw ArtifactErrors.storageIo(setup);
        } catch (GeneralSecurityException tag) {
            // AEADBadTagException and the other failures of a segment that does not open: the file is not what was written.
            LOG.debug("A segment of {} does not open", storageKey, tag);
            throw ArtifactErrors.corrupted("segment-tag");
        }
    }

    /** The plain length of a segment whose length prefix says {@code length}. */
    static long plainLength(long length) {
        return length - IV_BYTES - TAG_BYTES;
    }

    private static byte[] additionalData(String storageKey, long number, boolean last) {
        byte[] key = storageKey.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(key.length + Long.BYTES + 1).put(key).putLong(number).put((byte) (last ? 1 : 0)).array();
    }

}
