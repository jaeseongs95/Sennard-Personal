// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.skills;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;

import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.domain.error.InputCode;
import io.github.jaeseongs95.sennard.domain.error.InvalidInputException;
import io.github.jaeseongs95.sennard.server.contract.CanonicalJson;
import io.github.jaeseongs95.sennard.server.message.Messages;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** recovery-strategy-selector: a handoff's strategies are recomputed from the frozen selection request; a tie stays open unless a verified panel record breaks it. */
@Component
public class RecoveryStrategyCheck implements SkillCheck<RecoveryStrategyCheck.Command> {
    private static final Logger LOG = LoggerFactory.getLogger(RecoveryStrategyCheck.class);
    private static final String M ="skillChecksKo.recoveryStrategySelector.";
    private static final String DIR = "skills/recovery-strategy-selector/contracts/";
    private static final String REQUEST = DIR + "recovery-strategy-selection-request.v1.schema.json";
    private static final String HANDOFF = DIR + "recovery-handoff.v1.schema.json";
    private static final String DIAGNOSIS = "skills/blocker-diagnostician/contracts/diagnosis-report.v1.schema.json";
    private static final String ENVELOPE = "contracts/task-envelope.v1.schema.json";
    private static final String RECEIPT = "contracts/workflow-receipt.v1.schema.json";
    private static final String TASK_CONTRACT = "skills/task-contract/contracts/task-contract-validation.v1.schema.json";
    private static final String PANEL = "independent-deliberation-panel/decision-record.v1";
    private static final String HANDOFF_LOCATOR = "recovery-handoff:";
    /** The one action name the contracts reserve for widening the scope; it is not an action a host performs. */
    private static final String SCOPE_EXPANSION = "scope-expansion";

    /** The commands of this check, by the name a caller sends. */
    enum Command implements SkillCommand {
        DIGEST_REQUEST("digest-request"), VALIDATE_HANDOFF("validate-handoff"), VALIDATE_TASK_BINDING("validate-task-binding");

        private final String wire;

        Command(String wire) {
            this.wire = wire;
        }

        @Override
        public String wire() {
            return wire;
        }
    }

    private final SkillSchemas schemas;
    private final JsonMapper mapper;
    private final BlockerDiagnosisCheck diagnosis;
    private final TaskContractCheck taskContract;
    private final Messages messages;

    public RecoveryStrategyCheck(SkillSchemas schemas, JsonMapper mapper, BlockerDiagnosisCheck diagnosis, TaskContractCheck taskContract, Messages messages) {
        this.messages = messages;
        this.schemas = schemas;
        this.mapper = mapper;
        this.diagnosis = diagnosis;
        this.taskContract = taskContract;
    }

    @Override
    public String skill() {
        return "recovery-strategy-selector";
    }

    @Override
    public Class<Command> commandType() {
        return Command.class;
    }

    @Override
    public SkillCheckResult run(Command command, SkillArguments args) {
        return switch (command) {
            case DIGEST_REQUEST -> {
                JsonNode request = args.document("input");
                select(request);
                yield SkillCheckResult.ok(new RequestDigest(CanonicalJson.digest(request)));
            }
            case VALIDATE_HANDOFF -> {
                HandoffInput input = schemas.bind(args.document("input"), HandoffInput.class);
                List<String> errors = judge(input.request(), input.handoff(), input.requestArtifactDigest()).errors();
                yield SkillCheckResult.of(new HandoffValidation(errors.isEmpty(), errors), errors.isEmpty());
            }
            case VALIDATE_TASK_BINDING -> {
                BindingReport report = validateTaskBinding(schemas.bind(args.document("input"), TaskBindingInput.class));
                yield SkillCheckResult.of(report, report.valid());
            }
        };
    }

    private static void need(boolean condition, InputCode code, Object... params) {
        Checks.need(condition, code, params);
    }

    private String t(String key, Object... params) {
        return messages.forRequest(M + key, SennardException.args(params));
    }

    private static boolean sameValues(List<String> left, List<String> right) {
        return new HashSet<>(left).equals(new HashSet<>(right));
    }

    private void requireContract(String location, JsonNode value, InputCode code) {
        List<SkillSchemas.Violation> violations = schemas.violations(location, value);
        if (!violations.isEmpty()) {
            throw new InvalidInputException(code, SennardException.args("errors", String.join("; ", SkillSchemas.texts(violations))));
        }
    }

    private static Map<String, Authorization> authorizationMap(List<Authorization> evidence) {
        Map<String, Authorization> output = new HashMap<>();
        for (Authorization item : evidence) {
            need(!output.containsKey(item.action()), InputCode.RECOVERY_STRATEGY_AUTHORIZATION_ACTION_DUPLICATE, "action", item.action());
            need(!(item.effect() == Effect.ALLOW && item.authority() == Authority.PROJECT_INSTRUCTION), InputCode.RECOVERY_STRATEGY_PROJECT_INSTRUCTION_CANNOT_ALLOW, "action", item.action());
            output.put(item.action(), item);
        }
        return output;
    }

    private static boolean boundTo(CauseView cause, String expectedDigest) {
        return cause.evidenceBindings().stream()
                .anyMatch(binding -> Objects.equals(expectedDigest, binding.artifactDigest()) && binding.relation() == Relation.SUPPORTS && binding.hypothesisIds().contains(cause.causeId()));
    }

