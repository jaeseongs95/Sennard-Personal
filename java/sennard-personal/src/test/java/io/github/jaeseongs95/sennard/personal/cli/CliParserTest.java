// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.github.jaeseongs95.sennard.hookclient.HookClientCall;

/**
 * Case table (target: {@link CliParser#parse}; input: the argument array; expected: the request; judged by comparing the record):
 * <ol>
 * <li>each of the 10 commands without options except prepare needing --retry and attachments needing its action: Run with that command</li>
 * <li>hook: not a command (the hosts run the hook client's own JVM): usage error naming it; start --within=N and --within N: Run of START with that value</li>
 * <li>install --state-dir X and install --state-dir=X: Run with the same option value</li>
 * <li>a value containing '=' or spaces after the space form is kept whole</li>
 * <li>prepare without --retry: usage error naming --retry; with --retry: Run</li>
 * <li>admin-reset with and without --yes: Run with and without the flag</li>
 * <li>attachments: the action first (check, rekey, keygen, purge-orphans, prepare, upload, send), each Run with its options: rekey --from (space and = forms), keygen --id, purge-orphans with and without --yes</li>
 * <li>attachments prepare and send take the file as an operand after the action's name: prepare &lt;file&gt; (with and without --name, the file before or after the option), send --upload-id ID &lt;file&gt; (with
 * the file before or after the option; no --port: the server's address is read from its address file); the operands of the Run are [action, file]</li>
 * <li>attachments: no action, an unknown action, a stray second operand (check, rekey, keygen, purge-orphans each refuse one), rekey without --from, keygen without --id, an option of another action
 * (check --from, rekey --id, keygen --yes, prepare --upload-id, send --name; --port, which no action takes, is an unknown option), a value-less --from, a flag given a value, an unknown option: usage errors that name the action or the
 * option, with the command's usage</li>
 * <li>attachments prepare without its file, send without its file, send without --upload-id, a second file for prepare or send: usage errors ("Missing required parameter: '&lt;file&gt;'",
 * "Missing required option: '--upload-id'", "Unmatched argument")</li>
 * <li>attachments -h, attachments rekey --help: the usage of attachments, which names every action, the file operand of prepare and send, and the options of each</li>
 * <li>unknown command, unknown option, --state-dir without a value, a flag given a value, an option twice, a stray word: usage errors</li>
 * <li>no arguments: usage error "Missing required subcommand" (exit 2 in {@link SennardCliExecuteTest})</li>
 * <li>-h/--help and -V/--version, alone and after a command: Help (of the command) and Version</li>
 * </ol>
 */
class CliParserTest {
    private static CliRequest.Run run(String... args) {
        return assertInstanceOf(CliRequest.Run.class, CliParser.parse(args));
    }

    private static CliRequest.UsageError usageError(String... args) {
        return assertInstanceOf(CliRequest.UsageError.class, CliParser.parse(args));
    }

    @Test
    void everyCommandParsesByItsName() {
        for (SennardCommand command : SennardCommand.values()) {
            String[] args = switch (command) {
                case PREPARE -> new String[] {command.commandName(), "--retry"};
                case ATTACHMENTS -> new String[] {command.commandName(), "check"}; // a command with an action, which needs no option
                default -> new String[] {command.commandName()};
            };
            CliRequest.Run parsed = run(args);
            assertEquals(command, parsed.command(), command.name());
        }
        assertEquals(10, SennardCommand.values().length);
    }

    @Test
    void theCommandNamesAreTheOnesTheInstallerCalls() {
        assertEquals(java.util.List.of("install", "uninstall", "start", "stop", "status", "upgrade", "prepare", "hooks", "admin-reset", "attachments"),
                java.util.Arrays.stream(SennardCommand.values()).map(SennardCommand::commandName).toList());
    }

    @Test
    void stateDirectoryIsReadInBothForms() {
        assertEquals(Map.of("--state-dir", "C:\\sennard state"), run("install", "--state-dir", "C:\\sennard state").options());
        assertEquals(Map.of("--state-dir", "C:\\sennard state"), run("install", "--state-dir=C:\\sennard state").options());
        assertEquals(Optional.of("/x"), run("install", "--state-dir", "/x").value("--state-dir"));
    }

    @Test
    void aValueWithAnEqualsSignIsKeptWhole() {
        assertEquals(Optional.of("a=b"), run("install", "--state-dir", "a=b").value("--state-dir"));
        assertEquals(Optional.of("a=b"), run("install", "--state-dir=a=b").value("--state-dir"));
    }

    @Test
    void installWithoutAnOptionHasNone() {
        assertEquals(Map.of(), run("install").options());
    }

