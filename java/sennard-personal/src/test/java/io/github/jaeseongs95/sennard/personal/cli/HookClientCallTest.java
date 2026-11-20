// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.github.jaeseongs95.sennard.domain.error.InputCode;
import io.github.jaeseongs95.sennard.domain.error.InvalidInputException;
import io.github.jaeseongs95.sennard.hookclient.HookClientCall;
import io.github.jaeseongs95.sennard.personal.Sentences;

/**
 * The CLI's side of the hook description. There is no {@code sennard hook} command any more (the hosts run the hook client's own JVM, whose reader of the line is the hook-client
 * module's {@code HookClientCallTest}); what the CLI still has is {@code sennard start --within}, the option the hook client and the MCP relay start the server with when a session
 * start finds none ({@link HookClientCall#START_COMMAND}, {@link HookClientCall#START_WITHIN}). Case table (input: a command line; expected: the run it earns or the failure; judged by
 * record equality and the failure's code):
 * <ol>
 * <li>{@code sennard start --within=5} and {@code --within 5}: a run of START with the same value; {@code sennard start -h}: the usage has {@code --within}</li>
 * <li>the line the client writes ({@link HookClientCall#START_COMMAND}, {@link HookClientCall#START_WITHIN} {@code =} the wait of a session start) is accepted as it is</li>
 * <li>{@code sennard start --within=abc}, {@code =1.5}, {@code =} (empty), {@code =99999999999}: HOOK_OPTION_INVALID naming {@code --within}, exit 1 on stderr, no server started
 * (the number is read before the installer is asked to do anything; {@code SennardCli.run} only builds the installer)</li>
 * <li>the other commands take no operand except attachments, whose operand is its action</li>
 * </ol>
 */
class HookClientCallTest {
    private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
    private final CliOutput output = new CliOutput(new PrintStream(stdout, true, StandardCharsets.UTF_8), new PrintStream(stderr, true, StandardCharsets.UTF_8));

    private static CliRequest.Run run(String... args) {
        return assertInstanceOf(CliRequest.Run.class, CliParser.parse(args));
    }

    private static CliRequest.UsageError usageError(String... args) {
        return assertInstanceOf(CliRequest.UsageError.class, CliParser.parse(args));
    }

    private static String message(ByteArrayOutputStream stream) {
        return stream.toString(StandardCharsets.UTF_8);
    }

    @Test
    void startTakesWithinInBothFormsAndItsUsageNamesIt() {
        CliRequest.Run equalsForm = run("start", "--within=5");
        CliRequest.Run spaceForm = run("start", "--within", "5");

        assertEquals(SennardCommand.START, equalsForm.command());
        assertEquals(Optional.of("5"), equalsForm.value(HookClientCall.START_WITHIN));
        assertEquals(equalsForm, spaceForm);
        assertEquals(new CliRequest.Help(SennardCommand.START), CliParser.parse(new String[] {"start", "-h"}));
        assertTrue(CliUsage.of(SennardCommand.START).contains("--within"), CliUsage.of(SennardCommand.START));
        assertEquals(Optional.empty(), run("start").value(HookClientCall.START_WITHIN), "without the option the start is the plain one");
    }

    @Test
    void theClientStartsTheServerWithTheWordsTheCommandAccepts() {
        // The client (JarServerStart) writes START_COMMAND and START_WITHIN + "=" + seconds after the jar; the command must accept exactly that line.
        CliRequest.Run parsed = run(HookClientCall.START_COMMAND, HookClientCall.START_WITHIN + "=" + HookClientCall.START_SERVER_SECONDS);

        assertEquals(SennardCommand.START, parsed.command());
        assertEquals(Optional.of(String.valueOf(HookClientCall.START_SERVER_SECONDS)), parsed.value(HookClientCall.START_WITHIN));
    }

    @Test
    void aWithinThatIsNotANumberIsHookOptionInvalidAndStartsNothing() {
        for (String option : List.of("--within=abc", "--within=1.5", "--within=", "--within=99999999999")) {
            InvalidInputException failure = assertThrows(InvalidInputException.class, () -> SennardCli.run(run("start", option), output), option);
            assertEquals(InputCode.HOOK_OPTION_INVALID, failure.code(), option);
            assertTrue(Sentences.english(failure).contains("--within"), Sentences.english(failure));
        }
    }

    @Test
    void aWithinThatIsNotANumberIsExitOneWithItsCodeOnStderr() {
        int exit = SennardCli.execute(new String[] {"start", "--within=abc"}, output, request -> SennardCli.run(request, output), Optional::empty);

        assertEquals(1, exit);
        assertEquals("", message(stdout));
        assertTrue(message(stderr).startsWith("HOOK_OPTION_INVALID: --within is not a number: abc"), message(stderr));
    }

    @Test
    void theOtherCommandsStillTakeNoOperand() {
        for (SennardCommand command : SennardCommand.values()) {
            if (command == SennardCommand.ATTACHMENTS) {
                // The other command with an operand: its action, one only.
                assertEquals(List.of("action"), command.operands());
                assertEquals("Unmatched argument: 'stray'", usageError(command.commandName(), "check", "stray").message());
                continue;
            }
            assertEquals(List.of(), command.operands(), command.commandName());
            String[] args = command == SennardCommand.PREPARE ? new String[] {command.commandName(), "--retry", "stray"} : new String[] {command.commandName(), "stray"};
            assertEquals("Unmatched argument: 'stray'", usageError(args).message(), command.commandName());
        }
    }
}
