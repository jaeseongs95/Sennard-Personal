// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.validation.ValidationBindHandler;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import io.github.jaeseongs95.sennard.server.config.SennardProperties;

/**
 * The settings of the programs an execution starts ({@code sennard.run.*}), bound and validated as the context does through {@link SennardProperties}.
 *
 * <pre>
 * r1  default   without a setting at most 8 programs run at once and the runner keeps the last 32 finished executions; the stop has 15 seconds, a look at a working folder takes at
 *               most 50000 entries and 5 seconds, and all the observations of the server hold 100000 entries together
 * r2  binding   relaxed names bind
 * r3  validity  a concurrency limit below 1 or above 64 is refused; a retained count below 1 or above 1024 is refused
 * r4  the stop  a stop deadline above 20 seconds is refused (the command `sennard stop` gives up after 30, and the rest of the stop takes its part), and 20 seconds is not
 * r5  the total the entries that all the observations hold are at least the entries of one look: a total below the bound of a look is refused at the start, equal to it is not
 * </pre>
 */
class SennardPropertiesRunTest {
    private static SennardProperties bind(Map<String, String> settings) {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        Map<String, String> withHosts = new java.util.HashMap<>(settings); // the hosts folders are required (application.yaml sets them); this test is about sennard.run
        withHosts.put("sennard.hosts.claude-config-dir", "");
        withHosts.put("sennard.hosts.user-home", "home");
        return new Binder(new MapConfigurationPropertySource(withHosts)).bindOrCreate("sennard", Bindable.of(SennardProperties.class), new ValidationBindHandler(validator));
    }

    @Test
    void withoutSettingsAtMostEightProgramsRunAndThe32LastFinishedExecutionsAreKept() {
        SennardProperties.Run run = bind(Map.of()).run();
        assertEquals(8, run.maxConcurrent());
        assertEquals(32, run.retainedFinished());
    }

    @Test
    void withoutSettingsTheStopHas15SecondsALookAtAWorkingFolderTakesAtMost50000EntriesAndFiveSecondsAndAllTheObservationsHold100000EntriesTogether() {
        SennardProperties.Run run = bind(Map.of()).run();
        assertEquals(Duration.ofSeconds(15), run.stopDeadline());
        assertEquals(50_000L, run.observationMaxEntries());
        assertEquals(Duration.ofSeconds(5), run.observationMaxTime());
        assertEquals(100_000L, run.observationMaxTotalEntries());
    }

    @Test
    void theStopAndObservationSettingsBindAndAreValidated() {
        SennardProperties.Run bound = bind(Map.of("sennard.run.stop-deadline", "3s", "sennard.run.observation-max-entries", "7", "sennard.run.observation-max-time", "250ms",
                "sennard.run.observation-max-total-entries", "9")).run();
        assertEquals(Duration.ofSeconds(3), bound.stopDeadline());
        assertEquals(7L, bound.observationMaxEntries());
        assertEquals(Duration.ofMillis(250), bound.observationMaxTime());
        assertEquals(9L, bound.observationMaxTotalEntries());
        assertThrows(BindException.class, () -> bind(Map.of("sennard.run.stop-deadline", "0s")));
        assertThrows(BindException.class, () -> bind(Map.of("sennard.run.observation-max-entries", "0")));
        assertThrows(BindException.class, () -> bind(Map.of("sennard.run.observation-max-time", "0s")));
        assertThrows(BindException.class, () -> bind(Map.of("sennard.run.observation-max-total-entries", "0")));
    }

    @Test
    void theTotalOfTheObservationsBelowTheBoundOfOneLookIsRefusedAndEqualToItIsNot() {
        assertThrows(BindException.class, () -> bind(Map.of("sennard.run.observation-max-entries", "8", "sennard.run.observation-max-total-entries", "7")));
        assertThrows(BindException.class, () -> bind(Map.of("sennard.run.observation-max-total-entries", "49999")), "the default bound of a look is 50000");
        assertThrows(BindException.class, () -> bind(Map.of("sennard.run.observation-max-entries", "100001")), "and the default total is 100000");
        SennardProperties.Run equal = bind(Map.of("sennard.run.observation-max-entries", "7", "sennard.run.observation-max-total-entries", "7")).run();
        assertEquals(7L, equal.observationMaxEntries());
        assertEquals(7L, equal.observationMaxTotalEntries());
    }

    @Test
    void aStopDeadlineAbove20SecondsIsRefusedBecauseTheCommandThatStopsTheServerGivesUpAfter30() {
        assertEquals(Duration.ofSeconds(20), bind(Map.of("sennard.run.stop-deadline", "20s")).run().stopDeadline(), "20 seconds leave 10 for the rest of the stop of the server");
        assertThrows(BindException.class, () -> bind(Map.of("sennard.run.stop-deadline", "20001ms")));
        assertThrows(BindException.class, () -> bind(Map.of("sennard.run.stop-deadline", "21s")));
        assertThrows(BindException.class, () -> bind(Map.of("sennard.run.stop-deadline", "30s")), "the whole of what the command waits for");
        assertThrows(BindException.class, () -> bind(Map.of("sennard.run.stop-deadline", "1d")));
    }

    @Test
    void relaxedNamesBind() {
        SennardProperties.Run dashed = bind(Map.of("sennard.run.max-concurrent", "5", "sennard.run.retained-finished", "7")).run();
        assertEquals(5, dashed.maxConcurrent());
        assertEquals(7, dashed.retainedFinished());
        SennardProperties.Run camel = bind(Map.of("sennard.run.maxConcurrent", "1", "sennard.run.retainedFinished", "1024")).run();
        assertEquals(1, camel.maxConcurrent());
        assertEquals(1024, camel.retainedFinished());
    }

    @Test
    void aLimitOutsideItsRangeIsRefused() {
        assertThrows(BindException.class, () -> bind(Map.of("sennard.run.max-concurrent", "0")));
        assertThrows(BindException.class, () -> bind(Map.of("sennard.run.max-concurrent", "65")));
        assertThrows(BindException.class, () -> bind(Map.of("sennard.run.retained-finished", "0")));
        assertThrows(BindException.class, () -> bind(Map.of("sennard.run.retained-finished", "1025")));
    }
}
