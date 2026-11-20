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
 * The settings of the commands a test-engineering run starts ({@code sennard.run.*}), bound and validated as the context does through {@link SennardProperties}.
 *
 * <pre>
 * r1  default   without a setting at most 2 commands run at once and the output size is looked at every 25 ms
 * r2  binding   relaxed names; a duration with a unit and a plain number (milliseconds) bind
 * r3  validity  a limit below 1 or above 64 is refused; an interval of 0, a negative one or one below a millisecond is refused
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
    void withoutSettingsAtMostTwoCommandsRunAndTheOutputIsLookedAtEvery25Milliseconds() {
        SennardProperties.Run run = bind(Map.of()).run();
        assertEquals(2, run.maxConcurrent());
        assertEquals(Duration.ofMillis(25), run.outputCheckInterval());
    }

    @Test
    void relaxedNamesAndBothFormsOfADurationBind() {
        SennardProperties.Run unit = bind(Map.of("sennard.run.max-concurrent", "5", "sennard.run.output-check-interval", "1h")).run();
        assertEquals(5, unit.maxConcurrent());
        assertEquals(Duration.ofHours(1), unit.outputCheckInterval());
        SennardProperties.Run camel = bind(Map.of("sennard.run.maxConcurrent", "1", "sennard.run.outputCheckInterval", "100")).run();
        assertEquals(1, camel.maxConcurrent());
        assertEquals(Duration.ofMillis(100), camel.outputCheckInterval(), "a plain number is milliseconds");
    }

    @Test
    void aLimitOutsideOneToSixtyFourAndAnIntervalBelowAMillisecondAreRefused() {
        assertThrows(BindException.class, () -> bind(Map.of("sennard.run.max-concurrent", "0")));
        assertThrows(BindException.class, () -> bind(Map.of("sennard.run.max-concurrent", "65")));
        assertThrows(BindException.class, () -> bind(Map.of("sennard.run.output-check-interval", "soon")));
        for (String interval : new String[] {"0", "0ms", "-1", "-5ms", "-1s", "500us"}) {
            assertThrows(BindException.class, () -> bind(Map.of("sennard.run.output-check-interval", interval)), interval);
        }
        assertEquals(Duration.ofMillis(1), bind(Map.of("sennard.run.output-check-interval", "1ms")).run().outputCheckInterval(), "one millisecond is the smallest interval");
    }
}
