// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * The personal edition's settings. {@code sennard.state-dir} comes from {@code application.yaml} (the user's profile), from {@code SENNARD_STATE_DIR}
 * or from {@code sennard.yaml}: never from the command line, which loses characters outside the Windows ANSI code page.
 *
 * @param stateDir the per-user state directory
 * @param home the application folder, the one beside the jar ({@code sennard.home}: the launcher's configuration and the JVM settings file are there)
 * @param store what the server does with its store when it starts
 */
@ConfigurationProperties("sennard")
@Validated
public record PersonalProperties(@NotNull Path stateDir, @DefaultValue(".") Path home, @DefaultValue @Valid Store store) {
    /**
     * The business database is not a setting of this record: its one source is the link the user signed on the admin page, which the settings database in the state folder keeps
     * ({@code DatabaseLinkSettings}). A {@code sennard.store.database.*} key in {@code sennard.yaml} is no source and is not read.
     *
     * @param prepare false for an installation told not to prepare its store ({@code sennard.store.prepare})
     * @param isolation the isolation level of the store's transactions ({@code sennard.store.isolation}), Spring's {@link Isolation}: this edition's setting, READ_COMMITTED (one user, so
     *     the concurrency is left to the database's row locks and the constraints). A hosted edition has its own setting in its own settings file
     */
    public record Store(@DefaultValue("true") boolean prepare, @DefaultValue("READ_COMMITTED") @NotNull Isolation isolation) {
    }
}
