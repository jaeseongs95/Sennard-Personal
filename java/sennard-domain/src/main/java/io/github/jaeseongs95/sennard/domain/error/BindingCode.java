// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.domain.error;

/**
 * Reasons of {@link Category#BINDING}. The continuity reasons keep the {@code details.reasonCode} the contract has always shown for them (their names are those codes); the role
 * one is published under {@code BINDING_REQUIRED}.
 */
public enum BindingCode implements ErrorCode {
    /** The call names no session and the tool needs one; published under its own name, with {@code details.reasonCode} saying why no session could be told. */
    BINDING_REQUIRED("error.BINDING_REQUIRED", "BINDING_REQUIRED", true),
    /** The caller or session the call names is not valid (the host's call metadata contradicts itself, the row left for the call is another tool's). */
    BINDING_INVALID("error.BINDING_INVALID"),
    PEER_MAIN_SESSION_REQUIRED("error.PEER_MAIN_SESSION_REQUIRED"),

    /** The task binding's epoch is not the current one. */
    CONTINUITY_EPOCH_STALE("continuity.binding.epochStale"),
    CONTINUITY_CANDIDATE_MISMATCH("continuity.restore.candidateMismatch"),
    CONTINUITY_TOKEN_SIGNATURE_INVALID("continuity.token.verificationFailed"),
    CONTINUITY_TOKEN_INVALID("continuity.token.invalid"),
    CONTINUITY_TOKEN_EXPIRED("continuity.token.expired"),
    CONTINUITY_TOKEN_MALFORMED("continuity.token.malformed"),
    /** A role context call from a session that has no role binding; {@code details.reasonCode} says {@code ROLE_NOT_BOUND}, {@code details.adminUrl} where the user approves one. */
    CONTEXT_ROLE_UNBOUND("continuity.layers.roleUnbound", "BINDING_REQUIRED", false);

    private final String messageKey;
    private final String wireCode;
    private final boolean wireReason;

    BindingCode(String messageKey) {
        this(messageKey, Category.BINDING.wireCode(), true);
    }

    BindingCode(String messageKey, String wireCode, boolean wireReason) {
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
        return Category.BINDING;
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
