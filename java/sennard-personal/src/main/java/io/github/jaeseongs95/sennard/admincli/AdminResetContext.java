// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.admincli;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.domain.error.GateCode;
import io.github.jaeseongs95.sennard.domain.error.GateRefusedException;
import io.github.jaeseongs95.sennard.domain.error.UnavailableCode;
import io.github.jaeseongs95.sennard.domain.error.UnavailableException;
import io.github.jaeseongs95.sennard.service.store.StoreException;

/**
 * The entrance of {@code sennard admin-reset}: starts the command's own Spring Boot context ({@link AdminResetConfiguration}, no web server), lets its runner call the one
 * {@link AdminReset} use case, and closes the context, which ends the pool. The settings are read the way the server reads them: {@code application.yaml}, the environment and the
 * installation's {@code sennard.yaml}.
 */
public final class AdminResetContext {
    /** H2's error code {@code DATABASE_ALREADY_OPEN_1} (90020), which a file locked by another process ends in: the settings database is always H2. */
    private static final int SETTINGS_DATABASE_ALREADY_OPEN = 90020;

    private AdminResetContext() {
    }

    /**
     * Runs the reset against the installation in {@code home}. Nothing is written unless {@code confirmed}. Which reset runs is the state of the section of the signed log before the
     * link: open (the business database was never ready), the log is in the settings database alone and the business database is not touched ({@link AdminResetBeforeLink}); closed,
     * the business database's log is archived too ({@link AdminReset}). A failure is an {@link SennardException} that says what to do: ADMIN_RESET_NOT_PERMITTED when the password file
     * of the database account cannot be read (this OS user is not the installation's; the closed case), ADMIN_RESET_DATABASE_UNREACHABLE when the closed case finds no database set
     * or cannot reach it, ADMIN_RESET_SETTINGS_LOCKED when another process holds the settings database (the command stopped the server before it, and the server is still shutting down after its port closed, or a hook started it again; the
     * command does not retry, the caller runs it again), ADMIN_RESET_FAILED for the rest.
     *
     * @param home the application folder of the installation ({@code sennard.home}: its {@code sennard.yaml} is read)
     * @param clock the clock of the process that runs the command (the context has none: the server's is a bean of its own context)
     * @param settings further settings in the form of command line arguments ({@code --sennard.settings.directory=...}), which win over every other source; the tests give theirs here
     */
    // "try": the context is the resource that ends the pool; what the run produced is read from the holder the runner filled, not from the context.
    @SuppressWarnings("try")
    public static AdminReset.Result run(boolean confirmed, Clock clock, Path home, String... settings) {
        AdminResetConfiguration.Outcome outcome = new AdminResetConfiguration.Outcome();
        // Boot's console logging writes to the standard output, which is this command's JSON answer, and a failure is told by the exception: the log is off.
        // spring.main.keep-alive=false: application.yaml keeps the server's JVM alive, and this one ends with the command.
        List<String> arguments = new ArrayList<>(List.of("--spring.main.keep-alive=false", "--logging.level.root=OFF"));
        if (confirmed) {
            arguments.add("--" + AdminResetConfiguration.CONFIRMED_OPTION);
        }
        arguments.addAll(List.of(settings));
        // Lazily: the business database's pool is made only when the run needs the database (the section of the log before the link is closed), so a reset before the first link works
        // when no business database is set or it cannot be reached.
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(AdminResetConfiguration.class).web(WebApplicationType.NONE).lazyInitialization(true)
                .initializers(starting -> starting.addBeanFactoryPostProcessor(factory -> {
                    factory.registerSingleton("adminResetOutcome", outcome);
                    factory.registerSingleton("clock", clock);
                })).properties("sennard.home=" + home).logStartupInfo(false).run(arguments.toArray(String[]::new))) {
            return outcome.delivered();
        } catch (RuntimeException failure) {
            throw translated(failure);
        }
    }

    /** The reason a failure of the start or of the run has, wherever in the chain of causes Spring put it. */
    private static RuntimeException translated(RuntimeException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SennardException reason) {
                return reason;
            }
            // The one place the settings database's file lock becomes a code of the command: it is not swallowed, and it is not retried.
            if (cause instanceof SQLException sql && sql.getErrorCode() == SETTINGS_DATABASE_ALREADY_OPEN) {
                return new UnavailableException(UnavailableCode.ADMIN_RESET_SETTINGS_LOCKED, Map.of(), failure);
            }
            if (cause instanceof StoreException store) {
                return reasonOf(store);
            }
        }
        return failure;
    }

    private static SennardException reasonOf(StoreException failure) {
        return switch (failure.code()) {
            case DB_CREDENTIAL_UNREADABLE -> new GateRefusedException(GateCode.ADMIN_RESET_NOT_PERMITTED, Map.of(), failure);
            case STORE_UNREACHABLE -> new UnavailableException(UnavailableCode.ADMIN_RESET_DATABASE_UNREACHABLE, SennardException.args("cause", failure.getMessage()), failure);
            default -> new UnavailableException(UnavailableCode.ADMIN_RESET_FAILED, SennardException.args("cause", failure.getMessage()), failure);
        };
    }
}
