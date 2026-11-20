// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.admin;

import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.app.message.CatalogMessageSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * The admin page's sentences for one request language: the catalog patterns of the keys {@value #PREFIX}, as written. The server does not render them: a sentence of the
 * page has arguments the browser holds (a count, a time, a name), so the page makes the sentence from the pattern with the one function it has for it (the same
 * {@code MessageFormat} rules as {@code CatalogPattern}: {@code {name}}, {@code {name,number}}, a doubled apostrophe, and the wordings {@code key#name=selector}).
 * The language is the request's, English for a language with no catalog ({@code locales/<language>/admin.json}).
 */
@Component
public final class AdminTexts {
    /** The keys the page reads. */
    public static final String PREFIX = "admin.page.";

    private final CatalogMessageSource messages;
    private final JsonMapper mapper;

    public AdminTexts(CatalogMessageSource messages, JsonMapper mapper) {
        this.messages = messages;
        this.mapper = mapper;
    }

    /** The patterns by key (the whole key), English where the language lacks one. */
    public Map<String, String> patterns(Locale locale) {
        return messages.patterns(locale, PREFIX);
    }

    /** The patterns as JSON that can stand inside a {@code <script type="application/json">} element: no character in it can close the element or end a line of script. */
    public String patternsJson(Locale locale) {
        return mapper.writeValueAsString(patterns(locale)).replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026").replace("\u2028", "\\u2028")
                .replace("\u2029", "\\u2029");
    }

    /** One sentence that has no arguments, rendered here: what the server writes into the page before a script runs (the language, the title). */
    public String text(String key, Locale locale) {
        return messages.getMessage(PREFIX + key, null, locale);
    }

    /** One sentence rendered here with its named arguments. */
    public String text(String key, Locale locale, Map<String, Object> arguments) {
        return messages.getMessage(PREFIX + key, new Object[] {arguments}, locale);
    }

    /** The language the page is written in: the request's when the catalogs have it, English otherwise. */
    public String language(Locale locale) {
        return messages.patterns(locale, PREFIX + "language").getOrDefault(PREFIX + "language", "en");
    }
}
