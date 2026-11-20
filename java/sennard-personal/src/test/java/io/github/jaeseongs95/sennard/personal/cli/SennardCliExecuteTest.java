// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.github.jaeseongs95.sennard.domain.error.SennardException;
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
import io.github.jaeseongs95.sennard.domain.error.UnavailableCode;
import io.github.jaeseongs95.sennard.domain.error.UnavailableException;
import io.github.jaeseongs95.sennard.personal.Sentences;

/**
 * Case table (target: {@link SennardCli#execute}; the command executor is a recording stand-in for the installer, which needs an installation):
 * <ol>
 * <li>--help: usage on stdout naming every command, exit 0, nothing on stderr; install --help: that command's usage with its option</li>
 * <li>--version: exactly "sennard &lt;version&gt;" on stdout, exit 0</li>
 * <li>no arguments, unknown command, unknown option, --state-dir without value, prepare without --retry: exit 2, stderr has the reason then
 * the usage, the executor is not called, stdout is empty</li>
 * <li>attachments with no action, an unknown action, rekey without --from, an option of another action, prepare without its file, send without --upload-id, a second file: exit 2, stderr has
 * the reason then the usage of attachments, the executor is not called, stdout is empty; attachments --help: the usage names every action and the file of prepare and send</li>
 * <li>a valid command line: the executor gets the parsed request and its exit code is returned (also attachments, whose action is its operand, and whose prepare and send have the file as
 * a second operand)</li>
 * <li>executor throws an SennardException (what a command stops with): exit 1, stderr "CODE: message", then the "Run Sennard commands with" line only when an installation exists</li>
 * <li>executor throws another exception: it is not swallowed</li>
 * </ol>
 */
class SennardCliExecuteTest {
    private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
    private final CliOutput output = new CliOutput(new PrintStream(stdout, true, StandardCharsets.UTF_8), new PrintStream(stderr, true, StandardCharsets.UTF_8));
    private final List<CliRequest.Run> executed = new ArrayList<>();

    private int execute(SennardCli.CommandExecutor executor, Optional<String> installed, String... args) {
        return SennardCli.execute(args, output, executor, () -> installed);
    }

    private int execute(String... args) {
        return execute(request -> {
            executed.add(request);
            return 0;
        }, Optional.empty(), args);
    }

    private String out() {
        return stdout.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return stderr.toString(StandardCharsets.UTF_8);
    }

    @Test
    void helpPrintsTheUsageAndExitsZero() {
        assertEquals(0, execute("--help"));
        for (SennardCommand command : SennardCommand.values()) {
            assertTrue(out().contains(command.commandName()) && out().contains(command.description()), command.name());
        }
        assertEquals("", err());
        assertEquals(List.of(), executed);
    }

    @Test
    void aCommandsHelpNamesItsOptions() {
        assertEquals(0, execute("install", "--help"));
        assertTrue(out().contains("sennard install") && out().contains("--state-dir"), out());
        assertFalse(out().contains("uninstall"), out());
        assertEquals(List.of(), executed);
    }

    @Test
    void versionIsOneLine() {
        assertEquals(0, execute("--version"));
        assertEquals("sennard " + SennardCli.version() + System.lineSeparator(), out());
        assertEquals("", err());
    }

    @Test
    void usageErrorsExitTwoWithTheReasonAndTheUsage() {
        String[][] lines = {{}, {"frobnicate"}, {"install", "--nope"}, {"install", "--state-dir"}, {"prepare"}};
        for (String[] line : lines) {
            stderr.reset();
            assertEquals(2, execute(line), String.join(" ", line));
            assertTrue(err().contains("Usage: sennard"), err());
            assertTrue(err().lines().count() > 2, err());
        }
        assertEquals("", out());
        assertEquals(List.of(), executed);
        stderr.reset();
        execute("prepare");
        assertTrue(err().startsWith("Missing required option: '--retry'"), err());
    }

