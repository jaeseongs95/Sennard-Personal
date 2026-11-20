// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.domain.error;

/**
 * Reasons of {@link Category#UNAVAILABLE}. The {@code *_FULL} codes are the messaging's capacity limits; they carry {@code scope}, {@code release} and {@code earliestReleaseAt}.
 * The continuity one is published under {@code CONTINUITY_UNAVAILABLE}, with its reason in {@code details.reason}.
 */
public enum UnavailableCode implements ErrorCode {
    STORE_UNAVAILABLE("services.failure.storeUnavailable"),
    BOARD_NOT_RECORDED("server.sessionBoard.notRecorded"),
    BOARD_LIVENESS_UNAVAILABLE("server.sessionBoard.livenessUnavailable"),
    MESSAGE_PREPARATION_FULL("services.sessionMessage.prepare.capacityRejected"),
    MESSAGE_SENDER_RECEIPTS_FULL_NO_DRAFT("services.sessionMessage.prepare.senderReceiptsFull"),
    MESSAGE_RECEIPTS_FULL_NO_DRAFT("services.sessionMessage.prepare.receiptsFull"),
    MESSAGE_SENDER_RECEIPTS_FULL("services.sessionMessage.send.senderReceiptsFull"),
    MESSAGE_RECEIPTS_FULL("services.sessionMessage.send.capacityRejected"),
    MESSAGE_SPOOL_FULL("services.sessionMessage.send.spoolFull"),
    /** A send the queue refused for a reason of its own; {@code reason} (an argument) is that reason's sentence, and the sender is told to retry only the known ID. */
    SEND_REJECTED("services.sessionMessage.send.retryAfterRejection"),
    /** A send that could not be completed (the store failed); the outcome may be unknown, so the sender retries only the known ID. */
    SEND_FAILED("services.sessionMessage.send.retryAfterFailure"),

    /** A failure nobody expected, answered with a fixed sentence and its reason in {@code details.reason}; the envelope has had no reason code for it. */
    SERVER_FAILURE("services.failure.serverUnavailable", false),
    /** A continuity operation failed; the envelope's {@code details.reason} keeps the store's stable reason. */
    CONTINUITY_UNAVAILABLE("continuity.operation.failed", "CONTINUITY_UNAVAILABLE", false),
    /** The skill classification refused a request for a reason of the server, not of the request ({@code details.reason}); the envelope has had no reason code for it. */
    CLASSIFICATION_RUNTIME_UNAVAILABLE("server.classification.runtimeUnavailable", false),
    CLASSIFICATION_CAPACITY_EXCEEDED("server.classification.capacityExceeded", false),
    CLASSIFICATION_CANCELLED("server.classification.cancelled", false),
    CLASSIFICATION_FAILED("server.classification.failed", false),

    // A folder or a file that holds a secret (a key, a password, the settings database) is not what the storage of secrets (the port {@code PrivateStorage}) needs: it is refused and nothing is
    // repaired or skipped. The {@code path} argument names the folder or the file, never its content.
    /** A secret file has no byte: it is no secret, and the caller judges what an empty one means for it (an attachment key: ARTIFACT_KEY_MISSING). */
    PRIVATE_STORAGE_EMPTY("services.privateStorage.empty"),
    /** The path is not a regular file where a file is required, or not a folder where a folder is required. */
    PRIVATE_STORAGE_NOT_PLAIN("services.privateStorage.notPlain"),
    /** The file store offers neither permission bits nor an access list, so a folder or file that is private to its owner cannot be made there and the path is not used. */
    PRIVATE_STORAGE_UNSUPPORTED("services.privateStorage.unsupported"),
    /** The account the process runs as is not known to the file system, so a file that is private to it cannot be made. */
    PRIVATE_STORAGE_USER_UNKNOWN("services.privateStorage.userUnknown"),

