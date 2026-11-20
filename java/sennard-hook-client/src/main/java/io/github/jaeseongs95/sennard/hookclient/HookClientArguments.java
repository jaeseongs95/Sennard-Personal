// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.hookclient;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.jaeseongs95.sennard.hookclient.HookClientCall.FailureAnswer;
import io.github.jaeseongs95.sennard.hookclient.HookClientCall.RefusalAnswer;
import io.github.jaeseongs95.sennard.hookclient.HookOptionException.Problem;

/**
 * The words of a hook command line read back into the call they describe: the reader of {@link HookClientCall#arguments()} and of the installation's own options
 * ({@link HookClientCall#SERVER_FILE}, {@link HookClientCall#JAR}), for the client program. Words that start with {@code --} are options ({@code --name=value}, or {@code --name} for a flag), in any place; the rest are
 * the operands, {@code hook <label> <event>}. Nothing here needs a library: it is the whole of what a hook definition writes.
 */
public final class HookClientArguments {
    private static final String PREFIX = "--";
    private static final Set<String> FLAGS = Set.of(HookClientCall.BIND_HOST, HookClientCall.WAKE);
    private static final Set<String> VALUED = Set.of(HookClientCall.MODULE, HookClientCall.TIMEOUT, HookClientCall.ON_FAILURE, HookClientCall.ON_REFUSAL, HookClientCall.START_SERVER,
            HookClientCall.ONLY_IF_ENVIRONMENT, HookClientCall.WARM, HookClientCall.SERVER_FILE, HookClientCall.JAR);

    /**
     * What a command line asks for.
     *
     * @param call the hook to run
     * @param warm the value of {@code --warm} ({@code <label>=<file>}), or null
     * @param serverFile the value of {@code --server-file}: where the server announces its address ({@link ServerAddress}); every call needs it
     * @param jar the value of {@code --jar}: the installed jar that starts the server, or null when the call starts none
     */
    public record Parsed(HookClientCall call, String warm, Path serverFile, Path jar) {
    }

    private HookClientArguments() {
    }

    /** @throws HookOptionException when the words are not a hook command line */
    public static Parsed parse(List<String> words) {
        Map<String, String> options = new HashMap<>();
        List<String> operands = new ArrayList<>();
        for (String word : words) {
            if (!word.startsWith(PREFIX)) {
                operands.add(word);
                continue;
            }
            int equals = word.indexOf('=');
            String name = equals < 0 ? word : word.substring(0, equals);
            String value = equals < 0 ? "" : word.substring(equals + 1);
            if (FLAGS.contains(name)) {
                if (equals >= 0) {
                    throw new HookOptionException(Problem.OTHER, name, value);
                }
            } else if (!VALUED.contains(name) || equals < 0) {
                throw new HookOptionException(Problem.OTHER, name, value);
            }
            if (options.put(name, value) != null) {
                throw new HookOptionException(Problem.OTHER, name, value);
            }
        }
        if (operands.size() != 3 || !HookClientCall.COMMAND.equals(operands.get(0))) {
            throw new HookOptionException(Problem.OTHER, "operands", String.join(" ", operands));
        }
        HookClientCall call = new HookClientCall(operands.get(1), operands.get(2), options.get(HookClientCall.MODULE), failure(options),
                number(options, HookClientCall.TIMEOUT, HookClientCall.DEFAULT_TIMEOUT_SECONDS), options.containsKey(HookClientCall.BIND_HOST), options.containsKey(HookClientCall.WAKE),
                options.get(HookClientCall.ONLY_IF_ENVIRONMENT), number(options, HookClientCall.START_SERVER, 0), refusal(options));
        Path jar = options.containsKey(HookClientCall.JAR) ? path(options, HookClientCall.JAR) : null;
        if (call.startServerSeconds() > 0 && jar == null) {
            throw new HookOptionException(Problem.OTHER, HookClientCall.JAR, ""); // a call that starts the server needs the jar that does
        }
        return new Parsed(call, options.get(HookClientCall.WARM), required(options, HookClientCall.SERVER_FILE), jar);
    }

    /**
     * The path of a required option, which the call cannot go without: with no file that names the server's address, the client has no way to find the server (there is no port to
     * fall back to).
     */
    static Path required(Map<String, String> options, String option) {
        if (!options.containsKey(option)) {
            throw new HookOptionException(Problem.OTHER, option, "");
        }
        return path(options, option);
    }

    static Path path(Map<String, String> options, String option) {
        String text = options.get(option);
        try {
            return Path.of(text);
        } catch (InvalidPathException notAPath) {
            throw new HookOptionException(Problem.OTHER, option, text);
        }
    }

    private static FailureAnswer failure(Map<String, String> options) {
        String text = options.get(HookClientCall.ON_FAILURE);
        return text == null ? FailureAnswer.FAIL
                : FailureAnswer.ofOption(text).orElseThrow(() -> new HookOptionException(Problem.UNKNOWN_VALUE, HookClientCall.ON_FAILURE, text));
    }

    private static RefusalAnswer refusal(Map<String, String> options) {
        String text = options.get(HookClientCall.ON_REFUSAL);
        return text == null ? RefusalAnswer.AS_FAILURE
                : RefusalAnswer.ofOption(text).orElseThrow(() -> new HookOptionException(Problem.UNKNOWN_VALUE, HookClientCall.ON_REFUSAL, text));
    }

    static int number(Map<String, String> options, String option, int fallback) {
        String text = options.get(option);
        if (text == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException notANumber) {
            throw new HookOptionException(Problem.NOT_A_NUMBER, option, text);
        }
    }
}
