// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.archchecks;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * The three rules about what the repository may carry besides Java, which the user fixed as exceptions (the only non-Java things in the product): a script
 * executable is only the Gradle wrapper, a plugin package defines no hook and no MCP server (the installation writes them, naming its own Java and the Sennard Java programs), and a CI step's {@code run} only calls
 * {@code ./gradlew} (in a job without write permission; a job with write permission only uploads the files it was handed with {@code gh release upload}). Unlike the source rules they read files of the whole repository, so a violation names its file from the repository root (not from the
 * java folder). Each ceiling is a count of violations like the others; the script rule is a plain ban (ceiling 0).
 */
final class RepositoryRules {
    private RepositoryRules() {
    }

    static final String SCRIPT_EXECUTABLES = "script-executables";
    static final String HOOK_COMMAND = "hook-command-not-java-client";
    static final String CI_RUN = "ci-run-not-gradlew";

    // ------------------------------------------------------------------------------------------------------ script executables

    /** Folders that hold no source of the repository: version control, build output, caches, installed packages. */
    private static final Set<String> SKIPPED_FOLDERS = Set.of(".git", "build", ".gradle", "node_modules");
    private static final Set<String> SCRIPT_EXTENSIONS = Set.of("sh", "ps1", "psm1", "py", "js", "mjs", "cjs", "ts", "cmd", "bat", "vbs");
    /** The Gradle wrapper (user: gradlew and gradlew.bat are the accepted exceptions), as paths from the repository root. */
    static final Set<String> ALLOWED_SCRIPTS = Set.of("java/gradlew", "java/gradlew.bat");

    /** A file with one of the script extensions, or whose first two bytes are {@code #!}, that is not on the allow list. The {@code <script>} inside an HTML page is not a file and is not looked at. */
    static List<Violation> scriptExecutables(Path repository) throws IOException {
        List<Violation> found = new ArrayList<>();
        Files.walkFileTree(repository, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                return !directory.equals(repository) && SKIPPED_FOLDERS.contains(directory.getFileName().toString()) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                String name = file.getFileName().toString();
                if (!attributes.isRegularFile() || SKIPPED_FOLDERS.contains(name)) {
                    return FileVisitResult.CONTINUE;
                }
                String path = JavaSources.relative(repository, file);
                if (ALLOWED_SCRIPTS.contains(path)) {
                    return FileVisitResult.CONTINUE;
                }
                int dot = name.lastIndexOf('.');
                String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
                if (SCRIPT_EXTENSIONS.contains(extension)) {
                    found.add(new Violation(SCRIPT_EXECUTABLES, path, 1, "a script file (." + extension + ")"));
                } else if (startsWithShebang(file)) {
                    found.add(new Violation(SCRIPT_EXECUTABLES, path, 1, "a file that starts with #!"));
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return found;
    }

    private static boolean startsWithShebang(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] head = in.readNBytes(2);
            return head.length == 2 && head[0] == '#' && head[1] == '!';
        }
    }

    // ------------------------------------------------------------------------------------------------------------- hook commands

    /** The hook definitions of the hosts, as paths from the repository root. */
    static final List<String> HOOK_FILES = List.of("hooks.json", "hooks/hooks.json", "claude-plugin/hooks/hooks.json", "claude-overlay/hooks/hooks.json",
            "gemini-extension/hooks/hooks.json");

    /** The files of the plugin packages where a host reads its MCP servers (a manifest that holds them as a member, and the files of their own), as paths from the repository root. */
    static final List<String> MCP_FILES = List.of(".mcp.json", "mcp_config.json", "claude-plugin/.mcp.json", "claude-overlay/.mcp.json", "gemini-extension/gemini-extension.json",
            "claude-plugin/.claude-plugin/plugin.json", "claude-overlay/.claude-plugin/plugin.json");

