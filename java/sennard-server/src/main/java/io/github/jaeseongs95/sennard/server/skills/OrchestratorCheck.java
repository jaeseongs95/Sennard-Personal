// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.skills;

import static io.github.jaeseongs95.sennard.server.skills.Checks.need;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;

import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.domain.error.InputCode;
import io.github.jaeseongs95.sennard.domain.error.InvalidInputException;
import io.github.jaeseongs95.sennard.server.message.Messages;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/** orchestrator: the registry's providers for a capability, and whether a collaboration decision's route follows from its own criteria. */
@Component
public class OrchestratorCheck implements SkillCheck<OrchestratorCheck.Command> {
    private static final String M = "skillScripts.orchestrator.";
    private static final String DECISION = "skills/orchestrator/contracts/collaboration-decision.v1.schema.json";
    private static final String REGISTRY = "skills/registry.json";
    private static final String REGISTRY_VERSION = "2.0.0";

    /** The commands of this check, by the name a caller sends. */
    enum Command implements SkillCommand {
        QUERY_REGISTRY("query-registry"), VALIDATE_COLLABORATION_DECISION("validate-collaboration-decision");

        private final String wire;

        Command(String wire) {
            this.wire = wire;
        }

        @Override
        public String wire() {
            return wire;
        }
    }

    /** The registry's members this query reads; a registry carries more (paths, bindings, state mappings) that no query here needs. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Registry(String schemaVersion, List<RegistrySkill> skills) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RegistrySkill(String skillId, String version, Boolean enabled, Integer priority, List<RegistryProvider> providers) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RegistryProvider(List<String> capabilities, String executionClass, String phase, Integer phaseOrder, List<String> selectionCriteria, List<String> preconditions, Gate gate) {
    }

    /** A provider's gate as the answer states it (its validator belongs to the gate's own check). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Gate(String kind, String policy) {
    }

    /** One provider of a capability, with the skill's identity and priority. */
    record ProviderRow(String skillId, String version, int providerIndex, List<String> capabilities, String executionClass, String phase, Integer phaseOrder, Integer priority,
            List<String> selectionCriteria, List<String> preconditions, Gate gate) {
        int order() {
            return phaseOrder == null ? 0 : phaseOrder;
        }
    }

    record Query(boolean all, List<String> capabilities) {
    }

    record QueryAnswer(String schemaVersion, Query query, List<ProviderRow> providers, List<String> missingCapabilities) {
    }

    enum SchemaVersion {
        @JsonProperty("1.0.0") V1_0_0, @JsonProperty("1.1.0") V1_1_0, @JsonProperty("1.2.0") V1_2_0
    }

    enum Origin {
        @JsonProperty("user-turn") USER_TURN, @JsonProperty("peer") PEER, @JsonProperty("system") SYSTEM, @JsonProperty("developer") DEVELOPER, @JsonProperty("project") PROJECT,
        @JsonProperty("artifact") ARTIFACT, @JsonProperty("unknown") UNKNOWN, @JsonProperty("tool") TOOL, @JsonProperty("delegated") DELEGATED
    }

    enum Directive {
        @JsonProperty("require") REQUIRE, @JsonProperty("forbid") FORBID, @JsonProperty("unspecified") UNSPECIFIED
    }

    enum Route {
        DIRECT("direct"), DELEGATE("delegate"), AUDIT_ONLY("audit-only"), NEEDS_INPUT("needs-input");

        private final String wire;

        Route(String wire) {
            this.wire = wire;
        }

        /** The name the contract and the message catalog use for this route. */
        @JsonValue
        public String wire() {
            return wire;
        }
    }

    /** The five judgments a decision records about delegating. */
    record Criteria(boolean independentlyCompletable, boolean parallelBottleneckReduced, boolean limitedContextSufficient, boolean singleWriterOwnership, boolean netBenefitAfterOverhead) {
        boolean allHold() {
            return independentlyCompletable && parallelBottleneckReduced && limitedContextSufficient && singleWriterOwnership && netBenefitAfterOverhead;
        }
    }

    record FullHistory(boolean sufficient, String reason) {
    }

    /** A collaboration decision (the contract's members; this check reads the route-relevant ones, the schema gate has judged the rest). */
    record Decision(SchemaVersion schemaVersion, Origin sourceOriginKind, String sourceReceiptId, String authorityEffect, Directive userDirective, Criteria netBenefitCriteria,
            FullHistory fullHistoryContext, boolean auditSeparationRequired, Route route) {
    }

    record Diagnostic(String scope, boolean valid, List<String> errors, String diagnostic) {
    }

    private final SkillSchemas schemas;
    private final Messages messages;
    private final Registry packaged;

    public OrchestratorCheck(SkillSchemas schemas, Messages messages) {
        this.schemas = schemas;
        this.messages = messages;
        this.packaged = schemas.assets().read(REGISTRY, Registry.class);
    }

    @Override
    public String skill() {
        return "orchestrator";
    }

    @Override
    public Class<Command> commandType() {
        return Command.class;
    }

