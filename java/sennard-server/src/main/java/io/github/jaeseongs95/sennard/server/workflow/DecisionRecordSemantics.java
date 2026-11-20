// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

import io.github.jaeseongs95.sennard.server.workflow.DecisionTerms.Assurance;
import io.github.jaeseongs95.sennard.server.workflow.DecisionTerms.ConsensusStatus;
import io.github.jaeseongs95.sennard.server.workflow.DecisionTerms.CrossDecision;
import io.github.jaeseongs95.sennard.server.workflow.DecisionTerms.EvidenceStatus;
import io.github.jaeseongs95.sennard.server.workflow.DecisionTerms.IssueStatus;
import io.github.jaeseongs95.sennard.server.workflow.DecisionTerms.Observability;
import io.github.jaeseongs95.sennard.server.workflow.DecisionTerms.PanelPhase;
import io.github.jaeseongs95.sennard.server.workflow.DecisionTerms.Stage;
import io.github.jaeseongs95.sennard.server.workflow.DecisionTerms.WorkerClass;
import io.github.jaeseongs95.sennard.server.workflow.DecisionTerms.WorkerStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * The cross-field rules of a DecisionRecord.v1 that its JSON schema cannot state. The schema has already passed when this runs; the rules
 * are checked against the fixtures of this class's tests.
 */
final class DecisionRecordSemantics {
    private static final Set<WorkerClass> RE_DELIBERATION_ROLES = EnumSet.of(WorkerClass.REVIEWER, WorkerClass.ADAPTIVE_SPECIALIST);
    private static final Set<String> FORBIDDEN_KEYS = Set.of("chain_of_thought", "raw_reasoning", "internal_prompt", "system_prompt", "execution_directive", "tool_directive");
    private static final JsonNode EMPTY = JsonNodeFactory.instance.objectNode();

    /**
     * The limits that end the panel's repetition. They are policy, not the model's judgment: a panel that keeps asking or deliberating again is
     * more likely heading the wrong way than about to converge, so reaching one tells the model to re-examine the question and the evidence and
     * to record what is left, never to try once more. The number of reviewers and of specialists is the model's judgment and has no such limit.
     */
    static final int MAX_REDELIBERATIONS = 1;
    static final int MAX_FOLLOWUPS_PER_REVIEWER = 1;
    private static final String TERMINATION_CAP = "termination cap reached: ";
    private static final String RE_EXAMINE = "; the current direction may be wrong, so re-examine the question definition and the evidence,"
            + " record the remaining issues and the evidence still needed, and finish without another round";

    private static String capReached(String limit) {
        return TERMINATION_CAP + limit + RE_EXAMINE;
    }

    /** Whether a problem {@link #validate} returned is a reached termination limit, which the gate answers with the re-examine guidance. */
    static boolean isTerminationCap(String problem) {
        return problem.startsWith(TERMINATION_CAP);
    }

    private final JsonNode record;
    private final JsonNode run;
    private final JsonNode assurance;
    private final Stage stage;
    private final Set<String> instantiated = new LinkedHashSet<>();
    private final Map<String, JsonNode> workerById = new LinkedHashMap<>();
    private final Set<String> missing = new LinkedHashSet<>();
    private final Set<String> reviewerIds = new LinkedHashSet<>();
    private final Set<String> completed = new LinkedHashSet<>();
    private final Set<String> reused = new LinkedHashSet<>();
    private final Set<String> declaredFailed = new LinkedHashSet<>();
    private final List<String> errors = new ArrayList<>();
    private final Map<String, Set<EvidenceStatus>> claimStatuses = new LinkedHashMap<>();

    private final JsonMapper mapper;

    private DecisionRecordSemantics(JsonNode record, JsonMapper mapper) {
        this.mapper = mapper;
        this.record = record;
        this.run = object(record.path("run"));
        this.assurance = run.path("assurance");
        this.stage = enumOf(run, "stage", Stage.class);
    }

