// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook.host;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.jaeseongs95.sennard.server.hook.HostWorkFacts;
import io.github.jaeseongs95.sennard.server.hook.ShellPaths;

/**
 * What one shell command line will write: the literal paths it names, and whether some write's target could not be read (a variable, a glob,
 * pipeline input, a location change the observation cannot follow, inline script code). A bounded syntax observation, not a shell interpreter
 * or a file-system write monitor; a write it cannot read makes the edit gate count one file more.
 */
final class ShellEditTargets {
    /**
     * The shell a command is read in. {@code CMD} is Windows' command interpreter: a backslash is part of a path, only {@code "} quotes, {@code ^} escapes outside
     * quotes, {@code %VAR%} expands, {@code #} starts no comment and {@code ;} separates nothing.
     */
    enum Dialect {
        POSIX, POWERSHELL, CMD, EITHER
    }

    /**
     * @param unknown some write's target could not be read
     * @param shellRooted the writes to a path of the POSIX shell's own root where that is not the file system's ({@code /tmp/x} in Git Bash), normalized: a place this server cannot find a
     *            file at, which only a declared exception can account for
     * @param unreadFolders the folders the shell was in, as the command text names them, when a write it could not read happened: an absolute path of this file system, or a path
     *            relative to the working directory of the hook ({@code ""} for that directory itself)
     * @param unreadUnplaced some unread write happened where the shell's folder is not known (a location change that is not one literal, a folder of the shell's own root)
     * @param opaqueExecutionOnly code runs outside this observation, and no explicit write (named or unread) was observed in any reading of the command
     */
    record Writes(List<String> paths, boolean unknown, List<String> shellRooted, List<String> unreadFolders, boolean unreadUnplaced, boolean opaqueExecutionOnly) {
        /** The same facts as the host-neutral description every host's tool call is given to the gate in. */
        HostWorkFacts.EditTargets editTargets() {
            return new HostWorkFacts.EditTargets(paths, unknown, shellRooted, unreadFolders, unreadUnplaced, opaqueExecutionOnly);
        }
    }

    private record Token(String value, boolean word, boolean literal, String[] body) {
        static Token word(String value) {
            return new Token(value, true, true, null);
        }

