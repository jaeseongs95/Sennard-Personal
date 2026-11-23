// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.install;

import java.io.IOException;
import java.io.PrintStream;
import java.lang.module.ModuleDescriptor.Version;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Stream;

import org.springframework.util.FileSystemUtils;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.github.jaeseongs95.sennard.domain.error.ConflictCode;
import io.github.jaeseongs95.sennard.domain.error.ConflictException;
import io.github.jaeseongs95.sennard.domain.error.GateCode;
import io.github.jaeseongs95.sennard.domain.error.GateRefusedException;
import io.github.jaeseongs95.sennard.domain.error.InputCode;
import io.github.jaeseongs95.sennard.domain.error.IntegrityCode;
import io.github.jaeseongs95.sennard.domain.error.IntegrityException;
import io.github.jaeseongs95.sennard.domain.error.InvalidInputException;
import io.github.jaeseongs95.sennard.domain.error.NotFoundCode;
import io.github.jaeseongs95.sennard.domain.error.NotFoundException;
import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.domain.error.UnavailableCode;
import io.github.jaeseongs95.sennard.domain.error.UnavailableException;
import io.github.jaeseongs95.sennard.domain.jvm.JvmPreset;
import io.github.jaeseongs95.sennard.domain.jvm.JvmPresets;
import io.github.jaeseongs95.sennard.domain.jvm.MemoryEstimate;
import io.github.jaeseongs95.sennard.hookclient.ServerAddress;
import io.github.jaeseongs95.sennard.personal.SennardPersonalApplication;
import io.github.jaeseongs95.sennard.server.hook.HookInstallation;
import io.github.jaeseongs95.sennard.server.updates.HostPackageStatus.Mismatch;
import io.github.jaeseongs95.sennard.server.workflow.HostIdentity;
import io.github.jaeseongs95.sennard.service.secret.PrivateStorage;
import io.github.jaeseongs95.sennard.service.store.StoreCode;
import io.github.jaeseongs95.sennard.service.store.pg.StorePhase;

/**
 * {@code sennard install}, {@code uninstall}, {@code start}, {@code stop}, {@code upgrade} and {@code status}. There is no registration with the OS: the server is started by this class
 * from the installed version's jar, and by the session-start client of a host ({@code java -jar <installed jar> start --within=...}). The commands run from whichever release jar the user
 * executes, which is the one thing Sennard is shipped as; what they install is that jar extracted ({@link ReleaseJar}) into {@code <installation>/versions/<version>/}.
 */
public final class Installer {
    /** The server's first start with a store that still has to be downloaded and initialised. */
    private static final Duration HEALTHY = Duration.ofMinutes(2);
    private static final Duration STORE_READY = Duration.ofMinutes(10);
    private static final Duration TRAINING = Duration.ofMinutes(3);
    /** One readiness request of a session start: short, so that something on the port that never answers does not use up the hook's time. */
    private static final Duration SESSION_ANSWER = Duration.ofSeconds(2);
    private static final String HOST_PACKAGES = "host-packages";
    private static final String UPGRADE_PENDING = ".upgrade-pending";
    private static final String RETIREMENT_PENDING = ".upgrade-retirement-pending";

