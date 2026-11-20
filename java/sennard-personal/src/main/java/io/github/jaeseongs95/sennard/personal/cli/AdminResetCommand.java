// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.cli;

import java.nio.file.Path;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.github.jaeseongs95.sennard.admincli.AdminReset;
import io.github.jaeseongs95.sennard.admincli.AdminResetContext;
import io.github.jaeseongs95.sennard.personal.install.InstallEnvironment;
import io.github.jaeseongs95.sennard.personal.install.InstalledSettings;
import io.github.jaeseongs95.sennard.personal.install.ServerControl;
import io.github.jaeseongs95.sennard.service.store.modules.ContextLayerStore.Reset;

/**
 * {@code sennard admin-reset}: without {@code --yes} it only says what would be archived. Both runs open the settings database, which a running server holds (H2's file lock), so the
 * command first stops the server of this installation when one is up ({@link ServerControl#shutdown()}, the shutdown {@code sennard stop} uses), and says so. A hook starts the server
 * again at its next call.
 */
final class AdminResetCommand {
    /**
     * What the command prints. A confirmed run has {@code reset} and {@code archived}; one without {@code --yes} has {@code dryRun}, {@code wouldArchive} and
     * {@code effect}. A run that stopped the server of the installation also has {@code serverStopped} and {@code notice}, the catalog's sentence that says so.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Output(String command, Reset reset, AdminReset.Summary archived, Boolean dryRun, AdminReset.Summary wouldArchive, String effect, Boolean serverStopped, String notice) {
        static Output confirmed(AdminReset.Result result) {
            return new Output("admin-reset", result.reset(), result.summary(), null, null, null, null, null);
        }

        static Output dryRun(AdminReset.Result result) {
            return new Output("admin-reset", null, null, true, result.summary(), AdminReset.EFFECT, null, null);
        }

        Output stoppedServer(String sentence) {
            return new Output(command, reset, archived, dryRun, wouldArchive, effect, true, sentence);
        }
    }

    private AdminResetCommand() {
    }

    /**
     * Stops the server of the installation in {@code appDirectory} when it is up; nothing is stopped when it is not. {@link ServerControl#shutdown()} asks the server to end through its
     * own endpoint and returns when the server's process has ended (the address file names it), within its own limit; it does not retry. The port is closed before Spring Boot destroys the
     * beans and H2 releases its file lock, so the process's end is the sign that the settings database is free. If the
     * settings database is still held when the command opens it (a hook started the server again), the open ends with ADMIN_RESET_SETTINGS_LOCKED.
     *
     * @return whether a server was up and was stopped
     */
    private static boolean stopServer(Path appDirectory) {
        try (InstalledSettings settings = InstalledSettings.at(appDirectory)) {
            ServerControl server = new ServerControl(settings.serverAddressFile(), InstallEnvironment.current().environment());
            boolean up = server.listening();
            server.shutdown();
            return up;
        }
    }

    static int run(boolean confirmed, CliOutput out) {
        Path appDirectory = SennardCli.installer(out).activeVersion().folder();
        // Before the settings database is opened, by the preview as well as by the confirmed run: a server that is up holds its file.
        boolean stopped = stopServer(appDirectory);
        // The command's own Spring context (no web server) reads the installation's settings, opens the store the way the server does and runs the one use case in its transaction.
        AdminReset.Result result = AdminResetContext.run(confirmed, SennardCli.clock(), appDirectory);
        Output output = confirmed ? Output.confirmed(result) : Output.dryRun(result);
        out.json(stopped ? output.stoppedServer(out.sentence("cli.adminResetServerStopped")) : output);
        return 0;
    }
}
