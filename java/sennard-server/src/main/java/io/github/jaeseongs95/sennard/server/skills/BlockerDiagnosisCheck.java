// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.skills;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.domain.error.InputCode;
import io.github.jaeseongs95.sennard.domain.error.InvalidInputException;
import io.github.jaeseongs95.sennard.server.contract.CanonicalJson;
import io.github.jaeseongs95.sennard.server.message.Messages;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** blocker-diagnostician: clusters failure episodes by their stable tuple, selects the next discriminating check, and binds a report to a frozen request. */
@Component
public class BlockerDiagnosisCheck implements SkillCheck<BlockerDiagnosisCheck.Command> {
    private static final Logger LOG = LoggerFactory.getLogger(BlockerDiagnosisCheck.class);
    private static final String M ="skillScripts.blockerDiagnostician.";
    private static final String INPUT = "skills/blocker-diagnostician/contracts/failure-episode-set.v1.schema.json";
    private static final String REPORT = "skills/blocker-diagnostician/contracts/diagnosis-report.v1.schema.json";
    private static final String SCHEMA_VERSION = "1.0.0";
    private static final Set<String> VOLATILE = Set.of("attemptId", "createdAt", "observedAt", "randomId", "requestId", "tempPath", "temporaryPath", "time", "timestamp", "traceId", "updatedAt");
    private static final Comparator<String> COLLATE = Collator.getInstance(Locale.ROOT)::compare;
    /** Most information first, then the least risky, the cheapest, and by check ID. */
    private static final Comparator<CandidateTest> RANKING = Comparator.<CandidateTest>comparingInt(CandidateTest::informationGain).reversed().thenComparing(CandidateTest::risk)
            .thenComparingInt(CandidateTest::cost).thenComparing(CandidateTest::checkId, COLLATE);

    /** The commands of this check, by the name a caller sends. */
    enum Command implements SkillCommand {
        CLUSTER_FAILURES("cluster-failures"), DIGEST_REQUEST("digest-request"), ANALYZE("analyze"), VALIDATE_REPORT("validate-report");

        private final String wire;

        Command(String wire) {
            this.wire = wire;
        }

        @Override
        public String wire() {
            return wire;
        }
    }

    private enum CausalLayer {
        @JsonProperty("input") INPUT, @JsonProperty("state") STATE, @JsonProperty("permission") PERMISSION, @JsonProperty("environment") ENVIRONMENT, @JsonProperty("dependency") DEPENDENCY,
        @JsonProperty("timing") TIMING, @JsonProperty("implementation") IMPLEMENTATION, @JsonProperty("tooling") TOOLING, @JsonProperty("unknown") UNKNOWN
    }

    private enum State {
        @JsonProperty("open") OPEN, @JsonProperty("supported") SUPPORTED, @JsonProperty("refuted") REFUTED, @JsonProperty("confirmed") CONFIRMED
    }

    private enum Relation {
        @JsonProperty("supports") SUPPORTS, @JsonProperty("refutes") REFUTES;

        /** The name the contract writes: the constant's, in lower case. */
        String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Declared from the least to the most risky: the order is the ranking. */
    private enum Risk {
        @JsonProperty("read-only") READ_ONLY, @JsonProperty("reversible") REVERSIBLE, @JsonProperty("external") EXTERNAL, @JsonProperty("destructive") DESTRUCTIVE
    }

    private enum Verdict {
        CAUSE_CONFIRMED, NEXT_TEST, NEEDS_INPUT, NEEDS_APPROVAL, BLOCKED
    }

    private enum Disposition {
        PROHIBITED, APPROVAL_REQUIRED, ALLOWED
    }

    /** The request ({@code failure-episode-set.v1}); {@code schemaVersion} is the schema's constant and {@code lastKnownGood} a free-form note no judgment reads. */
    @JsonIgnoreProperties({"schemaVersion", "lastKnownGood"})
    private record Request(String objective, String expectedBehavior, List<Episode> episodes, List<String> constraints, Authorization authorization, List<EvidenceBinding> evidenceBindings,
            List<AttemptedCheck> attemptedChecks, List<Hypothesis> candidateHypotheses, List<CandidateTest> candidateTests, List<String> accessBlockers) {
    }

    /** The stable failure tuple is the caller's free-form object: it is normalized and hashed, never read member by member. */
    @JsonIgnoreProperties("changeSummary")
    private record Episode(String attemptId, String operation, Map<String, Object> stableFailureTuple, String environmentDigest, List<String> evidenceRefs) {
    }

    private record Authorization(List<String> allowedChecks, List<String> approvalRequired, List<String> prohibitedChecks) {
    }

    private record EvidenceBinding(String evidenceRef, String artifactDigest, List<String> hypothesisIds, Relation relation) {
    }