    /**
     * The semantic problems of a decision record, none for a valid one. The record is the panel skill's own document, of no shape the server owns, and it
     * may be of any shape when this judges it, so it is read as the tree it is and every problem becomes a line of the list instead of a failure to bind.
     */
    static List<String> validate(JsonNode record, JsonMapper mapper) {
        return new DecisionRecordSemantics(record, mapper).check();
    }

    private static JsonNode object(JsonNode value) {
        return value != null && value.isObject() ? value : EMPTY;
    }

    private static List<JsonNode> array(JsonNode value) {
        List<JsonNode> items = new ArrayList<>();
        if (value != null && value.isArray()) {
            value.forEach(items::add);
        }
        return items;
    }

    private static List<JsonNode> objects(JsonNode value) {
        return array(value).stream().map(DecisionRecordSemantics::object).toList();
    }

    private static List<String> strings(JsonNode value) {
        return array(value).stream().filter(JsonNode::isString).map(JsonNode::asString).toList();
    }

    private static boolean nonempty(JsonNode value) {
        return value != null && value.isString() && !value.asString().isBlank();
    }

    private static boolean sameValues(Collection<String> left, Collection<String> right) {
        return new HashSet<>(left).equals(new HashSet<>(right));
    }

    private static boolean duplicateFree(List<String> values) {
        return values.size() == new HashSet<>(values).size();
    }

    private static String text(JsonNode object, String key) {
        JsonNode value = object.path(key);
        return value.isString() ? value.asString() : null;
    }

    private static boolean yes(JsonNode object, String key) {
        return object.path(key).isBoolean() && object.get(key).booleanValue();
    }

    /** True when the field names the given constant; a missing field, another type or a name that is none of the set is not it. */
    private <E extends Enum<E>> boolean is(JsonNode object, String key, E expected) {
        return enumOf(object, key, expected.getDeclaringClass()) == expected;
    }

    /** The constant the field names, or null when it is missing, not text or names none. */
    private <E extends Enum<E>> E enumOf(JsonNode object, String key, Class<E> type) {
        return enumValue(object.path(key), type);
    }

    /** The constant a value names in the form Jackson writes it, or null when the value is not text or names none. */
    private <E extends Enum<E>> E enumValue(JsonNode value, Class<E> type) {
        if (value == null || !value.isString() || value.asString().isEmpty()) {
            return null;
        }
        return mapper.readerFor(type).with(EnumFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL).readValue(value);
    }

    private void error(String message) {
        errors.add(message);
    }

    private void errorIf(boolean condition, String message) {
        if (condition) {
            errors.add(message);
        }
    }

    private boolean eligibleReviewer(String id) {
        JsonNode worker = id == null ? null : workerById.get(id);
        if (worker == null) {
            return false;
        }
        Set<PanelPhase> participated = phases(worker);
        return is(worker, "classification", WorkerClass.REVIEWER) && !yes(worker, "is_judge") && yes(worker, "instantiated") && is(worker, "status", WorkerStatus.COMPLETED) && yes(worker, "blind_round1")
                && yes(worker, "context_isolated") && participated.contains(PanelPhase.ROUND1) && !participated.contains(PanelPhase.FINAL_JUDGE) && !participated.contains(PanelPhase.ADAPTIVE_SPECIALIST);
    }

    private boolean eligibleJudge(String id) {
        JsonNode worker = id == null ? null : workerById.get(id);
        if (worker == null) {
            return false;
        }
        return is(worker, "classification", WorkerClass.JUDGE) && yes(worker, "is_judge") && yes(worker, "instantiated") && is(worker, "status", WorkerStatus.COMPLETED) && !yes(worker, "blind_round1") && worker.path("blind_round1").isBoolean()
                && yes(worker, "context_isolated") && phases(worker).equals(EnumSet.of(PanelPhase.FINAL_JUDGE)) && array(worker.path("participated_stages")).size() == 1;
    }

    /** The phases a worker took part in; a name that is none of the set is left out (the schema refuses one before this runs). */
    private Set<PanelPhase> phases(JsonNode worker) {
        Set<PanelPhase> phases = EnumSet.noneOf(PanelPhase.class);
        for (JsonNode item : array(worker.path("participated_stages"))) {
            PanelPhase phase = enumValue(item, PanelPhase.class);
            if (phase != null) {
                phases.add(phase);
            }
        }
        return phases;
    }