    /**
     * What {@code sennard status} prints, as one JSON document.
     *
     * @param command the command of the newest installed version, which is what to run Sennard commands with
     * @param jvmPreset the saved JVM preset
     * @param port the server's port, healthy, store and admin page: only while a server of this installation runs, and the last two only while it answers
     * @param adminPage where the admin page is listening now (its port is the operating system's choice at each start of the server, as the server's own is)
     * @param hostCopiesWithoutDefinitions the host plugin files that hold the shipped, empty definition: the host updated its copy of the plugin after the definitions were written, and
     *     {@code hooks} writes them again
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Status(String installRoot, String command, List<String> versions, JvmPreset jvmPreset, Integer port, Boolean healthy, StoreStatus store, String adminPage,
            List<String> hostCopiesWithoutDefinitions, List<Mismatch> hostPackageMismatches) {
        public Status(String installRoot, String command, List<String> versions, JvmPreset jvmPreset, Integer port, Boolean healthy, StoreStatus store, String adminPage,
                List<String> hostCopiesWithoutDefinitions) {
            this(installRoot, command, versions, jvmPreset, port, healthy, store, adminPage, hostCopiesWithoutDefinitions, null);
        }

        public boolean hasHostPackageMismatch() {
            return hostPackageMismatches != null && !hostPackageMismatches.isEmpty();
        }

        Status withHostPackageMismatches(List<Mismatch> mismatches) {
            return new Status(installRoot, command, versions, jvmPreset, port, false, store, adminPage, hostCopiesWithoutDefinitions, mismatches);
        }

        Status withPort(int serverPort) {
            return new Status(installRoot, command, versions, jvmPreset, serverPort, healthy, store, adminPage, hostCopiesWithoutDefinitions, hostPackageMismatches);
        }

        Status withPreset(JvmPreset preset) {
            return new Status(installRoot, command, versions, preset, port, healthy, store, adminPage, hostCopiesWithoutDefinitions, hostPackageMismatches);
        }

        Status withStore(StoreStatus status) {
            return new Status(installRoot, command, versions, jvmPreset, port, healthy, status, adminPage, hostCopiesWithoutDefinitions, hostPackageMismatches);
        }

        Status withAdminPage(String address) {
            return new Status(installRoot, command, versions, jvmPreset, port, healthy, store, address, hostCopiesWithoutDefinitions, hostPackageMismatches);
        }

        Status withEmptyCopies(List<String> copies) {
            return new Status(installRoot, command, versions, jvmPreset, port, healthy, store, adminPage, copies.isEmpty() ? null : copies, hostPackageMismatches);
        }
    }

    private final InstallEnvironment environment;
    private static final Runnable KEEP_CLAUDE_SETTINGS = () -> {
    };

    private final RunningProgram running;
    private final String version;
    private final PrintStream out;
    private final ClaudeStopConfirmation claudeStopConfirmation;

    /** @param running what this command runs from: a release jar (an installed version's jar included) or anything else */
    public Installer(InstallEnvironment environment, RunningProgram running, String version, PrintStream out) {
        this(environment, running, version, out, ClaudeStopConfirmation.interactive());
    }

    Installer(InstallEnvironment environment, RunningProgram running, String version, PrintStream out, ClaudeStopConfirmation claudeStopConfirmation) {
        this.environment = environment;
        this.running = running;
        this.version = version;
        this.out = out;
        this.claudeStopConfirmation = claudeStopConfirmation;
    }

    /**
     * The folder the commands work in: the installation the running jar is a version of ({@code <root>/versions/<version>/sennard-<version>.jar}), otherwise the environment's own.
     * A jar that was installed belongs to its installation wherever {@code SENNARD_INSTALL_ROOT} points.
     */
    private Path root() {
        return rootOf(environment, running);
    }

    private static Path rootOf(InstallEnvironment environment, RunningProgram running) {
        return running.jar().flatMap(InstalledVersion::ofJar).flatMap(InstalledVersion::installRoot).map(environment::checked).orElseGet(environment::installRoot);
    }

    public void install(String givenStateDirectory) {
        Path root = root();
        requireFreshInstall(root);
        String stateDirectory = givenStateDirectory == null ? null : checkedStateDirectory(givenStateDirectory, Path.of("").toAbsolutePath());
        Path versions = root.resolve(InstalledVersion.VERSIONS);
        preflightHostPackages(versions.resolve(version), version);
        Runnable applyClaudeSettings = prepareClaudeSettings();
        ReleaseJar release = release();
        VersionRetirement.withLock(versions, () -> install(root, stateDirectory, release, applyClaudeSettings));
    }

    private static void requireFreshInstall(Path root) {
        if (!installedVersions(root).isEmpty()) {
            throw new ConflictException(ConflictCode.ALREADY_INSTALLED, SennardException.args("root", root));
        }
    }

    private void install(Path root, String stateDirectory, ReleaseJar release, Runnable applyClaudeSettings) {
        requireFreshInstall(root);
        preflightHostPackages(root.resolve(InstalledVersion.VERSIONS).resolve(version), version);
        InstalledVersion installed = publish(root, release, null);
        if (stateDirectory != null) {
            writeStateDirectory(installed, stateDirectory);
        }
        JvmPreset preset = savedOrChosenPreset(installed, true);
        Path java = environment.java().path();
        train(installed, preset, java);
        try (InstalledSettings settings = InstalledSettings.at(installed.folder())) {
            ServerControl server = new ServerControl(settings.serverAddressFile(), environment.environment());
            startServer(installed, preset, java, server);
            awaitStore(settings, server);
            ensureHostIdentity(settings.stateDirectory(), environment.privateStorage());
            applyClaudeSettings.run();
            HostFiles.Placement placement = placeHostFiles(settings, root, installed, java);
            out.println("Installed version " + version + " in " + root + ".");
            out.println("State folder: " + settings.stateDirectory());
            out.println("Run Sennard commands with: " + command(installed));
            placement.requireReached();
        }
    }

