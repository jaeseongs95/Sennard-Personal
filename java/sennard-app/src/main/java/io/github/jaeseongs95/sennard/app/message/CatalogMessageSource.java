// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.message;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.text.MessageFormat;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.context.MessageSource;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.context.NoSuchMessageException;
import org.springframework.core.io.Resource;
import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one message source of Sennard: Spring's {@link MessageSource} over the repository's catalogs, {@code locales/<language>/*.json} (one flat key to
 * {@link CatalogPattern} object per file; English is the source, Korean the translation). Every sentence the product shows or sends is made here, through a
 * {@code MessageSourceAccessor} over this bean; nothing else formats a catalog sentence.
 *
 * <p>Arguments: a single {@code Map} is the named arguments of the pattern (what {@code SennardException.arguments()} is); anything else is positional, named
 * {@code 0}, {@code 1}, and so on. An argument the map lacks is written as {@code {name}}, so a missing value is seen.
 *
 * <p>A sentence that changes with a value (what ICU wrote as {@code select} or {@code plural}) has one catalog key per wording,
 * {@code <key>#<argument>=<selector>}, and {@code {argument}} in the sentence stands for the wording the argument's value picks: an enum constant's name, a
 * string, or a whole number's digits; {@code other} when no key has that selector. The wording is itself a pattern and takes the same arguments.
 *
 * <p>A key that is not in the locale's catalog is looked up in English before it is reported missing, as a catalog under translation has gaps. A language with
 * no catalog is answered in English. The patterns are compiled when a catalog is first read, so a broken one stops the application at its first sentence of
 * that language.
 */
@Component(AbstractApplicationContext.MESSAGE_SOURCE_BEAN_NAME) // the bean name the context looks its message source up by
public final class CatalogMessageSource implements MessageSource {
    private static final Locale SOURCE = Locale.ENGLISH;
    private static final String OTHER = "other";

    private final ResourcePatternResolver resources;
    private final JsonMapper mapper;
    private final Map<String, Map<String, Sentence>> catalogs = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> rawCatalogs = new ConcurrentHashMap<>();

    public CatalogMessageSource(ResourcePatternResolver resources, JsonMapper mapper) {
        this.resources = resources;
        this.mapper = mapper;
    }

    @Override
    public String getMessage(String code, Object[] args, String defaultMessage, Locale locale) {
        Sentence sentence = sentence(code, locale);
        return sentence == null ? defaultMessage : format(languageOf(locale), sentence, parameters(args));
    }

    @Override
    public String getMessage(String code, Object[] args, Locale locale) {
        Sentence sentence = sentence(code, locale);
        if (sentence == null) {
            throw new NoSuchMessageException(code, locale);
        }
        return format(languageOf(locale), sentence, parameters(args));
    }

    @Override
    public String getMessage(MessageSourceResolvable resolvable, Locale locale) {
        String[] codes = resolvable.getCodes();
        for (String code : codes == null ? new String[0] : codes) {
            Sentence sentence = sentence(code, locale);
            if (sentence != null) {
                return format(languageOf(locale), sentence, parameters(resolvable.getArguments()));
            }
        }
        String defaultMessage = resolvable.getDefaultMessage();
        if (defaultMessage != null) {
            return defaultMessage;
        }
        throw new NoSuchMessageException(codes != null && codes.length > 0 ? codes[codes.length - 1] : "", locale);
    }

    /** One catalog key: its pattern and the wordings its arguments pick (argument name, then selector). */
    private record Sentence(String key, CatalogPattern pattern, Map<String, Map<String, CatalogPattern>> wordings) {
    }

    private Sentence sentence(String code, Locale locale) {
        String language = languageOf(locale);
        Sentence sentence = catalog(language).get(code);
        return sentence != null || language.equals(SOURCE.getLanguage()) ? sentence : catalog(SOURCE.getLanguage()).get(code);
    }

    /** The language a locale is answered in: its own when it has a catalog, English when it has none (numbers and all). */
    private String languageOf(Locale locale) {
        String language = locale == null || locale.getLanguage().isEmpty() ? SOURCE.getLanguage() : locale.getLanguage();
        return catalog(language).isEmpty() ? SOURCE.getLanguage() : language;
    }

    private Map<String, Sentence> catalog(String language) {
        return catalogs.computeIfAbsent(language, this::load);
    }

    /**
     * The patterns of the catalog that answers {@code locale}, as written (not formatted), for the keys that start with {@code prefix}. A key the language lacks is
     * taken from English, as {@link #getMessage} does. For a client that formats the pattern itself, the admin page in a browser: the sentence is made where its
     * arguments are (a pattern with {@code {name}} and {@code {name,number}}, and the wordings {@code key#name=selector}).
     */
    public Map<String, String> patterns(Locale locale, String prefix) {
        String language = languageOf(locale);
        Map<String, String> result = new TreeMap<>();
        raw(SOURCE.getLanguage()).forEach((key, text) -> {
            if (key.startsWith(prefix)) {
                result.put(key, text);
            }
        });
        if (!language.equals(SOURCE.getLanguage())) {
            raw(language).forEach((key, text) -> {
                if (key.startsWith(prefix)) {
                    result.put(key, text);
                }
            });
        }
        return Collections.unmodifiableMap(result);
    }

    private Map<String, String> raw(String language) {
        return rawCatalogs.computeIfAbsent(language, this::read);
    }

    private Map<String, String> read(String language) {
        Map<String, String> patterns = new LinkedHashMap<>();
        try {
            // The build copies the catalogs without translations.lock.json (a record of the translation's digests, not wording).
            for (Resource file : resources.getResources("classpath*:locales/" + language + "/*.json")) {
                try (InputStream in = file.getInputStream()) {
                    JsonNode body = mapper.readTree(in);
                    body.properties().forEach(entry -> patterns.put(entry.getKey(), entry.getValue().asString()));
                }
            }
        } catch (IOException cause) {
            throw new IllegalStateException("The message catalog of " + language + " could not be read.", cause);
        }
        return patterns;
    }

    private Map<String, Sentence> load(String language) {
        Map<String, String> patterns = raw(language);
        Map<String, Map<String, Map<String, CatalogPattern>>> wordings = new HashMap<>();
        Map<String, Sentence> sentences = new HashMap<>();
        patterns.forEach((key, text) -> {
            int mark = key.indexOf('#');
            try {
                if (mark < 0) {
                    sentences.put(key, new Sentence(key, CatalogPattern.parse(text), null));
                    return;
                }
                String base = key.substring(0, mark);
                String selection = key.substring(mark + 1);
                int equals = selection.indexOf('=');
                if (equals <= 0 || selection.indexOf('#') >= 0) {
                    throw new IllegalArgumentException("A wording key is <key>#<argument>=<selector>.");
                }
                wordings.computeIfAbsent(base, ignored -> new LinkedHashMap<>()).computeIfAbsent(selection.substring(0, equals), ignored -> new LinkedHashMap<>())
                        .put(selection.substring(equals + 1), CatalogPattern.parse(text));
            } catch (IllegalArgumentException cause) {
                throw new IllegalStateException("The message " + key + " of " + language + " is not valid: " + cause.getMessage(), cause);
            }
        });
        wordings.forEach((base, byArgument) -> {
            Sentence main = sentences.get(base);
            if (main == null) {
                throw new IllegalStateException("The wordings of " + base + " in " + language + " have no sentence.");
            }
            byArgument.forEach((argument, bySelector) -> {
                if (!main.pattern().names().contains(argument) || !bySelector.containsKey(OTHER)) {
                    throw new IllegalStateException("The wordings of " + base + "#" + argument + " in " + language + " must be an argument of the sentence and have \"other\".");
                }
            });
            sentences.put(base, new Sentence(base, main.pattern(), byArgument));
        });
        return sentences;
    }

    private static Map<String, Object> parameters(Object[] args) {
        if (args != null && args.length == 1 && args[0] instanceof Map<?, ?> named) {
            Map<String, Object> parameters = new HashMap<>();
            named.forEach((name, value) -> parameters.put(String.valueOf(name), value));
            return parameters;
        }
        Map<String, Object> positional = new HashMap<>();
        for (int index = 0; args != null && index < args.length; index++) {
            positional.put(Integer.toString(index), args[index]);
        }
        return positional;
    }

    private String format(String language, Sentence sentence, Map<String, Object> parameters) {
        return format(language, sentence.key(), sentence.pattern(), sentence.wordings(), parameters);
    }

    private String format(String language, String key, CatalogPattern pattern, Map<String, Map<String, CatalogPattern>> wordings, Map<String, Object> parameters) {
        Object[] arguments = new Object[pattern.names().size()];
        for (int index = 0; index < arguments.length; index++) {
            String name = pattern.names().get(index);
            Map<String, CatalogPattern> choices = wordings == null ? null : wordings.get(name);
            if (choices != null) {
                CatalogPattern wording = choices.getOrDefault(selector(parameters.get(name)), choices.get(OTHER));
                arguments[index] = format(language, key, wording, null, parameters);
            } else if (parameters.containsKey(name)) {
                arguments[index] = parameters.get(name);
            } else if (pattern.numericNames().contains(name)) {
                throw new IllegalArgumentException("The number " + name + " of " + key + " is not given.");
            } else {
                arguments[index] = "{" + name + "}";
            }
        }
        MessageFormat format = formats.computeIfAbsent(new FormatKey(language, pattern), ignored -> pattern.format(Locale.forLanguageTag(language)));
        synchronized (format) { // MessageFormat is not safe for concurrent use.
            return format.format(arguments);
        }
    }

    // ceiling: one compiled format per language and pattern; the bound is languages x catalog keys, not callers or arguments.
    private final Map<FormatKey, MessageFormat> formats = new ConcurrentHashMap<>();

    private record FormatKey(String language, CatalogPattern pattern) {
    }

    /** The text a value picks a wording by. */
    private static String selector(Object value) {
        return switch (value) {
            case null -> OTHER;
            case Enum<?> constant -> constant.name();
            case Byte whole -> whole.toString();
            case Short whole -> whole.toString();
            case Integer whole -> whole.toString();
            case Long whole -> whole.toString();
            case Float fraction -> new BigDecimal(fraction.toString()).stripTrailingZeros().toPlainString();
            case Double fraction -> new BigDecimal(fraction.toString()).stripTrailingZeros().toPlainString();
            default -> value.toString();
        };
    }
}
