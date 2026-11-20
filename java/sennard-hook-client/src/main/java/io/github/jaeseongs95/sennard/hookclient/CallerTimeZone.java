// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.hookclient;

import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;

/**
 * The time zone of the environment the command is called from, as the server wants it in {@link HookHeaders#TIME_ZONE}: an IANA region name such as
 * {@code Asia/Seoul}, from the {@code TZ} variable of the calling environment. It is never the JVM's default zone (the JVM is the one Sennard ships, not the caller's),
 * and a value that is not a region name (a POSIX rule such as {@code KST-9}, an offset, empty) is not guessed at: there is no zone to send, the header is left
 * out and the server answers in UTC.
 */
public final class CallerTimeZone {
    private static final String VARIABLE = "TZ";

    private CallerTimeZone() {
    }

    public static Optional<String> from(Map<String, String> environment) {
        String value = environment.get(VARIABLE);
        if (value == null) {
            return Optional.empty();
        }
        String name = value.startsWith(":") ? value.substring(1) : value; // POSIX allows ":Asia/Seoul"
        return ZoneId.getAvailableZoneIds().contains(name) ? Optional.of(name) : Optional.empty();
    }
}
