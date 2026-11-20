// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.identity;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

import io.github.jaeseongs95.sennard.domain.Digests;
import io.github.jaeseongs95.sennard.domain.secret.SecretValue;

/**
 * Identifiers and session keys: the digest that finds a host session
 * ({@code HMAC-SHA256(pepper, host 0x00 hostSessionId)}, one per pepper still valid while a rotation is under way). Where the pepper comes from is the assembly's business; this class takes the values.
 */
public final class SessionKeys {
    /** {@code as1_} and 26 Crockford base32 characters: 128 random bits and no meaning. */
    public static final Pattern SESSION_REF = Pattern.compile("^as1_[0-9A-HJKMNP-TV-Z]{26}$");
    private static final String CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

    /** A pepper and its version; the highest version is current. The key is a {@link SecretValue}, so the text of the record (a log line, an exception message) never shows it. */
    public record Pepper(int version, SecretValue key) {
        public Pepper {
            if (version < 1 || key == null) {
                throw new IllegalArgumentException("A pepper needs a version and a key");
            }
        }
    }

    /** One digest of a host session, with the pepper version that made it. The value is the digest in hex: the column that holds it is CHAR(64), and a database has no one name for bytes. */
    public record Digest(int version, String value) {
    }

    private final List<Pepper> peppers;
    private final SecureRandom random;

    /**
     * @param peppers the valid peppers; the highest version is used for new rows
     * @param random the application's one {@link SecureRandom}, which makes the session references
     */
    public SessionKeys(List<Pepper> peppers, SecureRandom random) {
        if (peppers.isEmpty()) {
            throw new IllegalArgumentException("At least one pepper is required");
        }
        List<Pepper> ordered = new ArrayList<>(peppers);
        ordered.sort(Comparator.comparingInt(Pepper::version).reversed());
        this.peppers = List.copyOf(ordered);
        this.random = random;
    }

    /** The digest for the current pepper, the value a new row stores. */
    public Digest currentDigest(String host, String hostSessionId) {
        return digest(peppers.get(0), host, hostSessionId);
    }

    /** One digest per valid pepper, current first: a row made under an older pepper is still found during a rotation. */
    public List<Digest> digests(String host, String hostSessionId) {
        return peppers.stream().map(pepper -> digest(pepper, host, hostSessionId)).toList();
    }

    private static Digest digest(Pepper pepper, String host, String hostSessionId) {
        byte[] key = pepper.key().revealBytes();
        try {
            return new Digest(pepper.version(), HexFormat.of().formatHex(Digests.hmacSha256(key, host.getBytes(StandardCharsets.UTF_8), new byte[] {0}, hostSessionId.getBytes(StandardCharsets.UTF_8))));
        } finally {
            SecretValue.clear(key);
        }
    }

    /** A new, unguessable sessionRef. */
    public String newSessionRef() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        StringBuilder text = new StringBuilder("as1_");
        long buffer = 0;
        int bits = 0;
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                bits -= 5;
                text.append(CROCKFORD.charAt((int) ((buffer >>> bits) & 31)));
            }
        }
        // 128 bits is 25 full groups and 3 left over; the last group is padded with zero bits.
        text.append(CROCKFORD.charAt((int) ((buffer << (5 - bits)) & 31)));
        return text.toString();
    }
}
