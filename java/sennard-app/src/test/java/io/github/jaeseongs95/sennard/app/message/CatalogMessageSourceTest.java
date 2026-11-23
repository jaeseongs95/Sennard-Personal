// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.context.NoSuchMessageException;
import org.springframework.context.support.MessageSourceAccessor;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

import tools.jackson.databind.json.JsonMapper;

/**
 * The one message source over the repository's catalogs, case by case.
 *
 * <pre>
 * m1  every catalog line, both languages   each value is a pattern the source reads (the wording of single keys is asserted in m3 and m4; the two languages agree: LocaleCatalogsTest)
 * m2  a language with no catalog (fr)      English
 * m3  the boundaries 0, 1, 2 of a count    en and ko: nothing for 0, the clause with the number for 1 and 2, the thousands separator for 1234
 * m4  a select                             a listed value picks its wording, an unlisted value, an enum constant of an unlisted name and null pick "other"
 * m5  an argument that is not given        written as {name}; a number that is not given is an error
 * m6  quoting                              a doubled apostrophe is one apostrophe, '{' is a brace
 * m7  a key the locale lacks               English; a key nobody has: NoSuchMessageException, or the default message
 * m8  the accessor                         its default locale is English whatever the JVM default is
 * </pre>
 */
class CatalogMessageSourceTest {
    private static final ResourcePatternResolver RESOURCES = new PathMatchingResourcePatternResolver();
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final CatalogMessageSource SOURCE = new CatalogMessageSource(RESOURCES, MAPPER);

    private enum Presence {
        ONLINE, MYSTERY
    }

    private static Map<String, String> catalog(String language) throws IOException {
        Map<String, String> keys = new LinkedHashMap<>();
        for (Resource file : RESOURCES.getResources("classpath*:locales/" + language + "/*.json")) {
            try (InputStream in = file.getInputStream()) {
                MAPPER.readTree(in).properties().forEach(entry -> keys.put(entry.getKey(), entry.getValue().asString()));
            }
        }
        return keys;
    }

    private static String editGate(Locale locale, int earlier) {
        return SOURCE.getMessage("hooks.editGate.denied", new Object[] {Map.of("limit", 1234, "paths", "a.txt", "earlier", earlier, "example", "{}")}, locale);
    }

    @Test
    void aLanguageWithNoCatalogIsAnsweredInEnglish() {
        assertEquals(editGate(Locale.ENGLISH, 2), editGate(Locale.FRENCH, 2));
        assertEquals(editGate(Locale.ENGLISH, 2), editGate(Locale.ROOT, 2));
        assertEquals(editGate(Locale.ENGLISH, 2), editGate(null, 2));
    }

    @Test
    void aCountHasNoClauseAtZeroAndTheNumberAtOneAndTwo() {
        assertEquals("[Sennard] Product file #1,234 changed without a workflow: a.txt. Start a workflow first (minimal call: {}).", editGate(Locale.ENGLISH, 0));
        assertEquals("[Sennard] Product file #1,234 changed without a workflow: a.txt (incl. 1 changed earlier). Start a workflow first (minimal call: {}).", editGate(Locale.ENGLISH, 1));
        assertTrue(editGate(Locale.ENGLISH, 2).contains("(incl. 2 changed earlier)"));
        assertTrue(editGate(Locale.ENGLISH, 1234).contains("(incl. 1,234 changed earlier)"));
        assertTrue(editGate(Locale.KOREAN, 0).startsWith("[Sennard] workflow 없이 제품 파일 1,234개째 변경: a.txt. "));
        assertTrue(editGate(Locale.KOREAN, 1).contains("a.txt (앞서 바꾼 제품 파일 1개 포함). "));
        assertTrue(editGate(Locale.KOREAN, 2).contains("(앞서 바꾼 제품 파일 2개 포함)"));
    }

    private static String status(Locale locale, Object state) {
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("state", state);
        return SOURCE.getMessage("hooks.checkpointCue.status", new Object[] {arguments}, locale);
    }

