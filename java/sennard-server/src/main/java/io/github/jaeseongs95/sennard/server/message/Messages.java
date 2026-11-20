// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.message;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.context.support.MessageSourceAccessor;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.server.web.RequestLocale;

/**
 * The one place the server makes a sentence: a {@link MessageRef} through Spring's {@code MessageSourceAccessor} over the catalogs ({@code locales/}). Wording is
 * data, so a sentence is changed in the catalog and never in code. A key that is not in any catalog renders as the key, which tells the reader what is missing.
 *
 * <p>Two audiences, two locales. What the model reads (hook text, tool descriptions and results, errors, the workflow records) is English whatever the request's
 * language ({@link #MODEL_LOCALE}), because the model reads it and every session pays for its tokens. What a person reads is in the locale the request carries
 * ({@link #render(MessageRef, Locale)} with {@link RequestLocale#current()}, or {@link #forRequest}): English or Korean, English for any other language.
 */
@Component
public class Messages {
    /** The language of everything the model reads. */
    public static final String MODEL_LOCALE = "en";

    private final MessageSourceAccessor accessor;

    public Messages(MessageSourceAccessor accessor) {
        this.accessor = accessor;
    }

    /** The model's wording. */
    public String render(MessageRef reference) {
        return render(reference, Locale.forLanguageTag(MODEL_LOCALE));
    }

    public String render(MessageRef reference, String languageTag) {
        return render(reference, Locale.forLanguageTag(languageTag));
    }

    /** Nested references are rendered first, so a sentence that embeds another stays in the same locale. */
    public String render(MessageRef reference, Locale locale) {
        Map<String, Object> params = new HashMap<>();
        reference.params().forEach((name, value) -> params.put(name, value instanceof MessageRef nested ? render(nested, locale) : value));
        return accessor.getMessage(reference.key(), new Object[] {params}, reference.key(), locale);
    }

    /** The model's wording of a refusal: its catalog sentence with its own arguments, never the bare catalog key a report must not show in place of a sentence. */
    public String render(SennardException failure) {
        return render(MessageRef.of(failure.messageKey(), failure.arguments()));
    }

    /** The wording, for the person whose request is being answered, of a refusal: see {@link #render(SennardException)}. */
    public String forRequest(SennardException failure) {
        return forRequest(MessageRef.of(failure.messageKey(), failure.arguments()));
    }

    /** The wording for the person whose request is being answered. */
    public String forRequest(MessageRef reference) {
        return render(reference, RequestLocale.current());
    }

    public String forRequest(String key, Map<String, Object> params) {
        return forRequest(MessageRef.of(key, params));
    }

    public String text(String key) {
        return render(MessageRef.of(key));
    }

    /** The rendered lines, or null when there is nothing to show. */
    public String renderLines(List<MessageRef> lines) {
        if (lines == null || lines.isEmpty()) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        for (MessageRef line : lines) {
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append(render(line));
        }
        return text.toString();
    }
}