    /**
     * The selection request as a conforming, source-bound document: the contracts' gates in the order they refuse, then every fact the handoff is judged against, read once.
     * A refusal is an {@link InvalidInputException}.
     */
    private Selection select(JsonNode tree) {
        requireContract(REQUEST, tree, InputCode.RECOVERY_STRATEGY_REQUEST_CONTRACT_VIOLATION);
        SelectionRequest request = schemas.bind(tree, SelectionRequest.class);
        BoundDiagnosis diag = request.diagnosis();
        BoundTask source = request.sourceTask();
        RequestWorkflow workflow = request.sourceWorkflow();
        requireContract(DIAGNOSIS, diag.report(), InputCode.RECOVERY_STRATEGY_DIAGNOSIS_REPORT_CONTRACT_VIOLATION);
        requireContract(ENVELOPE, source.envelope(), InputCode.RECOVERY_STRATEGY_TASK_ENVELOPE_CONTRACT_VIOLATION);
        requireContract(RECEIPT, workflow.receipt(), InputCode.RECOVERY_STRATEGY_WORKFLOW_RECEIPT_CONTRACT_VIOLATION);
        need(Objects.equals(CanonicalJson.digest(diag.report()), diag.digest()), InputCode.RECOVERY_STRATEGY_DIAGNOSIS_REPORT_DIGEST_MISMATCH);
        need(Objects.equals(CanonicalJson.digest(diag.request()), diag.requestDigest()), InputCode.RECOVERY_STRATEGY_DIAGNOSIS_REQUEST_DIGEST_MISMATCH);
        List<String> diagnosisErrors = diagnosis.validateReport(diag.report(), diag.request(), diag.requestDigest());
        need(diagnosisErrors.isEmpty(), InputCode.RECOVERY_STRATEGY_DIAGNOSIS_REPORT_BINDING_VIOLATION, "errors", String.join("; ", diagnosisErrors));
        need(Objects.equals(CanonicalJson.digest(source.envelope()), source.digest()), InputCode.RECOVERY_STRATEGY_SOURCE_TASK_DIGEST_MISMATCH);
        ReportView report = schemas.bind(diag.report(), ReportView.class);
        need(report.verdict() == DiagnosisVerdict.CAUSE_CONFIRMED && report.confirmedCause() != null, InputCode.RECOVERY_STRATEGY_CAUSE_CONFIRMED_REQUIRED);
        need(Objects.equals(CanonicalJson.digest(workflow.receipt()), workflow.receiptDigest()), InputCode.RECOVERY_STRATEGY_SOURCE_RECEIPT_DIGEST_MISMATCH);
        ReceiptView receipt = schemas.bind(workflow.receipt(), ReceiptView.class);
        need(Objects.equals(receipt.runId(), workflow.runId()) && receipt.revision() == workflow.revision() && receipt.state() == workflow.state(), InputCode.RECOVERY_STRATEGY_SOURCE_WORKFLOW_METADATA_MISMATCH);
        EnvelopeView envelope = schemas.bind(source.envelope(), EnvelopeView.class);
        need(!workflow.receiptLocator().isBlank() && Objects.equals(receipt.plan().taskId(), envelope.taskId()) && Objects.equals(receipt.plan().taskDigest(), source.digest()),
                InputCode.RECOVERY_STRATEGY_SOURCE_RECEIPT_NOT_BOUND_TO_TASK);
        need(Objects.equals(schemas.bind(diag.request(), DiagnosisRequestView.class).objective(), envelope.objective()), InputCode.RECOVERY_STRATEGY_DIAGNOSIS_OBJECTIVE_MISMATCH);
        CauseView cause = report.confirmedCause();
        need(Objects.equals(cause.causeId(), cause.hypothesisId()), InputCode.RECOVERY_STRATEGY_CAUSE_HYPOTHESIS_ID_MISMATCH);
        need(boundTo(cause, source.digest()), InputCode.RECOVERY_STRATEGY_DIAGNOSIS_NOT_BOUND_TO_TASK_DIGEST);
        need(boundTo(cause, workflow.receiptDigest()), InputCode.RECOVERY_STRATEGY_DIAGNOSIS_NOT_BOUND_TO_RECEIPT_DIGEST);
        Map<String, Authorization> authorization = authorizationMap(request.authorizationEvidence());
        need(Checks.allUnique(request.evidenceIndex().stream().map(Evidence::evidenceRef).toList()), InputCode.RECOVERY_STRATEGY_EVIDENCE_REF_NOT_UNIQUE);

        Set<String> verified = new HashSet<>();
        Set<String> panelRecords = new HashSet<>();
        for (Evidence item : request.evidenceIndex()) {
            if (item.verified()) {
                verified.add(item.evidenceRef());
                if (item.kind() == EvidenceKind.PANEL_DECISION_RECORD) {
                    panelRecords.add(item.evidenceRef());
                }
            }
        }
        String runId = workflow.runId();
        List<String> protectedTargets = List.of(runId, "run:" + runId, "workflow:" + runId, workflow.receiptLocator());
        String rootConditionDigest = Checks.digest(mapper, new RootCondition(cause.causeId(), cause.rootCondition()));
        return new Selection(request, report, cause, envelope, rootConditionDigest, authorization, verified, panelRecords, protectedTargets);
    }

