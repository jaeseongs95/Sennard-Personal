// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.domain.error;

/**
 * Reasons of {@link Category#CONFLICT}. The reasons of the continuity and the context layers are published under the contract's finer codes
 * ({@code STALE_REVISION}, {@code REQUEST_CONFLICT}, {@code SNAPSHOT_CONFLICT}) and, but for {@code CONTEXT_LAYER_STATE_STALE}, without a reason code of their own (their
 * {@code details} carry the one the contract has always shown). The {@code ADMIN_*} reasons are the admin page's own codes, published under their own names.
 */
public enum ConflictCode implements ErrorCode {
    MESSAGE_ID_TAKEN("services.sessionMessage.message.idTaken"),
    /**
     * Another writer changed the same rows or the same key first (a serialization failure, a deadlock, a lost optimistic-lock race, an INSERT of a key another transaction has not committed
     * yet): this call has no effect, and the caller sends the same request again (nothing is run again for it). Published as {@code CONFLICT} with this name as the reason.
     */
    CONCURRENT_UPDATE("error.CONCURRENT_UPDATE"),
    /** The wake reservation changed during a claim; the transaction has no effect and the reserved marker stays intercepted. */
    NATIVE_WAKE_CLAIM_CHANGED("error.NATIVE_WAKE_CLAIM_CHANGED"),
    /** A unique key of the store already exists. */
    DUPLICATE("error.DUPLICATE"),

    // The refusals of the session registry (sennard-core). Each is published under its own name, which is also its reason code: the host's launcher and the tools of the board read them by it.
    /** Another process owns the session's current generation; {@code details} carry the {@code sessionRef} and {@code retryAfterMs}. */
    CURRENT_GENERATION_PROTECTED("error.CURRENT_GENERATION_PROTECTED", "CURRENT_GENERATION_PROTECTED", true),
    /** The generation the caller expected is not the current one; {@code details} carry the {@code sessionRef} and the current {@code generation}. */
    GENERATION_CHANGED("error.GENERATION_CHANGED", "GENERATION_CHANGED", true),
    /** A start older than the session's current generation; {@code details} carry the {@code sessionRef} and the current {@code generation}. */
    GENERATION_STALE("error.GENERATION_STALE", "GENERATION_STALE", true),
    /** Another call changed the session's row first (a lost race the registry could not settle on its own); the call has no effect and may be sent again. */
    STORE_CONFLICT("error.STORE_CONFLICT", "STORE_CONFLICT", true),

    /**
     * The operationKey of a write tool already succeeded for another content ({@code OperationReceipts}). Every write tool publishes it the same way: the code {@code REQUEST_CONFLICT},
     * {@code details.reason} {@code OPERATION_CONFLICT} and {@code details.field} {@code operationKey} (the exception carries both details, so no entrance has to add them).
     */
    OPERATION_KEY_CONFLICT("services.operation.keyConflict", "REQUEST_CONFLICT"),
    /**
     * Two first requests with one operationKey of one caller ran at the same moment, and this one met the other's receipt when it wrote its own: its work is rolled back. The caller sends the
     * same request again with the same key and is answered with the first one's result. Published like {@link #OPERATION_KEY_CONFLICT}, with {@code details.reason} {@code OPERATION_IN_PROGRESS}.
     */
    OPERATION_KEY_IN_PROGRESS("services.operation.keyInProgress", "REQUEST_CONFLICT"),
    CHECKPOINT_REQUEST_CONFLICT("continuity.checkpoint.requestConflict", "REQUEST_CONFLICT"),
    PURGE_REQUEST_CONFLICT("continuity.purge.requestConflict", "REQUEST_CONFLICT"),
    /** The state token the caller sent is not the current one ({@code details.reasonCode}, {@code expectedStateToken}, {@code actualStateToken}). */
    CHECKPOINT_STATE_CHANGED("continuity.checkpoint.stateChanged", "STALE_REVISION"),
    CHECKPOINT_REPLAY_UNAVAILABLE("continuity.checkpoint.replayUnavailable", "STALE_REVISION"),
    PURGE_STATE_CHANGED("continuity.purge.stateChanged", "STALE_REVISION"),
    PURGE_REPLAY_UNAVAILABLE("continuity.purge.replayUnavailable", "STALE_REVISION"),
    RESTORE_DIRECT_STALE("continuity.restore.directStale", "STALE_REVISION"),
    RESTORE_WORKFLOW_STALE("continuity.restore.workflowStale", "STALE_REVISION"),
    CONTINUITY_EPOCH_CHANGED("continuity.epoch.changed", "STALE_REVISION"),
    /** The task binding's epoch moved on while the call ran ({@code details.reason}). */
    CONTINUITY_BINDING_STALE("continuity.binding.stale", "STALE_REVISION"),
    SNAPSHOT_SOURCE_CHANGED("continuity.source.changed", "SNAPSHOT_CONFLICT"),
    STATE_CLEANUP_CANDIDATES_CHANGED("services.stateCleanup.candidatesChanged", "STALE_REVISION"),
    STATE_CLEANUP_TOKEN_USED("services.stateCleanup.tokenUsed", "STALE_REVISION"),
    CONTEXT_LAYER_STATE_STALE("continuity.layers.stateChanged", "STALE_REVISION", true),