    private List<String> check() {
        preflight();
        errorIf(!object(record.path("case_brief")).path("constraints").equals(record.path("constraints")), "case_brief constraints must match record constraints");
        assuranceRules();
        workers();
        boolean strictShortfall = yes(run, "strict") && yes(run, "capability_shortfall");
        JsonNode judgeId = run.path("fresh_judge_id");
        JsonNode fallback = run.path("judge_fallback");
        judges(judgeId, fallback, strictShortfall);
        if (strictShortfall) {
            shortfallContract(judgeId, fallback);
            return errors;
        }
        panel(judgeId);
        Set<String> declaredSpecialists = specialists();
        List<JsonNode> redeliberations = redeliberations();
        claims();
        scanForbidden(record);
        Set<String> constraints = new LinkedHashSet<>(strings(record.path("constraints")));
        Set<String> requiredConstraints = new LinkedHashSet<>(strings(record.path("required_constraints")));
        errorIf(requiredConstraints.stream().anyMatch(c -> !constraints.contains(c)), "required constraints must be declared constraints");
        List<JsonNode> issues = objects(record.path("issue_ledger"));
        for (JsonNode issue : issues) {
            errorIf(enumOf(issue, "status", IssueStatus.class) == null, "issue ledger has invalid status");
        }
        observability();
        crossExamination();
        Set<String> axisNames = axes();
        Set<String> validScope = new HashSet<>(claimStatuses.keySet());
        issues.forEach(issue -> validScope.add(String.valueOf(text(issue, "issue_id"))));
        validScope.addAll(axisNames);
        for (JsonNode redeliberation : redeliberations) {
            errorIf(strings(redeliberation.path("impacted_scope")).stream().anyMatch(id -> !validScope.contains(id)), "re-deliberation scope must reference existing claims, issues, or axes");
        }
        boolean independent = is(run, "assurance", Assurance.INDEPENDENT);
        errorIf((!declaredSpecialists.isEmpty() || !redeliberations.isEmpty()) && independent, "material adaptive work cannot claim independent assurance");
        errorIf(independent && (!missing.isEmpty() || !array(run.path("failures")).isEmpty() || !reused.isEmpty()), "independent assurance requires no missing capability, failures, or reuse");
        consensus(constraints, requiredConstraints, issues, axisNames);
        return errors;
    }

    private void preflight() {
        JsonNode preflight = object(record.path("preflight"));
        List<String> required = strings(preflight.path("required_capabilities"));
        List<String> observed = strings(preflight.path("observed_capabilities"));
        List<String> declaredMissing = strings(preflight.path("missing_capabilities"));
        Predicate<String> blank = String::isBlank;
        errorIf(!duplicateFree(required) || !duplicateFree(observed) || !duplicateFree(declaredMissing) || required.stream().anyMatch(blank) || observed.stream().anyMatch(blank) || declaredMissing.stream().anyMatch(blank),
                "preflight capabilities must be unique nonempty strings");
        Set<String> expected = new LinkedHashSet<>(required);
        expected.removeAll(new HashSet<>(observed));
        missing.addAll(declaredMissing);
        errorIf(!expected.equals(missing), "preflight missing capabilities must exactly equal required minus observed");
    }

