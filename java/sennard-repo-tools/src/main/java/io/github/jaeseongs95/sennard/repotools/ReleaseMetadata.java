// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.repotools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Every place a release version is written, derived from {@code release/version.json}. */
public final class ReleaseMetadata {
    private static final String VERSION_VALUE = "\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z.-]+)?";
    public static final Pattern SEMVER = Pattern.compile("\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?");
    private static final Pattern VERSION_KO = Pattern.compile("(<!-- release-version:start -->\\r?\\n[\\s\\S]*?현재 (?:공개 릴리스는|릴리스 후보는) `v)" + VERSION_VALUE + "(`[^\\r\\n]*\\r?\\n<!-- release-version:end -->)");
    private static final Pattern VERSION_EN = Pattern.compile("(<!-- release-version:start -->\\r?\\n[\\s\\S]*?current (?:public release is|release candidate is) `v)" + VERSION_VALUE + "(`[^\\r\\n]*\\r?\\n<!-- release-version:end -->)");
    private static final Pattern INSTALL = Pattern.compile("(<!-- release-install:start -->\\r?\\n[\\s\\S]*?--ref v)" + VERSION_VALUE + "([\\s\\S]*?\\r?\\n<!-- release-install:end -->)");
    private static final Pattern AGY = Pattern.compile("(<!-- release-agy:start -->\\r?\\n)([\\s\\S]*?)(\\r?\\n<!-- release-agy:end -->)");
    private static final Pattern RELEASE_TAG = Pattern.compile("\\bv" + VERSION_VALUE + "(?=[/.])");
    private static final Pattern GRADLE_VERSION = Pattern.compile("(?m)^version=.*$");
    private static final String PLUGIN = "sennard";
    /** A checkout between releases builds {@code <next>-dev}; the release sets it to the released version. */
    private static final String DEV_SUFFIX = "-dev";

    private final Repo repo;

    public ReleaseMetadata(Repo repo) {
        this.repo = repo;
    }

    /** @return the files that differ from what the release version requires; in write mode they have been rewritten */
    public List<String> sync(boolean write) {
        Map<String, String> expected = expectedFiles();
        List<String> stale = new ArrayList<>();
        expected.forEach((file, content) -> {
            if (!repo.text(file).equals(content)) {
                if (write) {
                    repo.write(file, content);
                }
                stale.add(file);
            }
        });
        return stale;
    }

    Map<String, String> expectedFiles() {
        JsonNode release = repo.json("release/version.json");
        String version = release.path("version").stringValue("");
        if (!SEMVER.matcher(version).matches()) {
            throw new IllegalStateException("release/version.json must contain a strict semantic version");
        }
        boolean candidate = "candidate".equals(release.path("status").stringValue(""));
        String publicVersion = release.path("publicVersion").stringValue("");
        if (candidate && !SEMVER.matcher(publicVersion).matches()) {
            throw new IllegalStateException("candidate metadata requires publicVersion");
        }
        String installVersion = candidate ? publicVersion : version;
        UnaryOperator<String> status = text -> {
            String korean = replaceFirst(text, candidate ? "현재 공개 릴리스는" : "현재 릴리스 후보는", candidate ? "현재 릴리스 후보는" : "현재 공개 릴리스는");
            return replaceFirst(korean, candidate ? "current public release is" : "current release candidate is", candidate ? "current release candidate is" : "current public release is");
        };
        Map<String, String> files = new LinkedHashMap<>();
        for (String manifest : List.of("plugin.json", ".codex-plugin/plugin.json", ".gemini-plugin/plugin.json", "gemini-extension/gemini-extension.json")) {
            files.put(manifest, json(manifest, doc -> doc.put("version", version)));
        }
        files.put(".agents/plugins/marketplace.json", json(".agents/plugins/marketplace.json",
                doc -> ((ObjectNode) soleEntry(doc, ".agents marketplace").get("source")).put("ref", "v" + version)));
        files.put(".claude-plugin/marketplace.json", json(".claude-plugin/marketplace.json", doc -> {
            doc.put("version", version);
            ObjectNode entry = (ObjectNode) soleEntry(doc, "Claude marketplace");
            entry.put("version", version);
            entry.put("ref", "v" + version);
        }));
        files.put("README.ko.md", readme("README.ko.md", VERSION_KO, version, installVersion, status));
        files.put("README.md", readme("README.md", VERSION_EN, version, installVersion, status));
        // The roadmap is an English and a Korean edition (docs/contributing/document-format.md): each states the release in its own words.
        Map<String, Pattern> roadmaps = new LinkedHashMap<>();
        roadmaps.put("docs/contributing/roadmap.md", VERSION_EN);
        roadmaps.put("docs/contributing/roadmap.ko.md", VERSION_KO);
        roadmaps.forEach((file, pattern) -> {
            String roadmap = repo.text(file);
            assertMarkerPair(roadmap, "release-version", file);
            files.put(file, status.apply(replaceExactlyOnce(roadmap, pattern, match -> match.group(1) + version + match.group(2), file + " release-version")));
        });
        String gradle = repo.text("java/gradle.properties");
        Matcher declared = GRADLE_VERSION.matcher(gradle);
        if (!declared.find()) {
            throw new IllegalStateException("java/gradle.properties has no version");
        }
        if (!declared.group().endsWith(DEV_SUFFIX)) {
            files.put("java/gradle.properties", declared.replaceFirst("version=" + Matcher.quoteReplacement(version)));
        }
        return files;
    }

