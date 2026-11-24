// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.execution;

import static io.github.jaeseongs95.sennard.personal.RunnerFixture.awaitCondition;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.Test;

/**
 * The deadline of the stop of the server. What is waited for is the state of the deadline (it has passed), never a pause of a chosen length: the deadlines here are a few
 * milliseconds and the test polls until the deadline says it has passed.
 *
 * <pre>
 * d1  not armed    no stop is going on: what is left is the whole deadline, and a wait is not limited (not even to the deadline)
 * d2  armed        what is left is at most the deadline, a wait is limited to what is left and one that is shorter is not made longer; once the deadline has passed nothing is left
 * d3  armed once   the first arm fixes the moment; a second one (the other component of the stop) does not move it
 * d4  reset        a start of the server clears it: the next stop has the whole deadline again
 * d5  not negative a negative deadline is refused
 * </pre>
 */
class StopDeadlineTest {
    private static final Duration SHORT = Duration.ofMillis(30);

    @Test
    void whileNoStopIsGoingOnTheWholeDeadlineIsLeftAndAWaitIsNotLimited() {
        StopDeadline deadline = new StopDeadline(SHORT);

        assertEquals(SHORT, deadline.remaining());
        assertEquals(Duration.ofMinutes(5), deadline.limit(Duration.ofMinutes(5)), "the waits of the executions that end on their own are not changed by it");
    }

    @Test
    void aWaitIsLimitedToWhatIsLeftAndNothingIsLeftOnceTheDeadlineHasPassed() throws Exception {
        StopDeadline deadline = new StopDeadline(Duration.ofSeconds(30));
        deadline.arm();

        assertTrue(deadline.remaining().compareTo(Duration.ofSeconds(30)) <= 0);
        assertTrue(deadline.limit(Duration.ofMinutes(5)).compareTo(Duration.ofSeconds(30)) <= 0, "a long wait is cut to what is left");
        assertEquals(Duration.ofSeconds(1), deadline.limit(Duration.ofSeconds(1)), "a short wait is not made longer");

        StopDeadline passing = new StopDeadline(SHORT);
        passing.arm();
        awaitCondition(() -> passing.remaining().isZero(), "the deadline to have passed");
        assertEquals(Duration.ZERO, passing.limit(Duration.ofMinutes(5)));
        assertEquals(Duration.ZERO, passing.limit(Duration.ofMillis(1)));
    }

    @Test
    void theFirstArmFixesTheMomentAndTheSecondOneDoesNotMoveIt() throws Exception {
        StopDeadline deadline = new StopDeadline(SHORT);
        deadline.arm();
        awaitCondition(() -> deadline.remaining().isZero(), "the deadline to have passed");

        deadline.arm(); // the component that stops second

        assertEquals(Duration.ZERO, deadline.remaining(), "it was not armed again: the deadline of the stop has passed for it too");
    }

    @Test
    void aResetGivesTheNextStopTheWholeDeadlineAgain() throws Exception {
        StopDeadline deadline = new StopDeadline(SHORT);
        deadline.arm();
        awaitCondition(() -> deadline.remaining().isZero(), "the deadline to have passed");

        deadline.reset();

        assertEquals(SHORT, deadline.remaining());
    }

    @Test
    void aNegativeDeadlineIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new StopDeadline(Duration.ofMillis(-1)));
    }
}
