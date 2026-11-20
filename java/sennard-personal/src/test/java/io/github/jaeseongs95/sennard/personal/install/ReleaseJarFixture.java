// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.install;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.jaeseongs95.sennard.hookclient.ServerAddress;
import io.github.jaeseongs95.sennard.protocol.ToolProcess;
import io.github.jaeseongs95.sennard.serverit.SharedDatabase;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The release the build made, as a user has it: {@code sennard-<version>.jar} (the Spring Boot jar), and the same jar extracted ({@code -Djarmode=tools extract}, the layout an installed
 * version has: the application jar and {@code lib/}). A test that needs an installed version without running {@code sennard install} (which trains two AOT caches and takes minutes)
 * makes one from the extracted folder with {@link #installedCopy}; a test of the install itself runs the real jar ({@link #run}). The build passes {@code sennard.release.jar},
 * {@code sennard.extracted.dir} and {@code sennard.project.version} (sennard-personal/build.gradle.kts).
 *
 * <p>Isolation: a child JVM does not inherit the {@code user.home} system property, and the product takes its places from it, so every command here is given {@code -Duser.home=<home>}
 * ({@link #java}) and an environment whose {@code SENNARD_INSTALL_ROOT} and {@code SENNARD_STATE_DIR} are the caller's ({@link #isolated}). A test that leaves one of them out would reach
 * the real profile.
 */
public final class ReleaseJarFixture {
    /** Deadline for a command that never ends; the commands are waited for, not timed. */
    private static final long COMMAND_MINUTES = 5;

    private ReleaseJarFixture() {
    }

    /** The release jar the build made. */
    public static Path releaseJar() {
        return Path.of(System.getProperty("sennard.release.jar"));
    }

    /** The folder the release jar was extracted to: the application jar and {@code lib/}, without the skill and contract folders. */
    public static Path extracted() {
        return Path.of(System.getProperty("sennard.extracted.dir"));
    }

    /** The version the build stamps on the jar. */
    public static String version() {
        return System.getProperty("sennard.project.version");
    }

    /** The {@code java} of the JDK this test runs with (the one a user has, the product runs its own programs with the {@code java} that started the command). */
    public static Path javaProgram() {
        return Path.of(System.getProperty("java.home"), "bin", "java");
    }

    /**
     * An installed version without running the install: the extracted folder copied to {@code <installRoot>/versions/<version>}, its application jar named for that version (a test
     * of an upgrade needs versions the build did not make), and the skill folders and the contract schemas of the repository beside it, which the install copies out of the jar.
     * Not trained: there is no AOT cache, which is a start-up time and not a behaviour.
     */
    public static InstalledVersion installedCopy(Path installRoot, String version) throws IOException {
        Path folder = installRoot.resolve(InstalledVersion.VERSIONS).resolve(version);
        copyTree(extracted(), folder);
        Path built = folder.resolve(InstalledVersion.jarName(version()));
        Path named = folder.resolve(InstalledVersion.jarName(version));
        if (!built.equals(named)) {
            Files.move(built, named);
        }
        Path repository = Path.of(System.getProperty("sennard.repo.root"));
        copyTree(repository.resolve("skills"), folder.resolve("skills"));
        copyTree(repository.resolve("contracts"), folder.resolve("contracts"));
        return new InstalledVersion(folder);
    }

    /** The words that run {@code java} on {@code arguments} with {@code home} as the child's {@code user.home}: {@code java -Duser.home=<home> <arguments>}. */
    public static List<String> java(Path home, String... arguments) {
        List<String> command = new ArrayList<>();
        command.add(javaProgram().toString());
        command.add("-Duser.home=" + home);
        command.addAll(List.of(arguments));
        return command;
    }

    /**
     * The child's environment: this process's, with every variable that could lead outside the test's folders replaced. The caller gives {@code SENNARD_INSTALL_ROOT} and
     * {@code SENNARD_STATE_DIR} in {@code overrides}; nothing else names a place of the product.
     */
    public static Map<String, String> isolated(Path home, Map<String, String> overrides) {
        Map<String, String> environment = new HashMap<>();
        environment.put("USERPROFILE", home.toString());
        environment.put("HOME", home.toString());
        environment.put("XDG_CONFIG_HOME", home.resolve("config").toString());
        // The host plugin folders sennard hooks writes into follow these when set; the child inherits the rest of the runner's environment.
        environment.put("CLAUDE_CONFIG_DIR", home.resolve(".claude").toString());
        environment.put("CLAUDE_CODE_PLUGIN_CACHE_DIR", home.resolve(".claude/plugins").toString());
        environment.put("CODEX_HOME", home.resolve(".codex").toString());
        environment.put("SENNARD_SCHEDULEDPROBES", "false"); // the host CLIs and the tag API are not this test's
        // The temporary folder of the child is inside the test's folder too (D-0194): a server that is killed at the end of a case cannot delete the folders its Tomcat makes in
        // the temporary folder (Boot deletes them when Tomcat is destroyed), so they would stay in the test run's own.
        Path temporary = home.resolve("tmp");
        try {
            Files.createDirectories(temporary);
        } catch (IOException notMade) {
            throw new java.io.UncheckedIOException("The temporary folder of the child could not be made: " + temporary, notMade);
        }
        List.of("TMP", "TEMP", "TMPDIR").forEach(name -> environment.put(name, temporary.toString()));
        environment.putAll(overrides);
        return environment;
    }

    /** What a command printed (standard error merged) and the exit code it ended with. */
    public record Run(int exitCode, String stdout) {
    }

    /** Runs a command to its end in {@code directory} (the process's own when null). */
    public static Run run(List<String> command, Path directory, Map<String, String> environment) throws IOException, InterruptedException, TimeoutException {
        ToolProcess.Request request = ToolProcess.Request.of(command, ToolProcess.Stderr.MERGE, Duration.ofMinutes(COMMAND_MINUTES)).withEnvironment(environment);
        if (directory != null) {
            request = request.inDirectory(directory);
        }
        return switch (ToolProcess.run(request)) {
            case ToolProcess.Finished done -> new Run(done.exitCode(), new String(done.output(), StandardCharsets.UTF_8));
            case ToolProcess.TimedOut late -> throw new TimeoutException(command.get(0) + " did not end within " + COMMAND_MINUTES + " minutes");
            case ToolProcess.Overflowed big -> throw new IllegalStateException(command.get(0) + " overflowed an output limit that was not set");
        };
    }

    /** Starts a command that is to keep running: its output in {@code log}, its error stream on this process's, killed with this JVM if a test leaves it. */
    public static Process start(List<String> command, Path directory, Map<String, String> environment, Path log) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command).redirectOutput(log.toFile()).redirectError(ProcessBuilder.Redirect.INHERIT);
        if (directory != null) {
            builder.directory(directory.toFile());
        }
        builder.environment().putAll(environment);
        Process process = builder.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }));
        return process;
    }

    /**
     * {@code sennard.yaml} in the installed version, so the server a command starts (which has none of the test's environment but the one {@link #isolated} gives) stays in the test's
     * folders. An installation that prepares its store is given the business database the test run names as the link of the settings database in the state folder (where the page keeps
     * it: {@link SharedDatabase#linkInto}; the server and the commands read the same one) and starts from empty schemas, once for the running test class
     * ({@link SharedDatabase#resetForTheClass()}). The server's port is not written: the operating system chooses it at each start.
     */
    public static void writeSettings(InstalledVersion installed, Path stateDirectory, boolean prepareStore) throws IOException {
        if (prepareStore) {
            SharedDatabase.resetForTheClass();
            Files.createDirectories(stateDirectory);
            SharedDatabase.linkInto(stateDirectory.resolve("settings"));
        }
        Files.writeString(installed.settingsFile(), "sennard:\n  scheduled-probes: false\n  state-dir: \"" + stateDirectory.toString().replace("\\", "/") + "\"\n  store:\n    prepare: " + prepareStore
                + "\n", StandardCharsets.UTF_8);
    }

    private static final JsonMapper STATUS_JSON = JsonMapper.builder().build();
    private static final HttpClient HEALTH_CLIENT = HttpClient.newHttpClient();
    private static final Pattern PATCH_VERSION = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+).*");
    /** The most letters {@link #nonAsciiLetters} returns: enough to be outside ASCII and more than one character wide in the encodings that have variable width. */
    private static final int NON_ASCII_LETTERS = 3;

    /**
     * The next patch version after the build's ({@code 6.0.0} gives {@code 6.0.1}): a version an upgrade accepts, in the same major version as the host plugin copies the tests
     * make (a definition is written only into a plugin of the installation's major version).
     */
    public static String newerVersion() {
        Matcher parts = PATCH_VERSION.matcher(version());
        if (!parts.matches()) {
            throw new IllegalStateException("The project version is not <major>.<minor>.<patch>: " + version());
        }
        return parts.group(1) + "." + parts.group(2) + "." + (Integer.parseInt(parts.group(3)) + 1);
    }

    /**
     * A copy of the release jar in {@code folder}, named {@code sennard-<version>.jar}, whose manifest says {@code version} ({@code Implementation-Version}, which is what the command
     * reports as its own version and an install or upgrade takes as the version it makes). The manifest is changed through the JDK's zip file system, which keeps every other entry as it
     * was (a Spring Boot jar holds its libraries uncompressed, and the launcher needs them so). A test of an upgrade or a downgrade needs versions the build did not make.
     */
    public static Path releaseJarWithVersion(Path folder, String version) throws IOException {
        Files.createDirectories(folder);
        Path copy = folder.resolve(InstalledVersion.jarName(version));
        Files.copy(releaseJar(), copy, StandardCopyOption.REPLACE_EXISTING);
        try (FileSystem zip = FileSystems.newFileSystem(copy)) {
            Path manifestFile = zip.getPath("META-INF", "MANIFEST.MF");
            Manifest manifest;
            try (InputStream in = Files.newInputStream(manifestFile)) {
                manifest = new Manifest(in);
            }
            manifest.getMainAttributes().put(Attributes.Name.IMPLEMENTATION_VERSION, version);
            try (OutputStream out = Files.newOutputStream(manifestFile)) {
                manifest.write(out);
            }
        }
        return copy;
    }

    /**
     * Up to {@value #NON_ASCII_LETTERS} letters outside ASCII that the JDK's {@code native.encoding} can write: what the Windows {@code java} launcher can read in a command line (the
     * product refuses a folder that its encoder cannot encode, {@link InstallEnvironment#representable}). They are looked for from U+00C0 on, so that the letters are the same on a PC
     * whose encoding is UTF-8 and differ on one that is not.
     *
     * @throws AssertionError when the encoding can write none: a test that needs a non-ASCII folder cannot run there, which is a failure and not a reason to leave the folder out
     */
    public static String nonAsciiLetters() {
        CharsetEncoder encoder = Charset.forName(System.getProperty("native.encoding")).newEncoder();
        StringBuilder letters = new StringBuilder();
        for (int codePoint = 0xC0; codePoint <= 0xFFFF && letters.length() < NON_ASCII_LETTERS; codePoint++) {
            String letter = new String(Character.toChars(codePoint));
            if (Character.isLetter(codePoint) && encoder.canEncode(letter)) {
                letters.append(letter);
            }
        }
        if (letters.isEmpty()) {
            throw new AssertionError("The encoding of this PC (native.encoding " + System.getProperty("native.encoding") + ") can write no letter outside ASCII, so a folder named outside ASCII cannot be made");
        }
        return letters.toString();
    }

    /**
     * Links the business database the test run uses in the settings database of this state folder, where the page keeps the link (the server and every command read it), so that an
     * installation made by {@code sennard install} on that state folder prepares that store from its first start. The store starts from empty schemas, once for the running test class
     * ({@link SharedDatabase#resetForTheClass()}).
     */
    public static void linkBusinessDatabase(Path stateDirectory) throws IOException {
        SharedDatabase.resetForTheClass();
        Files.createDirectories(stateDirectory);
        SharedDatabase.linkInto(stateDirectory.resolve("settings"));
    }

    /**
     * The server that runs now according to the address file it wrote ({@link ServerAddress}), found where a client finds it: the port is the operating system's choice at each start,
     * so it is read and never chosen.
     *
     * @throws AssertionError when the file names no process that is alive
     */
    public static ServerAddress liveServer(Path addressFile) {
        return ServerAddress.read(addressFile).filter(ServerAddress::live)
                .orElseThrow(() -> new AssertionError("No running server is announced in " + addressFile));
    }

    /** The HTTP status of the server's health endpoint ({@code /actuator/health}) at the port it announced. */
    public static int serverHealth(ServerAddress address) throws IOException, InterruptedException {
        // The timeout only stops a server that accepted the connection and never answers; no case asserts how long an answer took.
        HttpRequest request = HttpRequest.newBuilder(address.baseUrl().resolve("/actuator/health")).timeout(Duration.ofSeconds(30)).GET().build();
        return HEALTH_CLIENT.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    /**
     * Ends the server the address file names, with what it started, when it is still there: for the end of a test, so that a case that failed midway leaves no server behind (the
     * commands under test stop the server themselves and a case that passed finds none). A server that was asked to end has deleted its file and is not touched.
     */
    public static void stopServer(Path addressFile) throws InterruptedException, ExecutionException, TimeoutException {
        Optional<ProcessHandle> server = ServerAddress.read(addressFile).filter(ServerAddress::live).flatMap(address -> ProcessHandle.of(address.pid()));
        if (server.isPresent()) {
            server.get().descendants().forEach(ProcessHandle::destroyForcibly);
            server.get().destroyForcibly();
            // The deadline only stops a process that cannot be ended; the folders the server held are released after it is gone.
            server.get().onExit().get(60, TimeUnit.SECONDS);
        }
    }

    /** The JSON document {@code sennard status} printed ({@link Installer.Status}), as a tree. */
    public static JsonNode statusJson(Run status) {
        int start = status.stdout().indexOf('{');
        if (status.exitCode() != 0 || start < 0) {
            throw new AssertionError("sennard status failed (exit " + status.exitCode() + "): " + status.stdout());
        }
        return STATUS_JSON.readTree(status.stdout().substring(start));
    }

    private static void copyTree(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                Files.createDirectories(target.resolve(source.relativize(directory).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.copy(file, target.resolve(source.relativize(file).toString()), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
