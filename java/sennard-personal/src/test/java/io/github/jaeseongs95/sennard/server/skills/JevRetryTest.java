// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.skills;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.core.retry.RetryException;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.util.backoff.BackOffExecution;

import io.github.jaeseongs95.sennard.server.config.SennardProperties;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.ReasonCode;

/** The retry's rules on their own: what {@code Retry-After} means, which failures are communication, and that the deadline ends the tries. */
class JevRetryTest {
    private static final Instant NOW = Instant.parse("2026-11-14T12:00:00Z");

    @Test
    void retryAfterIsSecondsOrAnHttpDateCountedFromNowAndAnythingElseIsNoAnswer() {
        assertEquals(Optional.of(Duration.ofSeconds(7)), JevRetry.retryAfter("7", NOW));
        assertEquals(Optional.of(Duration.ofSeconds(120)), JevRetry.retryAfter(" 120 ", NOW));
        assertEquals(Optional.of(Duration.ofSeconds(30)), JevRetry.retryAfter("Sat, 14 Nov 2026 12:00:30 GMT", NOW));
        assertEquals(Optional.of(Duration.ZERO), JevRetry.retryAfter("Sat, 14 Nov 2026 11:00:00 GMT", NOW), "a date in the past is no wait");
        assertEquals(Optional.of(Duration.ofDays(365)), JevRetry.retryAfter("999999999999999999", NOW), "an absurd wait is capped, never overflowed");
        assertEquals(Optional.empty(), JevRetry.retryAfter(null, NOW));
        assertEquals(Optional.empty(), JevRetry.retryAfter("soon", NOW));
        assertEquals(Optional.empty(), JevRetry.retryAfter("-5", NOW));
        assertEquals(Optional.empty(), JevRetry.retryAfter("1.5", NOW));
    }

    @Test
    void everyReasonCodeIsOneKindAndTheCommunicationOnesAreExactlyTheRetriedOnes() {
        for (ReasonCode code : ReasonCode.values()) {
            JevFailureKind.of(code);
        }
        assertEquals(JevFailureKind.COMMUNICATION, JevFailureKind.of(ReasonCode.TRANSPORT_UNAVAILABLE));
        assertEquals(JevFailureKind.COMMUNICATION, JevFailureKind.of(ReasonCode.RATE_LIMITED));
        assertEquals(JevFailureKind.COMMUNICATION, JevFailureKind.of(ReasonCode.SERVER_UNAVAILABLE));
        assertEquals(JevFailureKind.COMMUNICATION, JevFailureKind.of(ReasonCode.PROVIDER_TIMEOUT));
        assertEquals(JevFailureKind.NON_COMMUNICATION, JevFailureKind.of(ReasonCode.AUTH_UNAVAILABLE));
        assertEquals(JevFailureKind.NON_COMMUNICATION, JevFailureKind.of(ReasonCode.CREDENTIAL_UNAVAILABLE));
        assertEquals(JevFailureKind.NON_COMMUNICATION, JevFailureKind.of(ReasonCode.INVALID_PROVIDER_RESPONSE));
        assertEquals(JevFailureKind.NON_COMMUNICATION, JevFailureKind.of(ReasonCode.API_UNAVAILABLE));
        assertEquals(JevFailureKind.TERMINAL, JevFailureKind.of(ReasonCode.REMOTE_CONTENT_NOT_APPROVED), "sending was not allowed: no other evaluator either");
        assertEquals(JevFailureKind.TERMINAL, JevFailureKind.of(ReasonCode.BUDGET_UNAVAILABLE));
        assertEquals(JevFailureKind.TERMINAL, JevFailureKind.of(ReasonCode.CANCELLED));
    }


