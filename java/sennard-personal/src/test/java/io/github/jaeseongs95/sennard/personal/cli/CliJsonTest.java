// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jaeseongs95.sennard.admincli.AdminReset;
import io.github.jaeseongs95.sennard.app.attachment.AttachmentMaintenance;
import io.github.jaeseongs95.sennard.attachmentcli.AttachmentsResult;
import io.github.jaeseongs95.sennard.attachmentcli.UploadClient;
import io.github.jaeseongs95.sennard.domain.error.InputCode;
import io.github.jaeseongs95.sennard.domain.error.InvalidInputException;

/**
 * What the commands print as JSON, from records through the one mapper of {@link CliOutput}.
 *
 * <pre>
 * j1  dry run    admin-reset without --yes: command, dryRun, wouldArchive (the four counts) and effect, in that order, and nothing else
 * j2  indented   the indented form is Jackson's default pretty printer, one key per line
 * j3  check      attachments check prints command, findingCount and the findings (kind, id, detail); exit code 1 with a finding, 0 with none (an empty list, not a missing key)
 * j4  rekey      attachments rekey prints command, the key it started from and how many it wrote again; exit code 0
 * j5  keygen     attachments keygen prints command, the key's id and created: true, and no other value; exit code 0
 * j6  purge      attachments purge-orphans without --yes prints purged false, the files and what the confirmed run does; with it purged true, the files and no effect; exit code 0 for both
 * j7  prepare    attachments prepare &lt;file&gt; (the real command, which needs no installation and connects to nothing) prints exactly one line, a JSON object with the four keys fileName, size,
 *                sha256 and mediaType in that order and no other; exit code 0; --name gives the fileName; an empty file has size 0 and the sha256 of no bytes
 * j8  prepare    the same through the real entry (the parser, then the command): stdout is that one line and stderr is empty; a file that is not there ends with VALIDATION_FAILED on stderr, exit
 *                code 1, and nothing on stdout
 * j9  send       attachments send prints one line with uploadId, attachmentId, seq and received in that order (and nothing of a finish: the model calls artifact_finish once); a finding of a
 *                file names it as &lt;attachment id&gt;/&lt;file number&gt;
 * </pre>
 */
class CliJsonTest {
    private static final String ABC_SHA256 = "sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
    private static final String EMPTY_SHA256 = "sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    /** An extension no platform knows: the media type of such a file is the unknown type on every platform. */
    private static final String UNKNOWN_EXTENSION = ".sennard-test-unknown";

    private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
    private final CliOutput output = new CliOutput(new PrintStream(stdout, true, StandardCharsets.UTF_8), new PrintStream(stderr, true, StandardCharsets.UTF_8));

    @Test
    void aDryRunNamesWhatWouldBeArchivedAndTheEffect() {
        output.json(AdminResetCommand.Output.dryRun(new AdminReset.Result(new AdminReset.Summary(1, 2, 3, 4L), null)));
        assertEquals("{\"command\":\"admin-reset\",\"dryRun\":true,\"wouldArchive\":{\"keys\":1,\"roles\":2,\"bindings\":3,\"lastSeq\":4},\"effect\":\"" + AdminReset.EFFECT + "\"}"
                + System.lineSeparator(), stdout.toString(StandardCharsets.UTF_8));
    }

    @Test
    void theIndentedFormPutsEachKeyOnItsOwnLine() {
        output.prettyJson(AdminResetCommand.Output.dryRun(new AdminReset.Result(new AdminReset.Summary(1, 2, 3, 4L), null)));
        assertEquals(true, stdout.toString(StandardCharsets.UTF_8).lines().anyMatch(line -> line.strip().startsWith("\"dryRun\"")), stdout.toString(StandardCharsets.UTF_8));
    }

    private String printed() {
        return stdout.toString(StandardCharsets.UTF_8);
    }

