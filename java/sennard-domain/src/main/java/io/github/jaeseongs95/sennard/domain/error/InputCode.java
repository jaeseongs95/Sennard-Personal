// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.domain.error;

/**
 * Reasons of {@link Category#INPUT}. A constant that has a second argument is published as the contract has always shown it (see {@link ErrorCode}): the {@code false} says the
 * envelope has no {@code details.reasonCode} for it. The {@code ADMIN_*} reasons are the admin page's own codes, published under their own names; the page has a sentence of its
 * own for each, and the catalog has one more for the HTTP answer's {@code detail}. Every constant has a message key of its own: a test fails when two share one.
 */
public enum InputCode implements ErrorCode {
    /** A tool's input could not be read as the record of its contract at all ({@code details.cause} is what the reader said). */
    CONTRACT_MISMATCH("workflow.schema.inputUnreadable"),
    /** The arguments of a tool call do not satisfy the contract the tool published; the envelope has had no reason for it. */
    TOOL_ARGUMENTS_MISMATCH("workflow.schema.contractMismatch", false),
    /** A tool call carries an argument its published schema does not declare ({@code arguments} lists the names); the guard in front of every tool refuses it before the tool runs. */
    UNDECLARED_ARGUMENT("server.call.undeclaredArgument"),
    /** An argument could not be converted to the type the tool declares, so the tool did not run. */
    ARGUMENT_CONVERSION_FAILED("server.call.argumentConversionFailed"),
    /** A declared constraint of an input record or a method parameter failed (Jakarta Validation); {@code details.violations} lists the fields. */
    VALIDATION_FAILED("error.VALIDATION_FAILED"),
    SUMMARY_INVALID("error.SUMMARY_INVALID"),
    ROLE_INVALID("error.ROLE_INVALID"),
    MESSAGE_BODY_SIZE("services.sessionMessage.message.bodySize"),
    MESSAGE_BODY_NUL("services.sessionMessage.message.bodyNul"),
    MESSAGE_TTL_RANGE("services.sessionMessage.message.ttlRange"),
    MESSAGE_ID_SHAPE("services.sessionMessage.message.idShape"),
    ACK_MESSAGE_IDS_RANGE("services.sessionMessage.acknowledge.messageIdsRange"),
    ACK_CLAIMS_RANGE("services.sessionMessage.acknowledge.claimsRange"),
    ACK_CLAIM_RECEIPT_INVALID("services.sessionMessage.acknowledge.claimReceiptInvalid"),
    CLAIM_LIMIT_RANGE("error.CLAIM_LIMIT_RANGE"),
    /** The first pending message is larger than the claim's limits allow, so a claim with them could never take it. */
    CLAIM_BUDGET_EXCEEDED("error.CLAIM_BUDGET_EXCEEDED"),
    /** A turn recap that is only white space or weighs more than the limit ({@code maxWeight}; see {@code Recap}); {@code weight} is what it weighs, when it is too long. */
    RECAP_INVALID("error.RECAP_INVALID"),
    /** The calling session has no user prompt in the archive: nothing for a recap to belong to (the hooks have not seen a prompt of this session). */
    PROMPT_NOT_ARCHIVED("error.PROMPT_NOT_ARCHIVED"),
    /** The calling session is not one the hooks observe (a session the server minted for a caller without a host session id), so none of its prompts is archived. */
    PROMPT_SESSION_UNHOOKED("error.PROMPT_SESSION_UNHOOKED"),
    /** A recall for a role from a session that has no signed role binding (or whose binding has lapsed). The legacy answer for the same meaning is the context tools' BINDING_REQUIRED/ROLE_NOT_BOUND. */
    ROLE_NOT_BOUND("error.ROLE_NOT_BOUND"),
    /** A subagent called {@code record_turn_recap}: the recap is the main agent's summary of the turn, which a child's own report must not fill in. */
    RECAP_NOT_ROOT("error.RECAP_NOT_ROOT"),
    /** {@code recall_user_prompts} was given a time range that is empty or out of bounds: {@code from} is inclusive and {@code to} exclusive, so {@code from} must be earlier than {@code to}. */
    PROMPT_RANGE_INVALID("error.PROMPT_RANGE_INVALID"),
    /** {@code recall_user_prompts} was given a time that has the shape of the contract but does not exist (a thirteenth month, a thirty-second day). */
    PROMPT_TIME_UNREADABLE("error.PROMPT_TIME_UNREADABLE"),
    /** {@code read_prompt_transcript} named a prompt that the archive does not hold. */
    PROMPT_NOT_FOUND("error.PROMPT_NOT_FOUND"),
    /** {@code read_prompt_transcript} named a prompt that was said in another session: a session reads the transcript behind its own prompts only. */
    TRANSCRIPT_OTHER_SESSION("error.TRANSCRIPT_OTHER_SESSION"),
    /** {@code read_prompt_transcript} was asked to continue after an entry ({@code after}) that is not in the turn of the prompt: a key of another turn, of the user's own line, or none the transcript has. */
    TRANSCRIPT_AFTER_NOT_IN_TURN("error.TRANSCRIPT_AFTER_NOT_IN_TURN"),

    /** The context tools' arguments miss a required one or carry another schema version. */
    CONTEXT_LAYER_ARGUMENTS_INVALID("continuity.layers.argumentsInvalid"),
    CONTEXT_LAYER_BODY_INVALID("continuity.layers.bodyInvalid"),
    CONTEXT_LAYER_TOO_LARGE("continuity.layers.tooLarge"),
    CONTEXT_LAYER_TOKEN_INVALID("continuity.layers.tokenInvalid"),
    CONTEXT_SESSION_INVALID("continuity.layers.sessionInvalid"),
    ROLE_NAME_INVALID("continuity.layers.roleInvalid"),
    /** The role a session asks to be bound to is not in the registry ({@code details.adminUrl} is where the user creates it). */
    ROLE_NOT_REGISTERED("continuity.layers.roleNotRegistered"),
    /** {@code details.reason} says {@code ROOT_NOT_OWNED}: the contract's own name for it, kept beside the reason code. */
    ROOT_OWNED_ELSEWHERE("continuity.binding.rootOwnedElsewhere", false),
    CONTINUITY_MEMORY_TOO_LARGE("continuity.checkpoint.tooLarge"),
    STATE_CLEANUP_TOKEN_TOO_LARGE("services.stateCleanup.tokenTooLarge", false),
    STATE_CLEANUP_TOKEN_MALFORMED("services.stateCleanup.tokenMalformed", false),
    STATE_CLEANUP_TOKEN_SIGNATURE_INVALID("services.stateCleanup.tokenSignatureInvalid", false),
    STATE_CLEANUP_TOKEN_PAYLOAD_INVALID("services.stateCleanup.tokenPayloadInvalid", false),
    GLOSSARY_SOURCE_DIGEST_MISMATCH("services.glossary.sourceDigestMismatch", false),
    CLASSIFICATION_INPUT_MISMATCH("server.classification.inputMismatch", false),
    /** A classification request refused for its own state; {@code details.reasonCode} names which of the rejections it was. */
    CLASSIFICATION_REQUEST_REJECTED("server.classification.requestRejected", false),
    /** {@code get_skill_inventory} was given skill ids that are not installed, or more than one call may read whole ({@code details.skillIds} and {@code details.limit} say which). */
    INVENTORY_SKILL_IDS_REJECTED("server.inventory.skillIdsRejected"),
    UPDATES_ARGUMENTS_INVALID("server.updates.argumentsInvalid", false),
    /** A send whose arguments do not satisfy the tool's contract: the sentence also tells how to go on without sending twice ({@code cause} is the contract sentence). */
    SEND_INVALID("server.sessionMessage.sendInvalid", false),

