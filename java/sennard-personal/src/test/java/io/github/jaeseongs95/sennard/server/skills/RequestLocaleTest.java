// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.skills;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.text.Collator;
import java.util.Locale;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.MessageSourceAccessor;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import io.github.jaeseongs95.sennard.app.message.CatalogMessageSource;
import io.github.jaeseongs95.sennard.server.message.Messages;
import io.github.jaeseongs95.sennard.server.web.RequestLocale;
import tools.jackson.databind.json.JsonMapper;

/**
 * The language of an answer is the request's, never the server JVM's default locale: with the JVM default set to Korean, a request in English gets English, a request
 * in Korean gets Korean, a language with no catalog gets English, and where no request is answered the answer is English.
 *
 * <pre>
 * r1  no locale context, JVM default ko   English
 * r2  request en, JVM default ko          English sentence
 * r3  request ko, JVM default en          Korean sentence
 * r4  request fr (no catalog)             English sentence
 * r5  the collation follows the request locale, not the JVM default
 * </pre>
 */
class RequestLocaleTest {
    private static final String KEY = "skillChecks.mutationRiskPreflightSkill.snapshotChecksumMismatch";
    private static final Messages MESSAGES = new Messages(new MessageSourceAccessor(
            new CatalogMessageSource(new PathMatchingResourcePatternResolver(), JsonMapper.builder().build()), Locale.ENGLISH));
    private Locale jvmDefault;

    @BeforeEach
    void remember() {
        jvmDefault = Locale.getDefault();
        LocaleContextHolder.resetLocaleContext();
    }

    @AfterEach
    void restore() {
        Locale.setDefault(jvmDefault);
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void noRequestIsEnglishWhateverTheJvmDefaultIs() {
        Locale.setDefault(Locale.KOREAN);
        assertEquals(Locale.ENGLISH, RequestLocale.current());
        assertEquals("TaskEnvelope.v1 snapshot checksum mismatch", MESSAGES.forRequest(KEY, java.util.Map.of()));
    }

    @Test
    void anEnglishRequestGetsEnglishEvenWhenTheJvmDefaultIsKorean() {
        Locale.setDefault(Locale.KOREAN);
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        assertEquals("TaskEnvelope.v1 snapshot checksum mismatch", MESSAGES.forRequest(KEY, java.util.Map.of()));
    }

    @Test
    void aKoreanRequestGetsKoreanEvenWhenTheJvmDefaultIsEnglish() {
        Locale.setDefault(Locale.ENGLISH);
        LocaleContextHolder.setLocale(Locale.KOREAN);
        assertEquals("TaskEnvelope.v1 스냅샷의 checksum이 일치하지 않는다", MESSAGES.forRequest(KEY, java.util.Map.of()));
    }

    @Test
    void aLanguageWithoutACatalogGetsEnglish() {
        Locale.setDefault(Locale.KOREAN);
        LocaleContextHolder.setLocale(Locale.FRENCH);
        assertEquals(Locale.FRENCH, RequestLocale.current());
        assertEquals("TaskEnvelope.v1 snapshot checksum mismatch", MESSAGES.forRequest(KEY, java.util.Map.of()));
    }

    @Test
    void theCollationFollowsTheRequestLocale() {
        Locale.setDefault(Locale.ENGLISH);
        LocaleContextHolder.setLocale(Locale.forLanguageTag("sv"));
        // In Swedish "ä" sorts after "z"; in English it sorts with "a".
        assertEquals(true, Collator.getInstance(RequestLocale.current()).compare("ä", "z") > 0);
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        assertEquals(true, Collator.getInstance(RequestLocale.current()).compare("ä", "z") < 0);
    }
}