    /**
     * THE place that says what a plugin package may define: nothing. A host's hook definition and its MCP server registration name a program (the user's Java, the Sennard hook client's
     * jar, the relay) and the file where the server announces its address, which are the installation's: the server has no port of its own choosing, so there is no address that a
     * package could write for every user, and there is no program path either. The installation writes them into the host's copy ({@code install}, {@code upgrade}, {@code hooks}),
     * and the package ships the files empty. So a hook handler of any type (a command, an HTTP hook, an {@code mcp_tool}) in a package's hook file, and a server in a package's
     * {@code mcpServers}, are violations. Which command a host may run is the installation's tests' ({@code HookClientCommandTest}); where a host may run a command at all is the
     * contract test {@code HookCommandPlacesTest} of the repo-tools module.
     *
     * <p>A missing file has nothing to count.
     */
    static List<Violation> hookCommands(Path repository) throws IOException {
        List<Violation> found = new ArrayList<>();
        for (String file : HOOK_FILES) {
            Path path = repository.resolve(file);
            if (Files.isRegularFile(path)) {
                found.addAll(hookCommands(file, Files.readString(path, StandardCharsets.UTF_8)));
            }
        }
        for (String file : MCP_FILES) {
            Path path = repository.resolve(file);
            if (Files.isRegularFile(path)) {
                found.addAll(mcpServers(file, Files.readString(path, StandardCharsets.UTF_8)));
            }
        }
        return found;
    }

    /** The hook handlers of a hook definition: every object that has a {@code type}, which is what a host's handler is (a command, an HTTP hook, an MCP tool). */
    static List<Violation> hookCommands(String file, String json) {
        List<Violation> found = new ArrayList<>();
        for (Documents.Obj item : Documents.objects(JsonMapper.shared(), json)) {
            String type = item.string("type");
            if (type != null) {
                found.add(new Violation(HOOK_COMMAND, file, item.line(), "a hook of type \"" + type + "\" in a package, which ships its definition empty"));
            }
        }
        return found;
    }

    /** The servers of a file that registers MCP servers: every object that is a member of the {@code mcpServers} object. */
    static List<Violation> mcpServers(String file, String json) {
        List<Violation> found = new ArrayList<>();
        for (Documents.Obj item : Documents.objects(JsonMapper.shared(), json)) {
            if (item.path().startsWith("mcpServers/") && item.path().indexOf('/', "mcpServers/".length()) < 0) {
                found.add(new Violation(HOOK_COMMAND, file, item.line(), "an MCP server \"" + item.path().substring("mcpServers/".length()) + "\" in a package, which ships its registration empty"));
            }
        }
        return found;
    }

    // ------------------------------------------------------------------------------------------------------------------- CI runs

    private static final String CI_FOLDER = ".github/workflows";

    /** The steps of the workflows that break the rules of {@link #ciRuns(String, String)}. */
    static List<Violation> ciRuns(Path repository) throws IOException {
        List<Violation> found = new ArrayList<>();
        Path folder = repository.resolve(CI_FOLDER);
        if (Files.isDirectory(folder)) {
            List<Path> workflows;
            try (Stream<Path> list = Files.list(folder)) {
                workflows = list.filter(Files::isRegularFile).filter(file -> file.getFileName().toString().endsWith(".yml") || file.getFileName().toString().endsWith(".yaml")).sorted().toList();
            }
            for (Path workflow : workflows) {
                found.addAll(ciRuns(JavaSources.relative(repository, workflow), Files.readString(workflow, StandardCharsets.UTF_8)));
            }
        }
        return found;
    }

    /** What a job's token may do. {@code UNSET}: the workflow and the job say nothing, so the token has the repository's default, which may be write. */
    private enum Access {
        UNSET, READ_ONLY, WRITE
    }

    /** The only action a job with a write token may use: GitHub's own download of the workflow's artifacts. */
    private static final String DOWNLOAD_ACTION = "actions/download-artifact@";

