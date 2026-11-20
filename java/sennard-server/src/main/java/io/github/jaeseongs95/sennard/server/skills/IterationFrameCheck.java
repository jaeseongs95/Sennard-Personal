// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.skills;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;

import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.domain.error.InputCode;
import io.github.jaeseongs95.sennard.domain.error.InvalidInputException;
import io.github.jaeseongs95.sennard.server.contract.CanonicalJson;
import io.github.jaeseongs95.sennard.server.message.Messages;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** iteration-frame-auditor: whether a proposed iteration changed the frozen contract, control or target, and whether the review's route follows from that. */
@Component
public class IterationFrameCheck implements SkillCheck<IterationFrameCheck.Command> {
    private static final Logger LOG = LoggerFactory.getLogger(IterationFrameCheck.class);
    private static final String M ="skillTools.iterationFrame.";
    private static final String REQUEST = "skills/iteration-frame-auditor/contracts/iteration-frame-audit-request.v1.schema.json";
    private static final String COMPARISON = "skills/iteration-frame-auditor/contracts/iteration-frame-comparison.v1.schema.json";
    private static final String REVIEW = "contracts/convergence-review.v1.schema.json";
    private static final Set<Route> ROUTES_AFTER_TARGET_CHANGE = EnumSet.of(Route.RESUME_NEW_EPOCH, Route.DIAGNOSE, Route.STOP);
    private static final Set<Route> ROUTES_WITHOUT_TARGET_CHANGE = EnumSet.of(Route.DIAGNOSE, Route.STOP);

    /** The commands of this check, by the name a caller sends. */
    enum Command implements SkillCommand {
        DIGEST_REQUEST("digest-request"), VALIDATE_REVIEW("validate-review");

        private final String wire;

        Command(String wire) {
            this.wire = wire;
        }

        @Override
        public String wire() {
            return wire;
        }
    }

    /** What a piece of evidence in the inventory is evidence of. */
    enum EvidenceKind {
        CONTRACT_COMPARISON("contract-comparison"), CONTROL_COMPARISON("control-comparison"), TARGET_COMPARISON("target-comparison"), ATTEMPT_OUTCOME("attempt-outcome"),
        REVIEWER_SEPARATION("reviewer-separation");

        private final String wire;

        EvidenceKind(String wire) {
            this.wire = wire;
        }

        /** The name the contract and the message catalog use for this kind. */
        @JsonValue
        public String wire() {
            return wire;
        }
    }

    /** The part of the frame a change is claimed to belong to. */
    enum ClaimedRole {
        CONTRACT("contract"), CONTROL("control"), TARGET("target"), UNKNOWN("unknown");

        private final String wire;

        ClaimedRole(String wire) {
            this.wire = wire;
        }

        @JsonValue
        public String wire() {
            return wire;
        }
    }

    enum AttemptOutcome {
        @JsonProperty("failed") FAILED, @JsonProperty("aborted") ABORTED, @JsonProperty("crashed") CRASHED
    }

    enum ControlRole {
        @JsonProperty("validator") VALIDATOR, @JsonProperty("rubric") RUBRIC, @JsonProperty("oracle") ORACLE, @JsonProperty("aggregation") AGGREGATION,
        @JsonProperty("pass-condition") PASS_CONDITION, @JsonProperty("evaluation-input") EVALUATION_INPUT
    }

    enum TargetRole {
        @JsonProperty("target") TARGET, @JsonProperty("candidate") CANDIDATE
    }

    /** How a compared part changed; each part's contract lists the values it allows. */
    enum Impact {
        @JsonProperty("unchanged") UNCHANGED, @JsonProperty("semantics-preserving") SEMANTICS_PRESERVING, @JsonProperty("semantics-changing") SEMANTICS_CHANGING,
        @JsonProperty("changed") CHANGED, @JsonProperty("uncertain") UNCERTAIN
    }