    /** A change the admin page signed is not one of the kinds, or is not of the shape of its kind (or is too large). */
    ADMIN_CHANGE_INVALID("error.ADMIN_CHANGE_INVALID", "ADMIN_CHANGE_INVALID"),
    ADMIN_SIGNATURE_INVALID("error.ADMIN_SIGNATURE_INVALID", "ADMIN_SIGNATURE_INVALID"),
    ADMIN_ALGORITHM_UNSUPPORTED("error.ADMIN_ALGORITHM_UNSUPPORTED", "ADMIN_ALGORITHM_UNSUPPORTED"),
    WEBAUTHN_PUBLIC_KEY_UNAVAILABLE("error.WEBAUTHN_PUBLIC_KEY_UNAVAILABLE", "WEBAUTHN_PUBLIC_KEY_UNAVAILABLE"),
    ADMIN_CHALLENGE_MISMATCH("error.ADMIN_CHALLENGE_MISMATCH", "ADMIN_CHALLENGE_MISMATCH"),
    ADMIN_RPID_MISMATCH("error.ADMIN_RPID_MISMATCH", "ADMIN_RPID_MISMATCH"),
    ADMIN_USER_VERIFICATION_MISSING("error.ADMIN_USER_VERIFICATION_MISSING", "ADMIN_USER_VERIFICATION_MISSING"),
    ADMIN_INTENT_EXPIRED("error.ADMIN_INTENT_EXPIRED", "ADMIN_INTENT_EXPIRED"),
    ADMIN_INTENT_BEFORE_START("error.ADMIN_INTENT_BEFORE_START", "ADMIN_INTENT_BEFORE_START"),
    /** A signed intent this server did not prepare, or one a record has already used. */
    ADMIN_INTENT_NOT_ISSUED("error.ADMIN_INTENT_NOT_ISSUED", "ADMIN_INTENT_NOT_ISSUED"),
    ADMIN_INTENT_NONCANONICAL("error.ADMIN_INTENT_NONCANONICAL", "ADMIN_INTENT_NONCANONICAL"),
    ADMIN_RECORD_ALTERED("error.ADMIN_RECORD_ALTERED", "ADMIN_RECORD_ALTERED"),

    // The inputs a skill's check will not judge (sennard-server's skills package). Each constant is one refusal of one check; the wire of a check's answer carries the constant's
    // wire code ({@code INVALID_INPUT} unless it says otherwise), its message key and its arguments.
    // acceptance-evidence
    ACCEPTANCE_EVIDENCE_MUST_BE_NON_EMPTY_STRING("skillScripts.acceptanceEvidence.mustBeNonEmptyString"),
    ACCEPTANCE_EVIDENCE_MUST_BE_STRING_ARRAY("skillScripts.acceptanceEvidence.mustBeStringArray"),
    ACCEPTANCE_EVIDENCE_INPUT_NOT_OBJECT("skillScripts.acceptanceEvidence.inputNotObject"),
    ACCEPTANCE_EVIDENCE_INPUT_SCHEMA_FAILED("skillScripts.acceptanceEvidence.inputSchemaFailed"),
    ACCEPTANCE_EVIDENCE_DUPLICATE_WORK_UNIT("skillScripts.acceptanceEvidence.duplicateWorkUnit"),
    ACCEPTANCE_EVIDENCE_UNKNOWN_DEPENDENCY("skillScripts.acceptanceEvidence.unknownDependency"),
    ACCEPTANCE_EVIDENCE_DEPENDENCY_CYCLE("skillScripts.acceptanceEvidence.dependencyCycle"),
    ACCEPTANCE_EVIDENCE_MUST_NOT_DUPLICATE("skillScripts.acceptanceEvidence.mustNotDuplicate"),
    ACCEPTANCE_EVIDENCE_CRITERIA_MISMATCH("skillScripts.acceptanceEvidence.criteriaMismatch"),
    ACCEPTANCE_EVIDENCE_DUPLICATE_EVIDENCE("skillScripts.acceptanceEvidence.duplicateEvidence"),
    ACCEPTANCE_EVIDENCE_DUPLICATE_COMMAND("skillScripts.acceptanceEvidence.duplicateCommand"),
    ACCEPTANCE_EVIDENCE_RESERVED_COMMAND_EVIDENCE_ID("skillScripts.acceptanceEvidence.reservedCommandEvidenceId"),
    ACCEPTANCE_EVIDENCE_DUPLICATE_OVERRIDE("skillScripts.acceptanceEvidence.duplicateOverride"),
    ACCEPTANCE_EVIDENCE_OVERRIDE_NEEDS_AUTHORITY("skillScripts.acceptanceEvidence.overrideNeedsAuthority"),
    ACCEPTANCE_EVIDENCE_OVERRIDE_AUTHORITY_LOCATOR("skillScripts.acceptanceEvidence.overrideAuthorityLocator"),
    ACCEPTANCE_EVIDENCE_OVERRIDE_LOCATOR_UNRESOLVED("skillScripts.acceptanceEvidence.overrideLocatorUnresolved"),
    ACCEPTANCE_EVIDENCE_OVERRIDE_DIGEST_MISMATCH("skillScripts.acceptanceEvidence.overrideDigestMismatch"),
    ACCEPTANCE_EVIDENCE_EVIDENCE_UNKNOWN_CRITERION("skillScripts.acceptanceEvidence.evidenceUnknownCriterion"),

    // blocker-diagnostician
    BLOCKER_DIAGNOSIS_MUST_BE_NON_EMPTY_STRING("skillScripts.blockerDiagnostician.mustBeNonEmptyString"),
    BLOCKER_DIAGNOSIS_MUST_BE_STRING_ARRAY("skillScripts.blockerDiagnostician.mustBeStringArray"),
    BLOCKER_DIAGNOSIS_INPUT_NOT_OBJECT("skillScripts.blockerDiagnostician.inputNotObject"),
    BLOCKER_DIAGNOSIS_INPUT_SCHEMA_FAILED("skillScripts.blockerDiagnostician.inputSchemaFailed"),
    BLOCKER_DIAGNOSIS_DUPLICATE_ATTEMPT_ID("skillScripts.blockerDiagnostician.duplicateAttemptId"),
    BLOCKER_DIAGNOSIS_DUPLICATE_HYPOTHESIS("skillScripts.blockerDiagnostician.duplicateHypothesis"),
    BLOCKER_DIAGNOSIS_EVIDENCE_BOTH_WAYS("skillScripts.blockerDiagnostician.evidenceBothWays"),
    BLOCKER_DIAGNOSIS_EVIDENCE_OUTSIDE_INVENTORY("skillScripts.blockerDiagnostician.evidenceOutsideInventory"),
    BLOCKER_DIAGNOSIS_CONFIRMED_NEEDS_SUPPORT("skillScripts.blockerDiagnostician.confirmedNeedsSupport"),
    BLOCKER_DIAGNOSIS_ONLY_ONE_CONFIRMED("skillScripts.blockerDiagnostician.onlyOneConfirmed"),
    BLOCKER_DIAGNOSIS_BINDING_OUTSIDE_INVENTORY("skillScripts.blockerDiagnostician.bindingOutsideInventory"),
    BLOCKER_DIAGNOSIS_BINDING_UNKNOWN_HYPOTHESIS("skillScripts.blockerDiagnostician.bindingUnknownHypothesis"),
    BLOCKER_DIAGNOSIS_CONFLICTING_DIGESTS("skillScripts.blockerDiagnostician.conflictingDigests"),
    BLOCKER_DIAGNOSIS_DUPLICATE_BINDING("skillScripts.blockerDiagnostician.duplicateBinding"),
    BLOCKER_DIAGNOSIS_DUPLICATE_RELATION_BINDING("skillScripts.blockerDiagnostician.duplicateRelationBinding"),
    BLOCKER_DIAGNOSIS_CONFIRMED_WITH_REFUTING("skillScripts.blockerDiagnostician.confirmedWithRefuting"),
    BLOCKER_DIAGNOSIS_BINDING_NOT_REFLECTED("skillScripts.blockerDiagnostician.bindingNotReflected"),
    BLOCKER_DIAGNOSIS_SUPPORTING_NOT_BOUND("skillScripts.blockerDiagnostician.supportingNotBound"),
    BLOCKER_DIAGNOSIS_CONTRADICTING_NOT_BOUND("skillScripts.blockerDiagnostician.contradictingNotBound"),
    BLOCKER_DIAGNOSIS_DUPLICATE_CANDIDATE_TEST("skillScripts.blockerDiagnostician.duplicateCandidateTest"),
    BLOCKER_DIAGNOSIS_CANDIDATE_INPUT_DIGEST("skillScripts.blockerDiagnostician.candidateInputDigest"),
    BLOCKER_DIAGNOSIS_ATTEMPTED_CHECK_MISMATCH("skillScripts.blockerDiagnostician.attemptedCheckMismatch"),
    BLOCKER_DIAGNOSIS_ATTEMPTED_INPUT_DIGEST("skillScripts.blockerDiagnostician.attemptedInputDigest"),
    BLOCKER_DIAGNOSIS_CAUSE_ANALYSIS_REQUIRED("skillScripts.blockerDiagnostician.causeAnalysisRequired"),
    BLOCKER_DIAGNOSIS_CAUSE_ANALYSIS_MUST_CITE("skillScripts.blockerDiagnostician.causeAnalysisMustCite"),
    BLOCKER_DIAGNOSIS_CANDIDATE_OUTCOME_BOTH_WAYS("skillScripts.blockerDiagnostician.candidateOutcomeBothWays"),
    BLOCKER_DIAGNOSIS_CANDIDATE_UNKNOWN_HYPOTHESIS("skillScripts.blockerDiagnostician.candidateUnknownHypothesis"),
    BLOCKER_DIAGNOSIS_CANDIDATE_NOT_DISCRIMINATING("skillScripts.blockerDiagnostician.candidateNotDiscriminating"),

