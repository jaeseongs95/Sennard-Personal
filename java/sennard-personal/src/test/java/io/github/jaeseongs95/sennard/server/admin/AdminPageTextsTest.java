// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import io.github.jaeseongs95.sennard.app.message.CatalogMessageSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The admin page speaks the request's language through the catalog and nothing else (the user: the service is for the whole world, not for Koreans). The page is a static
 * file and the browser makes its sentences, so what can be held here is what the file says:
 *
 * <pre>
 * p1  the page and the move page have no Korean in them, not in a string and not in a comment
 * p2  every key the page asks for, {@code t('admin.page.…')} or {@code tn('admin.page.…')}, is in the English and in the Korean catalog
 * p3  every key of the page in the catalog is asked for by the page, or by the server for the frame (frame, move, language): none is left over
 * p4  the patterns the page is sent are the request language's, English for a language with no catalog, and the JSON that carries them cannot close its script element
 * </pre>
 */
class AdminPageTextsTest {
    private static final Pattern HANGUL = Pattern.compile("[\\uAC00-\\uD7A3]");
    /**
     * A key the page names, in whatever form it asks for it (a call, a table, either side of a conditional). One that ends with a dot is a fixed beginning that a value
     * completes, {@code 'admin.page.error.' + code}; the catalog must have keys that begin so.
     */
    private static final Pattern KEY_LITERAL = Pattern.compile("['\"](admin\\.page\\.[A-Za-z0-9_.]+)['\"]");
    private static final String PREFIX = AdminTexts.PREFIX;
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static String resource(String path) throws IOException {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Set<String> catalogKeys(String language) throws IOException {
        JsonNode catalog = MAPPER.readTree(resource("locales/" + language + "/admin.json"));
        Set<String> keys = new TreeSet<>();
        catalog.propertyNames().forEach(key -> {
            if (key.startsWith(PREFIX)) {
                keys.add(key);
            }
        });
        return keys;
    }

    /** The whole keys the page names, or its fixed beginnings of keys. */
    private static Set<String> literals(String page, boolean beginnings) {
        Set<String> found = new LinkedHashSet<>();
        Matcher matcher = KEY_LITERAL.matcher(page);
        while (matcher.find()) {
            if (matcher.group(1).endsWith(".") == beginnings) {
                found.add(matcher.group(1));
            }
        }
        return found;
    }

    @Test
    void thePagesHaveNoKoreanInThem() throws IOException {
        for (String path : List.of("admin/admin-page.html", "admin/move-to-localhost.html")) {
            Matcher korean = HANGUL.matcher(resource(path));
            assertFalse(korean.find(), path + " has Korean in it (a sentence belongs in locales/*/admin.json): " + (korean.find(0) ? korean.group() : ""));
        }
    }

    @Test
    void everyKeyThePageAsksForIsInBothCatalogs() throws IOException {
        String page = resource("admin/admin-page.html");
        Set<String> asked = literals(page, false);
        assertFalse(asked.isEmpty(), "the page asks for no sentence");
        for (String language : List.of("en", "ko")) {
            Set<String> keys = catalogKeys(language);
            Set<String> base = new TreeSet<>();
            keys.forEach(key -> base.add(key.contains("#") ? key.substring(0, key.indexOf('#')) : key));
            List<String> missing = new ArrayList<>(asked);
            missing.removeAll(base);
            assertTrue(missing.isEmpty(), language + " lacks " + missing);
            for (String beginning : literals(page, true)) {
                assertTrue(base.stream().anyMatch(key -> key.startsWith(beginning)), language + " has no key that begins " + beginning);
            }
        }
    }

    @Test
    void noKeyOfThePageIsLeftOverAndTheTwoCatalogsHaveTheSameKeys() throws IOException {
        String page = resource("admin/admin-page.html");
        Set<String> asked = literals(page, false);
        Set<String> beginnings = literals(page, true);
        Set<String> english = catalogKeys("en");
        assertEquals(english, catalogKeys("ko"), "English and Korean have the same keys");
        List<String> leftOver = new ArrayList<>();
        for (String key : english) {
            String base = key.contains("#") ? key.substring(0, key.indexOf('#')) : key;
            boolean server = base.startsWith(PREFIX + "frame.") || base.startsWith(PREFIX + "move.") || base.equals(PREFIX + "language");
            if (!server && !asked.contains(base) && beginnings.stream().noneMatch(base::startsWith)) {
                leftOver.add(key);
            }
        }
        assertTrue(leftOver.isEmpty(), "no page asks for " + leftOver);
    }

    @Test
    void thePageIsSentTheRequestLanguagesPatternsAndEnglishForOthers() {
        CatalogMessageSource source = new CatalogMessageSource(new PathMatchingResourcePatternResolver(), MAPPER);
        AdminTexts texts = new AdminTexts(source, MAPPER);
        assertEquals("Sennard Admin", texts.patterns(Locale.ENGLISH).get(PREFIX + "frame.title"));
        assertEquals("Sennard 관리자", texts.patterns(Locale.KOREAN).get(PREFIX + "frame.title"));
        assertEquals("Sennard Admin", texts.patterns(Locale.FRENCH).get(PREFIX + "frame.title"), "no catalog for the language: English");
        assertEquals("en", texts.language(Locale.FRENCH));
        assertEquals("ko", texts.language(Locale.KOREAN));
        Map<String, String> korean = texts.patterns(Locale.KOREAN);
        assertTrue(korean.keySet().stream().allMatch(key -> key.startsWith(PREFIX)), "only the page's keys are sent");
        String json = texts.patternsJson(Locale.ENGLISH);
        assertFalse(json.contains("<") || json.contains(">") || json.contains("&"), "nothing in the JSON can close the script element that holds it");
        assertEquals("Sennard Admin", MAPPER.readTree(json).path(PREFIX + "frame.title").asString());
    }

    @Test
    void aSentenceWithAnArgumentIsRenderedByTheServerForTheFrame() {
        CatalogMessageSource source = new CatalogMessageSource(new PathMatchingResourcePatternResolver(), MAPPER);
        AdminTexts texts = new AdminTexts(source, MAPPER);
        assertTrue(texts.text("move.reason", Locale.ENGLISH, Map.of("target", "http://localhost:1/")).contains("only at http://localhost:1/."));
        assertTrue(texts.text("move.reason", Locale.KOREAN, Map.of("target", "http://localhost:1/")).contains("http://localhost:1/에서만"));
    }
}