    @Test
    void aCheckPrintsItsFindingsAndExitsOneOnlyWhenThereIsOne() {
        int found = AttachmentsCommand.print(AttachmentAction.CHECK, new AttachmentsResult.Checked(List.of(new AttachmentMaintenance.Finding("file-without-row", "ab00", "no row names this file"))),
                output);
        assertEquals(1, found);
        assertEquals("{\"command\":\"attachments check\",\"findingCount\":1,\"findings\":[{\"kind\":\"file-without-row\",\"id\":\"ab00\",\"detail\":\"no row names this file\"}]}"
                + System.lineSeparator(), printed());

        stdout.reset();
        assertEquals(0, AttachmentsCommand.print(AttachmentAction.CHECK, new AttachmentsResult.Checked(List.of()), output));
        assertEquals("{\"command\":\"attachments check\",\"findingCount\":0,\"findings\":[]}" + System.lineSeparator(), printed());
    }

    @Test
    void aRekeyPrintsWhereItStartedAndHowManyItWrote() {
        assertEquals(0, AttachmentsCommand.print(AttachmentAction.REKEY, new AttachmentsResult.Rekeyed("old", 3), output));
        assertEquals("{\"command\":\"attachments rekey\",\"from\":\"old\",\"rewritten\":3}" + System.lineSeparator(), printed());
    }

    @Test
    void aKeygenPrintsTheIdOfTheKeyAndNothingOfItsValue() {
        assertEquals(0, AttachmentsCommand.print(AttachmentAction.KEYGEN, new AttachmentsResult.KeyCreated("key-2"), output));
        assertEquals("{\"command\":\"attachments keygen\",\"keyId\":\"key-2\",\"created\":true}" + System.lineSeparator(), printed());
    }

    @Test
    void aPurgeThatIsNotConfirmedListsTheFilesAndSaysWhatTheConfirmedRunDoes() {
        List<String> files = List.of("aa000000000000000000000000000000", "bb000000000000000000000000000000");
        assertEquals(0, AttachmentsCommand.print(AttachmentAction.PURGE_ORPHANS, new AttachmentsResult.Orphans(files, false), output));
        assertEquals("{\"command\":\"attachments purge-orphans\",\"purged\":false,\"files\":[\"aa000000000000000000000000000000\",\"bb000000000000000000000000000000\"],\"effect\":\""
                + AttachmentsCommand.Orphans.EFFECT + "\"}" + System.lineSeparator(), printed());

        stdout.reset();
        assertEquals(0, AttachmentsCommand.print(AttachmentAction.PURGE_ORPHANS, new AttachmentsResult.Orphans(files, true), output));
        assertEquals("{\"command\":\"attachments purge-orphans\",\"purged\":true,\"files\":[\"aa000000000000000000000000000000\",\"bb000000000000000000000000000000\"]}"
                + System.lineSeparator(), printed());
    }

    @Test
    void aFindingOfAFileNamesTheAttachmentAndTheNumberOfTheFile() {
        String id = "77777777-7777-4777-8777-777777777777";
        assertEquals(1, AttachmentsCommand.print(AttachmentAction.CHECK, new AttachmentsResult.Checked(List.of(new AttachmentMaintenance.Finding("file-missing", id, 1, "the current copy is not in the folder"))), output));
        assertEquals("{\"command\":\"attachments check\",\"findingCount\":1,\"findings\":[{\"kind\":\"file-missing\",\"id\":\"" + id + "\",\"seq\":1,\"detail\":\"the current copy is not in the folder\"}]}"
                + System.lineSeparator(), printed());
    }

