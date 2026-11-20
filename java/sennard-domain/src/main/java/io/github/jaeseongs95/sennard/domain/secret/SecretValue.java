// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.domain.secret;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Objects;

import javax.security.auth.Destroyable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * A secret (a password, a token, an API key, a signing key, a pepper) that does not appear when it is printed, logged, put in an exception message or written as JSON: {@link #toString()}
 * is a fixed text, so is the text of every record that holds one, and a JSON writer is refused (the one way in is the JSON a person types, {@link #fromJson}). The bytes are copied in
 * and out, and {@link #reveal()} / {@link #revealBytes()} are the only ways to read them; their caller is the code that hands the secret to the library that needs it (the JDBC driver,
 * an HTTP header, an HMAC) and it clears the copy when it is done ({@link #clear(char[])}, {@link #clear(byte[])}). A value that is an intermediate (read from a file or a request only to
 * be handed on) is {@linkplain #destroy() destroyed} by the code that is done with it: its own bytes are overwritten and it can no longer be read ({@link Destroyable}, the JDK's
 * contract for an object that holds a secret). The limit: the bytes are overwritten, not guarded, so a copy that a library made before the call, a value nobody destroyed, a swapped
 * page and a memory dump are not defended against, and the value is not cleared on a garbage collection.
 *
 * <p>The same design as the SSH portal's {@code SecretValue}, kept in the Sennard tree on purpose (the two products do not depend on each other). What differs: the bytes are held
 * as bytes, so that a key that is no text (a pepper, a data key) is a secret too, and two secrets are compared in constant time ({@link MessageDigest#isEqual}).
 */
public final class SecretValue implements Destroyable {

    private final byte[] bytes;
    private volatile boolean destroyed;

    private SecretValue(byte[] bytes) {
        this.bytes = bytes;
    }

    /** A secret of the text (kept as its UTF-8 bytes), which is not empty. */
    public static SecretValue of(CharSequence text) {
        Objects.requireNonNull(text, "text");
        if (text.isEmpty()) {
            throw new IllegalArgumentException("A secret is not empty");
        }
        ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(text));
        byte[] copy = new byte[encoded.remaining()];
        encoded.get(copy);
        Arrays.fill(encoded.array(), (byte) 0);
        return new SecretValue(copy);
    }

    /** A secret of a copy of the bytes, which are not empty. The caller clears its own array. */
    public static SecretValue ofBytes(byte[] value) {
        Objects.requireNonNull(value, "value");
        if (value.length == 0) {
            throw new IllegalArgumentException("A secret is not empty");
        }
        return new SecretValue(value.clone());
    }

    /**
     * The one way a JSON text a person typed becomes a secret (the admin page's key registration); every other JSON use of a secret is refused. An empty text is read as an empty value and
     * is not refused here: what a person typed is checked where the request is validated ({@code @SecretText} of sennard-server: at least one character), so that an empty one is a violation of
     * the field that names it and not a failure of reading the body. A value made any other way ({@link #of}, {@link #ofBytes}) is never empty, and a caller that takes a value from this
     * method hands it to that check before it uses it.
     */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static SecretValue fromJson(String text) {
        if (text != null && text.isEmpty()) {
            return new SecretValue(new byte[0]);
        }
        return of(text);
    }

    /** Overwrites the bytes of this value with zeros; after it {@link #reveal()} and {@link #revealBytes()} fail, and a second call does nothing. The one place that holds the value calls it when it is done with it. */
    @Override
    public void destroy() {
        destroyed = true;
        Arrays.fill(bytes, (byte) 0);
    }

    @Override
    public boolean isDestroyed() {
        return destroyed;
    }

    private void requireLive() {
        if (destroyed) {
            throw new IllegalStateException("The secret was destroyed");
        }
    }

    /** A copy of the characters (the bytes read as UTF-8); the caller clears it when it is done. */
    public char[] reveal() {
        requireLive();
        CharBuffer decoded = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(bytes));
        char[] copy = new char[decoded.remaining()];
        decoded.get(copy);
        Arrays.fill(decoded.array(), '\0');
        return copy;
    }

    /** A copy of the bytes; the caller clears it when it is done. */
    public byte[] revealBytes() {
        requireLive();
        return bytes.clone();
    }

    /** The number of bytes. */
    public int length() {
        return bytes.length;
    }

    /** Overwrites a copy that {@link #reveal()} gave. */
    public static void clear(char[] copy) {
        Arrays.fill(copy, '\0');
    }

    /** Overwrites a copy that {@link #revealBytes()} gave. */
    public static void clear(byte[] copy) {
        Arrays.fill(copy, (byte) 0);
    }

    /** What a JSON writer asks for: it is refused, so a secret that is put in a response, a log document or a stored record by mistake stops there and is never written. */
    @JsonValue
    private Object refusedByJson() {
        throw new UnsupportedOperationException("A secret is not written as JSON");
    }

    /** Equal by content, compared in constant time; a destroyed value holds no content and equals only itself. */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof SecretValue secret && !destroyed && !secret.destroyed && MessageDigest.isEqual(bytes, secret.bytes);
    }

    /** The length only, so the hash code tells nothing about the bytes. */
    @Override
    public int hashCode() {
        return Integer.hashCode(bytes.length);
    }

    @Override
    public String toString() {
        return "SecretValue[redacted]";
    }
}
