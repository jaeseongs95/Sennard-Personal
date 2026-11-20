// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.domain.error;

/** Reasons of {@link Category#INTEGRITY}: what the store's declared constraints refuse, and what a stored value's own digest or size contradicts. */
public enum IntegrityCode implements ErrorCode {
    /** A foreign key points at a row that does not exist (or a row that others point at was removed). */
    REFERENCE_VIOLATION("error.REFERENCE_VIOLATION"),
    /** A CHECK constraint or a column's type refuses the value (an enum name that is not a constant, a NULL where a value is required). */
    VALUE_NOT_ALLOWED("error.VALUE_NOT_ALLOWED"),

    /** A stored context layer is over its size limit or off its digest ({@code details.layer}). */
    CONTEXT_LAYER_INTEGRITY_FAILED("continuity.layers.integrity"),
    /** A stored continuity snapshot is over its size limit or off its digest; the envelope has had no reason code for it. */
    SNAPSHOT_DIGEST_INVALID("continuity.snapshot.digestInvalid", false),

    // What a skill's check refuses because the input contradicts a digest, a lock or a binding it carries (sennard-server's skills package); the check's answer publishes them under
    // INTEGRITY_FAILED.
    CS_ENGINEERING_ARTIFACT_DIGEST_MISMATCH("skillTools.csEngineering.artifactDigestMismatch"),
    CS_ENGINEERING_DIGEST_MISMATCH("skillTools.csEngineering.digestMismatch"),
    CS_ENGINEERING_LOCK_MISSING_SOURCE("skillTools.csEngineering.lockMissingSource"),
    CS_ENGINEERING_KNOWLEDGE_VERSION_MISMATCH("skillTools.csEngineering.knowledgeVersionMismatch"),
    CS_ENGINEERING_POLICY_KNOWLEDGE_CHANGED("skillTools.csEngineering.policyKnowledgeChanged"),
    CS_ENGINEERING_REQUIRED_OBLIGATIONS_CHANGED("skillTools.csEngineering.requiredObligationsChanged"),
    CS_ENGINEERING_STAGE_TASK_DIFFERS("skillTools.csEngineering.stageTaskDiffers"),

    // engineering practices
    PRACTICES_IO_ARTIFACT_DIGEST_MISMATCH("practices.io.artifactDigestMismatch"),
    PRACTICES_CORE_SNAPSHOT_DIGEST_MISMATCH("practices.core.snapshotDigestMismatch"),
    PRACTICES_CORE_CURRENT_FILES_DIFFER_FROM_THE_DECLARED("practices.core.currentFilesDifferFromTheDeclared"),
    PRACTICES_CORE_RECEIPT_LOG_DIGEST_MISMATCH("practices.core.receiptLogDigestMismatch"),
    PRACTICES_CORE_RECEIPT_DIGEST_MISMATCH("practices.core.receiptDigestMismatch"),
    PRACTICES_CORE_PROOF_BELONGS_TO_A_DIFFERENT_FROZEN("practices.core.proofBelongsToADifferentFrozen"),
    PRACTICES_CORE_GREEN_EVIDENCE_IS_FOR_A_DIFFERENT("practices.core.greenEvidenceIsForADifferent"),
    PRACTICES_CORE_TEST_CODE_DIFFERS_BETWEEN_RED_AND("practices.core.testCodeDiffersBetweenRedAnd"),
    PRACTICES_CORE_REVIEW_DOES_NOT_BIND_THIS_REQUEST("practices.core.reviewDoesNotBindThisRequest"),
    PRACTICES_CORE_A_SUPPOSEDLY_DELETED_FILE_IS_STILL("practices.core.aSupposedlyDeletedFileIsStill"),
    PRACTICES_STAGE_STAGE_BUNDLE_BYTES_CHANGED("practices.stageBundle.stageBundleBytesChanged"),
    PRACTICES_STAGE_BUNDLE_DOES_NOT_BIND_THE_SIGNED("practices.stageBundle.bundleDoesNotBindTheSigned"),
    PRACTICES_STAGE_STAGE_RESULT_DIFFERS_FROM_CURRENT_RAW("practices.stageBundle.stageResultDiffersFromCurrentRaw"),
    PRACTICES_STAGE_PREPARED_INPUT_DOES_NOT_BIND_THIS("practices.stageBundle.preparedInputDoesNotBindThis"),

    /** The installation's host identity file does not hold a UUID (sennard-personal's CLI). */
    HOST_ID_INVALID("cli.hostIdInvalid"),

    // The attachment codes the three products share (docs/contributing/attachments.md, 3.2): each is published under its own name, with no reason code.
    /** The sha256 or the size a finish declared is not what the bytes are. */
    ARTIFACT_HASH_MISMATCH("error.ARTIFACT_HASH_MISMATCH", "ARTIFACT_HASH_MISMATCH"),
    /** A tag, hash, size or end mark of a stored file does not match, or the file got shorter. */
    ARTIFACT_CORRUPTED("error.ARTIFACT_CORRUPTED", "ARTIFACT_CORRUPTED"),
    /** The hash of a chunk is not the one the caller declared. */
    CHUNK_HASH_MISMATCH("error.CHUNK_HASH_MISMATCH", "CHUNK_HASH_MISMATCH");

    private final String messageKey;
    private final String wireCode;
    private final boolean wireReason;

    IntegrityCode(String messageKey) {
        this(messageKey, Category.INTEGRITY.wireCode(), true);
    }

    IntegrityCode(String messageKey, boolean wireReason) {
        this(messageKey, Category.INTEGRITY.wireCode(), wireReason);
    }

    /** A reason published under a code of its own and without a reason code. */
    IntegrityCode(String messageKey, String wireCode) {
        this(messageKey, wireCode, false);
    }

    IntegrityCode(String messageKey, String wireCode, boolean wireReason) {
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
        return Category.INTEGRITY;
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