    @Test
    void attachmentsHelpNamesEveryActionAndItsOptions() {
        assertEquals(0, execute("attachments", "--help"));
        assertTrue(out().contains("sennard attachments <action>"), out());
        for (AttachmentAction action : AttachmentAction.values()) {
            assertTrue(out().contains(action.actionName()), action.name() + " in " + out());
        }
        assertTrue(out().contains("--from=<keyId>") && out().contains("--id=<keyId>") && out().contains("--yes"), out());
        assertTrue(out().contains("prepare <file> [--name=<fileName>]") && out().contains("send <file> --upload-id=<uploadId>"), out());
        assertTrue(out().contains("--name=<fileName>") && out().contains("--upload-id=<uploadId>"), out());
        assertFalse(out().contains("--port"), "the server's port is not the caller's to give: the address file names it");
        assertEquals("", err());
        assertEquals(List.of(), executed);
    }

    @Test
    void attachmentsUsageErrorsExitTwoWithTheReasonAndTheUsageOfAttachments() {
        String[][] lines = {{"attachments"}, {"attachments", "frobnicate"}, {"attachments", "rekey"}, {"attachments", "check", "--from", "old"}, {"attachments", "prepare"},
                {"attachments", "send", "report.txt"}, {"attachments", "send", "--upload-id", "U1"}, {"attachments", "prepare", "a.txt", "b.txt"},
                {"attachments", "prepare", "--upload-id", "U1", "a.txt"}, {"attachments", "check", "stray"}};
        String[] reasons = {"Missing required parameter: '<action>'", "Unknown action: 'frobnicate'", "Missing required option: '--from'", "Option '--from' is not used by 'attachments check'",
                "Missing required parameter: '<file>'", "Missing required option: '--upload-id'", "Missing required parameter: '<file>'", "Unmatched argument: 'b.txt'",
                "Option '--upload-id' is not used by 'attachments prepare'", "Unmatched argument: 'stray'"};
        assertEquals(lines.length, reasons.length);
        for (int i = 0; i < lines.length; i++) {
            stderr.reset();
            assertEquals(2, execute(lines[i]), String.join(" ", lines[i]));
            assertTrue(err().startsWith(reasons[i]), err());
            assertTrue(err().contains("Usage: sennard attachments <action>"), err());
        }
        assertEquals("", out());
        assertEquals(List.of(), executed);
    }

    @Test
    void anAttachmentsLineReachesTheExecutorWithItsActionAndOptionAndItsCodeIsReturned() {
        assertEquals(1, execute(request -> {
            executed.add(request);
            return 1;
        }, Optional.empty(), "attachments", "rekey", "--from", "old"));
        assertEquals(1, executed.size());
        assertEquals(SennardCommand.ATTACHMENTS, executed.get(0).command());
        assertEquals(List.of("rekey"), executed.get(0).operands());
        assertEquals(Optional.of("old"), executed.get(0).value("--from"));
    }

    @Test
    void anAttachmentsPrepareOrSendLineReachesTheExecutorWithItsActionItsFileAndItsOptions() {
        assertEquals(0, execute("attachments", "prepare", "--name", "docs/a.txt", "report.txt"));
        assertEquals(3, execute(request -> {
            executed.add(request);
            return 3;
        }, Optional.empty(), "attachments", "send", "--upload-id", "U1", "report.txt"));
        assertEquals(2, executed.size());

        CliRequest.Run prepare = executed.get(0);
        assertEquals(SennardCommand.ATTACHMENTS, prepare.command());
        assertEquals(List.of("prepare", "report.txt"), prepare.operands());
        assertEquals(Optional.of("docs/a.txt"), prepare.value("--name"));

        CliRequest.Run send = executed.get(1);
        assertEquals(List.of("send", "report.txt"), send.operands());
        assertEquals(Optional.of("U1"), send.value("--upload-id"));
        assertEquals(Map.of("--upload-id", "U1"), send.options());
        assertEquals("", err());
        assertEquals("", out());
    }

    @Test
    void aValidLineReachesTheExecutorAndItsCodeIsReturned() {
        assertEquals(7, execute(request -> {
            executed.add(request);
            return 7;
        }, Optional.empty(), "install", "--state-dir", "D:\\state"));
        assertEquals(1, executed.size());
        assertEquals(SennardCommand.INSTALL, executed.get(0).command());
        assertEquals(Optional.of("D:\\state"), executed.get(0).value("--state-dir"));
    }

