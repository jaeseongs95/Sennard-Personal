// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.skills;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.jaeseongs95.sennard.domain.Digests;
import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.domain.error.InputCode;
import io.github.jaeseongs95.sennard.domain.error.IntegrityCode;
import io.github.jaeseongs95.sennard.domain.error.IntegrityException;
import io.github.jaeseongs95.sennard.domain.error.InvalidInputException;
import io.github.jaeseongs95.sennard.server.contract.CanonicalJson;

import tools.jackson.databind.json.JsonMapper;

/** Small assertions the checks share. A failed condition throws a catalog-keyed failure; the checks read as a list of rules. */
final class Checks {
    private static final Logger LOG = LoggerFactory.getLogger(Checks.class);

    /** An instant the way the skills' stored reports write it: UTC with milliseconds. */
    private static final DateTimeFormatter REPORT_INSTANT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private static final Pattern DIGEST = Pattern.compile("sha256:[a-f0-9]{64}");

    private Checks() {
    }

    /** An input refusal: the check will not judge this input. */
    static void need(boolean condition, InputCode code, Object... params) {
        if (!condition) {
            throw new InvalidInputException(code, SennardException.args(params));
        }
    }

    /** A refusal because the input contradicts a digest or a binding it carries. */
    static void need(boolean condition, IntegrityCode code, Object... params) {
        if (!condition) {
            throw new IntegrityException(code, SennardException.args(params));
        }
    }

    /** Like {@link #need(boolean, InputCode, Object...)} with details the caller reads beside the sentence. */
    static void needWith(boolean condition, InputCode code, Map<String, Object> details, Object... params) {
        if (!condition) {
            throw new InvalidInputException(code, SennardException.args(params), details);
        }
    }

    /** Each item by its key value, refusing a repeated value the way the contracts' identifiers require. */
    static <T> Map<String, T> uniqueBy(Collection<T> items, Function<T, String> key, InputCode duplicateKey, String label) {
        Map<String, T> map = new LinkedHashMap<>();
        for (T item : items) {
            String value = key.apply(item);
            need(!map.containsKey(value), duplicateKey, "label", label, "value", String.valueOf(value));
            map.put(value, item);
        }
        return map;
    }

    /** A date-time the contracts allow, or null. */
    static Instant instant(String value) {
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException notADateTime) {
            // java.time has no non-throwing parse; a value the contracts' schemas did not hold to date-time is answered as "no instant" by the caller's rule, and this log ends the handling.
            LOG.debug("not an ISO-8601 date-time with an offset: {}", value, notADateTime);
            return null;
        }
    }

    static String iso(Instant now) {
        return REPORT_INSTANT.format(now);
    }

    /** {@code sha256:} and the hex digest of these bytes (the one digest helper is {@link CanonicalJson}). */
    static String hash(byte[] bytes) {
        return "sha256:" + Digests.sha256Hex(bytes);
    }

    static String hash(String text) {
        return "sha256:" + Digests.sha256Hex(text);
    }

    /** The digest of a document's canonical form; {@code value} is a record, a map or a tree, converted the way Jackson writes it. */
    static String digest(JsonMapper mapper, Object value) {
        return CanonicalJson.digest(mapper.valueToTree(value));
    }

    /** {@code sha256:} and 64 lower-case hexadecimal digits, in full. */
    static boolean isDigest(String value) {
        return value != null && DIGEST.matcher(value).matches();
    }

    static boolean allUnique(List<?> values) {
        return new HashSet<>(values).size() == values.size();
    }

    /** {@code left} and {@code right} hold the same values, ignoring order and with no repeated value in either. */
    static boolean sameSet(Collection<String> left, Collection<String> right) {
        Set<String> a = new HashSet<>(left);
        Set<String> b = new HashSet<>(right);
        return left.size() == right.size() && a.size() == left.size() && b.size() == right.size() && a.equals(b);
    }
}
