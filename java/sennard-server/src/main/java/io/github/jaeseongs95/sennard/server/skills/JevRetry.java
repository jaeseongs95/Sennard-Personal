// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.skills;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.retry.RetryException;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.core.retry.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.BackOffExecution;

import io.github.jaeseongs95.sennard.server.config.SennardProperties;

/**
 * How a provider call that failed in communication is tried again: Spring Framework's core retry ({@link RetryTemplate}) with one {@link RetryPolicy} of this class.
 * The standard policy builder cannot say what this needs (a pause the provider names with {@code Retry-After}, and no fixed number of tries), so the policy is written
 * against the standard interface: it retries only {@link Communication}, its {@link RetryPolicy#getTimeout() timeout} is what is left of the request's deadline (a new try
 * is not started, and a pause that would pass the deadline is not slept), and its back-off is the pause that grows ({@code sennard.jev.retry.*}) unless the failure named one.
 * The number of tries is therefore ended by the deadline and, in the code that calls this, by the budget: every try reserves and settles its own cost.
 */
@Component
class JevRetry {
    private static final Logger LOG = LoggerFactory.getLogger(JevRetry.class);
    private static final Duration SMALLEST = Duration.ofMillis(1);
    private static final Duration LONGEST_RETRY_AFTER = Duration.ofDays(365);
    private static final Pattern SECONDS = Pattern.compile("[0-9]{1,18}");

    /** A call that failed in communication and is to be tried again; {@code retryAfter} is the provider's {@code Retry-After} as it was sent, null when it sent none. */
    static class Communication extends Exception {
        private static final long serialVersionUID = 1L;
        private final String retryAfter;

        Communication(String retryAfter) {
            super("communication failure", null, false, false);
            this.retryAfter = retryAfter;
        }

        String retryAfter() {
            return retryAfter;
        }
    }

    private final SennardProperties.Retry settings;
    private final Clock clock;

    JevRetry(SennardProperties properties, Clock clock) {
        this.settings = properties.jev().retry();
        this.clock = clock;
    }

    /** The moment a classification starts; its deadline counts from it. */
    Instant start() {
        return clock.instant();
    }

    /**
     * Runs {@code call} and runs it again while it throws {@link Communication}, until the deadline of the request that started at {@code started}. A pause longer than what is
     * left is not slept: the {@link RetryException} that ends the run names the last {@link Communication} as its last exception. A try that is running is never cut short.
     */
    <R> R execute(Instant started, Retryable<R> call) throws RetryException {
        return new RetryTemplate(policy(started)).execute(call);
    }

    /**
     * The policy of the run of a request that started at {@code started}: its timeout is what is left of the deadline by this class's clock, and the pause it asks for after a
     * failure is the one {@code Retry-After} named, else the own growing pause. It is a method of its own so that what a run will wait and when it ends are read from the policy
     * (with a fixed clock) and not measured by waiting.
     */
    RetryPolicy policy(Instant started) {
        Duration left = settings.deadline().minus(Duration.between(started, clock.instant()));
        // A zero timeout would mean no limit; the call is made once and no new try is begun.
        return new CommunicationPolicy(left.compareTo(SMALLEST) < 0 ? SMALLEST : left);
    }

    /** The pause a {@code Retry-After} header asks for: seconds, or an HTTP date counted from {@code now}. Empty when the header is absent or not in either form. */
    static Optional<Duration> retryAfter(String header, Instant now) {
        if (header == null) {
            return Optional.empty();
        }
        String value = header.strip();
        if (SECONDS.matcher(value).matches()) {
            Duration asked = Duration.ofSeconds(Long.parseLong(value));
            return Optional.of(asked.compareTo(LONGEST_RETRY_AFTER) > 0 ? LONGEST_RETRY_AFTER : asked);
        }
        try {
            Duration until = Duration.between(now, ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
            return Optional.of(until.isNegative() ? Duration.ZERO : until.compareTo(LONGEST_RETRY_AFTER) > 0 ? LONGEST_RETRY_AFTER : until);
        } catch (DateTimeParseException notADate) {
            // The provider sent a Retry-After that is neither of the two forms: the caller takes this as "no pause named" and uses its own back-off, so the departure is told here.
            LOG.warn("The Retry-After of a provider is neither seconds nor an HTTP date; the own back-off is used", notADate);
            return Optional.empty();
        }
    }

    /** One run's policy: it keeps the last failure's {@code Retry-After}, as it was sent (null when none), for the back-off that is asked right after. */
    private final class CommunicationPolicy implements RetryPolicy {
        private final Duration timeout;
        private String told;

        CommunicationPolicy(Duration timeout) {
            this.timeout = timeout;
        }

        @Override
        public boolean shouldRetry(Throwable failure) {
            if (failure instanceof Communication communication) {
                told = communication.retryAfter();
                return true;
            }
            return false;
        }

        @Override
        public Duration getTimeout() {
            return timeout;
        }

        @Override
        public BackOff getBackOff() {
            return () -> new BackOffExecution() {
                private long pause = settings.initialDelay().toMillis();

                @Override
                public long nextBackOff() {
                    // The pause the provider named, if it named a usable one; only otherwise does the own pause grow.
                    return retryAfter(told, clock.instant()).map(Duration::toMillis).orElseGet(this::grown);
                }

                private long grown() {
                    long now = Math.min(pause, settings.maxDelay().toMillis());
                    pause = (long) Math.min(pause * settings.multiplier(), (double) settings.maxDelay().toMillis());
                    return now;
                }
            };
        }
    }
}