        String heredoc() {
            return body == null ? null : body[0];
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(ShellEditTargets.class);
    private static final Pattern POSIX_OPERATOR =Pattern.compile("^(?:<<-|<<<|<<|>>|>\\||>&|<&|&>>|&>|&&|\\|\\||[<>;|&()])");
    private static final Pattern POWERSHELL_OPERATOR = Pattern.compile("^(?:>>|>&|&&|\\|\\||[<>;|&(){},])");
    private static final Pattern DRIVE_PATH = Pattern.compile("^[A-Za-z]:");
    private static final Pattern CMD_OPERATOR = Pattern.compile("^(?:>>|>&|<&|&&|\\|\\||[<>|&()])");
    // Every regular expression of this class is compiled here once; a Pattern is immutable and safe to share (the guide's 5.1), a String.matches or replaceAll compiles one per call.
    private static final Pattern HEREDOC_START = Pattern.compile("<<-?\\s*['\"]?[\\w-]");
    private static final Pattern PS_PARAMETER = Pattern.compile("^-([A-Za-z][\\w-]*)(?::(.*))?$", Pattern.DOTALL);
    private static final Pattern TRAILING_CARRIAGE_RETURN = Pattern.compile("\\r$");
    private static final Pattern LEADING_TABS = Pattern.compile("^\\t+");
    private static final Pattern POWERSHELL_NUL = Pattern.compile("(?i)^nul:?$");
    private static final Pattern WILDCARD_CHARACTERS = Pattern.compile(".*[*?\\[\\]].*");
    private static final Pattern IN_PLACE_OPTION = Pattern.compile("^-[A-Za-z]*i.*");
    private static final Pattern SHELL_COMMAND_OPTION = Pattern.compile("^-[A-Za-z]*c$");
    private static final Pattern POWERSHELL_COMMAND_OPTION = Pattern.compile("(?i)^-(?:c|co|com|comm|comma|comman|command)$");
    private static final Pattern SCRIPT_FILE = Pattern.compile("(?i).*\\.[cm]?[jt]sx?$");
    private static final Pattern PYTHON_NAME = Pattern.compile("^(?:python[\\d.]*|py|pypy[\\d.]*)$");
    private static final Pattern ENVIRONMENT_ASSIGNMENT = Pattern.compile("^[A-Za-z_]\\w*=.*");
    private static final Pattern PREFIX_NUMBER = Pattern.compile("^\\d+(?:\\.\\d+)?[smhd]?$");
    private static final Pattern EXECUTABLE_SUFFIX = Pattern.compile("\\.(?:exe|cmd|bat)$");
    private static final Pattern PERL_CODE_OPTION = Pattern.compile("^-[A-Za-z]*[eE]$");
    private static final Pattern CMD_COMMAND_OPTION = Pattern.compile("(?i)^//?[ckr]$");
    private static final Pattern CMD_OPTION = Pattern.compile("^/-?[A-Za-z?][A-Za-z0-9]*(?::.*)?$");
    private static final Pattern CMD_ROBOCOPY_MOVE = Pattern.compile("(?i)^/mov(?:e)?$");
    private static final Pattern CMD_ROBOCOPY_LOG = Pattern.compile("(?i)^/(?:uni)?log\\+?:(.+)$");
    private static final Pattern CMD_SCRIPT_NAME = Pattern.compile("(?i).*\\.(?:bat|cmd)$");
    private static final Pattern POWERSHELL_IO_CALL = Pattern.compile("(?i)^\\[(?:system\\.)?io\\.(?:file|directory)\\]::(?:write|append|copy|move|delete|replace|create).*");
    private static final Token STDIN_WORD = new Token("{}", true, false, null);
    /**
     * Whether a POSIX shell's own tree is not this file system's: this file system does not take {@code /} for an absolute path (Git Bash and MSYS2 on a drive-letter
     * file system, where {@code /d/ws/f} is {@code D:\ws\f}). The file system says it; the server's operating system is not asked.
     */
    private static final boolean SHELL_TREE_IS_NOT_FILE_SYSTEM = !isAbsolute("/");
    private static final List<Pattern> SCRIPT_WRITES = List.of(
            Pattern.compile("\\b(?:writeFile|appendFile|copyFile|createWriteStream|writeTextFile|write_text|write_bytes|file_put_contents)"),
            Pattern.compile("\\b(?:unlink|rm|rmdir|rename|cp|truncate)Sync\\s*\\("),
            Pattern.compile("\\b(?:os\\.(?:remove(?:dirs)?|unlink|renames?|replace|truncate)|shutil\\.(?:copy\\w*|move|rmtree))\\s*\\("),
            Pattern.compile("\\.(?:unlink|rename|rm|rmdir|touch|truncate)\\s*\\("),
            Pattern.compile("\\bf?open(?:Sync)?\\s*\\([^)]*?,\\s*(?:mode\\s*=\\s*)?['\"](?:[wax]|r\\+)[bt+]*['\"]"),
            Pattern.compile("\\bopen\\b[^;\\n]*?['\"]\\+?>{1,2}"),
            Pattern.compile("\\bFile\\.(?:write|delete|rename)\\b"));
    private static final Map<String, String> PS_ALIASES = Map.ofEntries(Map.entry("sc", "set-content"), Map.entry("ac", "add-content"), Map.entry("clc", "clear-content"),
            Map.entry("ni", "new-item"), Map.entry("cpi", "copy-item"), Map.entry("copy", "copy-item"), Map.entry("cp", "copy-item"), Map.entry("mi", "move-item"),
            Map.entry("move", "move-item"), Map.entry("mv", "move-item"), Map.entry("ri", "remove-item"), Map.entry("rm", "remove-item"), Map.entry("del", "remove-item"),
            Map.entry("erase", "remove-item"), Map.entry("rd", "remove-item"), Map.entry("rmdir", "remove-item"), Map.entry("rni", "rename-item"), Map.entry("ren", "rename-item"),
            Map.entry("tee", "tee-object"), Map.entry("sl", "set-location"), Map.entry("chdir", "set-location"), Map.entry("cd", "set-location"), Map.entry("pushd", "push-location"),
            Map.entry("popd", "pop-location"));
    private static final List<String> PS_VALUED = List.of("path", "literalpath", "pspath", "lp", "filepath", "destination", "value", "encoding", "itemtype", "type", "name", "newname",
            "filter", "include", "exclude", "stream", "delimiter", "credential", "inputobject", "width", "variable", "erroraction", "ea", "warningaction", "wa", "errorvariable", "ev",
            "outvariable", "ov", "informationaction", "ia", "outbuffer", "ob", "pipelinevariable", "pv", "informationvariable", "iv", "warningvariable", "wv", "command",
            "encodedcommand", "file", "executionpolicy");
    private static final Set<String> SHELLS = Set.of("sh", "bash", "zsh", "dash", "ksh");
    /** The options with which a POSIX shell given no command runs none: a syntax check, a version or help query. */
    private static final Set<String> SHELL_NO_RUN_OPTIONS = Set.of("-n", "--version", "--help", "-version");
    private static final String DOUBLE_QUOTE_ESCAPED = "$`\"\\\n";
    private static final Set<String> DENO_RUNS =Set.of("run", "task");
    /** The git commands whose effect on the working tree is the difference of two states of the repository, which the command text does not name. */
    private static final Set<String> GIT_REWRITES_TREE = Set.of("pull", "merge", "cherry-pick", "rebase", "switch");
    private static final Set<String> GIT_STASH_RESTORES = Set.of("pop", "apply");
    private static final Set<String> PREFIXES = Set.of("sudo", "doas", "env", "command", "builtin", "exec", "nohup", "nice", "time", "timeout", "stdbuf");
    private static final Set<String> KEYWORDS = Set.of("if", "then", "else", "elif", "do", "while", "until", "!", "{", "}");
    private static final Map<String, Set<String>> FILE_VALUED = Map.of("truncate", Set.of("-s", "--size", "-r", "--reference"), "touch", Set.of("-d", "--date", "-t", "-r", "--reference"),
            "shred", Set.of("-n", "--iterations", "-s", "--size"));
    private static final Set<String> CP_VALUED = Set.of("-t", "--target-directory", "-S", "--suffix", "-m", "--mode", "-o", "--owner", "-g", "--group");
    private static final Set<String> OUTPUT_REDIRECTS = Set.of(">", ">>", ">|", "&>", "&>>");
    private static final Set<String> OPERAND_REDIRECTS = Set.of("<", "<<", "<<-", "<<<", "<&", ">&");
    private static final Set<String> BOUNDARIES = Set.of(";", "\n", "|", "&", "&&", "||", "(", ")", "{", "}");

    private ShellEditTargets() {
    }

    /**
     * The prospective write targets, or null for a read-only command.
     *
     * @param shell how the shell spells paths where this file system does not
     */
    static Writes of(String command, Dialect dialect, ShellPaths shell) {
        return of(command, dialect, SHELL_TREE_IS_NOT_FILE_SYSTEM, shell);
    }

    /**
     * @param shellTree a POSIX shell's {@code /} is not this file system's root, so the paths it writes in its own tree ({@code /d/ws/f}, {@code /tmp/x}) are
     *            translated or kept as text; false when they are paths of this file system as they stand
     * @param shell how the shell spells paths where this file system does not
     */
    static Writes of(String command, Dialect dialect, boolean shellTree, ShellPaths shell) {
        if (command == null) {
            return null;
        }
        List<Dialect> dialects = dialect != Dialect.EITHER ? List.of(dialect)
                : HEREDOC_START.matcher(command).find() ? List.of(Dialect.POSIX) : List.of(Dialect.POSIX, Dialect.POWERSHELL);
        Set<String> paths = new LinkedHashSet<>();
        Set<String> rooted = new LinkedHashSet<>();
        Set<String> folders = new LinkedHashSet<>();
        boolean unknown = false;
        boolean explicitUnknownWrite = false;
        boolean unplaced = false;
        for (Dialect given : dialects) {
            Scan scan = new Scan(given, shellTree, shell, 0, "");
            scan.run(command);
            paths.addAll(scan.paths);
            rooted.addAll(scan.shellRooted);
            unknown |= scan.unknown;
            explicitUnknownWrite |= scan.explicitUnknownWrite;
            for (String folder : scan.unreadIn) {
                if (folder == null) {
                    unplaced = true;
                } else {
                    folders.add(folder);
                }
            }
        }
        boolean opaqueExecutionOnly = unknown && !explicitUnknownWrite && paths.isEmpty() && rooted.isEmpty();
        return paths.isEmpty() && rooted.isEmpty() && !unknown ? null : new Writes(List.copyOf(paths), unknown, List.copyOf(rooted), List.copyOf(folders), unplaced, opaqueExecutionOnly);
    }

    private static List<Token> tokens(String command, Dialect dialect) {
        List<Token> result = new ArrayList<>();
        Pattern operators = switch (dialect) {
            case POSIX -> POSIX_OPERATOR;
            case CMD -> CMD_OPERATOR;
            case POWERSHELL, EITHER -> POWERSHELL_OPERATOR;
        };
        String wordBreak = switch (dialect) {
            case POSIX -> "<>;|&()";
            case CMD -> "<>|&()";
            case POWERSHELL, EITHER -> "<>;|&(){},";
        };
        boolean cmd = dialect == Dialect.CMD;
        record Heredoc(String delimiter, boolean tabs, Token token) {
        }
        List<Heredoc> heredocs = new ArrayList<>();
        int index = 0;
        while (index < command.length()) {
            char character = command.charAt(index);
            if (character == '\n') {
                result.add(new Token("\n", false, true, null));
                index++;
                for (Heredoc heredoc : heredocs) {
                    List<String> lines = new ArrayList<>();
                    while (index < command.length()) {
                        int end = command.indexOf('\n', index);
                        String line = command.substring(index, end < 0 ? command.length() : end);
                        line = TRAILING_CARRIAGE_RETURN.matcher(line).replaceAll("");
                        index = end < 0 ? command.length() : end + 1;
                        if ((heredoc.tabs() ? LEADING_TABS.matcher(line).replaceFirst("") : line).equals(heredoc.delimiter())) {
                            break;
                        }
                        lines.add(line);
                    }
                    heredoc.token().body()[0] = String.join("\n", lines);
                }
                heredocs.clear();
                continue;
            }
            if (Character.isWhitespace(character)) {
                index++;
                continue;
            }
            if (character == '#' && !cmd) {
                int end = command.indexOf('\n', index);
                index = end < 0 ? command.length() : end;
                continue;
            }
            Matcher operator = operators.matcher(command.substring(index));
            if (operator.find()) {
                result.add(new Token(operator.group(), false, true, null));
                index += operator.group().length();
                continue;
            }
            int wordStart = index;
            StringBuilder value = new StringBuilder();
            char quote = 0;
            boolean literal = true;
            char escape = switch (dialect) {
                case POWERSHELL -> '`';
                case CMD -> '^';
                case POSIX, EITHER -> '\\';
            };
            while (index < command.length()) {
                char next = command.charAt(index);
                if (quote == 0 && (Character.isWhitespace(next) || wordBreak.indexOf(next) >= 0)) {
                    break;
                }
                if (next == '"' || (next == '\'' && !cmd)) {
                    if (quote == 0) {
                        quote = next;
                        index++;
                        continue;
                    }
                    if (quote == next) {
                        if (dialect == Dialect.POWERSHELL && next == '\'' && index + 1 < command.length() && command.charAt(index + 1) == '\'') {
                            value.append('\'');
                            index += 2;
                            continue;
                        }
                        quote = 0;
                        index++;
                        continue;
                    }
                }
                // In a POSIX double-quoted string a backslash escapes only $ ` " \ and a line end; before anything else it is a backslash, as in a Windows path given to cmd.
                boolean backslashStays = quote == '"' && escape == '\\' && index + 1 < command.length() && DOUBLE_QUOTE_ESCAPED.indexOf(command.charAt(index + 1)) < 0;
                if (next == escape && quote != '\'' && !backslashStays && (!cmd || quote == 0) && index + 1 < command.length()) {
                    char escaped = command.charAt(index + 1);
                    if (escaped != '\n') {
                        value.append(escaped);
                    }
                    index += 2;
                    continue;
                }
                boolean unreadable = cmd ? next == '%' || (quote == 0 && "*?".indexOf(next) >= 0)
                        : quote != '\'' && (next == '$' || (dialect == Dialect.POSIX && next == '`') || (quote == 0 && "*?~[]".indexOf(next) >= 0));
                if (unreadable) {
                    literal = false;
                }
                value.append(next);
                index++;
            }
            if (quote != 0) {
                literal = false;
            }
            Token previous = result.isEmpty() ? null : result.getLast();
            boolean heredocStart = dialect == Dialect.POSIX && previous != null && ("<<".equals(previous.value()) || "<<-".equals(previous.value()));
            if (!heredocStart && isDescriptorNumber(command, wordStart, index)) {
                // The number of {@code 2>&1}, {@code 2>file} or {@code 0<file} names the stream the redirection is about; it is no argument of the command and no program's operand.
                continue;
            }
            Token token = new Token(value.toString(), true, literal, heredocStart ? new String[1] : null);
            result.add(token);
            if (heredocStart) {
                heredocs.add(new Heredoc(value.toString(), "<<-".equals(previous.value()), token));
            }
        }
        return result;
    }

    /**
     * Whether the word {@code command[start, end)} is the stream number of a redirection: digits only, as written (no quote or escape), and the redirection operator follows with no space
     * between (with a space, {@code echo 2 > f} has the argument 2).
     */
    private static boolean isDescriptorNumber(String command, int start, int end) {
        if (end <= start || end >= command.length() || (command.charAt(end) != '>' && command.charAt(end) != '<')) {
            return false;
        }
        for (int at = start; at < end; at++) {
            if (command.charAt(at) < '0' || command.charAt(at) > '9') {
                return false;
            }
        }
        return true;
    }

    private record Operands(List<String> options, Map<String, Token> values, List<Token> rest) {
    }

    private static Operands operands(List<Token> args, Set<String> valued) {
        List<String> options = new ArrayList<>();
        Map<String, Token> values = new HashMap<>();
        List<Token> rest = new ArrayList<>();
        boolean ended = false;
        for (int index = 0; index < args.size(); index++) {
            Token token = args.get(index);
            if (!ended && token.literal() && "--".equals(token.value())) {
                ended = true;
                continue;
            }
            if (!ended && token.literal() && token.value().length() >= 2 && token.value().charAt(0) == '-') {
                int equals = token.value().indexOf('=');
                String name = equals < 0 ? token.value() : token.value().substring(0, equals);
                options.add(name);
                if (equals >= 0) {
                    values.put(name, Token.word(token.value().substring(equals + 1)));
                } else if (valued.contains(name) && index + 1 < args.size()) {
                    values.put(name, args.get(++index));
                }
                continue;
            }
            rest.add(token);
        }
        return new Operands(options, values, rest);
    }

    private record PsParameters(List<Map.Entry<String, List<Token>>> named, List<List<Token>> positional) {
    }

    private static PsParameters psParameters(List<Token> args) {
        List<Map.Entry<String, List<Token>>> named = new ArrayList<>();
        List<List<Token>> positional = new ArrayList<>();
        int index = 0;
        while (index < args.size()) {
            Token token = args.get(index);
            if (!token.word()) {
                index++;
                continue;
            }
            Matcher match = token.literal() ? PS_PARAMETER.matcher(token.value()) : null;
            if (match != null && match.matches()) {
                String name = match.group(1).toLowerCase(Locale.ROOT);
                if (match.group(2) != null) {
                    named.add(Map.entry(name, List.of(Token.word(match.group(2)))));
                    index++;
                    continue;
                }
                boolean takesValue = PS_VALUED.stream().anyMatch(valued -> valued.startsWith(name));
                if (!takesValue || index + 1 >= args.size() || !args.get(index + 1).word()) {
                    named.add(Map.entry(name, List.of()));
                    index++;
                    continue;
                }
                List<Token> items = new ArrayList<>();
                index = array(args, index + 1, items);
                named.add(Map.entry(name, items));
                continue;
            }
            List<Token> items = new ArrayList<>();
            index = array(args, index, items);
            positional.add(items);
        }
        return new PsParameters(named, positional);
    }

    private static int array(List<Token> args, int start, List<Token> items) {
        items.add(args.get(start));
        int index = start + 1;
        while (index + 1 < args.size() && ",".equals(args.get(index).value()) && !args.get(index).word()) {
            items.add(args.get(index + 1));
            index += 2;
        }
        return index;
    }

    private static List<Token> psNamed(List<Map.Entry<String, List<Token>>> named, String... names) {
        List<Token> values = new ArrayList<>();
        for (Map.Entry<String, List<Token>> entry : named) {
            if (Arrays.stream(names).anyMatch(full -> full.startsWith(entry.getKey()))) {
                values.addAll(entry.getValue());
            }
        }
        return values;
    }

    private static boolean isAbsolute(String value) {
        try {
            return Path.of(value).isAbsolute();
        } catch (InvalidPathException invalid) {
            LOG.debug("not a path, so not absolute: {}", value, invalid);
            return false;
        }
    }

    private static String join(String base, String value) {
        return base.isEmpty() ? value : Path.of(base).resolve(value).normalize().toString();
    }

    private static final class Scan {
        private final Dialect dialect;
        private final boolean shellTree;
        private final ShellPaths shell;
        private final int depth;
        private String cwd;
        final List<String> paths = new ArrayList<>();
        final List<String> shellRooted = new ArrayList<>();
        boolean unknown;
        /** An observed write whose target is unread, separate from code execution this observation does not inspect. */
        boolean explicitUnknownWrite;
        /** Where the shell was at each unread write, as {@link #folder} gives it; null stands for a place that is not known. A set that takes null: the order is the command's. */
        final Set<String> unreadIn = new LinkedHashSet<>();

        Scan(Dialect dialect, boolean shellTree, ShellPaths shell, int depth, String cwd) {
            this.dialect = dialect;
            this.shellTree = shellTree;
            this.shell = shell;
            this.depth = depth;
            this.cwd = cwd;
        }

        /** A write whose target could not be read, in the folder the shell is in now. */
        private void unread() {
            unknown = true;
            explicitUnknownWrite = true;
            unreadIn.add(folder());
        }

        /**
         * The folder the shell is in, in the spelling of this file system: an absolute path, or one relative to the hook's working directory ({@code ""} when no location
         * change was followed). Null when the shell's folder is not known or is one this file system cannot place (the shell's own root).
         */
        private String folder() {
            if (cwd == null) {
                return null;
            }
            Optional<String> place = shellPlace(cwd);
            if (place.isEmpty()) {
                return cwd;
            }
            String spelled = shell.windowsSpelling(place.get());
            return spelled.equals(place.get()) ? null : spelled;
        }

        void run(String command) {
            Deque<String> saved = new ArrayDeque<>();
            List<Token> current = new ArrayList<>();
            boolean[] nullCwd = new boolean[1];
            for (Token token : tokens(command, dialect)) {
                if (token.word() || !BOUNDARIES.contains(token.value())) {
                    current.add(token);
                    continue;
                }
                simpleCommand(current);
                current = new ArrayList<>();
                if (dialect == Dialect.POSIX && "(".equals(token.value())) {
                    saved.push(cwd == null ? "\0" : cwd);
                }
                if (dialect == Dialect.POSIX && ")".equals(token.value()) && !saved.isEmpty()) {
                    String restored = saved.pop();
                    cwd = "\0".equals(restored) ? null : restored;
                }
            }
            simpleCommand(current);
        }

        private void addPath(Token token, boolean wildcards) {
            if (token == null || !token.word()) {
                return;
            }
            String value = token.value();
            // The null device is named by the shell's own dialect: PowerShell's $null and nul, a POSIX shell's /dev/null. In a POSIX shell nul is a file name like any other.
            if ("/dev/null".equals(value) || (dialect == Dialect.POWERSHELL && ("$null".equalsIgnoreCase(value) || POWERSHELL_NUL.matcher(value).matches()))
                    || (dialect == Dialect.CMD && POWERSHELL_NUL.matcher(value).matches())) {
                return;
            }
            if (!token.literal() || value.isEmpty() || (wildcards && WILDCARD_CHARACTERS.matcher(value).matches())) {
                unread();
                return;
            }
            shellPlace(value).ifPresentOrElse(this::addShellPlace, () -> addFilePath(value));
        }

        /** A write at a path of this file system: as it stands when it is absolute, else under the shell's directory when that is known. */
        private void addFilePath(String value) {
            if (isAbsolute(value)) {
                paths.add(value);
            } else if (cwd == null) {
                unread();
            } else {
                paths.add(join(cwd, value));
            }
        }

        private void addInto(Token directory, List<Token> sources) {
            for (Token source : sources) {
                if (!source.literal() || !directory.literal()) {
                    unread();
                    continue;
                }
                shellPlace(directory.value()).ifPresentOrElse(place -> addShellPlace(shell.childOf(place, shell.baseName(source.value()))),
                        () -> addPath(Token.word(join(directory.value(), shell.baseName(source.value()))), false));
            }
        }

        /**
         * Puts the shell's directory where {@code git -C} runs the command. A directory that is not a literal, or a second {@code -C} (which is
         * relative to the first), leaves the place unknown, so a relative path of the command counts as an unread write.
         */
        private void gitDirectory(Operands git) {
            Token directory = git.values().get("-C");
            if (directory == null) {
                return;
            }
            if (!directory.literal() || directory.value().isEmpty() || git.options().stream().filter("-C"::equals).count() > 1) {
                cwd = null;
            } else {
                changeLocation(directory);
            }
        }

        private void changeLocation(Token target) {
            if (target == null || !target.literal() || "-".equals(target.value()) || target.value().isEmpty()) {
                cwd = null;
                return;
            }
            shellPlace(target.value()).ifPresentOrElse(place -> cwd = place, () -> {
                if (isAbsolute(target.value())) {
                    cwd = target.value();
                } else {
                    cwd = cwd == null ? null : join(cwd, target.value());
                }
            });
        }

        /**
         * {@code cd}, {@code chdir} and {@code pushd} of cmd.exe: {@code /d} also changes the current drive. A drive path without it changes that drive's own directory and
         * leaves the current one where it is, which is not known here, so a relative write after it has no known place.
         */
        private void cmdChangeDirectory(List<Token> args) {
            boolean drive = args.stream().anyMatch(token -> token.literal() && "/d".equalsIgnoreCase(token.value()));
            List<Token> operands = args.stream().filter(token -> !(token.literal() && "/d".equalsIgnoreCase(token.value()))).toList();
            Token target = operands.isEmpty() ? null : operands.get(0);
            if (!drive && target != null && target.literal() && DRIVE_PATH.matcher(target.value()).find()) {
                cwd = null;
                return;
            }
            changeLocation(target);
        }

        /** A POSIX shell whose tree is not the file system's is Git Bash or MSYS2, which write a drive as {@code /d/...}. */
        private boolean msys() {
            return shellTree && dialect == Dialect.POSIX;
        }

        /**
         * Where the shell puts a path, in the shell's own tree: absolute, {@code ..} resolved against its root (where {@code /d} and {@code /c} are two
         * directories, so {@code /d/../c} is the C drive), starting with one slash. None for a path that is not in that tree (a Windows drive, a UNC path, or a
         * relative path when the shell's directory is not known in it) and for every path when the shell's tree is the file system's.
         */
        private Optional<String> shellPlace(String value) {
            return msys() ? shell.placeOf(value, cwd, isAbsolute(value)) : Optional.empty();
        }

        /** A write at a place of the shell's tree: the file of a drive, or text of the shell's own root; {@code /dev} is no file. */
        private void addShellPlace(String place) {
            if (shell.holdsNoFile(place)) {
                return;
            }
            String spelled = shell.windowsSpelling(place);
            if (spelled.equals(place)) {
                shellRooted.add(place);
            } else {
                paths.add(spelled);
            }
        }

        /**
         * Code this observation does not inspect: kept as unread for a change workflow, but distinct from an observed write such as {@link #inlineCode}.
         * Only the workflow policy may allow this execution; a named or unread explicit write elsewhere in the call still counts.
         */
        private void runsScriptFile(boolean runs) {
            if (runs) {
                unknown = true;
                unreadIn.add(folder());
            }
        }

        private void inlineCode(String code) {
            if (code != null && SCRIPT_WRITES.stream().anyMatch(pattern -> pattern.matcher(code).find())) {
                unread();
            }
        }

        private void nested(String command, Dialect inner) {
            if (depth >= 4) {
                unread();
                return;
            }
            Scan scan = new Scan(inner, shellTree, shell, depth + 1, cwd);
            scan.run(command);
            paths.addAll(scan.paths);
            shellRooted.addAll(scan.shellRooted);
            unknown |= scan.unknown;
            explicitUnknownWrite |= scan.explicitUnknownWrite;
            unreadIn.addAll(scan.unreadIn);
        }

        private record Target(Token token, boolean wildcards) {
        }

        private boolean powershellCommand(String name, List<Token> args) {
            String cmdlet = PS_ALIASES.getOrDefault(name, name);
            PsParameters parameters = psParameters(args);
            List<Map.Entry<String, List<Token>>> named = parameters.named();
            List<List<Token>> positional = parameters.positional();
            List<Token> literalPaths = psNamed(named, "literalpath", "pspath", "lp");
            java.util.function.BiFunction<Integer, String[], List<Target>> paths = (index, extra) -> {
                List<Target> given = new ArrayList<>();
                literalPaths.forEach(token -> given.add(new Target(token, false)));
                String[] names = new String[extra.length + 1];
                names[0] = "path";
                System.arraycopy(extra, 0, names, 1, extra.length);
                psNamed(named, names).forEach(token -> given.add(new Target(token, true)));
                if (!given.isEmpty()) {
                    return given;
                }
                List<Target> fallback = new ArrayList<>();
                if (index < positional.size()) {
                    positional.get(index).forEach(token -> fallback.add(new Target(token, true)));
                }
                return fallback;
            };
            switch (cmdlet) {
                case "set-content", "add-content", "clear-content", "out-file", "remove-item" -> {
                    write(paths.apply(0, new String[] {"filepath"}));
                    return true;
                }
                case "tee-object" -> {
                    if (psNamed(named, "variable").isEmpty()) {
                        write(paths.apply(0, new String[] {"filepath"}));
                    }
                    return true;
                }
                case "new-item" -> {
                    List<Token> type = psNamed(named, "itemtype", "type");
                    if (!type.isEmpty() && type.get(0).value().toLowerCase(Locale.ROOT).startsWith("d")) {
                        return true;
                    }
                    List<Token> names = psNamed(named, "name");
                    List<Target> given = paths.apply(0, new String[0]);
                    if (names.isEmpty()) {
                        write(given);
                        return true;
                    }
                    if (given.isEmpty()) {
                        addInto(Token.word("."), names);
                    } else {
                        given.forEach(target -> addInto(target.token(), names));
                    }
                    return true;
                }
                case "copy-item", "move-item" -> {
                    if ("move-item".equals(cmdlet)) {
                        write(paths.apply(0, new String[0]));
                    }
                    List<Token> destination = psNamed(named, "destination");
                    List<Target> targets = new ArrayList<>();
                    (destination.isEmpty() ? (positional.size() > 1 ? positional.get(1) : List.<Token>of()) : destination).forEach(token -> targets.add(new Target(token, false)));
                    write(targets);
                    return true;
                }
                case "rename-item" -> {
                    List<Target> source = paths.apply(0, new String[0]);
                    write(source);
                    List<Token> newNames = psNamed(named, "newname");
                    Token newName = !newNames.isEmpty() ? newNames.get(0) : positional.size() > 1 && !positional.get(1).isEmpty() ? positional.get(1).get(0) : null;
                    if (source.size() != 1 || newName == null) {
                        unread();
                    } else {
                        addInto(Token.word(shell.dirName(source.get(0).token().value())), List.of(newName));
                    }
                    return true;
                }
                case "set-location", "push-location" -> {
                    List<Token> pathsNamed = psNamed(named, "path");
                    changeLocation(!literalPaths.isEmpty() ? literalPaths.get(0) : !pathsNamed.isEmpty() ? pathsNamed.get(0) : !positional.isEmpty() && !positional.get(0).isEmpty() ? positional.get(0).get(0) : null);
                    return true;
                }
                case "pop-location" -> {
                    cwd = null;
                    return true;
                }
                default -> {
                    return false;
                }
            }
        }

        private void write(List<Target> targets) {
            if (targets.isEmpty()) {
                unread();
            }
            targets.forEach(target -> addPath(target.token(), target.wildcards()));
        }

        private String codeAfter(List<Token> args, Set<String> flags) {
            for (int index = 0; index < args.size(); index++) {
                if (flags.contains(args.get(index).value())) {
                    return index + 1 < args.size() ? args.get(index + 1).value() : null;
                }
            }
            return null;
        }

        /**
         * The writes of a program every shell has (the file commands, the interpreters, git, the shells themselves).
         *
         * @return whether the program is one this observation knows; a program it does not know that was started by a path is read as a script file run
         */
        private boolean nativeCommand(String name, List<Token> args, String stdin) {
            switch (name) {
                case "tee", "rm", "unlink", "shred", "touch", "truncate" -> {
                    operands(args, FILE_VALUED.getOrDefault(name, Set.of())).rest().forEach(token -> addPath(token, false));
                    return true;
                }
                case "cp", "mv", "install", "ln" -> {
                    Operands parsed = operands(args, CP_VALUED);
                    if ("install".equals(name) && parsed.options().contains("-d")) {
                        return true;
                    }
                    Token directory = parsed.values().getOrDefault("-t", parsed.values().get("--target-directory"));
                    List<Token> rest = parsed.rest();
                    if ("mv".equals(name)) {
                        (directory != null || rest.isEmpty() ? rest : rest.subList(0, rest.size() - 1)).forEach(token -> addPath(token, false));
                    }
                    if (directory != null) {
                        addInto(directory, rest);
                        return true;
                    }
                    if (rest.size() < 2) {
                        return true;
                    }
                    Token destination = rest.getLast();
                    if (rest.size() > 2 || shell.namesDirectory(destination.value())) {
                        addInto(destination, rest.subList(0, rest.size() - 1));
                    } else {
                        addPath(destination, false);
                    }
                    return true;
                }
                case "sed", "gsed" -> {
                    int bsd = -1;
                    for (int index = 0; index + 1 < args.size(); index++) {
                        if ("-i".equals(args.get(index).value()) && args.get(index + 1).word() && args.get(index + 1).value().isEmpty()) {
                            bsd = index;
                            break;
                        }
                    }
                    List<Token> given = new ArrayList<>(args);
                    if (bsd >= 0) {
                        given.remove(bsd + 1);
                    }
                    Operands parsed = operands(given, Set.of("-e", "--expression", "-f", "--file", "-l", "--line-length"));
                    if (parsed.options().stream().noneMatch(option -> IN_PLACE_OPTION.matcher(option).matches() || "--in-place".equals(option))) {
                        return true;
                    }
                    boolean script = parsed.options().stream().anyMatch(option -> Set.of("-e", "--expression", "-f", "--file").contains(option));
                    List<Token> files = script || parsed.rest().isEmpty() ? parsed.rest() : parsed.rest().subList(1, parsed.rest().size());
                    if (files.isEmpty()) {
                        unread();
                    }
                    files.forEach(token -> addPath(token, false));
                    return true;
                }
                case "dd" -> {
                    args.stream().filter(token -> token.value().startsWith("of=")).forEach(token -> addPath(new Token(token.value().substring(3), token.word(), token.literal(), null), false));
                    return true;
                }
                case "curl", "wget" -> {
                    Set<String> valued = "wget".equals(name) ? Set.of("-o", "--output", "-O", "--output-document") : Set.of("-o", "--output", "--output-document");
                    Operands parsed = operands(args, valued);
                    Token output = "curl".equals(name) ? parsed.values().getOrDefault("-o", parsed.values().get("--output"))
                            : parsed.values().getOrDefault("-O", parsed.values().get("--output-document"));
                    if (output != null) {
                        addPath(output, false);
                    } else if ("wget".equals(name) || parsed.options().stream().anyMatch(option -> "-O".equals(option) || "--remote-name".equals(option))) {
                        unread();
                    }
                    return true;
                }
                case "patch" -> {
                    if (!operands(args, Set.of()).options().contains("--dry-run")) {
                        unread();
                    }
                    return true;
                }
                case "git" -> {
                    Operands all = operands(args, Set.of("-C", "-c", "--git-dir", "--work-tree", "--namespace"));
                    if (all.rest().isEmpty()) {
                        return true;
                    }
                    // The paths a git command names are relative to the directory -C gives it, not to the shell's.
                    String outerDirectory = cwd;
                    gitDirectory(all);
                    int start = args.indexOf(all.rest().get(0));
                    String sub = args.get(start).value();
                    List<Token> subArgs = args.subList(start + 1, args.size());
                    Operands parsed = operands(subArgs, Set.of());
                    Set<String> readOnlyApply = Set.of("--check", "--stat", "--numstat", "--summary");
                    if ("apply".equals(sub) && parsed.options().stream().noneMatch(readOnlyApply::contains)) {
                        unread();
                    } else if ("mv".equals(sub)) {
                        nativeCommand("mv", subArgs, null);
                    } else if ("rm".equals(sub) && !parsed.options().contains("--cached")) {
                        parsed.rest().forEach(token -> addPath(token, false));
                    } else if ("restore".equals(sub) && (parsed.options().stream().noneMatch(option -> "--staged".equals(option) || "-S".equals(option))
                            || parsed.options().stream().anyMatch(option -> "--worktree".equals(option) || "-W".equals(option)))) {
                        parsed.rest().forEach(token -> addPath(token, false));
                    } else if ("checkout".equals(sub)) {
                        int separator = -1;
                        for (int index = 0; index < subArgs.size(); index++) {
                            if ("--".equals(subArgs.get(index).value())) {
                                separator = index;
                                break;
                            }
                        }
                        if (separator >= 0) {
                            subArgs.subList(separator + 1, subArgs.size()).forEach(token -> addPath(token, false));
                        } else if (!parsed.rest().isEmpty()) {
                            // A branch or commit given with no path list: the files the checkout rewrites are the difference of two trees, which no command text names.
                            unread();
                        }
                    } else if (GIT_REWRITES_TREE.contains(sub) || ("reset".equals(sub) && parsed.options().contains("--hard"))
                            || ("stash".equals(sub) && !parsed.rest().isEmpty() && GIT_STASH_RESTORES.contains(parsed.rest().get(0).value()))) {
                        // The state of the repository, not the command text, decides which files these change.
                        unread();
                    }
                    cwd = outerDirectory;
                    return true;
                }
                case "perl" -> {
                    if (args.stream().anyMatch(token -> token.literal() && IN_PLACE_OPTION.matcher(token.value()).matches())) {
                        unread();
                    }
                    String code = codeAfter(args, Set.of("-e", "-E"));
                    inlineCode(code != null ? code : stdin);
                    runsScriptFile(code == null && args.stream().noneMatch(token -> token.literal() && PERL_CODE_OPTION.matcher(token.value()).matches())
                            && args.stream().anyMatch(token -> token.word() && !token.value().startsWith("-")));
                    return true;
                }
                case "eval" -> {
                    nested(String.join(" ", args.stream().map(Token::value).toList()), Dialect.POSIX);
                    return true;
                }
                case "source", "." -> {
                    runsScriptFile(args.stream().anyMatch(Token::word));
                    return true;
                }
                case "cmd" -> {
                    for (int index = 0; index < args.size(); index++) {
                        if (args.get(index).literal() && CMD_COMMAND_OPTION.matcher(args.get(index).value()).matches()) {
                            nested(String.join(" ", args.subList(index + 1, args.size()).stream().map(Token::value).toList()), Dialect.CMD);
                            break;
                        }
                    }
                    return true;
                }
                default -> {
                }
            }
            if (SHELLS.contains(name)) {
                Token script = null;
                for (int index = 0; index < args.size(); index++) {
                    if (args.get(index).literal() && SHELL_COMMAND_OPTION.matcher(args.get(index).value()).matches()) {
                        script = index + 1 < args.size() ? args.get(index + 1) : null;
                        break;
                    }
                }
                Operands parsed = operands(args, Set.of());
                if (script != null) {
                    nested(script.value(), Dialect.POSIX);
                } else if (stdin != null && parsed.rest().isEmpty()) {
                    nested(stdin, Dialect.POSIX);
                } else {
                    // A script file, or commands from a pipe or a file redirect: code this observation does not read. A syntax check or a version query runs none.
                    runsScriptFile(parsed.options().stream().noneMatch(option -> SHELL_NO_RUN_OPTIONS.contains(option)));
                }
                return true;
            }
            if ("pwsh".equals(name) || "powershell".equals(name)) {
                PsParameters parameters = psParameters(args);
                if (!psNamed(parameters.named(), "encodedcommand").isEmpty() || parameters.named().stream().anyMatch(entry -> Set.of("e", "ec", "enc").contains(entry.getKey()))) {
                    unread();
                    return true;
                }
                int commandAt = -1;
                for (int index = 0; index < args.size(); index++) {
                    if (args.get(index).literal() && POWERSHELL_COMMAND_OPTION.matcher(args.get(index).value()).matches()) {
                        commandAt = index;
                        break;
                    }
                }
                if (commandAt >= 0) {
                    nested(String.join(" ", args.subList(commandAt + 1, args.size()).stream().map(Token::value).toList()), Dialect.POWERSHELL);
                    return true;
                }
                Token first = parameters.positional().isEmpty() || parameters.positional().get(0).isEmpty() ? null : parameters.positional().get(0).get(0);
                boolean file = parameters.named().stream().anyMatch(entry -> "file".startsWith(entry.getKey()));
                if (file || (first != null && first.value().toLowerCase(Locale.ROOT).endsWith(".ps1"))) {
                    // A script file: code this observation does not read.
                    runsScriptFile(true);
                } else if (first != null) {
                    nested(String.join(" ", args.subList(args.indexOf(first), args.size()).stream().map(Token::value).toList()), Dialect.POWERSHELL);
                }
                return true;
            }
            boolean scriptOperand = args.stream().anyMatch(token -> token.word() && !token.value().startsWith("-"));
            if (PYTHON_NAME.matcher(name).matches()) {
                String code = codeAfter(args, Set.of("-c"));
                boolean module = args.stream().anyMatch(token -> "-m".equals(token.value()));
                boolean dashStdin = args.stream().anyMatch(token -> "-".equals(token.value()));
                inlineCode(code != null ? code : module || (scriptOperand && !dashStdin) ? null : stdin);
                runsScriptFile(code == null && !module && scriptOperand && !dashStdin);
            } else if (Set.of("node", "nodejs", "bun").contains(name)) {
                String code = codeAfter(args, Set.of("-e", "--eval", "-p", "--print"));
                inlineCode(code != null ? code : scriptOperand ? null : stdin);
                // bun also has subcommands (install, test, ...) that are no script: only an operand that names a script file is one.
                runsScriptFile(code == null && ("bun".equals(name) ? args.stream().anyMatch(token -> token.word() && SCRIPT_FILE.matcher(token.value()).matches()) : scriptOperand));
            } else if ("deno".equals(name)) {
                inlineCode(!args.isEmpty() && "eval".equals(args.get(0).value()) && args.size() > 1 ? args.get(1).value() : null);
                // deno has subcommands (fmt, lint, ...) that run no script: run, task, or a script file given as the first operand do.
                String first = args.isEmpty() ? "" : args.get(0).value();
                runsScriptFile(DENO_RUNS.contains(first) || SCRIPT_FILE.matcher(first).matches());
            } else if ("ruby".equals(name)) {
                String code = codeAfter(args, Set.of("-e"));
                inlineCode(code != null ? code : scriptOperand ? null : stdin);
                runsScriptFile(code == null && scriptOperand);
            } else if ("php".equals(name)) {
                String code = codeAfter(args, Set.of("-r"));
                inlineCode(code);
                runsScriptFile(code == null && scriptOperand);
            } else {
                return false;
            }
            return true;
        }

        /** Whether the program was started by a path ({@code ./x.sh}, {@code scripts/run}, {@code .\x.ps1}); a Windows binary ({@code .exe}) is no script. */
        private boolean startedByPath(String value) {
            boolean separator = value.indexOf('/') >= 0 || (dialect != Dialect.POSIX && value.indexOf('\\') >= 0);
            return separator && !value.toLowerCase(Locale.ROOT).endsWith(".exe");
        }

        private static boolean isCmdOption(Token token) {
            return token.word() && token.literal() && CMD_OPTION.matcher(token.value()).matches();
        }

        /**
         * The file commands built into cmd.exe ({@code dir} and {@code type} only read): a target that is a literal is counted, one that cannot be read makes the write an unread one.
         *
         * @return whether the command is one of them
         */
        private boolean cmdBuiltin(String name, List<Token> args) {
            List<Token> operands = args.stream().filter(token -> token.word() && !isCmdOption(token)).toList();
            switch (name) {
                case "del", "erase", "md", "mkdir", "rd", "rmdir" -> operands.forEach(token -> addPath(token, false));
                case "copy", "xcopy", "move" -> {
                    if ("move".equals(name)) {
                        operands.subList(0, Math.max(0, operands.size() - 1)).forEach(token -> addPath(token, false));
                    }
                    if (operands.size() < 2) {
                        // Without a destination cmd.exe writes to the current directory under the source's name.
                        unread();
                    } else {
                        Token destination = operands.getLast();
                        if (shell.namesDirectory(destination.value()) || destination.value().endsWith("\\")) {
                            addInto(destination, operands.subList(0, operands.size() - 1));
                        } else {
                            addPath(destination, false);
                        }
                    }
                }
                case "ren", "rename" -> {
                    if (operands.size() < 2) {
                        unread();
                    } else {
                        addPath(operands.get(0), false);
                        addInto(Token.word(shell.dirName(operands.get(0).value())), List.of(operands.get(1)));
                    }
                }
                case "robocopy" -> {
                    if (operands.size() < 2) {
                        unread();
                    } else {
                        addPath(operands.get(1), false);
                        if (args.stream().anyMatch(token -> token.literal() && CMD_ROBOCOPY_MOVE.matcher(token.value()).matches())) {
                            addPath(operands.get(0), false);
                        }
                    }
                    args.stream().filter(Token::literal).map(token -> CMD_ROBOCOPY_LOG.matcher(token.value())).filter(Matcher::matches)
                            .forEach(log -> addPath(Token.word(log.group(1)), false));
                }
                case "mklink" -> {
                    if (operands.size() < 2) {
                        unread();
                    } else {
                        addPath(operands.get(0), false);
                    }
                }
                default -> {
                    return false;
                }
            }
            return true;
        }

        private void simpleCommand(List<Token> given) {
            List<Token> args = new ArrayList<>();
            String stdin = null;
            for (int index = 0; index < given.size(); index++) {
                Token token = given.get(index);
                if (!token.word() && OUTPUT_REDIRECTS.contains(token.value())) {
                    addPath(index + 1 < given.size() ? given.get(++index) : null, false);
                    continue;
                }
                if (!token.word() && OPERAND_REDIRECTS.contains(token.value())) {
                    Token operand = index + 1 < given.size() ? given.get(++index) : null;
                    if ("<<<".equals(token.value())) {
                        stdin = operand == null ? null : operand.value();
                    } else if (token.value().startsWith("<<")) {
                        stdin = operand == null ? null : operand.heredoc();
                    }
                    continue;
                }
                args.add(token);
            }
            int index = 0;
            while (true) {
                if (index >= args.size() || !args.get(index).word()) {
                    return;
                }
                Token current = args.get(index);
                String lower = current.value().toLowerCase(Locale.ROOT);
                if (dialect == Dialect.POSIX && (KEYWORDS.contains(current.value()) || ENVIRONMENT_ASSIGNMENT.matcher(current.value()).matches())) {
                    index++;
                    continue;
                }
                if (PREFIXES.contains(lower) || "xargs".equals(lower)) {
                    if ("xargs".equals(lower)) {
                        args.add(STDIN_WORD);
                    }
                    index++;
                    while (index < args.size() && args.get(index).word() && (args.get(index).value().startsWith("-") || PREFIX_NUMBER.matcher(args.get(index).value()).matches()
                            || ("env".equals(lower) && ENVIRONMENT_ASSIGNMENT.matcher(args.get(index).value()).matches()))) {
                        index++;
                    }
                    continue;
                }
                break;
            }
            Token command = args.get(index);
            String name = EXECUTABLE_SUFFIX.matcher(shell.baseName(command.value()).toLowerCase(Locale.ROOT)).replaceAll("");
            List<Token> rest = args.subList(index + 1, args.size());
            if (dialect == Dialect.CMD && Set.of("cd", "pushd", "chdir").contains(name)) {
                cmdChangeDirectory(rest);
            } else if (dialect == Dialect.POSIX && Set.of("cd", "pushd", "chdir").contains(name)) {
                List<Token> operands = operands(rest, Set.of()).rest();
                changeLocation(operands.isEmpty() ? null : operands.get(0));
            } else if ((dialect == Dialect.POSIX || dialect == Dialect.CMD) && "popd".equals(name)) {
                cwd = null;
            } else if (dialect == Dialect.POWERSHELL && POWERSHELL_IO_CALL.matcher(command.value()).matches()) {
                unread();
            } else if (dialect == Dialect.CMD && "call".equals(name)) {
                simpleCommand(new ArrayList<>(rest));
            } else if (!(dialect == Dialect.CMD && cmdBuiltin(name, rest)) && (dialect != Dialect.POWERSHELL || !powershellCommand(name, rest))) {
                if (!nativeCommand(name, rest, stdin)) {
                    // A program this observation does not know, started by a path or as a batch file: a script whose writes are not read.
                    runsScriptFile(startedByPath(command.value()) || (dialect == Dialect.CMD && CMD_SCRIPT_NAME.matcher(command.value()).matches()));
                }
            }
            if ("find".equals(name)) {
                if (rest.stream().anyMatch(token -> "-delete".equals(token.value()))) {
                    unread();
                }
                for (int at = 0; at < rest.size(); at++) {
                    if (!Set.of("-exec", "-execdir", "-ok", "-okdir").contains(rest.get(at).value())) {
                        continue;
                    }
                    int end = -1;
                    for (int after = at + 1; after < rest.size(); after++) {
                        if (";".equals(rest.get(after).value()) || "+".equals(rest.get(after).value())) {
                            end = after;
                            break;
                        }
                    }
                    List<Token> inner = new ArrayList<>();
                    for (Token next : rest.subList(at + 1, end < 0 ? rest.size() : end)) {
                        inner.add("{}".equals(next.value()) ? STDIN_WORD : next);
                    }
                    if (!inner.contains(STDIN_WORD)) {
                        inner.add(STDIN_WORD);
                    }
                    simpleCommand(inner);
                }
            }
        }
    }
}
