// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.cli;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.github.jaeseongs95.sennard.app.attachment.ArtifactErrors;
import io.github.jaeseongs95.sennard.app.attachment.AttachmentMaintenance.Finding;
import io.github.jaeseongs95.sennard.attachmentcli.AttachmentsContext;
import io.github.jaeseongs95.sennard.attachmentcli.AttachmentsRequest;
import io.github.jaeseongs95.sennard.attachmentcli.AttachmentsResult;
import io.github.jaeseongs95.sennard.attachmentcli.PreparedFile;
import io.github.jaeseongs95.sennard.attachmentcli.UploadClient;
import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.domain.error.UnavailableCode;
import io.github.jaeseongs95.sennard.domain.error.UnavailableException;
import io.github.jaeseongs95.sennard.personal.install.InstallEnvironment;
import io.github.jaeseongs95.sennard.personal.install.InstalledSettings;
import io.github.jaeseongs95.sennard.personal.install.ServerControl;

/**
 * {@code sennard attachments prepare} and {@code sennard attachments send}: the upload client for a large file. {@code prepare} measures a file and connects to nothing; the model gives
 * {@code artifact_begin} what it printed for each file of the attachment, and {@code send} then sends one file to its upload, with the MCP tools of the server on this computer, and prints the
 * upload and its attachment as one line of JSON. It does not finish the attachment: the model calls {@code artifact_finish} once, after every file was sent. The other actions are the upkeep below.
 *
 * <p>{@code sennard attachments <action>}: the upkeep of the attachment folder and its keys, run without the server. The parser has already checked the action and its options
 * ({@link AttachmentAction}); the command's own Spring context ({@link AttachmentsContext}) reads the installation's settings, opens the store the way the server does and runs the
 * request. This class prints the answer as JSON; the exit code is 1 for a check that found something, as for any command that did not end clean.
 */
final class AttachmentsCommand {
    /** What {@code check} prints: how many things it found and each of them. */
    record Checked(String command, int findingCount, List<Finding> findings) {
    }

    /** What {@code rekey} prints. */
    record Rekeyed(String command, String from, int rewritten) {
    }

    /** What {@code keygen} prints: the key's id and that it was made. The key's value is never in an answer or a log. */
    record KeyCreated(String command, String keyId, boolean created) {
    }

    /** What {@code purge-orphans} prints: the files of the quarantine folder, whether they were deleted, and, for a listing only, what the confirmed run does. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Orphans(String command, boolean purged, List<String> files, String effect) {
        static final String EFFECT = "Nothing was deleted. Run again with --yes to delete these files and write their names to the audit log.";
    }

    private AttachmentsCommand() {
    }

    static int run(CliRequest.Run request, CliOutput out) {
        AttachmentAction action = AttachmentAction.named(request.operands().get(0))
                .orElseThrow(() -> new IllegalStateException("The parser let an action through that is not one: " + request.operands()));
        return switch (action) {
            case PREPARE -> prepare(request, out);
            case SEND -> send(request, out);
            case UPLOAD -> upload(request, out);
            case CHECK, REKEY, KEYGEN, PURGE_ORPHANS -> maintain(action, request, out);
        };
    }

    /** What {@code prepare} prints is {@link PreparedFile} itself: the four fields {@code artifact_begin} takes. Nothing is connected to. */
    private static int prepare(CliRequest.Run request, CliOutput out) {
        out.json(PreparedFile.of(fileOf(request), request.valueOrNull(AttachmentAction.NAME)));
        return 0;
    }

