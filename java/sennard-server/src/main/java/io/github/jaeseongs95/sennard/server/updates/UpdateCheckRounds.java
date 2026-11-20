// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.updates;

import java.time.Duration;

import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.IntervalTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.server.config.SennardProperties;

/**
 * When the update check runs on its own: one round after the start and one after the end of each round, {@code sennard.updates.poll-interval} apart. The round is registered with the
 * interval as the {@link Duration} the settings record binds (an {@code @Scheduled} placeholder string would need the key and its default written in the code), and the work of a
 * round is {@link UpdateCheckSchedule#whenDue}. A class of its own because {@code UpdateCheckSchedule} has an {@code @Async} method, and an interface on it would make its
 * proxy an interface proxy that the tools, which take the class, cannot be given.
 */
@Component
public class UpdateCheckRounds implements SchedulingConfigurer {
    private final UpdateCheckSchedule schedule;
    private final Duration pollInterval;

    public UpdateCheckRounds(UpdateCheckSchedule schedule, SennardProperties properties) {
        this.schedule = schedule;
        this.pollInterval = properties.updates().pollInterval();
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(new IntervalTask(schedule::whenDue, pollInterval, pollInterval));
    }
}
