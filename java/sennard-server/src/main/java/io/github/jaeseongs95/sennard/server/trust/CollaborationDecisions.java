// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.trust;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonValue;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;

import io.github.jaeseongs95.sennard.server.contract.SchemaLocale;

import io.github.jaeseongs95.sennard.server.message.MessageRef;
import io.github.jaeseongs95.sennard.server.message.Messages;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The structural check of a collaboration decision: the published schema, then the route its own fields imply. */
@Component
public class CollaborationDecisions {
    /** The schema versions of a decision; the wire text is the version. */
    public enum SchemaVersion {
        V1_0_0("1.0.0"), V1_1_0("1.1.0"), V1_2_0("1.2.0");

        private final String wire;

        SchemaVersion(String wire) {
            this.wire = wire;
        }

        @JsonValue
        public String wire() {
            return wire;
        }
    }

    /** Where the input a decision rests on came from. */
    public enum OriginKind {
        USER_TURN("user-turn"), PEER("peer"), SYSTEM("system"), DEVELOPER("developer"), PROJECT("project"), ARTIFACT("artifact"), UNKNOWN("unknown"), TOOL("tool"), DELEGATED("delegated");

        private final String wire;

        OriginKind(String wire) {
            this.wire = wire;
        }

        @JsonValue
        public String wire() {
            return wire;
        }
    }

    /** What the user said about delegating. */
    public enum Directive {
        REQUIRE("require"), FORBID("forbid"), UNSPECIFIED("unspecified");

        private final String wire;

        Directive(String wire) {
            this.wire = wire;
        }

        @JsonValue
        public String wire() {
            return wire;
        }
    }

    /** The implementation routing of a decision. */
    public enum Route {
        DIRECT("direct"), DELEGATE("delegate"), AUDIT_ONLY("audit-only"), NEEDS_INPUT("needs-input");

        private final String wire;

        Route(String wire) {
            this.wire = wire;
        }

        @JsonValue
        public String wire() {
            return wire;
        }
    }

    /** The five yes-or-no findings the route rests on. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Criteria(boolean independentlyCompletable, boolean parallelBottleneckReduced, boolean limitedContextSufficient, boolean singleWriterOwnership,
            boolean netBenefitAfterOverhead) {
        boolean allHold() {
            return independentlyCompletable && parallelBottleneckReduced && limitedContextSufficient && singleWriterOwnership && netBenefitAfterOverhead;
        }
    }

    /** Whether the full history gave enough context (schema 1.1.0 and later). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FullHistory(boolean sufficient, String reason) {
    }

    /** A decision that passed the schema, read for the fields the route and the provenance check use. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Decision(SchemaVersion schemaVersion, OriginKind sourceOriginKind, String sourceReceiptId, Directive userDirective, Criteria netBenefitCriteria,
            boolean auditSeparationRequired, Route route, FullHistory fullHistoryContext) {
    }

    /** Only the origin of a decision that may not have passed the schema; whatever it holds is compared with the one origin that matters. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OriginClaim(Object sourceOriginKind) {
    }

    /**
     * The result of the check.
     *
     * @param errors the structural errors, empty when the decision is well formed
     * @param decision the decision as a record, present only when there are no errors
     * @param claimsUserTurn whether the decision names the user's turn as its origin, also when it is malformed otherwise
     */
    public record Checked(List<String> errors, Decision decision, boolean claimsUserTurn) {
    }

    private final Schema schema;
    private final Messages messages;
    private final JsonMapper mapper;

    public CollaborationDecisions(Messages messages, JsonMapper mapper) throws IOException {
        this.messages = messages;
        this.mapper = mapper;
        try (InputStream in = new ClassPathResource("contracts/collaboration-decision.v1.schema.json").getInputStream()) {
            this.schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(in);
        }
    }

    /** The route the decision's fields imply. Version 1.0.0 decisions remain valid evidence with their own rule; new planning uses the later one. */
    static Route derive(Decision decision) {
        Criteria criteria = decision.netBenefitCriteria();
        boolean allBenefitsHold = criteria.allHold();
        Directive directive = decision.userDirective();
        boolean audit = decision.auditSeparationRequired();
        boolean userTurn = decision.sourceOriginKind() == OriginKind.USER_TURN;
        if (decision.schemaVersion() == SchemaVersion.V1_0_0) {
            if (audit) {
                return directive == Directive.REQUIRE ? Route.NEEDS_INPUT : Route.AUDIT_ONLY;
            }
            if (!userTurn || directive == Directive.FORBID) {
                return Route.DIRECT;
            }
            if (directive == Directive.REQUIRE) {
                return allBenefitsHold ? Route.DELEGATE : Route.NEEDS_INPUT;
            }
            return allBenefitsHold ? Route.DELEGATE : Route.DIRECT;
        }
        Route local = audit ? Route.AUDIT_ONLY : Route.DIRECT;
        if (!userTurn || directive == Directive.FORBID) {
            return local;
        }
        if (directive == Directive.REQUIRE) {
            boolean feasible = criteria.independentlyCompletable() && criteria.singleWriterOwnership()
                    && (criteria.limitedContextSufficient() || (decision.fullHistoryContext() != null && decision.fullHistoryContext().sufficient()));
            return feasible ? Route.DELEGATE : Route.NEEDS_INPUT;
        }
        return allBenefitsHold ? Route.DELEGATE : local;
    }

    /**
     * The structural check. The decision arrives as a tree because the JSON Schema validator reads one; once it passes, the tree is bound to {@link Decision} once and the
     * route is derived from that record.
     */
    public Checked check(JsonNode decision) {
        List<Error> errors = schema.validate(decision, SchemaLocale.IN_ENGLISH);
        if (!errors.isEmpty()) {
            List<String> described = errors.stream().map(error -> {
                String location = error.getInstanceLocation().toString();
                return (location.isEmpty() ? "/" : location) + " " + error.getMessage();
            }).toList();
            return new Checked(described, null, OriginKind.USER_TURN.wire().equals(mapper.treeToValue(decision, OriginClaim.class).sourceOriginKind()));
        }
        Decision bound = mapper.treeToValue(decision, Decision.class);
        Route expected = derive(bound);
        boolean userTurn = bound.sourceOriginKind() == OriginKind.USER_TURN;
        if (expected == bound.route()) {
            return new Checked(List.of(), bound, userTurn);
        }
        return new Checked(List.of(messages.render(MessageRef.of("skillScripts.orchestrator.routeMustBe", Map.of("route", expected.wire())))), null, userTurn);
    }
}
