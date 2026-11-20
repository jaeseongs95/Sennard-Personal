// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Where a generated definition will run from: the two programs a host starts for Sennard. There is no server address in a definition because the server does not have a
 * port of its own choosing (it binds the one the operating system gives it at each start and announces it in a file of its state folder), so what a definition holds is the
 * program that finds the server: {@code hookProgram} is the hook client, which a host starts for a command hook, and {@code mcpProgram} is the MCP relay, which a host
 * starts as its stdio MCP server. An installation writes its own (see {@code HookClientCommand} in sennard-personal); a plugin package ships no program, only empty definitions
 * ({@link HostHookAdapter#shippedFiles()}).
 *
 * <p>Each program is the words of one command in order: the Java of the user, the JVM options, {@code -cp}, the path of the hook client's jar, the program's main class, and the
 * installation's own options that every call of it needs ({@code --server-file=<where the server announces its address>} and, for a call that may start the server,
 * {@code --jar=<the installed Sennard jar>}). An adapter appends the words that are the call's own ({@code HookClientCall#arguments()} for a hook, the host's label and the wait for
 * the server's start for the relay).
 *
 * @param hookProgram the program a command hook runs and the words that precede the hook's own arguments: the program first
 * @param mcpProgram the program a host starts as the MCP server and the words that precede the relay's own arguments: the program first
 * @param platform the platform the installation registers hooks for, used when a host needs a platform-specific command form
 */
public record HookInstallation(List<String> hookProgram, List<String> mcpProgram, Platform platform) {
    public enum Platform {
        POSIX, WINDOWS
    }

    /** A registration using the portable command form. An installer supplies the target platform explicitly. */
    public HookInstallation(List<String> hookProgram, List<String> mcpProgram) {
        this(hookProgram, mcpProgram, Platform.POSIX);
    }

    public HookInstallation {
        hookProgram = List.copyOf(hookProgram);
        mcpProgram = List.copyOf(mcpProgram);
        Objects.requireNonNull(platform, "platform");
        if (hookProgram.isEmpty() || mcpProgram.isEmpty()) {
            throw new IllegalArgumentException("A hook installation runs two programs: the words of neither are empty");
        }
    }

    /** The hook client's program, for a host that takes it apart from its arguments (Claude Code's exec form). */
    public String hookExecutable() {
        return hookProgram.getFirst();
    }

    /** The arguments of {@link #hookExecutable()} in order: the program's own words after it, then the call's. Nothing is quoted: no shell reads them. */
    public List<String> hookArguments(List<String> callArguments) {
        return arguments(hookProgram, callArguments);
    }

    /**
     * The hook client's program and the call's arguments as one command line, for a host whose command is a string a shell of the host's choice splits. The program, which a shell
     * reads first, is put in double quotes when it has a space or a character that a shell and Windows' command interpreter read as more than a name ({@code & | < > ^ ( ) , ; =});
     * every other word of the program when it has a space or one of {@code & | < > ^ ( )} (the options of a JVM hold {@code =}, {@code ,} and {@code :}, which are only characters in
     * a word that is not first, and Antigravity on Windows rewrites a quote inside its command, so a word is quoted only when it must be). A word with a character no quoting
     * survives (a quote, a percent sign, a control character) is refused instead of written. The call's arguments are free of all of these and are never quoted.
     */
    public String hookCommandLine(List<String> callArguments) {
        List<String> words = new ArrayList<>();
        words.add(quoted(hookProgram.getFirst(), "&|<>^(),;="));
        hookProgram.subList(1, hookProgram.size()).forEach(word -> words.add(quoted(word, "&|<>^()")));
        words.addAll(callArguments);
        return String.join(" ", words);
    }

    /** The relay's program, for the hosts that register an MCP server by its command and its argument list: all of them. */
    public String mcpExecutable() {
        return mcpProgram.getFirst();
    }

    /** The arguments of {@link #mcpExecutable()} in order: the program's own words after it, then the relay's. A host starts the MCP server without a shell, so nothing is quoted. */
    public List<String> mcpArguments(List<String> relayArguments) {
        return arguments(mcpProgram, relayArguments);
    }

    private static List<String> arguments(List<String> program, List<String> own) {
        List<String> words = new ArrayList<>(program.subList(1, program.size()));
        words.addAll(own);
        return words;
    }

    private static String quoted(String word, String special) {
        if (word.isEmpty() || word.indexOf('"') >= 0 || word.indexOf('%') >= 0 || word.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("This program path cannot be written in a hook command: " + word);
        }
        return word.chars().anyMatch(c -> Character.isWhitespace(c) || special.indexOf(c) >= 0) ? "\"" + word + "\"" : word;
    }
}