    // iteration-frame-auditor
    ITERATION_FRAME_DIGEST_MISMATCH("skillTools.iterationFrame.digestMismatch"),
    ITERATION_FRAME_UNKNOWN_EVIDENCE("skillTools.iterationFrame.unknownEvidence"),
    ITERATION_FRAME_DUPLICATES("skillTools.iterationFrame.duplicates"),
    ITERATION_FRAME_CHANGE_SUMMARY_MISSING("skillTools.iterationFrame.changeSummaryMissing"),
    ITERATION_FRAME_REQUEST_NOT_OBJECT("skillTools.iterationFrame.requestNotObject"),
    ITERATION_FRAME_REQUEST_SCHEMA_FAILED("skillTools.iterationFrame.requestSchemaFailed"),
    ITERATION_FRAME_MISSING_EVIDENCE_KINDS("skillTools.iterationFrame.missingEvidenceKinds"),
    ITERATION_FRAME_FUTURE_EPOCH("skillTools.iterationFrame.futureEpoch"),
    ITERATION_FRAME_ATTEMPT_NEEDS_OUTCOME("skillTools.iterationFrame.attemptNeedsOutcome"),
    ITERATION_FRAME_REVIEWER_ACTORS_MISMATCH("skillTools.iterationFrame.reviewerActorsMismatch"),
    ITERATION_FRAME_REVIEWER_NOT_DISTINCT("skillTools.iterationFrame.reviewerNotDistinct"),
    ITERATION_FRAME_FRESH_CONTEXT_EVIDENCE_KIND("skillTools.iterationFrame.freshContextEvidenceKind"),
    // instruction-scope-resolver
    INSTRUCTION_SCOPE_INVALID_FILE_DESCRIPTOR("skillTools.instructionScope.invalidFileDescriptor"),
    INSTRUCTION_SCOPE_TARGET_MISSING("skillTools.instructionScope.targetMissing"),
    INSTRUCTION_SCOPE_NO_EXISTING_ANCESTOR("skillTools.instructionScope.noExistingAncestor"),
    INSTRUCTION_SCOPE_INSTRUCTION_OUTSIDE_ROOTS("skillTools.instructionScope.instructionOutsideRoots"),
    INSTRUCTION_SCOPE_REQUEST_VALIDATION_FAILED("skillTools.instructionScope.requestValidationFailed"),
    INSTRUCTION_SCOPE_WORKSPACE_ROOT_NOT_DIRECTORY("skillTools.instructionScope.workspaceRootNotDirectory"),
    INSTRUCTION_SCOPE_INSTRUCTION_ROOT_NOT_DIRECTORY("skillTools.instructionScope.instructionRootNotDirectory"),
    INSTRUCTION_SCOPE_NO_AUTHORIZED_ROOT("skillTools.instructionScope.noAuthorizedRoot"),
    INSTRUCTION_SCOPE_TARGET_OUTSIDE_WORKSPACE("skillTools.instructionScope.targetOutsideWorkspace"),
    INSTRUCTION_SCOPE_TARGET_RESOLVES_OUTSIDE("skillTools.instructionScope.targetResolvesOutside"),
    INSTRUCTION_SCOPE_TARGET_NOT_COVERED("skillTools.instructionScope.targetNotCovered"),
    // orchestrator
    ORCHESTRATOR_REGISTRY_NOT_V2("skillScripts.orchestrator.registryNotV2"),
    ORCHESTRATOR_ALL_OR_CAPABILITY("skillScripts.orchestrator.allOrCapability"),
    ORCHESTRATOR_SPECIFY_CAPABILITY("skillScripts.orchestrator.specifyCapability"),
    // software-security-auditor
    SECURITY_AUDIT_MISSING_REPORT_OR_ROOT("skillScripts.securityAuditor.missingReportOrRoot"),
    SECURITY_AUDIT_INVALID_OPTION("skillScripts.securityAuditor.invalidOption"),

    // change-scope-guardian
    CHANGE_SCOPE_SCHEMA_FAILED("skillScripts.changeScope.schemaFailed"),
    CHANGE_SCOPE_JOURNAL_MISSING("skillScripts.changeScope.journalMissing"),
    CHANGE_SCOPE_JOURNAL_PLANNING("skillScripts.changeScope.journalPlanning"),
    CHANGE_SCOPE_WORKSPACE_ROOT_INVALID("skillScripts.changeScope.workspaceRootInvalid"),
    CHANGE_SCOPE_BASELINE_IDENTITY_MISMATCH("skillScripts.changeScope.baselineIdentityMismatch"),
    CHANGE_SCOPE_BASELINE_RECORD_MISMATCH("skillScripts.changeScope.baselineRecordMismatch"),
    CHANGE_SCOPE_RULE_NOT_STRING("skillScripts.changeScope.ruleNotString"),
    CHANGE_SCOPE_RULE_UNSAFE("skillScripts.changeScope.ruleUnsafe"),
    CHANGE_SCOPE_BASELINE_TASK_ENVELOPE_MISMATCH("skillScripts.changeScope.baselineTaskEnvelopeMismatch"),
    CHANGE_SCOPE_BASELINE_PATHS("skillScripts.changeScope.baselinePaths"),
    CHANGE_SCOPE_BASELINE_CHECKSUM("skillScripts.changeScope.baselineChecksum"),
    CHANGE_SCOPE_ENVELOPE_WORK_UNIT_IDS("skillScripts.changeScope.envelopeWorkUnitIds"),
    CHANGE_SCOPE_REQUEST_MODE("skillScripts.changeScope.requestMode"),
    CHANGE_SCOPE_BASELINE_DIGEST_MISMATCH("skillScripts.changeScope.baselineDigestMismatch"),
    CHANGE_SCOPE_ARTIFACT_AND_DIGEST_REQUIRED("skillScripts.changeScope.artifactAndDigestRequired"),
    CHANGE_SCOPE_REPORT_DIGEST_MISMATCH("skillScripts.changeScope.reportDigestMismatch"),
    CHANGE_SCOPE_SUMMARY_COUNT("skillScripts.changeScope.summaryCount"),
    CHANGE_SCOPE_CHANGE_PATHS_UNIQUE("skillScripts.changeScope.changePathsUnique"),
    CHANGE_SCOPE_MISSING_BASELINE("skillScripts.changeScope.missingBaseline"),
    CHANGE_SCOPE_VERDICT_MISMATCH("skillScripts.changeScope.verdictMismatch"),
    CHANGE_SCOPE_REPORT_DIGEST_INVALID("skillScripts.changeScope.reportDigestInvalid"),
    CHANGE_SCOPE_FINDINGS_MISMATCH("skillScripts.changeScope.findingsMismatch"),
    CHANGE_SCOPE_REPORT_CHECKSUM("skillScripts.changeScope.reportChecksum"),

