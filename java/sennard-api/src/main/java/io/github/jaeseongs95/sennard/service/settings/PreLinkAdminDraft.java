// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.settings;

/**
 * A signed admin record made before the business database is linked: what the passkey signed (the canonical intent) and the assertion's parts, with the hash that chains
 * it to the one before. {@code requestId} is null for a record made without one; a request id signs one record.
 */
public record PreLinkAdminDraft(String target, String intent, String credentialId, String authenticatorData, String clientDataJson, String signature, String recordHash,
        String requestId) {
    public static final int KEY_MAX_LENGTH = 255;

    public PreLinkAdminDraft {
        SettingsChecks.text("target", target, KEY_MAX_LENGTH);
        SettingsChecks.required("intent", intent);
        SettingsChecks.text("credentialId", credentialId, PasskeyRegistration.CREDENTIAL_ID_MAX_LENGTH);
        SettingsChecks.required("authenticatorData", authenticatorData);
        SettingsChecks.required("clientDataJson", clientDataJson);
        SettingsChecks.required("signature", signature);
        SettingsChecks.text("recordHash", recordHash, KEY_MAX_LENGTH);
        SettingsChecks.textOrNull("requestId", requestId, KEY_MAX_LENGTH);
    }
}
