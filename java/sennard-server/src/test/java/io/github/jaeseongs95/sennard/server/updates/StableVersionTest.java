// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.updates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StableVersionTest {
    @ParameterizedTest
    @ValueSource(strings = {"1.3.0-rc.1", "abc", ""})
    void anUnrecognizedVersionIsNotParsed(String text) {
        assertTrue(StableVersion.tryParse(text).isEmpty());
    }

    @Test
    void aReleaseVersionIsParsed() {
        StableVersion version = StableVersion.tryParse("1.3.0").orElseThrow();
        assertEquals(new StableVersion(BigInteger.ONE, BigInteger.valueOf(3), BigInteger.ZERO), version);
    }

    @Test
    void strictParsingStillRejectsAPrerelease() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> StableVersion.parse("1.3.0-rc.1"));
        assertEquals("Not a release version: 1.3.0-rc.1", failure.getMessage());
    }
}
