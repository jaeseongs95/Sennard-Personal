// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/**
 * The limits of a convergence root: each at least 1 (the declaration {@code @Min(1)} is the whole rule), no upper bound, and 3 attempts in 2 epochs when the user has
 * set nothing.
 *
 * <pre>
 * l1  default   3 full attempts per epoch and 2 epochs
 * l2  minimum   1 is allowed, 0 and a negative number are refused, in either value
 * l3  no upper  a very large number is allowed
 * l4  frame     a frame takes the limits and keeps every other part of itself
 * </pre>
 */
class ConvergenceLimitsTest {
    private static LocalValidatorFactoryBean validator;

    @BeforeAll
    static void wire() {
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
    }

    /** L1 */
    @Test
    void withNothingSetTheLimitsAreThreeAttemptsAndTwoEpochs() {
        assertEquals(3, ConvergenceLimits.DEFAULT.maxAttemptsPerEpoch());
        assertEquals(2, ConvergenceLimits.DEFAULT.maxEpochs());
        assertTrue(validator.validate(ConvergenceLimits.DEFAULT).isEmpty());
    }

    /** L2 and L3 */
    @ParameterizedTest
    @CsvSource({"1,1,true", "1,2,true", "3,2,true", "10,7,true", "2147483647,2147483647,true", "0,2,false", "3,0,false", "0,0,false", "-1,2,false", "3,-1,false", "-5,-5,false",
            "-2147483648,2,false"})
    void eachLimitIsAtLeastOneAndHasNoUpperBound(int attempts, int epochs, boolean valid) {
        assertEquals(valid, validator.validate(new ConvergenceLimits(attempts, epochs)).isEmpty(), attempts + "," + epochs);
    }

    /** L4 */
    @Test
    void aFrameTakesTheLimitsAndKeepsTheRestOfItself() {
        ConvergenceFrame frame = new ConvergenceFrame("1.0.0", new ConvergenceFrame.Workspace("w", "/w"), java.util.List.of(), java.util.List.of(),
                new ConvergenceFrame.OperationalSettings(3, 2, 300));
        ConvergenceFrame changed = frame.withLimits(new ConvergenceLimits(7, 5));
        assertEquals(new ConvergenceFrame.OperationalSettings(7, 5, 300), changed.operationalSettings());
        assertEquals(frame.workspace(), changed.workspace());
        assertEquals(frame.controlArtifacts(), changed.controlArtifacts());
        assertEquals(frame.targetArtifacts(), changed.targetArtifacts());
        assertEquals(frame.schemaVersion(), changed.schemaVersion());
        assertEquals(new ConvergenceFrame.OperationalSettings(3, 2, 300), frame.operationalSettings(), "the frame it was taken from is not changed");
    }
}