    ADMIN_ROOT_NOT_ACTIVE("admin.rootRelease.errorNotActive", "ADMIN_ROOT_NOT_ACTIVE"),
    ADMIN_ROOT_STALE("admin.rootRelease.errorStale", "ADMIN_ROOT_STALE"),
    ADMIN_TARGET_STALE("error.ADMIN_TARGET_STALE", "ADMIN_TARGET_STALE"),
    ADMIN_ALREADY_BOOTSTRAPPED("error.ADMIN_ALREADY_BOOTSTRAPPED", "ADMIN_ALREADY_BOOTSTRAPPED"),
    ADMIN_LAST_KEY_REVOKE_REFUSED("error.ADMIN_LAST_KEY_REVOKE_REFUSED", "ADMIN_LAST_KEY_REVOKE_REFUSED"),
    ADMIN_LOG_REGRESSED("error.ADMIN_LOG_REGRESSED", "ADMIN_LOG_REGRESSED"),

    // The attachment codes the three products share (docs/contributing/attachments.md, 3.2): each is published under its own name, with no reason code.
    /** The same operationKey of the same author began a different upload. */
    ARTIFACT_OPERATION_CONFLICT("error.ARTIFACT_OPERATION_CONFLICT", "ARTIFACT_OPERATION_CONFLICT"),
    /** A chunk out of order, or a number already received that arrives with other content. */
    ARTIFACT_CHUNK_CONFLICT("error.ARTIFACT_CHUNK_CONFLICT", "ARTIFACT_CHUNK_CONFLICT"),
    /** Another request of the same upload is in progress: an append, or the begin of the same operationKey ({@code details.reason} {@code begin-in-progress}). */
    ARTIFACT_BUSY("error.ARTIFACT_BUSY", "ARTIFACT_BUSY"),
    /** A tail that is not yet taken into the row, or fewer bytes than the upload declared. */
    ARTIFACT_INCOMPLETE("error.ARTIFACT_INCOMPLETE", "ARTIFACT_INCOMPLETE"),
    /** The upload has expired. */
    ARTIFACT_EXPIRED("error.ARTIFACT_EXPIRED", "ARTIFACT_EXPIRED"),

    // The reasons a command of the sennard program stops on the state of the installation (sennard-personal's CLI).
    ALREADY_INSTALLED("cli.alreadyInstalled"),
    /** Another server of the same state folder holds its {@code server.lock}: one server runs per state folder, and the port is the operating system's choice at each start. */
    SERVER_ALREADY_RUNNING("cli.serverAlreadyRunning"),
    SAME_VERSION("cli.sameVersion");

    private final String messageKey;
    private final String wireCode;
    private final boolean wireReason;

    ConflictCode(String messageKey) {
        this(messageKey, Category.CONFLICT.wireCode(), true);
    }

    /** A reason published under a code of its own and without a reason code. */
    ConflictCode(String messageKey, String wireCode) {
        this(messageKey, wireCode, false);
    }

    ConflictCode(String messageKey, String wireCode, boolean wireReason) {
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
        return Category.CONFLICT;
    }

    @Override
    public String wireCode() {
        return wireCode;
    }

    @Override
    public boolean wireReason() {
        return wireReason;
    }

    /** Whether this is one of the two conflicts of an operationKey ({@code OperationReceipts}), which every write tool answers the same way: {@code REQUEST_CONFLICT} with {@code details.reason}. */
    public boolean isOperationKey() {
        return this == OPERATION_KEY_CONFLICT || this == OPERATION_KEY_IN_PROGRESS;
    }
}
