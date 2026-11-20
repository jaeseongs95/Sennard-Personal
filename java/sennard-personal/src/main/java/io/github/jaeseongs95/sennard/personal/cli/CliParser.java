// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.cli;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.DefaultApplicationArguments;

import io.github.jaeseongs95.sennard.personal.cli.SennardCommand.CliOption;

/**
 * Command line to {@link CliRequest}: the command is the first word, its options are what {@link SennardCommand#options()} lists, read by
 * Spring Boot's {@link ApplicationArguments}. That class knows only {@code --name=value}; {@code --name value} is joined here, for the
 * options that take a value, before it is handed over.
 */
final class CliParser {
    private static final String SEPARATOR = "--";

    private CliParser() {
    }

    static CliRequest parse(String[] args) {
        if (args.length == 0) {
            return new CliRequest.UsageError("Missing required subcommand", null);
        }
        String first = args[0];
        if (isHelp(first)) {
            return new CliRequest.Help(null);
        }
        if (isVersion(first)) {
            return new CliRequest.Version();
        }
        if (first.startsWith("-")) {
            return new CliRequest.UsageError("Unknown option: '" + first + "'", null);
        }
        return SennardCommand.named(first).<CliRequest>map(command -> parseOptions(command, List.of(args).subList(1, args.length)))
                .orElseGet(() -> new CliRequest.UsageError("Unknown command: '" + first + "'", null));
    }

    private static CliRequest parseOptions(SennardCommand command, List<String> words) {
        SennardCommand where = command;
        List<String> joined = new ArrayList<>();
        boolean help = false;
        boolean version = false;
        for (int i = 0; i < words.size(); i++) {
            String word = words.get(i);
            if (SEPARATOR.equals(word)) {
                joined.addAll(words.subList(i, words.size()));
                break;
            }
            if (isHelp(word)) {
                help = true;
                continue;
            }
            if (isVersion(word)) {
                version = true;
                continue;
            }
            // A flag has no value label, so the label is there exactly for an option that takes a value.
            Optional<String> valueLabel = command.option(word).map(CliOption::valueLabel);
            if (valueLabel.isPresent()) {
                if (i + 1 >= words.size() || words.get(i + 1).startsWith(SEPARATOR)) {
                    return new CliRequest.UsageError("Missing required parameter for option '" + word + "' (<" + valueLabel.orElseThrow() + ">)", where);
                }
                joined.add(word + "=" + words.get(++i));
            } else {
                joined.add(word);
            }
        }

        ApplicationArguments arguments = new DefaultApplicationArguments(joined.toArray(String[]::new));
        Map<String, String> given = new LinkedHashMap<>();
        for (String name : arguments.getOptionNames()) {
            String dashed = SEPARATOR + name;
            Optional<CliOption> option = command.option(dashed);
            if (option.isEmpty()) {
                return new CliRequest.UsageError("Unknown option: '" + dashed + "'", where);
            }
            List<String> values = arguments.getOptionValues(name);
            if (values.size() > 1) {
                return new CliRequest.UsageError("option '" + dashed + "' should be specified only once", where);
            }
            if (!option.map(CliOption::takesValue).orElse(false) && !values.isEmpty()) {
                return new CliRequest.UsageError("option '" + dashed + "' does not take a value", where);
            }
            given.put(dashed, values.isEmpty() ? "" : values.get(0));
        }
        List<String> operands = arguments.getNonOptionArgs();
        // An action may take operands of its own after its name (the file of "attachments send").
        int allowed = command.operands().size() + (operands.isEmpty() ? 0 : command.choice(operands.get(0)).map(choice -> choice.operands().size()).orElse(0));
        if (operands.size() > allowed) {
            return new CliRequest.UsageError("Unmatched argument: '" + operands.get(allowed) + "'", where);
        }
        if (help) {
            return new CliRequest.Help(where);
        }
        if (version) {
            return new CliRequest.Version();
        }
        for (CliOption option : command.options()) {
            if (option.required() && !given.containsKey(option.name())) {
                return new CliRequest.UsageError("Missing required option: '" + option.name() + "'", where);
            }
        }
        if (operands.size() < command.operands().size()) {
            return new CliRequest.UsageError("Missing required parameter: '<" + command.operands().get(operands.size()) + ">'", where);
        }
        if (!command.choices().isEmpty()) {
            Optional<CliRequest> refused = refusedForTheChoice(command, operands, given);
            if (refused.isPresent()) {
                return refused.orElseThrow();
            }
        }
        return new CliRequest.Run(command, Map.copyOf(given), List.copyOf(operands));
    }

    /**
     * What a command with actions refuses in the line: a first operand that is none of its actions, an option the action does not take, or an option the action must be given.
     * The command's own options are the union of its actions', so the parser above knows each name; this is where an action's own set is held to.
     */
    private static Optional<CliRequest> refusedForTheChoice(SennardCommand command, List<String> operands, Map<String, String> given) {
        String value = operands.get(0);
        Optional<SennardCommand.Choice> found = command.choice(value);
        if (found.isEmpty()) {
            String known = String.join(", ", command.choices().stream().map(SennardCommand.Choice::value).toList());
            return Optional.of(new CliRequest.UsageError("Unknown " + command.operands().get(0) + ": '" + value + "' (one of: " + known + ")", command));
        }
        SennardCommand.Choice choice = found.orElseThrow();
        for (String name : given.keySet()) {
            if (!choice.takes(name)) {
                return Optional.of(new CliRequest.UsageError("Option '" + name + "' is not used by '" + command.commandName() + " " + value + "'", command));
            }
        }
        for (String name : choice.required()) {
            if (!given.containsKey(name)) {
                return Optional.of(new CliRequest.UsageError("Missing required option: '" + name + "'", command));
            }
        }
        if (operands.size() < 1 + choice.operands().size()) {
            return Optional.of(new CliRequest.UsageError("Missing required parameter: '<" + choice.operands().get(operands.size() - 1) + ">'", command));
        }
        return Optional.empty();
    }

    private static boolean isHelp(String word) {
        return "-h".equals(word) || "--help".equals(word);
    }

    private static boolean isVersion(String word) {
        return "-V".equals(word) || "--version".equals(word);
    }
}
