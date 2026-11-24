// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.repotools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The setup launchers of a release run for real: the real templates are rendered by {@link ReleaseAssets} next to a small jar of this test, and started as a process with an
 * environment of its own. Which launcher can run here is decided by the existence of its shell (cmd.exe with Windows PowerShell for {@code setup.cmd}, {@code /bin/sh} for
 * {@code setup.sh}), not by a guess about the operating system; a launcher whose shell is not there is skipped.
 */
class SetupLauncherProcessTest {
    private static final String PROMPT = "Enter the Java 25+";
    private static final String REFUSED = "does not provide Java 25";
    private static final List<String> SH_TOOLS = List.of("dirname", "awk", "mkdir", "mktemp", "mv", "rm");

    @TempDir
    Path temporary;

    private static final String FAKE_SENNARD_CLASS = "FakeSennard";
    /**
     * The main class of the jar of these tests, as the source of a small program that is compiled when a release is laid out (the JDK's own compiler, in this JVM; the way the
     * programs of the other process tests are made, see {@code RunnerFixture.program}). Its standard output is what the tests read, so it is a program of its own and not code of
     * this class: it prints what it was started with and ends with the exit code the environment names.
     */
    private static final String FAKE_SENNARD = """
            import java.nio.charset.StandardCharsets;
            import java.nio.file.Files;
            import java.nio.file.Path;

            public final class FakeSennard {
                public static void main(String[] args) throws Exception {
                    System.out.println("FAKE-MAIN " + String.join(" ", args));
                    System.out.println("FAKE-LOCALE LC_ALL=" + System.getenv("LC_ALL") + " LC_CTYPE=" + System.getenv("LC_CTYPE"));
                    System.out.println("FAKE-CWD " + Path.of("").toAbsolutePath());
                    // The folders as the Java of the product reads them (Path.of(value) taken from the working folder, which is what its relative-path binding does).
                    for (String name : new String[] {"SENNARD_STATE_DIR", "SENNARD_INSTALL_ROOT"}) {
                        String value = System.getenv(name);
                        System.out.println("FAKE-" + name + " " + (value == null ? "unset" : Path.of(value).toAbsolutePath().normalize()));
                    }
                    // For a launcher in a console no test can read: when the jar ended, in milliseconds since the epoch.
                    String mark = System.getenv("SENNARD_FAKE_MARK");
                    if (mark != null) {
                        Files.writeString(Path.of(mark), Long.toString(System.currentTimeMillis()), StandardCharsets.UTF_8);
                    }
                    String exit = System.getenv("SENNARD_FAKE_EXIT");
                    System.exit(exit == null ? 0 : Integer.parseInt(exit));
                }
            }
            """;

    enum Kind {
        CMD("setup.cmd", "java.exe"), SH("setup.sh", "java");

        final String script;
        final String javaName;

        Kind(String script, String javaName) {
            this.script = script;
            this.javaName = javaName;
        }

        boolean available() {
            return switch (this) {
                case CMD -> systemRoot() != null && Files.isRegularFile(cmdExe()) && Files.isRegularFile(powerShell());
                case SH -> Files.isRegularFile(Path.of("/bin/sh")) && Files.isExecutable(Path.of("/bin/sh"));
            };
        }
    }

    private static Path systemRoot() {
        String root = System.getenv("SystemRoot");
        return root == null ? null : Path.of(root);
    }

    private static Path cmdExe() {
        return systemRoot().resolve("System32/cmd.exe");
    }

    private static Path powerShell() {
        return systemRoot().resolve("System32/WindowsPowerShell/v1.0/powershell.exe");
    }

    private record Result(int exit, String output) {
    }

    private record Release(Path folder, String jar) {
    }

    // ---- fixtures