    @Override
    public SkillCheckResult run(Command command, SkillArguments args) {
        return switch (command) {
            case QUERY_REGISTRY -> SkillCheckResult.ok(queryRegistry(args.flag("all"), args.stringList("capability"), args.has("registry") ? registryFrom(args.document("registry")) : packaged));
            case VALIDATE_COLLABORATION_DECISION -> {
                Diagnostic result = diagnostic(args.document("input"));
                yield SkillCheckResult.of(result, result.valid());
            }
        };
    }

    /** A caller-supplied registry, bound to the same record the packaged one is. */
    private Registry registryFrom(JsonNode document) {
        try {
            return schemas.mapper().treeToValue(document, Registry.class);
        } catch (JacksonException notARegistry) {
            throw new InvalidInputException(InputCode.ORCHESTRATOR_REGISTRY_NOT_V2, Map.of(), Map.of(), notARegistry);
        }
    }

    private static QueryAnswer queryRegistry(boolean all, List<String> capabilities, Registry registry) {
        need(!all || capabilities.isEmpty(), InputCode.ORCHESTRATOR_ALL_OR_CAPABILITY);
        need(all || !capabilities.isEmpty(), InputCode.ORCHESTRATOR_SPECIFY_CAPABILITY);
        need(REGISTRY_VERSION.equals(registry.schemaVersion()) && registry.skills() != null, InputCode.ORCHESTRATOR_REGISTRY_NOT_V2);
        Set<String> requested = new LinkedHashSet<>(capabilities);
        Set<String> matched = new LinkedHashSet<>();
        List<ProviderRow> rows = new ArrayList<>();
        for (RegistrySkill skill : registry.skills()) {
            if (skill == null || !Boolean.TRUE.equals(skill.enabled()) || skill.providers() == null) {
                continue;
            }
            int providerIndex = -1;
            for (RegistryProvider provider : skill.providers()) {
                providerIndex++;
                if (provider == null || provider.capabilities() == null) {
                    continue;
                }
                List<String> provided = provider.capabilities();
                if (!all && provided.stream().noneMatch(requested::contains)) {
                    continue;
                }
                provided.stream().filter(requested::contains).forEach(matched::add);
                Gate gate = provider.gate() == null ? new Gate(null, null) : provider.gate();
                rows.add(new ProviderRow(skill.skillId(), skill.version(), providerIndex, provided, provider.executionClass(), provider.phase(), provider.phaseOrder(), skill.priority(),
                        provider.selectionCriteria(), provider.preconditions(), gate));
            }
        }
        rows.sort(Comparator.comparingInt(ProviderRow::order).thenComparing(ProviderRow::skillId).thenComparingInt(ProviderRow::providerIndex));
        List<String> missing = all ? List.of() : requested.stream().filter(capability -> !matched.contains(capability)).toList();
        return new QueryAnswer("1.0.0", new Query(all, new ArrayList<>(requested)), rows, missing);
    }

    static Route deriveRoute(Decision decision) {
        Criteria benefits = decision.netBenefitCriteria();
        boolean allHold = benefits.allHold();
        boolean auditSeparation = decision.auditSeparationRequired();
        Directive directive = decision.userDirective();
        boolean userTurn = decision.sourceOriginKind() == Origin.USER_TURN;
        // Decisions recorded under v1.0 remain valid evidence of what was decided then; new planning uses v1.1.
        if (decision.schemaVersion() == SchemaVersion.V1_0_0) {
            if (auditSeparation) {
                return directive == Directive.REQUIRE ? Route.NEEDS_INPUT : Route.AUDIT_ONLY;
            }
            if (!userTurn || directive == Directive.FORBID) {
                return Route.DIRECT;
            }
            if (directive == Directive.REQUIRE) {
                return allHold ? Route.DELEGATE : Route.NEEDS_INPUT;
            }
            return allHold ? Route.DELEGATE : Route.DIRECT;
        }
        Route local = auditSeparation ? Route.AUDIT_ONLY : Route.DIRECT;
        if (!userTurn || directive == Directive.FORBID) {
            return local;
        }
        if (directive == Directive.REQUIRE) {
            boolean fullHistorySufficient = decision.fullHistoryContext() != null && decision.fullHistoryContext().sufficient();
            boolean feasible = benefits.independentlyCompletable() && benefits.singleWriterOwnership() && (benefits.limitedContextSufficient() || fullHistorySufficient);
            return feasible ? Route.DELEGATE : Route.NEEDS_INPUT;
        }
        return allHold ? Route.DELEGATE : local;
    }

    private Diagnostic diagnostic(JsonNode document) {
        List<String> errors = new ArrayList<>(SkillSchemas.texts(schemas.violations(DECISION, document)));
        if (errors.isEmpty()) {
            Decision decision = schemas.bind(document, Decision.class);
            Route expected = deriveRoute(decision);
            if (expected != decision.route()) {
                errors.add(messages.forRequest(M + "routeMustBe", SennardException.args("route", expected.wire())));
            }
        }
        return new Diagnostic("structural-only", errors.isEmpty(), errors, messages.forRequest(M + "structuralDiagnostic", Map.of()));
    }
}