    @Test
    void prepareNeedsRetry() {
        CliRequest.UsageError error = usageError("prepare");
        assertTrue(error.message().contains("--retry"), error.message());
        assertEquals(SennardCommand.PREPARE, error.command());
        assertTrue(run("prepare", "--retry").has("--retry"));
    }

    @Test
    void adminResetIsADryRunUnlessYes() {
        assertEquals(false, run("admin-reset").has("--yes"));
        assertEquals(true, run("admin-reset", "--yes").has("--yes"));
    }

    @Test
    void attachmentsReadsItsActionAsTheOperandAndTheOptionsOfThatAction() {
        CliRequest.Run check = run("attachments", "check");
        assertEquals(SennardCommand.ATTACHMENTS, check.command());
        assertEquals(java.util.List.of("check"), check.operands());
        assertEquals(Map.of(), check.options());

        assertEquals(Map.of("--from", "old"), run("attachments", "rekey", "--from", "old").options());
        assertEquals(Map.of("--from", "old"), run("attachments", "rekey", "--from=old").options());
        assertEquals(java.util.List.of("rekey"), run("attachments", "--from", "old", "rekey").operands(), "options may come first");
        assertEquals(Map.of("--id", "key-2"), run("attachments", "keygen", "--id", "key-2").options());
        assertEquals(false, run("attachments", "purge-orphans").has("--yes"));
        assertEquals(true, run("attachments", "purge-orphans", "--yes").has("--yes"));
    }

    @Test
    void everyActionOfAttachmentsIsOneThatTheCommandHandles() {
        assertEquals(java.util.List.of("check", "rekey", "keygen", "purge-orphans", "prepare", "upload", "send"), SennardCommand.ATTACHMENTS.choices().stream().map(SennardCommand.Choice::value).toList());
        for (AttachmentAction action : AttachmentAction.values()) {
            assertEquals(action, AttachmentAction.named(action.actionName()).orElseThrow(), action.name());
            assertTrue(SennardCommand.ATTACHMENTS.choice(action.actionName()).isPresent(), action.name());
        }
    }

    @Test
    void prepareUploadAndSendTakeAFileAsTheirOperand() {
        for (AttachmentAction action : AttachmentAction.values()) {
            boolean takesAFile = action == AttachmentAction.PREPARE || action == AttachmentAction.UPLOAD || action == AttachmentAction.SEND;
            assertEquals(takesAFile ? java.util.List.of("file") : java.util.List.of(), SennardCommand.ATTACHMENTS.choice(action.actionName()).orElseThrow().operands(), action.name());
        }
    }

    @Test
    void attachmentsPrepareReadsItsFileAsTheOperandAfterTheAction() {
        CliRequest.Run plain = run("attachments", "prepare", "report.txt");
        assertEquals(SennardCommand.ATTACHMENTS, plain.command());
        assertEquals(java.util.List.of("prepare", "report.txt"), plain.operands());
        assertEquals(Map.of(), plain.options());

        CliRequest.Run named = run("attachments", "prepare", "--name", "a/b.txt", "report.txt");
        assertEquals(java.util.List.of("prepare", "report.txt"), named.operands());
        assertEquals(Map.of("--name", "a/b.txt"), named.options());
        assertEquals(named, run("attachments", "prepare", "report.txt", "--name=a/b.txt"), "the file may come before the option");
        assertEquals(named, run("attachments", "--name", "a/b.txt", "prepare", "report.txt"), "the option may come before the action");
    }

    @Test
    void attachmentsUploadReadsTheSessionAndFileAndRequiresBoth() {
        assertEquals(java.util.List.of("upload", "a file.txt"), run("attachments", "upload", "--session-ref", "REF", "a file.txt").operands());
        assertEquals(Map.of("--session-ref", "REF", "--operation-key", "KEY", "--name", "folder/report.txt"),
                run("attachments", "upload", "a file.txt", "--session-ref=REF", "--operation-key=KEY", "--name=folder/report.txt").options());
        assertEquals("Missing required option: '--session-ref'", usageError("attachments", "upload", "a.txt").message());
        assertEquals("Missing required parameter: '<file>'", usageError("attachments", "upload", "--session-ref", "REF").message());
    }

    @Test
    void attachmentsSendReadsItsUploadIdAndItsFile() {
        CliRequest.Run sent = run("attachments", "send", "--upload-id", "U1", "report.txt");
        assertEquals(java.util.List.of("send", "report.txt"), sent.operands());
        assertEquals(Map.of("--upload-id", "U1"), sent.options());

        CliRequest.Run equalsForm = run("attachments", "send", "--upload-id=U1", "report.txt");
        assertEquals(sent, equalsForm);
        assertEquals(sent, run("attachments", "send", "report.txt", "--upload-id=U1"), "the file may come before the option");
    }