    // The reasons a command of the sennard program stops (sennard-personal's CLI). The program prints the constant's name and then its sentence; their keys are the {@code cli} catalog's.
    ADMIN_RESET_DATABASE_UNREACHABLE("cli.adminResetDatabaseUnreachable"),
    ADMIN_RESET_FAILED("cli.adminResetFailed"),
    /** The settings database is held by another process (the server is still shutting down after its port closed, or a hook started it again): nothing was changed, and running the command again is what to do. */
    ADMIN_RESET_SETTINGS_LOCKED("cli.adminResetSettingsLocked"),
    COMMAND_INTERRUPTED("cli.commandInterrupted"),
    COMMAND_NOT_STARTED("cli.commandNotStarted"),
    COMMAND_TIMEOUT("cli.commandTimeout"),
    DELETE_FAILED("cli.deleteFailed"),
    HOST_FILES_FAILED("cli.hostFilesFailed"),
    /** A host reads a hook definition from a folder the user owns and this installation does not change; the arguments name the host, the files, the installation's definitions and its launcher. */
    HOST_HOOKS_NOT_REACHED("cli.hostHooksNotReached"),
    HOST_ID_NOT_WRITTEN("cli.hostIdNotWritten"),
    /** An installation step failed; the {@code what} argument names the step (the wordings of the sentence). */
    INSTALL_FAILED("cli.installFailed"),
    /** The JVM does not report the program file it runs from, so the installation cannot name the {@code java} its hook definitions and its server run with. */
    JAVA_EXECUTABLE_UNKNOWN("cli.javaExecutableUnknown"),
    JVM_SETTINGS_NOT_WRITTEN("cli.jvmSettingsNotWritten"),
    JVM_SETTINGS_UNREADABLE("cli.jvmSettingsUnreadable"),
    MEMORY_UNKNOWN("cli.memoryUnknown"),
    SERVER_UNREACHABLE("cli.serverUnreachable"),
    /** The server was not started; the {@code what} argument says why (the wordings of the sentence). */
    START_FAILED("cli.startFailed"),
    /** The server was not stopped; the {@code what} argument says why (the wordings of the sentence). */
    STOP_FAILED("cli.stopFailed"),
    STORE_PREPARATION_FAILED("cli.storePreparationFailed"),
    STORE_PREPARATION_TIMEOUT("cli.storePreparationTimeout"),

    // The attachment codes the three products share (docs/contributing/attachments.md, 3.2): each is published under its own name, with no reason code.
    /** A size, budget or open-upload limit is exceeded; {@code details.limit} says which. */
    ARTIFACT_LIMIT("error.ARTIFACT_LIMIT", "ARTIFACT_LIMIT", false),
    /** The disk is short of space once the file is written (requestedBytes, usableBytes, marginBytes). */
    ARTIFACT_STORAGE_FULL("error.ARTIFACT_STORAGE_FULL", "ARTIFACT_STORAGE_FULL", false),
    /** The disk could not be read or written. */
    ARTIFACT_STORAGE_IO_ERROR("error.ARTIFACT_STORAGE_IO_ERROR", "ARTIFACT_STORAGE_IO_ERROR", false),
    /** A storage path is not a regular file or folder (a file where a folder belongs, and the like); never an owner or an access (design version 10). */
    ARTIFACT_STORAGE_UNSAFE("error.ARTIFACT_STORAGE_UNSAFE", "ARTIFACT_STORAGE_UNSAFE", false),
    /** The key file of a row's key_id is not there. */
    ARTIFACT_KEY_MISSING("error.ARTIFACT_KEY_MISSING", "ARTIFACT_KEY_MISSING", false);

    private final String messageKey;
    private final String wireCode;
    private final boolean wireReason;

    UnavailableCode(String messageKey) {
        this(messageKey, Category.UNAVAILABLE.wireCode(), true);
    }

    UnavailableCode(String messageKey, boolean wireReason) {
        this(messageKey, Category.UNAVAILABLE.wireCode(), wireReason);
    }

    UnavailableCode(String messageKey, String wireCode, boolean wireReason) {
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
        return Category.UNAVAILABLE;
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