    @Test
    void thePolicyRetriesOnlyCommunicationAndItsTimeoutIsWhatIsLeftOfTheDeadline() {
        JevRetry retry = new JevRetry(properties(settings(5, 20, Duration.ofSeconds(60))), Clock.fixed(NOW.plusSeconds(25), ZoneOffset.UTC));
        RetryPolicy policy = retry.policy(NOW);
        assertEquals(Duration.ofSeconds(35), policy.getTimeout(), "the deadline counts from the start of the request, by the clock");
        assertTrue(policy.shouldRetry(new JevRetry.Communication(null)));
        assertFalse(policy.shouldRetry(new IllegalStateException("not communication")));
        assertEquals(Duration.ofSeconds(60), new JevRetry(properties(settings(5, 20, Duration.ofSeconds(60))), Clock.fixed(NOW, ZoneOffset.UTC)).policy(NOW).getTimeout());
        assertEquals(Duration.ofMillis(1), new JevRetry(properties(settings(5, 20, Duration.ofSeconds(60))), Clock.fixed(NOW.plusSeconds(61), ZoneOffset.UTC)).policy(NOW).getTimeout(),
                "a deadline that has passed leaves the smallest timeout, never zero (zero would mean no limit)");
    }

    @Test
    void theProvidersRetryAfterIsTheWaitAndOnlyWithoutOneDoesTheOwnPauseGrowToItsCeiling() {
        JevRetry retry = new JevRetry(properties(settings(5, 20, Duration.ofSeconds(60))), Clock.fixed(NOW, ZoneOffset.UTC));
        RetryPolicy policy = retry.policy(NOW);

        policy.shouldRetry(new JevRetry.Communication("1"));
        BackOffExecution named = policy.getBackOff().start();
        assertEquals(1000, named.nextBackOff(), "Retry-After: 1 is followed, not the 5 ms pause");
        policy.shouldRetry(new JevRetry.Communication("Sat, 14 Nov 2026 12:00:30 GMT"));
        assertEquals(30_000, named.nextBackOff(), "an HTTP date is counted from the clock's now");
        policy.shouldRetry(new JevRetry.Communication("3600"));
        assertEquals(3_600_000, named.nextBackOff(), "an hour asked for is the wait that the deadline then refuses");

        BackOffExecution own = policy.getBackOff().start();
        policy.shouldRetry(new JevRetry.Communication(null));
        assertEquals(List.of(5L, 10L, 20L, 20L), List.of(own.nextBackOff(), own.nextBackOff(), own.nextBackOff(), own.nextBackOff()), "doubled each time, to the ceiling");
        BackOffExecution neither = policy.getBackOff().start();
        policy.shouldRetry(new JevRetry.Communication("soon"));
        assertEquals(5, neither.nextBackOff(), "a header of neither form names no pause, so the own pause is used");
    }

    @Test
    void aCallThatFailsInCommunicationIsMadeAgainUntilItAnswersAndOtherFailuresAreNotRetried() throws Exception {
        JevRetry retry = new JevRetry(properties(settings(1, 1, Duration.ofSeconds(60))), Clock.systemUTC());
        AtomicInteger tries = new AtomicInteger();
        String answer = retry.execute(retry.start(), () -> {
            if (tries.incrementAndGet() < 4) {
                throw new JevRetry.Communication(null);
            }
            return "answered";
        });
        assertEquals("answered", answer);
        assertEquals(4, tries.get(), "three failures in communication, then the answer");

        AtomicInteger other = new AtomicInteger();
        assertThrows(RetryException.class, () -> retry.execute(retry.start(), () -> {
            other.incrementAndGet();
            throw new IllegalStateException("not communication");
        }));
        assertEquals(1, other.get());
    }

    @Test
    void aWaitThatWouldPassTheDeadlineEndsTheTriesAtOnceAndTheLastFailureIsTheEndsLastException() {
        JevRetry retry = new JevRetry(properties(settings(5, 20, Duration.ofSeconds(1))), Clock.systemUTC());
        AtomicInteger tries = new AtomicInteger();
        JevRetry.Communication last = new JevRetry.Communication("3600");
        RetryException ended = assertThrows(RetryException.class, () -> retry.execute(retry.start(), () -> {
            tries.incrementAndGet();
            throw last;
        }));
        assertSame(last, ended.getLastException());
        assertEquals(1, tries.get(), "an hour asked for is past a one-second deadline: no second try (the pause is not slept)");
    }

    private static SennardProperties.Retry settings(long initialMs, long maxMs, Duration deadline) {
        return new SennardProperties.Retry(Duration.ofMillis(initialMs), 2.0, Duration.ofMillis(maxMs), deadline);
    }

    private static SennardProperties properties(SennardProperties.Retry retry) {
        return new SennardProperties(null, null, null, java.nio.file.Path.of("."), false, null, null, null, new SennardProperties.Jev(retry), null, null, null);
    }
}
