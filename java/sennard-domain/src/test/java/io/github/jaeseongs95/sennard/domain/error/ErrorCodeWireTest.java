// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.domain.error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * What the code enums publish, against what the strings they replaced said. Every failure that the server's tool and web boundaries answered from a string code
 * ({@code ToolFailure}, {@code ContractError}, {@code AdminError}) is now a constant, and its public form must be the old one: the same {@code error.code}, the same
 * catalog key and, where the contract showed one, the same {@code details.reasonCode}. Cases:
 *
 * <pre>
 * w1  the old literals  each migrated constant: its wire code, whether the envelope names it as the reason, and its catalog key are the old string's (the table below is the
 *                       list of the old literals, written out)
 * w2  the rest          every other constant (the board's, the messaging's, the prompt archive's) is published under its category's code with its own name as the reason;
 *                       the two contracts below are the only constants that are not: the attachments' and the operationKey's
 * w3  admin             an admin code is published under its own name, with no reason code, and its category decides its HTTP status (the gates are the 403s)
 * w4  a code's kind     a constant is published under its category's code, or under one of the contract's finer codes (the list below), or under its own name when it is a code of the
 *                       attachments' shared contract or of the session identity (w7), never under another text
 * w5  attachments       the common error codes of the attachments (docs/contributing/attachments.md 3.2, design editions 11 and 12) are a contract the three products share: every constant
 *                       the enums name that way (ARTIFACT_*, CHUNK_HASH_MISMATCH) is published under its own name with no reason code (the document's table is held to the enums by
 *                       RepositoryContractTest in sennard-repo-tools, which reads the repository's files, so no list is written out here)
 * w6  operationKey      the two conflicts of an operationKey (OperationReceipts, AGS3-D-0267 F4) are published as REQUEST_CONFLICT, and {@code details.reason} is added by the exception
 *                       ({@code ConflictCode#isOperationKey}); no other constant is one of them
 * w7  session identity  the seven reasons the session registry and the call binding refuse with (once the reasons of a ServiceFailure) are constants of the code enums under the same names,
 *                       and each names itself as the reason on the wire; the six that the hook launcher and the tools of the board read are published under their own names
 * </pre>
 */
class ErrorCodeWireTest {
    /** What a string said: the public code, whether {@code details.reasonCode} named the reason, the catalog key. */
    private record Old(String wire, boolean reason, String key) {
    }

    private static final String INPUT = "INVALID_INPUT";
    private static final String BINDING = "BINDING_INVALID";
    private static final String STALE = "STALE_REVISION";
    private static final Map<ErrorCode, Old> OLD = new HashMap<>();
    private static final String REQUEST_CONFLICT = "REQUEST_CONFLICT";
    /** The reasons of the session registry and the call binding, as a ServiceFailure named them (its seven reasons, which the hook launcher and the board's tools read by name). */
    private static final List<String> SESSION_IDENTITY = List.of("BINDING_INVALID", "BINDING_REQUIRED", "CURRENT_GENERATION_PROTECTED", "GENERATION_CHANGED", "GENERATION_STALE",
            "SESSION_NOT_FOUND", "STORE_CONFLICT");

    private static void old(ErrorCode code, String wire, boolean reason, String key) {
        assertTrue(OLD.put(code, new Old(wire, reason, key)) == null, code + " is written twice in the table");
    }

    static {
        // ToolEntrance, ToolCalls, ToolSupport: the contract errors of the tools' arguments, as ContractError("INVALID_INPUT", ...) and ToolFailure("INVALID_INPUT", ...)
        old(InputCode.TOOL_ARGUMENTS_MISMATCH, INPUT, false, "workflow.schema.contractMismatch");
        old(InputCode.CONTEXT_LAYER_ARGUMENTS_INVALID, INPUT, true, "continuity.layers.argumentsInvalid");
        old(InputCode.CONTEXT_LAYER_BODY_INVALID, INPUT, true, "continuity.layers.bodyInvalid");
        old(InputCode.CONTEXT_LAYER_TOO_LARGE, INPUT, true, "continuity.layers.tooLarge");
        old(InputCode.CONTEXT_LAYER_TOKEN_INVALID, INPUT, true, "continuity.layers.tokenInvalid");
        old(InputCode.CONTEXT_SESSION_INVALID, INPUT, true, "continuity.layers.sessionInvalid");
        old(InputCode.ROLE_NAME_INVALID, INPUT, true, "continuity.layers.roleInvalid");
        old(InputCode.ROLE_NOT_REGISTERED, INPUT, true, "continuity.layers.roleNotRegistered");
        old(InputCode.ROOT_OWNED_ELSEWHERE, INPUT, false, "continuity.binding.rootOwnedElsewhere");
        old(InputCode.CONTINUITY_MEMORY_TOO_LARGE, INPUT, true, "continuity.checkpoint.tooLarge");
        old(InputCode.STATE_CLEANUP_TOKEN_TOO_LARGE, INPUT, false, "services.stateCleanup.tokenTooLarge");
        old(InputCode.STATE_CLEANUP_TOKEN_MALFORMED, INPUT, false, "services.stateCleanup.tokenMalformed");
        old(InputCode.STATE_CLEANUP_TOKEN_SIGNATURE_INVALID, INPUT, false, "services.stateCleanup.tokenSignatureInvalid");
        old(InputCode.STATE_CLEANUP_TOKEN_PAYLOAD_INVALID, INPUT, false, "services.stateCleanup.tokenPayloadInvalid");
        old(InputCode.GLOSSARY_SOURCE_DIGEST_MISMATCH, INPUT, false, "services.glossary.sourceDigestMismatch");
        old(InputCode.CLASSIFICATION_INPUT_MISMATCH, INPUT, false, "server.classification.inputMismatch");
        old(InputCode.CLASSIFICATION_REQUEST_REJECTED, INPUT, false, "server.classification.requestRejected");
        old(InputCode.UPDATES_ARGUMENTS_INVALID, INPUT, false, "server.updates.argumentsInvalid");
        old(InputCode.SEND_INVALID, INPUT, false, "server.sessionMessage.sendInvalid");

        old(ConflictCode.CHECKPOINT_REQUEST_CONFLICT, "REQUEST_CONFLICT", false, "continuity.checkpoint.requestConflict");
        old(ConflictCode.PURGE_REQUEST_CONFLICT, "REQUEST_CONFLICT", false, "continuity.purge.requestConflict");
        old(ConflictCode.CHECKPOINT_STATE_CHANGED, STALE, false, "continuity.checkpoint.stateChanged");
        old(ConflictCode.CHECKPOINT_REPLAY_UNAVAILABLE, STALE, false, "continuity.checkpoint.replayUnavailable");
        old(ConflictCode.PURGE_STATE_CHANGED, STALE, false, "continuity.purge.stateChanged");
        old(ConflictCode.PURGE_REPLAY_UNAVAILABLE, STALE, false, "continuity.purge.replayUnavailable");
        old(ConflictCode.RESTORE_DIRECT_STALE, STALE, false, "continuity.restore.directStale");
        old(ConflictCode.RESTORE_WORKFLOW_STALE, STALE, false, "continuity.restore.workflowStale");
        old(ConflictCode.CONTINUITY_EPOCH_CHANGED, STALE, false, "continuity.epoch.changed");
        old(ConflictCode.CONTINUITY_BINDING_STALE, STALE, false, "continuity.binding.stale");
        old(ConflictCode.SNAPSHOT_SOURCE_CHANGED, "SNAPSHOT_CONFLICT", false, "continuity.source.changed");
        old(ConflictCode.STATE_CLEANUP_CANDIDATES_CHANGED, STALE, false, "services.stateCleanup.candidatesChanged");
        old(ConflictCode.STATE_CLEANUP_TOKEN_USED, STALE, false, "services.stateCleanup.tokenUsed");
        old(ConflictCode.CONTEXT_LAYER_STATE_STALE, STALE, true, "continuity.layers.stateChanged");

        old(NotFoundCode.RESTORE_SUPPRESSED, "SNAPSHOT_NOT_FOUND", false, "continuity.restore.suppressed");
        old(NotFoundCode.RESTORE_DIRECT_NOT_FOUND, "SNAPSHOT_NOT_FOUND", false, "continuity.restore.directNotFound");

        old(BindingCode.CONTINUITY_EPOCH_STALE, BINDING, true, "continuity.binding.epochStale");
        old(BindingCode.CONTINUITY_CANDIDATE_MISMATCH, BINDING, true, "continuity.restore.candidateMismatch");
        old(BindingCode.CONTINUITY_TOKEN_SIGNATURE_INVALID, BINDING, true, "continuity.token.verificationFailed");
        old(BindingCode.CONTINUITY_TOKEN_INVALID, BINDING, true, "continuity.token.invalid");
        old(BindingCode.CONTINUITY_TOKEN_EXPIRED, BINDING, true, "continuity.token.expired");
        old(BindingCode.CONTINUITY_TOKEN_MALFORMED, BINDING, true, "continuity.token.malformed");
        old(BindingCode.CONTEXT_ROLE_UNBOUND, "BINDING_REQUIRED", false, "continuity.layers.roleUnbound");
        // The session registry's and the call binding's ServiceFailure: its reason was the public code and the reason code. SESSION_NOT_FOUND and BINDING_INVALID are published under
        // their category's code (the second is the same text), so they are in no table.
        old(BindingCode.BINDING_REQUIRED, "BINDING_REQUIRED", true, "error.BINDING_REQUIRED");
        for (ConflictCode code : List.of(ConflictCode.CURRENT_GENERATION_PROTECTED, ConflictCode.GENERATION_CHANGED, ConflictCode.GENERATION_STALE, ConflictCode.STORE_CONFLICT)) {
            old(code, code.name(), true, "error." + code.name());
        }

        old(IntegrityCode.CONTEXT_LAYER_INTEGRITY_FAILED, "INTEGRITY_FAILED", true, "continuity.layers.integrity");
        old(IntegrityCode.SNAPSHOT_DIGEST_INVALID, "INTEGRITY_FAILED", false, "continuity.snapshot.digestInvalid");

        // The skills' checks (SkillCheckFailure, now an InvalidInputException): a check's answer carries the code the constant publishes. Three are published under a code of their own.
        old(InputCode.SKILL_CHECK_INPUT_TOO_LARGE, "INPUT_TOO_LARGE", false, "skillCheck.inputTooLarge");
        old(InputCode.SKILL_CHECK_INVALID_SUBAGENT_MODEL_REQUEST, "INVALID_SUBAGENT_MODEL_REQUEST", false, "skillCheck.invalidSubagentModelRequest");
        old(InputCode.SKILL_CHECK_INVALID_SUBAGENT_MODEL_ADVICE, "INVALID_SUBAGENT_MODEL_ADVICE", false, "skillCheck.invalidSubagentModelAdvice");

        old(UnavailableCode.SERVER_FAILURE, "MCP_UNAVAILABLE", false, "services.failure.serverUnavailable");
        old(UnavailableCode.CONTINUITY_UNAVAILABLE, "CONTINUITY_UNAVAILABLE", false, "continuity.operation.failed");
        old(UnavailableCode.CLASSIFICATION_RUNTIME_UNAVAILABLE, "MCP_UNAVAILABLE", false, "server.classification.runtimeUnavailable");
        old(UnavailableCode.CLASSIFICATION_CAPACITY_EXCEEDED, "MCP_UNAVAILABLE", false, "server.classification.capacityExceeded");
        old(UnavailableCode.CLASSIFICATION_CANCELLED, "MCP_UNAVAILABLE", false, "server.classification.cancelled");
        old(UnavailableCode.CLASSIFICATION_FAILED, "MCP_UNAVAILABLE", false, "server.classification.failed");

        // AdminError: the admin page's own codes. The page reads the code and has its own sentence for it; the catalog has one more per code (error.<NAME>) for the HTTP answer's detail.
        for (InputCode code : List.of(InputCode.ADMIN_CHANGE_INVALID, InputCode.ADMIN_SIGNATURE_INVALID, InputCode.ADMIN_ALGORITHM_UNSUPPORTED, InputCode.WEBAUTHN_PUBLIC_KEY_UNAVAILABLE,
                InputCode.ADMIN_CHALLENGE_MISMATCH, InputCode.ADMIN_RPID_MISMATCH, InputCode.ADMIN_USER_VERIFICATION_MISSING, InputCode.ADMIN_INTENT_EXPIRED,
                InputCode.ADMIN_INTENT_BEFORE_START, InputCode.ADMIN_INTENT_NOT_ISSUED, InputCode.ADMIN_INTENT_NONCANONICAL, InputCode.ADMIN_RECORD_ALTERED)) {
            old(code, code.name(), false, "error." + code.name());
        }
        for (GateCode code : List.of(GateCode.ADMIN_ORIGIN_REJECTED, GateCode.ADMIN_KEY_NOT_REGISTERED, GateCode.ADMIN_KEY_UNKNOWN, GateCode.ADMIN_KEY_REVOKED)) {
            old(code, code.name(), false, "error." + code.name());
        }
        old(NotFoundCode.ADMIN_PROPOSAL_NOT_FOUND, "ADMIN_PROPOSAL_NOT_FOUND", false, "error.ADMIN_PROPOSAL_NOT_FOUND");
        old(NotFoundCode.ADMIN_ROOT_NOT_FOUND, "ADMIN_ROOT_NOT_FOUND", false, "admin.rootRelease.errorNotFound");
        old(ConflictCode.ADMIN_ROOT_NOT_ACTIVE, "ADMIN_ROOT_NOT_ACTIVE", false, "admin.rootRelease.errorNotActive");
        old(ConflictCode.ADMIN_ROOT_STALE, "ADMIN_ROOT_STALE", false, "admin.rootRelease.errorStale");
        old(ConflictCode.ADMIN_TARGET_STALE, "ADMIN_TARGET_STALE", false, "error.ADMIN_TARGET_STALE");
        old(ConflictCode.ADMIN_ALREADY_BOOTSTRAPPED, "ADMIN_ALREADY_BOOTSTRAPPED", false, "error.ADMIN_ALREADY_BOOTSTRAPPED");
        old(ConflictCode.ADMIN_LAST_KEY_REVOKE_REFUSED, "ADMIN_LAST_KEY_REVOKE_REFUSED", false, "error.ADMIN_LAST_KEY_REVOKE_REFUSED");
        old(ConflictCode.ADMIN_LOG_REGRESSED, "ADMIN_LOG_REGRESSED", false, "error.ADMIN_LOG_REGRESSED");
    }

    private static List<ErrorCode> allCodes() {
        List<ErrorCode> codes = new ArrayList<>();
        for (Class<?> type : ErrorCode.class.getPermittedSubclasses()) {
            for (Object constant : type.getEnumConstants()) {
                codes.add((ErrorCode) constant);
            }
        }
        return codes;
    }

    @Test
    void everyMigratedConstantIsPublishedAsTheStringItReplacedWas() {
        for (Map.Entry<ErrorCode, Old> entry : OLD.entrySet()) {
            ErrorCode code = entry.getKey();
            Old old = entry.getValue();
            assertEquals(old.wire(), code.wireCode(), code + " wire code");
            assertEquals(old.reason(), code.wireReason(), code + " reason code on the wire");
            assertEquals(old.key(), code.messageKey(), code + " catalog key");
        }
    }

    @Test
    void everyOtherConstantIsPublishedUnderItsCategoryAndItsOwnName() {
        List<String> wrong = new ArrayList<>();
        for (ErrorCode code : allCodes()) {
            if (!OLD.containsKey(code) && !isAttachmentCode(code) && !isOperationKey(code) && (!code.wireCode().equals(code.category().wireCode()) || !code.wireReason())) {
                wrong.add(code.name());
            }
        }
        assertEquals(List.of(), wrong, "a constant that is not in the table of old literals is published the way the rewritten areas are");
    }

    private static boolean isOperationKey(ErrorCode code) {
        return code instanceof ConflictCode conflict && conflict.isOperationKey();
    }

    /** The attachments' shared contract names its codes ARTIFACT_* and CHUNK_HASH_MISMATCH (the same rule RepositoryContractTest reads the document's table by). */
    private static boolean isAttachmentCode(ErrorCode code) {
        return code.name().startsWith("ARTIFACT_") || code.name().equals("CHUNK_HASH_MISMATCH");
    }

    @Test
    void theAttachmentCodesAreTheSharedContractAndArePublishedUnderTheirOwnNamesWithNoReasonCode() {
        List<ErrorCode> attachments = allCodes().stream().filter(ErrorCodeWireTest::isAttachmentCode).toList();
        assertFalse(attachments.isEmpty(), "the enums have attachment codes");
        for (ErrorCode code : attachments) {
            assertEquals(code.name(), code.wireCode(), code + " is published under its own name");
            assertFalse(code.wireReason(), code + " has no reason code: the code is the reason");
        }
    }

    @Test
    void theSessionIdentityRefusalsAreConstantsOfTheCodeEnumsThatNameThemselvesAsTheReason() {
        for (String name : SESSION_IDENTITY) {
            List<ErrorCode> named = allCodes().stream().filter(code -> code.name().equals(name)).toList();
            assertEquals(1, named.size(), name + " is one constant of the code enums");
            ErrorCode code = named.get(0);
            assertTrue(code.wireReason(), name + " is its own reason code on the wire");
            // The one that is published under its category's code is the session that does not exist, which the messaging's tools already answer so.
            assertEquals(name.equals("SESSION_NOT_FOUND") ? Category.NOT_FOUND.wireCode() : name, code.wireCode(), name + " wire code");
        }
    }

    @Test
    void theTwoConflictsOfAnOperationKeyArePublishedAsRequestConflictAndAreTheOnlyOnesThatSay() {
        for (ConflictCode code : List.of(ConflictCode.OPERATION_KEY_CONFLICT, ConflictCode.OPERATION_KEY_IN_PROGRESS)) {
            assertEquals(REQUEST_CONFLICT, code.wireCode(), code + " wire code");
            assertTrue(code.isOperationKey(), code + " is an operationKey conflict");
        }
        List<String> others = new ArrayList<>();
        for (ConflictCode code : ConflictCode.values()) {
            if (code.isOperationKey() && code != ConflictCode.OPERATION_KEY_CONFLICT && code != ConflictCode.OPERATION_KEY_IN_PROGRESS) {
                others.add(code.name());
            }
        }
        assertEquals(List.of(), others, "only the two conflicts of the key");
    }

    @Test
    void anAdminCodeIsPublishedUnderItsOwnNameWithNoReasonCode() {
        // ADMIN_RESET_* are the commands' (sennard admin-reset) own reasons, which no boundary of the page publishes: they have the category's code like every other constant.
        List<ErrorCode> admin = allCodes().stream().filter(code -> (code.name().startsWith("ADMIN_") && !code.name().startsWith("ADMIN_RESET_")) || code.name().equals("WEBAUTHN_PUBLIC_KEY_UNAVAILABLE")).toList();
        assertEquals(24, admin.size(), "the admin page's codes: " + admin);
        for (ErrorCode code : admin) {
            assertEquals(code.name(), code.wireCode());
            assertFalse(code.wireReason(), code.name());
        }
    }

    @Test
    void aWireCodeIsTheCategorysOrOneOfTheContractsFinerCodesOrAnAdminCode() {
        Set<String> finer = Set.of("STALE_REVISION", "REQUEST_CONFLICT", "SNAPSHOT_CONFLICT", "SNAPSHOT_NOT_FOUND", "BINDING_REQUIRED", "CONTINUITY_UNAVAILABLE", "INPUT_TOO_LARGE",
                "INVALID_SUBAGENT_MODEL_REQUEST", "INVALID_SUBAGENT_MODEL_ADVICE");
        for (ErrorCode code : allCodes()) {
            boolean admin = code.wireCode().equals(code.name()) && (code.name().startsWith("ADMIN_") || code.name().equals("WEBAUTHN_PUBLIC_KEY_UNAVAILABLE"));
            boolean attachments = isAttachmentCode(code) && code.wireCode().equals(code.name());
            boolean identity = SESSION_IDENTITY.contains(code.name()) && code.wireCode().equals(code.name());
            assertTrue(code.wireCode().equals(code.category().wireCode()) || finer.contains(code.wireCode()) || admin || attachments || identity, code.name() + " is published as " + code.wireCode());
        }
    }
}
