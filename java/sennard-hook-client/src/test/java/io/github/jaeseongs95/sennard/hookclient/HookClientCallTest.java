// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.hookclient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import io.github.jaeseongs95.sennard.hookclient.HookClientArguments.Parsed;
import io.github.jaeseongs95.sennard.hookclient.HookClientCall.FailureAnswer;
import io.github.jaeseongs95.sennard.hookclient.HookOptionException.Problem;

/**
 * The description a hook definition is generated from and the client reads back ({@link HookClientCall#arguments()}, {@link HookClientArguments#parse}). Case table (input: a call,
 * its {@code arguments()}, a command line; expected: the same call, or the {@link HookOptionException} the command line earns, judged by record equality and by the exception's
 * problem, option, value and message):
 * <ol>
 * <li>every combination of module (none, continuity, wake), failure answer (all 6), timeout (default, 1, a day), bind-host, wake, only-if-env (none, a value, a value holding
 * {@code =}) and start-server (none, 1, the adapters' wait): {@code arguments()} read by {@code parse} (with the installation's {@code --server-file} and {@code --jar}) gives a call
 * equal to the original; the call writes no port, no {@code --server-file}, no {@code --jar} and no {@code --warm}</li>
 * <li>the exact arguments of the calls the adapters build (a plain call, the session start, the session start that starts the server, a short timeout, the idle wake); every
 * argument is free of spaces</li>
 * <li>options in any place among the operands read the same call; the defaults apply when no option is given</li>
 * <li>{@code --server-file=<path>} and {@code --jar=<path>} (the installation's: handed over as written, a value holding {@code =} or a Windows path too) and {@code --warm=<label>=<file>}
 * are no part of the call; the server file is required, and the jar when the call asks to start the server</li>
 * <li>a timeout or a start wait that is not a number (abc, 1.5, empty, above the int range): NOT_A_NUMBER with the option and the text</li>
 * <li>an unknown failure answer: UNKNOWN_VALUE with the option and the text</li>
 * <li>OTHER: an option that does not exist (with a value and as a flag; {@code --port} and {@code --launcher} are gone and are such), a valued option without {@code =value}
 * ({@code --timeout 5} is refused at {@code --timeout}: the space form is not a value), a flag with a value, an option given twice (valued and flag), no operand, one, four, a first
 * operand other than {@code hook}, no {@code --server-file}, a start with no {@code --jar}</li>
 * <li>the message of each is {@code HOOK_OPTION_INVALID <problem code> <option>=<value>}</li>
 * <li>each failure answer has its option name and is found by it, by that exact name only</li>
 * </ol>
 */
class HookClientCallTest {
    private static final List<String> MODULES = Arrays.asList(null, "continuity", "wake");
    private static final List<Integer> TIMEOUTS = List.of(HookClientCall.DEFAULT_TIMEOUT_SECONDS, 1, 86_400);
    private static final List<String> CONDITIONS = Arrays.asList(null, "CLAUDE_CODE_SESSION_ATTENDED=1", "A=b=c");
    /** 0: starts nothing (no option is written). */
    private static final List<Integer> START_SERVER = List.of(0, 1, HookClientCall.START_SERVER_SECONDS);
    /** The installation's two options, which every command line carries. */
    private static final List<String> INSTALLATION = List.of("--server-file=/state/server.properties", "--jar=/install/versions/6.0.0/sennard-6.0.0.jar");
    private static final String SERVER_FILE = "--server-file=/state/server.properties";

    private static final HookClientCall DEFAULT_CALL = new HookClientCall("agy", "PreInvocation", null, FailureAnswer.FAIL, HookClientCall.DEFAULT_TIMEOUT_SECONDS, false, false, null, 0,
            HookClientCall.RefusalAnswer.AS_FAILURE);

    private static List<String> withInstallation(List<String> words) {
        List<String> all = new ArrayList<>(words);
        all.addAll(INSTALLATION);
        return all;
    }