    private record AttemptedCheck(String checkId, String inputDigest, String resultDigest, CandidateTest check) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record Hypothesis(String id, CausalLayer causalLayer, String statement, List<String> supportingEvidence, List<String> contradictingEvidence, State state, CauseAnalysis causeAnalysis) {
    }

    private record CauseAnalysis(String symptom, String mechanism, String rootCondition, EvidenceStatement discriminatingEvidence, EvidenceStatement eliminationObservation) {
    }

    private record EvidenceStatement(String statement, List<String> evidenceRefs) {
    }

    /**
     * A check the diagnosis may run next ({@code test}) or an attempted one ({@code testContent}, which carries no {@code inputDigest}). The check's input is the caller's free-form
     * object: it is hashed with the check ID, never read member by member.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record CandidateTest(String checkId, Map<String, Object> checkInput, String question, List<String> preconditions, Risk risk, List<String> requiredAuthorization, List<Outcome> outcomes,
            String stopCondition, int informationGain, int cost, String inputDigest) {
    }

    private record Outcome(String observation, List<String> supportsHypotheses, List<String> refutesHypotheses) {
    }

    /** What a check's input digest covers. */
    private record CheckContent(String checkId, Map<String, Object> checkInput) {
    }

    /** What a failure cluster is keyed by. */
    private record Signature(String operation, Map<String, Object> stableFailureTuple, String environmentDigest) {
    }

    private record Cluster(String fingerprint, List<String> episodeIds, String operation, Map<String, Object> stableFailureTuple, String environmentDigest) {
    }

    private record Observation(String id, String statement, List<String> evidenceRefs) {
    }

    private record Cause(String causeId, String hypothesisId, String statement, String symptom, String mechanism, String rootCondition, EvidenceStatement discriminatingEvidence,
            EvidenceStatement eliminationObservation, List<EvidenceBinding> evidenceBindings) {
    }

    /** The report: derived by {@link #analyze} and read back from a caller's document. */
    private record Report(String schemaVersion, String requestArtifactDigest, List<Cluster> failureClusters, List<Observation> observations, List<Hypothesis> hypotheses,
            CandidateTest nextDiscriminatingTest, Cause confirmedCause, String recommendedNextAction, List<String> limitations, Verdict verdict) {
    }

    /** One evidence binding of one hypothesis. */
    private record Pair(String evidenceRef, String hypothesisId, Relation relation) {
    }

    /** A check as the request attempted it. */
    private record Attempt(String checkId, String inputDigest) {
    }

    /** What an outcome tells about the hypotheses; two outcomes with the same implication do not discriminate. */
    private record Implication(List<String> supports, List<String> refutes) {
    }

    /** A request that was judged once: the request and its digest, or why it could not be. */
    private record Prepared(Request request, String digest, InvalidInputException failure) {
    }

    private record Clusters(String schemaVersion, List<Cluster> failureClusters) {
    }

    private record RequestDigest(String requestArtifactDigest) {
    }

    private record Validation(boolean valid, List<String> errors) {
    }

    private final SkillSchemas schemas;
    private final Messages messages;
    private final JsonMapper mapper;

    public BlockerDiagnosisCheck(SkillSchemas schemas, Messages messages) {
        this.schemas = schemas;
        this.messages = messages;
        this.mapper = schemas.mapper();
    }

    @Override
    public String skill() {
        return "blocker-diagnostician";
    }

    @Override
    public Class<Command> commandType() {
        return Command.class;
    }

    @Override
    public SkillCheckResult run(Command command, SkillArguments args) {
        return switch (command) {
            case CLUSTER_FAILURES -> SkillCheckResult.ok(new Clusters(SCHEMA_VERSION, clusterFailures(validated(args.document("input")))));
            case DIGEST_REQUEST -> {
                JsonNode input = args.document("input");
                validated(input);
                yield SkillCheckResult.ok(new RequestDigest(CanonicalJson.digest(input)));
            }
            case ANALYZE -> {
                JsonNode input = args.document("input");
                yield SkillCheckResult.ok(analyze(validated(input), CanonicalJson.digest(input)));
            }
            case VALIDATE_REPORT -> {
                JsonNode request = args.has("request") ? args.document("request") : null;
                List<String> errors = validateReport(args.document("report"), request, args.text("requestArtifactDigest"));
                yield SkillCheckResult.of(new Validation(errors.isEmpty(), errors), errors.isEmpty());
            }
        };
    }

    private String t(String key, Object... params) {
        return messages.forRequest(M + key, SennardException.args(params));
    }

    private static InvalidInputException input(InputCode code, Object... params) {
        return new InvalidInputException(code, SennardException.args(params));
    }

