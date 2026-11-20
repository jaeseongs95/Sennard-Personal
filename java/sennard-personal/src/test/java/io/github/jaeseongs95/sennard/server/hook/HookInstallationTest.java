// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.jaeseongs95.sennard.hookclient.HookClientCall;
import io.github.jaeseongs95.sennard.hookclient.HookClientCall.FailureAnswer;
import io.github.jaeseongs95.sennard.hookclient.McpRelayArguments;
import io.github.jaeseongs95.sennard.server.hook.host.CodexHost;

/**
 * Case table (target: {@link HookInstallation#hookCommandLine}, {@code hookExecutable()}, {@code hookArguments()}, {@code mcpExecutable()}, {@code mcpArguments()}; input: the words of
 * the hook client's program and of the MCP relay's (the program first) and the arguments of a {@link HookClientCall} or of the relay; expected: the one command line a host that takes a
 * command string runs, or the program and the argument list a host that takes a list runs; judged by comparing the strings and lists):
 * <ol>
 * <li>a program of one word with no space or shell character: written as it is</li>
 * <li>a first word with a space, or with one of {@code & | < > ^ ( ) , ; =}: in double quotes</li>
 * <li>a first word with {@code "}, {@code %} or a control character, and an empty first word: {@link IllegalArgumentException}, nothing is written</li>
 * <li>the arguments are never quoted or changed; none of them holds a port, because a definition cannot name the server's port (the operating system gives one at each start)</li>
 * <li>a program of several words (a java, its options, a class path, a main class, the installation's options): the first word by the rules of 1 to 3, every other word in double
 * quotes only when it has a space or one of {@code & | < > ^ ( )}, and not when it has {@code =}, {@code ,}, {@code :} or {@code *} (an option of a JVM, a class path of a folder
 * with a wildcard); a word with {@code "}, {@code %} or a control character, and an empty word, anywhere in the list: {@link IllegalArgumentException} with that word in the message</li>
 * <li>{@code hookExecutable()} is the first word; {@code hookArguments(call)} is the other words then the call's, unquoted (no shell reads a list); the same for the relay's two</li>
 * <li>an empty list of words, of either program: {@link IllegalArgumentException} at construction; both lists are copied, so changing the one given changes nothing</li>
 * <li>the two programs are separate: the relay's words are not written into a hook's command line and the hook client's not into a registration of the MCP server</li>
 * </ol>
 */
class HookInstallationTest {
    private static final List<String> ARGUMENTS = HookClientCall.of(CodexHost.ID.label(), "SessionStart", FailureAnswer.GUIDANCE_STDOUT).bindingHost().arguments();
    private static final String CALL = "hook codex SessionStart --on-failure=guidance-stdout --bind-host";
    private static final List<String> RELAY = McpRelayArguments.arguments(CodexHost.ID.label(), 30);

    private static HookInstallation hooks(String program) {
        return new HookInstallation(List.of(program), List.of("relay"));
    }

    private static HookInstallation hooks(List<String> program) {
        return new HookInstallation(program, List.of("relay"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"C:/jdk/bin/java.exe", "C:\\jdk\\bin\\java.exe", "/opt/jdk/bin/java", "/home/user/.sdkman/java", "java"})
    void aPathWithNothingToQuoteIsWrittenAsItIs(String program) {
        assertEquals(program + " " + CALL, hooks(program).hookCommandLine(ARGUMENTS));
    }

    @ParameterizedTest
    @ValueSource(strings = {"C:\\Program Files\\Java\\bin\\java.exe", "/Users/some one/java", "C:/a&b/java.exe", "C:/a(b)/java.exe", "C:/a,b/java.exe", "C:/a;b/java.exe", "C:/a=b/java.exe", "C:/a^b/java.exe",
            "C:/a|b/java.exe", "C:/a<b>/java.exe"})
    void aPathWithASpaceOrAShellCharacterIsInDoubleQuotes(String program) {
        assertEquals("\"" + program + "\" " + CALL, hooks(program).hookCommandLine(ARGUMENTS));
    }

    @ParameterizedTest
    @ValueSource(strings = {"C:/say \"hi\"/java.exe", "C:/100%/java.exe", "%USERPROFILE%/java.exe", "C:/line\nbreak/java.exe", "C:/tab\tdir/java.exe", "C:/carriage\rreturn/java.exe", ""})
    void aPathNoQuotingSurvivesIsRefusedInsteadOfWritten(String program) {
        HookInstallation installation = hooks(program);

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> installation.hookCommandLine(ARGUMENTS));
        assertEquals("This program path cannot be written in a hook command: " + program, refused.getMessage());
    }