    /**
     * The state folder the server will use, after both checks of the encoding: the text as it was given, and the absolute path it becomes. A relative path takes the
     * folder {@code workingDirectory} (the folder this command runs in) in front of it, so a folder that cannot be passed to a program makes the result refused although the text
     * the user typed is plain ASCII. The folder is a parameter so that the check is tested without running in such a folder.
     *
     * @throws InvalidInputException STATE_DIR_NOT_REPRESENTABLE
     */
    String checkedStateDirectory(String givenStateDirectory, Path workingDirectory) {
        Path given;
        try {
            given = Path.of(givenStateDirectory);
        } catch (InvalidPathException unusable) {
            // The text is the user's input: one that is no path at all (the JDK has no check of a path's syntax that does not throw) is refused like one the encoding cannot carry, with the cause kept.
            throw new InvalidInputException(InputCode.STATE_DIR_NOT_REPRESENTABLE, Map.of(), Map.of(), unusable);
        }
        if (!environment.representable(given)) {
            throw stateDirectoryNotRepresentable();
        }
        Path absolute = workingDirectory.resolve(given).toAbsolutePath().normalize();
        if (!environment.representable(absolute)) {
            throw stateDirectoryNotRepresentable();
        }
        return absolute.toString();
    }

    private static InvalidInputException stateDirectoryNotRepresentable() {
        return new InvalidInputException(InputCode.STATE_DIR_NOT_REPRESENTABLE);
    }

    /**
     * The state folder as the server will use it. A relative path is taken from the folder this command runs in: the server resolves sennard.state-dir against
     * its own working folder, which is not necessarily this one.
     */
    static String absoluteStateDirectory(String stateDirectory) {
        return Path.of(stateDirectory).toAbsolutePath().normalize().toString();
    }

    /**
     * Writes the definitions again, after a host installed or updated its copy of the plugin (the host's copy holds the shipped, empty definitions then). The hook client's AOT cache is
     * made again too: it belongs to the {@code java} the definitions name, which is this command's.
     */
    public void hooks() {
        InstalledVersion installed = activeVersion();
        try (InstalledSettings settings = InstalledSettings.at(installed.folder())) {
            hostFiles(settings, installed).requireMatchingVersions(environment);
            Runnable applyClaudeSettings = prepareClaudeSettings();
            Path root = root();
            VersionRetirement.withLock(root.resolve(InstalledVersion.VERSIONS), () -> {
                if (!installed.equals(activeVersion())) {
                    throw new UnavailableException(UnavailableCode.INSTALL_FAILED, SennardException.args("what", "unreadable", "file", installed.folder()));
                }
                hostFiles(settings, installed).requireMatchingVersions(environment);
                Path java = environment.java().path();
                new CommandAotCache(environment.commands(), out).make(installed, java);
                applyClaudeSettings.run();
                placeHostFiles(settings, root, installed, java).requireReached();
            });
        }
    }

    private Runnable prepareClaudeSettings() {
        return ClaudeStopSettings.prepare(environment, claudeStopConfirmation)
                .<Runnable>map(change -> () -> applyClaudeSettings(change))
                .orElse(KEEP_CLAUDE_SETTINGS);
    }

    private void applyClaudeSettings(ClaudeStopSettings.Change change) {
        change.apply();
        out.println("Set env." + ClaudeStopSettings.KEY + " to \"0\" in " + change.file() + ".");
    }

    /**
     * Writes the definitions and prints what was done to each file.
     *
     * @param installed the installed version whose server the hooks reach and whose jars the definitions run
     * @return the placement: a command that is finished otherwise ends with {@link HostFiles.Placement#requireReached()}, so that a host that reads a definition this installation cannot write fails it
     */
    private HostFiles.Placement placeHostFiles(InstalledSettings settings, Path root, InstalledVersion installed, Path java) {
        Path serverFile = settings.serverAddressFile();
        HookInstallation installation = HookClientCommand.installation(java, installed, serverFile);
        try {
            VersionRetirement.protect(installed);
        } catch (IOException cause) {
            throw new UnavailableException(UnavailableCode.HOST_FILES_FAILED, SennardException.args("cause", cause.toString()), cause);
        }
        HostFiles.Placement placement = hostFiles(settings, installed)
                .place(settings.hostAdapters(), environment, installation, hostPackages(root));
        placement.report().forEach(out::println);
        return placement;
    }

