// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.cli;

import java.util.List;
import java.util.Optional;

import io.github.jaeseongs95.sennard.hookclient.HookClientCall;

/**
 * The closed set of {@code sennard} commands: name, description and the options each one takes. A command added here is not run until
 * {@link SennardCli#run} has a branch for it, because that switch has no default.
 */
enum SennardCommand {
    INSTALL("install", "Extracts this release jar into this user's installation folder and starts the server.",
            new CliOption("--state-dir", "stateDirectory", false, "State folder, when not the default. Must be writable in the encoding the system passes file names in; otherwise use SENNARD_STATE_DIR.")),
    UNINSTALL("uninstall", "Stops the server and removes the program. The state folder stays."),
    /** The hook client and the MCP relay run it, with {@code --within}, to start the server when a session start finds none (see {@link HookClientCall#START_WITHIN}). */
    START("start", "Starts the server.",
            new CliOption(HookClientCall.START_WITHIN, "seconds", false, "Waits up to this many seconds for the server to be ready, as a host's session start does: one start at a time, nothing printed.")),
    STOP("stop", "Stops the server."),
    STATUS("status", "Prints the installation, server and store state as JSON."),
    UPGRADE("upgrade", "Replaces the installed version with this release jar's."),
    PREPARE("prepare", "Asks a server whose store preparation failed to try again.",
            new CliOption("--retry", null, true, "Try again after FAILED.")),
    HOOKS("hooks", "Writes this installation's hook and MCP definitions into the hosts again, after a host plugin was installed or updated."),
    ADMIN_RESET("admin-reset", "After every passkey is lost: archives the signed admin log and returns every signed item to its default.",
            new CliOption("--yes", null, false, "Performs the reset; without it nothing is written.")),
    /** Runs without the server, in a context of its own (the options are each action's: {@link AttachmentAction}). */
    ATTACHMENTS("attachments", "Checks and maintains the attachment folder and its keys, without the server (check, rekey, keygen, purge-orphans), and sends a large file to an upload the model began (prepare, send).", List.of("action"), AttachmentAction.choices(),
            new CliOption(AttachmentAction.FROM, "keyId", false, "rekey: the key whose files are written again."),
            new CliOption(AttachmentAction.ID, "keyId", false, "keygen: the id of the new key."),
            new CliOption(AttachmentAction.YES, null, false, "purge-orphans: deletes the files of orphans/; without it the folder is compared with the rows and the files are only listed."),
            new CliOption(AttachmentAction.NAME, "fileName", false, "prepare: the file name to declare, when not the file's own name (a file of a review bundle is named by its path under the bundle's folder)."),
            new CliOption(AttachmentAction.UPLOAD_ID, "uploadId", false, "send: the upload that artifact_begin returned."),
            new CliOption(AttachmentAction.SESSION_REF, "sessionRef", false, "upload: the calling session reference issued by Sennard; used only for attribution, not as host-observation evidence."),
            new CliOption(AttachmentAction.OPERATION_KEY, "operationKey", false, "upload: an optional stable request key; normally derived automatically from the path, file hash and name."));

    private final String commandName;
    private final String description;
    private final List<String> operands;
    private final List<Choice> choices;
    private final List<CliOption> options;

    SennardCommand(String commandName, String description, CliOption... options) {
        this(commandName, description, List.of(), options);
    }

    SennardCommand(String commandName, String description, List<String> operands, CliOption... options) {
        this(commandName, description, operands, List.of(), options);
    }

    SennardCommand(String commandName, String description, List<String> operands, List<Choice> choices, CliOption... options) {
        this.commandName = commandName;
        this.description = description;
        this.operands = List.copyOf(operands);
        this.choices = List.copyOf(choices);
        this.options = List.of(options);
    }

    /** The names of the positional arguments the command takes, in order; all are required. */
    List<String> operands() {
        return operands;
    }

    /** The values the first operand may have, each with the options it takes; empty for a command whose operands are free words. */
    List<Choice> choices() {
        return choices;
    }

    Optional<Choice> choice(String value) {
        return choices.stream().filter(choice -> choice.value().equals(value)).findFirst();
    }

    String commandName() {
        return commandName;
    }

    String description() {
        return description;
    }

    List<CliOption> options() {
        return options;
    }

    Optional<CliOption> option(String name) {
        return options.stream().filter(option -> option.name().equals(name)).findFirst();
    }

    static Optional<SennardCommand> named(String name) {
        for (SennardCommand command : values()) {
            if (command.commandName.equals(name)) {
                return Optional.of(command);
            }
        }
        return Optional.empty();
    }

    /**
     * One option of a command. {@code valueLabel} is the placeholder of an option that takes a value and {@code null} for a flag.
     */
    record CliOption(String name, String valueLabel, boolean required, String description) {
        boolean takesValue() {
            return valueLabel != null;
        }
    }

    /**
     * One value of a command's first operand (an action): what it does, the options it must be given ({@code required}) and the ones it may be given ({@code optional}); an option
     * of the command that is in neither is refused for this action. {@code operands} are the positional arguments the action takes after its name, all required.
     */
    record Choice(String value, String description, List<String> required, List<String> optional, List<String> operands) {
        Choice {
            required = List.copyOf(required);
            optional = List.copyOf(optional);
            operands = List.copyOf(operands);
        }

        Choice(String value, String description, List<String> required, List<String> optional) {
            this(value, description, required, optional, List.of());
        }

        boolean takes(String option) {
            return required.contains(option) || optional.contains(option);
        }
    }
}