    @Test
    void aCliFailureExitsOneWithItsCodeAndMessage() {
        SennardCli.CommandExecutor failing = request -> {
            throw new UnavailableException(UnavailableCode.START_FAILED, SennardException.args("what", "notHealthy", "file", "server.properties"));
        };
        assertEquals(1, execute(failing, Optional.empty(), "start"));
        assertEquals("START_FAILED: The server did not become healthy; its address file is server.properties. See: sennard status" + System.lineSeparator(), err());
        assertEquals("", out());
    }

    @Test
    void aCliFailureAlsoSaysHowToRunTheInstalledCommand() {
        SennardCli.CommandExecutor failing = request -> {
            throw new UnavailableException(UnavailableCode.STORE_PREPARATION_TIMEOUT);
        };
        assertEquals(1, execute(failing, Optional.of("java -jar sennard-installed.jar"), "start"));
        List<String> lines = err().lines().toList();
        assertEquals(List.of("STORE_PREPARATION_TIMEOUT: The store was not ready within the time allowed. See: sennard status", "Run Sennard commands with: java -jar sennard-installed.jar"), lines);
    }

    @Test
    void everyCategoryOfFailureIsToldWithItsCodeAndExitsOne() {
        List<SennardException> failures = List.of(new InvalidInputException(InputCode.STATE_DIR_NOT_REPRESENTABLE), new NotFoundException(NotFoundCode.NOT_INSTALLED, SennardException.args("what", "other", "root", "R")),
                new ConflictException(ConflictCode.SAME_VERSION, SennardException.args("version", "6.0.0")), new GateRefusedException(GateCode.NOT_A_RELEASE_JAR),
                new IntegrityException(IntegrityCode.HOST_ID_INVALID, SennardException.args("file", "F")), new UnavailableException(UnavailableCode.JAVA_EXECUTABLE_UNKNOWN),
                new InvalidInputException(InputCode.INSTALL_ROOT_NOT_REPRESENTABLE, SennardException.args("root", "R")), new ConflictException(ConflictCode.SERVER_ALREADY_RUNNING));
        for (SennardException failure : failures) {
            SennardCli.CommandExecutor failing = request -> {
                throw failure;
            };
            assertEquals(1, execute(failing, Optional.empty(), "start"), failure.code().name());
        }
        assertTrue(err().contains("NOT_INSTALLED: Sennard is not installed in R. Use: sennard install"), err());
        assertTrue(err().contains("NOT_A_RELEASE_JAR: Run this from the release jar: java -jar sennard-<version>.jar <command>."), err());
        assertTrue(err().contains("JAVA_EXECUTABLE_UNKNOWN: The JVM did not report the java program it runs from"), err());
        assertTrue(err().contains("INSTALL_ROOT_NOT_REPRESENTABLE: The installation folder R cannot be written in this system's file name encoding"), err());
        assertTrue(err().contains("SERVER_ALREADY_RUNNING: Another Sennard server already runs on this state folder."), err());
        // The catalog tells the same failures in Korean to a caller that asks for it; the CLI itself speaks English.
        assertTrue(Sentences.korean(new GateRefusedException(GateCode.NOT_A_RELEASE_JAR)).contains("릴리스 jar"), Sentences.korean(new GateRefusedException(GateCode.NOT_A_RELEASE_JAR)));
        assertTrue(Sentences.korean(new UnavailableException(UnavailableCode.JAVA_EXECUTABLE_UNKNOWN)).contains("java 프로그램"));
        assertTrue(Sentences.korean(new InvalidInputException(InputCode.INSTALL_ROOT_NOT_REPRESENTABLE, SennardException.args("root", "R"))).contains("SENNARD_INSTALL_ROOT"));
        assertTrue(Sentences.korean(new ConflictException(ConflictCode.SERVER_ALREADY_RUNNING)).contains("이미 실행 중"));
    }

    @Test
    void anyOtherExceptionIsNotSwallowed() {
        IllegalStateException boom = new IllegalStateException("boom");
        SennardCli.CommandExecutor failing = request -> {
            throw boom;
        };
        assertEquals(boom, assertThrows(IllegalStateException.class, () -> execute(failing, Optional.empty(), "start")));
    }
}
