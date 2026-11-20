// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.message;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * One catalog pattern: {@link java.text.MessageFormat} syntax whose arguments are named, {@code {rootId}} or {@code {limit,number}}, as a sentence in a catalog is
 * read by a person, not by an index. {@code java.text.MessageFormat} numbers its arguments, so the names are replaced by their order of first appearance here and
 * the pattern then goes to {@code MessageFormat} unchanged: quoting (a doubled apostrophe, {@code '{'}) and the {@code number} format are the JDK's.
 *
 * <p>A choice between wordings (what ICU wrote as {@code select} or {@code plural}) is not in the pattern: it is the catalog's keys {@code key#name=selector}, see
 * {@link CatalogMessageSource}.
 */
public final class CatalogPattern {
    /** The two argument forms a catalog uses. */
    private enum Form {
        PLAIN("", ""), NUMBER("number", ",number");

        private final String word;
        private final String suffix;

        Form(String word, String suffix) {
            this.word = word;
            this.suffix = suffix;
        }
    }

    private final List<String> names;
    private final Set<String> numeric;
    private final String indexed;

    private CatalogPattern(List<String> names, Set<String> numeric, String indexed) {
        this.names = List.copyOf(names);
        this.numeric = Set.copyOf(numeric);
        this.indexed = indexed;
    }

    /** @throws IllegalArgumentException when the pattern is not one {@code MessageFormat} reads, or has an argument form other than {@code {name}} and {@code {name,number}} */
    public static CatalogPattern parse(String pattern) {
        List<String> names = new ArrayList<>();
        Set<String> numeric = new LinkedHashSet<>();
        StringBuilder indexed = new StringBuilder();
        boolean quoted = false;
        for (int at = 0; at < pattern.length(); at++) {
            char c = pattern.charAt(at);
            if (c == '\'') {
                if (!quoted && at + 1 < pattern.length() && pattern.charAt(at + 1) == '\'') {
                    indexed.append("''");
                    at++;
                } else {
                    quoted = !quoted;
                    indexed.append(c);
                }
            } else if (c == '{' && !quoted) {
                int end = pattern.indexOf('}', at);
                if (end < 0) {
                    throw new IllegalArgumentException("An argument is not closed in: " + pattern);
                }
                String argument = pattern.substring(at + 1, end);
                if (argument.indexOf('{') >= 0) {
                    throw new IllegalArgumentException("An argument inside an argument is not read (a choice of wording is a key, key#name=selector): " + pattern);
                }
                int comma = argument.indexOf(',');
                String name = (comma < 0 ? argument : argument.substring(0, comma)).trim();
                String word = comma < 0 ? "" : argument.substring(comma + 1).trim();
                Form form = Arrays.stream(Form.values()).filter(candidate -> candidate.word.equals(word)).findFirst().orElseThrow(() -> unreadArgument(argument, pattern));
                if (name.isEmpty()) {
                    throw unreadArgument(argument, pattern);
                }
                int index = names.indexOf(name);
                if (index < 0) {
                    index = names.size();
                    names.add(name);
                }
                if (form == Form.NUMBER) {
                    numeric.add(name);
                }
                indexed.append('{').append(index).append(form.suffix).append('}');
                at = end;
            } else {
                indexed.append(c);
            }
        }
        if (quoted) {
            throw new IllegalArgumentException("A quote is not closed in: " + pattern);
        }
        CatalogPattern parsed = new CatalogPattern(names, numeric, indexed.toString());
        parsed.format(Locale.ROOT); // the JDK's own check of what is left
        return parsed;
    }

    private static IllegalArgumentException unreadArgument(String argument, String pattern) {
        return new IllegalArgumentException("Only {name} and {name,number} are read, not {" + argument + "}: " + pattern);
    }

    /** The argument names in the order the compiled pattern numbers them. */
    public List<String> names() {
        return names;
    }

    /** The names written with the {@code number} format. */
    public Set<String> numericNames() {
        return numeric;
    }

    MessageFormat format(Locale locale) {
        return new MessageFormat(indexed, locale);
    }
}
