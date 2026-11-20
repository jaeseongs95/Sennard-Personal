// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.settings;

import java.util.regex.Pattern;

/**
 * A passkey to register: the credential id and the SPKI public key the browser exposes (base64url text), its algorithm and the label the user gave it. The public key is
 * kept here and never in the business database.
 */
public record PasskeyRegistration(String credentialId, String publicKey, PasskeyAlgorithm algorithm, String label) {
    public static final int CREDENTIAL_ID_MAX_LENGTH = 200;
    public static final int PUBLIC_KEY_MAX_LENGTH = 4000;
    public static final int LABEL_MAX_LENGTH = 255;
    private static final Pattern CREDENTIAL_ID = Pattern.compile("[A-Za-z0-9_-]{1," + CREDENTIAL_ID_MAX_LENGTH + "}");

    public PasskeyRegistration {
        SettingsChecks.text("credentialId", credentialId, CREDENTIAL_ID_MAX_LENGTH);
        if (!CREDENTIAL_ID.matcher(credentialId).matches()) {
            throw new SettingsException(SettingsCode.INVALID_SETTING, "credentialId is not base64url text.");
        }
        SettingsChecks.text("publicKey", publicKey, PUBLIC_KEY_MAX_LENGTH);
        SettingsChecks.required("algorithm", algorithm);
        SettingsChecks.text("label", label, LABEL_MAX_LENGTH);
    }
}