    /**
     * Two rules about what a workflow runs, by what the job's token may do:
     * <ol>
     * <li>a job without write permission runs, in its {@code run} steps, only {@code ./gradlew} (its build and checks are Gradle tasks and JUnit tests in Java);</li>
     * <li>a job with write permission (a {@code write} entry, {@code write-all}, or no {@code permissions} at all, which leaves the repository's default token) checks out nothing,
     * runs no {@code ./gradlew} and no script of the repository, uses no action but {@code actions/download-artifact}, and its {@code run} steps are only
     * {@code gh release upload}.</li>
     * </ol>
     * The second rule is there so that code of the repository (a Gradle build script, a Java task, a third-party action) never runs next to a token that can write.
     * It names {@code gh}, the GitHub CLI that every GitHub-hosted runner has installed, and not a Java program, because a Java program of the repository is exactly the code that
     * must not run there, and the upload to a release is the one call that cannot be done without a program on the runner that is outside the repository
     * (the exception the global instructions allow for what Java cannot replace). The job that builds and checks the files is the first kind and hands them over as workflow artifacts.
     */
    static List<Violation> ciRuns(String file, String yaml) {
        List<Documents.Obj> objects = Documents.objects(YAMLMapper.shared(), yaml);
        Access workflowAccess = accessOf(objects, "");
        List<Violation> found = new ArrayList<>();
        for (Documents.Obj job : objects) {
            String[] segments = job.path().split("/");
            if (segments.length != 2 || !segments[0].equals("jobs")) {
                continue;
            }
            Access own = accessOf(objects, job.path());
            Access access = own != Access.UNSET ? own : workflowAccess;
            boolean writes = access != Access.READ_ONLY;
            if (writes && job.string("uses") != null) {
                found.add(new Violation(CI_RUN, file, job.line(), "write job " + segments[1] + ": uses " + job.string("uses")));
            }
            for (Documents.Obj step : objects) {
                if (!step.path().equals(job.path() + "/steps")) {
                    continue;
                }
                String foreign = writes ? foreignToUpload(step) : foreignToGradlew(step);
                if (foreign != null) {
                    found.add(new Violation(CI_RUN, file, step.line(), writes ? "write job " + segments[1] + ": " + foreign : foreign));
                }
            }
        }
        return found;
    }

    /** The access the {@code permissions} of the object at {@code path} (the workflow is "") give: a word ({@code write-all}, {@code read-all}) or a map of scopes. */
    private static Access accessOf(List<Documents.Obj> objects, String path) {
        String mapPath = path.isEmpty() ? "permissions" : path + "/permissions";
        for (Documents.Obj object : objects) {
            if (object.path().equals(path) && object.string("permissions") != null) {
                return "write-all".equals(object.string("permissions")) ? Access.WRITE : Access.READ_ONLY;
            }
            if (object.path().equals(mapPath)) {
                return object.strings().containsValue("write") ? Access.WRITE : Access.READ_ONLY;
            }
        }
        return Access.UNSET;
    }

    /** What a step of a job without write permission runs that is not {@code ./gradlew}, or null. */
    private static String foreignToGradlew(Documents.Obj step) {
        String run = step.string("run");
        return run == null ? null : firstLineNot(run, RepositoryRules::callsGradlew);
    }

    /** What a step of a job with write permission does that is not downloading the workflow's artifacts or {@code gh release upload}, or null. */
    private static String foreignToUpload(Documents.Obj step) {
        String uses = step.string("uses");
        if (uses != null && !uses.startsWith(DOWNLOAD_ACTION)) {
            return "uses " + uses;
        }
        String run = step.string("run");
        return run == null ? null : firstLineNot(run, RepositoryRules::uploadsRelease);
    }

    /** The first command line of a run script that the check does not accept: blank lines and comments are left out and a line ending in a backslash is joined with the next. */
    static String firstLineNot(String run, Predicate<String> accepted) {
        StringBuilder logical = new StringBuilder();
        for (String raw : run.lines().toList()) {
            String line = raw.strip();
            if (logical.isEmpty() && (line.isEmpty() || line.startsWith("#"))) {
                continue;
            }
            if (line.endsWith("\\")) {
                logical.append(line, 0, line.length() - 1).append(' ');
                continue;
            }
            String command = logical.append(line).toString().strip();
            logical.setLength(0);
            if (!accepted.test(command)) {
                return command;
            }
        }
        String rest = logical.toString().strip();
        return rest.isEmpty() || accepted.test(rest) ? null : rest;
    }

    private static boolean callsGradlew(String command) {
        String wrapper = "./gradlew";
        return command.startsWith(wrapper) && (command.length() == wrapper.length() || Character.isWhitespace(command.charAt(wrapper.length())));
    }

    private static final String UPLOAD = "gh release upload";
    /** What chains or redirects a command, or substitutes another one into it: none of it may follow {@code gh release upload}, or the line would run more than the upload. */
    private static final Pattern SHELL_CONTROL = Pattern.compile("[;|&<>`]|\\$\\(");

    /** {@code gh release upload <tag> <files> ...} and nothing chained to it. */
    private static boolean uploadsRelease(String command) {
        return command.startsWith(UPLOAD) && command.length() > UPLOAD.length() && Character.isWhitespace(command.charAt(UPLOAD.length())) && !SHELL_CONTROL.matcher(command).find();
    }
}