    private void assuranceRules() {
        Assurance value = enumOf(run, "assurance", Assurance.class);
        if (value == null) {
            error("run.assurance must be independent, partially_independent, single_agent, or provisional");
        }
        boolean strictShortfall = yes(run, "strict") && yes(run, "capability_shortfall");
        if (!strictShortfall) {
            JsonNode fallback = run.path("judge_fallback");
            boolean fallbackRecorded = !fallback.isNull() && !fallback.isMissingNode();
            if (stage == Stage.LOW && !(fallbackRecorded ? EnumSet.of(Assurance.SINGLE_AGENT, Assurance.PROVISIONAL) : EnumSet.of(Assurance.SINGLE_AGENT)).contains(value)) {
                error("LOW must use single_agent assurance unless a fallback is recorded");
            }
            if (stage == Stage.MEDIUM && !Assurance.PANEL.contains(value)) {
                error("MEDIUM assurance is invalid");
            }
            if (stage != null && stage.isHighOrCritical() && !Assurance.PANEL.contains(value)) {
                error("HIGH/CRITICAL assurance is invalid");
            }
        }
        errorIf(!run.path("capability_shortfall").isBoolean() || run.get("capability_shortfall").booleanValue() != !missing.isEmpty(), "run capability_shortfall must match preflight missing capabilities");
    }

    private void workers() {
        for (JsonNode worker : objects(run.path("workers"))) {
            JsonNode id = worker.path("id");
            if (!nonempty(id) || workerById.containsKey(id.asString())) {
                error("worker ids must be unique nonempty strings");
            } else {
                workerById.put(id.asString(), worker);
            }
        }
        errorIf(!array(record.path("panel_manifest")).equals(array(run.path("workers"))), "panel_manifest must exactly equal run.workers");
        workerById.forEach((id, worker) -> {
            if (yes(worker, "instantiated")) {
                instantiated.add(id);
            }
        });
        JsonNode cap = run.path("worker_cap");
        boolean validCap = cap.isNumber() && cap.decimalValue().stripTrailingZeros().scale() <= 0 && cap.asLong() >= 0 && instantiated.size() <= cap.asLong();
        errorIf(!validCap, "worker cap exceeded or invalid");

        List<JsonNode> failures = objects(run.path("failures"));
        List<String> failureIds = failures.stream().map(failure -> text(failure, "worker_id")).filter(java.util.Objects::nonNull).toList();
        workerById.forEach((id, worker) -> {
            if (is(worker, "status", WorkerStatus.FAILED)) {
                declaredFailed.add(id);
            }
        });
        errorIf(!duplicateFree(failureIds) || !sameValues(failureIds, declaredFailed) || failures.stream().anyMatch(failure -> !nonempty(failure.path("reason"))),
                "run.failures must exactly identify failed workers with reasons");

        List<String> completedIds = strings(run.path("completed_worker_ids"));
        List<String> reusedIds = strings(run.path("reused_worker_ids"));
        completed.addAll(completedIds);
        reused.addAll(reusedIds);
        errorIf(!duplicateFree(completedIds) || !duplicateFree(reusedIds) || completed.stream().anyMatch(reused::contains), "completed and reused worker identities must be unique and distinct");
        workerById.forEach((id, worker) -> {
            WorkerStatus status = enumOf(worker, "status", WorkerStatus.class);
            errorIf(status == WorkerStatus.COMPLETED && !completed.contains(id), "completed worker missing from completed ids");
            errorIf(status == WorkerStatus.REUSED && !reused.contains(id), "reused worker missing from reused ids");
            errorIf(status != null && WorkerStatus.LIFECYCLE.contains(status) && !yes(worker, "instantiated"), "lifecycle workers must be instantiated");
        });
        List<String> lifecycle = new ArrayList<>(completed);
        lifecycle.addAll(reused);
        for (String id : lifecycle) {
            JsonNode worker = workerById.get(id);
            errorIf(worker == null || (completed.contains(id) && !is(worker, "status", WorkerStatus.COMPLETED)) || (reused.contains(id) && !is(worker, "status", WorkerStatus.REUSED)),
                    "completed/reused ids must match worker lifecycle status");
        }
        workerById.keySet().stream().filter(this::eligibleReviewer).forEach(reviewerIds::add);
    }

