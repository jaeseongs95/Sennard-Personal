// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.cli;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import io.github.jaeseongs95.sennard.personal.cli.SennardCommand.Choice;

/**
 * The closed set of actions of {@code sennard attachments} (its one operand): the name on the command line, what it does, and the options it must have or may have. The parser reads
 * the actions from {@link SennardCommand#choices()}, which is made from this list, and the command runs the one it names with a switch that has no default.
 */
enum AttachmentAction {
    CHECK("check", "Compares the rows with the folder and prints what differs as JSON; exit code 1 if anything differs. Changes nothing.", List.of(), List.of()),
    REKEY("rekey", "Writes the files of the given key again under the active key and prints how many.", List.of(AttachmentAction.FROM), List.of()),
    KEYGEN("keygen", "Makes a new key file; the key's value is never printed.", List.of(AttachmentAction.ID), List.of()),
    PURGE_ORPHANS("purge-orphans", "Compares the folder with the rows (moves the files with no row or replaced, after the grace, to orphans/) and lists the files in orphans/; with --yes only deletes them and logs their names.", List.of(), List.of(AttachmentAction.YES)),
    PREPARE("prepare", "Measures a file and prints {\"fileName\":..,\"size\":..,\"sha256\":..,\"mediaType\":..} on one line, for artifact_begin. Connects to nothing.", List.of(), List.of(AttachmentAction.NAME), List.of("file")),
    UPLOAD("upload", "Reads a file and automatically begins, sends and finishes its attachment. Prints only attachment metadata; file bytes never pass through the model. Repeat the same command to recover an interrupted result.", List.of(AttachmentAction.SESSION_REF), List.of(AttachmentAction.NAME, AttachmentAction.OPERATION_KEY), List.of("file")),
    SEND("send", "Sends the file to the upload (one of the uploads artifact_begin returned) as standard multipart/form-data and prints {\"uploadId\":..,\"attachmentId\":..,\"seq\":..,\"received\":..} on one line. It does not finish the attachment: call artifact_finish once, after every file was sent. Sends nothing again; a stopped send is continued by running it again.", List.of(AttachmentAction.UPLOAD_ID),
            List.of(), List.of("file"));

    static final String FROM = "--from";
    static final String ID = "--id";
    static final String YES = "--yes";
    static final String NAME = "--name";
    static final String UPLOAD_ID = "--upload-id";
    static final String SESSION_REF = "--session-ref";
    static final String OPERATION_KEY = "--operation-key";

    private final String actionName;
    private final Choice choice;

    AttachmentAction(String actionName, String description, List<String> required, List<String> optional) {
        this(actionName, description, required, optional, List.of());
    }

    AttachmentAction(String actionName, String description, List<String> required, List<String> optional, List<String> operands) {
        this.actionName = actionName;
        this.choice = new Choice(actionName, description, required, optional, operands);
    }

    String actionName() {
        return actionName;
    }

    /** The actions as the parser's choices of the command's operand, in the order they are declared. */
    static List<Choice> choices() {
        return Arrays.stream(values()).map(action -> action.choice).toList();
    }

    static Optional<AttachmentAction> named(String name) {
        return Arrays.stream(values()).filter(action -> action.actionName.equals(name)).findFirst();
    }
}
