// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.domain.error;

/** Reasons of {@link Category#NOT_FOUND}. The restore reasons are published under the contract's {@code SNAPSHOT_NOT_FOUND}, the {@code ADMIN_*} ones under their own names. */
public enum NotFoundCode implements ErrorCode {
    SESSION_NOT_FOUND("error.SESSION_NOT_FOUND"),
    /** No background job has the reference {@code get_background_job} was given. */
    JOB_NOT_FOUND("skillCheck.jobNotFound"),
    MESSAGE_ID_UNAVAILABLE("services.sessionMessage.message.issuedIdUnavailable"),

    RESTORE_SUPPRESSED("continuity.restore.suppressed", "SNAPSHOT_NOT_FOUND"),
    RESTORE_DIRECT_NOT_FOUND("continuity.restore.directNotFound", "SNAPSHOT_NOT_FOUND"),

    ADMIN_PROPOSAL_NOT_FOUND("error.ADMIN_PROPOSAL_NOT_FOUND", "ADMIN_PROPOSAL_NOT_FOUND"),
    ADMIN_ROOT_NOT_FOUND("admin.rootRelease.errorNotFound", "ADMIN_ROOT_NOT_FOUND"),

    /** No upload or attachment has this id (published under its own name, like the other attachment codes). */
    ARTIFACT_NOT_FOUND("error.ARTIFACT_NOT_FOUND", "ARTIFACT_NOT_FOUND"),

    /** A command of the sennard program needs an installation and there is none; the {@code what} argument says which command (the wordings of the sentence). */
    NOT_INSTALLED("cli.notInstalled");

    private final String messageKey;
    private final String wireCode;
    private final boolean wireReason;

    NotFoundCode(String messageKey) {
        this(messageKey, Category.NOT_FOUND.wireCode(), true);
    }

    /** A reason published under a code of its own and without a reason code. */
    NotFoundCode(String messageKey, String wireCode) {
        this(messageKey, wireCode, false);
    }

    NotFoundCode(String messageKey, String wireCode, boolean wireReason) {
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
        return Category.NOT_FOUND;
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