    /** A string the schema lets through but the judgments do not: blank. */
    private static void filled(String value, String name) {
        if (value.isBlank()) {
            throw input(InputCode.BLOCKER_DIAGNOSIS_MUST_BE_NON_EMPTY_STRING, "name", name);
        }
    }

    /** {@link #filled(String, String)} for a name made of a subject; the name is built only when the value is refused. */
    private static void filled(String value, String before, String subject, String after) {
        if (value.isBlank()) {
            throw input(InputCode.BLOCKER_DIAGNOSIS_MUST_BE_NON_EMPTY_STRING, "name", before + subject + after);
        }
    }

    private static void noBlank(List<String> values, String name) {
        for (String value : values) {
            if (value.isBlank()) {
                throw input(InputCode.BLOCKER_DIAGNOSIS_MUST_BE_STRING_ARRAY, "name", name);
            }
        }
    }

    private static void noBlank(List<String> values, String before, String subject, String after) {
        for (String value : values) {
            if (value.isBlank()) {
                throw input(InputCode.BLOCKER_DIAGNOSIS_MUST_BE_STRING_ARRAY, "name", before + subject + after);
            }
        }
    }

    /** The tuple without the members that differ between runs of the same failure (at any depth), keys in collation order. */
    private static Object normalizeStable(Object value) {
        return switch (value) {
            case Map<?, ?> map -> {
                Map<String, Object> sorted = new TreeMap<>(COLLATE);
                map.forEach((key, item) -> {
                    if (!VOLATILE.contains(key)) {
                        sorted.put((String) key, normalizeStable(item));
                    }
                });
                yield sorted;
            }
            case List<?> list -> list.stream().map(BlockerDiagnosisCheck::normalizeStable).toList();
            case null, default -> value;
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> normalizedTuple(Map<String, Object> tuple) {
        return (Map<String, Object>) normalizeStable(tuple);
    }

    private String checkInputDigest(CandidateTest check) {
        return Checks.digest(mapper, new CheckContent(check.checkId(), check.checkInput()));
    }

    /** The request as a record that passed the contract's schema and the rules the schema cannot state. */
    private Request validated(JsonNode tree) {
        if (!tree.isObject()) {
            throw input(InputCode.BLOCKER_DIAGNOSIS_INPUT_NOT_OBJECT);
        }
        List<SkillSchemas.Violation> violations = schemas.violations(INPUT, tree);
        if (!violations.isEmpty()) {
            throw new InvalidInputException(InputCode.BLOCKER_DIAGNOSIS_INPUT_SCHEMA_FAILED, Map.of(), SennardException.args("errors", violations));
        }
        Request request = schemas.bind(tree, Request.class);
        rules(request);
        return request;
    }

    private void rules(Request request) {
        filled(request.objective(), "objective");
        filled(request.expectedBehavior(), "expectedBehavior");
        Set<String> episodeIds = new HashSet<>();
        Set<String> inventory = new HashSet<>();
        for (Episode episode : request.episodes()) {
            String attemptId = episode.attemptId();
            filled(attemptId, "episode.attemptId");
            if (!episodeIds.add(attemptId)) {
                throw input(InputCode.BLOCKER_DIAGNOSIS_DUPLICATE_ATTEMPT_ID, "attemptId", attemptId);
            }
            filled(episode.operation(), "episode ", attemptId, " operation");
            filled(episode.environmentDigest(), "episode ", attemptId, " environmentDigest");
            noBlank(episode.evidenceRefs(), "episode ", attemptId, " evidenceRefs");
            inventory.addAll(episode.evidenceRefs());
        }
        noBlank(request.constraints(), "constraints");
        noBlank(request.accessBlockers(), "accessBlockers");
        Authorization authorization = request.authorization();
        noBlank(authorization.allowedChecks(), "authorization.allowedChecks");
        noBlank(authorization.approvalRequired(), "authorization.approvalRequired");
        noBlank(authorization.prohibitedChecks(), "authorization.prohibitedChecks");
        Map<String, Hypothesis> hypotheses = new HashMap<>();
        int confirmed = 0;
        for (Hypothesis hypothesis : request.candidateHypotheses()) {
            String id = hypothesis.id();
            filled(id, "hypothesis.id");
            if (hypotheses.putIfAbsent(id, hypothesis) != null) {
                throw input(InputCode.BLOCKER_DIAGNOSIS_DUPLICATE_HYPOTHESIS, "hypothesisId", id);
            }
            filled(hypothesis.statement(), "hypothesis ", id, " statement");
            List<String> supporting = hypothesis.supportingEvidence();
            List<String> contradicting = hypothesis.contradictingEvidence();
            noBlank(supporting, "hypothesis ", id, " supportingEvidence");
            noBlank(contradicting, "hypothesis ", id, " contradictingEvidence");
            if (!Collections.disjoint(supporting, contradicting)) {
                throw input(InputCode.BLOCKER_DIAGNOSIS_EVIDENCE_BOTH_WAYS, "hypothesisId", id);
            }
            for (String ref : supporting) {
                if (!inventory.contains(ref)) {
                    throw input(InputCode.BLOCKER_DIAGNOSIS_EVIDENCE_OUTSIDE_INVENTORY, "hypothesisId", id, "ref", ref);
                }
            }
            for (String ref : contradicting) {
                if (!inventory.contains(ref)) {
                    throw input(InputCode.BLOCKER_DIAGNOSIS_EVIDENCE_OUTSIDE_INVENTORY, "hypothesisId", id, "ref", ref);
                }
            }
            if (hypothesis.state() == State.CONFIRMED) {
                if (supporting.isEmpty() || !contradicting.isEmpty()) {
                    throw input(InputCode.BLOCKER_DIAGNOSIS_CONFIRMED_NEEDS_SUPPORT, "hypothesisId", id);
                }
                causeAnalysis(hypothesis, inventory);
                confirmed++;
            }
        }
        if (confirmed > 1) {
            throw input(InputCode.BLOCKER_DIAGNOSIS_ONLY_ONE_CONFIRMED);
        }
        Set<EvidenceBinding> bindingKeys = new HashSet<>();
        Set<Pair> pairs = new HashSet<>();
        Map<String, String> evidenceDigests = new HashMap<>();
        for (EvidenceBinding binding : request.evidenceBindings()) {
            String evidenceRef = binding.evidenceRef();
            if (!inventory.contains(evidenceRef)) {
                throw input(InputCode.BLOCKER_DIAGNOSIS_BINDING_OUTSIDE_INVENTORY, "evidenceRef", evidenceRef);
            }
            for (String id : binding.hypothesisIds()) {
                if (!hypotheses.containsKey(id)) {
                    throw input(InputCode.BLOCKER_DIAGNOSIS_BINDING_UNKNOWN_HYPOTHESIS, "id", id);
                }
            }
            String previous = evidenceDigests.put(evidenceRef, binding.artifactDigest());
            if (previous != null && !Objects.equals(previous, binding.artifactDigest())) {
                throw input(InputCode.BLOCKER_DIAGNOSIS_CONFLICTING_DIGESTS, "evidenceRef", evidenceRef);
            }
            // Two bindings that differ only in the order of their hypotheses are the same binding.
            if (!bindingKeys.add(new EvidenceBinding(evidenceRef, binding.artifactDigest(), binding.hypothesisIds().stream().sorted().toList(), binding.relation()))) {
                throw input(InputCode.BLOCKER_DIAGNOSIS_DUPLICATE_BINDING, "evidenceRef", evidenceRef);
            }
            Relation relation = binding.relation();
            for (String id : binding.hypothesisIds()) {
                Hypothesis hypothesis = hypotheses.get(id);
                if (!pairs.add(new Pair(evidenceRef, id, relation))) {
                    throw input(InputCode.BLOCKER_DIAGNOSIS_DUPLICATE_RELATION_BINDING, "relation", relation.wire(), "evidenceRef", evidenceRef, "id", id);
                }
                if (hypothesis.state() == State.CONFIRMED && relation == Relation.REFUTES) {
                    throw input(InputCode.BLOCKER_DIAGNOSIS_CONFIRMED_WITH_REFUTING, "id", id);
                }
                List<String> reflected = switch (relation) {
                    case SUPPORTS -> hypothesis.supportingEvidence();
                    case REFUTES -> hypothesis.contradictingEvidence();
                };
                if (!reflected.contains(evidenceRef)) {
                    throw input(InputCode.BLOCKER_DIAGNOSIS_BINDING_NOT_REFLECTED, "relation", relation.wire(), "evidenceRef", evidenceRef, "id", id);
                }
            }
        }
        for (Hypothesis hypothesis : request.candidateHypotheses()) {
            String id = hypothesis.id();
            for (String ref : hypothesis.supportingEvidence()) {
                if (!pairs.contains(new Pair(ref, id, Relation.SUPPORTS))) {
                    throw input(InputCode.BLOCKER_DIAGNOSIS_SUPPORTING_NOT_BOUND, "evidenceRef", ref, "hypothesisId", id);
                }
            }
            for (String ref : hypothesis.contradictingEvidence()) {
                if (!pairs.contains(new Pair(ref, id, Relation.REFUTES))) {
                    throw input(InputCode.BLOCKER_DIAGNOSIS_CONTRADICTING_NOT_BOUND, "evidenceRef", ref, "hypothesisId", id);
                }
            }
        }
        Set<String> testIds = new HashSet<>();
        for (CandidateTest candidate : request.candidateTests()) {
            String checkId = candidate.checkId();
            filled(checkId, "candidate test checkId");
            if (!testIds.add(checkId)) {
                throw input(InputCode.BLOCKER_DIAGNOSIS_DUPLICATE_CANDIDATE_TEST, "checkId", checkId);
            }
            candidateContent(candidate, hypotheses.keySet());
            if (!Objects.equals(checkInputDigest(candidate), candidate.inputDigest())) {
                throw input(InputCode.BLOCKER_DIAGNOSIS_CANDIDATE_INPUT_DIGEST, "checkId", checkId);
            }
        }
        for (AttemptedCheck attempt : request.attemptedChecks()) {
            String checkId = attempt.checkId();
            filled(checkId, "attempted checkId");
            filled(attempt.resultDigest(), "attempted check ", checkId, " resultDigest");
            candidateContent(attempt.check(), hypotheses.keySet());
            if (!Objects.equals(checkId, attempt.check().checkId())) {
                throw input(InputCode.BLOCKER_DIAGNOSIS_ATTEMPTED_CHECK_MISMATCH, "checkId", checkId, "embeddedCheckId", attempt.check().checkId());
            }
            if (!Objects.equals(checkInputDigest(attempt.check()), attempt.inputDigest())) {
                throw input(InputCode.BLOCKER_DIAGNOSIS_ATTEMPTED_INPUT_DIGEST, "checkId", checkId);
            }
        }
    }

    private static void causeAnalysis(Hypothesis hypothesis, Set<String> inventory) {
        String id = hypothesis.id();
        CauseAnalysis analysis = hypothesis.causeAnalysis();
        if (analysis == null) {
            throw input(InputCode.BLOCKER_DIAGNOSIS_CAUSE_ANALYSIS_REQUIRED, "hypothesisId", id);
        }
        filled(analysis.symptom(), "confirmed hypothesis ", id, " causeAnalysis.symptom");
        filled(analysis.mechanism(), "confirmed hypothesis ", id, " causeAnalysis.mechanism");
        filled(analysis.rootCondition(), "confirmed hypothesis ", id, " causeAnalysis.rootCondition");
        causeObservation(hypothesis, "discriminatingEvidence", analysis.discriminatingEvidence(), inventory);
        causeObservation(hypothesis, "eliminationObservation", analysis.eliminationObservation(), inventory);
    }

    private static void causeObservation(Hypothesis hypothesis, String field, EvidenceStatement observation, Set<String> inventory) {
        String id = hypothesis.id();
        filled(observation.statement(), "confirmed hypothesis ", id, " causeAnalysis." + field + ".statement");
        noBlank(observation.evidenceRefs(), "confirmed hypothesis ", id, " causeAnalysis." + field + ".evidenceRefs");
        for (String ref : observation.evidenceRefs()) {
            if (!inventory.contains(ref) || !hypothesis.supportingEvidence().contains(ref)) {
                throw input(InputCode.BLOCKER_DIAGNOSIS_CAUSE_ANALYSIS_MUST_CITE, "hypothesisId", id, "field", field, "ref", ref);
            }
        }
    }

    private static void candidateContent(CandidateTest candidate, Set<String> hypothesisIds) {
        String checkId = candidate.checkId();
        filled(checkId, "candidate test checkId");
        filled(candidate.question(), "candidate ", checkId, " question");
        noBlank(candidate.preconditions(), "candidate ", checkId, " preconditions");
        noBlank(candidate.requiredAuthorization(), "candidate ", checkId, " requiredAuthorization");
        filled(candidate.stopCondition(), "candidate ", checkId, " stopCondition");
        Set<Implication> implications = new HashSet<>();
        for (Outcome outcome : candidate.outcomes()) {
            filled(outcome.observation(), "candidate ", checkId, " outcome observation");
            List<String> supports = outcome.supportsHypotheses();
            List<String> refutes = outcome.refutesHypotheses();
            noBlank(supports, "candidate ", checkId, " supportsHypotheses");
            noBlank(refutes, "candidate ", checkId, " refutesHypotheses");
            if (!Collections.disjoint(supports, refutes)) {
                throw input(InputCode.BLOCKER_DIAGNOSIS_CANDIDATE_OUTCOME_BOTH_WAYS, "checkId", checkId);
            }
            for (String id : supports) {
                if (!hypothesisIds.contains(id)) {
                    throw input(InputCode.BLOCKER_DIAGNOSIS_CANDIDATE_UNKNOWN_HYPOTHESIS, "checkId", checkId, "id", id);
                }
            }
            for (String id : refutes) {
                if (!hypothesisIds.contains(id)) {
                    throw input(InputCode.BLOCKER_DIAGNOSIS_CANDIDATE_UNKNOWN_HYPOTHESIS, "checkId", checkId, "id", id);
                }
            }
            implications.add(implication(outcome));
        }
        if (implications.size() < 2) {
            throw input(InputCode.BLOCKER_DIAGNOSIS_CANDIDATE_NOT_DISCRIMINATING, "checkId", checkId);
        }
    }

    private static Implication implication(Outcome outcome) {
        return new Implication(outcome.supportsHypotheses().stream().sorted().toList(), outcome.refutesHypotheses().stream().sorted().toList());
    }

    private List<Cluster> clusterFailures(Request request) {
        Map<String, Cluster> clusters = new LinkedHashMap<>();
        for (Episode episode : request.episodes()) {
            Signature signature = new Signature(episode.operation(), normalizedTuple(episode.stableFailureTuple()), episode.environmentDigest());
            String fingerprint = Checks.digest(mapper, signature);
            Cluster existing = clusters.get(fingerprint);
            if (existing != null) {
                existing.episodeIds().add(episode.attemptId());
            } else {
                clusters.put(fingerprint, new Cluster(fingerprint, new ArrayList<>(List.of(episode.attemptId())), signature.operation(), signature.stableFailureTuple(), signature.environmentDigest()));
            }
        }
        return List.copyOf(clusters.values());
    }

    private static Disposition disposition(CandidateTest candidate, Set<String> prohibited, Set<String> approval, Set<String> allowed) {
        String checkId = candidate.checkId();
        List<String> required = candidate.requiredAuthorization();
        if (prohibited.contains(checkId) || required.stream().anyMatch(prohibited::contains)) {
            return Disposition.PROHIBITED;
        }
        if (approval.contains(checkId) || required.stream().anyMatch(approval::contains)) {
            return Disposition.APPROVAL_REQUIRED;
        }
        // Task-scoped read-only checks do not need a second grant for their diagnostic ID.
        boolean granted = allowed.containsAll(required) && (candidate.risk() == Risk.READ_ONLY || allowed.contains(checkId));
        return granted ? Disposition.ALLOWED : Disposition.APPROVAL_REQUIRED;
    }

    /** The report a request alone determines; {@code requestDigest} is the digest of the request document as given. A validator re-derives it and compares. */
    private Report analyze(Request request, String requestDigest) {
        List<Cluster> clusters = clusterFailures(request);
        List<Observation> observations = new ArrayList<>(request.episodes().size());
        int number = 0;
        for (Episode episode : request.episodes()) {
            number++;
            observations.add(new Observation(String.format(Locale.ROOT, "OBS-%03d", number), "Attempt " + episode.attemptId() + " failed during " + episode.operation() + " in environment " + episode.environmentDigest() + ".",
                    episode.evidenceRefs()));
        }
        List<Hypothesis> hypotheses = request.candidateHypotheses();
        Hypothesis confirmed = null;
        for (Hypothesis hypothesis : hypotheses) {
            if (hypothesis.state() == State.CONFIRMED && !hypothesis.supportingEvidence().isEmpty()) {
                confirmed = hypothesis;
                break;
            }
        }
        if (!request.accessBlockers().isEmpty()) {
            return new Report(SCHEMA_VERSION, requestDigest, clusters, observations, hypotheses, null, null, "Provide access to the blocked evidence or tool before continuing diagnosis.",
                    request.accessBlockers(), Verdict.BLOCKED);
        }
        if (confirmed != null) {
            Set<String> supporting = Set.copyOf(confirmed.supportingEvidence());
            List<EvidenceBinding> bindings = new ArrayList<>();
            for (EvidenceBinding binding : request.evidenceBindings()) {
                if (binding.relation() == Relation.SUPPORTS && binding.hypothesisIds().contains(confirmed.id()) && supporting.contains(binding.evidenceRef())) {
                    bindings.add(binding);
                }
            }
            CauseAnalysis analysis = confirmed.causeAnalysis();
            Cause cause = new Cause(confirmed.id(), confirmed.id(), confirmed.statement(), analysis.symptom(), analysis.mechanism(), analysis.rootCondition(), analysis.discriminatingEvidence(),
                    analysis.eliminationObservation(), bindings);
            return new Report(SCHEMA_VERSION, requestDigest, clusters, observations, hypotheses, null, cause,
                    "Continue remediation within the original request, goal, or project authorization; obtain approval only for newly required actions.", List.of(), Verdict.CAUSE_CONFIRMED);
        }
        // A check whose digest was already attempted is not repeated; a validated request's digests are the digests of the contents.
        Set<Attempt> attempted = new HashSet<>();
        for (AttemptedCheck attempt : request.attemptedChecks()) {
            attempted.add(new Attempt(attempt.checkId(), attempt.inputDigest()));
        }
        Set<String> prohibited = Set.copyOf(request.authorization().prohibitedChecks());
        Set<String> approval = Set.copyOf(request.authorization().approvalRequired());
        Set<String> allowed = Set.copyOf(request.authorization().allowedChecks());
        List<String> limitations = new ArrayList<>();
        List<String> notRepeated = new ArrayList<>();
        List<CandidateTest> available = new ArrayList<>();
        List<CandidateTest> approvalNeeded = new ArrayList<>();
        for (CandidateTest candidate : request.candidateTests()) {
            if (attempted.contains(new Attempt(candidate.checkId(), candidate.inputDigest()))) {
                notRepeated.add(candidate.checkId());
                continue;
            }
            switch (disposition(candidate, prohibited, approval, allowed)) {
                case PROHIBITED -> limitations.add("Candidate " + candidate.checkId() + " is prohibited by the authorization boundary.");
                case APPROVAL_REQUIRED -> approvalNeeded.add(candidate);
                case ALLOWED -> available.add(candidate);
            }
        }
        if (!notRepeated.isEmpty()) {
            limitations.add("Already attempted without changed input: " + String.join(", ", notRepeated) + ".");
        }
        available.sort(RANKING);
        approvalNeeded.sort(RANKING);
        if (!available.isEmpty()) {
            CandidateTest selected = available.get(0);
            return new Report(SCHEMA_VERSION, requestDigest, clusters, observations, hypotheses, selected, null,
                    "Run " + selected.checkId() + " within the recorded authorization boundary and capture the observed branch.", limitations, Verdict.NEXT_TEST);
        }
        if (!approvalNeeded.isEmpty()) {
            CandidateTest selected = approvalNeeded.get(0);
            return new Report(SCHEMA_VERSION, requestDigest, clusters, observations, hypotheses, selected, null, "Obtain the listed authorization before running " + selected.checkId() + ".",
                    limitations, Verdict.NEEDS_APPROVAL);
        }
        return new Report(SCHEMA_VERSION, requestDigest, clusters, observations, hypotheses, null, null,
                "Provide a new discriminating candidate, changed input, or additional evidence; do not repeat an unchanged check.", limitations, Verdict.NEEDS_INPUT);
    }

    private Prepared prepare(JsonNode request) {
        try {
            return new Prepared(validated(request), CanonicalJson.digest(request), null);
        } catch (InvalidInputException failure) {
            // The refusal travels in Prepared and becomes a verdict error of the report check; this ends the handling here.
            LOG.debug("blocker-diagnostician: the request cannot be prepared: {}", failure.messageKey(), failure);
            return new Prepared(null, null, failure);
        }
    }

    public List<String> validateReport(JsonNode report, JsonNode request, String frozenDigest) {
        if (report == null || !report.isObject()) {
            return List.of(t("reportNotObject"));
        }
        List<String> errors = new ArrayList<>();
        List<SkillSchemas.Violation> violations = schemas.violations(REPORT, report);
        if (!violations.isEmpty()) {
            errors.add(t("reportSchemaFailed", "errors", mapper.writeValueAsString(violations)));
        }
        if (request == null) {
            errors.add(t("originalRequestRequired"));
        }
        if (!Checks.isDigest(frozenDigest)) {
            errors.add(t("frozenDigestRequired"));
        }
        // A report that breaks its contract is refused on that ground: its members cannot be judged one by one.
        Report bound = violations.isEmpty() ? schemas.bind(report, Report.class) : null;
        Prepared prepared = request == null ? null : prepare(request);
        if (prepared != null && frozenDigest != null) {
            if (prepared.failure() != null) {
                errors.add(t("requestCannotSubstantiate", "message", messages.forRequest(prepared.failure())));
            } else {
                if (!Objects.equals(prepared.digest(), frozenDigest)) {
                    errors.add(t("requestVsFrozenDigest"));
                }
                if (bound != null && !Objects.equals(frozenDigest, bound.requestArtifactDigest())) {
                    errors.add(t("reportVsFrozenDigest"));
                }
            }
        }
        if (bound == null) {
            return errors;
        }
        Verdict verdict = bound.verdict();
        if (verdict == Verdict.CAUSE_CONFIRMED) {
            confirmedCause(bound, prepared, errors);
        }
        if (verdict == Verdict.NEXT_TEST || verdict == Verdict.NEEDS_APPROVAL) {
            nextTest(bound, prepared, errors);
        }
        if (prepared != null) {
            if (prepared.failure() != null) {
                errors.add(t("requestCannotSubstantiate", "message", messages.forRequest(prepared.failure())));
            } else {
                compareWithDerived(bound, prepared, errors);
            }
        }
        return errors;
    }

    private void confirmedCause(Report report, Prepared prepared, List<String> errors) {
        Cause cause = report.confirmedCause();
        List<EvidenceBinding> bindings = cause == null ? List.of() : cause.evidenceBindings();
        if (bindings.isEmpty()) {
            errors.add(t("causeConfirmedNeedsEvidence"));
        }
        String hypothesisId = cause == null ? null : cause.hypothesisId();
        Hypothesis hypothesis = null;
        for (Hypothesis item : report.hypotheses()) {
            if (Objects.equals(item.id(), hypothesisId)) {
                hypothesis = item;
                break;
            }
        }
        if (hypothesis == null || hypothesis.state() != State.CONFIRMED || !hypothesis.contradictingEvidence().isEmpty()) {
            errors.add(t("confirmedCauseHypothesis"));
        }
        if (cause == null || !Objects.equals(cause.causeId(), hypothesisId)) {
            errors.add(t("causeIdEqualsHypothesisId"));
        }
        if (prepared != null && prepared.request() != null) {
            Set<EvidenceBinding> requested = Set.copyOf(prepared.request().evidenceBindings());
            for (EvidenceBinding binding : bindings) {
                if (!requested.contains(binding) || binding.relation() != Relation.SUPPORTS || !binding.hypothesisIds().contains(hypothesisId)) {
                    errors.add(t("confirmedEvidenceNotBound", "evidenceRef", binding.evidenceRef(), "hypothesisId", hypothesisId));
                }
            }
        }
    }

    private void nextTest(Report report, Prepared prepared, List<String> errors) {
        CandidateTest candidate = report.nextDiscriminatingTest();
        if (candidate == null) {
            errors.add(t("verdictNeedsNextTest", "verdict", report.verdict().name()));
            return;
        }
        Set<Implication> implications = new HashSet<>();
        for (Outcome outcome : candidate.outcomes()) {
            implications.add(implication(outcome));
        }
        if (candidate.outcomes().size() < 2 || implications.size() < 2) {
            errors.add(t("nextTestOutcomes"));
        }
        if (candidate.stopCondition().isBlank()) {
            errors.add(t("nextTestStopCondition"));
        }
        if (prepared != null && prepared.request() != null) {
            // The request's attempts were validated: each one's input digest is the digest of its content.
            String digest = checkInputDigest(candidate);
            for (AttemptedCheck attempt : prepared.request().attemptedChecks()) {
                if (Objects.equals(attempt.checkId(), candidate.checkId()) && Objects.equals(attempt.inputDigest(), digest)) {
                    errors.add(t("nextTestRepeats"));
                    break;
                }
            }
        }
    }

    private void compareWithDerived(Report report, Prepared prepared, List<String> errors) {
        Request request = prepared.request();
        Report expected = analyze(request, prepared.digest());
        List<String> expectedIds = new ArrayList<>(request.episodes().size());
        for (Episode episode : request.episodes()) {
            expectedIds.add(episode.attemptId());
        }
        List<String> actualIds = new ArrayList<>();
        for (Cluster cluster : report.failureClusters()) {
            actualIds.addAll(cluster.episodeIds());
        }
        Collections.sort(expectedIds);
        Collections.sort(actualIds);
        if (!expectedIds.equals(actualIds)) {
            errors.add(t("clustersCoverage"));
        }
        if (!same(expected.failureClusters(), report.failureClusters())) {
            errors.add(t("clusterFingerprints"));
        }
        if (!same(expected.observations(), report.observations())) {
            errors.add(t("observationsMismatch"));
        }
        if (!same(expected.hypotheses(), report.hypotheses())) {
            errors.add(t("hypothesesMismatch"));
        }
        if (!same(expected.nextDiscriminatingTest(), report.nextDiscriminatingTest())) {
            errors.add(t("selectedTestMismatch"));
        }
        if (!same(expected.confirmedCause(), report.confirmedCause())) {
            errors.add(t("confirmedCauseMismatch"));
        }
        if (!same(expected.limitations(), report.limitations())) {
            errors.add(t("limitationsMismatch"));
        }
        if (!Objects.equals(expected.recommendedNextAction(), report.recommendedNextAction())) {
            errors.add(t("nextActionMismatch"));
        }
        if (expected.verdict() != report.verdict()) {
            errors.add(t("verdictMustBe", "verdict", expected.verdict().name()));
        }
    }

    /** The same document: equal as records, or, when a free-form member spells a number another way (1 and 1.0), equal in canonical form. */
    private boolean same(Object left, Object right) {
        if (Objects.equals(left, right)) {
            return true;
        }
        return left != null && right != null && Objects.equals(CanonicalJson.of(mapper.valueToTree(left)), CanonicalJson.of(mapper.valueToTree(right)));
    }
}
