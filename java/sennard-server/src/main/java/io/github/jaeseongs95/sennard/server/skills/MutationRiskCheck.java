// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.skills;

import java.text.Collator;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonValue;

import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.domain.error.InvalidInputException;
import io.github.jaeseongs95.sennard.server.contract.CanonicalJson;
import io.github.jaeseongs95.sennard.server.hook.host.ForeignPathText;
import io.github.jaeseongs95.sennard.server.message.MessageRef;
import io.github.jaeseongs95.sennard.server.message.Messages;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** mutation-risk-preflight: from a declared intent and its evidence, a READY/BLOCKED verdict and digests a later verification re-derives exactly. */
@Component
public class MutationRiskCheck implements SkillCheck<MutationRiskCheck.Command> {
    private static final Logger LOG = LoggerFactory.getLogger(MutationRiskCheck.class);
    private static final String M = "skillChecksKo.mutationRiskPreflight.";
    private static final String V = "skillChecksKo.mutationRiskPreflightVerify.";
    private static final String INTENT = "skills/mutation-risk-preflight/contracts/mutation-intent.v1.schema.json";
    private static final String REPORT = "skills/mutation-risk-preflight/contracts/mutation-preflight-report.v1.schema.json";
    private static final String VERSION = "1.0.0";
    private static final Set<ActionClass> APPROVAL = Set.of(ActionClass.DELETE, ActionClass.DEPLOY, ActionClass.PUBLISH, ActionClass.MIGRATE, ActionClass.PERMISSION_CHANGE, ActionClass.BILLING,
            ActionClass.GLOBAL_CONFIG);
    private static final Set<ActionClass> RECOVERY = Set.of(ActionClass.DELETE, ActionClass.OVERWRITE, ActionClass.DEPLOY, ActionClass.PUBLISH, ActionClass.MIGRATE, ActionClass.PERMISSION_CHANGE,
            ActionClass.BILLING, ActionClass.GLOBAL_CONFIG);
    private static final Set<ActionClass> DATA_LOSS = Set.of(ActionClass.DELETE, ActionClass.OVERWRITE, ActionClass.MIGRATE);
    private static final Set<ActionClass> CONTAINMENT_ACTIONS = Set.of(ActionClass.PERMISSION_CHANGE, ActionClass.DEPLOY, ActionClass.OTHER);
    private static final Set<ActionClass> PRIVATE_EXTERNAL_ACTIONS = Set.of(ActionClass.DEPLOY, ActionClass.OTHER);
    private static final Set<String> ROOT_LOCATORS = Set.of("/", ".", "..", "~");
    private static final Comparator<String> COLLATE = Collator.getInstance(Locale.ROOT)::compare;