    /** j9: what {@code attachments send} prints for a file it sent: one line with the upload, the attachment it is a file of, the file's number and the bytes held; no key of a finish. */
    @Test
    void aSendPrintsTheUploadTheAttachmentTheNumberOfTheFileAndTheBytesTheServerHolds() {
        output.json(new UploadClient.Sent("11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222", 1, 300000L));
        assertEquals("{\"uploadId\":\"11111111-1111-4111-8111-111111111111\",\"attachmentId\":\"22222222-2222-4222-8222-222222222222\",\"seq\":1,\"received\":300000}" + System.lineSeparator(),
                printed());
        assertEquals(List.of("uploadId", "attachmentId", "seq", "received"), java.util.Arrays.stream(UploadClient.Sent.class.getRecordComponents()).map(component -> component.getName()).toList());
    }

    private static Path write(Path folder, String name, String text) throws IOException {
        return Files.write(folder.resolve(name), text.getBytes(StandardCharsets.UTF_8));
    }

    private static CliRequest.Run prepare(Map<String, String> options, Path file) {
        return new CliRequest.Run(SennardCommand.ATTACHMENTS, options, List.of("prepare", file.toString()));
    }

    @Test
    void aPreparePrintsTheFourKeysOfTheFileOnOneLineAndExitsZero(@TempDir Path temp) throws IOException {
        Path file = write(temp, "payload" + UNKNOWN_EXTENSION, "abc");

        assertEquals(0, AttachmentsCommand.run(prepare(Map.of(), file), output));

        assertEquals("{\"fileName\":\"payload" + UNKNOWN_EXTENSION + "\",\"size\":3,\"sha256\":\"" + ABC_SHA256 + "\",\"mediaType\":\"application/octet-stream\"}" + System.lineSeparator(), printed());
        assertEquals(1, printed().lines().count(), "one line, so that a caller reads it whole");
        assertEquals("", stderr.toString(StandardCharsets.UTF_8));
    }

    @Test
    void aPrepareWithANameDeclaresThatNameAndAnEmptyFileHasNoBytes(@TempDir Path temp) throws IOException {
        Path file = write(temp, "empty" + UNKNOWN_EXTENSION, "");

        assertEquals(0, AttachmentsCommand.run(prepare(Map.of("--name", "docs/empty.txt"), file), output));

        assertEquals("{\"fileName\":\"docs/empty.txt\",\"size\":0,\"sha256\":\"" + EMPTY_SHA256 + "\",\"mediaType\":\"application/octet-stream\"}" + System.lineSeparator(), printed());
    }

    @Test
    void aPrepareOfAFileThatIsNotThereIsAnInvalidInputAndPrintsNothing(@TempDir Path temp) {
        InvalidInputException refused = assertThrows(InvalidInputException.class, () -> AttachmentsCommand.run(prepare(Map.of(), temp.resolve("missing.txt")), output));

        assertEquals(InputCode.VALIDATION_FAILED, refused.code());
        assertEquals("", printed());
    }

    @Test
    void thePrepareThroughTheRealEntryPrintsTheOneLineAndAFileThatIsNotThereEndsWithTheCodeOnStderr(@TempDir Path temp) throws IOException {
        Path file = write(temp, "payload" + UNKNOWN_EXTENSION, "abc");

        int exit = SennardCli.execute(new String[] {"attachments", "prepare", "--name", "docs/a.txt", file.toString()}, output, request -> SennardCli.run(request, output), Optional::empty);

        assertEquals(0, exit);
        assertEquals("{\"fileName\":\"docs/a.txt\",\"size\":3,\"sha256\":\"" + ABC_SHA256 + "\",\"mediaType\":\"application/octet-stream\"}" + System.lineSeparator(), printed());
        assertEquals("", stderr.toString(StandardCharsets.UTF_8));

        stdout.reset();
        int failed = SennardCli.execute(new String[] {"attachments", "prepare", temp.resolve("missing.txt").toString()}, output, request -> SennardCli.run(request, output), Optional::empty);

        assertEquals(1, failed);
        assertEquals("", printed());
        assertTrue(stderr.toString(StandardCharsets.UTF_8).startsWith("VALIDATION_FAILED: "), stderr.toString(StandardCharsets.UTF_8));
    }
}
