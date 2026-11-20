// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.domain.error;

/**
 * Reasons of {@link Category#GATE}. The board and the messaging have none yet; the workflow areas of phase 5 add theirs here. The {@code ADMIN_*} reasons are the refusals of
 * the admin page's signed changes (who may sign, from where), published under their own names; the HTTP boundary answers them 403, not as a gate's 422.
 */
public enum GateCode implements ErrorCode {
    GATE_FAILED("error.GATE_FAILED"),

    ADMIN_ORIGIN_REJECTED("error.ADMIN_ORIGIN_REJECTED", "ADMIN_ORIGIN_REJECTED"),
    ADMIN_KEY_NOT_REGISTERED("error.ADMIN_KEY_NOT_REGISTERED", "ADMIN_KEY_NOT_REGISTERED"),
    ADMIN_KEY_UNKNOWN("error.ADMIN_KEY_UNKNOWN", "ADMIN_KEY_UNKNOWN"),
    ADMIN_KEY_REVOKED("error.ADMIN_KEY_REVOKED", "ADMIN_KEY_REVOKED"),

    // The reasons a command of the sennard program refuses (sennard-personal's CLI); the HTTP boundary never answers them, but a gate is a gate: 422.
    ADMIN_RESET_NOT_PERMITTED("cli.adminResetNotPermitted"),
    DOWNGRADE_REFUSED("cli.downgradeRefused"),
    /** {@code sennard install} or {@code upgrade} was not started from a release jar (a Spring Boot jar file), so there is nothing to copy. */
    NOT_A_RELEASE_JAR("cli.notAReleaseJar"),
    UNINSTALL_FROM_INSTALLED_COPY("cli.uninstallFromInstalledCopy");

    private final String messageKey;
    private final String wireCode;
    private final boolean wireReason;

    GateCode(String messageKey) {
        this(messageKey, Category.GATE.wireCode(), true);
    }

    /** A reason published under a code of its own and without a reason code. */
    GateCode(String messageKey, String wireCode) {
        this(messageKey, wireCode, false);
    }

    GateCode(String messageKey, String wireCode, boolean wireReason) {
        this.messageKey = messageKey;
        this.wireCode = wireCode;
        this.wireReason = wireReason;
    }

    @Override
    public String messageKey() {
        return messageKey;
    }

    @Override
    public Category category() {
        return Category.GATE;
    }

    @Override
    public String wireCode() {
        return wireCode;
    }

    @Override
    public boolean wireReason() {
        return wireReason;
    }
}