    private static HookOptionException refused(String... words) {
        return assertThrows(HookOptionException.class, () -> HookClientArguments.parse(List.of(words)), String.join(" ", words));
    }

    private static void assertRefused(Problem problem, String option, String value, String... words) {
        HookOptionException failure = refused(words);
        String where = String.join(" ", words);
        assertEquals(problem, failure.problem(), where);
        assertEquals(option, failure.option(), where);
        assertEquals(value, failure.value(), where);
        assertEquals("HOOK_OPTION_INVALID " + problem.code() + " " + option + "=" + value, failure.getMessage(), where);
    }

    @Test
    void everyCallIsReadBackFromItsArgumentsAsTheSameCall() {
        int checked = 0;
        for (String module : MODULES) {
            for (FailureAnswer failure : FailureAnswer.values()) {
                for (int timeout : TIMEOUTS) {
                    for (boolean bindHost : List.of(false, true)) {
                        for (boolean wake : List.of(false, true)) {
                            for (String condition : CONDITIONS) {
                                for (int startServer : START_SERVER) {
                                    HookClientCall call = new HookClientCall("gemini", "SessionStart", module, failure, timeout, bindHost, wake, condition, startServer,
                                            HookClientCall.RefusalAnswer.AS_FAILURE);
                                    assertEquals(call, HookClientArguments.parse(withInstallation(call.arguments())).call(), call.arguments().toString());
                                    call.arguments().forEach(argument -> assertFalse(argument.startsWith("--server-file") || argument.startsWith("--jar") || argument.startsWith("--port")
                                            || argument.startsWith("--warm"), "the call writes none of the installation's options and no port: " + argument));
                                    checked++;
                                }
                            }
                        }
                    }
                }
            }
        }
        assertEquals(3 * FailureAnswer.values().length * 3 * 2 * 2 * 3 * 3, checked);
    }

    @Test
    void theAdaptersCallsHaveTheseExactArguments() {
        assertEquals(List.of("hook", "claude", "PreToolUse"), HookClientCall.of("claude", "PreToolUse", FailureAnswer.FAIL).arguments());
        assertEquals(List.of("hook", "claude", "SessionStart", "--on-failure=guidance-stderr", "--bind-host"),
                HookClientCall.of("claude", "SessionStart", FailureAnswer.GUIDANCE_STDERR).bindingHost().arguments());
        assertEquals(List.of("hook", "claude", "SessionStart", "--on-failure=guidance-stderr", "--bind-host", "--start-server=" + HookClientCall.START_SERVER_SECONDS),
                HookClientCall.of("claude", "SessionStart", FailureAnswer.GUIDANCE_STDERR).bindingHost().startingServer(HookClientCall.START_SERVER_SECONDS).arguments(),
                "the session start asks for the server start; no other call of an adapter does");
        assertEquals(List.of("hook", "codex", "SessionEnd", "--timeout=1"), HookClientCall.of("codex", "SessionEnd", FailureAnswer.FAIL).withTimeout(1).arguments());
        assertEquals(List.of("hook", "claude", "PreToolUse", "--module=governance", "--timeout=4"),
                HookClientCall.of("claude", "PreToolUse", FailureAnswer.FAIL).inModule("governance").withTimeout(4).arguments());
        assertEquals(List.of("hook", "claude", "Stop", "--module=wake", "--timeout=86400", "--wake", "--only-if-env=CLAUDE_CODE_SESSION_ATTENDED=1"),
                HookClientCall.of("claude", "Stop", FailureAnswer.GUIDANCE_STDERR).asWake(86_400, "CLAUDE_CODE_SESSION_ATTENDED=1").arguments(),
                "a wake always answers FAIL, so no --on-failure is written");
    }

