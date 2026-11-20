// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.attachmentcli;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.domain.error.UnavailableCode;
import io.github.jaeseongs95.sennard.domain.error.UnavailableException;
import io.github.jaeseongs95.sennard.service.store.StoreException;

/**
 * The entrance of {@code sennard attachments}: starts the command's own Spring Boot context ({@link AttachmentsConfiguration}, no web server), lets its runner call the one
 * {@link AttachmentsEntrance}, and closes the context, which ends the pool. The settings are read the way the server reads them: {@code application.yaml}, the environment and the
 * installation's {@code sennard.yaml}; so {@code sennard.attachments.directory} and {@code sennard.attachments.key-directory} are the installation's.
 *
 * <p>The clock is the application layer's own bean ({@code AppConfiguration}), not one the caller gives: the upkeep's judgements of time are the server's.
 */
public final class AttachmentsContext {
    /** The one logger whose lines the explicit purge writes as its audit of the deletion ({@code AttachmentMaintenance#purgeQuarantine}). */
    private static final String AUDITED_LOGGER = "io.github.jaeseongs95.sennard.app.attachment.AttachmentMaintenance";
    /** The audit log of the command, beside the server's log in the state folder and not in the server's file (the commands read the same {@code application.yaml}). */
    private static final String AUDIT_LOG_FILE = "${sennard.state-dir}/logs/sennard-attachments.log";
    /** H2's error code {@code DATABASE_ALREADY_OPEN_1} (90020), which a file locked by another process ends in: the settings database is always H2. */
    private static final int SETTINGS_DATABASE_ALREADY_OPEN = 90020;

    private AttachmentsContext() {
    }

    /**
     * Runs the request against the installation in {@code home}. A failure is an {@link SennardException} that says what to do: the attachment codes of the storage and the keys
     * (ARTIFACT_STORAGE_UNSAFE, ARTIFACT_KEY_MISSING, ARTIFACT_STORAGE_IO_ERROR), and COMMAND_NOT_STARTED when no business database is linked on the admin page, a running server holds
     * the settings database that keeps the link, the password file cannot be read or the database cannot be reached (the reason is in the sentence).
     *
     * @param request what to do
     * @param home the application folder of the installation ({@code sennard.home}: its {@code sennard.yaml} is read)
     * @param settings further settings in the form of command line arguments ({@code --sennard.state-dir=...}), which win over every other source; the tests give theirs here
     */
    // "try": the context is the resource that ends the pool; what the run produced is read from the holder the runner filled, not from the context.
    @SuppressWarnings("try")
    public static AttachmentsResult run(AttachmentsRequest request, Path home, String... settings) {
        AttachmentsConfiguration.Outcome outcome = new AttachmentsConfiguration.Outcome();
        // Boot's console logging writes to the standard output, which is this command's JSON answer, and a failure is told by the exception: the console is off and so is every logger.
        // A confirmed purge is the one run that logs on purpose: the names it deletes are its audit, written to the command's own file.
        // spring.main.keep-alive=false: application.yaml keeps the server's JVM alive, and this one ends with the command.
        List<String> arguments = new ArrayList<>(List.of("--spring.main.keep-alive=false", "--logging.level.root=OFF", "--logging.threshold.console=OFF"));
        if (request instanceof AttachmentsRequest.PurgeOrphans purge && purge.confirmed()) {
            arguments.add("--logging.level." + AUDITED_LOGGER + "=INFO");
            arguments.add("--logging.file.name=" + AUDIT_LOG_FILE);
        }
        arguments.addAll(List.of(settings));
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(AttachmentsConfiguration.class).web(WebApplicationType.NONE)
                .initializers(starting -> starting.addBeanFactoryPostProcessor(factory -> {
                    factory.registerSingleton("attachmentsOutcome", outcome);
                    factory.registerSingleton("attachmentsRequest", request);
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
            // The link is in the settings database (H2), whose file a running server holds: the command says to stop the server, it does not retry.
            if (cause instanceof SQLException sql && sql.getErrorCode() == SETTINGS_DATABASE_ALREADY_OPEN) {
                return new UnavailableException(UnavailableCode.COMMAND_NOT_STARTED, SennardException.args("command", AttachmentsConfiguration.COMMAND, "cause",
                        "The settings database is held by a running Sennard server. Run `sennard stop`, then this command again (a hook starts the server again at its next call)."), failure);
            }
            if (cause instanceof StoreException store) {
                return new UnavailableException(UnavailableCode.COMMAND_NOT_STARTED, SennardException.args("command", AttachmentsConfiguration.COMMAND, "cause", store.getMessage()), store);
            }
        }
        return failure;
    }
}