    // task-contract
    TASK_CONTRACT_WORK_UNIT_IDS_UNIQUE("skillTools.taskContract.workUnitIdsUnique"),
    TASK_CONTRACT_DEPENDENCY_MISSING("skillTools.taskContract.dependencyMissing"),
    TASK_CONTRACT_SELF_DEPENDENCY("skillTools.taskContract.selfDependency"),
    TASK_CONTRACT_DEPENDENCY_CYCLE("skillTools.taskContract.dependencyCycle"),
    TASK_CONTRACT_RECEIPT_CANNOT_AUTHORIZE("skillTools.taskContract.receiptCannotAuthorize"),
    TASK_CONTRACT_PROJECT_GRANT_UNBOUND("skillTools.taskContract.projectGrantUnbound"),
    TASK_CONTRACT_RESTRICTIVE_EVIDENCE_OMITTED("skillTools.taskContract.restrictiveEvidenceOmitted"),
    TASK_CONTRACT_ACTIONS_NOT_UNIQUE("skillTools.taskContract.actionsNotUnique"),
    TASK_CONTRACT_PROVENANCE_BINDING_EXACTLY_ONE("skillTools.taskContract.provenanceBindingExactlyOne"),
    TASK_CONTRACT_ALLOWED_CONFLICTS_RESTRICTIVE("skillTools.taskContract.allowedConflictsRestrictive"),
    TASK_CONTRACT_BINDING_COVERS_MULTIPLE("skillTools.taskContract.bindingCoversMultiple"),
    TASK_CONTRACT_PROVENANCE_MATCH_EXACTLY_ONE("skillTools.taskContract.provenanceMatchExactlyOne"),
    TASK_CONTRACT_PROVENANCE_EXTRA_ACTION("skillTools.taskContract.provenanceExtraAction"),
    TASK_CONTRACT_SCHEMA_VALIDATION_FAILED("skillTools.taskContract.schemaValidationFailed"),
    TASK_CONTRACT_TASK_ID_MISMATCH("skillTools.taskContract.taskIdMismatch"),
    TASK_CONTRACT_SCOPE_PATH_OVERLAP("skillTools.taskContract.scopePathOverlap"),
    TASK_CONTRACT_WRITE_TARGETS_FORMAT("skillTools.taskContract.writeTargetsFormat"),
    TASK_CONTRACT_WRITE_TARGET_EXCLUDED("skillTools.taskContract.writeTargetExcluded"),
    TASK_CONTRACT_CRITERION_ID_MISSING("skillTools.taskContract.criterionIdMissing"),
    TASK_CONTRACT_CRITERION_IDS_UNIQUE("skillTools.taskContract.criterionIdsUnique"),
    TASK_CONTRACT_PLAN_CRITERION_IDS_UNIQUE("skillTools.taskContract.planCriterionIdsUnique"),
    TASK_CONTRACT_CRITERIA_ONE_TO_ONE("skillTools.taskContract.criteriaOneToOne"),
    TASK_CONTRACT_CRITERION_STATEMENT_MISMATCH("skillTools.taskContract.criterionStatementMismatch"),
    TASK_CONTRACT_CORE_FIELD_NO_PROVENANCE("skillTools.taskContract.coreFieldNoProvenance"),
    TASK_CONTRACT_PASS_BLOCKING_AMBIGUITY("skillTools.taskContract.passBlockingAmbiguity"),
    TASK_CONTRACT_PASS_CONTRADICTIONS("skillTools.taskContract.passContradictions"),
    TASK_CONTRACT_PASS_MATERIAL_ASSUMPTION("skillTools.taskContract.passMaterialAssumption"),
    TASK_CONTRACT_SCOPE_OVERLAP_EXACT("skillTools.taskContract.scopeOverlapExact"),
    TASK_CONTRACT_ALLOWED_PROHIBITED_OVERLAP("skillTools.taskContract.allowedProhibitedOverlap"),
    TASK_CONTRACT_ALLOWED_APPROVAL_OVERLAP("skillTools.taskContract.allowedApprovalOverlap"),
    TASK_CONTRACT_PROHIBITED_APPROVAL_OVERLAP("skillTools.taskContract.prohibitedApprovalOverlap"),

    // evaluation-validity-auditor
    EVALUATION_VALIDITY_FORMAT_INVALID("skillReports.evaluationValidityAuditor.formatInvalid"),
    EVALUATION_VALIDITY_ARTIFACT_ROOT_REQUIRED("skillReports.evaluationValidityAuditor.artifactRootRequired"),
    EVALUATION_VALIDITY_ARTIFACT_ROOT_UNRESOLVED("skillReports.evaluationValidityAuditor.artifactRootUnresolved"),
    EVALUATION_VALIDITY_DUPLICATE_ARTIFACT_ID("skillReports.evaluationValidityAuditor.duplicateArtifactId"),
    EVALUATION_VALIDITY_REQUEST_INVALID("skillReports.evaluationValidityAuditor.requestInvalid"),
    EVALUATION_VALIDITY_GENERATED_REPORT_INVALID("skillReports.evaluationValidityAuditor.generatedReportInvalid"),
    EVALUATION_VALIDITY_VALIDATION_NOT_OBJECT("skillReports.evaluationValidityAuditor.validationNotObject"),
    EVALUATION_VALIDITY_VALIDATION_INPUT_INVALID("skillReports.evaluationValidityAuditor.validationInputInvalid"),