    /** The refusal answer is written only when it is not the default, and is read back as the same call. */
    @ParameterizedTest
    @EnumSource(HookClientCall.RefusalAnswer.class)
    void everyRefusalAnswerIsReadBackFromItsArgumentsAsTheSameCall(HookClientCall.RefusalAnswer refusal) {
        HookClientCall call = new HookClientCall("agy", "PreToolUse", null, FailureAnswer.ALLOW_DECISION, HookClientCall.DEFAULT_TIMEOUT_SECONDS, false, false, null, 0, refusal);

        assertEquals(call, HookClientArguments.parse(withInstallation(call.arguments())).call(), call.arguments().toString());
        assertEquals(refusal != HookClientCall.RefusalAnswer.AS_FAILURE, call.arguments().contains("--on-refusal=" + refusal.option()), call.arguments().toString());
        call.arguments().forEach(argument -> assertFalse(argument.contains(" "), argument));
    }

    @Test
    void aGatesCallsHaveTheseExactArguments() {
        assertEquals(List.of("hook", "agy", "PreToolUse", "--on-failure=allow-decision", "--on-refusal=deny-decision"),
                HookClientCall.of("agy", "PreToolUse", FailureAnswer.ALLOW_DECISION).denyingRefusal().arguments());
        assertEquals(List.of("hook", "gemini", "BeforeTool", "--on-refusal=deny-decision"), HookClientCall.of("gemini", "BeforeTool", FailureAnswer.FAIL).denyingRefusal().arguments());
        assertEquals(HookClientCall.RefusalAnswer.AS_FAILURE, HookClientCall.of("gemini", "AfterTool", FailureAnswer.FAIL).onRefusal(), "a call that is no gate does not deny a refusal");
    }

    @Test
    void aRefusalAnswerThatDoesNotExistIsRefusedWithItsOptionAndText() {
        assertRefused(Problem.UNKNOWN_VALUE, "--on-refusal", "explode", "hook", "claude", "Stop", "--on-refusal=explode", SERVER_FILE);
        assertRefused(Problem.OTHER, "--on-refusal", "deny-decision", "hook", "claude", "Stop", "--on-refusal=deny-decision", "--on-refusal=deny-decision", SERVER_FILE);
    }

    @Test
    void everyArgumentIsFreeOfSpacesSoAHostThatSplitsAtSpacesReadsTheSameWords() {
        for (FailureAnswer failure : FailureAnswer.values()) {
            HookClientCall call = new HookClientCall("claude", "SessionStart", "continuity", failure, 3, true, true, "CLAUDE_CODE_SESSION_ATTENDED=1", 20, HookClientCall.RefusalAnswer.AS_FAILURE);
            call.arguments().forEach(argument -> assertFalse(argument.contains(" "), argument));
        }
    }

    @Test
    void theOperandsAreLabelAndEventInOrderAndTheDefaultsApply() {
        Parsed parsed = HookClientArguments.parse(withInstallation(List.of("hook", "agy", "PreInvocation")));

        assertEquals(DEFAULT_CALL, parsed.call());
        assertNull(parsed.warm());
    }

    @Test
    void optionsAreReadInAnyPlaceAmongTheOperands() {
        HookClientCall expected = new HookClientCall("claude", "Stop", "continuity", FailureAnswer.ALLOW_DECISION, 2, true, true, "A=b", 5, HookClientCall.RefusalAnswer.AS_FAILURE);
        List<String> options = new ArrayList<>(List.of("--timeout=2", "--on-failure=allow-decision", "--module=continuity", "--bind-host", "--wake", "--only-if-env=A=b", "--start-server=5"));
        options.addAll(INSTALLATION);

        for (int at = 0; at <= 3; at++) {
            List<String> words = new ArrayList<>(List.of("hook", "claude", "Stop"));
            words.addAll(at, options);
            assertEquals(expected, HookClientArguments.parse(words).call(), "the options inserted before operand " + at + ": " + words);
        }
        assertEquals(expected, HookClientArguments.parse(List.of("--bind-host", "hook", "--server-file=/state/server.properties", "claude", "--timeout=2", "Stop", "--on-failure=allow-decision",
                "--module=continuity", "--wake", "--only-if-env=A=b", "--start-server=5", "--jar=/install/versions/6.0.0/sennard-6.0.0.jar")).call());
    }