    @Test
    void aSelectPicksItsWordingByTheValueAndOtherForTheRest() {
        assertEquals("[Sennard checkpoint status] Work record: present.", status(Locale.ENGLISH, "saved"));
        assertEquals("[Sennard checkpoint status] No prior save: this does not restore what compaction lost.", status(Locale.ENGLISH, "missing"));
        assertEquals("[Sennard checkpoint status] Work record: none.", status(Locale.ENGLISH, "unheard-of"));
        assertEquals("[Sennard checkpoint status] Work record: none.", status(Locale.ENGLISH, Presence.MYSTERY));
        assertEquals("[Sennard checkpoint status] Work record: none.", status(Locale.ENGLISH, null));
        assertEquals(status(Locale.ENGLISH, "other"), status(Locale.ENGLISH, Presence.ONLINE));
        assertTrue(status(Locale.KOREAN, "saved").length() > 0 && !status(Locale.KOREAN, "saved").equals(status(Locale.ENGLISH, "saved")));
    }

    @Test
    void anArgumentThatIsNotGivenIsWrittenAsItsNameAndANumberThatIsNotGivenIsAnError() {
        assertEquals("[Sennard global context stateToken={stateToken}]\n"
                + SOURCE.getMessage("hooks.contextLayers.global", new Object[] {Map.of("stateToken", "x", "body", "{body}", "changed", "no")}, Locale.ENGLISH).split("\n", 2)[1],
                SOURCE.getMessage("hooks.contextLayers.global", new Object[] {Map.of("body", "{body}", "changed", "no")}, Locale.ENGLISH));
        assertThrows(IllegalArgumentException.class, () -> SOURCE.getMessage("hooks.editGate.denied", new Object[] {Map.of("paths", "a")}, Locale.ENGLISH));
    }

    @Test
    void quotingIsTheJdksAndTheFormsOtherThanNameAndNumberAreRefused() {
        assertEquals(List.of("n"), CatalogPattern.parse("it''s '{x}' {n,number}").names());
        assertEquals("it's {x} 1,234", new java.text.MessageFormat("it''s '{x}' {0,number}", Locale.ENGLISH).format(new Object[] {1234}));
        assertThrows(IllegalArgumentException.class, () -> CatalogPattern.parse("{n, plural, one {a} other {b}}"));
        assertThrows(IllegalArgumentException.class, () -> CatalogPattern.parse("{n,date}"));
        assertThrows(IllegalArgumentException.class, () -> CatalogPattern.parse("{n"));
        assertThrows(IllegalArgumentException.class, () -> CatalogPattern.parse("it's {n}"));
    }

    /** The baseline rows cover only the keys of the baseline; a key added later (an apostrophe written as one quote opens a quotation) is caught here, by its name. */
    @Test
    void everyCatalogValueOfEveryLanguageIsAPatternTheSourceReads() throws IOException {
        List<String> unreadable = new ArrayList<>();
        for (String language : List.of("en", "ko")) {
            catalog(language).forEach((key, text) -> {
                try {
                    CatalogPattern.parse(text);
                } catch (IllegalArgumentException cause) {
                    unreadable.add(language + " " + key + ": " + cause.getMessage());
                }
            });
        }
        assertEquals(List.of(), unreadable);
    }

    @Test
    void aKeyTheLocaleLacksIsEnglishAndAKeyNobodyHasIsMissing() {
        assertThrows(NoSuchMessageException.class, () -> SOURCE.getMessage("no.such.key", new Object[0], Locale.KOREAN));
        assertEquals("fallback", SOURCE.getMessage("no.such.key", new Object[0], "fallback", Locale.KOREAN));
    }

    @Test
    void theAccessorAnswersInEnglishWhateverTheJvmDefaultIs() {
        MessageSourceAccessor accessor = new MessageSourceAccessor(SOURCE, Locale.ENGLISH);
        assertEquals(SOURCE.getMessage("hooks.phaseCheck.waiting", new Object[0], Locale.ENGLISH), accessor.getMessage("hooks.phaseCheck.waiting"));
        assertEquals(SOURCE.getMessage("hooks.phaseCheck.waiting", new Object[0], Locale.KOREAN), accessor.getMessage("hooks.phaseCheck.waiting", new Object[0], Locale.KOREAN));
    }
}
