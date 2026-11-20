// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.cli;

import java.util.ArrayList;
import java.util.List;

import io.github.jaeseongs95.sennard.personal.cli.SennardCommand.CliOption;

/** The usage text of {@code sennard} and of each command, built from {@link SennardCommand}. */
final class CliUsage {
    private static final String HELP = "-h, --help";
    private static final String VERSION = "-V, --version";

    private CliUsage() {
    }

    /** The text without a trailing line break; {@code command} is null for the usage of {@code sennard} itself. */
    static String of(SennardCommand command) {
        return command == null ? forSennard() : forCommand(command);
    }

    private static String forSennard() {
        List<String> lines = new ArrayList<>();
        lines.add("Usage: sennard [-hV] [COMMAND]");
        lines.add("  " + pad(HELP, 15) + "Show this help message and exit.");
        lines.add("  " + pad(VERSION, 15) + "Print version information and exit.");
        lines.add("Commands:");
        for (SennardCommand command : SennardCommand.values()) {
            lines.add("  " + pad(command.commandName(), 13) + command.description());
        }
        return String.join(System.lineSeparator(), lines);
    }

    private static String forCommand(SennardCommand command) {
        StringBuilder synopsis = new StringBuilder("Usage: sennard ").append(command.commandName());
        command.operands().forEach(operand -> synopsis.append(" <").append(operand).append('>'));
        for (CliOption option : command.options()) {
            String shown = option.name() + (option.takesValue() ? "=<" + option.valueLabel() + ">" : "");
            synopsis.append(option.required() ? " " + shown : " [" + shown + "]");
        }
        synopsis.append(" [-hV]");
        List<String> lines = new ArrayList<>();
        lines.add(synopsis.toString());
        lines.add(command.description());
        if (!command.choices().isEmpty()) {
            lines.add("Actions:");
            for (SennardCommand.Choice choice : command.choices()) {
                lines.add("  " + actionSynopsis(command, choice) + "  " + choice.description());
            }
        }
        for (CliOption option : command.options()) {
            String shown = option.name() + (option.takesValue() ? "=<" + option.valueLabel() + ">" : "");
            lines.add("  " + shown + "  " + option.description());
        }
        lines.add("  " + HELP + "  Show this help message and exit.");
        lines.add("  " + VERSION + "  Print version information and exit.");
        return String.join(System.lineSeparator(), lines);
    }

    /** An action as it is typed: its name, the options it must be given as they are written, then the ones it may be given in brackets. */
    private static String actionSynopsis(SennardCommand command, SennardCommand.Choice choice) {
        StringBuilder synopsis = new StringBuilder(choice.value());
        choice.operands().forEach(operand -> synopsis.append(" <").append(operand).append('>'));
        choice.required().forEach(name -> synopsis.append(' ').append(shown(command, name)));
        choice.optional().forEach(name -> synopsis.append(" [").append(shown(command, name)).append(']'));
        return synopsis.toString();
    }

    private static String shown(SennardCommand command, String name) {
        return command.option(name).map(option -> option.name() + (option.takesValue() ? "=<" + option.valueLabel() + ">" : "")).orElse(name);
    }

    private static String pad(String text, int width) {
        return text.length() >= width ? text + " " : text + " ".repeat(width - text.length());
    }
}