    private static boolean isProtectedTarget(String target, List<String> protectedTargets) {
        for (String protectedTarget : protectedTargets) {
            if (Objects.equals(target, protectedTarget)) {
                return true;
            }
            if (target.startsWith(protectedTarget)) {
                String suffix = target.substring(protectedTarget.length());
                if (!suffix.isEmpty() && "/#?:".indexOf(suffix.charAt(0)) >= 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Expected gateFor(Strategy strategy, Selection selection) {
        TreeSet<String> reasons = new TreeSet<>();
        TaskAuthorization sourceAuth = selection.envelope().authorization();
        if (!strategy.objectivePreserved()) {
            reasons.add("objective-not-preserved");
        }
        if (!strategy.acceptanceCriteriaPreserved()) {
            reasons.add("acceptance-criteria-not-preserved");
        }
        if (strategy.causeFit() == CauseFit.NONE) {
            reasons.add("confirmed-cause-not-addressed");
        }
        if (!Objects.equals(strategy.addressesCauseId(), selection.cause().causeId())) {
            reasons.add("confirmed-cause-id-mismatch");
        }
        if (!Objects.equals(selection.rootConditionDigest(), strategy.rootConditionDigest())) {
            reasons.add("confirmed-root-condition-mismatch");
        }
        if (strategy.rootConditionChange() == null || strategy.rootConditionChange().isBlank()) {
            reasons.add("root-condition-change-missing");
        }
        if (strategy.verificationStrength() == VerificationStrength.UNAVAILABLE || strategy.verificationPlan().isEmpty()) {
            reasons.add("verification-unavailable");
        }
        if (strategy.stopConditions().isEmpty()) {
            reasons.add("stop-condition-missing");
        }
        if (strategy.mutatesPriorRun()) {
            reasons.add("prior-run-mutation");
        }
        List<String> protectedTargets = selection.protectedTargets();
        if (strategy.actions().stream().anyMatch(action -> isProtectedTarget(action.target(), protectedTargets))
                || strategy.writeTargets().stream().anyMatch(target -> isProtectedTarget(target, protectedTargets))) {
            reasons.add("prior-run-target");
        }
        if (strategy.repeatsPriorAttempt() || selection.request().priorStrategyFingerprints().contains(strategy.strategyFingerprint())) {
            reasons.add("unchanged-failed-attempt");
        }
        if (strategy.preconditions().stream().anyMatch(item -> item.status() != PreconditionStatus.SATISFIED)) {
            reasons.add("precondition-not-satisfied");
        }
        boolean approval = strategy.scopeExpansion();
        TreeSet<String> required = new TreeSet<>();
        for (Action action : strategy.actions()) {
            String name = action.name();
            Authorization evidence = selection.authorization().get(name);
            if (sourceAuth.prohibitedActions().contains(name)) {
                reasons.add("action-prohibited-by-source-task:" + name);
            } else if (evidence == null) {
                reasons.add("authorization-missing:" + name);
            } else {
                switch (evidence.effect()) {
                    case PROHIBIT -> reasons.add("action-prohibited:" + name);
                    case REQUIRE_APPROVAL -> {
                        approval = true;
                        required.add(name);
                    }
                    case ALLOW -> {
                    }
                }
            }
            if (sourceAuth.approvalRequired().contains(name)) {
                approval = true;
                required.add(name);
            }
        }
        if (strategy.scopeExpansion()) {
            required.add(SCOPE_EXPANSION);
        }
        GateVerdict verdict = !reasons.isEmpty() ? GateVerdict.FAIL : approval ? GateVerdict.REQUIRES_APPROVAL : GateVerdict.PASS;
        return new Expected(verdict, new ArrayList<>(reasons), new ArrayList<>(required));
    }

    /** Higher first; zero is a tie on every ranked field. */
    private static final Comparator<Strategy> ORDER = Comparator.<Strategy>comparingInt(s -> s.causeFit().rank())
            .thenComparingInt(s -> s.verificationStrength().rank())
            .thenComparingInt(s -> s.reversibility().rank())
            .thenComparingInt(s -> s.failureImpact().rank())
            .thenComparingInt(s -> -s.changeBreadth())
            .thenComparingInt(s -> s.objectiveGate().verdict() == GateVerdict.PASS ? 1 : 0)
            .reversed();

    private static Outcome expectedOutcome(List<Strategy> strategies, Deliberation deliberation, Set<String> evidence, Set<String> panelRecords) {
        List<Strategy> survivors = strategies.stream().filter(s -> s.objectiveGate().verdict() != GateVerdict.FAIL).toList();
        if (survivors.isEmpty()) {
            return new Outcome(survivors, List.of(), null, HandoffVerdict.NO_VIABLE_STRATEGY);
        }
        List<Strategy> ordered = new ArrayList<>(survivors);
        ordered.sort(ORDER);
        Strategy first = ordered.get(0);
        List<Strategy> leaders = ordered.stream().takeWhile(s -> ORDER.compare(s, first) == 0).toList();
        Optional<Strategy> resolved = Optional.empty();
        if (leaders.size() > 1 && deliberation != null && evidence.containsAll(deliberation.evidenceRefs()) && deliberation.evidenceRefs().stream().anyMatch(panelRecords::contains)) {
            resolved = leaders.stream().filter(s -> Objects.equals(s.strategyId(), deliberation.selectedStrategyId())).findFirst();
        }
        if (leaders.size() > 1 && resolved.isEmpty()) {
            return new Outcome(survivors, leaders, null, HandoffVerdict.NEEDS_INPUT);
        }
        Strategy selected = resolved.orElseGet(() -> leaders.get(0));
        return new Outcome(survivors, leaders, selected, selected.objectiveGate().verdict() == GateVerdict.REQUIRES_APPROVAL ? HandoffVerdict.NEEDS_APPROVAL : HandoffVerdict.SELECTED);
    }

    private void validateTaskSeed(TaskSeed seed, Strategy selected, Selection selection, List<String> errors) {
        if (seed == null) {
            errors.add(t("nextTaskSeedRequired"));
            return;
        }
        EnvelopeView source = selection.envelope();
        if (!Objects.equals(seed.objective(), source.objective())) {
            errors.add(t("seedObjectiveNotPreserved"));
        }
        if (!Objects.equals(seed.recoveryObjective(), selected.summary())) {
            errors.add(t("seedRecoveryObjectiveMismatch"));
        }
        if (!Objects.equals(seed.addressesCauseId(), selected.addressesCauseId())) {
            errors.add(t("seedCauseIdMismatch"));
        }
        if (!Objects.equals(seed.rootConditionDigest(), selected.rootConditionDigest())) {
            errors.add(t("seedRootConditionDigestMismatch"));
        }
        boolean expansion = selected.scopeExpansion();
        boolean sameScope = Objects.equals(seed.scope(), source.scope());
        if (!expansion && !sameScope) {
            errors.add(t("seedScopeNotPreserved"));
        }
        if (expansion && (!new HashSet<>(seed.scope().included()).containsAll(source.scope().included()) || sameScope)) {
            errors.add(t("seedScopeExpansionUnclear"));
        }
        if (!Objects.equals(seed.acceptanceCriteria(), source.acceptanceCriteria())) {
            errors.add(t("seedAcceptanceCriteriaMismatch"));
        }
        List<String> constraints = new ArrayList<>(source.constraints());
        constraints.addAll(selection.request().constraints());
        if (!sameValues(seed.constraints(), constraints)) {
            errors.add(t("seedConstraintsNotPreserved"));
        }
        if (!sameValues(seed.requestedActions(), selected.actions().stream().map(Action::name).toList())) {
            errors.add(t("seedRequestedActionsMismatch"));
        }
        Set<String> targets = new HashSet<>(selected.writeTargets());
        Set<String> boundTargets = new HashSet<>();
        Set<String> unitIds = new HashSet<>();
        for (WorkUnit unit : seed.workUnits()) {
            if (!unitIds.add(unit.id())) {
                errors.add(t("workUnitIdDuplicate", "id", unit.id()));
            }
            boundTargets.addAll(unit.writeTargets());
            if (!targets.containsAll(unit.writeTargets())) {
                errors.add(t("workUnitOutsideWriteTarget", "id", unit.id()));
            }
        }
        if (!boundTargets.equals(targets)) {
            errors.add(t("seedWorkUnitsTargetsMismatch"));
        }
        if (!Objects.equals(seed.verificationPlan(), selected.verificationPlan())) {
            errors.add(t("seedVerificationPlanMismatch"));
        }
    }

    private String fingerprint(Strategy strategy) {
        return Checks.digest(mapper, new Fingerprint(strategy.mechanism(), strategy.addressesCauseId(), strategy.rootConditionDigest(), strategy.rootConditionChange(), strategy.actions(),
                strategy.writeTargets(), strategy.preconditions().stream().map(Precondition::statement).toList(), strategy.verificationPlan(), strategy.stopConditions()));
    }

    /** What makes two strategies the same strategy: the same mechanism, the same actions and the same write targets, whatever their order. */
    private static Identity identity(Strategy strategy) {
        List<Action> actions = new ArrayList<>(strategy.actions());
        actions.sort(Comparator.comparing(Action::name).thenComparing(Action::target));
        return new Identity(strategy.mechanism().trim(), actions, new TreeSet<>(strategy.writeTargets()));
    }

    /** The errors a handoff has against its selection request; the bound request and handoff come with them when the request was judged. */
    private Judged judge(JsonNode requestTree, JsonNode handoffTree, String frozenRequestDigest) {
        Selection selection;
        try {
            selection = select(requestTree);
        } catch (InvalidInputException failure) {
            // The request is refused; the failure's catalog key is the handoff check's answer, so this ends the handling.
            LOG.debug("recovery-strategy-selector: the selection request is refused: {}", failure.messageKey(), failure);
            return new Judged(List.of(failure.messageKey()), null, null);
        }
        List<SkillSchemas.Violation> violations = schemas.violations(HANDOFF, handoffTree);
        if (!violations.isEmpty()) {
            return new Judged(SkillSchemas.texts(violations), null, null);
        }
        Handoff handoff = schemas.bind(handoffTree, Handoff.class);
        SelectionRequest request = selection.request();
        List<String> errors = new ArrayList<>();
        String computed = CanonicalJson.digest(requestTree);
        String expectedDigest = frozenRequestDigest != null ? frozenRequestDigest : computed;
        if (!Objects.equals(expectedDigest, handoff.selectionRequestDigest()) || !Objects.equals(expectedDigest, computed)) {
            errors.add(t("handoffRequestDigestMismatch"));
        }
        RequestWorkflow workflow = request.sourceWorkflow();
        if (!Objects.equals(handoff.sourceTask(), new SourceTask(selection.envelope().taskId(), request.sourceTask().digest()))) {
            errors.add(t("handoffSourceTaskMismatch"));
        }
        if (!Objects.equals(handoff.sourceWorkflow(), new WorkflowRef(workflow.runId(), workflow.revision(), workflow.receiptDigest()))) {
            errors.add(t("handoffSourceWorkflowMismatch"));
        }
        String causeId = selection.cause().causeId();
        if (!Objects.equals(handoff.diagnosis(), new DiagnosisBinding(request.diagnosis().digest(), selection.report().requestArtifactDigest(), causeId, selection.rootConditionDigest()))) {
            errors.add(t("handoffDiagnosisMismatch"));
        }
        List<Strategy> strategies = handoff.strategies();
        if (!Checks.allUnique(strategies.stream().map(Strategy::strategyId).toList())) {
            errors.add(t("strategyIdNotUnique"));
        }
        Set<String> fingerprints = new HashSet<>();
        Set<Identity> identities = new HashSet<>();
        Set<String> evidence = selection.verifiedEvidence();
        for (Strategy strategy : strategies) {
            String id = strategy.strategyId();
            String fingerprint = fingerprint(strategy);
            if (!Objects.equals(fingerprint, strategy.strategyFingerprint())) {
                errors.add(t("strategyFingerprintMismatch", "strategyId", id));
            }
            if (!fingerprints.add(fingerprint)) {
                errors.add(t("strategyDuplicateStructure"));
            }
            if (!identities.add(identity(strategy))) {
                errors.add(t("strategyDuplicateIdentity"));
            }
            if (strategy.changeBreadth() != strategy.writeTargets().size()) {
                errors.add(t("strategyChangeBreadthMismatch", "strategyId", id));
            }
            Expected expected = gateFor(strategy, selection);
            Gate objectiveGate = strategy.objectiveGate();
            if (expected.verdict() != objectiveGate.verdict() || !new ArrayList<>(new TreeSet<>(objectiveGate.reasons())).equals(expected.reasons())) {
                errors.add(t("strategyGateMismatch", "strategyId", id));
            }
            if (!new ArrayList<>(new TreeSet<>(strategy.requiredAuthorization())).equals(expected.requiredAuthorization())) {
                errors.add(t("strategyRequiredAuthorizationMismatch", "strategyId", id));
            }
            if (!Objects.equals(strategy.addressesCauseId(), causeId)) {
                errors.add(t("strategyCauseIdMismatch", "strategyId", id));
            }
            if (!Objects.equals(selection.rootConditionDigest(), strategy.rootConditionDigest())) {
                errors.add(t("strategyRootConditionDigestMismatch", "strategyId", id));
            }
            if (!evidence.containsAll(strategy.evidenceRefs()) || !strategy.preconditions().stream().allMatch(item -> evidence.containsAll(item.evidenceRefs()))) {
                errors.add(t("strategyUnverifiedEvidence", "strategyId", id));
            }
        }
        CrossReview crossReview = handoff.crossReview();
        Deliberation deliberation = crossReview.deliberation();
        Outcome outcome = expectedOutcome(strategies, deliberation, evidence, selection.panelRecords());
        List<String> survivorIds = outcome.survivors().stream().map(Strategy::strategyId).toList();
        if (!sameValues(handoff.survivingStrategyIds(), survivorIds)) {
            errors.add(t("survivingStrategyIdsMismatch"));
        }
        if (outcome.verdict() != handoff.verdict()) {
            errors.add(t("handoffVerdictMismatch", "verdict", outcome.verdict().name()));
        }
        String selectedId = outcome.selected() == null ? null : outcome.selected().strategyId();
        if (!Objects.equals(selectedId, handoff.selectedStrategyId())) {
            errors.add(t("selectedStrategyIdMismatch"));
        }
        List<Review> reviews = crossReview.reviews();
        if (outcome.survivors().size() <= 1) {
            if (crossReview.status() != CrossReviewStatus.NOT_REQUIRED || !reviews.isEmpty() || !crossReview.unresolvedStrategyIds().isEmpty()) {
                errors.add(t("crossReviewNotAllowed"));
            }
        } else {
            List<String> reviewIds = reviews.stream().map(Review::strategyId).toList();
            if (!sameValues(reviewIds, survivorIds) || !Checks.allUnique(reviewIds)) {
                errors.add(t("crossReviewCoverage"));
            }
            if (reviews.stream().anyMatch(review -> !evidence.containsAll(review.evidenceRefs()))) {
                errors.add(t("crossReviewUnverifiedEvidence"));
            }
            CrossReviewStatus expectedStatus = outcome.selected() != null ? CrossReviewStatus.COMPLETED : CrossReviewStatus.UNRESOLVED;
            if (expectedStatus != crossReview.status()) {
                errors.add(t("crossReviewStatusMismatch", "status", expectedStatus.wire()));
            }
            List<String> unresolved = outcome.selected() != null ? List.of() : outcome.leaders().stream().map(Strategy::strategyId).toList();
            if (!sameValues(crossReview.unresolvedStrategyIds(), unresolved)) {
                errors.add(t("crossReviewUnresolvedMismatch"));
            }
        }
        if (deliberation != null) {
            if (outcome.leaders().size() < 2) {
                errors.add(t("deliberationOnlyOnTie"));
            }
            List<String> refs = deliberation.evidenceRefs();
            if (!evidence.containsAll(refs)) {
                errors.add(t("deliberationUnverifiedEvidence"));
            }
            if (refs.stream().noneMatch(selection.panelRecords()::contains)) {
                errors.add(t("deliberationPanelRecordRequired", "kind", PANEL));
            }
            String chosen = deliberation.selectedStrategyId();
            if (chosen != null && outcome.leaders().stream().noneMatch(s -> Objects.equals(chosen, s.strategyId()))) {
                errors.add(t("deliberationSelectedNotLeader"));
            }
        }
        if (outcome.selected() != null) {
            validateTaskSeed(handoff.nextTaskSeed(), outcome.selected(), selection, errors);
            if (!sameValues(handoff.approvalRequired(), outcome.selected().requiredAuthorization())) {
                errors.add(t("handoffApprovalRequiredMismatch"));
            }
        } else if (handoff.nextTaskSeed() != null || !handoff.approvalRequired().isEmpty()) {
            errors.add(t("noSelectionNoSeed"));
        }
        return new Judged(errors, handoff, selection);
    }

    private BindingReport validateTaskBinding(TaskBindingInput input) {
        Judged judged = judge(input.request(), input.handoff(), null);
        List<String> errors = new ArrayList<>(judged.errors());
        String handoffDigest = CanonicalJson.digest(input.handoff() == null ? mapper.nullNode() : input.handoff());
        JsonNode envelopeTree = taskEnvelope(input.taskContractReport());
        if (!errors.isEmpty()) {
            return bindingReport(handoffDigest, envelopeTree, errors);
        }
        Handoff handoff = judged.handoff();
        TaskSeed seed = handoff.nextTaskSeed();
        if (seed == null || envelopeTree == null) {
            errors.add(t("bindingInputsRequired"));
            return bindingReport(handoffDigest, envelopeTree, errors);
        }
        JsonNode contract = mapper.valueToTree(new TaskContractInput("1.0.0", input.taskContractRequest(), input.taskContractReport()));
        boolean readable = true;
        try {
            taskContract.validate(contract);
        } catch (InvalidInputException failure) {
            // The task-contract refusal is this check's verdict error (its catalog key); this ends the handling.
            LOG.debug("recovery-strategy-selector: the task-contract report is refused: {}", failure.messageKey(), failure);
            errors.add(t("taskContractReportInvalid", "reason", messages.forRequest(failure)));
            // The comparisons below read members the contract guarantees; a document that does not conform to it is judged by that failure alone.
            readable = schemas.valid(TASK_CONTRACT, contract);
        }
        if (!readable) {
            return bindingReport(handoffDigest, envelopeTree, errors);
        }
        TaskRequestView taskRequest = schemas.bind(input.taskContractRequest(), TaskRequestView.class);
        TaskReportView taskReport = schemas.bind(input.taskContractReport(), TaskReportView.class);
        EnvelopeView envelope = schemas.bind(envelopeTree, EnvelopeView.class);
        if (taskReport.verdict() != TaskVerdict.PASS) {
            errors.add(t("newContractVerdictNotPass"));
        }
        if (Objects.equals(envelope.taskId(), handoff.sourceTask().taskId())) {
            errors.add(t("recoveryTaskIdMustDiffer"));
        }
        sameField(errors, "objective", envelope.objective(), seed.objective());
        sameField(errors, "scope", envelope.scope(), seed.scope());
        sameField(errors, "acceptanceCriteria", envelope.acceptanceCriteria(), seed.acceptanceCriteria());
        sameField(errors, "constraints", envelope.constraints(), seed.constraints());
        sameField(errors, "workUnits", envelope.workUnits(), seed.workUnits());
        sameField(errors, "requiredCapabilities", envelope.requiredCapabilities(), seed.requiredCapabilities());
        Set<String> authorized = new HashSet<>();
        for (Authorization item : taskRequest.authorizationEvidence()) {
            if (item.effect() == Effect.ALLOW && item.authority() != Authority.PROJECT_INSTRUCTION && !item.sourceLocator().startsWith(HANDOFF_LOCATOR)) {
                authorized.add(item.action());
            }
        }
        List<String> approvalRequired = handoff.approvalRequired();
        // scope-expansion is answered by its own error below; every other action named needs an authorization of its own.
        List<String> otherActions = new ArrayList<>(approvalRequired);
        otherActions.removeAll(List.of(SCOPE_EXPANSION));
        for (String action : otherActions) {
            if (!authorized.contains(action)) {
                errors.add(t("approvalActionNoAuthority", "action", action));
            }
        }
        if (approvalRequired.contains(SCOPE_EXPANSION) && !authorized.contains(SCOPE_EXPANSION)) {
            errors.add(t("scopeExpansionNoAuthority"));
        }
        TaskAuthorization sourceAuth = judged.selection().envelope().authorization();
        TaskAuthorization envelopeAuth = envelope.authorization();
        Set<String> sourceAllowed = new HashSet<>(sourceAuth.allowedActions());
        Set<String> requested = new HashSet<>(seed.requestedActions());
        List<String> allowed = envelopeAuth.allowedActions();
        for (String action : allowed) {
            if (!sourceAllowed.contains(action) && (!requested.contains(action) || !authorized.contains(action))) {
                errors.add(t("envelopeAllowedActionExpanded", "action", action));
            }
        }
        for (String action : requested) {
            if (!allowed.contains(action)) {
                errors.add(t("envelopeActionNotAllowed", "action", action));
            }
        }
        for (String action : sourceAuth.prohibitedActions()) {
            if (!envelopeAuth.prohibitedActions().contains(action)) {
                errors.add(t("envelopeProhibitedActionRemoved", "action", action));
            }
        }
        for (String action : sourceAuth.approvalRequired()) {
            if (!envelopeAuth.approvalRequired().contains(action) && !authorized.contains(action)) {
                errors.add(t("envelopeApprovalRequirementRemoved", "action", action));
            }
        }
        String locator = HANDOFF_LOCATOR + handoffDigest;
        boolean supplied = taskRequest.suppliedFacts().stream().anyMatch(item -> Objects.equals(locator, item.locator()));
        if (!Objects.equals(taskRequest.taskId(), envelope.taskId()) || !supplied) {
            errors.add(t("taskContractRequestNotBound"));
        }
        for (String field : List.of("/objective", "/scope", "/acceptanceCriteria", "/workUnits")) {
            boolean found = taskReport.provenance().stream().anyMatch(item -> TaskContractCheck.fieldWithin(item.field(), field) && Objects.equals(locator, item.sourceLocator()));
            if (!found) {
                errors.add(t("provenanceNotBound", "field", field));
            }
        }
        return bindingReport(handoffDigest, envelopeTree, errors);
    }

    private void sameField(List<String> errors, String field, Object envelopeValue, Object seedValue) {
        if (!Objects.equals(envelopeValue, seedValue)) {
            errors.add(t("envelopeFieldSeedMismatch", "field", field));
        }
    }

    /** The task envelope of a task-contract report when the report is an object that has one; the envelope is the task-contract check's own document, kept as it came. */
    private JsonNode taskEnvelope(JsonNode report) {
        return report == null || !report.isObject() ? null : schemas.bind(report, ReportEnvelope.class).taskEnvelope();
    }

    private static BindingReport bindingReport(String handoffDigest, JsonNode envelope, List<String> errors) {
        return new BindingReport("1.0.0", errors.isEmpty(), handoffDigest, envelope == null ? null : CanonicalJson.digest(envelope), errors);
    }

    // ---- what the commands answer -------------------------------------------------------------------------------------------------------------------------

    private record RequestDigest(String requestArtifactDigest) {
    }

    private record HandoffValidation(boolean valid, List<String> errors) {
    }

    private record BindingReport(String schemaVersion, boolean valid, String handoffDigest, String taskEnvelopeDigest, List<String> errors) {
    }

    // ---- what the commands read ---------------------------------------------------------------------------------------------------------------------------

    /** The {@code input} of validate-handoff; its two documents are the contracts' own, judged by the gates above. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record HandoffInput(JsonNode request /* gated by the request contract */, JsonNode handoff /* gated by the handoff contract */, String requestArtifactDigest) {
    }

    /** The {@code input} of validate-task-binding. The task-contract documents belong to the task-contract check, which validates and binds them. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TaskBindingInput(JsonNode request, JsonNode handoff, JsonNode taskContractRequest /* opaque: task-contract check */, JsonNode taskContractReport /* opaque: task-contract check */) {
    }

    /** What {@link TaskContractCheck#validate} takes; both documents are forwarded as they came because that check validates and binds them. */
    private record TaskContractInput(String schemaVersion, JsonNode request, JsonNode report) {
    }

    // ---- the selection request ---------------------------------------------------------------------------------------------------------------------------

    private record SelectionRequest(String schemaVersion, String selectionId, BoundDiagnosis diagnosis, BoundTask sourceTask, RequestWorkflow sourceWorkflow, List<String> constraints,
            List<Authorization> authorizationEvidence, List<String> priorStrategyFingerprints, List<Evidence> evidenceIndex) {
    }

    /** The diagnosis documents are the blocker-diagnosis check's: it validates them ({@code validateReport}), and this check hashes and forwards them as they came. */
    private record BoundDiagnosis(String locator, String digest, JsonNode report, String requestLocator, String requestDigest, JsonNode request) {
    }

    /** The envelope is the task envelope contract's document: hashed as it came, its members read through {@link EnvelopeView}. */
    private record BoundTask(String locator, String digest, JsonNode envelope) {
    }

    /** The receipt is the workflow's document: hashed as it came, its members read through {@link ReceiptView}. */
    private record RequestWorkflow(String runId, long revision, WorkflowState state, String receiptLocator, String receiptDigest, JsonNode receipt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Authorization(String action, Effect effect, Authority authority, String sourceLocator) {
    }

    private record Evidence(String evidenceRef, String locator, String digest, boolean verified, EvidenceKind kind) {
    }

    private enum Effect {
        @JsonProperty("allow") ALLOW, @JsonProperty("prohibit") PROHIBIT, @JsonProperty("require-approval") REQUIRE_APPROVAL
    }

    private enum Authority {
        @JsonProperty("system") SYSTEM, @JsonProperty("developer") DEVELOPER, @JsonProperty("user") USER, @JsonProperty("project-instruction") PROJECT_INSTRUCTION
    }

    private enum EvidenceKind {
        @JsonProperty(PANEL) PANEL_DECISION_RECORD
    }

    private enum WorkflowState {
        READY, RUNNING, NEEDS_INPUT, NEEDS_APPROVAL, NEEDS_REDESIGN, FAILED, PASSED, BLOCKED
    }

    // ---- the members this check reads of the documents other contracts own (the members are the contracts', the rest is ignored) --------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ReportView(String requestArtifactDigest, CauseView confirmedCause, DiagnosisVerdict verdict) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CauseView(String causeId, String hypothesisId, String rootCondition, List<BindingView> evidenceBindings) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BindingView(String artifactDigest, Relation relation, List<String> hypothesisIds) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record DiagnosisRequestView(String objective) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ReceiptView(String runId, long revision, WorkflowState state, PlanView plan) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PlanView(String taskId, String taskDigest) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record EnvelopeView(String taskId, String objective, Scope scope, List<String> acceptanceCriteria, List<WorkUnit> workUnits, List<String> requiredCapabilities, List<String> constraints,
            TaskAuthorization authorization) {
    }

    private record TaskAuthorization(List<String> allowedActions, List<String> prohibitedActions, List<String> approvalRequired) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ReportEnvelope(JsonNode taskEnvelope) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TaskReportView(TaskVerdict verdict, List<Provenance> provenance) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Provenance(String field, String sourceLocator) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TaskRequestView(String taskId, List<Authorization> authorizationEvidence, List<SuppliedFact> suppliedFacts) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SuppliedFact(String locator) {
    }

    private enum DiagnosisVerdict {
        CAUSE_CONFIRMED, NEXT_TEST, NEEDS_INPUT, NEEDS_APPROVAL, BLOCKED
    }

    private enum Relation {
        @JsonProperty("supports") SUPPORTS, @JsonProperty("refutes") REFUTES
    }

    private enum TaskVerdict {
        PASS, NEEDS_INPUT, BLOCKED
    }

    // ---- the handoff -------------------------------------------------------------------------------------------------------------------------------------

    private record Handoff(String schemaVersion, String handoffId, String selectionRequestDigest, SourceTask sourceTask, WorkflowRef sourceWorkflow, DiagnosisBinding diagnosis,
            List<Strategy> strategies, List<String> survivingStrategyIds, CrossReview crossReview, String selectedStrategyId, TaskSeed nextTaskSeed, List<String> approvalRequired,
            List<String> limitations, HandoffVerdict verdict) {
    }

    private record SourceTask(String taskId, String envelopeDigest) {
    }

    private record WorkflowRef(String runId, long revision, String receiptDigest) {
    }

    private record DiagnosisBinding(String reportDigest, String requestArtifactDigest, String confirmedCauseId, String rootConditionDigest) {
    }

    private record Strategy(String strategyId, String summary, String mechanism, String addressesCauseId, String rootConditionDigest, String rootConditionChange, List<Action> actions,
            List<String> writeTargets, List<Precondition> preconditions, List<Verification> verificationPlan, List<String> stopConditions, FailureImpact failureImpact,
            Reversibility reversibility, CauseFit causeFit, VerificationStrength verificationStrength, int changeBreadth, boolean scopeExpansion, boolean objectivePreserved,
            boolean acceptanceCriteriaPreserved, boolean mutatesPriorRun, boolean repeatsPriorAttempt, List<String> requiredAuthorization, List<String> evidenceRefs,
            String strategyFingerprint, Gate objectiveGate) {
    }

    private record Action(String name, String target) {
    }

    private record Precondition(String statement, PreconditionStatus status, List<String> evidenceRefs) {
    }

    private record Verification(String check, String expectedResult, String evidenceRequired) {
    }

    private record Gate(GateVerdict verdict, List<String> reasons) {
    }

    private record CrossReview(CrossReviewStatus status, List<Review> reviews, List<String> unresolvedStrategyIds, Deliberation deliberation) {
    }

    private record Review(String strategyId, String challenge, String response, List<String> evidenceRefs) {
    }

    private record Deliberation(String selectedStrategyId, List<String> evidenceRefs) {
    }

    private record TaskSeed(String objective, String recoveryObjective, String addressesCauseId, String rootConditionDigest, Scope scope, List<String> acceptanceCriteria,
            List<String> constraints, List<WorkUnit> workUnits, List<String> requiredCapabilities, List<String> requestedActions, List<Verification> verificationPlan) {
    }

    private record Scope(List<String> included, List<String> excluded) {
    }

    private record WorkUnit(String id, String objective, List<String> dependencies, List<String> writeTargets) {
    }

    private enum HandoffVerdict {
        SELECTED, NEEDS_APPROVAL, NEEDS_INPUT, NO_VIABLE_STRATEGY, BLOCKED
    }

    private enum GateVerdict {
        PASS, REQUIRES_APPROVAL, FAIL
    }

    private enum PreconditionStatus {
        @JsonProperty("satisfied") SATISFIED, @JsonProperty("unknown") UNKNOWN, @JsonProperty("failed") FAILED
    }

    /** The wire text is also what a refusal's sentence names, so the constant carries it ({@code @JsonValue} reads and writes it). */
    private enum CrossReviewStatus {
        NOT_REQUIRED("not-required"), COMPLETED("completed"), UNRESOLVED("unresolved");

        private final String wire;

        CrossReviewStatus(String wire) {
            this.wire = wire;
        }

        @JsonValue
        String wire() {
            return wire;
        }
    }

    /** Ranks: a higher rank is the better strategy on that axis. */
    private enum CauseFit {
        @JsonProperty("direct") DIRECT(3), @JsonProperty("containment") CONTAINMENT(2), @JsonProperty("workaround") WORKAROUND(1), @JsonProperty("none") NONE(0);

        private final int rank;

        CauseFit(int rank) {
            this.rank = rank;
        }

        int rank() {
            return rank;
        }
    }

    private enum VerificationStrength {
        @JsonProperty("direct") DIRECT(2), @JsonProperty("indirect") INDIRECT(1), @JsonProperty("unavailable") UNAVAILABLE(0);

        private final int rank;

        VerificationStrength(int rank) {
            this.rank = rank;
        }

        int rank() {
            return rank;
        }
    }

    private enum Reversibility {
        @JsonProperty("full") FULL(2), @JsonProperty("partial") PARTIAL(1), @JsonProperty("none") NONE(0);

        private final int rank;

        Reversibility(int rank) {
            this.rank = rank;
        }

        int rank() {
            return rank;
        }
    }

    private enum FailureImpact {
        @JsonProperty("low") LOW(3), @JsonProperty("medium") MEDIUM(2), @JsonProperty("high") HIGH(1), @JsonProperty("critical") CRITICAL(0);

        private final int rank;

        FailureImpact(int rank) {
            this.rank = rank;
        }

        int rank() {
            return rank;
        }
    }

    // ---- what the judgment works with ------------------------------------------------------------------------------------------------------------------------

    /** What a conforming selection request establishes, computed once: the request, the members read of its embedded documents, and the sets a handoff is judged against. */
    private record Selection(SelectionRequest request, ReportView report, CauseView cause, EnvelopeView envelope, String rootConditionDigest, Map<String, Authorization> authorization,
            Set<String> verifiedEvidence, Set<String> panelRecords, List<String> protectedTargets) {
    }

    /** The confirmed cause's identity as the strategies' {@code rootConditionDigest} binds it. */
    private record RootCondition(String causeId, String rootCondition) {
    }

    /** The members a strategy's fingerprint covers; serialised, it is the document the fingerprint is the digest of. */
    private record Fingerprint(String mechanism, String addressesCauseId, String rootConditionDigest, String rootConditionChange, List<Action> actions, List<String> writeTargets,
            List<String> preconditions, List<Verification> verificationPlan, List<String> stopConditions) {
    }

    private record Identity(String mechanism, List<Action> actions, Set<String> writeTargets) {
    }

    /** The gate a strategy must carry, computed from the request. */
    private record Expected(GateVerdict verdict, List<String> reasons, List<String> requiredAuthorization) {
    }

    private record Outcome(List<Strategy> survivors, List<Strategy> leaders, Strategy selected, HandoffVerdict verdict) {
    }

    /** The errors of a handoff, with the bound handoff and request facts when the request conformed and the handoff conformed to its contract. */
    private record Judged(List<String> errors, Handoff handoff, Selection selection) {
    }
}
