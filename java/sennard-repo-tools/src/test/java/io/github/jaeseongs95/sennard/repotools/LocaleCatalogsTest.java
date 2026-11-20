// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.repotools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import io.github.jaeseongs95.sennard.app.message.CatalogPattern;
import tools.jackson.databind.JsonNode;

/** Every shipped catalog: canonical locale tags, keys under their domain, translations with the source's placeholders, reviewed against the current source. */
class LocaleCatalogsTest {
    private static final Repo REPO = new Repo(Path.of(System.getProperty("sennard.repo.root")));

    /** Allowed value kinds per placeholder: a {@code {name,number}} takes a number, a plain {@code {name}} takes anything. */
    static Map<String, Set<String>> placeholders(String message) {
        CatalogPattern pattern = CatalogPattern.parse(message);
        Map<String, Set<String>> kinds = new HashMap<>();
        for (String name : pattern.names()) {
            kinds.put(name, pattern.numericNames().contains(name) ? Set.of("number") : Set.of("string", "number", "message"));
        }
        return kinds;
    }

    /** The problems of the wording keys {@code key#name=selector}: the sentence they belong to, an argument of it, and the {@code other} wording. */
    static List<String> wordingProblems(Map<String, String> messages) {
        List<String> problems = new ArrayList<>();
        Map<String, Set<String>> selectorsOf = new HashMap<>();
        messages.keySet().stream().filter(key -> key.indexOf('#') >= 0).forEach(key -> {
            String base = key.substring(0, key.indexOf('#'));
            String selection = key.substring(key.indexOf('#') + 1);
            int equals = selection.indexOf('=');
            if (equals <= 0 || selection.indexOf('#') >= 0 || !messages.containsKey(base)) {
                problems.add(key + ": a wording key is <sentence key>#<argument>=<selector> and the sentence must exist");
                return;
            }
            String argument = selection.substring(0, equals);
            if (!placeholders(messages.get(base)).containsKey(argument)) {
                problems.add(key + ": " + argument + " is not an argument of " + base);
            }
            selectorsOf.computeIfAbsent(base + "#" + argument, ignored -> new HashSet<>()).add(selection.substring(equals + 1));
        });
        selectorsOf.forEach((group, selectors) -> {
            if (!selectors.contains("other")) {
                problems.add(group + ": no \"other\" wording");
            }
        });
        return problems;
    }

    private static String names(Map<String, Set<String>> map) {
        return String.join(",", new TreeSet<>(map.keySet()));
    }

    private static Map<String, String> messages(JsonNode node) {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        node.properties().forEach(entry -> result.put(entry.getKey(), entry.getValue().stringValue("")));
        return result;
    }