    private static HostFiles hostFiles(InstalledSettings settings, InstalledVersion installed) {
        return new HostFiles(settings.hostLocations(), installed.version(), command(installed) + " hooks", settings.jsonMapper());
    }

    private void preflightHostPackages(Path folder, String expected) {
        try (InstalledSettings settings = InstalledSettings.quietlyAt(folder)) {
            new HostFiles(settings.hostLocations(), expected, "", settings.jsonMapper()).requireMatchingVersions(environment);
        }
    }

    private static Path hostPackages(Path root) {
        return root.resolve(HOST_PACKAGES);
    }

    /** The command that runs Sennard's commands from {@code installed}: the user's {@code java} on the installed jar. */
    private static String command(InstalledVersion installed) {
        String jar = installed.appJar().toString();
        return "java -jar " + (jar.chars().anyMatch(Character::isWhitespace) ? "\"" + jar + "\"" : jar);
    }

    /**
     * The words that start the server of {@code installed}: the JVM options of the preset and the application jar as the class path, the same words as the training run's, because the JDK's
     * AOT cache is made for one class path and refused for another.
     */
    private static List<String> serverCommand(InstalledVersion installed, JvmPreset preset, Path java) {
        return JvmOptions.command(java, JvmOptions.server(preset, installed), installed.appJar(), SennardPersonalApplication.class.getName());
    }

    /** Starts the server from {@code installed} unless one of this state folder runs, and waits until it is healthy. */
    private void startServer(InstalledVersion installed, JvmPreset preset, Path java, ServerControl server) {
        launch(installed, preset, java, server);
        server.awaitHealthy(HEALTHY);
    }

    /** The working folder is the installation's, so that the JVM's log files are written there by relative names ({@link JvmOptions}). */
    private void launch(InstalledVersion installed, JvmPreset preset, Path java, ServerControl server) {
        if (!server.listening()) {
            environment.starter().start(serverCommand(installed, preset, java), root(), environment.environment());
        }
    }

    public void start() {
        InstalledVersion installed = activeVersion();
        JvmPreset preset = savedOrChosenPreset(installed, true);
        try (InstalledSettings settings = InstalledSettings.at(installed.folder())) {
            startServer(installed, preset, environment.java().path(), new ServerControl(settings.serverAddressFile(), environment.environment()));
            out.println("Started.");
        }
    }

    /**
     * What a host's session start does when no server runs: starts the installed server the way {@link #start()} does and
     * returns when it is ready to take an event: healthy and, where the installation prepares its store, the store ready. Nothing is printed: the caller's
     * standard output is not for a message (the hook client discards it). One process at a time starts it ({@link StartLock}); another session start that finds no server waits for that
     * one's server instead of starting a second, and a server that something else started in the meantime is waited for the same way. A server that is slow is
     * not killed when {@code within} is over: it is the next session start's server, and what it was starting (its store) is not left half done.
     *
     * @param within how long this call waits; the host's limit for the hook is above it
     * @throws SennardException when nothing is installed, the server cannot be started, the store preparation failed, or the server is not ready in time
     */
    public void startForSession(Duration within) {
        InstalledVersion installed = activeVersion();
        try (InstalledSettings settings = InstalledSettings.quietlyAt(installed.folder())) {
            ServerControl server = new ServerControl(settings.serverAddressFile(), environment.environment(), SESSION_ANSWER);
            long end = System.nanoTime() + within.toNanos(); // a wait for another process: the monotonic clock, not a date
            while (!ready(settings, server)) {
                boolean started = StartLock.runHolding(settings.stateDirectory(), environment.privateStorage(), () -> {
                    if (!server.listening()) {
                        launch(installed, savedOrChosenPreset(installed, false), environment.java().path(), server);
                    }
                    ServerControl.await(() -> ready(settings, server), Duration.ofNanos(Math.max(0, end - System.nanoTime())), UnavailableCode.START_FAILED,
                            SennardException.args("what", "notReady", "file", settings.serverAddressFile(), "seconds", String.valueOf(within.toSeconds())));
                });
                if (started) {
                    return;
                }
                if (System.nanoTime() - end > 0) {
                    throw new UnavailableException(UnavailableCode.START_FAILED, SennardException.args("what", "otherStartPending", "seconds", String.valueOf(within.toSeconds())));
                }
                LockSupport.parkNanos(ServerControl.POLL.toNanos());
            }
        }
    }

    /** Healthy, and where the installation prepares its store, with the store ready: the server can record an event. */
    private static boolean ready(InstalledSettings settings, ServerControl server) {
        return server.healthy() && (!settings.preparesStore() || storeReady(server));
    }