    /** A folder of the real launchers and a fake jar, as {@code checkJar} lays them out. */
    private Release release(String folderName) throws IOException {
        ReleaseAssets assets = new ReleaseAssets(new Repo(Path.of(System.getProperty("sennard.repo.root"))));
        String jar = "sennard-" + assets.version() + ".jar";
        Path folder = Files.createDirectories(temporary.resolve(folderName));
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, FAKE_SENNARD_CLASS);
        String entry = FAKE_SENNARD_CLASS + ".class";
        byte[] compiled = Files.readAllBytes(fakeSennardClasses().resolve(entry));
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(folder.resolve(jar)), manifest)) {
            out.putNextEntry(new ZipEntry(entry));
            out.write(compiled);
            out.closeEntry();
        }
        assets.checkJar(folder, "");
        return new Release(folder, jar);
    }

    /** The folder with the compiled {@code FakeSennard}, made once for a test. */
    private Path fakeSennardClasses() throws IOException {
        Path folder = Files.createDirectories(temporary.resolve("fake-sennard"));
        if (Files.notExists(folder.resolve(FAKE_SENNARD_CLASS + ".class"))) {
            Path source = folder.resolve(FAKE_SENNARD_CLASS + ".java");
            Files.writeString(source, FAKE_SENNARD, StandardCharsets.UTF_8);
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            if (compiler == null) {
                throw new IllegalStateException("The test JVM has no Java compiler");
            }
            if (compiler.run(null, null, null, "-d", folder.toString(), source.toString()) != 0) {
                throw new IllegalStateException("The program " + FAKE_SENNARD_CLASS + " did not compile");
            }
        }
        return folder;
    }

    private static Path realJdk() {
        return Path.of(System.getProperty("java.home"));
    }

    private static String javaExecutable(Kind kind) {
        return "bin/" + kind.javaName;
    }

    /** A JDK folder whose java reports the given specification version (a shell script; only for {@code setup.sh}). */
    private Path fakeJdk(String name, int specification) throws IOException {
        Path java = Files.createDirectories(temporary.resolve(name).resolve("bin")).resolve("java");
        Files.writeString(java, """
                #!/bin/sh
                case "$*" in
                    *-XshowSettings:properties*) echo "    java.specification.version = %d" >&2; exit 0 ;;
                esac
                echo "FAKE-JAVA %d $*"
                """.formatted(specification, specification), StandardCharsets.UTF_8);
        assertTrue(java.toFile().setExecutable(true), "the fake java must be executable");
        return java.getParent().getParent();
    }

    /** A JDK folder whose java is Java 25 and always answers that its file name encoding is ASCII, whatever the locale (only for {@code setup.sh}). */
    private Path asciiOnlyJdk() throws IOException {
        Path java = Files.createDirectories(temporary.resolve("jdk-ascii").resolve("bin")).resolve("java");
        Files.writeString(java, """
                #!/bin/sh
                case "$*" in
                    *-XshowSettings:properties*) echo "    java.specification.version = 25" >&2; echo "    sun.jnu.encoding = ANSI_X3.4-1968" >&2; exit 0 ;;
                esac
                [ "$1" = "-jar" ] || { echo "FAKE-JAVA-BAD-FIRST-ARGUMENT $1"; exit 41; }
                [ "$2" = "$SENNARD_EXPECTED_JAR" ] || { echo "FAKE-JAVA-BAD-JAR $2"; exit 42; }
                shift 2
                echo "FAKE-MAIN $*"
                echo "FAKE-LOCALE LC_ALL=${LC_ALL-unset} LC_CTYPE=${LC_CTYPE-unset}"
                """, StandardCharsets.UTF_8);
        assertTrue(java.toFile().setExecutable(true), "the fake java must be executable");
        return java.getParent().getParent();
    }

    /** A JDK folder that is not Java 25 or later: a Java below 25 for {@code setup.sh}, a program that rejects the version query for {@code setup.cmd}. */
    private Path tooOldJdk(Kind kind) throws IOException {
        if (kind == Kind.SH) {
            return fakeJdk("jdk24", 24);
        }
        Path bin = Files.createDirectories(temporary.resolve("jdk24/bin"));
        Files.copy(systemRoot().resolve("System32/whoami.exe"), bin.resolve("java.exe"));
        return bin.getParent();
    }

    /** The folder {@code PATH} points at when no Java may be found: the few tools {@code setup.sh} calls, linked by name (a system java could be anywhere else). */
    private Path toolsFolder(Kind kind) throws IOException {
        Path tools = Files.createDirectories(temporary.resolve("tools"));
        if (kind == Kind.CMD) {
            return systemRoot().resolve("System32");
        }
        for (String tool : SH_TOOLS) {
            if (Files.notExists(tools.resolve(tool))) {
                Path found = null;
                for (String directory : System.getenv("PATH").split(File.pathSeparator)) {
                    Path candidate = Path.of(directory.isEmpty() ? "." : directory).resolve(tool);
                    if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                        found = candidate;
                        break;
                    }
                }
                assumeTrue(found != null, tool + " is not on the PATH of the test JVM");
                Files.createSymbolicLink(tools.resolve(tool), found);
            }
        }
        return tools;
    }

    private Map<String, String> environment(Kind kind, Path home, Path stateDirectory, Path javaHome, List<Path> pathFolders) {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("HOME", home.toString());
        environment.put("USERPROFILE", home.toString());
        environment.put("TEMP", temporary.toString());
        environment.put("TMP", temporary.toString());
        if (kind == Kind.CMD) {
            for (String name : List.of("SystemRoot", "windir", "ComSpec", "PATHEXT")) {
                if (System.getenv(name) != null) {
                    environment.put(name, System.getenv(name));
                }
            }
        }
        if (stateDirectory != null) {
            environment.put("SENNARD_STATE_DIR", stateDirectory.toString());
        }
        if (javaHome != null) {
            environment.put("JAVA_HOME", javaHome.toString());
        }
        List<String> path = new ArrayList<>();
        pathFolders.forEach(folder -> path.add(folder.toString()));
        environment.put("PATH", String.join(File.pathSeparator, path));
        return environment;
    }

    private static Result run(Kind kind, Path script, Path workingDirectory, Map<String, String> environment, String input) throws Exception {
        return run(kind, script, workingDirectory, environment, input, true, 60);
    }

    /**
     * Starts the launcher with its output read on a thread of its own, so that a launcher that waits for a key cannot hold this test: it is stopped after {@code seconds}. With
     * {@code closeInput} false the standard input stays open (and silent) until the launcher ends, as a pipe that no one writes to.
     */
    private static Result run(Kind kind, Path script, Path workingDirectory, Map<String, String> environment, String input, boolean closeInput, int seconds) throws Exception {
        List<String> command = kind == Kind.SH ? List.of("/bin/sh", script.toString()) : List.of(script.toString());
        ProcessBuilder builder = new ProcessBuilder(command).directory(workingDirectory.toFile()).redirectErrorStream(true);
        builder.environment().clear();
        builder.environment().putAll(environment);
        Process process = builder.start();
        CompletableFuture<byte[]> output = CompletableFuture.supplyAsync(() -> {
            try {
                return process.getInputStream().readAllBytes();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        OutputStream stdin = process.getOutputStream();
        try {
            stdin.write(input.getBytes(StandardCharsets.UTF_8));
            stdin.flush();
            if (closeInput) {
                stdin.close();
            }
        } catch (IOException closed) {
            // the launcher ended before it read its input: the exit code and the output tell what it did
        }
        boolean ended = process.waitFor(seconds, TimeUnit.SECONDS);
        if (!ended) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            throw new AssertionError("the launcher did not end within " + seconds + " seconds");
        }
        try {
            stdin.close();
        } catch (IOException closed) {
            // already closed by the ended process
        }
        return new Result(process.exitValue(), new String(output.get(30, TimeUnit.SECONDS), StandardCharsets.UTF_8));
    }

    private Result runIn(Kind kind, Release release, Map<String, String> environment, String input) throws Exception {
        return run(kind, release.folder().resolve(kind.script), temporary, environment, input);
    }

    private Path home() throws IOException {
        return Files.createDirectories(temporary.resolve("home"));
    }

    private Path stateFolder() {
        return temporary.resolve("state folder");
    }

    private static String savedPath(Path state) throws IOException {
        return Files.readAllLines(state.resolve("setup-java-path"), StandardCharsets.UTF_8).get(0);
    }

    private static void assertSavedJava(Path state, Kind kind) throws IOException {
        assertTrue(Files.isRegularFile(state.resolve("setup-java-path")), "setup-java-path was not written in " + state);
        Path saved = Path.of(savedPath(state));
        assertTrue(Files.isSameFile(saved, realJdk().resolve(javaExecutable(kind))), "saved: " + saved);
    }

    /**
     * That the launcher asked. Windows PowerShell writes the prompt of {@code Read-Host} to its host and not to the redirected output, so for {@code setup.cmd} the asking shows
     * in what followed (the exit code, the saved file) and the prompt text is checked for {@code setup.sh} only.
     */
    private static void assertPrompted(Kind kind, Result result) {
        if (kind == Kind.SH) {
            assertTrue(result.output().contains(PROMPT), result.output());
        }
    }

    private static void assertRanJar(Result result) {
        assertEquals(0, result.exit(), result.output());
        assertTrue(result.output().contains("FAKE-MAIN setup"), result.output());
    }

    // ---- the Java comes from the environment

    @ParameterizedTest
    @EnumSource(Kind.class)
    void javaHomeWithJava25RunsSetupWithoutAskingAndSavesNothing(Kind kind) throws Exception {
        assumeTrue(kind.available());
        Release release = release("release");
        Result result = runIn(kind, release, environment(kind, home(), stateFolder(), realJdk(), List.of(toolsFolder(kind))), "");
        assertRanJar(result);
        assertFalse(result.output().contains(PROMPT), result.output());
        assertTrue(Files.notExists(stateFolder().resolve("setup-java-path")), "an environment Java is not saved");
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void java25OnThePathWithoutJavaHomeRunsSetupWithoutAsking(Kind kind) throws Exception {
        assumeTrue(kind.available());
        Release release = release("release");
        Result result = runIn(kind, release, environment(kind, home(), stateFolder(), null, List.of(realJdk().resolve("bin"), toolsFolder(kind))), "");
        assertRanJar(result);
        assertFalse(result.output().contains(PROMPT), result.output());
        assertTrue(Files.notExists(stateFolder().resolve("setup-java-path")));
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void aJavaHomeBelow25MovesOnToJava25OnThePathWithoutAsking(Kind kind) throws Exception {
        assumeTrue(kind.available());
        Release release = release("release");
        Path old = tooOldJdk(kind);
        Result result = runIn(kind, release, environment(kind, home(), stateFolder(), old, List.of(old.resolve("bin"), realJdk().resolve("bin"), toolsFolder(kind))), "");
        assertRanJar(result);
        assertFalse(result.output().contains(PROMPT), result.output());
        assertTrue(Files.notExists(stateFolder().resolve("setup-java-path")));
    }

    @Test
    void java26OrLaterPasses() throws Exception {
        assumeTrue(Kind.SH.available());
        Release release = release("release");
        Result result = runIn(Kind.SH, release, environment(Kind.SH, home(), stateFolder(), fakeJdk("jdk26", 26), List.of(toolsFolder(Kind.SH))), "");
        assertEquals(0, result.exit(), result.output());
        assertTrue(result.output().contains("FAKE-JAVA 26 -jar " + release.folder().resolve(release.jar()) + " setup"), result.output());
        assertFalse(result.output().contains(PROMPT), result.output());
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void onlyAJavaBelow25AsksAndAnEmptyAnswerCancelsWithExitCode1(Kind kind) throws Exception {
        assumeTrue(kind.available());
        Release release = release("release");
        Path old = tooOldJdk(kind);
        Result result = runIn(kind, release, environment(kind, home(), stateFolder(), old, List.of(old.resolve("bin"), toolsFolder(kind))), "\n");
        assertEquals(1, result.exit(), result.output());
        assertPrompted(kind, result);
        assertFalse(result.output().contains("FAKE-MAIN"), result.output());
        assertTrue(Files.notExists(stateFolder().resolve("setup-java-path")));
    }

    // ---- the Java is entered and saved

    @ParameterizedTest
    @EnumSource(Kind.class)
    void aWrongPathIsRefusedThenAJdkFolderIsAcceptedAndSaved(Kind kind) throws Exception {
        assumeTrue(kind.available());
        Release release = release("release");
        String input = temporary.resolve("no such jdk") + "\n" + realJdk() + "\n";
        Result result = runIn(kind, release, environment(kind, home(), stateFolder(), null, List.of(toolsFolder(kind))), input);
        assertRanJar(result);
        assertTrue(result.output().contains(REFUSED), result.output());
        assertSavedJava(stateFolder(), kind);
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void thePathOfTheJavaProgramIsAccepted(Kind kind) throws Exception {
        assumeTrue(kind.available());
        Release release = release("release");
        String input = realJdk().resolve(javaExecutable(kind)) + "\n";
        Result result = runIn(kind, release, environment(kind, home(), stateFolder(), null, List.of(toolsFolder(kind))), input);
        assertRanJar(result);
        assertSavedJava(stateFolder(), kind);
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void aPathInQuotesIsAccepted(Kind kind) throws Exception {
        assumeTrue(kind.available());
        Release release = release("release");
        String input = "\"" + realJdk() + "\"\n";
        Result result = runIn(kind, release, environment(kind, home(), stateFolder(), null, List.of(toolsFolder(kind))), input);
        assertRanJar(result);
        assertSavedJava(stateFolder(), kind);
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void theSecondRunReusesTheSavedPathWithoutAsking(Kind kind) throws Exception {
        assumeTrue(kind.available());
        Release release = release("release");
        Map<String, String> environment = environment(kind, home(), stateFolder(), null, List.of(toolsFolder(kind)));
        assertRanJar(runIn(kind, release, environment, realJdk() + "\n"));
        Result second = runIn(kind, release, environment, "");
        assertRanJar(second);
        assertFalse(second.output().contains(PROMPT), second.output());
        assertSavedJava(stateFolder(), kind);
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void aSavedPathThatIsNoLongerValidAsksAgainAndIsOverwritten(Kind kind) throws Exception {
        assumeTrue(kind.available());
        Release release = release("release");
        Files.createDirectories(stateFolder());
        Files.writeString(stateFolder().resolve("setup-java-path"), temporary.resolve("gone").resolve(kind.javaName) + "\n", StandardCharsets.UTF_8);
        Result result = runIn(kind, release, environment(kind, home(), stateFolder(), null, List.of(toolsFolder(kind))), realJdk() + "\n");
        assertRanJar(result);
        assertPrompted(kind, result);
        assertSavedJava(stateFolder(), kind);
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void theStateFolderIsTheEnvironmentOneOrTheDefaultUnderTheHome(Kind kind) throws Exception {
        assumeTrue(kind.available());
        Release release = release("release");
        Path home = home();
        assertRanJar(runIn(kind, release, environment(kind, home, stateFolder(), null, List.of(toolsFolder(kind))), realJdk() + "\n"));
        assertTrue(Files.isRegularFile(stateFolder().resolve("setup-java-path")));
        assertTrue(Files.notExists(home.resolve(".sennard")), "SENNARD_STATE_DIR set: the default folder stays untouched");

        assertRanJar(runIn(kind, release, environment(kind, home, null, null, List.of(toolsFolder(kind))), realJdk() + "\n"));
        assertSavedJava(home.resolve(".sennard"), kind);
    }

    // ---- relative folders

    /** What the child printed after {@code FAKE-<name> } ({@code FakeSennard}). */
    private static String childValue(Result result, String name) {
        return result.output().lines().filter(line -> line.startsWith("FAKE-" + name + " ")).map(line -> line.substring(("FAKE-" + name + " ").length()).strip()).findFirst()
                .orElseThrow(() -> new AssertionError("the child printed no " + name + ": " + result.output()));
    }

    /**
     * The same input is the same folder for the launcher and the Java it starts: a relative {@code SENNARD_STATE_DIR} and {@code SENNARD_INSTALL_ROOT} are taken from the folder
     * the launcher was started in, which is where the launcher keeps the saved Java and where the Java reads them (it does not start in that folder for {@code setup.cmd}, which
     * starts it in the jar's). A variable that is not set stays not set.
     */
    @ParameterizedTest
    @EnumSource(Kind.class)
    void aRelativeStateFolderAndInstallRootAreTheSameFolderForTheLauncherAndTheJava(Kind kind) throws Exception {
        assumeTrue(kind.available());
        Release release = release("release");
        Path elsewhere = Files.createDirectories(temporary.resolve("elsewhere"));
        Path installRoot = Files.createDirectories(elsewhere.resolve("relative").resolve("install"));
        Map<String, String> environment = environment(kind, home(), null, null, List.of(toolsFolder(kind)));
        environment.put("SENNARD_STATE_DIR", "relative state");
        environment.put("SENNARD_INSTALL_ROOT", "relative" + File.separator + "install");

        Result result = run(kind, release.folder().resolve(kind.script), elsewhere, environment, realJdk() + "\n");

        assertRanJar(result);
        Path state = elsewhere.resolve("relative state");
        assertSavedJava(state, kind);
        assertTrue(Files.isSameFile(state, Path.of(childValue(result, "SENNARD_STATE_DIR"))), result.output());
        assertTrue(Files.isSameFile(installRoot, Path.of(childValue(result, "SENNARD_INSTALL_ROOT"))), result.output());
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void aStateFolderOrInstallRootThatIsNotSetIsNotSetForTheJava(Kind kind) throws Exception {
        assumeTrue(kind.available());
        Release release = release("release");
        Result result = runIn(kind, release, environment(kind, home(), null, realJdk(), List.of(toolsFolder(kind))), "");
        assertRanJar(result);
        assertEquals("unset", childValue(result, "SENNARD_STATE_DIR"));
        assertEquals("unset", childValue(result, "SENNARD_INSTALL_ROOT"));
    }

    // ---- where and how it is started

    @ParameterizedTest
    @EnumSource(Kind.class)
    void aFolderWithSpacesAndKoreanLettersAndAnotherWorkingFolderWork(Kind kind) throws Exception {
        assumeTrue(kind.available());
        Release release;
        try {
            release = release("릴리스 폴더 with spaces");
        } catch (InvalidPathException | IOException unwritable) {
            assumeTrue(false, "this file system cannot hold the folder name: " + unwritable);
            return;
        }
        Path elsewhere = Files.createDirectories(temporary.resolve("다른 작업 폴더"));
        Result result = run(kind, release.folder().resolve(kind.script), elsewhere, environment(kind, home(), stateFolder(), realJdk(), List.of(toolsFolder(kind))), "");
        assertRanJar(result);
    }

    static Stream<Arguments> specialCharacters() {
        return Stream.of(Kind.values()).flatMap(kind -> Stream.of("&", "%", "!", "(", "'", "$", "[", "``", "`[").map(character -> Arguments.of(kind, character)));
    }

    /** The folder the child printed as its working folder ({@code FakeSennard}). */
    private static Path childFolder(Result result) {
        return result.output().lines().filter(line -> line.startsWith("FAKE-CWD ")).map(line -> Path.of(line.substring("FAKE-CWD ".length()).strip())).findFirst()
                .orElseThrow(() -> new AssertionError("the child printed no working folder: " + result.output()));
    }

    /**
     * The characters a shell treats as its own do not change how the launcher finds and runs the jar; a name the file system does not allow skips the case. Two backticks in a row, and a
     * backtick before a bracket, are names that Windows PowerShell's own call of a program did not run at all (and did not report): the child must run, and in the folder of the jar
     * ({@code setup.cmd} starts it there, which is how it opens the jar by its file name).
     */
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("specialCharacters")
    void aFolderNameWithAShellCharacterWorks(Kind kind, String character) throws Exception {
        assumeTrue(kind.available());
        Release release;
        try {
            release = release("a" + character + "b " + character + "c");
        } catch (InvalidPathException | IOException unwritable) {
            assumeTrue(false, "this file system cannot hold the folder name: " + unwritable);
            return;
        }
        Path elsewhere = Files.createDirectories(temporary.resolve("elsewhere"));
        Result result = run(kind, release.folder().resolve(kind.script), elsewhere, environment(kind, home(), stateFolder(), realJdk(), List.of(toolsFolder(kind))), "");
        assertRanJar(result);
        if (kind == Kind.CMD) {
            assertTrue(Files.isSameFile(release.folder(), childFolder(result)), "the child ran in " + childFolder(result));
        }
    }

    /** A letter that the file name encoding of this computer ({@code native.encoding}, the code page a Windows java launcher reads its command line in) cannot write, or null. */
    private static String unencodableLetter() {
        Charset charset;
        try {
            charset = Charset.forName(System.getProperty("native.encoding", Charset.defaultCharset().name()));
        } catch (RuntimeException unknown) {
            return null;
        }
        for (String candidate : List.of("ő", "ž", "ğ", "ł", "ā", "я", "ա", "ก", "😀")) {
            if (!charset.newEncoder().canEncode(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** The jar of a folder whose name has a letter the system's code page cannot write is opened by its file name: the path is not passed in the command line. */
    @ParameterizedTest
    @EnumSource(Kind.class)
    void aFolderWithALetterOutsideTheNativeEncodingWorks(Kind kind) throws Exception {
        assumeTrue(kind.available());
        String letter = unencodableLetter();
        assumeTrue(letter != null, "every candidate letter can be written in native.encoding");
        Release release;
        try {
            release = release("folder " + letter);
        } catch (InvalidPathException | IOException unwritable) {
            assumeTrue(false, "this file system cannot hold the folder name: " + unwritable);
            return;
        }
        Path elsewhere = Files.createDirectories(temporary.resolve("elsewhere"));
        Result result = run(kind, release.folder().resolve(kind.script), elsewhere, environment(kind, home(), stateFolder(), realJdk(), List.of(toolsFolder(kind))), "");
        assertRanJar(result);
    }

    /** The launcher runs the jar of a folder with spaces and Korean letters with the real JVM; {@code locale} is what the user's environment names (the JVM decides the rest). */
    private Result runInKoreanFolder(Map<String, String> locale, Path javaHome) throws Exception {
        assumeTrue(Kind.SH.available());
        Release release;
        try {
            release = release("릴리스 폴더 with spaces");
        } catch (InvalidPathException | IOException unwritable) {
            assumeTrue(false, "this file system cannot hold the folder name: " + unwritable);
            return null;
        }
        Path elsewhere = Files.createDirectories(temporary.resolve("다른 작업 폴더"));
        Map<String, String> environment = environment(Kind.SH, home(), stateFolder(), javaHome, List.of(toolsFolder(Kind.SH)));
        environment.putAll(locale);
        environment.put("SENNARD_EXPECTED_JAR", release.folder().resolve(release.jar()).toString());
        return run(Kind.SH, release.folder().resolve(Kind.SH.script), elsewhere, environment, "");
    }

    @Test
    void anUtf8LocaleOfTheUserIsLeftAsItIs() throws Exception {
        Result result = runInKoreanFolder(Map.of("LANG", "C.UTF-8"), realJdk());
        assertRanJar(result);
        assertTrue(result.output().contains("FAKE-LOCALE LC_ALL=null LC_CTYPE=null"), result.output());
    }

    @Test
    void anAsciiLanguageSettingGetsAUtf8LocaleForTheJvmOnly() throws Exception {
        Result result = runInKoreanFolder(Map.of("LANG", "C"), realJdk());
        assertRanJar(result);
        assertTrue(result.output().contains("LC_ALL=null LC_CTYPE="), result.output());
        assertFalse(result.output().contains("LC_CTYPE=null"), result.output());
    }

    @Test
    void anAsciiLcAllIsReplacedByAUtf8One() throws Exception {
        Result result = runInKoreanFolder(Map.of("LC_ALL", "C"), realJdk());
        assertRanJar(result);
        assertFalse(result.output().contains("LC_ALL=C "), result.output());
        assertFalse(result.output().contains("LC_ALL=null"), result.output());
    }

    @Test
    void whenNoCandidateMakesTheJvmAnswerUtf8TheEnvironmentStaysAndTheReasonIsTold() throws Exception {
        Result result = runInKoreanFolder(Map.of("LC_ALL", "C"), asciiOnlyJdk());
        assertRanJar(result);
        assertTrue(result.output().contains("FAKE-LOCALE LC_ALL=C LC_CTYPE=unset"), result.output());
        assertTrue(result.output().contains("No UTF-8 locale was found"), result.output());
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void withoutTheJarItEndsWithExitCode1AndSaysWhereToPutIt(Kind kind) throws Exception {
        assumeTrue(kind.available());
        Release release = release("release");
        Files.delete(release.folder().resolve(release.jar()));
        Result result = runIn(kind, release, environment(kind, home(), stateFolder(), realJdk(), List.of(toolsFolder(kind))), "");
        assertEquals(1, result.exit(), result.output());
        assertTrue(result.output().contains("Put " + release.jar() + " next to " + kind.script), result.output());
        assertFalse(result.output().contains("FAKE-MAIN"), result.output());
    }

    /** A failure never waits for a key when the input is not a console: the input here stays open and silent, so a launcher that waits would be stopped by the time limit. */
    @ParameterizedTest
    @EnumSource(Kind.class)
    void aFailureWithRedirectedInputEndsWithoutWaitingForAKey(Kind kind) throws Exception {
        assumeTrue(kind.available());
        Release release = release("release");
        Map<String, String> environment = environment(kind, home(), stateFolder(), realJdk(), List.of(toolsFolder(kind)));
        environment.put("SENNARD_FAKE_EXIT", "7");
        Result failed = run(kind, release.folder().resolve(kind.script), temporary, environment, "", false, 30);
        assertEquals(7, failed.exit(), failed.output());

        Files.delete(release.folder().resolve(release.jar()));
        Result missing = run(kind, release.folder().resolve(kind.script), temporary, environment, "", false, 30);
        assertEquals(1, missing.exit(), missing.output());
        assertTrue(missing.output().contains("Put " + release.jar() + " next to " + kind.script), missing.output());
    }

    /**
     * A failure in a console that no one looks at ends by itself. A hidden window of a signed-in user (what {@code Start-Process -WindowStyle Hidden} makes) is an interactive session
     * with a console input, so no guard of the launcher can tell it from a visible window: the wait for a key must have an end instead. The launcher is rendered with a short wait,
     * started as {@code cmd /c} in a new hidden console whose input is not redirected, and its jar fails with exit code 7. It must wait the rendered time (so that the wait of a
     * window that is really the user's is the one tested) and then end with the exit code of the jar. The processes this starts are ended with their tree when the time limit passes.
     */
    @Test
    void aFailureInAHiddenConsoleEndsAfterTheWaitAndPassesTheExitCode() throws Exception {
        assumeTrue(Kind.CMD.available());
        int waitSeconds = 6;
        Release release = release("release");
        Path launcher = release.folder().resolve(Kind.CMD.script);
        Files.write(launcher, new ReleaseAssets(new Repo(Path.of(System.getProperty("sennard.repo.root")))).launcherBytes("setup.cmd", release.jar(), waitSeconds));
        Path host = temporary.resolve("hidden-console-host.ps1");
        Files.writeString(host, """
                param($Launcher, $LimitMilliseconds)
                $process = Start-Process -FilePath $env:ComSpec -ArgumentList ('/c ""' + $Launcher + '" "') -WindowStyle Hidden -PassThru
                $null = $process.Handle
                $ended = $process.WaitForExit([int] $LimitMilliseconds)
                $endedAt = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
                'INTERACTIVE ' + [Environment]::UserInteractive
                if (-not $ended) {
                    & taskkill.exe /T /F /PID $process.Id | Out-Null
                    'TIMEOUT'
                    exit 99
                }
                'ENDED-AT ' + $endedAt
                'EXIT ' + $process.ExitCode
                """, StandardCharsets.UTF_8);
        Path mark = temporary.resolve("jar-ended-at.txt");
        Map<String, String> environment = environment(Kind.CMD, home(), stateFolder(), realJdk(), List.of(toolsFolder(Kind.CMD)));
        environment.put("SENNARD_FAKE_EXIT", "7");
        environment.put("SENNARD_FAKE_MARK", mark.toString());
        int limitSeconds = waitSeconds + 40;
        List<String> command = List.of(powerShell().toString(), "-NoLogo", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", host.toString(), launcher.toString(),
                Integer.toString(limitSeconds * 1000));
        ProcessBuilder builder = new ProcessBuilder(command).directory(temporary.toFile()).redirectErrorStream(true);
        builder.environment().clear();
        builder.environment().putAll(environment);
        Process process = builder.start();
        try {
            process.getOutputStream().close();
            CompletableFuture<byte[]> read = CompletableFuture.supplyAsync(() -> {
                try {
                    return process.getInputStream().readAllBytes();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            boolean finished = process.waitFor(limitSeconds + 20, TimeUnit.SECONDS);
            if (!finished) {
                throw new AssertionError("the host of the hidden console did not end within " + (limitSeconds + 20) + " seconds");
            }
            String output = new String(read.get(30, TimeUnit.SECONDS), StandardCharsets.UTF_8);
            assumeTrue(output.contains("INTERACTIVE True"), "the wait is only for an interactive session: " + output);
            assertFalse(output.contains("TIMEOUT"), "the launcher still waited for a key after " + limitSeconds + " seconds: " + output);
            assertTrue(output.contains("EXIT 7"), output);
            long jarEndedAt = Long.parseLong(Files.readString(mark, StandardCharsets.UTF_8).strip());
            long launcherEndedAt = output.lines().filter(line -> line.startsWith("ENDED-AT ")).map(line -> Long.parseLong(line.substring("ENDED-AT ".length()).strip())).findFirst()
                    .orElseThrow(() -> new AssertionError("the host printed no ENDED-AT: " + output));
            assertTrue(launcherEndedAt - jarEndedAt >= waitSeconds * 1000L - 500, "the launcher did not wait: " + (launcherEndedAt - jarEndedAt) + " ms after its jar ended");
        } finally {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void theExitCodeOfTheJarIsTheExitCodeOfTheLauncher(Kind kind) throws Exception {
        assumeTrue(kind.available());
        Release release = release("release");
        Map<String, String> environment = environment(kind, home(), stateFolder(), realJdk(), List.of(toolsFolder(kind)));
        environment.put("SENNARD_FAKE_EXIT", "7");
        Result result = runIn(kind, release, environment, "");
        assertEquals(7, result.exit(), result.output());
        assertTrue(result.output().contains("FAKE-MAIN setup"), result.output());
    }
}
