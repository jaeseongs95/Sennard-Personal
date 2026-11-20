// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.cli;

import java.util.Locale;
import java.util.Map;

import org.springframework.context.MessageSource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import io.github.jaeseongs95.sennard.app.message.SennardMessageResolvable;
import io.github.jaeseongs95.sennard.app.message.CatalogMessageSource;
import io.github.jaeseongs95.sennard.domain.error.SennardException;

import tools.jackson.databind.json.JsonMapper;

/**
 * The one place the CLI process turns a failure into the sentence of the catalog ({@code locales/<language>/cli.json}). The process has no Spring context to take the message
 * source from, so it is made here from the catalogs on the classpath, when the first sentence is asked for: a command that succeeds never reads them. The sentence is English:
 * a person or an agent reads it on a terminal and no language is asked for.
 */
final class CliSentences {
    private static final Locale LANGUAGE = Locale.ENGLISH;

    private final JsonMapper json;
    private MessageSource messages;

    CliSentences(JsonMapper json) {
        this.json = json;
    }

    /** What a failure tells: the catalog's sentence of an {@link SennardException}, the message of anything else. */
    String of(RuntimeException failure) {
        if (failure instanceof SennardException known) {
            return messages().getMessage(SennardMessageResolvable.of(known), LANGUAGE);
        }
        return failure.getMessage();
    }

    /** The catalog's sentence of a key that has no failure behind it (a notice of a command that succeeded), without arguments. */
    String of(String key) {
        return messages().getMessage(key, new Object[] {Map.of()}, LANGUAGE);
    }

    private MessageSource messages() {
        if (messages == null) {
            messages = new CatalogMessageSource(new PathMatchingResourcePatternResolver(), json);
        }
        return messages;
    }
}