    // recovery-strategy-selector
    RECOVERY_STRATEGY_REQUEST_CONTRACT_VIOLATION("skillChecksKo.recoveryStrategySelector.requestContractViolation"),
    RECOVERY_STRATEGY_DIAGNOSIS_REPORT_CONTRACT_VIOLATION("skillChecksKo.recoveryStrategySelector.diagnosisReportContractViolation"),
    RECOVERY_STRATEGY_TASK_ENVELOPE_CONTRACT_VIOLATION("skillChecksKo.recoveryStrategySelector.taskEnvelopeContractViolation"),
    RECOVERY_STRATEGY_WORKFLOW_RECEIPT_CONTRACT_VIOLATION("skillChecksKo.recoveryStrategySelector.workflowReceiptContractViolation"),
    RECOVERY_STRATEGY_AUTHORIZATION_ACTION_DUPLICATE("skillChecksKo.recoveryStrategySelector.authorizationActionDuplicate"),
    RECOVERY_STRATEGY_PROJECT_INSTRUCTION_CANNOT_ALLOW("skillChecksKo.recoveryStrategySelector.projectInstructionCannotAllow"),
    RECOVERY_STRATEGY_DIAGNOSIS_REPORT_DIGEST_MISMATCH("skillChecksKo.recoveryStrategySelector.diagnosisReportDigestMismatch"),
    RECOVERY_STRATEGY_DIAGNOSIS_REQUEST_DIGEST_MISMATCH("skillChecksKo.recoveryStrategySelector.diagnosisRequestDigestMismatch"),
    RECOVERY_STRATEGY_DIAGNOSIS_REPORT_BINDING_VIOLATION("skillChecksKo.recoveryStrategySelector.diagnosisReportBindingViolation"),
    RECOVERY_STRATEGY_SOURCE_TASK_DIGEST_MISMATCH("skillChecksKo.recoveryStrategySelector.sourceTaskDigestMismatch"),
    RECOVERY_STRATEGY_CAUSE_CONFIRMED_REQUIRED("skillChecksKo.recoveryStrategySelector.causeConfirmedRequired"),
    RECOVERY_STRATEGY_SOURCE_RECEIPT_DIGEST_MISMATCH("skillChecksKo.recoveryStrategySelector.sourceReceiptDigestMismatch"),
    RECOVERY_STRATEGY_SOURCE_WORKFLOW_METADATA_MISMATCH("skillChecksKo.recoveryStrategySelector.sourceWorkflowMetadataMismatch"),
    RECOVERY_STRATEGY_SOURCE_RECEIPT_NOT_BOUND_TO_TASK("skillChecksKo.recoveryStrategySelector.sourceReceiptNotBoundToTask"),
    RECOVERY_STRATEGY_DIAGNOSIS_OBJECTIVE_MISMATCH("skillChecksKo.recoveryStrategySelector.diagnosisObjectiveMismatch"),
    RECOVERY_STRATEGY_CAUSE_HYPOTHESIS_ID_MISMATCH("skillChecksKo.recoveryStrategySelector.causeHypothesisIdMismatch"),
    RECOVERY_STRATEGY_DIAGNOSIS_NOT_BOUND_TO_TASK_DIGEST("skillChecksKo.recoveryStrategySelector.diagnosisNotBoundToTaskDigest"),
    RECOVERY_STRATEGY_DIAGNOSIS_NOT_BOUND_TO_RECEIPT_DIGEST("skillChecksKo.recoveryStrategySelector.diagnosisNotBoundToReceiptDigest"),
    RECOVERY_STRATEGY_EVIDENCE_REF_NOT_UNIQUE("skillChecksKo.recoveryStrategySelector.evidenceRefNotUnique"),
    // korean-prose-editor
    KOREAN_PROSE_MUST_BE_OBJECT("skillChecks.koreanProseEditor.mustBeObject"),
    KOREAN_PROSE_THREE_ACTORS_REQUIRED("skillChecks.koreanProseEditor.threeActorsRequired"),
    KOREAN_PROSE_PLAN_SCHEMA_VERSION_UNSUPPORTED("skillChecks.koreanProseEditor.planSchemaVersionUnsupported"),
    KOREAN_PROSE_ACTOR_IDS_COUNT("skillChecks.koreanProseEditor.actorIdsCount"),
    KOREAN_PROSE_ACTOR_IDS_FORMAT("skillChecks.koreanProseEditor.actorIdsFormat"),
    KOREAN_PROSE_ACTOR_IDS_UNIQUE("skillChecks.koreanProseEditor.actorIdsUnique"),
    KOREAN_PROSE_PROVIDER_ACTOR_MISMATCH("skillChecks.koreanProseEditor.providerActorMismatch"),
    KOREAN_PROSE_FINALIZER_PROVIDER("skillChecks.koreanProseEditor.finalizerProvider"),
    KOREAN_PROSE_FINALIZER_ACTOR("skillChecks.koreanProseEditor.finalizerActor"),
    KOREAN_PROSE_SPANS_SOURCE_NOT_STRING("skillChecks.koreanProseEditorSpans.sourceNotString"),
    KOREAN_PROSE_SPANS_PROTECTED_STRINGS_INVALID("skillChecks.koreanProseEditorSpans.protectedStringsInvalid"),
    KOREAN_PROSE_UNITS_SOURCE_NOT_STRING("skillChecks.koreanProseEditorUnits.sourceNotString"),
    KOREAN_PROSE_UNITS_MANIFEST_SOURCE_MISMATCH("skillChecks.koreanProseEditorUnits.manifestSourceMismatch"),
    KOREAN_PROSE_FINAL_REQUEST_SCHEMA_VERSION_UNSUPPORTED("skillChecks.koreanProseEditorFinalizer.requestSchemaVersionUnsupported"),
    KOREAN_PROSE_FINAL_MODE_INVALID("skillChecks.koreanProseEditorFinalizer.modeInvalid"),
    KOREAN_PROSE_FINAL_SOURCE_NOT_STRING("skillChecks.koreanProseEditorFinalizer.sourceNotString"),