    @Test
    void attachmentsPrepareAndSendRefuseALineWithoutTheirFileOrTheirUploadId() {
        assertEquals("Missing required parameter: '<file>'", usageError("attachments", "prepare").message());
        assertEquals("Missing required parameter: '<file>'", usageError("attachments", "prepare", "--name", "a.txt").message());
        assertEquals("Missing required parameter: '<file>'", usageError("attachments", "send", "--upload-id", "U1").message());
        assertEquals("Missing required option: '--upload-id'", usageError("attachments", "send", "report.txt").message());
        assertEquals("Missing required option: '--upload-id'", usageError("attachments", "send").message(), "the option is judged before the file");
        assertEquals(SennardCommand.ATTACHMENTS, usageError("attachments", "prepare").command());
        assertEquals(SennardCommand.ATTACHMENTS, usageError("attachments", "send", "report.txt").command());
    }

    @Test
    void attachmentsPrepareAndSendRefuseASecondFile() {
        assertEquals("Unmatched argument: 'second.txt'", usageError("attachments", "prepare", "first.txt", "second.txt").message());
        assertEquals("Unmatched argument: 'second.txt'", usageError("attachments", "send", "--upload-id", "U1", "first.txt", "second.txt").message());
    }

    @Test
    void attachmentsActionsThatTakeNoFileStillRefuseAnExtraOperand() {
        assertEquals("Unmatched argument: 'stray'", usageError("attachments", "check", "stray").message());
        assertEquals("Unmatched argument: 'stray'", usageError("attachments", "rekey", "--from", "old", "stray").message());
        assertEquals("Unmatched argument: 'stray'", usageError("attachments", "keygen", "--id", "k", "stray").message());
        assertEquals("Unmatched argument: 'stray'", usageError("attachments", "purge-orphans", "stray").message());
        assertEquals("Unmatched argument: 'stray'", usageError("attachments", "purge-orphans", "--yes", "stray").message());
    }

    @Test
    void attachmentsNeedsAnActionThatIsOneOfItsOwn() {
        CliRequest.UsageError none = usageError("attachments");
        assertEquals("Missing required parameter: '<action>'", none.message());
        assertEquals(SennardCommand.ATTACHMENTS, none.command());

        CliRequest.UsageError unknown = usageError("attachments", "frobnicate");
        assertEquals("Unknown action: 'frobnicate' (one of: check, rekey, keygen, purge-orphans, prepare, upload, send)", unknown.message());
        assertEquals(SennardCommand.ATTACHMENTS, unknown.command());
        assertTrue(usageError("attachments", "Check").message().contains("'Check'"), "the action's name is exact");
        assertTrue(usageError("attachments", "frobnicate", "--from", "old").message().startsWith("Unknown action"), "the action is judged before its options");

        assertEquals("Unmatched argument: 'stray'", usageError("attachments", "check", "stray").message());
    }

    @Test
    void attachmentsRefusesAnActionWithoutItsRequiredOption() {
        assertEquals("Missing required option: '--from'", usageError("attachments", "rekey").message());
        assertEquals("Missing required option: '--id'", usageError("attachments", "keygen").message());
        assertEquals(SennardCommand.ATTACHMENTS, usageError("attachments", "rekey").command());
    }

    @Test
    void attachmentsRefusesAnOptionThatBelongsToAnotherAction() {
        assertEquals("Option '--from' is not used by 'attachments check'", usageError("attachments", "check", "--from", "old").message());
        assertEquals("Option '--id' is not used by 'attachments rekey'", usageError("attachments", "rekey", "--from", "old", "--id", "k").message());
        assertEquals("Option '--yes' is not used by 'attachments keygen'", usageError("attachments", "keygen", "--id", "k", "--yes").message());
        assertEquals("Option '--yes' is not used by 'attachments rekey'", usageError("attachments", "rekey", "--yes", "--from", "old").message());
        assertEquals("Option '--upload-id' is not used by 'attachments prepare'", usageError("attachments", "prepare", "--upload-id", "U1", "report.txt").message());
        assertEquals("Option '--name' is not used by 'attachments send'", usageError("attachments", "send", "--upload-id", "U1", "--name", "a.txt", "report.txt").message());
        assertEquals("Unknown option: '--port'", usageError("attachments", "send", "--upload-id", "U1", "--port", "55001", "report.txt").message(), "no action takes a port any more");
        assertEquals("Unknown option: '--port'", usageError("attachments", "check", "--port", "55001").message());
        assertEquals("Option '--name' is not used by 'attachments rekey'", usageError("attachments", "rekey", "--from", "old", "--name", "a.txt").message());
    }