    private static boolean storeReady(ServerControl server) {
        return server.storeStatus().map(store -> {
            if (store.phase() == StorePhase.FAILED) {
                // H2 holds Sennard's own first-time settings only: the user named it as the business database, so the refusal is the user's input to correct, in its own words.
                if (StoreCode.BUSINESS_DATABASE_NOT_H2.name().equals(store.reason())) {
                    throw new InvalidInputException(InputCode.BUSINESS_DATABASE_NOT_H2);
                }
                throw new UnavailableException(UnavailableCode.STORE_PREPARATION_FAILED, SennardException.args("reason", store.reason()));
            }
            // NOT_CONFIGURED: the server is up and waits for a database to be set; there is nothing more for the install to wait for.
            return store.phase() == StorePhase.READY || store.phase() == StorePhase.NOT_CONFIGURED;
        }).orElse(false);
    }

    public void stop() {
        InstalledVersion installed = activeVersion();
        try (InstalledSettings settings = InstalledSettings.at(installed.folder())) {
            new ServerControl(settings.serverAddressFile(), environment.environment()).shutdown();
            out.println("Stopped.");
        }
    }

    public void uninstall() {
        Path root = root();
        List<InstalledVersion> versions = installedVersions(root);
        // All or nothing: a program that runs from inside the folder is removed with it (and some file systems keep a running program's files), so on any OS the run
        // would stop the server and then fail or remove its own program half way.
        if (runsFromInside(root)) {
            throw new GateRefusedException(GateCode.UNINSTALL_FROM_INSTALLED_COPY, SennardException.args("running", running.jar().orElseThrow(), "root", root));
        }
        if (!versions.isEmpty()) {
            try (InstalledSettings settings = InstalledSettings.at(versions.get(0).folder())) {
                new ServerControl(settings.serverAddressFile(), environment.environment()).shutdown();
                new HostFiles(settings.hostLocations(), versions.get(0).version(), command(versions.get(0)) + " hooks", settings.jsonMapper()).removePlaced(hostPackages(root)).forEach(out::println);
                delete(root);
                out.println("Removed " + root + ". The state folder stays: " + settings.stateDirectory());
                out.println("The Sennard plugin stays installed in each host. Remove it there too: an installed plugin keeps calling the removed server.");
            }
        } else {
            out.println("Sennard is not installed.");
        }
    }

    /**
     * Real paths on both sides: the running jar's folder comes from the class path, which the JVM has already resolved, while the root is as the
     * environment spells it, an 8.3 short name or a junction included. The answer does not depend on the operating system: the uninstall that started from
     * inside the folder it removes is refused everywhere, so the same input gives the same result on every OS.
     */
    private boolean runsFromInside(Path root) {
        Optional<Path> jar = running.jar();
        if (jar.isEmpty() || !Files.exists(root)) {
            return false;
        }
        try {
            return jar.get().toRealPath().startsWith(root.toRealPath());
        } catch (IOException cause) {
            throw new UnavailableException(UnavailableCode.INSTALL_FAILED, SennardException.args("what", "unreadable", "file", root), cause);
        }
    }

    /** Publishes the runtime and settings together, then records placement and retirement progress for retries. */
    public void upgrade() {
        Path root = root();
        InstalledVersion newest = upgradeCandidate(root);
        preflightHostPackages(newest.folder(), version);
        boolean needsPlacement = !newest.version().equals(version) || Files.isRegularFile(newest.folder().resolve(UPGRADE_PENDING));
        Runnable applyClaudeSettings = needsPlacement ? prepareClaudeSettings() : KEEP_CLAUDE_SETTINGS;
        VersionRetirement.withLock(root.resolve(InstalledVersion.VERSIONS), () -> upgrade(root, applyClaudeSettings));
    }

    private InstalledVersion upgradeCandidate(Path root) {
        List<InstalledVersion> versions = installedVersions(root);
        if (versions.isEmpty()) {
            throw new NotFoundException(NotFoundCode.NOT_INSTALLED, SennardException.args("what", "upgrade", "root", root));
        }
        InstalledVersion newest = versions.getFirst();
        Version installedVersion = Version.parse(newest.version());
        int order = Version.parse(version).compareTo(installedVersion);
        if (order < 0) {
            throw new GateRefusedException(GateCode.DOWNGRADE_REFUSED, SennardException.args("version", version, "installed", installedVersion));
        }
        boolean pending = newest.version().equals(version) && (Files.isRegularFile(newest.folder().resolve(UPGRADE_PENDING))
                || Files.isRegularFile(newest.folder().resolve(RETIREMENT_PENDING)));
        if (order == 0 && !pending) {
            throw new ConflictException(ConflictCode.SAME_VERSION, SennardException.args("version", version));
        }
        return newest;
    }

