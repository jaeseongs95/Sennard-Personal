// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.trust;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import io.github.jaeseongs95.sennard.server.trust.CollaborationDecisions.Decision;
import io.github.jaeseongs95.sennard.server.trust.CollaborationDecisions.OriginClaim;
import io.github.jaeseongs95.sennard.server.trust.CollaborationDecisions.Route;
import io.github.jaeseongs95.sennard.server.trust.TrustService.StoredReceipt;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The route a collaboration decision implies, derived from the record the decision is bound to (the schema has already passed when it is), and the receipt fields the
 * provenance check reads. The schema check itself, the receipt's integrity, binding, freshness and claim are held by ToolRulesServerTest through the tool.
 *
 * <pre>
 * d1  1.2.0 user turn, require, every benefit              delegate
 * d2  1.2.0 user turn, require, no single writer            needs-input
 * d3  1.2.0 user turn, require, limited context no but the full history is enough     delegate
 * d4  1.2.0 peer origin                                     direct, and audit-only when an audit is required
 * d5  1.0.0 audit required                                  needs-input when the user requires delegation, audit-only otherwise
 * d6  forbid                                                direct
 * d7  unspecified                                           delegate with every benefit, direct otherwise
 * d8  the wire words                                        audit-only, needs-input, user-turn are read to the constants
 * d9  a word that is none of them                           the binding is an error
 * d10 an origin that may not pass the schema                read as it is, so "user-turn" is found and a number is not
 * d11 a stored receipt                                      absent members read as empty text, unknown ones are ignored, one of the wrong shape is an error
 * </pre>
 */
class CollaborationDecisionRecordTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static Decision decision(String version, String origin, String directive, boolean audit, boolean independent, boolean singleWriter, boolean limitedContext, boolean everyOther,
            String fullHistory) {
        String criteria = "{\"independentlyCompletable\":" + independent + ",\"parallelBottleneckReduced\":" + everyOther + ",\"limitedContextSufficient\":" + limitedContext
                + ",\"singleWriterOwnership\":" + singleWriter + ",\"netBenefitAfterOverhead\":" + everyOther + "}";
        return MAPPER.readValue("{\"schemaVersion\":\"" + version + "\",\"sourceOriginKind\":\"" + origin + "\",\"sourceReceiptId\":null,\"authorityEffect\":\"none\",\"userDirective\":\"" + directive
                + "\",\"netBenefitCriteria\":" + criteria + ",\"auditSeparationRequired\":" + audit + ",\"route\":\"direct\"" + (fullHistory == null ? "" : ",\"fullHistoryContext\":" + fullHistory) + "}",
                Decision.class);
    }

    @Test
    void d1EveryBenefitDelegates() {
        assertEquals(Route.DELEGATE, CollaborationDecisions.derive(decision("1.2.0", "user-turn", "require", false, true, true, true, true, null)));
    }

    @Test
    void d2NoSingleWriterNeedsInput() {
        assertEquals(Route.NEEDS_INPUT, CollaborationDecisions.derive(decision("1.2.0", "user-turn", "require", false, true, false, true, true, null)));
    }

    @Test
    void d3TheFullHistoryCanMakeUpForLimitedContext() {
        assertEquals(Route.DELEGATE, CollaborationDecisions.derive(decision("1.2.0", "user-turn", "require", false, true, true, false, false, "{\"sufficient\":true,\"reason\":\"all of it\"}")));
        assertEquals(Route.NEEDS_INPUT, CollaborationDecisions.derive(decision("1.2.0", "user-turn", "require", false, true, true, false, false, "{\"sufficient\":false,\"reason\":\"no\"}")));
    }

    @Test
    void d4APeerOriginGoesDirectOrAuditOnly() {
        assertEquals(Route.DIRECT, CollaborationDecisions.derive(decision("1.2.0", "peer", "unspecified", false, true, true, true, true, null)));
        assertEquals(Route.AUDIT_ONLY, CollaborationDecisions.derive(decision("1.2.0", "peer", "unspecified", true, true, true, true, true, null)));
    }

    @Test
    void d5TheFirstVersionHasItsOwnRuleForAudit() {
        assertEquals(Route.NEEDS_INPUT, CollaborationDecisions.derive(decision("1.0.0", "user-turn", "require", true, true, true, true, true, null)));
        assertEquals(Route.AUDIT_ONLY, CollaborationDecisions.derive(decision("1.0.0", "user-turn", "unspecified", true, true, true, true, true, null)));
        assertEquals(Route.DIRECT, CollaborationDecisions.derive(decision("1.0.0", "user-turn", "unspecified", false, true, true, true, false, null)));
    }

    @Test
    void d6ForbidIsDirect() {
        assertEquals(Route.DIRECT, CollaborationDecisions.derive(decision("1.2.0", "user-turn", "forbid", false, true, true, true, true, null)));
        assertEquals(Route.DIRECT, CollaborationDecisions.derive(decision("1.0.0", "user-turn", "forbid", false, true, true, true, true, null)));
    }

    @Test
    void d7UnspecifiedDelegatesOnlyWithEveryBenefit() {
        assertEquals(Route.DELEGATE, CollaborationDecisions.derive(decision("1.2.0", "user-turn", "unspecified", false, true, true, true, true, null)));
        assertEquals(Route.DIRECT, CollaborationDecisions.derive(decision("1.2.0", "user-turn", "unspecified", false, true, true, true, false, null)));
    }

    @Test
    void d8TheWireWordsAreReadToTheConstants() {
        Decision decision = decision("1.1.0", "user-turn", "require", true, true, true, true, true, null);
        assertEquals(CollaborationDecisions.SchemaVersion.V1_1_0, decision.schemaVersion());
        assertEquals(CollaborationDecisions.OriginKind.USER_TURN, decision.sourceOriginKind());
        assertEquals(CollaborationDecisions.Directive.REQUIRE, decision.userDirective());
        assertEquals("audit-only", Route.AUDIT_ONLY.wire());
        assertEquals("needs-input", Route.NEEDS_INPUT.wire());
        assertEquals("\"audit-only\"", MAPPER.writeValueAsString(Route.AUDIT_ONLY));
    }

    @Test
    void d9AWordThatIsNoneOfThemIsABindingError() {
        assertThrows(JacksonException.class, () -> MAPPER.readValue("{\"route\":\"not-a-route\"}", Decision.class));
        assertThrows(JacksonException.class, () -> MAPPER.readValue("{\"schemaVersion\":\"9.9.9\"}", Decision.class));
    }

    @Test
    void d10AnOriginThatMayNotPassTheSchemaIsReadAsItIs() {
        assertEquals("user-turn", MAPPER.readValue("{\"sourceOriginKind\":\"user-turn\",\"route\":\"x\"}", OriginClaim.class).sourceOriginKind());
        assertEquals(5, MAPPER.readValue("{\"sourceOriginKind\":5}", OriginClaim.class).sourceOriginKind());
        assertNull(MAPPER.readValue("{}", OriginClaim.class).sourceOriginKind());
    }

    @Test
    void d11AStoredReceiptReadsItsFieldsAndRefusesAWrongShape() {
        StoredReceipt receipt = MAPPER.readValue("{\"host\":\"codex\",\"originKind\":\"peer\",\"integrityToken\":\"t\",\"unknown\":{\"a\":1}}", StoredReceipt.class);
        assertEquals("codex", receipt.host());
        assertEquals("peer", receipt.originKind());
        assertEquals("", receipt.sessionId());
        assertEquals("", receipt.expiresAt());
        assertThrows(JacksonException.class, () -> MAPPER.readValue("{\"host\":{\"a\":1}}", StoredReceipt.class));
    }
}