    private void judges(JsonNode judgeId, JsonNode fallback, boolean strictShortfall) {
        boolean high = stage == Stage.HIGH;
        boolean critical = stage == Stage.CRITICAL;
        String judge = judgeId.isString() ? judgeId.asString() : null;
        if ((high || critical) && !strictShortfall) {
            errorIf(fallback.isNull() && (!nonempty(judgeId) || !completed.contains(judge) || !eligibleJudge(judge)), "HIGH/CRITICAL requires a completed fresh Judge");
            errorIf(judge != null && (reviewerIds.contains(judge) || reused.contains(judge)), "Judge must be fresh and not reused");
            boolean strictOrIndependent = yes(run, "strict") || is(run, "assurance", Assurance.INDEPENDENT);
            errorIf(high && strictOrIndependent && !yes(run, "capability_shortfall") && reviewerIds.isEmpty(), "HIGH independent review requires an eligible completed reviewer");
            if (critical) {
                errorIf(strictOrIndependent && !yes(run, "capability_shortfall") && reviewerIds.isEmpty(), "CRITICAL independent review requires an eligible completed reviewer");
                String roles = String.join(" ", reviewerIds.stream().map(id -> String.valueOf(text(workerById.get(id), "role")).toLowerCase(Locale.ROOT)).toList());
                errorIf(!roles.contains("red team") && !roles.contains("independent design-assurance"), "CRITICAL requires a red-team or independent design-assurance reviewer");
            }
            errorIf(is(run, "assurance", Assurance.PARTIALLY_INDEPENDENT) && reviewerIds.isEmpty(), "partially_independent HIGH/CRITICAL requires an eligible reviewer");
        } else if (stage == Stage.LOW && !judgeId.isNull()) {
            error("LOW must not claim a fresh Judge");
        } else if (stage == Stage.MEDIUM && !judgeId.isNull() && (!nonempty(judgeId) || !completed.contains(judge) || reused.contains(judge) || !eligibleJudge(judge))) {
            error("MEDIUM fresh Judge must be completed, isolated, and unreused");
        }
        if (!fallback.isNull()) {
            JsonNode fallbackObject = object(fallback);
            errorIf(!yes(fallbackObject, "provisional") || !nonempty(fallbackObject.path("reason")) || !judgeId.isNull(), "Judge fallback must be provisional, explained, and exclusive of a fresh Judge");
            errorIf((high || critical) && !is(run, "assurance", Assurance.PROVISIONAL), "HIGH/CRITICAL Judge fallback must use provisional assurance");
            errorIf(yes(run, "strict"), "strict execution cannot use a Judge fallback");
        }
    }

    private void shortfallContract(JsonNode judgeId, JsonNode fallback) {
        JsonNode cross = object(record.path("cross_examination"));
        boolean inconsistent = !is(run, "assurance", Assurance.PROVISIONAL) || !record.path("consensus_proposal").isNull() || !judgeId.isNull() || !fallback.isNull() || !instantiated.isEmpty() || missing.isEmpty()
                || List.of("workers", "completed_worker_ids", "reused_worker_ids", "failures", "specialist_additions", "redeliberations").stream().anyMatch(field -> !array(run.path(field)).isEmpty())
                || List.of("panel_manifest", "material_claims", "issue_ledger", "axis_decisions").stream().anyMatch(field -> !array(record.path(field)).isEmpty())
                || !is(cross, "decision", CrossDecision.SKIP) || !nonempty(cross.path("reason"))
                || List.of("trigger_items", "selected_item_ids", "coverage", "followups").stream().anyMatch(field -> !array(cross.path(field)).isEmpty());
        errorIf(inconsistent, "strict capability shortfall contract is inconsistent");
    }

    private void panel(JsonNode judgeId) {
        errorIf(stage == Stage.LOW && !workerById.isEmpty(), "LOW must not create panel workers");
        if (stage == Stage.MEDIUM) {
            errorIf(reviewerIds.isEmpty(), "MEDIUM requires an eligible completed reviewer");
            errorIf(is(run, "assurance", Assurance.INDEPENDENT) && judgeId.isNull(), "MEDIUM independent assurance requires a fresh Judge");
        }
    }

