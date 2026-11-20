// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.cli;

import java.awt.GraphicsEnvironment;
import java.io.File;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.boot.system.ApplicationHome;

import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.domain.error.InputCode;
import io.github.jaeseongs95.sennard.domain.error.InvalidInputException;
import io.github.jaeseongs95.sennard.personal.install.InstallEnvironment;
import io.github.jaeseongs95.sennard.personal.install.InstalledSettings;
import io.github.jaeseongs95.sennard.personal.install.Installer;
import io.github.jaeseongs95.sennard.personal.install.RunningProgram;
import io.github.jaeseongs95.sennard.personal.install.ServerControl;
import io.github.jaeseongs95.sennard.personal.setup.SetupWindow;
import io.github.jaeseongs95.sennard.personal.wake.NativeWakeWorker;
import io.github.jaeseongs95.sennard.protocol.Utf8Boundary;
import io.github.jaeseongs95.sennard.hookclient.HookClientCall;
import io.github.jaeseongs95.sennard.hookclient.NativeWakeProtocol;
import io.github.jaeseongs95.sennard.service.store.StoreException;

/**
 * The user's commands, run rarely by a person: one JVM per command is fine here and nowhere else. A failure prints
 * {@code CODE: what to do} on stderr and exits 1; a command line that cannot be understood prints why and the usage on stderr and exits 2.
 */
public final class SennardCli {
    private static final int USAGE_ERROR = 2;

    private SennardCli() {
    }

    /** Runs one parsed command and returns its exit code. */
    @FunctionalInterface
    interface CommandExecutor {
        int run(CliRequest.Run request);
    }

    public static void main(String[] args) {
        if (args.length > 0 && NativeWakeProtocol.COMMAND.equals(args[0])) {
            System.exit(NativeWakeWorker.run(args, System.in, InstallEnvironment.current().environment()));
        }
        Utf8Boundary.Streams streams = Utf8Boundary.fixStandardStreams();
        CliOutput output = new CliOutput(streams.out(), streams.err(), System.in);
        int exit = execute(args, output, request -> run(request, output), SennardCli::installedCommand);
        output.flush();
        System.exit(exit);
    }

    /** Everything {@code main} does except the exit; an exception that is not an {@link SennardException} goes on to the JVM. */
    static int execute(String[] args, CliOutput output, CommandExecutor executor, Supplier<Optional<String>> installed) {
        return switch (CliParser.parse(args)) {
            case CliRequest.Help help -> {
                output.result(CliUsage.of(help.command()));
                yield 0;
            }
            case CliRequest.Version version -> {
                output.result("sennard " + version());
                yield 0;
            }
            case CliRequest.UsageError error -> {
                output.error(error.message());
                output.error(CliUsage.of(error.command()));
                yield USAGE_ERROR;
            }
            case CliRequest.Run request -> {
                try {
                    yield executor.run(request);
                } catch (SennardException known) {
                    output.failure(known);
                    try {
                        installed.get().ifPresent(command -> output.error("Run Sennard commands with: " + command));
                    } catch (SennardException | StoreException hintFailed) {
                        // The hint is a courtesy to a command that already failed: its own failure is said, not hidden, and does not replace the first.
                        output.hintUnavailable(hintFailed);
                    }
                    yield 1;
                }
            }
        };
    }

    /** The switch has no default: a command added to {@link SennardCommand} does not compile until it is handled here. */
    static int run(CliRequest.Run request, CliOutput output) {
        return switch (request.command()) {
            case SETUP -> {
                if (GraphicsEnvironment.isHeadless()) {
                    output.error("Setup needs a graphical desktop. Use 'install', 'upgrade', or 'status' at the command line.");
                    yield 1;
                }
                SetupWindow.showAndWait(InstallEnvironment.current(), runningJar(), version(),
                        failure -> failure.code().name() + ": " + output.sentences().of(failure));
                yield 0;
            }
            case INSTALL -> {
                installer(output).install(request.valueOrNull("--state-dir"));
                yield 0;
            }
            case UNINSTALL -> {
                installer(output).uninstall();
                yield 0;
            }
            case START -> {
                // With --within the start is a host's session start (HookClientCall#START_WITHIN): the hook client and the MCP relay run it when they find no server.
                if (request.has(HookClientCall.START_WITHIN)) {
                    installer(output).startForSession(Duration.ofSeconds(number(request, HookClientCall.START_WITHIN, 0)));
                } else {
                    installer(output).start();
                }
                yield 0;
            }
            case STOP -> {
                installer(output).stop();
                yield 0;
            }
            case STATUS -> {
                output.prettyJson(installer(output).status());
                yield 0;
            }
            case UPGRADE -> {
                installer(output).upgrade();
                yield 0;
            }
            case PREPARE -> {
                try (InstalledSettings settings = InstalledSettings.at(installer(output).activeVersion().folder())) {
                    new ServerControl(settings.serverAddressFile(), InstallEnvironment.current().environment()).retryPreparation();
                }
                yield 0;
            }
            case HOOKS -> {
                installer(output).hooks();
                yield 0;
            }
            case ADMIN_RESET -> AdminResetCommand.run(request.has("--yes"), output);
            case ATTACHMENTS -> AttachmentsCommand.run(request, output);
        };
    }

    /** The messages say {@code sennard <command>}, which is not on PATH; this is what to run once there is an installation. */
    private static Optional<String> installedCommand() {
        return Installer.installedCommand(InstallEnvironment.current(), runningJar());
    }

    public static String version() {
        String version = SennardCli.class.getPackage().getImplementationVersion();
        return version == null ? "unknown" : version;
    }

    /**
     * The release jar this process runs from ({@code java -jar sennard-<version>.jar}, or the application jar of an installed version); anything else when it runs from classes in a
     * folder, which is not a release and so has nothing to install. Spring Boot reports the outer jar for a class inside one of its nested jars.
     */
    static RunningProgram runningJar() {
        return Optional.ofNullable(new ApplicationHome(SennardCli.class).getSource()).map(File::toPath).map(RunningProgram::fromJar).orElseGet(RunningProgram::elsewhere);
    }

    /** The installer: it installs from the jar this JVM runs from, and works on the installation that jar belongs to when it is a version of one. */
    static Installer installer(CliOutput output) {
        return new Installer(InstallEnvironment.current(), runningJar(), version(), output.stream());
    }

    private static int number(CliRequest.Run request, String option, int fallback) {
        return request.value(option).map(text -> {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException notANumber) {
                throw new InvalidInputException(InputCode.HOOK_OPTION_INVALID, SennardException.args("problem", "notANumber", "option", option, "value", text));
            }
        }).orElse(fallback);
    }

    /**
     * The command's clock. The CLI is a process of its own that has no application context (a command that starts one, the admin reset, is given this clock and registers
     * it as the context's {@code Clock} bean), so this entry point is the one place that process makes its UTC clock (the server's is the {@code Clock} bean of AppConfiguration);
     * every command receives it as an argument.
     */
    static Clock clock() {
        return Clock.systemUTC();
    }
}
