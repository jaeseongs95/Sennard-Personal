// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.contract;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

import io.github.jaeseongs95.sennard.domain.Digests;
import io.github.jaeseongs95.sennard.domain.secret.SecretValue;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Sorted-key JSON without spaces, the byte form signatures and digests are made over. Jackson's writer is not used because the bytes are
 * fixed by this specification (the vectors in conformance/vectors): control characters as lower-case {@code \}{@code u00xx}, numbers in the shortest
 * form of ECMA-262 Number::toString.
 */
public final class CanonicalJson {
    private CanonicalJson() {
    }

    public static String of(JsonNode value) {
        StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    /** {@code sha256:} and the hex digest of the canonical form. */
    public static String digest(JsonNode value) {
        return "sha256:" + Digests.sha256Hex(of(value));
    }

    /** The {@code integrityToken} of a signed document: HMAC-SHA256, base64url without padding, of the canonical form without that token. */
    public static String integrityToken(JsonNode document, byte[] key) {
        ObjectNode unsigned = ((ObjectNode) document).deepCopy();
        unsigned.remove("integrityToken");
        return Base64.getUrlEncoder().withoutPadding().encodeToString(Digests.hmacSha256(key, of(unsigned).getBytes(StandardCharsets.UTF_8)));
    }

    /** The same under a key that is held as base64url text in a {@link SecretValue} (a signing key of the store): the decoded key lives only for this call and is cleared at its end. */
    public static String integrityToken(JsonNode document, SecretValue encodedKey) {
        byte[] encoded = encodedKey.revealBytes();
        byte[] key;
        try {
            key = Base64.getUrlDecoder().decode(encoded);
        } finally {
            SecretValue.clear(encoded);
        }
        try {
            return integrityToken(document, key);
        } finally {
            SecretValue.clear(key);
        }
    }

    private static void write(JsonNode node, StringBuilder out) {
        if (node.isObject()) {
            List<String> names = new ArrayList<>(node.propertyNames());
            Collections.sort(names);
            out.append('{');
            String separator = "";
            for (String name : names) {
                out.append(separator);
                quote(name, out);
                out.append(':');
                write(node.get(name), out);
                separator = ",";
            }
            out.append('}');
        } else if (node.isArray()) {
            out.append('[');
            String separator = "";
            for (JsonNode item : node) {
                out.append(separator);
                write(item, out);
                separator = ",";
            }
            out.append(']');
        } else if (node.isString()) {
            quote(node.asString(), out);
        } else if (node.isNumber()) {
            if (node.isFloatingPointNumber() && !Double.isFinite(node.doubleValue())) {
                throw new IllegalArgumentException("non-finite number");
            }
            out.append(number(node.decimalValue()));
        } else if (node.isBoolean()) {
            out.append(node.asBoolean());
        } else if (node.isNull()) {
            out.append("null");
        } else {
            throw new IllegalArgumentException("Value cannot be serialized canonically");
        }
    }

    /** ECMAScript number-to-string of the double this number denotes (ECMA-262 Number::toString): integers past 2^53 round to the double. */
    public static String number(BigDecimal value) {
        double number = value.doubleValue();
        if (Double.isNaN(number) || Double.isInfinite(number)) {
            throw new IllegalArgumentException("non-finite number");
        }
        if (number == 0) {
            return "0";
        }
        BigDecimal shortest = new BigDecimal(Double.toString(Math.abs(number))).stripTrailingZeros();
        // Double.toString always writes two digits and picks the closer two-digit decimal; Number::toString takes one digit when one identifies the value (5e-324, not 4.9e-324).
        BigDecimal oneDigit = shortest.round(new MathContext(1, RoundingMode.HALF_EVEN));
        if (shortest.precision() == 2 && oneDigit.doubleValue() == Math.abs(number)) {
            shortest = oneDigit.stripTrailingZeros();
        }
        int digits = shortest.precision();
        int exponent = shortest.precision() - shortest.scale(); // n in 10^(n-1) <= v < 10^n
        String text = shortest.unscaledValue().toString();
        StringBuilder out = new StringBuilder(number < 0 ? "-" : "");
        if (digits <= exponent && exponent <= 21) {
            out.append(text).append("0".repeat(exponent - digits));
        } else if (0 < exponent && exponent <= 21) {
            out.append(text, 0, exponent).append('.').append(text, exponent, digits);
        } else if (-6 < exponent && exponent <= 0) {
            out.append("0.").append("0".repeat(-exponent)).append(text);
        } else {
            int e = exponent - 1;
            out.append(text.charAt(0));
            if (digits > 1) {
                out.append('.').append(text, 1, digits);
            }
            out.append('e').append(e < 0 ? '-' : '+').append(Math.abs(e));
        }
        return out.toString();
    }

    private static void quote(String text, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    boolean lone = Character.isSurrogate(c) && !(Character.isHighSurrogate(c) && i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1)))
                            && !(Character.isLowSurrogate(c) && i > 0 && Character.isHighSurrogate(text.charAt(i - 1)));
                    if (c < 0x20 || lone) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