    private Set<String> specialists() {
        List<JsonNode> specialists = objects(run.path("specialist_additions"));
        List<String> specialistIds = new ArrayList<>();
        for (JsonNode specialist : specialists) {
            String workerId = text(specialist, "worker_id");
            if (workerId != null) {
                specialistIds.add(workerId);
            }
            JsonNode worker = workerId == null ? null : workerById.get(workerId);
            JsonNode admission = object(specialist.path("admission"));
            boolean invalid = !nonempty(specialist.path("admission_reason")) || !nonempty(specialist.path("reason")) || !is(specialist, "classification", WorkerClass.ADAPTIVE_SPECIALIST) || worker == null
                    || !is(worker, "classification", WorkerClass.ADAPTIVE_SPECIALIST) || !is(worker, "status", WorkerStatus.COMPLETED) || !yes(worker, "instantiated") || yes(worker, "is_judge")
                    || !worker.path("blind_round1").isBoolean() || yes(worker, "blind_round1") || !yes(worker, "context_isolated") || !phases(worker).contains(PanelPhase.ADAPTIVE_SPECIALIST)
                    || !yes(admission, "material_gap") || !yes(admission, "distinct_capability") || !yes(admission, "verdict_change_possible") || !yes(admission, "cap_available");
            errorIf(invalid, "specialist admission contract is invalid");
        }
        Set<String> declared = new LinkedHashSet<>();
        workerById.forEach((id, worker) -> {
            if (is(worker, "classification", WorkerClass.ADAPTIVE_SPECIALIST) && yes(worker, "instantiated") && is(worker, "status", WorkerStatus.COMPLETED)) {
                declared.add(id);
            }
        });
        errorIf(!duplicateFree(specialistIds) || !sameValues(specialistIds, declared), "specialist additions must identify every completed specialist");
        return declared;
    }

    private List<JsonNode> redeliberations() {
        List<JsonNode> redeliberations = objects(run.path("redeliberations"));
        errorIf(redeliberations.size() > MAX_REDELIBERATIONS, capReached("at most one re-deliberation is allowed"));
        for (JsonNode item : redeliberations) {
            List<String> scope = strings(item.path("impacted_scope"));
            List<String> participants = strings(item.path("participant_worker_ids"));
            errorIf(scope.isEmpty() || participants.isEmpty() || !duplicateFree(scope) || !duplicateFree(participants) || !yes(item, "non_independent"), "re-deliberation requires unique scope, participants, and non_independent=true");
            for (String id : participants) {
                JsonNode worker = workerById.get(id);
                errorIf(worker == null || !yes(worker, "instantiated") || !is(worker, "status", WorkerStatus.COMPLETED) || !RE_DELIBERATION_ROLES.contains(enumOf(worker, "classification", WorkerClass.class)),
                        "re-deliberation participants must be completed existing reviewers or specialists");
            }
        }
        return redeliberations;
    }

    private void claims() {
        for (JsonNode claim : objects(record.path("material_claims"))) {
            String id = text(claim, "id");
            if (!nonempty(claim.path("id")) || claimStatuses.containsKey(id)) {
                error("material claim ids must be unique nonempty strings");
                continue;
            }
            List<JsonNode> provenance = objects(claim.path("provenance"));
            errorIf(provenance.isEmpty(), "material claims require provenance");
            Set<EvidenceStatus> statuses = EnumSet.noneOf(EvidenceStatus.class);
            for (JsonNode source : provenance) {
                EvidenceStatus status = enumOf(source, "verification_status", EvidenceStatus.class);
                if (status == null) {
                    error("material claim has invalid verification status");
                } else {
                    statuses.add(status);
                }
                errorIf(status == EvidenceStatus.VERIFIED && (!nonempty(source.path("locator")) || !nonempty(source.path("verification_note"))), "verified provenance requires a locator and verification note");
            }
            claimStatuses.put(id, statuses);
        }
    }

    private void scanForbidden(JsonNode value) {
        if (value.isArray()) {
            value.forEach(this::scanForbidden);
        } else if (value.isObject()) {
            value.properties().forEach(entry -> {
                if (FORBIDDEN_KEYS.contains(entry.getKey().toLowerCase(Locale.ROOT))) {
                    error("DecisionRecord must not retain raw reasoning, prompts, or directives");
                }
                scanForbidden(entry.getValue());
            });
        }
    }