    @Test
    void theArgumentsAreNotChangedAndHoldNoPort() {
        List<String> call = List.of("hook", "claude", "SessionStart", "--bind-host", "--only-if-env=A=b");

        assertEquals("java hook claude SessionStart --bind-host --only-if-env=A=b", hooks("java").hookCommandLine(call));
        assertEquals(false, ARGUMENTS.stream().anyMatch(argument -> argument.startsWith("--port")), "no call writes a port: " + ARGUMENTS);
    }

    @Test
    void aProgramOfSeveralWordsIsWrittenWordByWord() {
        HookInstallation installation = hooks(List.of("/opt/jdk/bin/java", "-XX:+UseSerialGC", "-Xmx256m", "-XX:AOTCache=/opt/sennard/sennard-hook.aot", "-cp",
                "/opt/sennard/lib/sennard-hook-client-6.0.0.jar", "io.github.jaeseongs95.sennard.hookclient.HookClientMain", "--server-file=/home/u/.sennard/server.properties",
                "--jar=/opt/sennard/sennard-6.0.0.jar"));

        assertEquals("/opt/jdk/bin/java -XX:+UseSerialGC -Xmx256m -XX:AOTCache=/opt/sennard/sennard-hook.aot -cp /opt/sennard/lib/sennard-hook-client-6.0.0.jar "
                + "io.github.jaeseongs95.sennard.hookclient.HookClientMain --server-file=/home/u/.sennard/server.properties --jar=/opt/sennard/sennard-6.0.0.jar " + CALL,
                installation.hookCommandLine(ARGUMENTS));
    }

    @Test
    void theFirstWordIsQuotedByTheOldRulesAndTheOthersOnlyForASpaceOrAShellOperator() {
        HookInstallation installation = hooks(List.of("C:\\Program Files\\Java\\bin\\java.exe", "-XX:AOTCache=C:/Users/some one/sennard-hook.aot", "-cp",
                "C:\\Program Files\\Sennard\\lib\\sennard-hook-client-6.0.0.jar", "-Da=b,c;d", "-Dx=y:z", "-cp2", "lib/*", "-Dq=a&b", "-Dq=a|b", "-Dq=a<b", "-Dq=a>b", "-Dq=a^b", "-Dq=(b)",
                "--server-file=C:/Users/some one/.sennard/server.properties"));

        assertEquals("\"C:\\Program Files\\Java\\bin\\java.exe\" \"-XX:AOTCache=C:/Users/some one/sennard-hook.aot\" -cp \"C:\\Program Files\\Sennard\\lib\\sennard-hook-client-6.0.0.jar\" "
                + "-Da=b,c;d -Dx=y:z -cp2 lib/* \"-Dq=a&b\" \"-Dq=a|b\" \"-Dq=a<b\" \"-Dq=a>b\" \"-Dq=a^b\" \"-Dq=(b)\" \"--server-file=C:/Users/some one/.sennard/server.properties\" " + CALL,
                installation.hookCommandLine(ARGUMENTS));
    }