    // the skill-check boundary itself (arguments of a command)
    SKILL_CHECK_UNKNOWN_SKILL("skillCheck.unknownSkill"),
    SKILL_CHECK_UNKNOWN_COMMAND("skillCheck.unknownCommand"),
    SKILL_CHECK_ARGUMENT_REQUIRED("skillCheck.argumentRequired"),
    SKILL_CHECK_ARGUMENT_MUST_BE_STRING("skillCheck.argumentMustBeString"),
    SKILL_CHECK_ARGUMENT_MUST_BE_INTEGER("skillCheck.argumentMustBeInteger"),
    SKILL_CHECK_ARGUMENT_MUST_BE_BOOLEAN("skillCheck.argumentMustBeBoolean"),
    SKILL_CHECK_ARGUMENT_MUST_BE_ARRAY("skillCheck.argumentMustBeArray"),
    SKILL_CHECK_ARGUMENT_MUST_BE_OBJECT_OF_STRINGS("skillCheck.argumentMustBeObjectOfStrings"),
    SKILL_CHECK_ARGUMENT_FILE_MUST_BE_STRING("skillCheck.argumentFileMustBeString"),
    SKILL_CHECK_INVALID_JSON("skillCheck.invalidJson"),
    SKILL_CHECK_MALFORMED_ARGUMENT("skillCheck.malformedArgument"),
    /** {@code get_background_job} was given a text that is not a job reference. */
    JOB_REFERENCE_INVALID("skillCheck.jobReferenceInvalid"),
    /** A document over the size a check reads; a check's answer has published it under {@code INPUT_TOO_LARGE}. */
    SKILL_CHECK_INPUT_TOO_LARGE("skillCheck.inputTooLarge", "INPUT_TOO_LARGE"),
    /** A subagent model request that does not satisfy its contract; published under its own code, with {@code details.errors}. */
    SKILL_CHECK_INVALID_SUBAGENT_MODEL_REQUEST("skillCheck.invalidSubagentModelRequest", "INVALID_SUBAGENT_MODEL_REQUEST"),
    /** A subagent model advice that does not satisfy its contract; published under its own code, with {@code details.errors}. */
    SKILL_CHECK_INVALID_SUBAGENT_MODEL_ADVICE("skillCheck.invalidSubagentModelAdvice", "INVALID_SUBAGENT_MODEL_ADVICE"),
    // model-routing
    MODEL_ROUTING_PRESETS_JSON_OBJECT("skillTools.modelRouting.presetsJsonObject"),
    MODEL_ROUTING_MODEL_CLASS_ORDER_IMMUTABLE("skillTools.modelRouting.modelClassOrderImmutable"),
    MODEL_ROUTING_EFFORT_ORDER_IMMUTABLE("skillTools.modelRouting.effortOrderImmutable"),
    MODEL_ROUTING_PROVIDER_REQUIRED("skillTools.modelRouting.providerRequired"),
    MODEL_ROUTING_PRESETS_NOT_OVERRIDABLE_OPTIONS("skillTools.modelRouting.presetsNotOverridableOptions"),
    MODEL_ROUTING_HIGH_RISK_EXPLICIT("skillTools.modelRouting.highRiskExplicit"),
    MODEL_ROUTING_SUPPORTED_FROM_HOST("skillTools.modelRouting.supportedFromHost"),
    MODEL_ROUTING_CAPABILITIES_UNIQUE("skillTools.modelRouting.capabilitiesUnique"),
    MODEL_ROUTING_INVALID_SUPPORTED_EFFORTS("skillTools.modelRouting.invalidSupportedEfforts"),
    MODEL_ROUTING_UNKNOWN_ROLE("skillTools.modelRouting.unknownRole"),
    MODEL_ROUTING_AUDIT_NEEDS_HIGH_RISK("skillTools.modelRouting.auditNeedsHighRisk"),
    MODEL_ROUTING_INVALID_JUDGMENT("skillTools.modelRouting.invalidJudgment"),
    MODEL_ROUTING_INVALID_USER_EFFORT("skillTools.modelRouting.invalidUserEffort"),
    MODEL_ROUTING_ASSIGNMENT_ID_REQUIRED("skillTools.modelRouting.assignmentIdRequired"),
    MODEL_ROUTING_SELECTION_REASON_REQUIRED("skillTools.modelRouting.selectionReasonRequired"),
    MODEL_ROUTING_PRESETS_NOT_OVERRIDABLE_INPUT("skillTools.modelRouting.presetsNotOverridableInput"),
    MODEL_ROUTING_ADAPTER_REQUIRED("skillTools.modelRouting.adapterRequired"),
    MODEL_ROUTING_SPAWN_ARGUMENTS_REQUIRED("skillTools.modelRouting.spawnArgumentsRequired"),
    MODEL_ROUTING_INVALID_CONTEXT_ARGUMENT("skillTools.modelRouting.invalidContextArgument"),
    MODEL_ROUTING_CONTEXT_MODE_CONTRADICTS("skillTools.modelRouting.contextModeContradicts"),
    MODEL_ROUTING_INVALID_CONTEXT_MODE("skillTools.modelRouting.invalidContextMode"),
    MODEL_ROUTING_AUDIT_INHERITED_CONTEXT("skillTools.modelRouting.auditInheritedContext"),
    MODEL_ROUTING_FULL_HISTORY_NEEDS_REASON("skillTools.modelRouting.fullHistoryNeedsReason"),
    MODEL_ROUTING_REASON_NO_SUPPORTED_CONFIGURATION("skillTools.modelRouting.reasonNoSupportedConfiguration"),
    MODEL_ROUTING_DISPATCH_MUST_MATCH("skillTools.modelRouting.dispatchMustMatch"),
    MODEL_ROUTING_INHERITED_NO_OVERRIDES("skillTools.modelRouting.inheritedNoOverrides"),
    MODEL_ROUTING_FULL_HISTORY_OVERRIDES_UNSUPPORTED("skillTools.modelRouting.fullHistoryOverridesUnsupported"),
    MODEL_ROUTING_OBSERVATION_SOURCE("skillTools.modelRouting.observationSource"),
    MODEL_ROUTING_OBSERVATION_REFERENCE("skillTools.modelRouting.observationReference"),

    // cs-engineering
    CS_ENGINEERING_INVALID_MODE("skillTools.csEngineering.invalidMode"),
    CS_ENGINEERING_SCHEMA_VALIDATION_FAILED("skillTools.csEngineering.schemaValidationFailed"),
    CS_ENGINEERING_DUPLICATE("skillTools.csEngineering.duplicate"),
    CS_ENGINEERING_UNKNOWN_REFERENCE("skillTools.csEngineering.unknownReference"),
    CS_ENGINEERING_BOUNDED_PATH_REQUIRED("skillTools.csEngineering.boundedPathRequired"),
    CS_ENGINEERING_FORBIDDEN_PATH_FORM("skillTools.csEngineering.forbiddenPathForm"),
    CS_ENGINEERING_UNSAFE_PATH_COMPONENT("skillTools.csEngineering.unsafePathComponent"),
    CS_ENGINEERING_INVALID_KNOWLEDGE_LOCK("skillTools.csEngineering.invalidKnowledgeLock"),
    CS_ENGINEERING_EMPTY_KNOWLEDGE_MODULE("skillTools.csEngineering.emptyKnowledgeModule"),
    CS_ENGINEERING_MODULE_RULE_LIST_MISMATCH("skillTools.csEngineering.moduleRuleListMismatch"),
    CS_ENGINEERING_DUPLICATE_RULE_ID("skillTools.csEngineering.duplicateRuleId"),
    CS_ENGINEERING_RULE_METADATA_MISMATCH("skillTools.csEngineering.ruleMetadataMismatch"),
    CS_ENGINEERING_RULE_LACKS_FIELD("skillTools.csEngineering.ruleLacksField"),
    CS_ENGINEERING_CATALOGUE_ROW_MISMATCH("skillTools.csEngineering.catalogueRowMismatch"),
    CS_ENGINEERING_UNLOADED_CATALOGUE_RULE("skillTools.csEngineering.unloadedCatalogueRule"),
    CS_ENGINEERING_UNKNOWN_DOMAIN("skillTools.csEngineering.unknownDomain"),
    CS_ENGINEERING_UNKNOWN_RULE("skillTools.csEngineering.unknownRule"),
    CS_ENGINEERING_SELECTED_RULE_MISSING("skillTools.csEngineering.selectedRuleMissing"),
    CS_ENGINEERING_DRAFT_NEEDS_BASIS("skillTools.csEngineering.draftNeedsBasis"),
    CS_ENGINEERING_FACT_ASSUMPTION_COLLIDE("skillTools.csEngineering.factAssumptionCollide"),
    CS_ENGINEERING_REQUIRED_INVARIANT_NEEDS_FACT("skillTools.csEngineering.requiredInvariantNeedsFact"),
    CS_ENGINEERING_OBLIGATION_SEVERITY_MISMATCH("skillTools.csEngineering.obligationSeverityMismatch"),
    CS_ENGINEERING_REQUIRED_INVARIANT_NO_OBLIGATION("skillTools.csEngineering.requiredInvariantNoObligation"),
    CS_ENGINEERING_APPLICABLE_READY_INCOMPLETE("skillTools.csEngineering.applicableReadyIncomplete"),
    CS_ENGINEERING_NOT_APPLICABLE_HAS_CONSTRAINTS("skillTools.csEngineering.notApplicableHasConstraints"),
    CS_ENGINEERING_ONLY_READY_FROZEN("skillTools.csEngineering.onlyReadyFrozen"),
    CS_ENGINEERING_DRAFT_NOT_AUTHORIZED("skillTools.csEngineering.draftNotAuthorized"),
    CS_ENGINEERING_CANDIDATE_PATHS_UNSORTED("skillTools.csEngineering.candidatePathsUnsorted"),
    CS_ENGINEERING_REVIEW_NEEDS_READY("skillTools.csEngineering.reviewNeedsReady"),
    CS_ENGINEERING_INVARIANT_REVIEW_COVERAGE("skillTools.csEngineering.invariantReviewCoverage"),
    CS_ENGINEERING_OBLIGATION_RESULT_COVERAGE("skillTools.csEngineering.obligationResultCoverage"),
    CS_ENGINEERING_VERIFICATION_METHOD_CHANGED("skillTools.csEngineering.verificationMethodChanged"),
    CS_ENGINEERING_EXECUTED_NEEDS_EVIDENCE("skillTools.csEngineering.executedNeedsEvidence"),
    CS_ENGINEERING_ENVIRONMENT_MISMATCH("skillTools.csEngineering.environmentMismatch"),
    CS_ENGINEERING_EVIDENCE_KIND_MISMATCH("skillTools.csEngineering.evidenceKindMismatch"),
    CS_ENGINEERING_NON_EXECUTED_CLAIMS("skillTools.csEngineering.nonExecutedClaims"),
    CS_ENGINEERING_FINDING_EVIDENCE_UNCONNECTED("skillTools.csEngineering.findingEvidenceUnconnected"),
    CS_ENGINEERING_CONCLUSIVE_NEEDS_EVIDENCE("skillTools.csEngineering.conclusiveNeedsEvidence"),
    CS_ENGINEERING_SATISFIED_INCOMPLETE("skillTools.csEngineering.satisfiedIncomplete"),
    CS_ENGINEERING_VIOLATED_NO_FAIL("skillTools.csEngineering.violatedNoFail"),
    CS_ENGINEERING_FAILED_VERIFICATION_HIDDEN("skillTools.csEngineering.failedVerificationHidden"),
    CS_ENGINEERING_BLOCKING_LIST_INACCURATE("skillTools.csEngineering.blockingListInaccurate"),
    CS_ENGINEERING_UNVERIFIED_LIST_INACCURATE("skillTools.csEngineering.unverifiedListInaccurate"),
    CS_ENGINEERING_REVIEW_VERDICT_MISMATCH("skillTools.csEngineering.reviewVerdictMismatch"),
    CS_ENGINEERING_STAGE_DIGESTS_REQUIRED("skillTools.csEngineering.stageDigestsRequired"),