    enum Classification {
        @JsonProperty("semantics-preserving") SEMANTICS_PRESERVING, @JsonProperty("semantics-changing") SEMANTICS_CHANGING, @JsonProperty("ambiguous") AMBIGUOUS
    }

    enum Route {
        @JsonProperty("resume-new-epoch") RESUME_NEW_EPOCH, @JsonProperty("diagnose") DIAGNOSE, @JsonProperty("panel") PANEL, @JsonProperty("needs-user") NEEDS_USER,
        @JsonProperty("stop") STOP
    }

    enum ObservedProvenance {
        HOST_OBSERVED, UNAVAILABLE
    }

    enum ObservedIndependence {
        VERIFIED, UNOBSERVED
    }

    record Contract(String objective, List<String> scope, List<String> acceptanceCriteria, List<String> constraints, List<String> authorization, String workspace,
            List<String> targetLocations) {
    }

    record Workspace(String workspaceId, String locator) {
    }

    record ControlArtifact(String artifactId, ControlRole role, String locator, String digest) {
    }

    record TargetArtifact(String artifactId, TargetRole role, String locator, String digest) {
    }

    record OperationalSettings(int maxAttemptsPerEpoch, int maxEpochs, int leaseTtlSeconds) {
    }

    /** The frame as the digests are made over it: re-written, it is the document the caller sent, member for member. */
    record Frame(String schemaVersion, Workspace workspace, List<ControlArtifact> controlArtifacts, List<TargetArtifact> targetArtifacts, OperationalSettings operationalSettings) {
    }

    record Root(String rootId, long revision, long epoch, Contract contract, String contractDigest, Frame frame, String frameDigest, String controlDigest, String targetDigest) {
    }

    record Attempt(String attemptId, long epoch, long sequence, List<String> implementationActorIds, AttemptOutcome outcome, String failureFingerprint, List<String> evidenceRefs) {
    }

    record Change(String path, ClaimedRole claimedRole, String summary) {
    }

    record Proposal(Contract contract, String contractDigest, Frame frame, String frameDigest, String controlDigest, String targetDigest, List<Change> changeSummary,
            List<String> evidenceRefs) {
    }

    record Reviewer(String actorId, boolean freshContext, String freshContextEvidenceRef, List<String> implementationActorIds) {
    }

    record Evidence(String evidenceRef, String artifactDigest, String locator, EvidenceKind kind, boolean verified) {
    }

    record Request(String schemaVersion, Root root, List<Attempt> attemptHistory, Proposal proposal, Reviewer reviewer, List<Evidence> evidenceInventory) {
    }

    record FreshContext(boolean confirmed, String evidenceRef) {
    }

    record Comparability(boolean comparable, String rationale) {
    }

    record ObservedReviewer(String actor, List<String> parentChain, ObservedProvenance provenance, ObservedIndependence independence) {
    }

    record Review(String schemaVersion, String reviewId, String rootId, long rootRevision, long epoch, String reviewerActorId, FreshContext freshContext,
            ObservedReviewer observedReviewer, Classification classification, Comparability comparability, Route route, Frame proposedFrame, List<String> evidenceRefs,
            List<String> userApprovalRefs, String reviewedAt) {
    }

    record ComparisonReviewer(String actorId, boolean freshContext) {
    }

    /** One compared part: how it changed, the paths that changed and the evidence for it. */
    record Dimension(Impact impact, List<String> changedPaths, List<String> evidenceRefs) {
    }

    record Comparisons(Dimension contract, Dimension control, Dimension target) {
    }

    record Rationale(String statement, List<String> evidenceRefs) {
    }

    record Comparison(String schemaVersion, String rootId, long reviewedEpoch, String requestArtifactDigest, ComparisonReviewer reviewer, Comparisons comparisons,
            List<String> evidenceRefs, List<Rationale> rationale, List<String> limitations) {
    }

    record DigestAnswer(String schemaVersion, String requestArtifactDigest) {
    }