    /** The file to send, to the upload of {@code --upload-id}. A tool's refusal ends the command with its code and sentence, and a send that stopped says what the server holds. */
    private static int send(CliRequest.Run request, CliOutput out) {
        Path file = fileOf(request);
        int port = serverPort(out);
        try (UploadClient client = new UploadClient(port)) {
            return switch (client.send(file, request.valueOrNull(AttachmentAction.UPLOAD_ID))) {
                case UploadClient.Sent sent -> {
                    out.json(sent);
                    yield 0;
                }
                case UploadClient.Stopped stopped -> {
                    out.error("attachments send: " + stopped.message());
                    yield 1;
                }
            };
        } catch (UploadClient.Refused refused) {
            // A tool's refusal is a defined result of the command: its code and sentence on stderr and exit code 1.
            out.refusal(refused);
            return 1;
        }
    }

    private static int upload(CliRequest.Run request, CliOutput out) {
        try (UploadClient client = new UploadClient(serverPort(out))) {
            out.json(client.upload(fileOf(request), request.valueOrNull(AttachmentAction.SESSION_REF),
                    request.valueOrNull(AttachmentAction.OPERATION_KEY), request.valueOrNull(AttachmentAction.NAME)));
            return 0;
        } catch (UploadClient.Refused refused) {
            out.refusal(refused);
            return 1;
        }
    }

    private static Path fileOf(CliRequest.Run request) {
        String name = request.operands().get(1);
        try {
            return Path.of(name);
        } catch (InvalidPathException invalid) {
            throw ArtifactErrors.invalidArgument("<file>", "not a path (" + invalid.getReason() + ")");
        }
    }

    /**
     * The server's port, from the address the running server wrote in the state folder: the port is the operating system's choice at each start of the server, so no setting holds it.
     *
     * @throws UnavailableException SERVER_UNREACHABLE when no server of this installation runs
     */
    private static int serverPort(CliOutput out) {
        try (InstalledSettings settings = InstalledSettings.at(SennardCli.installer(out).activeVersion().folder())) {
            return new ServerControl(settings.serverAddressFile(), InstallEnvironment.current().environment()).running()
                    .orElseThrow(() -> new UnavailableException(UnavailableCode.SERVER_UNREACHABLE, SennardException.args("where", "no live server is announced in " + settings.serverAddressFile()))).port();
        }
    }

    private static int maintain(AttachmentAction action, CliRequest.Run request, CliOutput out) {
        AttachmentsRequest asked = switch (action) {
            case CHECK -> new AttachmentsRequest.Check();
            case REKEY -> new AttachmentsRequest.Rekey(request.valueOrNull(AttachmentAction.FROM));
            case KEYGEN -> new AttachmentsRequest.Keygen(request.valueOrNull(AttachmentAction.ID));
            case PURGE_ORPHANS -> new AttachmentsRequest.PurgeOrphans(request.has(AttachmentAction.YES));
            case PREPARE, SEND, UPLOAD -> throw new IllegalStateException("Not an upkeep action: " + action);
        };
        // The command's own Spring context (no web server) reads the installation's settings, opens the store the way the server does and runs the one request.
        AttachmentsResult result = AttachmentsContext.run(asked, SennardCli.installer(out).activeVersion().folder());
        return print(action, result, out);
    }

    /** The answer of each result as JSON, and the exit code. The switch has no default: a result added to {@link AttachmentsResult} does not compile until it is printed here. */
    static int print(AttachmentAction action, AttachmentsResult result, CliOutput out) {
        String command = "attachments " + action.actionName();
        return switch (result) {
            case AttachmentsResult.Checked checked -> {
                out.json(new Checked(command, checked.findings().size(), checked.findings()));
                yield checked.findings().isEmpty() ? 0 : 1;
            }
            case AttachmentsResult.Rekeyed rekeyed -> {
                out.json(new Rekeyed(command, rekeyed.fromKeyId(), rekeyed.rewritten()));
                yield 0;
            }
            case AttachmentsResult.KeyCreated created -> {
                out.json(new KeyCreated(command, created.keyId(), true));
                yield 0;
            }
            case AttachmentsResult.Orphans orphans -> {
                out.json(new Orphans(command, orphans.purged(), orphans.files(), orphans.purged() ? null : Orphans.EFFECT));
                yield 0;
            }
        };
    }
}