    @Test
    void attachmentsRefusesMalformedOptions() {
        assertTrue(usageError("attachments", "rekey", "--from").message().contains("--from"));
        assertTrue(usageError("attachments", "rekey", "--from", "--yes").message().contains("--from"));
        assertTrue(usageError("attachments", "purge-orphans", "--yes=true").message().contains("--yes"));
        assertTrue(usageError("attachments", "check", "--nope").message().contains("--nope"));
        assertTrue(usageError("attachments", "rekey", "--from", "a", "--from", "b").message().contains("only once"));
    }

    @Test
    void attachmentsHelpIsTheCommandsUsageWithEveryActionAndItsOptions() {
        assertEquals(new CliRequest.Help(SennardCommand.ATTACHMENTS), CliParser.parse(new String[] {"attachments", "-h"}));
        assertEquals(new CliRequest.Help(SennardCommand.ATTACHMENTS), CliParser.parse(new String[] {"attachments", "rekey", "--help"}));
        String usage = CliUsage.of(SennardCommand.ATTACHMENTS);
        assertTrue(usage.startsWith("Usage: sennard attachments <action>"), usage);
        for (AttachmentAction action : AttachmentAction.values()) {
            assertTrue(usage.contains(action.actionName()), action.name() + " in " + usage);
        }
        assertTrue(usage.contains("rekey --from=<keyId>") && usage.contains("keygen --id=<keyId>") && usage.contains("purge-orphans [--yes]"), usage);
        assertTrue(usage.contains("prepare <file> [--name=<fileName>]"), "prepare names its file and its option: " + usage);
        assertTrue(usage.contains("send <file> --upload-id=<uploadId>"), "send names its file and its required option: " + usage);
        assertFalse(usage.contains("--port"), "the server's address comes from its address file, not from the command line: " + usage);
    }

    @Test
    void anUnknownCommandIsRefused() {
        CliRequest.UsageError error = usageError("frobnicate");
        assertTrue(error.message().contains("frobnicate"), error.message());
        assertNull(error.command());
    }

    @Test
    void anUnknownOptionIsRefused() {
        assertTrue(usageError("install", "--nope").message().contains("--nope"));
        assertTrue(usageError("install", "--nope=1").message().contains("--nope"));
        assertTrue(usageError("start", "--state-dir", "x").message().contains("--state-dir"));
    }

    @Test
    void stateDirWithoutAValueIsRefused() {
        assertTrue(usageError("install", "--state-dir").message().contains("--state-dir"));
        assertTrue(usageError("install", "--state-dir", "--other").message().contains("--state-dir"));
    }

    @Test
    void otherMalformedLinesAreRefused() {
        assertTrue(usageError("admin-reset", "--yes=true").message().contains("--yes"));
        assertTrue(usageError("install", "--state-dir", "a", "--state-dir", "b").message().contains("only once"));
        assertTrue(usageError("start", "extra").message().contains("extra"));
    }

    @Test
    void thereIsNoHookCommandAnyMore() {
        // The hosts' definitions run the hook client's own JVM; a "sennard hook" that started this heavy CLI per hook call was the slow path that is gone.
        CliRequest.UsageError error = usageError("hook", "claude", "Stop");
        assertTrue(error.message().contains("hook"), error.message());
        assertNull(error.command());
        assertTrue(SennardCommand.named("hook").isEmpty());
    }

    @Test
    void startTakesWithinInBothForms() {
        CliRequest.Run equalsForm = run("start", "--within=5");

        assertEquals(SennardCommand.START, equalsForm.command());
        assertEquals(Optional.of("5"), equalsForm.value(HookClientCall.START_WITHIN));
        assertEquals(equalsForm, run("start", "--within", "5"));
        assertEquals(Optional.empty(), run("start").value(HookClientCall.START_WITHIN), "without the option the start is the plain one");
    }

    @Test
    void noArgumentsIsAUsageError() {
        CliRequest.UsageError error = usageError();
        assertEquals("Missing required subcommand", error.message());
        assertNull(error.command());
    }

    @Test
    void helpAndVersionAreRecognizedEverywhere() {
        assertEquals(new CliRequest.Help(null), CliParser.parse(new String[] {"--help"}));
        assertEquals(new CliRequest.Help(null), CliParser.parse(new String[] {"-h"}));
        assertEquals(new CliRequest.Version(), CliParser.parse(new String[] {"--version"}));
        assertEquals(new CliRequest.Version(), CliParser.parse(new String[] {"-V"}));
        assertEquals(new CliRequest.Help(SennardCommand.INSTALL), CliParser.parse(new String[] {"install", "--help"}));
        assertEquals(new CliRequest.Help(SennardCommand.PREPARE), CliParser.parse(new String[] {"prepare", "-h"}));
        assertEquals(new CliRequest.Version(), CliParser.parse(new String[] {"status", "-V"}));
    }
}
