// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The closed sets of the workflow documents as enums. Each case reads a document the way the gates do: a name the set knows gives its constant, a
 * name it does not know is refused (strict read) or is not any constant (lenient read), and the wire text a skill or contract fixes is the text
 * the constant is written with.
 */
class ClosedSetTermsTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static <E extends Enum<E>> E read(String text, Class<E> type) {
        return MAPPER.readValue("\"" + text + "\"", type);
    }

    private static List<String> wires(Enum<?>[] constants) {
        return Arrays.stream(constants).map(constant -> MAPPER.valueToTree(constant).asString()).toList();
    }

    /** T1: a set whose wire text is fixed outside reads and writes that text. */
    @Test
    void aFixedWireTextIsReadAndWrittenAsSpelled() {
        assertEquals(RiskGate.MANDATORY, read("mandatory", RiskGate.class));
        assertEquals(ReviewRoute.RESUME_NEW_EPOCH, read("resume-new-epoch", ReviewRoute.class));
        assertEquals(DecisionTerms.Assurance.PARTIALLY_INDEPENDENT, read("partially_independent", DecisionTerms.Assurance.class));
        assertEquals("\"post-deploy\"", MAPPER.writeValueAsString(AuditPhase.POST_DEPLOY));
    }

    /** T2: a set the server owns is written by the constant's name, which Jackson reads back. */
    @Test
    void aServerOwnedSetIsSpelledByName() {
        assertEquals(Provenance.HOST_OBSERVED, read("HOST_OBSERVED", Provenance.class));
        assertEquals("\"SHA256_MATCHED\"", MAPPER.writeValueAsString(ArtifactCheck.SHA256_MATCHED));
        assertThrows(JacksonException.class, () -> read("host-observed", Provenance.class));
    }

    /** T3: the strict read refuses what names no constant; a null is no constant. */
    @Test
    void theStrictReadRefusesAnUnknownNameAndReadsANullAsNoConstant() {
        assertThrows(JacksonException.class, () -> read("bogus", RiskGate.class));
        assertThrows(JacksonException.class, () -> read("MANDATORY", RiskGate.class));
        assertNull(MAPPER.readValue("null", RiskGate.class));
    }

    /** T4: a tool's response mode is read from the argument as the text it is; a name it does not know and a number are no mode, and a number is never an ordinal. */
    @Test
    void aResponseModeIsTheTextItIsWrittenWithAndNothingElse() {
        assertEquals(ResponseMode.COMPACT, ResponseMode.of("compact"));
        assertEquals(ResponseMode.FULL, ResponseMode.of("full"));
        assertNull(ResponseMode.of("verbose"));
        assertNull(ResponseMode.of("COMPACT"));
        assertNull(ResponseMode.of(1));
        assertNull(ResponseMode.of(null));
    }

    /** T5: a published input schema lists a set's values in constant order, from the enum itself. */
    @Test
    void aPublishedSchemaListsTheValuesOfTheEnum() {
        assertEquals(List.of("pre-execution", "post-execution", "pre-deploy", "post-deploy"), wires(AuditPhase.values()));
        assertEquals(List.of("compact", "full"), wires(ResponseMode.values()));
        assertEquals(List.of("design-readiness", "quality-or-release"), wires(EvaluationAuditPurpose.values()));
    }

    /** T6: the routes a review of each classification may take, the whole table (a route outside it is refused by the gate). */
    @ParameterizedTest
    @EnumSource(ReviewClassification.class)
    void eachClassificationAllowsExactlyItsRoutes(ReviewClassification classification) {
        Set<ReviewRoute> expected = switch (classification) {
            case SEMANTICS_PRESERVING -> EnumSet.of(ReviewRoute.RESUME_NEW_EPOCH, ReviewRoute.DIAGNOSE, ReviewRoute.STOP);
            case SEMANTICS_CHANGING -> EnumSet.of(ReviewRoute.NEEDS_USER);
            case AMBIGUOUS -> EnumSet.of(ReviewRoute.PANEL, ReviewRoute.NEEDS_USER, ReviewRoute.STOP);
        };
        assertEquals(expected, classification.allowedRoutes());
    }

    /** T7: only a route that resumes the work leaves the root's state to the epoch budget. */
    @Test
    void onlyResumingLeavesTheRootStateOpen() {
        for (ReviewRoute route : ReviewRoute.values()) {
            assertEquals(route == ReviewRoute.RESUME_NEW_EPOCH, route.resultingState() == null, route.name());
        }
    }

    /** T8: audit phases after the work ran are exactly the two that must say they verified that state. */
    @Test
    void thePhasesAfterExecutionAreTheTwoThatSayTheyVerifiedIt() {
        assertEquals(EnumSet.of(AuditPhase.POST_EXECUTION, AuditPhase.POST_DEPLOY), EnumSet.copyOf(java.util.Arrays.stream(AuditPhase.values()).filter(AuditPhase::isAfterExecution).toList()));
        assertTrue(RiskLevel.HIGH.needsAudit() && RiskLevel.CRITICAL.needsAudit());
        assertFalse(RiskLevel.LOW.needsAudit() || RiskLevel.MEDIUM.needsAudit());
    }
}
