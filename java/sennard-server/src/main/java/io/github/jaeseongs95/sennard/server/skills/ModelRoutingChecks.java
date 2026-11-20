// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.skills;

import static io.github.jaeseongs95.sennard.server.skills.Checks.need;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;

import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.domain.error.InputCode;
import io.github.jaeseongs95.sennard.domain.error.InvalidInputException;
import io.github.jaeseongs95.sennard.server.contract.CanonicalJson;
import io.github.jaeseongs95.sennard.server.message.Messages;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * coordinate-subagents (resolve, record and advise). The delegating agent judges the model class and the reasoning depth an assignment needs; this
 * code only maps that judgment onto the models and effort labels the host lists, and keeps any combination the host supports selectable (a
 * user's model or effort, an inherited setting). The class and depth orders are the declaration orders of {@link ModelClass} and {@link EffortDepth};
 * the bundled routing data ({@code runtime/model-routing-presets.json}) must state the same orders (checked when the router is built). No model or effort label is named in this class or
 * in that data, and this class has no host-specific branch; the per-host values (field names, delivery) live only under the data's {@code providers}, the adapter data.
 * Effort labels are whatever strings the host lists, in the order it lists them.
 * <p>
 * Every call is named explicitly where the host takes a setting per call: a host that lists models gets an explicit model and, when the chosen model lists efforts, an effort label; a model
 * that lists no effort gets the model alone, because an argument the host does not take makes the call fail. Inheritance is chosen only when the host lists nothing it takes per call, when
 * the host or context forbids overrides ({@code inheritOnly}), or when no listed model can be judged; the plan then carries the reason.
 */
public final class ModelRoutingChecks {
    private static final String M = "skillTools.modelRouting.";
    private static final String REQUEST = "skills/coordinate-subagents/contracts/subagent-model-request.v1.schema.json";
    private static final String ADVICE = "skills/coordinate-subagents/contracts/subagent-model-advice.v1.schema.json";
    private static final String PRESETS = "runtime/model-routing-presets.json";
    private static final int INPUT_LIMIT = 1024 * 1024;
    /** A context argument that is a count of turns. */
    private static final Pattern POSITIVE_INTEGER = Pattern.compile("[1-9][0-9]*");

    private ModelRoutingChecks() {
    }

    /** The commands of the coordinate-subagents check, by the name a caller sends. */
    enum Command implements SkillCommand {
        RESOLVE("resolve"), RECORD("record"), ADVISE("advise");

        private final String wire;

        Command(String wire) {
            this.wire = wire;
        }

        @Override
        public String wire() {
            return wire;
        }
    }

    /** Model classes a delegating agent can judge a task to need, lightest first. */
    enum ModelClass {
        LIGHTWEIGHT("lightweight"), GENERAL("general"), DEEP("deep"), FRONTIER("frontier");

        private final String wire;

        ModelClass(String wire) {
            this.wire = wire;
        }

        @JsonValue
        public String wire() {
            return wire;
        }
    }

    /** Abstract reasoning depths a delegating agent can judge a task to need, shallowest first. The label a host uses for each is read from its own listing, never from here. */
    enum EffortDepth {
        SHALLOW("shallow"), STANDARD("standard"), DEEP("deep"), DEEPEST("deepest");

        /** The steps between the shallowest and the deepest depth: the span a host's effort labels are spread over. */
        static final int SPAN = values().length - 1;

        private final String wire;

        EffortDepth(String wire) {
            this.wire = wire;
        }

        @JsonValue
        public String wire() {
            return wire;
        }
    }

    enum Role {
        DISCOVERY("discovery"), GENERAL_IMPLEMENTATION("general-implementation"), INDEPENDENT_AUDIT("independent-audit");

        private final String wire;

        Role(String wire) {
            this.wire = wire;
        }

        @JsonValue
        public String wire() {
            return wire;
        }
    }

    enum Difficulty {
        @JsonProperty("routine") ROUTINE, @JsonProperty("substantial") SUBSTANTIAL, @JsonProperty("complex") COMPLEX, @JsonProperty("frontier") FRONTIER
    }

    enum RiskLevel {
        @JsonProperty("low") LOW, @JsonProperty("medium") MEDIUM, @JsonProperty("high") HIGH, @JsonProperty("critical") CRITICAL
    }

    enum Verification {
        @JsonProperty("direct") DIRECT, @JsonProperty("indirect") INDIRECT, @JsonProperty("limited") LIMITED
    }

    enum Delivery {
        @JsonProperty("explicit") EXPLICIT, @JsonProperty("inherited") INHERITED, @JsonProperty("blocked") BLOCKED
    }

    enum AdviceStatus {
        READY, INHERIT_UNVERIFIED, BLOCKED
    }

    enum ContextMode {
        @JsonProperty("limited") LIMITED, @JsonProperty("full-history") FULL_HISTORY
    }

    enum ObservationSource {
        @JsonProperty("spawn-result") SPAWN_RESULT, @JsonProperty("host-task-view") HOST_TASK_VIEW, @JsonProperty("tool-contract") TOOL_CONTRACT
    }

    enum ActualStatus {
        @JsonProperty("applied") APPLIED, @JsonProperty("inherited") INHERITED, @JsonProperty("unverified") UNVERIFIED
    }

    /** The routing data: the orders and roles a caller may not reorder, and the per-provider adapter values. A caller's presets may carry only some of it. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Presets(@JsonProperty("model_class_order") List<String> modelClassOrder, @JsonProperty("depth_order") List<String> depthOrder, List<String> roles,
            Map<String, ProviderPreset> providers) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ProviderPreset(Application application) {
    }

    /** How a provider takes a model, an effort and a context choice on a spawn. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Application(@JsonProperty("model_field") String modelField, @JsonProperty("effort_field") String effortField, @JsonProperty("context_field") String contextField,
            @JsonProperty("default_context") String defaultContext, @JsonProperty("full_history_value") String fullHistoryValue,
            @JsonProperty("limited_context_value") String limitedContextValue, @JsonProperty("full_history_overrides") Boolean fullHistoryOverrides) {
    }

    /** A model the host lists, its class (null when the host's model is of no known class) and the effort labels it accepts, shallowest first. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Listed(String model, @JsonProperty(value = "modelClass", required = true) ModelClass modelClass, List<String> efforts) {
    }

    /** A model and an effort label: what the host runs, or what it inherited. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Setting(String model, String effort) {
    }

    /** The delegating agent's own judgment: a class and a depth, never a model or a label. Any other member is refused. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Judgment(ModelClass modelClass, EffortDepth effortDepth) {
    }

    /** What the user named: a model, an effort label, or both. Any other member is refused. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record User(String model, String effort) {
    }

    /** What was asked for, as the answer states it: every member is written, null when it was not named. */
    record Requested(String model, ModelClass modelClass, EffortDepth effortDepth, String effort) {
    }

    /** The options a selection is made under; {@code presets} is read only to refuse it, a caller's presets travel as their own argument. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record RoutingOptions(String provider, List<Listed> supported, Setting inherited, Boolean inheritanceSupported, Boolean inheritOnly, Role role, Boolean highRisk, Judgment judgment,
            User user, @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) Object presets) {
    }

    record Plan(Requested requested, Setting selection, Delivery delivery, String fallbackReason) {
    }

    /** What actually ran, as the host's observation reports it; a member the host did not report is left out. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Observation(ObservationSource source, String reference, String model, String effort) {
    }

    /** The record input. {@code spawnArguments} are the host's own argument names, named by the provider's adapter data: a free-form map this check reads by those names and echoes. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record RecordInput(String assignmentId, String selectionReason, ContextMode contextMode, String contextReason, RoutingOptions options, Map<String, Object> spawnArguments,
            Observation observation, @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) Object presets) {
    }

    /** The model and effort as dispatched, in whatever form the spawn arguments held them. */
    record Dispatched(Object model, Object effort) {
    }

    record Actual(ActualStatus status, String model, String effort) {
    }

    record Recorded(String assignmentId, String selectionReason, ContextMode contextMode, String contextReason, Map<String, Object> spawnArguments, Requested requested,
            Dispatched dispatched, Observation observation, Actual actual, String fallbackReason) {
    }

    record Assignment(Role role, Difficulty difficulty, RiskLevel riskLevel, Verification verification, List<String> signals) {
    }

    record Host(String id, String provider, String capabilityReference, List<Listed> supported, Setting inherited, Boolean inheritanceSupported, Boolean inheritOnly) {
    }

    record AdviseRequest(String schemaVersion, Assignment assignment, Host host, Judgment judgment, User user) {
    }

    record CapabilitySource(String hostId, String reference) {
    }

    record Advice(String schemaVersion, AdviceStatus status, String authorityEffect, Assignment assignment, CapabilitySource capabilitySource, RoutingOptions routingOptions,
            Plan selectionPlan) {
    }

    /** The advice as the answer: the tree the advice contract was checked on is the tree that is written, so the advice is converted once. */
    record Advised(AdviceStatus status, JsonNode advice) {
    }

    /** The check of the {@code coordinate-subagents} skill. */
    @Component
    static final class CoordinateSubagentsCheck implements SkillCheck<Command> {
        private final ModelRouter router;

        CoordinateSubagentsCheck(ModelRouter router) {
            this.router = router;
        }

        @Override
        public String skill() {
            return "coordinate-subagents";
        }

        @Override
        public Class<Command> commandType() {
            return Command.class;
        }

        @Override
        public SkillCheckResult run(Command command, SkillArguments args) {
            Presets presets = args.has("presets") ? router.presetsFrom(args.document("presets")) : router.bundled();
            JsonNode input = args.document("input");
            return switch (command) {
                case ADVISE -> {
                    Checks.need(CanonicalJson.of(input).length() <= INPUT_LIMIT, InputCode.SKILL_CHECK_INPUT_TOO_LARGE);
                    Advised advised = router.advise(input, presets);
                    yield new SkillCheckResult(advised.advice(), advised.status() == AdviceStatus.BLOCKED ? 1 : 0);
                }
                case RESOLVE -> {
                    Plan plan = router.resolve(router.bind(input, RoutingOptions.class), presets);
                    yield new SkillCheckResult(plan, plan.delivery() == Delivery.BLOCKED ? 1 : 0);
                }
                case RECORD -> new SkillCheckResult(router.record(router.bind(input, RecordInput.class), presets), 0);
            };
        }
    }

    @Component
    static final class ModelRouter {
        private final SkillSchemas schemas;
        private final Messages messages;
        private final JsonMapper mapper;
        private final Presets bundled;

        ModelRouter(SkillSchemas schemas, Messages messages, JsonMapper mapper) {
            this.schemas = schemas;
            this.messages = messages;
            this.mapper = mapper;
            bundled = schemas.assets().read(PRESETS, Presets.class);
            if (!classNames().equals(bundled.modelClassOrder()) || !depthNames().equals(bundled.depthOrder())) {
                throw new IllegalStateException(PRESETS + " states a class or depth order other than the declaration order of ModelClass and EffortDepth");
            }
        }

        Presets bundled() {
            return bundled;
        }

        private static List<String> classNames() {
            return Arrays.stream(ModelClass.values()).map(ModelClass::wire).toList();
        }

        private static List<String> depthNames() {
            return Arrays.stream(EffortDepth.values()).map(EffortDepth::wire).toList();
        }

        /**
         * A document bound to its record. A member the record does not name is refused unless the record says it tolerates others: the options a caller sends have no JSON Schema
         * (the advice request has one, and is bound after it), so what a type refuses is stated by the type.
         */
        <T> T bind(JsonNode document, Class<T> type) {
            try {
                return mapper.readerFor(type).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(document);
            } catch (JacksonException malformed) {
                throw new InvalidInputException(InputCode.SKILL_CHECK_MALFORMED_ARGUMENT, Map.of(), Map.of(), malformed);
            }
        }

        Presets presetsFrom(JsonNode supplied) {
            check(supplied.isObject(), InputCode.MODEL_ROUTING_PRESETS_JSON_OBJECT);
            return bind(supplied, Presets.class);
        }

        private String t(String key) {
            return messages.forRequest(M + key, Map.of());
        }

        private static void check(boolean condition, InputCode code) {
            need(condition, code);
        }

        private static void assertImmutableOrder(Presets presets) {
            if (presets.modelClassOrder() != null) {
                check(classNames().equals(presets.modelClassOrder()), InputCode.MODEL_ROUTING_MODEL_CLASS_ORDER_IMMUTABLE);
            }
            if (presets.depthOrder() != null) {
                check(depthNames().equals(presets.depthOrder()), InputCode.MODEL_ROUTING_EFFORT_ORDER_IMMUTABLE);
            }
        }

        /**
         * The listed model nearest the wanted class (on a tie the higher class, then the host's order) that accepts the requested label. If no classified model qualifies,
         * the first compatible unclassified model in the host's list is used. A model with no effort labels can be picked when no label is requested.
         */
        private static Listed byClass(List<Listed> supported, ModelClass wanted, String label) {
            int target = wanted.ordinal();
            Listed best = null;
            Listed unclassified = null;
            int bestRank = -1;
            for (Listed item : supported) {
                if (label != null && !item.efforts().contains(label)) {
                    continue;
                }
                if (item.modelClass() == null) {
                    if (unclassified == null) {
                        unclassified = item;
                    }
                    continue;
                }
                int rank = item.modelClass().ordinal();
                int distance = Math.abs(rank - target);
                int bestDistance = Math.abs(bestRank - target);
                if (best == null || distance < bestDistance || (distance == bestDistance && rank > bestRank)) {
                    best = item;
                    bestRank = rank;
                }
            }
            return best == null ? unclassified : best;
        }

        /** A host lists a model's efforts from the shallowest to the deepest; a depth takes the label at the matching position, so the first listed label is the shallowest and the last the deepest. */
        private static String labelFor(Listed item, EffortDepth depth) {
            List<String> offered = item.efforts();
            if (offered.isEmpty() || depth == null) {
                return null;
            }
            return offered.get(Math.round((float) depth.ordinal() * (offered.size() - 1) / EffortDepth.SPAN));
        }

        /** An effort label is whatever non-empty string the host uses; this code knows none of them. */
        private static boolean isLabel(String value) {
            return value != null && !value.isBlank();
        }

        private static Requested asRequested(Setting setting) {
            return setting == null ? null : new Requested(setting.model(), null, null, setting.effort());
        }

        /**
         * A setting names a model, or only a class that the host's own listing resolves to a model; it names an effort label, or only a depth that the chosen model's listing resolves to a
         * label. When the chosen model does not list the effort that is wanted (it lists none, or not that label), {@code modelAlone} decides: a request to dispatch passes the model alone,
         * because an effort the host does not list makes the call fail; a setting that is only compared with what the host reported (an inherited combination, an observation) must match
         * exactly and so resolves to nothing.
         */
        private static Setting resolveSupported(Requested setting, List<Listed> supported, boolean modelAlone) {
            if (setting == null) {
                return null;
            }
            String label = isLabel(setting.effort()) ? setting.effort() : null;
            EffortDepth depth = setting.effortDepth();
            if (label == null && depth == null) {
                return null;
            }
            Listed capability = null;
            if (setting.model() != null) {
                for (Listed item : supported) {
                    if (Objects.equals(setting.model(), item.model())) {
                        capability = item;
                    }
                }
            } else if (setting.modelClass() != null) {
                capability = byClass(supported, setting.modelClass(), label);
                if (capability == null && label != null && modelAlone) {
                    capability = byClass(supported, setting.modelClass(), null);
                }
            }
            if (capability == null) {
                return null;
            }
            String effort = label != null ? (capability.efforts().contains(label) ? label : null) : labelFor(capability, depth);
            if (effort == null) {
                return modelAlone ? new Setting(capability.model(), null) : null;
            }
            return new Setting(capability.model(), effort);
        }

        private void validateOptions(RoutingOptions options, Presets presets) {
            assertImmutableOrder(presets);
            List<String> roles = presets.roles() != null ? presets.roles() : bundled.roles();
            check(options != null && options.provider() != null, InputCode.MODEL_ROUTING_PROVIDER_REQUIRED);
            check(options.presets() == null, InputCode.MODEL_ROUTING_PRESETS_NOT_OVERRIDABLE_OPTIONS);
            check(options.highRisk() != null, InputCode.MODEL_ROUTING_HIGH_RISK_EXPLICIT);
            check(options.supported() != null, InputCode.MODEL_ROUTING_SUPPORTED_FROM_HOST);
            Set<String> names = new LinkedHashSet<>();
            for (Listed item : options.supported()) {
                check(item != null && item.model() != null && names.add(item.model()), InputCode.MODEL_ROUTING_CAPABILITIES_UNIQUE);
                check(item.efforts() != null && item.efforts().stream().allMatch(ModelRouter::isLabel) && item.efforts().stream().distinct().count() == item.efforts().size(),
                        InputCode.MODEL_ROUTING_INVALID_SUPPORTED_EFFORTS);
            }
            check(options.role() != null && roles.contains(options.role().wire()), InputCode.MODEL_ROUTING_UNKNOWN_ROLE);
            check(options.role() != Role.INDEPENDENT_AUDIT || options.highRisk(), InputCode.MODEL_ROUTING_AUDIT_NEEDS_HIGH_RISK);
            if (options.judgment() != null) {
                check(options.judgment().modelClass() != null && options.judgment().effortDepth() != null, InputCode.MODEL_ROUTING_INVALID_JUDGMENT);
            }
            if (options.user() != null) {
                check(options.user().effort() == null || isLabel(options.user().effort()), InputCode.MODEL_ROUTING_INVALID_USER_EFFORT);
            }
            // A host that lists models takes a model per call, so the call names one: that needs the delegating agent's judgment (or a user who named both the model and the effort).
            // Without it the only answer left would be to inherit on a host that does not need to.
            boolean userNamedBoth = options.user() != null && options.user().model() != null && options.user().effort() != null;
            check(Boolean.TRUE.equals(options.inheritOnly()) || options.supported().isEmpty() || options.judgment() != null || userNamedBoth, InputCode.MODEL_ROUTING_INVALID_JUDGMENT);
        }

        private static Requested request(Requested baseline, User user) {
            if (baseline == null && user == null) {
                return null;
            }
            String model = user != null && user.model() != null ? user.model() : baseline == null ? null : baseline.model();
            String effort = user != null && user.effort() != null ? user.effort() : baseline == null ? null : baseline.effort();
            // A class is requested only while no model is named, and a depth only while no effort label is named; a judgment is a class and a depth, never a model or a label.
            return new Requested(model, model == null && baseline != null ? baseline.modelClass() : null, effort == null && baseline != null ? baseline.effortDepth() : null, effort);
        }

        private static ModelClass listedClass(List<Listed> supported, String model) {
            for (Listed item : supported) {
                if (Objects.equals(model, item.model())) {
                    return item.modelClass();
                }
            }
            return null;
        }

        Plan resolve(RoutingOptions options, Presets presets) {
            validateOptions(options, presets);
            // The delegating agent's own judgment of the class and depth the task needs, not a table: the role and the risk level only describe the task.
            Setting inherited = options.inherited();
            Judgment judgment = options.judgment();
            Requested requested = request(judgment != null ? new Requested(null, judgment.modelClass(), judgment.effortDepth(), null) : asRequested(inherited), options.user());
            boolean inheritOnly = Boolean.TRUE.equals(options.inheritOnly());
            if (!inheritOnly) {
                Setting selection = resolveSupported(requested, options.supported(), true);
                if (selection != null) {
                    List<String> reasons = new ArrayList<>();
                    if (requested.modelClass() != null && requested.modelClass() != listedClass(options.supported(), selection.model())) {
                        reasons.add(t("reasonCombinationUnavailable"));
                    }
                    if (selection.effort() == null) {
                        reasons.add(t("reasonEffortNotAccepted"));
                    }
                    return new Plan(requested, selection, Delivery.EXPLICIT, reasons.isEmpty() ? null : String.join("; ", reasons));
                }
            }
            // Past this point the call inherits (or is blocked): the plan says why. A host that lists no model is one that takes no setting per call; inheritOnly is a host or context that forbids one.
            String inheritReason = t(inheritOnly ? "reasonInheritanceRequired" : options.supported().isEmpty() ? "reasonHostListsNoModel" : "reasonCombinationUnavailable");
            Setting inheritedSelection = resolveSupported(asRequested(inherited), options.supported(), false);
            if (inheritedSelection != null) {
                return new Plan(requested, inheritedSelection, Delivery.INHERITED, inheritReason);
            }
            // A host or context that forbids overrides can only inherit, so it never blocks the delegation.
            if ((inheritOnly || Boolean.TRUE.equals(options.inheritanceSupported())) && inherited == null) {
                return new Plan(requested, null, Delivery.INHERITED, options.supported().isEmpty() || inheritOnly ? inheritReason : t("reasonSettingsNotObservable"));
            }
            return new Plan(requested, null, Delivery.BLOCKED, t("reasonNoSupportedConfiguration"));
        }

        private static Application applicationOf(Presets presets, String provider) {
            ProviderPreset preset = presets.providers() == null ? null : presets.providers().get(provider);
            return preset == null ? null : preset.application();
        }

        private static boolean filled(String value) {
            return value != null && !value.isEmpty();
        }

        Recorded record(RecordInput input, Presets presets) {
            check(filled(input.assignmentId()), InputCode.MODEL_ROUTING_ASSIGNMENT_ID_REQUIRED);
            check(filled(input.selectionReason()), InputCode.MODEL_ROUTING_SELECTION_REASON_REQUIRED);
            check(input.presets() == null, InputCode.MODEL_ROUTING_PRESETS_NOT_OVERRIDABLE_INPUT);
            RoutingOptions options = input.options();
            Plan plan = resolve(options, presets);
            Application application = applicationOf(presets, options.provider());
            check(application != null, InputCode.MODEL_ROUTING_ADAPTER_REQUIRED);
            Map<String, Object> spawn = input.spawnArguments();
            check(spawn != null, InputCode.MODEL_ROUTING_SPAWN_ARGUMENTS_REQUIRED);
            String contextField = application.contextField();
            ContextMode contextMode = input.contextMode();
            String context = null;
            if (contextField != null) {
                context = spawn.containsKey(contextField) ? (spawn.get(contextField) instanceof String given ? given : null) : application.defaultContext();
                String full = application.fullHistoryValue();
                String limited = application.limitedContextValue();
                check(context != null && (Objects.equals(context, full) || Objects.equals(context, limited) || POSITIVE_INTEGER.matcher(context).matches()), InputCode.MODEL_ROUTING_INVALID_CONTEXT_ARGUMENT);
                ContextMode derived = Objects.equals(context, full) ? ContextMode.FULL_HISTORY : ContextMode.LIMITED;
                check(contextMode == null || derived == contextMode, InputCode.MODEL_ROUTING_CONTEXT_MODE_CONTRADICTS);
                contextMode = derived;
            }
            check(contextMode != null, InputCode.MODEL_ROUTING_INVALID_CONTEXT_MODE);
            // An independent audit starts fresh; a context reason never justifies inherited parent turns.
            check(options.role() != Role.INDEPENDENT_AUDIT || (contextMode == ContextMode.LIMITED && (contextField == null || Objects.equals(context, application.limitedContextValue()))),
                    InputCode.MODEL_ROUTING_AUDIT_INHERITED_CONTEXT);
            if (contextMode == ContextMode.FULL_HISTORY) {
                check(filled(input.contextReason()), InputCode.MODEL_ROUTING_FULL_HISTORY_NEEDS_REASON);
            }
            Delivery delivery = plan.delivery();
            check(delivery != Delivery.BLOCKED, InputCode.MODEL_ROUTING_REASON_NO_SUPPORTED_CONFIGURATION);
            String modelField = application.modelField();
            String effortField = application.effortField();
            Dispatched dispatched = !spawn.containsKey(modelField) && !spawn.containsKey(effortField) ? null : new Dispatched(spawn.get(modelField), spawn.get(effortField));
            Setting selection = plan.selection();
            if (delivery == Delivery.EXPLICIT) {
                check(dispatched != null && selection != null && Objects.equals(dispatched.model(), selection.model()) && Objects.equals(dispatched.effort(), selection.effort()),
                        InputCode.MODEL_ROUTING_DISPATCH_MUST_MATCH);
            } else {
                check(dispatched == null, InputCode.MODEL_ROUTING_INHERITED_NO_OVERRIDES);
            }
            check(!(contextMode == ContextMode.FULL_HISTORY && application.fullHistoryOverrides() != null && !application.fullHistoryOverrides() && dispatched != null),
                    InputCode.MODEL_ROUTING_FULL_HISTORY_OVERRIDES_UNSUPPORTED);
            Observation observation = input.observation();
            if (observation != null) {
                check(observation.source() != null, InputCode.MODEL_ROUTING_OBSERVATION_SOURCE);
                check(filled(observation.reference()), InputCode.MODEL_ROUTING_OBSERVATION_REFERENCE);
            }
            boolean observed = observation != null && selection != null && Objects.equals(observation.model(), selection.model()) && Objects.equals(observation.effort(), selection.effort());
            boolean confirmed = observed || (delivery == Delivery.INHERITED && selection == null
                    && resolveSupported(observation == null ? null : new Requested(observation.model(), null, null, observation.effort()), options.supported(), false) != null);
            Map<String, Object> settings = new LinkedHashMap<>();
            for (String key : Arrays.asList(modelField, effortField, contextField)) {
                if (key != null && spawn.containsKey(key)) {
                    settings.put(key, spawn.get(key));
                }
            }
            String fallback = plan.fallbackReason();
            String reason = confirmed ? fallback : fallback == null ? t("reasonNotConfirmed") : fallback + "; " + t("reasonNotConfirmed");
            ActualStatus status = confirmed ? (delivery == Delivery.INHERITED ? ActualStatus.INHERITED : ActualStatus.APPLIED) : ActualStatus.UNVERIFIED;
            return new Recorded(input.assignmentId(), input.selectionReason(), contextMode, input.contextReason(), settings, plan.requested(), dispatched, observation,
                    new Actual(status, observation == null ? null : observation.model(), observation == null ? null : observation.effort()), reason);
        }

        Advised advise(JsonNode input, Presets presets) {
            List<SkillSchemas.Violation> requestViolations = schemas.violations(REQUEST, input);
            if (!requestViolations.isEmpty()) {
                throw new InvalidInputException(InputCode.SKILL_CHECK_INVALID_SUBAGENT_MODEL_REQUEST, Map.of(), SennardException.args("errors", requestViolations));
            }
            AdviseRequest request = schemas.bind(input, AdviseRequest.class);
            Assignment assignment = request.assignment();
            Host host = request.host();
            boolean highRisk = assignment.riskLevel() == RiskLevel.HIGH || assignment.riskLevel() == RiskLevel.CRITICAL || assignment.role() == Role.INDEPENDENT_AUDIT;
            RoutingOptions routing = new RoutingOptions(host.provider(), host.supported(), host.inherited(), host.inheritanceSupported(), host.inheritOnly(), assignment.role(), highRisk,
                    request.judgment(), request.user(), null);
            Plan plan = resolve(routing, presets);
            AdviceStatus status = plan.delivery() == Delivery.BLOCKED ? AdviceStatus.BLOCKED : plan.selection() == null ? AdviceStatus.INHERIT_UNVERIFIED : AdviceStatus.READY;
            JsonNode advice = mapper.valueToTree(new Advice("1.0.0", status, "none", assignment, new CapabilitySource(host.id(), host.capabilityReference()), routing, plan));
            List<SkillSchemas.Violation> adviceViolations = schemas.violations(ADVICE, advice);
            if (!adviceViolations.isEmpty()) {
                throw new InvalidInputException(InputCode.SKILL_CHECK_INVALID_SUBAGENT_MODEL_ADVICE, Map.of(), SennardException.args("errors", adviceViolations));
            }
            return new Advised(status, advice);
        }
    }
}