    private void upgrade(Path root, Runnable applyClaudeSettings) {
        InstalledVersion previous = upgradeCandidate(root);
        preflightHostPackages(previous.folder(), version);
        InstalledVersion published = previous.version().equals(version) ? previous : publish(root, release(), previous);
        Path placementPending = published.folder().resolve(UPGRADE_PENDING);
        Path retirementPending = published.folder().resolve(RETIREMENT_PENDING);
        try {
            if (Files.isRegularFile(placementPending)) {
                finishUpgradePlacement(published, root, applyClaudeSettings);
                Files.move(placementPending, retirementPending, StandardCopyOption.ATOMIC_MOVE);
            }
            List<InstalledVersion> older = installedVersions(root).stream().filter(old -> !old.equals(published)).toList();
            new VersionRetirement().collect(root.resolve(InstalledVersion.VERSIONS), older);
            Files.delete(retirementPending);
        } catch (IOException cause) {
            throw new UnavailableException(UnavailableCode.INSTALL_FAILED, SennardException.args("what", "unwritable", "file", published.folder()), cause);
        }
        out.println("Upgraded to " + version + ".");
        out.println("Run Sennard commands with: " + command(published));
    }

    private void finishUpgradePlacement(InstalledVersion published, Path root, Runnable applyClaudeSettings) {
        JvmPreset preset = savedOrChosenPreset(published, true);
        Path java = environment.java().path();
        train(published, preset, java);
        try (InstalledSettings settings = InstalledSettings.at(published.folder())) {
            ServerControl server = new ServerControl(settings.serverAddressFile(), environment.environment());
            server.shutdown();
            startServer(published, preset, java, server);
            awaitStore(settings, server);
            applyClaudeSettings.run();
            placeHostFiles(settings, root, published, java).requireReached();
        }
    }

    /** One JSON document on stdout. */
    public Status status() {
        Path root = root();
        List<InstalledVersion> versions = installedVersions(root);
        List<String> names = versions.stream().map(InstalledVersion::version).toList();
        if (versions.isEmpty()) {
            return new Status(root.toString(), null, names, null, null, null, null, null, null);
        }
        InstalledVersion newest = versions.get(0);
        Optional<JvmPreset> preset = JvmSettingsFile.read(newest.jvmSettingsFile()).map(JvmSettingsFile.Content::preset);
        try (InstalledSettings settings = InstalledSettings.at(newest.folder())) {
            ServerControl server = new ServerControl(settings.serverAddressFile(), environment.environment());
            Optional<ServerAddress> address = server.running();
            Status installed = new Status(root.toString(), command(newest), names, null, null, server.healthy(), null, null, null);
            Status withPort = address.map(ServerAddress::port).map(installed::withPort).orElse(installed);
            Status withPreset = preset.map(withPort::withPreset).orElse(withPort);
            Status withStore = server.storeStatus().map(withPreset::withStore).orElse(withPreset);
            Status withAdminPage = server.adminPage().map(withStore::withAdminPage).orElse(withStore);
            HostFiles files = hostFiles(settings, newest);
            List<Mismatch> mismatches = files.mismatches(environment);
            if (!mismatches.isEmpty()) {
                return withAdminPage.withHostPackageMismatches(mismatches);
            }
            List<String> empty = files.emptyCopies(settings.hostAdapters(), environment).stream().map(Path::toString).toList();
            return withAdminPage.withEmptyCopies(empty);
        }
    }

    /** The command to run Sennard commands with: the one of the newest installed version, empty when nothing is installed. */
    public static Optional<String> installedCommand(InstallEnvironment environment, RunningProgram running) {
        return installedVersions(rootOf(environment, running)).stream().findFirst().map(Installer::command);
    }

    /** The newest installed version. */
    public InstalledVersion activeVersion() {
        List<InstalledVersion> versions = installedVersions(root());
        if (versions.isEmpty()) {
            throw new NotFoundException(NotFoundCode.NOT_INSTALLED, SennardException.args("what", "other", "root", root()));
        }
        return versions.get(0);
    }