    @Test
    void theServerFileTheJarAndTheWarmValueAreHandedOverAsWrittenAndAreNoPartOfTheCall() {
        Parsed parsed = HookClientArguments.parse(List.of("hook", "agy", "PreInvocation", "--server-file=C:\\Users\\Jaeseong Song\\.sennard\\server.properties",
                "--jar=C:\\Users\\Jaeseong Song\\.sennard\\install\\sennard=6.jar", "--warm=agy=C:\\training\\agy=input.json"));

        assertEquals(DEFAULT_CALL, parsed.call());
        assertEquals("C:\\Users\\Jaeseong Song\\.sennard\\server.properties", parsed.serverFile().toString(), "the path as written");
        assertEquals("agy=C:\\training\\agy=input.json", parsed.warm());
        assertEquals(Path.of("/opt/sennard/server.properties"), HookClientArguments.parse(List.of("--server-file=/opt/sennard/server.properties", "hook", "agy", "PreInvocation")).serverFile());
        assertNull(HookClientArguments.parse(List.of("--server-file=/opt/sennard/server.properties", "hook", "agy", "PreInvocation")).jar(), "no --jar: the call starts no server");
        assertEquals(Path.of("/opt/sennard/sennard.jar"),
                HookClientArguments.parse(List.of(SERVER_FILE, "--start-server=3", "--jar=/opt/sennard/sennard.jar", "hook", "agy", "SessionStart")).jar());
    }

    @Test
    void theServerFileIsRequiredBecauseThereIsNoPortToFallBackTo() {
        assertRefused(Problem.OTHER, "--server-file", "", "hook", "agy", "PreInvocation");
        assertRefused(Problem.OTHER, "--server-file", "", "hook", "agy", "PreInvocation", "--jar=/opt/sennard/sennard.jar");
    }

    @Test
    void aCallThatStartsTheServerNeedsTheJarThatDoes() {
        assertRefused(Problem.OTHER, "--jar", "", "hook", "agy", "SessionStart", SERVER_FILE, "--start-server=30");
        HookClientArguments.parse(List.of("hook", "agy", "SessionStart", SERVER_FILE, "--start-server=0")); // 0 starts nothing: no jar is needed
    }

    @Test
    void aValueThatIsNotANumberIsRefusedWithItsOptionAndText() {
        assertRefused(Problem.NOT_A_NUMBER, "--timeout", "1.5", "hook", "claude", "Stop", SERVER_FILE, "--timeout=1.5");
        assertRefused(Problem.NOT_A_NUMBER, "--timeout", "", "hook", "claude", "Stop", SERVER_FILE, "--timeout=");
        assertRefused(Problem.NOT_A_NUMBER, "--timeout", "99999999999", "hook", "claude", "Stop", SERVER_FILE, "--timeout=99999999999");
        assertRefused(Problem.NOT_A_NUMBER, "--start-server", "soon", "hook", "claude", "Stop", SERVER_FILE, "--start-server=soon");
    }

    @Test
    void anUnknownFailureAnswerIsRefusedWithItsOptionAndText() {
        assertRefused(Problem.UNKNOWN_VALUE, "--on-failure", "explode", "hook", "claude", "Stop", SERVER_FILE, "--on-failure=explode");
        assertRefused(Problem.UNKNOWN_VALUE, "--on-failure", "FAIL", "hook", "claude", "Stop", SERVER_FILE, "--on-failure=FAIL");
        assertRefused(Problem.UNKNOWN_VALUE, "--on-failure", "", "hook", "claude", "Stop", SERVER_FILE, "--on-failure=");
    }

