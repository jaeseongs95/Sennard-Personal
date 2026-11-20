// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.settings;

/** The COSE algorithms a registered passkey may use (the ones the admin signature check imports). The column holds the constant's name. */
public enum PasskeyAlgorithm {
    ES256(-7), RS256(-257), EDDSA(-8);

    private final int cose;

    PasskeyAlgorithm(int cose) {
        this.cose = cose;
    }

    /** The COSE algorithm identifier the browser reports. */
    public int cose() {
        return cose;
    }

    /** The algorithm of a COSE identifier; {@link SettingsCode#VALUE_OUTSIDE_SET} for one this edition does not verify. */
    public static PasskeyAlgorithm ofCose(int cose) {
        for (PasskeyAlgorithm algorithm : values()) {
            if (algorithm.cose == cose) {
                return algorithm;
            }
        }
        throw new SettingsException(SettingsCode.VALUE_OUTSIDE_SET, "The COSE algorithm is not one the passkey check verifies.");
    }
}