    // engineering practices
    SKILL_CHECK_PROCESSES_NOT_AVAILABLE("skillCheck.processesNotAvailable"),
    PRACTICES_SCHEMA_VALIDATION_FAILED("practices.schema.validationFailed"),
    PRACTICES_CLI_UNKNOWN_MODULE("practices.cli.unknownModule"),
    PRACTICES_IO_A_BOUNDED_RELATIVE_PATH_IS_REQUIRED("practices.io.aBoundedRelativePathIsRequired"),
    PRACTICES_IO_ABSOLUTE_DRIVE_BACKSLASH_CONTROL_AND_STREAM("practices.io.absoluteDriveBackslashControlAndStream"),
    PRACTICES_IO_UNSAFE_PATH_COMPONENT("practices.io.unsafePathComponent"),
    PRACTICES_IO_DUPLICATE_ENTRY("practices.io.duplicateEntry"),
    PRACTICES_IO_ARTIFACT_ROOT_MUST_BE_A_REAL("practices.io.artifactRootMustBeAReal"),
    PRACTICES_RUNNER_SNAPSHOT_MUST_BE_UNDER_THE_ROOT("practices.runner.snapshotMustBeUnderTheRoot"),
    PRACTICES_RUNNER_EXPLICIT_BOUNDED_ARGV_IS_REQUIRED("practices.runner.explicitBoundedArgvIsRequired"),
    PRACTICES_RUNNER_TIMEOUTMS_MUST_BE_WITHIN_1300000("practices.runner.timeoutmsMustBeWithin1300000"),
    PRACTICES_RUNNER_EXPLICIT_BOUNDED_ENV_IS_REQUIRED("practices.runner.explicitBoundedEnvIsRequired"),
    PRACTICES_RUNNER_ENVIRONMENT_NOTE_IS_REQUIRED("practices.runner.environmentNoteIsRequired"),
    PRACTICES_RUNNER_TOO_MANY_RUNS("practices.runner.tooManyRuns"),
    PRACTICES_RUNNER_SERVER_IS_STOPPING("practices.runner.serverIsStopping"),
    PRACTICES_RUNNER_BATCH_FILE_ARGUMENTS_UNSAFE("practices.runner.batchFileArgumentsUnsafe"),
    PRACTICES_STAGE_UNSUPPORTED_ENGINEERING_STAGE_CAPABILITY("practices.stageBundle.unsupportedEngineeringStageCapability"),
    PRACTICES_STAGE_INVALID_OR_MISMATCHED_STAGE_BUNDLE("practices.stageBundle.invalidOrMismatchedStageBundle"),
    PRACTICES_STAGE_TASK_DID_NOT_SELECT_THIS_ENGINEERING("practices.stageBundle.taskDidNotSelectThisEngineering"),
    PRACTICES_STAGE_EXPECTED_A_CLOSED_RAW_FILE_REFERENCE("practices.stageBundle.expectedAClosedRawFileReference"),
    PRACTICES_STAGE_PREPARED_REQUIREMENTS_DIFFER_FROM_THE_FROZEN("practices.stageBundle.preparedRequirementsDifferFromTheFrozen"),
    PRACTICES_STAGE_A_FROZEN_REQUIREMENT_HAS_NO_REQUIRED("practices.stageBundle.aFrozenRequirementHasNoRequired"),
    PRACTICES_STAGE_REQUIRED_SENSITIVITY_EVIDENCE_CANNOT_BE_DOWNGRADED("practices.stageBundle.requiredSensitivityEvidenceCannotBeDowngraded"),
    PRACTICES_CORE_EMPTY_PATH_SET("practices.core.emptyPathSet"),
    PRACTICES_CORE_GIT_INTERNALS_ARE_OUT_OF_SNAPSHOT("practices.core.gitInternalsAreOutOfSnapshot"),
    PRACTICES_CORE_DUPLICATE_PATHS("practices.core.duplicatePaths"),
    PRACTICES_CORE_DUPLICATE_IDENTIFIER("practices.core.duplicateIdentifier"),
    PRACTICES_CORE_SNAPSHOT_IS_LIMITED_TO_1000_DECLARED("practices.core.snapshotIsLimitedTo1000Declared"),
    PRACTICES_CORE_SNAPSHOT_EXCEEDS_64_MIB("practices.core.snapshotExceeds64Mib"),
    PRACTICES_CORE_TOO_MANY_SNAPSHOT_FILES("practices.core.tooManySnapshotFiles"),
    PRACTICES_CORE_INVALID_SNAPSHOT_SIZES_OR_LINES("practices.core.invalidSnapshotSizesOrLines"),
    PRACTICES_CORE_SNAPSHOT_EXCEEDS_TOTAL_BOUND("practices.core.snapshotExceedsTotalBound"),
    PRACTICES_CORE_SNAPSHOT_PATHS_MUST_BE_SORTED("practices.core.snapshotPathsMustBeSorted"),
    PRACTICES_CORE_NUL_IN_FROZEN_CASE_ARGV("practices.core.nulInFrozenCaseArgv"),
    PRACTICES_CORE_UNKNOWN_REQUIREMENT_IN_CASE("practices.core.unknownRequirementInCase"),
    PRACTICES_CORE_CASE_TEST_FILE_OUTSIDE_SCOPE("practices.core.caseTestFileOutsideScope"),
    PRACTICES_CORE_CASE_IDENTITY_ORACLE_AND_SENSITIVITY_RATIONALE("practices.core.caseIdentityOracleAndSensitivityRationale"),
    PRACTICES_CORE_A_REQUIREMENT_HAS_NO_TEST_CASE("practices.core.aRequirementHasNoTestCase"),
    PRACTICES_CORE_RUN_RECEIPT_VERSION_1_IS_NOT_ACCEPTED("practices.core.runReceiptVersion1IsNotAccepted"),
    PRACTICES_CORE_NUL_IN_ARGV("practices.core.nulInArgv"),
    PRACTICES_CORE_RECEIPT_EXECUTABLE_DIFFERS_FROM_ARGV_0("practices.core.receiptExecutableDiffersFromArgv0"),
    PRACTICES_CORE_INVALID_RECEIPT_TIMESTAMPS("practices.core.invalidReceiptTimestamps"),
    PRACTICES_CORE_INVALID_TIMEOUT_BOUND("practices.core.invalidTimeoutBound"),
    PRACTICES_CORE_UNSAFE_EXIT_CODE("practices.core.unsafeExitCode"),
    PRACTICES_CORE_FALSE_PASSING_RECEIPT("practices.core.falsePassingReceipt"),
    PRACTICES_CORE_NON_ASSERTING_PROCESS_FAILURE_CANNOT_MASQUERADE("practices.core.nonAssertingProcessFailureCannotMasquerade"),
    PRACTICES_CORE_INPUT_CHANGED_REQUIRES_DIFFERENT_INPUTS("practices.core.inputChangedRequiresDifferentInputs"),
    PRACTICES_CORE_INVALID_RECEIPT_REFERENCE("practices.core.invalidReceiptReference"),
    PRACTICES_CORE_CANDIDATE_FILE_SET_DIFFERS_FROM_THE("practices.core.candidateFileSetDiffersFromThe"),
    PRACTICES_CORE_PROOF_CASES_DO_NOT_EXACTLY_MATCH("practices.core.proofCasesDoNotExactlyMatch"),
    PRACTICES_CORE_EXEMPTION_WAS_NOT_IN_THE_FROZEN("practices.core.exemptionWasNotInTheFrozen"),
    PRACTICES_CORE_A_NON_EXECUTED_PROOF_CANNOT_CARRY("practices.core.aNonExecutedProofCannotCarry"),
    PRACTICES_CORE_DEMONSTRATION_CONFLICTS_WITH_THE_PLANNED_METHOD("practices.core.demonstrationConflictsWithThePlannedMethod"),
    PRACTICES_CORE_DISTINCT_RED_GREEN_RECEIPTS_AND_A("practices.core.distinctRedGreenReceiptsAndA"),
    PRACTICES_CORE_PROOF_REQUIRES_ORDINARY_FAILING_RED_AND("practices.core.proofRequiresOrdinaryFailingRedAnd"),
    PRACTICES_CORE_NO_PRODUCTION_MUTATION_IS_REPRESENTED("practices.core.noProductionMutationIsRepresented"),
    PRACTICES_CORE_RED_AND_GREEN_COMMANDS_DIFFER("practices.core.redAndGreenCommandsDiffer"),
    PRACTICES_CORE_RED_AND_GREEN_COMMANDS_MUST_MATCH("practices.core.redAndGreenCommandsMustMatch"),
    PRACTICES_CORE_DECLARED_EXECUTION_ENVIRONMENTS_DIFFER("practices.core.declaredExecutionEnvironmentsDiffer"),
    PRACTICES_CORE_GREEN_MUST_FOLLOW_THE_RECORDED_RED("practices.core.greenMustFollowTheRecordedRed"),
    PRACTICES_CORE_RED_AND_GREEN_FILE_SETS_DIFFER("practices.core.redAndGreenFileSetsDiffer"),
    PRACTICES_CORE_MUTATION_MUST_EXACTLY_DESCRIBE_PRODUCTION_CHANGES("practices.core.mutationMustExactlyDescribeProductionChanges"),
    PRACTICES_CORE_THE_CLAIMED_ASSERTION_WITNESS_IS_ABSENT("practices.core.theClaimedAssertionWitnessIsAbsent"),
    PRACTICES_CORE_CHANGED_FILE_INVENTORY_OMITS_OR_INVENTS("practices.core.changedFileInventoryOmitsOrInvents"),
    PRACTICES_CORE_EXCLUDED_PATH_IS_NOT_IN_THE("practices.core.excludedPathIsNotInThe"),
    PRACTICES_CORE_THE_REQUEST_EXCLUDES_ITS_ENTIRE_REVIEW("practices.core.theRequestExcludesItsEntireReview"),
    PRACTICES_CORE_EVERY_CHANGED_FILE_MUST_BE_REVIEWED("practices.core.everyChangedFileMustBeReviewed"),
    PRACTICES_CORE_COVERAGE_REQUIRES_A_REASON_SCOPE_NOTE("practices.core.coverageRequiresAReasonScopeNote"),
    PRACTICES_CORE_AN_EXCLUDED_FILE_CANNOT_BE_COUNTED("practices.core.anExcludedFileCannotBeCounted"),
    PRACTICES_CORE_FINDING_POINTS_AT_AN_UNREVIEWED_OR("practices.core.findingPointsAtAnUnreviewedOr"),
    PRACTICES_CORE_FINDING_LINE_RANGE_IS_OUTSIDE_THE("practices.core.findingLineRangeIsOutsideThe"),
    PRACTICES_CORE_FINDING_HAS_MISSING_REASONING_OR_IDENTITY("practices.core.findingHasMissingReasoningOrIdentity"),
    PRACTICES_CORE_RECOMMENDATIONS_AND_UNSUPPORTED_HYPOTHESES_CANNOT_BE("practices.core.recommendationsAndUnsupportedHypothesesCannotBe"),
    PRACTICES_CORE_BLOCKING_ITEM_IS_NOT_A_DEFECT("practices.core.blockingItemIsNotADefect"),
    PRACTICES_CORE_INVALID_SPECIALIST_HANDOFF("practices.core.invalidSpecialistHandoff"),
    PRACTICES_CORE_UNKNOWN_CATALOG_FORMAT("practices.core.unknownCatalogFormat"),
    PRACTICES_CORE_INCOMPLETE_RULE_CARD("practices.core.incompleteRuleCard"),
    PRACTICES_CORE_UNSUPPORTED_CAPABILITY("practices.core.unsupportedCapability"),