    // Locator and target text analysis. Each pattern is compiled once.
    private static final Pattern DRIVE_ONLY = Pattern.compile("^[a-z]:(?:\\.)?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern UNC_SHARE_ROOT = Pattern.compile("^//[^/]+/[^/]+$");
    private static final Pattern GLOB = Pattern.compile("[*?\\[\\]]");
    private static final Pattern ENVIRONMENT_VARIABLE = Pattern.compile("\\$\\{|\\$[A-Za-z_]|%[^%]+%");
    private static final Pattern WHOLE_TARGET_TYPE = Pattern.compile("^(home|filesystem-root|drive-root|repository-root)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern EXTERNAL_ENVIRONMENT = Pattern.compile("^(production|prod|shared|external)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SHARED_ENVIRONMENT = Pattern.compile("^(production|prod|shared)$", Pattern.CASE_INSENSITIVE);

    // ---- contract enums (closed value sets of mutation-intent.v1 and mutation-preflight-report.v1); each reads and writes through its wire name

    /** The commands of this check, by the name a caller sends. */
    enum Command implements SkillCommand {
        EVALUATE("evaluate"), VERIFY_RECEIPT("verify-receipt");

        private final String wire;

        Command(String wire) {
            this.wire = wire;
        }

        @Override
        public String wire() {
            return wire;
        }
    }

    private enum ActionClass {
        DELETE("delete"), OVERWRITE("overwrite"), MOVE("move"), DEPLOY("deploy"), PUBLISH("publish"), MIGRATE("migrate"), PERMISSION_CHANGE("permission-change"), BILLING("billing"),
        GLOBAL_CONFIG("global-config"), OTHER("other");

        private final String wire;

        ActionClass(String wire) {
            this.wire = wire;
        }

        @JsonValue
        String wire() {
            return wire;
        }
    }

    /** The action classes by the name the contract writes (the same {@code wire()} Jackson reads and writes), so a document's text is looked up, not converted by a call that throws. */
    private static final Map<String, ActionClass> ACTION_BY_WIRE = Map.copyOf(Arrays.stream(ActionClass.values()).collect(Collectors.toMap(ActionClass::wire, Function.identity())));

    private enum Existence {
        ABSENT("absent"), PRESENT("present"), UNKNOWN("unknown");

        private final String wire;

        Existence(String wire) {
            this.wire = wire;
        }

        @JsonValue
        String wire() {
            return wire;
        }
    }

    private enum SideEffects {
        SCOPED_RESOURCES_ONLY("scoped-resources-only"), SCOPED_RESOURCES_PLUS_PLATFORM_EVENTS("scoped-resources-plus-platform-events");

        private final String wire;

        SideEffects(String wire) {
            this.wire = wire;
        }

        @JsonValue
        String wire() {
            return wire;
        }
    }

    private enum RecipientInventory {
        OBSERVED("observed"), NOT_OBSERVABLE("not-observable");

        private final String wire;

        RecipientInventory(String wire) {
            this.wire = wire;
        }

        @JsonValue
        String wire() {
            return wire;
        }
    }

    private enum Reversibility {
        REVERSIBLE("reversible"), CONDITIONAL("conditional"), IRREVERSIBLE("irreversible");

        private final String wire;

        Reversibility(String wire) {
            this.wire = wire;
        }

        @JsonValue
        String wire() {
            return wire;
        }
    }

    private enum CheckState {
        PASSED("passed"), FAILED("failed"), UNKNOWN("unknown");

        private final String wire;

        CheckState(String wire) {
            this.wire = wire;
        }

        @JsonValue
        String wire() {
            return wire;
        }
    }

    private enum ApprovalStatus {
        NOT_REQUIRED("not-required"), MISSING("missing"), OBSERVED("observed"), STALE("stale"), MISMATCHED("mismatched");

        private final String wire;

        ApprovalStatus(String wire) {
            this.wire = wire;
        }

        @JsonValue
        String wire() {
            return wire;
        }
    }

    private enum Verdict {
        READY, NEEDS_INPUT, NEEDS_APPROVAL, NEEDS_REDESIGN, BLOCKED
    }

    /** The error codes of a receipt that does not verify. */
    private enum ErrorCode {
        INVALID_INPUT, INVALID_TRANSITION, STALE_REVISION
    }

    // ---- the intent (mutation-intent.v1.schema.json). Serialized again, these records write exactly the members the schema allows, so a digest of one equals a digest of the document.

    private record Intent(String schemaVersion, String operationId, ActionClass actionClass, List<Target> targets, JsonNode plannedCommandOrTool, Scope scopeRef, Authorization authorizationRef,
            List<Approval> approvalEvidenceRefs, List<CurrentState> currentStateEvidenceRefs, RecoveryPlan recoveryPlan, BlastRadius expectedBlastRadius) {
        // plannedCommandOrTool stays a raw tree: it is hashed and echoed into the action digest as given, and no rule reads inside it.
    }

    private record Target(String locator, String targetType, String environment, String expectedFingerprint) {
        Target withLocator(String canonical) {
            return new Target(canonical, targetType, environment, expectedFingerprint);
        }
    }

    private record Scope(String locator, String digest, List<String> includedTargets, List<String> excludedTargets) {
    }

    private record Authorization(String locator, String digest, List<String> allowedActions, List<String> allowedTargets, List<String> environments) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record Approval(String locator, String digest, String operationId, String actionClass, List<String> targetLocators, List<String> environments, String expiresAt) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record CurrentState(String locator, String digest, String targetLocator, String fingerprint, Existence existence) {
    }

    /** The four references may be null and are then written as null; the containment is optional and is then left out. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private record RecoveryPlan(boolean available, String method, String backupRef, String restoreProcedureRef, String restoreTestEvidenceRef, boolean irreversibilityAccepted,
            @JsonInclude(JsonInclude.Include.NON_NULL) FreshResourceContainment freshResourceContainment) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record FreshResourceContainment(String commandDigest, String targetDigest, EvidenceRef guardEvidenceRef, String partialFailure, ExternalPreparation externalPreparation) {
    }

    private record EvidenceRef(String locator, String digest) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record ExternalPreparation(String exposure, String existingState, SideEffects sideEffects, PlatformEvents platformEvents, EvidenceRef effectsEvidenceRef) {
    }

    private record PlatformEvents(String providerStandardEvents, int integrationChanges, int recipientChanges, int permissionChanges, int separateDispatches, String payloadScope,
            RecipientInventory recipientInventory, List<String> knownHighRiskFollowUpEffects, String approvalConditions, String requiredTargetQueries) {
    }

    private record BlastRadius(long affectedTargets, long affectedUsers, boolean external, String description) {
    }

    // ---- the report (mutation-preflight-report.v1.schema.json); component order is the report's key order

    private record Report(String schemaVersion, String operationId, String actionDigest, String targetDigest, Classification classification, List<Check> checks, ApprovalResult approval,
            RecoveryResult recovery, BlastRadius blastRadius, List<String> unresolved, List<String> invalidationTriggers, String observedAt, Verdict verdict) {
    }

    private record Classification(ActionClass actionClass, Reversibility reversibility, boolean externalImpact, boolean dataLossPotential, boolean permissionImpact) {
    }

    private record Check(String checkId, CheckState state, List<String> evidenceRefs, String message) {
    }

    private record ApprovalResult(boolean required, ApprovalStatus status, List<String> evidenceRefs) {
    }

    private record RecoveryResult(boolean available, boolean restoreTested, boolean irreversibilityAccepted, List<String> evidenceRefs) {
    }

    // ---- what is digested (constructed objects) and what is answered

    /** The object the action digest covers: the intent with every locator canonical and every list sorted. */
    private record ActionIntent(String operationId, ActionClass actionClass, JsonNode plannedCommandOrTool, String targetDigest, Scope scopeRef, Authorization authorizationRef,
            List<Approval> approvalEvidenceRefs, List<CurrentState> currentStateEvidenceRefs, RecoveryPlan recoveryPlan, BlastRadius expectedBlastRadius) {
    }

    /** An entry of the target digest of a document that broke its contract. */
    private record LocatorKey(String locator) {
    }

    /** The object the action digest of a document that broke its contract covers. */
    private record InvalidAction(boolean invalid, JsonNode operationId) {
    }

    /** A receipt's answer. The digests are strings, or the raw members of a report that broke its contract. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private record Receipt(String schemaVersion, boolean valid, ErrorCode errorCode, String reason, Object actionDigest, Object targetDigest) {
    }

    private record Keyed<T>(String key, T item) {
    }

    private record ApprovalState(ApprovalStatus status, List<String> refs) {
    }

    // ---- documents that broke their contract: every member is the raw value, because such a document has no reliable shape. They are bound only to be read by type, never to be validated.

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Draft(JsonNode operationId, JsonNode actionClass, JsonNode targets, JsonNode expectedBlastRadius) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record DraftBlast(JsonNode affectedTargets, JsonNode affectedUsers, JsonNode external, JsonNode description) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record DraftTarget(JsonNode locator) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record DraftDigests(JsonNode actionDigest, JsonNode targetDigest) {
    }

    /** The verification request: no contract covers it as a whole, only its {@code report} and {@code intent}, which stay raw until their own gates pass. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record VerifyInput(JsonNode report, JsonNode intent, String verificationTime) {
    }

    private static final Draft NO_DRAFT = new Draft(null, null, null, null);
    private static final DraftDigests NO_DIGESTS = new DraftDigests(null, null);
    private static final VerifyInput NO_VERIFY_INPUT = new VerifyInput(null, null, null);

    private final SkillSchemas schemas;
    private final Messages messages;
    private final Clock clock;
    private final JsonMapper mapper;

    public MutationRiskCheck(SkillSchemas schemas, Messages messages, Clock clock, JsonMapper mapper) {
        this.schemas = schemas;
        this.messages = messages;
        this.clock = clock;
        this.mapper = mapper;
    }

    @Override
    public String skill() {
        return "mutation-risk-preflight";
    }

    @Override
    public Class<Command> commandType() {
        return Command.class;
    }

    @Override
    public SkillCheckResult run(Command command, SkillArguments args) {
        return switch (command) {
            case EVALUATE -> {
                Instant now = args.has("now") ? Checks.instant(args.text("now")) : clock.instant();
                Report report = now == null ? blockedReport(NO_DRAFT, List.of(t("evaluationTimeInvalid")), Instant.EPOCH) : evaluateArgument(args, now);
                yield new SkillCheckResult(report, report.verdict() == Verdict.READY ? 0 : 2);
            }
            case VERIFY_RECEIPT -> {
                Receipt outcome = verifyReceipt(args.document("input"));
                yield new SkillCheckResult(outcome, outcome.valid() ? 0 : 2);
            }
        };
    }

    /** The report for the {@code input} argument; an argument that cannot be read is a BLOCKED report that says why. */
    private Report evaluateArgument(SkillArguments args, Instant now) {
        JsonNode input;
        try {
            input = args.document("input");
        } catch (InvalidInputException failure) {
            // The refusal becomes this check's own BLOCKED verdict (the report carries its catalog key); the exception is logged here so the conversion is not silent.
            LOG.debug("The input argument cannot be read: {}", failure.messageKey(), failure);
            return blockedReport(NO_DRAFT, List.of(failure.messageKey()), now);
        }
        return evaluate(input, now);
    }

    /** The report's wording is the model's (English) whatever the request's language: verification recomputes the whole report and compares it with the one given. */
    private String t(String key, Object... params) {
        return messages.render(MessageRef.of(M + key, SennardException.args(params)));
    }

    private String v(String key, Object... params) {
        return messages.render(MessageRef.of(V + key, SennardException.args(params)));
    }

    private List<String> contractErrors(String location, JsonNode value) {
        return SkillSchemas.texts(schemas.violations(location, value));
    }

    /**
     * The canonical form of a locator an agent declared for a target that may live on any platform: read by the syntax it shows (URI, drive path, UNC path, POSIX path), the same on
     * every host, which {@link java.nio.file.Path} cannot give (it follows only the file system it runs on). That text handling is {@link ForeignPathText}'s.
     */
    private static String canonicalLocator(String value) {
        return ForeignPathText.canonicalLocator(value);
    }

    private static List<String> canonicalLocators(List<String> locators) {
        return locators.stream().map(MutationRiskCheck::canonicalLocator).toList();
    }

    private static List<String> sorted(List<String> values) {
        return values.stream().sorted().toList();
    }

    /** Why this target is too broad or unresolved to act on, or null. {@code locator} is the target's canonical locator. */
    private String unsafeTarget(Target target, String locator) {
        String raw = target.locator();
        if (ROOT_LOCATORS.contains(locator)) {
            return t("unsafeRootTarget");
        }
        if (DRIVE_ONLY.matcher(locator).matches()) {
            return t("unsafeDriveRoot");
        }
        if (UNC_SHARE_ROOT.matcher(locator).matches()) {
            return t("unsafeUncShareRoot");
        }
        if (GLOB.matcher(raw).find()) {
            return t("unsafeUnresolvedGlob");
        }
        if (ENVIRONMENT_VARIABLE.matcher(raw).find()) {
            return t("unsafeUnresolvedEnvironmentVariable");
        }
        if (WHOLE_TARGET_TYPE.matcher(target.targetType()).matches()) {
            return t("unsafeWholeTargetType", "targetType", target.targetType());
        }
        return null;
    }

    /** The items ordered by their canonical text, each text made once. */
    private <T> List<T> sortedByCanonical(List<T> items) {
        return items.stream().map(item -> new Keyed<>(CanonicalJson.of(mapper.valueToTree(item)), item)).sorted(Comparator.comparing(Keyed::key, COLLATE)).map(Keyed::item).toList();
    }

    private Approval canonicalApproval(Approval approval) {
        return new Approval(canonicalLocator(approval.locator()), approval.digest(), approval.operationId(), approval.actionClass(), sorted(canonicalLocators(approval.targetLocators())),
                sorted(approval.environments()), approval.expiresAt());
    }

    private static List<String> refLocators(List<String> locators) {
        return locators.stream().filter(s -> s != null && !s.isEmpty()).distinct().toList();
    }

    /** The approval evidence that covers this operation, action, targets and environments, judged at {@code now}. */
    private static ApprovalState approvalState(Intent intent, List<Target> targets, List<Approval> canonicalApprovals, Instant now, boolean required) {
        if (!required) {
            return new ApprovalState(ApprovalStatus.NOT_REQUIRED, List.of());
        }
        List<Approval> approvals = intent.approvalEvidenceRefs();
        if (approvals.isEmpty()) {
            return new ApprovalState(ApprovalStatus.MISSING, List.of());
        }
        List<String> targetLocators = targets.stream().map(Target::locator).toList();
        boolean sawExpired = false;
        for (int index = 0; index < approvals.size(); index++) {
            Approval given = approvals.get(index);
            Approval canonical = canonicalApprovals.get(index);
            boolean matches = Objects.equals(canonical.operationId(), intent.operationId()) && Objects.equals(canonical.actionClass(), intent.actionClass().wire())
                    && new HashSet<>(canonical.targetLocators()).containsAll(targetLocators) && targets.stream().allMatch(t -> canonical.environments().contains(t.environment()));
            if (!matches) {
                continue;
            }
            // A user approval carries no expiry unless its evidence states one; a stated expiry still applies.
            if (given.expiresAt() == null) {
                return new ApprovalState(ApprovalStatus.OBSERVED, List.of(given.locator()));
            }
            Instant expires = Checks.instant(given.expiresAt());
            if (expires == null || !expires.isAfter(now)) {
                sawExpired = true;
            } else {
                return new ApprovalState(ApprovalStatus.OBSERVED, List.of(given.locator()));
            }
        }
        return new ApprovalState(sawExpired ? ApprovalStatus.STALE : ApprovalStatus.MISMATCHED, refLocators(approvals.stream().map(Approval::locator).toList()));
    }

    // ---- a document that broke its contract

    private Draft draft(JsonNode input) {
        return input != null && input.isObject() ? schemas.bind(input, Draft.class) : NO_DRAFT;
    }

    private static String stringOf(JsonNode node) {
        return node != null && node.isString() ? node.stringValue() : null;
    }

    private static boolean integralAtLeast(JsonNode node, long minimum) {
        return node != null && node.isIntegralNumber() && node.canConvertToLong() && node.longValue() >= minimum;
    }

    /** The blast radius the document stated when it is well-formed, or the unconfirmed one. */
    private BlastRadius statedBlast(JsonNode supplied) {
        if (supplied != null && supplied.isObject()) {
            DraftBlast stated = schemas.bind(supplied, DraftBlast.class);
            String description = stringOf(stated.description());
            if (integralAtLeast(stated.affectedTargets(), 1) && integralAtLeast(stated.affectedUsers(), 0) && stated.external() != null && stated.external().isBoolean() && description != null
                    && !description.isBlank()) {
                return new BlastRadius(stated.affectedTargets().longValue(), stated.affectedUsers().longValue(), stated.external().booleanValue(), description);
            }
        }
        return new BlastRadius(1, 0, false, t("blastRadiusUnconfirmed"));
    }

    /** The action class the document named when it is one of the contract's, else {@code other}. */
    private ActionClass namedAction(JsonNode node) {
        String text = stringOf(node);
        return text == null ? ActionClass.OTHER : ACTION_BY_WIRE.getOrDefault(text, ActionClass.OTHER);
    }

    private Report blockedReport(Draft draft, List<String> reasons, Instant now) {
        List<LocatorKey> targetKeys = new ArrayList<>();
        if (draft.targets() != null && draft.targets().isArray()) {
            for (JsonNode target : draft.targets()) {
                String locator = target.isObject() ? stringOf(schemas.bind(target, DraftTarget.class).locator()) : null;
                targetKeys.add(new LocatorKey(canonicalLocator(locator == null ? "unknown" : locator)));
            }
        }
        String operation = stringOf(draft.operationId());
        String operationId = operation != null && !operation.isEmpty() ? operation : "unknown";
        return new Report(VERSION, operationId, Checks.digest(mapper, new InvalidAction(true, draft.operationId())), Checks.digest(mapper, targetKeys),
                new Classification(namedAction(draft.actionClass()), Reversibility.CONDITIONAL, false, false, false),
                List.of(new Check("input-validity", CheckState.FAILED, List.of(), String.join(" ", reasons))), new ApprovalResult(false, ApprovalStatus.NOT_REQUIRED, List.of()),
                new RecoveryResult(false, false, false, List.of()), statedBlast(draft.expectedBlastRadius()), reasons, List.of(t("invalidationInputChanged"), t("invalidationTargetChanged")),
                Checks.iso(now), Verdict.BLOCKED);
    }

    /** The report for an intent document. A failing input is a BLOCKED report, not an exception, so the caller always gets a verdict. */
    private Report evaluate(JsonNode input, Instant now) {
        List<String> violations = contractErrors(INTENT, input);
        if (!violations.isEmpty()) {
            return blockedReport(draft(input), violations, now);
        }
        return evaluate(schemas.bind(input, Intent.class), now);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private Report evaluate(Intent intent, Instant now) {
        ActionClass action = intent.actionClass();
        BlastRadius blast = intent.expectedBlastRadius();
        List<Target> targets = new ArrayList<>();
        List<String> unsafe = new ArrayList<>();
        for (Target given : intent.targets()) {
            String canonical = canonicalLocator(given.locator());
            targets.add(given.withLocator(canonical));
            String reason = unsafeTarget(given, canonical);
            if (reason != null) {
                unsafe.add(reason);
            }
        }
        List<String> targetLocators = targets.stream().map(Target::locator).toList();
        String targetDigest = Checks.digest(mapper, targets.stream().sorted(Comparator.comparing(Target::locator, COLLATE)).toList());

        Scope scopeRef = intent.scopeRef();
        Authorization authRef = intent.authorizationRef();
        List<String> included = canonicalLocators(scopeRef.includedTargets());
        List<String> excluded = canonicalLocators(scopeRef.excludedTargets());
        List<String> allowedTargets = canonicalLocators(authRef.allowedTargets());
        List<Approval> canonicalApprovals = intent.approvalEvidenceRefs().stream().map(this::canonicalApproval).toList();
        List<CurrentState> states = intent.currentStateEvidenceRefs().stream()
                .map(s -> new CurrentState(canonicalLocator(s.locator()), s.digest(), canonicalLocator(s.targetLocator()), s.fingerprint(), s.existence())).toList();
        ActionIntent covered = new ActionIntent(intent.operationId(), action, intent.plannedCommandOrTool(), targetDigest,
                new Scope(canonicalLocator(scopeRef.locator()), scopeRef.digest(), sorted(included), sorted(excluded)),
                new Authorization(canonicalLocator(authRef.locator()), authRef.digest(), sorted(authRef.allowedActions()), sorted(allowedTargets), sorted(authRef.environments())),
                sortedByCanonical(canonicalApprovals), sortedByCanonical(states), intent.recoveryPlan(), blast);
        String actionDigest = Checks.digest(mapper, covered);

        boolean externalImpact = blast.external() || targets.stream().anyMatch(t -> EXTERNAL_ENVIRONMENT.matcher(t.environment()).matches());
        boolean dataLoss = DATA_LOSS.contains(action);
        boolean permissionImpact = action == ActionClass.PERMISSION_CHANGE;
        boolean approvalRequired = APPROVAL.contains(action) || externalImpact;
        ApprovalState approval = approvalState(intent, targets, canonicalApprovals, now, approvalRequired);
        boolean scopeIncluded = new HashSet<>(included).containsAll(targetLocators);
        Set<String> excludedSet = new HashSet<>(excluded);
        boolean scopeExcluded = targetLocators.stream().anyMatch(excludedSet::contains);
        boolean authorized = authRef.allowedActions().contains(action.wire()) && new HashSet<>(allowedTargets).containsAll(targetLocators)
                && targets.stream().allMatch(t -> authRef.environments().contains(t.environment()));
        boolean currentStateMatched = targets.stream().allMatch(t -> states.stream().anyMatch(e -> Objects.equals(e.targetLocator(), t.locator()) && Objects.equals(e.fingerprint(), t.expectedFingerprint())));
        boolean recoveryRequired = RECOVERY.contains(action) || externalImpact;
        RecoveryPlan recoveryPlan = intent.recoveryPlan();
        FreshResourceContainment containment = recoveryPlan.freshResourceContainment();
        ExternalPreparation preparation = containment == null ? null : containment.externalPreparation();
        boolean available = recoveryPlan.available();
        boolean irreversibility = recoveryPlan.irreversibilityAccepted();
        String backup = recoveryPlan.backupRef();
        String restoreProcedure = recoveryPlan.restoreProcedureRef();
        String restoreTest = recoveryPlan.restoreTestEvidenceRef();
        boolean privateExternal = preparation != null && externalImpact && PRIVATE_EXTERNAL_ACTIONS.contains(action)
                && targets.stream().noneMatch(t -> SHARED_ENVIRONMENT.matcher(t.environment()).matches()) && blank(backup) && blank(restoreTest);
        boolean containmentSufficient = containment != null && CONTAINMENT_ACTIONS.contains(action) && ((!externalImpact && preparation == null) || privateExternal) && !dataLoss && available
                && !irreversibility && !blank(recoveryPlan.method()) && !blank(restoreProcedure) && Objects.equals(CanonicalJson.digest(intent.plannedCommandOrTool()), containment.commandDigest())
                && Objects.equals(targetDigest, containment.targetDigest()) && targets.stream().allMatch(t -> {
                    List<CurrentState> observations = states.stream().filter(e -> Objects.equals(e.targetLocator(), t.locator())).toList();
                    return !observations.isEmpty() && observations.stream().allMatch(e -> e.existence() == Existence.ABSENT && Objects.equals(e.fingerprint(), t.expectedFingerprint()));
                });
        boolean recoverySufficient = containment != null ? containmentSufficient
                : !recoveryRequired || (available && !blank(recoveryPlan.method()) && !blank(backup) && !blank(restoreProcedure) && (!blank(restoreTest) || irreversibility))
                        || (!available && irreversibility);
        boolean blastRadiusMatched = blast.affectedTargets() >= targets.size();
        List<String> containmentRefs = new ArrayList<>();
        if (containment != null) {
            containmentRefs.add(containment.guardEvidenceRef().locator());
        }
        if (preparation != null) {
            containmentRefs.add(preparation.effectsEvidenceRef().locator());
        }
        String recoveryMessage = containmentSufficient
                ? (preparation != null ? t("recoveryPreparationSubmitted") : t("recoveryContainmentEvidence"))
                : recoverySufficient ? t("recoverySufficientEvidence")
                        : containment != null && externalImpact ? t("recoveryExternalPreparationEvidenceNeeded") : t("recoveryDesignNeedsWork");
        // The model-facing wording is fixed in English (t() renders in the model locale): verification recomputes the whole report and compares it with the one given.
        boolean approvalPassed = approval.status() == ApprovalStatus.OBSERVED || approval.status() == ApprovalStatus.NOT_REQUIRED;
        boolean scopeOk = scopeIncluded && !scopeExcluded;
        List<Check> checks = new ArrayList<>();
        checks.add(new Check("target-specificity", unsafe.isEmpty() ? CheckState.PASSED : CheckState.FAILED, List.of(), unsafe.isEmpty() ? t("targetsSpecificCheck") : String.join(" ", unsafe)));
        checks.add(new Check("scope-match", scopeOk ? CheckState.PASSED : CheckState.FAILED, List.of(scopeRef.locator()), scopeOk ? t("scopeIncludedCheck") : t("scopeMismatchCheck")));
        checks.add(new Check("authorization-match", authorized ? CheckState.PASSED : CheckState.FAILED, List.of(authRef.locator()), authorized ? t("authorizationMatchedCheck") : t("authorizationMismatchCheck")));
        checks.add(new Check("current-state", currentStateMatched ? CheckState.PASSED : CheckState.UNKNOWN, refLocators(intent.currentStateEvidenceRefs().stream().map(CurrentState::locator).toList()),
                currentStateMatched ? t("currentStateMatchedCheck") : t("currentStateUnconfirmedCheck")));
        checks.add(new Check("approval", approvalPassed ? CheckState.PASSED : CheckState.UNKNOWN, approval.refs(),
                approvalPassed ? t("approvalPassedCheck", "status", approval.status().wire()) : t("approvalStatusCheck", "status", approval.status().wire())));
        List<String> recoveryRefs = Stream.concat(Stream.of(backup, restoreProcedure, restoreTest), containmentRefs.stream()).filter(s -> s != null && !s.isEmpty()).distinct().toList();
        checks.add(new Check("recovery", recoverySufficient ? CheckState.PASSED : CheckState.FAILED, recoveryRefs, recoveryMessage));
        checks.add(new Check("blast-radius", blastRadiusMatched ? CheckState.PASSED : CheckState.FAILED, List.of(), blastRadiusMatched ? t("blastRadiusCoversCheck") : t("blastRadiusSmallerCheck")));
        if (preparation != null && preparation.sideEffects() == SideEffects.SCOPED_RESOURCES_PLUS_PLATFORM_EVENTS) {
            PlatformEvents events = preparation.platformEvents();
            String standard = events.providerStandardEvents();
            String recipients = events.recipientInventory().wire();
            checks.add(new Check("platform-events", containmentSufficient ? CheckState.PASSED : CheckState.FAILED, List.of(preparation.effectsEvidenceRef().locator()),
                    t("platformEventsCheck", "providerStandardEvents", standard, "recipientInventory", recipients)));
        }
        List<String> unresolved = new ArrayList<>();
        Verdict verdict = Verdict.READY;
        if (!unsafe.isEmpty() || !scopeOk || !authorized) {
            verdict = Verdict.BLOCKED;
            unresolved.addAll(unsafe);
            if (!scopeOk) {
                unresolved.add(t("unresolvedScopeMismatch"));
            }
            if (!authorized) {
                unresolved.add(t("unresolvedAuthorizationMismatch"));
            }
        } else if (!currentStateMatched) {
            verdict = Verdict.NEEDS_INPUT;
            unresolved.add(t("unresolvedCurrentStateEvidence"));
        } else if (!approvalPassed) {
            verdict = Verdict.NEEDS_APPROVAL;
            unresolved.add(t("unresolvedApprovalNeeded", "status", approval.status().wire()));
        } else if (!recoverySufficient || !blastRadiusMatched) {
            verdict = Verdict.NEEDS_REDESIGN;
            if (!recoverySufficient) {
                unresolved.add(recoveryMessage);
            }
            if (!blastRadiusMatched) {
                unresolved.add(t("unresolvedBlastRadiusRecompute"));
            }
        }
        Reversibility reversibility = irreversibility ? Reversibility.IRREVERSIBLE : recoverySufficient && containment == null ? Reversibility.REVERSIBLE : Reversibility.CONDITIONAL;
        return new Report(VERSION, intent.operationId(), actionDigest, targetDigest, new Classification(action, reversibility, externalImpact, dataLoss, permissionImpact), checks,
                new ApprovalResult(approvalRequired, approval.status(), approval.refs()), new RecoveryResult(available, containment == null && !blank(restoreTest), irreversibility, recoveryRefs), blast,
                List.copyOf(new LinkedHashSet<>(unresolved)),
                List.of(t("invalidationActionChanged"), t("invalidationTargetOrEnvironmentChanged"), t("invalidationCurrentStateChanged"), t("invalidationScopeOrAuthorizationChanged"),
                        t("invalidationApprovalEvidenceChanged"), t("invalidationRecoveryPlanChanged"), t("invalidationBlastRadiusChanged")),
                Checks.iso(now), verdict);
    }

    /** An invalid receipt; the digests are the freshly derived ones when there are any, else those the report states. */
    private Receipt invalid(ErrorCode code, String reason, JsonNode reportTree, Report fresh) {
        if (fresh != null) {
            return new Receipt(VERSION, false, code, reason, fresh.actionDigest(), fresh.targetDigest());
        }
        DraftDigests stated = reportTree != null && reportTree.isObject() ? schemas.bind(reportTree, DraftDigests.class) : NO_DIGESTS;
        return new Receipt(VERSION, false, code, reason, stated.actionDigest(), stated.targetDigest());
    }

    private static boolean absent(JsonNode node) {
        return node == null || node.isNull();
    }

    private Receipt verifyReceipt(JsonNode input) {
        VerifyInput given = input.isObject() ? schemas.bind(input, VerifyInput.class) : NO_VERIFY_INPUT;
        JsonNode reportTree = given.report();
        JsonNode intentTree = given.intent();
        Instant now = given.verificationTime() == null ? clock.instant() : Checks.instant(given.verificationTime());
        if (absent(reportTree) || absent(intentTree) || now == null) {
            return invalid(ErrorCode.INVALID_INPUT, v("inputsRequired"), reportTree, null);
        }
        List<String> reportErrors = contractErrors(REPORT, reportTree);
        List<String> intentErrors = contractErrors(INTENT, intentTree);
        if (!reportErrors.isEmpty() || !intentErrors.isEmpty()) {
            List<String> details = new ArrayList<>(reportErrors.stream().map(item -> "report" + item).toList());
            details.addAll(intentErrors.stream().map(item -> "intent" + item).toList());
            return invalid(ErrorCode.INVALID_INPUT, v("contractViolation", "details", String.join("; ", details)), reportTree, null);
        }
        Report report = schemas.bind(reportTree, Report.class);
        if (report.verdict() != Verdict.READY) {
            return invalid(ErrorCode.INVALID_TRANSITION, v("onlyReadyReceipt"), reportTree, null);
        }
        Instant observedAt = Checks.instant(report.observedAt());
        if (observedAt == null) {
            return invalid(ErrorCode.INVALID_INPUT, v("inputsRequired"), reportTree, null);
        }
        if (now.isBefore(observedAt)) {
            return invalid(ErrorCode.STALE_REVISION, v("verificationBeforeObservation"), reportTree, null);
        }
        Intent intent = schemas.bind(intentTree, Intent.class);
        Report baseline = evaluate(intent, observedAt);
        if (baseline.verdict() != Verdict.READY || !baseline.equals(report)) {
            return invalid(ErrorCode.STALE_REVISION, v("receiptMismatchAtObservation"), reportTree, baseline);
        }
        Report current = evaluate(intent, now);
        if (current.verdict() != Verdict.READY || !Objects.equals(current.actionDigest(), report.actionDigest()) || !Objects.equals(current.targetDigest(), report.targetDigest())) {
            return invalid(ErrorCode.STALE_REVISION, v("intentChangedSinceReceipt"), reportTree, current);
        }
        return new Receipt(VERSION, true, null, v("receiptMatches"), current.actionDigest(), current.targetDigest());
    }
}