    private void observability() {
        JsonNode observability = object(record.path("observability"));
        observability.values().forEach(value -> errorIf(value.isString() && enumValue(value, Observability.class) == null, "observability strings must be NOT_OBSERVABLE"));
        JsonNode count = observability.path("worker_count");
        errorIf(count.isNumber() && count.asInt() != instantiated.size(), "observability worker_count must match instantiated workers");
    }

    private void crossExamination() {
        JsonNode cross = object(record.path("cross_examination"));
        List<JsonNode> triggers = objects(cross.path("trigger_items"));
        List<String> selected = strings(cross.path("selected_item_ids"));
        List<JsonNode> coverage = objects(cross.path("coverage"));
        List<JsonNode> followups = objects(cross.path("followups"));
        errorIf(!nonempty(cross.path("reason")), "cross-examination requires a reason");
        Map<String, String> triggerOrigins = new LinkedHashMap<>();
        for (JsonNode trigger : triggers) {
            String id = text(trigger, "id");
            String origin = text(trigger, "origin_reviewer");
            if (!nonempty(trigger.path("id")) || triggerOrigins.containsKey(id) || !nonempty(trigger.path("origin_reviewer")) || !eligibleReviewer(origin)) {
                error("cross trigger ids and origins are invalid");
            } else {
                triggerOrigins.put(id, origin);
            }
        }
        errorIf(is(cross, "decision", CrossDecision.SKIP) && (!triggers.isEmpty() || !selected.isEmpty() || !coverage.isEmpty() || !followups.isEmpty()), "cross-examination skip requires empty details");
        if (is(cross, "decision", CrossDecision.RUN)) {
            errorIf(triggers.isEmpty() || coverage.isEmpty() || followups.isEmpty() || !duplicateFree(selected) || !sameValues(selected, triggerOrigins.keySet()), "cross-examination run must select every trigger exactly once");
            List<String> coverageIds = coverage.stream().map(entry -> String.valueOf(text(entry, "item_id"))).toList();
            errorIf(!duplicateFree(coverageIds) || !sameValues(coverageIds, selected), "cross coverage must identify every selected item");
            for (JsonNode entry : coverage) {
                String itemId = String.valueOf(text(entry, "item_id"));
                List<String> reviewers = strings(entry.path("reviewer_ids"));
                errorIf(!duplicateFree(reviewers) || reviewers.stream().noneMatch(id -> !Objects.equals(id, triggerOrigins.get(itemId))) || reviewers.stream().anyMatch(id -> !eligibleReviewer(id)), "cross coverage requires eligible non-origin reviewers");
                // Every (reviewer, item) pair in coverage needs that reviewer's own follow-up for the item.
                errorIf(!reviewers.stream().allMatch(reviewer -> followups.stream().anyMatch(followup -> Objects.equals(reviewer, String.valueOf(text(followup, "reviewer_id"))) && strings(followup.path("item_ids")).contains(itemId))),
                        "cross coverage must have a matching follow-up");
            }
            Map<String, List<String>> coverageByItem = new LinkedHashMap<>();
            coverage.forEach(entry -> coverageByItem.put(String.valueOf(text(entry, "item_id")), strings(entry.path("reviewer_ids"))));
            errorIf(followups.stream().anyMatch(followup -> strings(followup.path("item_ids")).stream().anyMatch(itemId -> !coverageByItem.getOrDefault(itemId, List.of()).contains(String.valueOf(text(followup, "reviewer_id"))))),
                    "cross follow-up must match its reviewer coverage");
        }
        Map<String, Integer> followupCounts = new LinkedHashMap<>();
        for (JsonNode followup : followups) {
            String reviewer = String.valueOf(text(followup, "reviewer_id"));
            followupCounts.merge(reviewer, 1, Integer::sum);
            List<String> items = strings(followup.path("item_ids"));
            errorIf(!eligibleReviewer(reviewer) || !duplicateFree(items) || items.stream().anyMatch(id -> !selected.contains(id)), "cross follow-up must use eligible reviewers and selected items");
        }
        errorIf(followupCounts.values().stream().anyMatch(count -> count > MAX_FOLLOWUPS_PER_REVIEWER), capReached("a reviewer may receive at most one cross follow-up"));
    }