    /** Newest first. Temporary and incomplete runtime directories are excluded. */
    private static List<InstalledVersion> installedVersions(Path root) {
        Path versions = root.resolve(InstalledVersion.VERSIONS);
        if (!Files.isDirectory(versions)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(versions)) {
            return entries.flatMap(entry -> InstalledVersion.at(entry).stream())
                    .sorted(Comparator.comparing((InstalledVersion entry) -> Version.parse(entry.version())).reversed()).toList();
        } catch (IOException cause) {
            throw new UnavailableException(UnavailableCode.INSTALL_FAILED, SennardException.args("what", "unreadable", "file", versions), cause);
        }
    }

    /**
     * Makes {@code versions/<version>} from the release jar this command runs from: extracted into a folder of its own ({@link ReleaseJar}), which is moved into place when it is
     * complete, so that the version is never seen half made.
     */
    private InstalledVersion publish(Path root, ReleaseJar release, InstalledVersion previous) {
        Path versions = root.resolve(InstalledVersion.VERSIONS);
        Path target = versions.resolve(version);
        Path temporary = versions.resolve(".publishing-" + version + "-" + UUID.randomUUID());
        boolean moved = false;
        try {
            Files.createDirectories(versions);
            if (Files.exists(target)) {
                throw new UnavailableException(UnavailableCode.INSTALL_FAILED, SennardException.args("what", "folderExists", "folder", target));
            }
            release.extractInto(temporary, version, environment.java().path(), environment.commands(), environment.environment());
            InstalledVersion.recordRuntime(temporary, version);
            Files.createFile(temporary.resolve(VersionRetirement.UNEXPOSED));
            if (previous != null) {
                copySettings(previous, InstalledVersion.ofFolder(temporary));
                Files.createFile(temporary.resolve(UPGRADE_PENDING));
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            moved = true;
        } catch (IOException cause) {
            throw new UnavailableException(UnavailableCode.INSTALL_FAILED, SennardException.args("what", "copyFailed", "folder", target), cause);
        } finally {
            if (!moved) {
                deleteQuietly(temporary);
            }
        }
        return new InstalledVersion(target);
    }

    private ReleaseJar release() {
        return running.jar().flatMap(ReleaseJar::of).orElseThrow(() -> new GateRefusedException(GateCode.NOT_A_RELEASE_JAR));
    }

    /**
     * The preset the server of this version is started with: the saved one when the version has one (an upgrade carries the file, and a test or an
     * administrator may have written it); otherwise the one chosen from a single reading of the machine's memory, which is saved so that nothing reads
     * the memory again. The options are made from it each time the server starts, so the admin page's change of the file is the next start's.
     *
     * @param announce whether the choice is printed: a session start's output is nobody's to read
     */
    private JvmPreset savedOrChosenPreset(InstalledVersion installed, boolean announce) {
        return JvmSettingsFile.read(installed.jvmSettingsFile()).orElseGet(() -> chooseFromMemory(installed.jvmSettingsFile(), announce)).preset();
    }

    private JvmSettingsFile.Content chooseFromMemory(Path file, boolean announce) {
        long total = environment.memory().totalBytes();
        MemoryEstimate estimate;
        try {
            estimate = JvmPresets.estimate(total);
        } catch (IllegalArgumentException unusable) {
            throw new UnavailableException(UnavailableCode.MEMORY_UNKNOWN, SennardException.args("total", String.valueOf(total), "file", file, "presets", Arrays.toString(JvmPreset.values())), unusable);
        }
        JvmSettingsFile.Content content = new JvmSettingsFile.Content(estimate.preset(), total);
        JvmSettingsFile.write(file, content);
        if (announce) {
            out.println("JVM preset " + estimate.preset() + ": estimated memory " + estimate.estimatedGigabytes() + " GB (calculated from the "
                    + String.format(Locale.ROOT, "%.1f", total / (double) (1L << 30)) + " GB the JVM reported; this may differ from the RAM installed). Change it on the admin page; a change applies when the server restarts.");
        }
        return content;
    }

    /**
     * The AOT caches of the installed classes, trained in the folder they will run from: the server's, for the preset's collector, and the hook client's. A JVM started with
     * {@code -XX:AOTCache} simply starts without a cache that is missing or stale (the JVM says why in its own log), so a failed training costs start-up time only; it is reported and does
     * not stop the install. The cache of the other collector is not made: a preset that changes the collector starts without one until the next upgrade.
     */
    private void train(InstalledVersion installed, JvmPreset preset, Path java) {
        new CommandAotCache(environment.commands(), out).make(installed, java);
        // The training run's state folder (SENNARD_STATE_DIR): the server keeps its settings database and its keys there, so it is made through the port of the storage of secrets like the real one.
        Path temporary = environment.temporaryFolder().resolve("sennard-aot-" + UUID.randomUUID());
        try {
            environment.privateStorage().createFolder(temporary);
        } catch (IOException cause) {
            throw new UnavailableException(UnavailableCode.INSTALL_FAILED, SennardException.args("what", "temporaryFolderFailed"), cause);
        }
        Path configuration = installed.aotConfiguration();
        try {
            Map<String, String> settings = Map.of("SENNARD_STATE_DIR", temporary.toString(), "SENNARD_STORE_PREPARE", "false");
            String main = SennardPersonalApplication.class.getName();
            String step = "record";
            Commands.Result result = environment.commands().run(JvmOptions.command(java, JvmOptions.training(preset, installed), installed.appJar(), main), settings, TRAINING);
            if (result.exitCode() == 0 && Files.exists(configuration)) {
                step = "create";
                result = environment.commands().run(JvmOptions.command(java, JvmOptions.cacheCreation(preset, installed), installed.appJar(), main), settings, TRAINING);
            }
            if (result.exitCode() != 0 || !Files.exists(installed.aotCache(preset.collector()))) {
                out.println("warning: AOT training failed in " + step + " (exit " + result.exitCode() + "); the server will start without the cache: " + result.output().strip());
            }
        } finally {
            deleteQuietly(temporary);
            try {
                Files.deleteIfExists(configuration);
            } catch (IOException cause) {
                out.println("warning: could not remove " + configuration + ": " + cause);
            }
        }
    }

    private void deleteQuietly(Path folder) {
        try {
            FileSystemUtils.deleteRecursively(folder);
        } catch (IOException cause) {
            out.println("warning: could not remove " + folder + ": " + cause);
        }
    }

    private void awaitStore(InstalledSettings settings, ServerControl server) {
        if (!settings.preparesStore()) {
            return;
        }
        ServerControl.await(() -> storeReady(server), STORE_READY, UnavailableCode.STORE_PREPARATION_TIMEOUT, Map.of());
    }

    /** The machine's identity is made once, here, and kept in the state folder, which an uninstall and a reinstall leave in place. */
    private static void ensureHostIdentity(Path stateDirectory, PrivateStorage storage) {
        try {
            if (HostIdentity.ensure(stateDirectory, storage) == null) {
                throw new IntegrityException(IntegrityCode.HOST_ID_INVALID, SennardException.args("file", stateDirectory.resolve(HostIdentity.FILE)));
            }
        } catch (IOException cause) {
            throw new UnavailableException(UnavailableCode.HOST_ID_NOT_WRITTEN, SennardException.args("file", stateDirectory.resolve(HostIdentity.FILE)), cause);
        }
    }

    private void writeStateDirectory(InstalledVersion installed, String stateDirectory) {
        try {
            Files.writeString(installed.settingsFile(), JvmSettingsFile.dump(Map.of("sennard", Map.of("state-dir", stateDirectory))), StandardCharsets.UTF_8);
        } catch (IOException cause) {
            throw new UnavailableException(UnavailableCode.INSTALL_FAILED, SennardException.args("what", "unwritable", "file", installed.settingsFile()), cause);
        }
    }

    /** The settings the installation holds: the user's {@code sennard.yaml} and the programs' {@code sennard-jvm.yaml}, so that an upgrade keeps the preset the admin page saved. */
    private static void copySettings(InstalledVersion from, InstalledVersion to) {
        for (Path[] pair : new Path[][] {{from.settingsFile(), to.settingsFile()}, {from.jvmSettingsFile(), to.jvmSettingsFile()}}) {
            try {
                if (Files.exists(pair[0])) {
                    Files.copy(pair[0], pair[1], StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException cause) {
                throw new UnavailableException(UnavailableCode.INSTALL_FAILED, SennardException.args("what", "carryOverFailed", "file", pair[0]), cause);
            }
        }
    }

    /**
     * Removes a folder with everything in it. The server that ran from it has ended (the stop waits for its process), so nothing holds a file of it but a program that was started
     * from its jars and is still running (a host's MCP server); that is the one reason a delete is refused, and it is said with the file system's own words so that the user can end that program.
     */
    private static void delete(Path folder) {
        try {
            FileSystemUtils.deleteRecursively(folder);
        } catch (IOException held) {
            throw new UnavailableException(UnavailableCode.DELETE_FAILED, SennardException.args("folder", folder, "cause", String.valueOf(held)), held);
        }
    }
}