    @Test
    void aFirstWordWithACommaOrAnEqualsIsQuotedButTheSameWordLaterIsNot() {
        assertEquals("\"C:/a=b,c/java\" -cp C:/a=b,c/app.jar x", hooks(List.of("C:/a=b,c/java", "-cp", "C:/a=b,c/app.jar")).hookCommandLine(List.of("x")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-Dsay=\"hi\"", "-Dhome=%USERPROFILE%", "-Dline=a\nb", "-Dtab=a\tb", "-Dcr=a\rb", ""})
    void aLaterWordNoQuotingSurvivesIsRefusedWithThatWord(String word) {
        HookInstallation installation = hooks(List.of("java", "-cp", word));

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> installation.hookCommandLine(ARGUMENTS));
        assertEquals("This program path cannot be written in a hook command: " + word, refused.getMessage());
    }

    @Test
    void theExecutableAndTheArgumentsOfAListProgramAreTheFirstWordAndTheRest() {
        HookInstallation installation = hooks(List.of("C:/Program Files/Java/java.exe", "-Xmx256m", "-cp", "C:/Program Files/Sennard/lib/a.jar", "Main", "--server-file=C:/Users/x y/server.properties"));

        assertEquals("C:/Program Files/Java/java.exe", installation.hookExecutable());
        assertEquals(List.of("-Xmx256m", "-cp", "C:/Program Files/Sennard/lib/a.jar", "Main", "--server-file=C:/Users/x y/server.properties", "hook", "codex", "SessionStart",
                "--on-failure=guidance-stdout", "--bind-host"), installation.hookArguments(ARGUMENTS), "no quoting: no shell reads a list");
    }

    @Test
    void theExecutableAndTheArgumentsOfAOneWordProgramAreTheWordAndTheCallsArguments() {
        assertEquals("java", hooks("java").hookExecutable());
        assertEquals(ARGUMENTS, hooks("java").hookArguments(ARGUMENTS));
    }

    @Test
    void theRelayIsAnotherProgramWithItsOwnWordsAndItsHostsRegistrationTakesThemAsAList() {
        HookInstallation installation = new HookInstallation(List.of("java", "-cp", "client.jar", "HookClientMain"),
                List.of("C:/Program Files/Java/java.exe", "-Xmx64m", "-cp", "C:/Program Files/Sennard/client.jar", "McpRelayMain", "--server-file=C:/Users/x y/server.properties", "--jar=C:/Sennard/a.jar"));

        assertEquals("C:/Program Files/Java/java.exe", installation.mcpExecutable());
        assertEquals(List.of("-Xmx64m", "-cp", "C:/Program Files/Sennard/client.jar", "McpRelayMain", "--server-file=C:/Users/x y/server.properties", "--jar=C:/Sennard/a.jar", "--host=codex",
                "--start-server=30"), installation.mcpArguments(RELAY), "no quoting: a host starts an MCP server without a shell");
        assertEquals("java -cp client.jar HookClientMain " + CALL, installation.hookCommandLine(ARGUMENTS), "the relay's words are not in a hook's command line");
        assertEquals(List.of("-cp", "client.jar", "HookClientMain", "hook", "codex", "SessionStart", "--on-failure=guidance-stdout", "--bind-host"), installation.hookArguments(ARGUMENTS));
    }

    @Test
    void anEmptyListOfWordsOfEitherProgramIsRefusedAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new HookInstallation(List.of(), List.of("relay")));
        assertThrows(IllegalArgumentException.class, () -> new HookInstallation(List.of("hook"), List.of()));
    }

    @Test
    void theWordsAreCopiedSoTheListsGivenCanNoLongerChangeTheInstallation() {
        List<String> hook = new ArrayList<>(List.of("java", "-cp", "a.jar"));
        List<String> relay = new ArrayList<>(List.of("java", "-cp", "b.jar"));
        HookInstallation installation = new HookInstallation(hook, relay);
        hook.set(0, "other");
        hook.add("extra");
        relay.clear();

        assertEquals(List.of("java", "-cp", "a.jar"), installation.hookProgram());
        assertEquals(List.of("java", "-cp", "b.jar"), installation.mcpProgram());
        assertEquals("java", installation.hookExecutable());
        assertEquals("java", installation.mcpExecutable());
    }
}
