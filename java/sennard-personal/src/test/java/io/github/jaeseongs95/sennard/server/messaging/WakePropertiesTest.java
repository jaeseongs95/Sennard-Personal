// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import io.github.jaeseongs95.sennard.server.config.SennardProperties;
import io.github.jaeseongs95.sennard.server.hook.HookRejection;
import io.github.jaeseongs95.sennard.server.hook.HookRejectionBody;
import tools.jackson.databind.json.JsonMapper;

/**
 * The key {@code sennard.hooks.wake-hold-ms} through the settings record ({@link SennardProperties.Hooks}) and not through {@code @Value}.
 *
 * <pre>
 * w1  not set        a day less a minute (the constant the registration test holds under the host's wait)
 * w2  0              no hold: every wait ends at once (the lifecycle test sets it)
 * w3  120000         a plain number is milliseconds
 * w4  1h             a unit is understood too
 * w5  not a duration the binding is an error, not a silent default
 * w6  the response   a body that does not wake says why, as {"reason":...}
 * </pre>
 */
class WakePropertiesTest {
    private static SennardProperties.Hooks bound(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties)).bindOrCreate("sennard", SennardProperties.class).hooks();
    }

    @Test
    void w1TheDefaultIsADayLessAMinute() {
        assertEquals(Duration.ofMillis(WakeController.DEFAULT_HOLD_MS), bound(Map.of()).wakeHoldMs());
        assertEquals(Duration.ofHours(24).minusMinutes(1), bound(Map.of()).wakeHoldMs());
    }

    @Test
    void w2ZeroIsNoHold() {
        assertEquals(Duration.ZERO, bound(Map.of("sennard.hooks.wake-hold-ms", "0")).wakeHoldMs());
    }

    @Test
    void w3APlainNumberIsMilliseconds() {
        assertEquals(Duration.ofSeconds(120), bound(Map.of("sennard.hooks.wake-hold-ms", "120000")).wakeHoldMs());
    }

    @Test
    void w4AUnitIsUnderstood() {
        assertEquals(Duration.ofHours(1), bound(Map.of("sennard.hooks.wake-hold-ms", "1h")).wakeHoldMs());
    }

    @Test
    void w5ANonDurationIsABindingError() {
        assertThrows(BindException.class, () -> bound(Map.of("sennard.hooks.wake-hold-ms", "soon")));
    }

    @Test
    void w6ARefusalToWakeSaysWhy() {
        assertEquals("{\"reason\":\"HOOK_WAKE_NO_MESSAGE\"}", JsonMapper.builder().build().writeValueAsString(new HookRejectionBody(HookRejection.HOOK_WAKE_NO_MESSAGE.name())));
    }
}
