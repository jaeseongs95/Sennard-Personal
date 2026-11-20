// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.domain;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import io.github.jaeseongs95.sennard.domain.secret.SecretValue;

/**
 * The one place a SHA-256 digest and an HMAC-SHA256 are made. The JDK's {@link MessageDigest}, {@link Mac} and {@link HexFormat} do the work; this class
 * only fixes the algorithm and the text encoding, so a digest written by one module is the one another module computes. It holds no state: a {@code MessageDigest} and a
 * {@code Mac} are not thread-safe, so each call takes its own from the JDK's provider.
 */
public final class Digests {
    private static final String SHA_256 = "SHA-256";
    private static final String HMAC_SHA_256 = "HmacSHA256";

    private Digests() {
    }

    /** The SHA-256 of the text's UTF-8 bytes, in lower-case hex. */
    public static String sha256Hex(String text) {
        return HexFormat.of().formatHex(sha256(text.getBytes(StandardCharsets.UTF_8)));
    }

    /** The SHA-256 of the bytes, in lower-case hex. */
    public static String sha256Hex(byte[] bytes) {
        return HexFormat.of().formatHex(sha256(bytes));
    }

    /** The SHA-256 of the bytes. */
    public static byte[] sha256(byte[] bytes) {
        return newSha256().digest(bytes);
    }

    /** The SHA-256 of what the stream holds from here to its end, read in blocks; the stream is not closed. */
    public static byte[] sha256(InputStream in) throws IOException {
        MessageDigest digest = newSha256();
        new DigestInputStream(in, digest).transferTo(OutputStream.nullOutputStream());
        return digest.digest();
    }

    /**
     * A new SHA-256 for a caller that feeds it in pieces (a digest over several files, framed by the caller). It is the only place the algorithm is looked up by name;
     * the digest holds state and is not thread-safe, so each caller takes its own.
     */
    public static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance(SHA_256);
        } catch (GeneralSecurityException cause) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", cause);
        }
    }

    /** The HMAC-SHA256 of the parts, one after the other, under the key. */
    public static byte[] hmacSha256(byte[] key, byte[]... parts) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA_256);
            mac.init(new SecretKeySpec(key, HMAC_SHA_256));
            for (byte[] part : parts) {
                mac.update(part);
            }
            return mac.doFinal();
        } catch (GeneralSecurityException cause) {
            throw new IllegalStateException("HMAC-SHA256 is required by the Java platform", cause);
        }
    }

    /** The HMAC-SHA256 of the parts under a secret key: the key's bytes are revealed for this call only and cleared at its end. */
    public static byte[] hmacSha256(SecretValue key, byte[]... parts) {
        byte[] bytes = key.revealBytes();
        try {
            return hmacSha256(bytes, parts);
        } finally {
            SecretValue.clear(bytes);
        }
    }

    /** The HMAC-SHA256 of the text's UTF-8 bytes under a secret key (its bytes), in lower-case hex. */
    public static String hmacSha256Hex(SecretValue key, String text) {
        return HexFormat.of().formatHex(hmacSha256(key, text.getBytes(StandardCharsets.UTF_8)));
    }

    /** The HMAC-SHA256 of the text's UTF-8 bytes under the text's UTF-8 bytes as the key, in lower-case hex. */
    public static String hmacSha256Hex(String key, String text) {
        return HexFormat.of().formatHex(hmacSha256(key.getBytes(StandardCharsets.UTF_8), text.getBytes(StandardCharsets.UTF_8)));
    }
}