    private String json(String file, java.util.function.Consumer<ObjectNode> mutate) {
        ObjectNode document = (ObjectNode) repo.json(file);
        mutate.accept(document);
        return PrettyJson.text(document);
    }

    private static JsonNode soleEntry(ObjectNode marketplace, String label) {
        List<JsonNode> entries = new ArrayList<>();
        for (JsonNode entry : marketplace.path("plugins")) {
            if (PLUGIN.equals(entry.path("name").stringValue(""))) {
                entries.add(entry);
            }
        }
        if (entries.size() != 1) {
            throw new IllegalStateException(label + " plugin entry " + PLUGIN + " must occur exactly once");
        }
        return entries.get(0);
    }

    private String readme(String file, Pattern versionPattern, String version, String installVersion, UnaryOperator<String> status) {
        String text = repo.text(file);
        assertMarkerPair(text, "release-version", file);
        assertMarkerPair(text, "release-install", file);
        String versioned = replaceExactlyOnce(text, versionPattern, match -> match.group(1) + version + match.group(2), file + " release-version");
        assertMarkerPair(text, "release-agy", file);
        String installed = replaceExactlyOnce(versioned, INSTALL, match -> match.group(1) + installVersion + match.group(2), file + " release-install");
        String withTags = AGY.matcher(installed).replaceAll(match -> Matcher.quoteReplacement(match.group(1) + RELEASE_TAG.matcher(match.group(2)).replaceAll(Matcher.quoteReplacement("v" + installVersion)) + match.group(3)));
        return status.apply(withTags);
    }

    private static String replaceFirst(String text, String target, String replacement) {
        int at = text.indexOf(target);
        return at < 0 ? text : text.substring(0, at) + replacement + text.substring(at + target.length());
    }

    private static String replaceExactlyOnce(String text, Pattern pattern, Function<MatchResult, String> replacement, String label) {
        long matches = pattern.matcher(text).results().count();
        if (matches != 1) {
            throw new IllegalStateException(label + " marker/value must occur exactly once; found " + matches);
        }
        return pattern.matcher(text).replaceFirst(match -> Matcher.quoteReplacement(replacement.apply(match)));
    }

    private static void assertMarkerPair(String text, String name, String label) {
        for (String edge : List.of("start", "end")) {
            String marker = "<!-- " + name + ":" + edge + " -->";
            int count = 0;
            for (int at = text.indexOf(marker); at >= 0; at = text.indexOf(marker, at + marker.length())) {
                count++;
            }
            if (count != 1) {
                throw new IllegalStateException(label + " " + marker + " must occur exactly once; found " + count);
            }
        }
    }
}
