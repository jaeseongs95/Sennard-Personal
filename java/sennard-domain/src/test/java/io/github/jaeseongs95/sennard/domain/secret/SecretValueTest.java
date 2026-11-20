// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.domain.secret;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * A secret does not show its value (D-0269): not in its own text, not in the text of a record, a list, a map or an Optional that holds it, not in a formatted or concatenated string.
 * (Jackson and the log line are in sennard-app's SecretValueJsonTest, the module that has the JSON mapper and SLF4J.)
 */
class SecretValueTest {
    private static final String TEXT = "hunter2-password-0269";

    private record Credential(String user, SecretValue password, byte[] unrelated) {
    }

    private record Carrier(SecretValue first, List<SecretValue> more, Map<String, SecretValue> byName) {
    }

    @Test
    void itsTextIsAFixedTextWithoutTheValue() {
        SecretValue secret = SecretValue.of(TEXT);
        assertEquals("SecretValue[redacted]", secret.toString());
        assertFalse(secret.toString().contains("hunter2"));
        assertFalse(("value: " + secret).contains("hunter2"));
        assertFalse(String.format("%s|%10s|%-30s", secret, secret, secret).contains("hunter2"));
        assertFalse(String.valueOf((Object) secret).contains("hunter2"));
    }

    @Test
    void theTextOfARecordCollectionAndOptionalThatHoldItHasNoValue() {
        SecretValue secret = SecretValue.of(TEXT);
        Credential credential = new Credential("admin", secret, new byte[0]);
        Carrier carrier = new Carrier(secret, List.of(secret), Map.of("k", secret));
        for (Object holder : new Object[] {credential, carrier, List.of(secret), Map.of("k", secret), Optional.of(secret)}) {
            assertFalse(holder.toString().contains("hunter2"), holder.toString());
            assertFalse(holder.toString().contains("password-0269"), holder.toString());
        }
        assertTrue(credential.toString().contains("SecretValue[redacted]"));
        assertTrue(credential.toString().contains("admin"));
    }

    @Test
    void theValueComesOutOnlyThroughTheExplicitMethodsAndAsACopy() {
        SecretValue secret = SecretValue.of(TEXT);
        char[] chars = secret.reveal();
        assertEquals(TEXT, new String(chars));
        SecretValue.clear(chars);
        assertArrayEquals(new char[TEXT.length()], chars, "the caller clears its copy");
        assertEquals(TEXT, new String(secret.reveal()), "clearing a copy does not change the secret");
        byte[] bytes = secret.revealBytes();
        assertArrayEquals(TEXT.getBytes(StandardCharsets.UTF_8), bytes);
        SecretValue.clear(bytes);
        assertArrayEquals(TEXT.getBytes(StandardCharsets.UTF_8), secret.revealBytes());
        assertEquals(TEXT.length(), secret.length());
    }

    @Test
    void theInputIsCopiedIn() {
        byte[] source = {1, 2, 3, 4};
        SecretValue secret = SecretValue.ofBytes(source);
        source[0] = 9;
        assertArrayEquals(new byte[] {1, 2, 3, 4}, secret.revealBytes());
        assertEquals(4, secret.length());
    }

    @Test
    void aKeyThatIsNoTextIsASecretToo() {
        byte[] key = {(byte) 0xff, (byte) 0xfe, 0, 7};
        assertArrayEquals(key, SecretValue.ofBytes(key).revealBytes());
        assertEquals("SecretValue[redacted]", SecretValue.ofBytes(key).toString());
    }

    @Test
    void textOutsideAsciiSurvivesTheRoundTrip() {
        assertEquals("비밀번호-0269", new String(SecretValue.of("비밀번호-0269").reveal()));
    }

    @Test
    void equalByContentWithAHashCodeThatTellsOnlyTheLength() {
        assertEquals(SecretValue.of("abc"), SecretValue.of("abc"));
        assertEquals(SecretValue.of("abc"), SecretValue.ofBytes("abc".getBytes(StandardCharsets.UTF_8)));
        assertNotEquals(SecretValue.of("abc"), SecretValue.of("abd"));
        assertNotEquals(SecretValue.of("abc"), "abc");
        assertEquals(SecretValue.of("abc").hashCode(), SecretValue.of("xyz").hashCode());
    }

    /** A value that its holder is done with is the JDK's {@link javax.security.auth.Destroyable}: its bytes are zeros afterwards and it cannot be read. */
    @Test
    void aDestroyedSecretHasZeroBytesAndCannotBeRead() throws Exception {
        SecretValue secret = SecretValue.of(TEXT);
        assertTrue(secret instanceof javax.security.auth.Destroyable);
        assertFalse(secret.isDestroyed());
        // The only way to see that the value's own bytes were overwritten, and not only that reading is refused, is to look at the field that holds them.
        java.lang.reflect.Field field = SecretValue.class.getDeclaredField("bytes");
        field.setAccessible(true);
        byte[] held = (byte[]) field.get(secret);
        assertFalse(Arrays.equals(new byte[held.length], held), "a live value holds its bytes");

        secret.destroy();

        assertTrue(secret.isDestroyed());
        assertArrayEquals(new byte[TEXT.length()], held, "the value's own bytes are zeros");
        assertThrows(IllegalStateException.class, secret::reveal);
        assertThrows(IllegalStateException.class, secret::revealBytes);
        assertEquals("SecretValue[redacted]", secret.toString());
        secret.destroy(); // a second call does nothing
        assertTrue(secret.isDestroyed());
    }

    @Test
    void aDestroyedSecretEqualsOnlyItselfAndDoesNotEqualAZeroedOne() {
        SecretValue destroyed = SecretValue.ofBytes(new byte[] {1, 2, 3});
        SecretValue zeros = SecretValue.ofBytes(new byte[] {0, 0, 0});
        destroyed.destroy();
        assertEquals(destroyed, destroyed);
        assertNotEquals(destroyed, zeros, "a destroyed value holds no content, so it is not equal to the zeros it was overwritten with");
        assertNotEquals(zeros, destroyed);
        assertEquals(zeros, SecretValue.ofBytes(new byte[] {0, 0, 0}));
    }

    @Test
    void aSecretIsNeverEmpty() {
        assertThrows(IllegalArgumentException.class, () -> SecretValue.of(""));
        assertThrows(IllegalArgumentException.class, () -> SecretValue.ofBytes(new byte[0]));
        assertThrows(NullPointerException.class, () -> SecretValue.of(null));
        assertThrows(NullPointerException.class, () -> SecretValue.ofBytes(null));
    }

    /** What a person typed is read as it is, an empty text included, so that the request's own validation names the field; only that entry makes an empty value. */
    @Test
    void anEmptyTextOfTheJsonEntryIsReadAndLeftToTheValidationOfTheRequest() {
        SecretValue empty = SecretValue.fromJson("");
        assertEquals(0, empty.length());
        assertEquals("SecretValue[redacted]", empty.toString());
        assertEquals(TEXT, new String(SecretValue.fromJson(TEXT).reveal()));
        assertThrows(NullPointerException.class, () -> SecretValue.fromJson(null));
    }
}
