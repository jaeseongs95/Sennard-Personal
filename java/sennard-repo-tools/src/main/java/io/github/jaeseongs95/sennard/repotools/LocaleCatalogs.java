// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.repotools;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.jaeseongs95.sennard.domain.Digests;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** The message catalogs under {@code locales/}: a reviewed translation records the digest of the source text it was checked against. */
public final class LocaleCatalogs {
    private static final Pattern KEY_LITERAL = Pattern.compile("\"([A-Za-z][A-Za-z0-9_.\\-]*)\"");

    private final Repo repo;

    public LocaleCatalogs(Repo repo) {
        this.repo = repo;
    }

    /** Records the current source digests for {@code locale}'s translations, after review. */
    public void accept(String locale) {
        JsonNode catalog = repo.json("locales/catalog.json");
        List<String> locales = new ArrayList<>();
        catalog.path("locales").forEach(tag -> locales.add(tag.stringValue("")));
        if (!locales.contains(locale)) {
            throw new IllegalArgumentException(locale + " is not a shipped locale.");
        }
        String lockFile = Repo.join("locales", locale, "translations.lock.json");
        Map<String, String> lock = new TreeMap<>();
        if (repo.exists(lockFile)) {
            repo.json(lockFile).properties().forEach(entry -> lock.put(entry.getKey(), entry.getValue().stringValue("")));
        }
        catalog.path("domains").properties().forEach(domainEntry -> {
            String domain = domainEntry.getKey();
            String sourceLocale = domainEntry.getValue().path("sourceLocale").stringValue("");
            String translated = Repo.join("locales", locale, domain + ".json");
            if (sourceLocale.equals(locale) || !repo.exists(translated)) {
                return;
            }
            JsonNode source = repo.json(Repo.join("locales", sourceLocale, domain + ".json"));
            repo.json(translated).properties().forEach(entry -> {
                if (source.has(entry.getKey())) {
                    lock.put(entry.getKey(), digest(source.get(entry.getKey()).stringValue("")));
                }
            });
        });
        ObjectNode document = Repo.JSON.createObjectNode();
        lock.forEach(document::put);
        repo.write(lockFile, PrettyJson.text(document));
    }

    /**
     * Removes the keys under the given prefixes that nothing in the product refers to, from every locale and from the translation locks. A key is referred to when a
     * string literal in the Java sources, or a contract or skill file, is that key or a prefix of it ending in a dot.
     *
     * @return the number of keys removed
     */
    public int prune(List<String> prefixes) {
        Set<String> literals = new HashSet<>();
        for (String file : repo.trackedFiles()) {
            boolean source = file.startsWith("java/") && file.contains("/src/main/") && (file.endsWith(".java") || file.endsWith(".yaml") || file.endsWith(".html") || file.endsWith(".js"));
            if (source || ((file.startsWith("contracts/") || file.startsWith("skills/")) && file.endsWith(".json"))) {
                Matcher literal = KEY_LITERAL.matcher(repo.text(file));
                while (literal.find()) {
                    literals.add(literal.group(1));
                }
            }
        }
        List<String> dynamic = literals.stream().filter(text -> text.endsWith(".")).toList();
        JsonNode catalog = repo.json("locales/catalog.json");
        Set<String> removed = new HashSet<>();
        catalog.path("locales").forEach(tag -> {
            String locale = tag.stringValue("");
            catalog.path("domains").propertyNames().forEach(domain -> pruneFile(Repo.join("locales", locale, domain + ".json"), prefixes, literals, dynamic, removed));
            pruneFile(Repo.join("locales", locale, "translations.lock.json"), prefixes, literals, dynamic, removed);
        });
        return removed.size();
    }

    private void pruneFile(String file, List<String> prefixes, Set<String> literals, List<String> dynamic, Set<String> removed) {
        if (!repo.exists(file)) {
            return;
        }
        ObjectNode kept = Repo.JSON.createObjectNode();
        boolean changed = false;
        for (Map.Entry<String, JsonNode> entry : repo.json(file).properties()) {
            String key = entry.getKey();
            boolean candidate = prefixes.stream().anyMatch(key::startsWith);
            if (candidate && !literals.contains(key) && dynamic.stream().noneMatch(key::startsWith)) {
                removed.add(key);
                changed = true;
            } else {
                kept.set(key, entry.getValue());
            }
        }
        if (changed) {
            repo.write(file, PrettyJson.text(kept));
        }
    }

    /** The lock's digest of a source message: unprefixed hex of its UTF-8 bytes. */
    public static String digest(String text) {
        return Digests.sha256Hex(text);
    }
}