    @Test
    void anOptionThatDoesNotExistIsRefusedAndNoPortOrLauncherOptionExistsAnyMore() {
        assertRefused(Problem.OTHER, "--nope", "1", "hook", "claude", "Stop", SERVER_FILE, "--nope=1");
        assertRefused(Problem.OTHER, "--nope", "", "hook", "claude", "Stop", SERVER_FILE, "--nope");
        assertRefused(Problem.OTHER, "--", "", "hook", "claude", "Stop", SERVER_FILE, "--");
        assertRefused(Problem.OTHER, "--port", "1234", "hook", "claude", "Stop", SERVER_FILE, "--port=1234");
        assertRefused(Problem.OTHER, "--launcher", "/opt/sennard", "hook", "claude", "Stop", SERVER_FILE, "--launcher=/opt/sennard");
        assertRefused(Problem.OTHER, "--server-file-name", "x", "hook", "claude", "Stop", SERVER_FILE, "--server-file-name=x");
    }

    @Test
    void aValuedOptionWithoutAValueIsRefusedAndTheSpaceFormIsNotAValue() {
        assertRefused(Problem.OTHER, "--timeout", "", "hook", "claude", "Stop", SERVER_FILE, "--timeout");
        assertRefused(Problem.OTHER, "--jar", "", "hook", "claude", "Stop", SERVER_FILE, "--jar");
        assertRefused(Problem.OTHER, "--server-file", "", "hook", "claude", "Stop", "--server-file");
        assertRefused(Problem.OTHER, "--timeout", "", "hook", "claude", "Stop", SERVER_FILE, "--timeout", "5");
    }

    @Test
    void aFlagWithAValueIsRefused() {
        assertRefused(Problem.OTHER, "--wake", "1", "hook", "claude", "Stop", SERVER_FILE, "--wake=1");
        assertRefused(Problem.OTHER, "--bind-host", "", "hook", "claude", "Stop", SERVER_FILE, "--bind-host=");
    }

    @Test
    void anOptionGivenTwiceIsRefused() {
        assertRefused(Problem.OTHER, "--timeout", "2", "hook", "claude", "Stop", SERVER_FILE, "--timeout=1", "--timeout=2");
        assertRefused(Problem.OTHER, "--wake", "", "hook", "claude", "Stop", SERVER_FILE, "--wake", "--wake");
        assertRefused(Problem.OTHER, "--server-file", "/b", "--server-file=/a", "hook", "claude", "Stop", "--server-file=/b");
    }

    @Test
    void theOperandsAreHookLabelAndEventAndNothingElse() {
        assertRefused(Problem.OTHER, "operands", "", new String[] {SERVER_FILE});
        assertRefused(Problem.OTHER, "operands", "hook", "hook", SERVER_FILE);
        assertRefused(Problem.OTHER, "operands", "hook claude", "hook", "claude", SERVER_FILE);
        assertRefused(Problem.OTHER, "operands", "hook claude Stop extra", "hook", "claude", "Stop", "extra", SERVER_FILE);
        assertRefused(Problem.OTHER, "operands", "run claude Stop", "run", "claude", "Stop", SERVER_FILE);
        assertRefused(Problem.OTHER, "operands", "claude Stop hook", "claude", "Stop", "hook", SERVER_FILE);
    }

    @ParameterizedTest
    @EnumSource(FailureAnswer.class)
    void everyFailureAnswerHasItsOptionNameAndIsFoundByIt(FailureAnswer answer) {
        assertEquals(Optional.of(answer), FailureAnswer.ofOption(answer.option()));
        assertEquals(Optional.empty(), FailureAnswer.ofOption(answer.option().toUpperCase(Locale.ROOT)), "the names are exact");
        assertEquals(Optional.empty(), FailureAnswer.ofOption(" " + answer.option()), "the names are exact");
    }

    @Test
    void theFailureAnswersOptionNamesAreDistinctAndUnknownNamesAreFoundByNothing() {
        Set<String> names = Arrays.stream(FailureAnswer.values()).map(FailureAnswer::option).collect(Collectors.toSet());

        assertEquals(FailureAnswer.values().length, names.size());
        assertEquals(Optional.empty(), FailureAnswer.ofOption("explode"));
        assertEquals(Optional.empty(), FailureAnswer.ofOption(""));
    }
}