    record ReviewAnswer(boolean valid, List<String> errors) {
    }

    /** One attempt's place in the history: a pair of these may not repeat. */
    private record Position(long epoch, long sequence) {
    }

    private final SkillSchemas schemas;
    private final Messages messages;
    private final JsonMapper mapper;

    public IterationFrameCheck(SkillSchemas schemas, Messages messages, JsonMapper mapper) {
        this.schemas = schemas;
        this.messages = messages;
        this.mapper = mapper;
    }

    @Override
    public String skill() {
        return "iteration-frame-auditor";
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
                validateRequest(request);
                yield SkillCheckResult.ok(new DigestAnswer("1.0.0", CanonicalJson.digest(request)));
            }
            case VALIDATE_REVIEW -> {
                List<String> errors = validateReview(args.document("review"), args.document("comparison"), args.has("request") ? args.document("request") : null, args.text("requestArtifactDigest"));
                yield SkillCheckResult.of(new ReviewAnswer(errors.isEmpty(), errors), errors.isEmpty());
            }
        };
    }

    private static InvalidInputException fail(InputCode code, Map<String, Object> details, Object... params) {
        return new InvalidInputException(code, SennardException.args(params), details == null ? Map.of() : details);
    }

    private String t(String key, Object... params) {
        return messages.forRequest(M + key, SennardException.args(params));
    }

    private void requireDigest(String actual, Object value, String label) {
        if (!Objects.equals(actual, Checks.digest(mapper, value))) {
            throw fail(InputCode.ITERATION_FRAME_DIGEST_MISMATCH, null, "label", label);
        }
    }

    private static String firstUnknown(List<String> refs, Set<String> known) {
        for (String ref : refs) {
            if (!known.contains(ref)) {
                return ref;
            }
        }
        return null;
    }

    private static void requireKnown(List<String> refs, Set<String> known, String subject, String id) {
        String unknown = firstUnknown(refs, known);
        if (unknown != null) {
            throw fail(InputCode.ITERATION_FRAME_UNKNOWN_EVIDENCE, null, "subject", subject, "id", id, "ref", unknown);
        }
    }

    private static void unique(List<?> values, String label) {
        if (!Checks.allUnique(values)) {
            throw fail(InputCode.ITERATION_FRAME_DUPLICATES, null, "label", label);
        }
    }

    private static void requireTargetLocations(Contract contract, Frame frame, String label) {
        // Locators are opaque references, including URIs: no host path normalization.
        List<String> actual = frame.targetArtifacts().stream().map(TargetArtifact::locator).toList();
        unique(actual, label + ".frame.targetArtifacts[].locator");
        Set<String> expected = new HashSet<>(contract.targetLocations());
        if (actual.size() != expected.size() || !expected.containsAll(actual)) {
            throw fail(InputCode.ITERATION_FRAME_DIGEST_MISMATCH, SennardException.args("code", "TARGET_LOCATIONS_MISMATCH", "side", label, "expected", new ArrayList<>(expected), "actual", actual),
                    "label", label + ".contract.targetLocations / frame.targetArtifacts[].locator");
        }
    }

    private static void requireClaimed(ClaimedRole role, boolean changed, Set<ClaimedRole> claimed) {
        if (changed && !claimed.contains(role) && !claimed.contains(ClaimedRole.UNKNOWN)) {
            throw fail(InputCode.ITERATION_FRAME_CHANGE_SUMMARY_MISSING, null, "role", role.wire());
        }
    }

    /** The request, judged against its contract and its own digests; the bound record when it holds. */
    Request validateRequest(JsonNode document) {
        if (document == null || !document.isObject()) {
            throw fail(InputCode.ITERATION_FRAME_REQUEST_NOT_OBJECT, null);
        }
        List<SkillSchemas.Violation> violations = schemas.violations(REQUEST, document);
        if (!violations.isEmpty()) {
            throw fail(InputCode.ITERATION_FRAME_REQUEST_SCHEMA_FAILED, SennardException.args("errors", violations));
        }
        Request request = schemas.bind(document, Request.class);
        Root root = request.root();
        Proposal proposal = request.proposal();
        requireDigest(root.contractDigest(), root.contract(), "root.contractDigest");
        requireDigest(root.frameDigest(), root.frame(), "root.frameDigest");
        requireDigest(root.controlDigest(), root.frame().controlArtifacts(), "root.controlDigest");
        requireDigest(root.targetDigest(), root.frame().targetArtifacts(), "root.targetDigest");
        requireDigest(proposal.contractDigest(), proposal.contract(), "proposal.contractDigest");
        requireDigest(proposal.frameDigest(), proposal.frame(), "proposal.frameDigest");
        requireDigest(proposal.controlDigest(), proposal.frame().controlArtifacts(), "proposal.controlDigest");
        requireDigest(proposal.targetDigest(), proposal.frame().targetArtifacts(), "proposal.targetDigest");
        requireTargetLocations(root.contract(), root.frame(), "root");
        requireTargetLocations(proposal.contract(), proposal.frame(), "proposal");
        Map<String, EvidenceKind> kindByRef = new HashMap<>();
        List<String> ids = new ArrayList<>();
        for (Evidence item : request.evidenceInventory()) {
            ids.add(item.evidenceRef());
            kindByRef.put(item.evidenceRef(), item.kind());
        }
        unique(ids, "evidenceInventory.evidenceRef");
        Set<EvidenceKind> requiredKinds = new LinkedHashSet<>(List.of(EvidenceKind.CONTRACT_COMPARISON, EvidenceKind.CONTROL_COMPARISON, EvidenceKind.TARGET_COMPARISON,
                EvidenceKind.REVIEWER_SEPARATION));
        List<Attempt> attempts = request.attemptHistory();
        if (!attempts.isEmpty()) {
            requiredKinds.add(EvidenceKind.ATTEMPT_OUTCOME);
        }
        request.evidenceInventory().forEach(item -> requiredKinds.remove(item.kind()));
        if (!requiredKinds.isEmpty()) {
            throw fail(InputCode.ITERATION_FRAME_MISSING_EVIDENCE_KINDS, null, "kinds", requiredKinds.stream().map(EvidenceKind::wire).collect(Collectors.joining(", ")));
        }
        Set<String> known = kindByRef.keySet();
        List<String> attemptIds = new ArrayList<>();
        List<Position> positions = new ArrayList<>();
        Set<String> implementers = new HashSet<>();
        for (Attempt attempt : attempts) {
            attemptIds.add(attempt.attemptId());
            positions.add(new Position(attempt.epoch(), attempt.sequence()));
            if (attempt.epoch() > root.epoch()) {
                throw fail(InputCode.ITERATION_FRAME_FUTURE_EPOCH, null, "attemptId", attempt.attemptId());
            }
            implementers.addAll(attempt.implementationActorIds());
            requireKnown(attempt.evidenceRefs(), known, "attempt", attempt.attemptId());
            if (attempt.evidenceRefs().stream().noneMatch(ref -> kindByRef.get(ref) == EvidenceKind.ATTEMPT_OUTCOME)) {
                throw fail(InputCode.ITERATION_FRAME_ATTEMPT_NEEDS_OUTCOME, null, "attemptId", attempt.attemptId());
            }
        }
        unique(attemptIds, "attemptHistory.attemptId");
        unique(positions, "attemptHistory epoch/sequence");
        Reviewer reviewer = request.reviewer();
        if (!new HashSet<>(reviewer.implementationActorIds()).equals(implementers)) {
            throw fail(InputCode.ITERATION_FRAME_REVIEWER_ACTORS_MISMATCH, null);
        }
        if (implementers.contains(reviewer.actorId())) {
            throw fail(InputCode.ITERATION_FRAME_REVIEWER_NOT_DISTINCT, null);
        }
        String freshRef = reviewer.freshContextEvidenceRef();
        requireKnown(List.of(freshRef), known, "reviewerAssignment", "");
        if (kindByRef.get(freshRef) != EvidenceKind.REVIEWER_SEPARATION) {
            throw fail(InputCode.ITERATION_FRAME_FRESH_CONTEXT_EVIDENCE_KIND, null);
        }
        requireKnown(proposal.evidenceRefs(), known, "proposal", "");
        Set<ClaimedRole> claimed = EnumSet.noneOf(ClaimedRole.class);
        proposal.changeSummary().forEach(item -> claimed.add(item.claimedRole()));
        requireClaimed(ClaimedRole.CONTRACT, !Objects.equals(root.contractDigest(), proposal.contractDigest()), claimed);
        requireClaimed(ClaimedRole.CONTROL, !Objects.equals(root.controlDigest(), proposal.controlDigest()), claimed);
        requireClaimed(ClaimedRole.TARGET, !Objects.equals(root.targetDigest(), proposal.targetDigest()), claimed);
        return request;
    }

    private void comparisonShape(List<String> errors, Dimension comparison, boolean expectedChanged, String label) {
        boolean unchanged = comparison.impact() == Impact.UNCHANGED;
        if (unchanged == expectedChanged) {
            errors.add(t("impactConflictsEquality", "label", label));
        }
        boolean noChangedPaths = comparison.changedPaths().isEmpty();
        if (unchanged ? !noChangedPaths : noChangedPaths) {
            errors.add(t("changedPathsConflictsImpact", "label", label));
        }
    }

    private static boolean evidenceKindMatches(List<String> refs, EvidenceKind kind, Map<String, EvidenceKind> kindByRef) {
        return refs.stream().anyMatch(ref -> kindByRef.get(ref) == kind);
    }

    /** A reference to evidence the request never listed is one error, spelled as the catalog key the failure carries (as it always has been). */
    private static void knownRefs(List<String> errors, List<String> refs, Set<String> known) {
        if (firstUnknown(refs, known) != null) {
            errors.add(M + "unknownEvidence");
        }
    }

    private void comparisonEvidence(List<String> errors, Dimension comparison, EvidenceKind kind, String subject, Map<String, EvidenceKind> kindByRef) {
        if (firstUnknown(comparison.evidenceRefs(), kindByRef.keySet()) != null) {
            errors.add(M + "unknownEvidence");
        } else if (!evidenceKindMatches(comparison.evidenceRefs(), kind, kindByRef)) {
            errors.add(t("comparisonNeedsEvidenceKind", "subject", subject, "kind", kind.wire()));
        }
    }

    public List<String> validateReview(JsonNode reviewDocument, JsonNode comparisonDocument, JsonNode requestDocument, String frozenDigest) {
        if (reviewDocument == null || !reviewDocument.isObject()) {
            return List.of(t("reviewNotObject"));
        }
        List<SkillSchemas.Violation> reviewViolations = schemas.violations(REVIEW, reviewDocument);
        if (!reviewViolations.isEmpty()) {
            return List.of(t("reviewSchemaFailed", "errors", mapper.writeValueAsString(reviewViolations)));
        }
        if (comparisonDocument == null || !comparisonDocument.isObject()) {
            return List.of(t("comparisonRequired"));
        }
        List<SkillSchemas.Violation> comparisonViolations = schemas.violations(COMPARISON, comparisonDocument);
        if (!comparisonViolations.isEmpty()) {
            return List.of(t("comparisonSchemaFailed", "errors", mapper.writeValueAsString(comparisonViolations)));
        }
        Review review = schemas.bind(reviewDocument, Review.class);
        Comparison comparison = schemas.bind(comparisonDocument, Comparison.class);
        List<String> errors = new ArrayList<>();
        if (requestDocument == null) {
            errors.add(t("auditRequestRequired"));
        }
        if (!Checks.isDigest(frozenDigest)) {
            errors.add(t("frozenDigestRequired"));
        }
        if (requestDocument == null) {
            return errors;
        }
        Request request;
        try {
            request = validateRequest(requestDocument);
        } catch (InvalidInputException failure) {
            // The request is refused; the review check answers with the failure's catalog key and details, so this ends the handling.
            LOG.debug("iteration-frame-auditor: the audit request is refused: {}", failure.messageKey(), failure);
            errors.add(t("requestCannotSubstantiate", "message", messages.forRequest(failure)));
            if (!failure.details().isEmpty()) {
                errors.add(t("requestValidationDetails", "details", mapper.writeValueAsString(failure.details())));
            }
            return errors;
        }
        Root root = request.root();
        Proposal proposal = request.proposal();
        Reviewer reviewer = request.reviewer();
        if (!Objects.equals(CanonicalJson.digest(requestDocument), frozenDigest)) {
            errors.add(t("requestDigestMismatch"));
        }
        if (!Objects.equals(frozenDigest, comparison.requestArtifactDigest())) {
            errors.add(t("comparisonDigestMismatch"));
        }
        if (!Objects.equals(review.rootId(), root.rootId())) {
            errors.add(t("reviewRootIdMismatch"));
        }
        if (review.rootRevision() != root.revision()) {
            errors.add(t("reviewRootRevisionMismatch"));
        }
        if (review.epoch() != root.epoch()) {
            errors.add(t("reviewEpochMismatch"));
        }
        if (!Objects.equals(review.reviewerActorId(), reviewer.actorId())) {
            errors.add(t("reviewerActorMismatch"));
        }
        FreshContext fresh = review.freshContext();
        if (!fresh.confirmed()) {
            errors.add(t("freshContextNotConfirmed"));
        }
        if (!Objects.equals(fresh.evidenceRef(), reviewer.freshContextEvidenceRef())) {
            errors.add(t("freshContextEvidenceMismatch"));
        }
        if (reviewer.implementationActorIds().contains(review.reviewerActorId())) {
            errors.add(t("reviewerNotDistinctActors"));
        }
        Frame proposedFrame = review.proposedFrame();
        if (proposedFrame == null || !Objects.equals(Checks.digest(mapper, proposedFrame), proposal.frameDigest())) {
            errors.add(t("proposedFrameMismatch"));
        }
        if (!Objects.equals(comparison.rootId(), root.rootId())) {
            errors.add(t("comparisonRootIdMismatch"));
        }
        if (comparison.reviewedEpoch() != root.epoch()) {
            errors.add(t("comparisonEpochMismatch"));
        }
        ComparisonReviewer comparisonReviewer = comparison.reviewer();
        if (!Objects.equals(comparisonReviewer.actorId(), reviewer.actorId()) || !comparisonReviewer.freshContext()) {
            errors.add(t("comparisonReviewerMismatch"));
        }
        Comparisons comparisons = comparison.comparisons();
        boolean contractChanged = !Objects.equals(root.contractDigest(), proposal.contractDigest());
        boolean controlChanged = !Objects.equals(root.controlDigest(), proposal.controlDigest());
        boolean targetChanged = !Objects.equals(root.targetDigest(), proposal.targetDigest());
        comparisonShape(errors, comparisons.contract(), contractChanged, "comparisons.contract");
        comparisonShape(errors, comparisons.control(), controlChanged, "comparisons.control");
        comparisonShape(errors, comparisons.target(), targetChanged, "comparisons.target");
        Impact contractImpact = comparisons.contract().impact();
        Impact controlImpact = comparisons.control().impact();
        Impact targetImpact = comparisons.target().impact();
        if (contractChanged && contractImpact != Impact.SEMANTICS_CHANGING) {
            errors.add(t("contractMustBeSemantic"));
        }
        if (!controlChanged && controlImpact != Impact.UNCHANGED) {
            errors.add(t("controlMustBeUnchanged"));
        }
        if (!targetChanged && targetImpact != Impact.UNCHANGED) {
            errors.add(t("targetMustBeUnchanged"));
        }
        Map<String, EvidenceKind> kindByRef = new HashMap<>();
        request.evidenceInventory().forEach(item -> kindByRef.put(item.evidenceRef(), item.kind()));
        comparisonEvidence(errors, comparisons.contract(), EvidenceKind.CONTRACT_COMPARISON, "contractComparison", kindByRef);
        comparisonEvidence(errors, comparisons.control(), EvidenceKind.CONTROL_COMPARISON, "controlComparison", kindByRef);
        comparisonEvidence(errors, comparisons.target(), EvidenceKind.TARGET_COMPARISON, "targetComparison", kindByRef);
        List<String> reviewRefs = review.evidenceRefs();
        List<String> comparisonRefs = comparison.evidenceRefs();
        List<String> rationaleRefs = new ArrayList<>();
        comparison.rationale().forEach(item -> rationaleRefs.addAll(item.evidenceRefs()));
        knownRefs(errors, reviewRefs, kindByRef.keySet());
        knownRefs(errors, comparisonRefs, kindByRef.keySet());
        knownRefs(errors, rationaleRefs, kindByRef.keySet());
        if (!request.attemptHistory().isEmpty() && !evidenceKindMatches(reviewRefs, EvidenceKind.ATTEMPT_OUTCOME, kindByRef)) {
            errors.add(t("reviewNeedsAttemptOutcome"));
        }
        if (!evidenceKindMatches(reviewRefs, EvidenceKind.REVIEWER_SEPARATION, kindByRef)) {
            errors.add(t("reviewNeedsSeparation"));
        }
        Set<String> cited = new HashSet<>(reviewRefs);
        for (String ref : comparisonRefs) {
            if (!cited.contains(ref)) {
                errors.add(t("reviewMissingComparisonEvidence", "ref", ref));
            }
        }
        boolean unknownRole = proposal.changeSummary().stream().anyMatch(item -> item.claimedRole() == ClaimedRole.UNKNOWN);
        boolean uncertain = unknownRole || contractImpact == Impact.UNCERTAIN || controlImpact == Impact.UNCERTAIN || targetImpact == Impact.UNCERTAIN;
        boolean semantic = contractImpact == Impact.SEMANTICS_CHANGING || controlImpact == Impact.SEMANTICS_CHANGING;
        Classification classification = review.classification();
        boolean comparable = review.comparability().comparable();
        Route route = review.route();
        if (uncertain) {
            push(errors, classification == Classification.AMBIGUOUS, "uncertainNeedsAmbiguous");
            push(errors, !comparable, "ambiguousNonComparable");
            push(errors, route == Route.PANEL, "ambiguousRoutePanel");
        } else if (semantic) {
            push(errors, classification == Classification.SEMANTICS_CHANGING, "semanticNeedsClassification");
            push(errors, !comparable, "semanticNonComparable");
            push(errors, route == Route.NEEDS_USER, "semanticRouteNeedsUser");
        } else {
            push(errors, classification == Classification.SEMANTICS_PRESERVING, "stableNeedsPreserving");
            push(errors, comparable, "preservingComparable");
            Set<Route> allowed = targetChanged ? ROUTES_AFTER_TARGET_CHANGE : ROUTES_WITHOUT_TARGET_CHANGE;
            if (!allowed.contains(route)) {
                errors.add(t("incompatibleRoute", "targetState", targetChanged ? "changed" : "unchanged"));
            }
        }
        if (route == Route.RESUME_NEW_EPOCH) {
            push(errors, targetChanged, "resumeNeedsTargetChange");
            push(errors, targetImpact == Impact.CHANGED, "resumeNeedsImpactChanged");
        }
        return errors;
    }

    private void push(List<String> errors, boolean condition, String key) {
        if (!condition) {
            errors.add(t(key));
        }
    }
}