    // The reasons a command of the sennard program refuses its input (sennard-personal's CLI).
    /** A hook option whose value cannot be read; the {@code problem} argument says how (the wordings of the sentence). */
    HOOK_OPTION_INVALID("cli.hookOptionInvalid"),
    /** The JVM preset of the settings file is missing or is not a preset; the {@code problem} argument says which (the wordings of the sentence). */
    JVM_PRESET_UNKNOWN("cli.jvmPresetUnknown"),
    STATE_DIR_NOT_REPRESENTABLE("cli.stateDirNotRepresentable"),
    /** The installation folder cannot be written in the encoding the operating system passes file names to a program in; the folder is named by {@code SENNARD_INSTALL_ROOT} instead. */
    INSTALL_ROOT_NOT_REPRESENTABLE("cli.installRootNotRepresentable"),

    /** The business database's JDBC URL names H2: H2 holds Sennard's first-time settings only, never the business data. */
    BUSINESS_DATABASE_NOT_H2("error.BUSINESS_DATABASE_NOT_H2"),

    /** The product keeps no upload of this purpose (Sennard keeps {@code evidence} only); published under its own name, like the other attachment codes. */
    ARTIFACT_UNSUPPORTED_PURPOSE("error.ARTIFACT_UNSUPPORTED_PURPOSE", "ARTIFACT_UNSUPPORTED_PURPOSE");

    private final String messageKey;
    private final String wireCode;
    private final boolean wireReason;

    InputCode(String messageKey) {
        this(messageKey, Category.INPUT.wireCode(), true);
    }

    InputCode(String messageKey, boolean wireReason) {
        this(messageKey, Category.INPUT.wireCode(), wireReason);
    }

    /** A reason published under a code of its own and without a reason code. */
    InputCode(String messageKey, String wireCode) {
        this(messageKey, wireCode, false);
    }

    InputCode(String messageKey, String wireCode, boolean wireReason) {
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
        return Category.INPUT;
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