    private Set<String> axes() {
        Set<String> names = new LinkedHashSet<>();
        for (JsonNode axis : objects(record.path("axis_decisions"))) {
            String name = text(axis, "axis");
            if (!nonempty(axis.path("axis")) || names.contains(name)) {
                error("axis decisions must have unique nonempty names");
            } else {
                names.add(name);
            }
            for (String claimId : strings(axis.path("evidence_claim_ids"))) {
                Set<EvidenceStatus> statuses = claimStatuses.get(claimId);
                errorIf(statuses == null || !statuses.equals(EnumSet.of(EvidenceStatus.VERIFIED)), "axis evidence must reference verified claims");
            }
        }
        return names;
    }

    private void consensus(Set<String> constraints, Set<String> requiredConstraints, List<JsonNode> issues, Set<String> axisNames) {
        JsonNode proposal = object(record.path("consensus_proposal"));
        ConsensusStatus status = enumOf(proposal, "status", ConsensusStatus.class);
        List<String> supported = strings(proposal.path("supported_by_verified_claims"));
        boolean consensus = status == ConsensusStatus.CONSENSUS;
        boolean conditional = status == ConsensusStatus.CONDITIONAL_CONSENSUS;
        errorIf((consensus || conditional) && (supported.isEmpty() || axisNames.isEmpty()), "consensus requires verified support and decision axes");
        for (String claimId : supported) {
            Set<EvidenceStatus> statuses = claimStatuses.get(claimId);
            errorIf(statuses == null || !statuses.equals(EnumSet.of(EvidenceStatus.VERIFIED)), "consensus links must reference verified claims only");
        }
        List<String> satisfied = strings(proposal.path("satisfied_constraints"));
        errorIf(satisfied.stream().anyMatch(c -> !constraints.contains(c)), "consensus references undeclared constraints");
        List<JsonNode> alignment = objects(proposal.path("axis_alignment"));
        List<String> alignedAxes = alignment.stream().map(entry -> String.valueOf(text(entry, "axis"))).toList();
        boolean noConsensus = status == ConsensusStatus.NO_CONSENSUS;
        errorIf(!noConsensus && (!duplicateFree(alignedAxes) || !sameValues(alignedAxes, axisNames)), "consensus must link every decision axis exactly once");
        errorIf(!noConsensus && alignment.stream().anyMatch(entry -> !entry.path("decision_ref").equals(entry.path("axis"))), "consensus decision_ref must match its axis");
        boolean materialDissent = objects(proposal.path("unresolved_dissent")).stream().anyMatch(item -> yes(item, "material"));
        if (consensus) {
            errorIf(!nonempty(proposal.path("action")) || !array(proposal.path("conditions")).isEmpty() || materialDissent, "unconditional consensus shape is invalid");
            errorIf(issues.stream().anyMatch(issue -> enumOf(issue, "status", IssueStatus.class) != null && enumOf(issue, "status", IssueStatus.class).blocksConsensus()), "unresolved issues prevent consensus");
            errorIf(!sameValues(satisfied, requiredConstraints), "consensus must satisfy every required constraint");
        } else if (conditional) {
            errorIf(!nonempty(proposal.path("action")) || array(proposal.path("conditions")).isEmpty() || !sameValues(satisfied, requiredConstraints), "conditional consensus shape is invalid");
        } else if (noConsensus) {
            errorIf(!proposal.path("action").isNull() || !nonempty(proposal.path("no_consensus_reason")) || array(proposal.path("remaining_options")).isEmpty() || !nonempty(proposal.path("decision_owner")), "no_consensus shape is invalid");
        }
        errorIf(!noConsensus && (!proposal.path("no_consensus_reason").isNull() || !proposal.path("decision_owner").isNull()), "only no_consensus may include a no-consensus reason or owner");
    }
}