    @Test
    void shippedCatalogsAreCompleteAndReviewed() {
        JsonNode catalog = REPO.json("locales/catalog.json");
        List<String> problems = new ArrayList<>();
        List<String> locales = new ArrayList<>();
        catalog.path("locales").forEach(tag -> locales.add(tag.stringValue("")));
        String rootLocale = catalog.path("rootLocale").stringValue("");
        for (String locale : new TreeSet<>(concat(locales, rootLocale))) {
            if (!Locale.forLanguageTag(locale).toLanguageTag().equals(locale)) {
                problems.add("locale " + locale + ": not a canonical BCP 47 tag");
            }
        }
        if (!locales.contains(rootLocale)) {
            problems.add("root locale " + rootLocale + " is not shipped");
        }
        catalog.path("domains").properties().forEach(domainEntry -> {
            String domain = domainEntry.getKey();
            String sourceLocale = domainEntry.getValue().path("sourceLocale").stringValue("");
            if (!locales.contains(sourceLocale)) {
                problems.add(domain + ": source locale " + sourceLocale + " is not shipped");
                return;
            }
            String sourceFile = "locales/" + sourceLocale + "/" + domain + ".json";
            if (!REPO.exists(sourceFile)) {
                problems.add(sourceFile + ": missing");
                return;
            }
            Map<String, String> source = messages(REPO.json(sourceFile));
            wordingProblems(source).forEach(problem -> problems.add(sourceLocale + " " + problem));
            Map<String, Map<String, Set<String>>> sourceKinds = new HashMap<>();
            source.forEach((key, text) -> {
                if (!key.startsWith(domain + ".")) {
                    problems.add(sourceFile + " " + key + ": key must start with \"" + domain + ".\"");
                }
                try {
                    sourceKinds.put(key, placeholders(text));
                } catch (RuntimeException e) {
                    problems.add(sourceLocale + " " + key + ": " + e.getMessage());
                }
            });
            for (String locale : locales) {
                if (locale.equals(sourceLocale)) {
                    continue;
                }
                String file = "locales/" + locale + "/" + domain + ".json";
                if (!REPO.exists(file)) {
                    problems.add(file + ": missing");
                    continue;
                }
                Map<String, String> translated = messages(REPO.json(file));
                wordingProblems(translated).forEach(problem -> problems.add(locale + " " + problem));
                String lockFile = "locales/" + locale + "/translations.lock.json";
                JsonNode lock = REPO.exists(lockFile) ? REPO.json(lockFile) : Repo.JSON.createObjectNode();
                source.keySet().stream().filter(key -> !translated.containsKey(key)).forEach(key -> problems.add(locale + " " + key + ": missing translation"));
                translated.forEach((key, text) -> {
                    if (!source.containsKey(key)) {
                        problems.add(locale + " " + key + ": not in the " + sourceLocale + " source");
                        return;
                    }
                    Map<String, Set<String>> kinds;
                    try {
                        kinds = placeholders(text);
                    } catch (RuntimeException e) {
                        problems.add(locale + " " + key + ": " + e.getMessage());
                        return;
                    }
                    Map<String, Set<String>> expected = sourceKinds.get(key);
                    if (expected == null) {
                        return;
                    }
                    if (!names(kinds).equals(names(expected))) {
                        problems.add(locale + " " + key + ": placeholders {" + names(kinds) + "} differ from the source {" + names(expected) + "}");
                        return;
                    }
                    expected.forEach((name, allowed) -> {
                        if (!kinds.get(name).containsAll(allowed)) {
                            problems.add(locale + " " + key + ": {" + name + "} cannot take every value the source allows");
                        }
                    });
                    if (!LocaleCatalogs.digest(source.get(key)).equals(lock.path(key).stringValue(""))) {
                        problems.add(locale + " " + key + ": translation not reviewed against the current source (run :sennard-repo-tools:acceptTranslation after review)");
                    }
                });
            }
        });
        assertEquals(List.of(), problems);
    }

    private static List<String> concat(List<String> values, String more) {
        List<String> all = new ArrayList<>(values);
        all.add(more);
        return all;
    }

    @Test
    void placeholderKindsFollowTheArgumentType() {
        assertEquals(Set.of("string", "number", "message"), placeholders("Hi {name}!").get("name"));
        assertEquals(Set.of("number"), placeholders("{n, number} files").get("n"));
        assertEquals(Set.of("number"), placeholders("{n} and {n,number}").get("n"));
        assertEquals(Set.of(), placeholders("It''s fine").keySet());
        assertEquals(Set.of("paths"), placeholders("the workflows''s writeTargets: {paths}").keySet());
        assertThrows(IllegalArgumentException.class, () -> placeholders("{n, plural, one {# file} other {# files}}"));
        assertThrows(IllegalArgumentException.class, () -> placeholders("the workflows' writeTargets: {paths}"));
    }

    @Test
    void wordingKeysNeedTheirSentenceAnArgumentOfItAndOther() {
        assertEquals(List.of(), wordingProblems(Map.of("a.b", "x {kind}", "a.b#kind=one", "1", "a.b#kind=other", "n")));
        assertEquals(1, wordingProblems(Map.of("a.b", "x {kind}", "a.b#kind=one", "1")).size());
        assertEquals(2, wordingProblems(Map.of("a.b", "x", "a.b#kind=one", "1", "a.b#kind=other", "n")).size());
        assertEquals(1, wordingProblems(Map.of("a.c#kind=other", "n")).size());
    }
}
