// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.repotools;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Entry of the repository's Gradle tool tasks; see {@code java/sennard-repo-tools/build.gradle.kts} for how each is run. A task's report goes to the log; a task that
 * finds something to fix or is misused ends by a {@link Failed} exception, which the JVM turns into a non-zero exit that Gradle reports as the task's failure.
 */
public final class RepoTools {
    private static final Logger LOGGER = LoggerFactory.getLogger(RepoTools.class);

    /** The task's check found a difference or was called wrongly; the message says which. */
    public static final class Failed extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public Failed(String message) {
            super(message);
        }
    }

    private RepoTools() {
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            throw new IllegalArgumentException("usage: RepoTools <command> [arguments]");
        }
        Repo repo = new Repo(Path.of("").toAbsolutePath());
        List<String> arguments = Arrays.asList(args).subList(1, args.length);
        switch (args[0]) {
            case "release-metadata" -> releaseMetadata(repo, arguments);
            case "host-hook-files" -> hostHookFiles(repo, arguments);
            case "claude-plugin" -> claudePlugin(repo, arguments);
            case "source-lock" -> sourceLock(repo, arguments);
            case "classification-digests" -> classificationDigests(repo, arguments);
            case "cs-engineering-lock" -> csEngineeringLock(repo, arguments);
            case "antigravity-package" -> antigravityPackage(repo, arguments);
            case "check-whitespace" -> checkWhitespace(repo, arguments);
            case "release-archive" -> releaseArchive(repo, arguments);
            case "release-checksums" -> releaseChecksums(repo, arguments);
            case "i18n-accept" -> i18nAccept(repo, arguments);
            case "i18n-prune" -> i18nPrune(repo, arguments);
            default -> throw new IllegalArgumentException("unknown command " + args[0]);
        }
    }

    private static boolean writing(List<String> arguments) {
        return arguments.contains("--write");
    }

    private static void releaseMetadata(Repo repo, List<String> arguments) {
        List<String> stale = new ReleaseMetadata(repo).sync(writing(arguments));
        if (writing(arguments)) {
            LOGGER.info("synchronized {} release metadata file(s)", stale.size());
            return;
        }
        if (!stale.isEmpty()) {
            throw new Failed("release metadata is stale: " + String.join(", ", stale));
        }
        LOGGER.info("release metadata is synchronized");
    }

    private static void hostHookFiles(Repo repo, List<String> arguments) {
        HostHookFiles files = new HostHookFiles(repo);
        if (writing(arguments)) {
            files.write();
            return;
        }
        failOn(files.check());
    }

    private static void claudePlugin(Repo repo, List<String> arguments) {
        ClaudePlugin plugin = new ClaudePlugin(repo);
        if (writing(arguments)) {
            plugin.write();
            return;
        }
        List<String> problems = plugin.check();
        if (problems.isEmpty()) {
            LOGGER.info("{}: fresh", ClaudePlugin.OUTPUT);
            return;
        }
        problems.forEach(LOGGER::error);
        throw new Failed(ClaudePlugin.OUTPUT + ": " + problems.size() + " problem(s)");
    }

    /** All sources are internal, so an integrated checksum records the skill as committed: it follows the files. */
    private static void sourceLock(Repo repo, List<String> arguments) {
        JsonNode lock = repo.json("skills/source-lock.json");
        int changed = 0;
        for (JsonNode source : lock.path("sources")) {
            String path = source.path("path").stringValue("");
            String checksum = DirectoryChecksum.of(repo, path);
            if (!checksum.equals(source.path("integratedChecksum").stringValue(""))) {
                ((ObjectNode) source).put("integratedChecksum", checksum);
                changed++;
            }
        }
        if (changed > 0 && writing(arguments)) {
            repo.write("skills/source-lock.json", PrettyJson.text(lock));
        }
        LOGGER.info("{} integrated checksum(s) {}", changed, writing(arguments) ? "updated" : "differ");
        if (changed != 0 && !writing(arguments)) {
            throw new Failed(changed + " integrated checksum(s) differ");
        }
    }

    /** A classification span's digest is the checksum of the file it cites; the spans are the author's and stay where they are. */
    private static void classificationDigests(Repo repo, List<String> arguments) {
        ClaudePlugin plugin = new ClaudePlugin(repo);
        List<String> stale = writing(arguments) ? plugin.updateClassificationDigests() : plugin.classificationDigests(repo.trackedFiles(), false);
        String verb = writing(arguments) ? "updated" : "differ";
        stale.forEach(file -> LOGGER.info("{}: {}", verb, file));
        LOGGER.info("{} cited file(s) {}", stale.size(), verb);
        if (!stale.isEmpty() && !writing(arguments)) {
            throw new Failed(stale.size() + " cited file(s) differ");
        }
    }

    /** The digests of the cs-engineering knowledge pack and of its frozen example follow the files they name (see {@link CsEngineeringLock}). */
    private static void csEngineeringLock(Repo repo, List<String> arguments) {
        List<String> stale = new CsEngineeringLock(repo).sync(writing(arguments));
        String verb = writing(arguments) ? "updated" : "differ";
        stale.forEach(file -> LOGGER.info("{}: {}", verb, file));
        LOGGER.info("{} cs-engineering file(s) {}", stale.size(), verb);
        if (!stale.isEmpty() && !writing(arguments)) {
            throw new Failed(stale.size() + " cs-engineering file(s) differ");
        }
    }

    private static void antigravityPackage(Repo repo, List<String> arguments) {
        Map<String, String> options = options(arguments);
        if (!options.containsKey("ref") || !options.containsKey("out")) {
            throw new IllegalArgumentException("usage: --ref <tag or commit> --out <file.zip>");
        }
        new ClaudePlugin(repo).antigravityPackage(options.get("ref"), options.get("out"));
        LOGGER.info("antigravity package: {}", repo.root().resolve(options.get("out")));
    }

    /** {@code git diff --check <base>...HEAD}, or {@code git show --check HEAD} without a base (an empty value is none): see {@link WhitespaceCheck}. */
    private static void checkWhitespace(Repo repo, List<String> arguments) {
        List<String> problems = new WhitespaceCheck(repo).problems(options(arguments).get("base"));
        if (!problems.isEmpty()) {
            problems.forEach(LOGGER::error);
            throw new Failed(problems.size() + " whitespace problem(s) in the added lines");
        }
        LOGGER.info("no whitespace problem in the added lines");
    }

    /** The release jar the build made: its name against the tag and the version, and its checksum file. */
    private static void releaseArchive(Repo repo, List<String> arguments) {
        Map<String, String> options = options(arguments);
        if (!options.containsKey("dir")) {
            throw new IllegalArgumentException("usage: --tag <tag or empty> --dir <folder>");
        }
        Path directory = repo.root().resolve(options.get("dir"));
        String asset = new ReleaseAssets(repo).checkJar(directory, options.get("tag"));
        LOGGER.info("{} and {}.sha256 are in {}", asset, asset, directory);
    }

    /** The jar and the checksum file of a folder, against each other. */
    private static void releaseChecksums(Repo repo, List<String> arguments) {
        Map<String, String> options = options(arguments);
        if (!options.containsKey("dir")) {
            throw new IllegalArgumentException("usage: --dir <folder>");
        }
        new ReleaseAssets(repo).checkChecksum(repo.root().resolve(options.get("dir")));
        LOGGER.info("the jar of {} matches its checksum", options.get("dir"));
    }

    private static void i18nPrune(Repo repo, List<String> prefixes) {
        if (prefixes.isEmpty()) {
            throw new IllegalArgumentException("usage: i18n-prune <key prefix>...");
        }
        LOGGER.info("removed {}", new LocaleCatalogs(repo).prune(prefixes));
    }

    private static void i18nAccept(Repo repo, List<String> arguments) {
        if (arguments.size() != 1) {
            throw new IllegalArgumentException("usage: i18n-accept <locale>");
        }
        new LocaleCatalogs(repo).accept(arguments.get(0));
    }

    private static void failOn(List<String> problems) {
        if (!problems.isEmpty()) {
            problems.forEach(LOGGER::error);
            throw new Failed(problems.size() + " problem(s)");
        }
    }

    private static Map<String, String> options(List<String> arguments) {
        Map<String, String> options = new TreeMap<>();
        for (int index = 0; index + 1 < arguments.size(); index += 2) {
            if (!arguments.get(index).startsWith("--")) {
                throw new IllegalArgumentException("Unexpected argument: " + arguments.get(index));
            }
            options.put(arguments.get(index).substring(2), arguments.get(index + 1));
        }
        return options;
    }
}
