// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.settings.h2;

import java.nio.file.Path;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Where the settings database is and how its pool is sized ({@code sennard.settings.*}).
 *
 * @param directory the folder of the settings database file ({@code sennard-settings.mv.db}), under the Sennard state folder; made when it is not there (the personal edition's
 *        {@code application.yaml} sets it from {@code sennard.state-dir})
 * @param pool the pool of the settings database
 */
@ConfigurationProperties("sennard.settings")
@Validated
public record SettingsProperties(@NotNull Path directory, @DefaultValue @Valid Pool pool) {
    /**
     * @param name the pool's name in logs and metrics
     * @param maximumSize the most connections the pool opens
     * @param minimumIdle the connections the pool keeps open: at least one, so the database stays open for the pool's life and a new connection is made against an open
     *        database
     * @param connectionTimeout how long a caller waits for a connection
     */
    public record Pool(@DefaultValue("sennard-settings") @NotNull String name, @DefaultValue("4") @Min(1) int maximumSize, @DefaultValue("1") @Min(1) int minimumIdle,
            @DefaultValue("5s") @NotNull Duration connectionTimeout) {
    }
}
