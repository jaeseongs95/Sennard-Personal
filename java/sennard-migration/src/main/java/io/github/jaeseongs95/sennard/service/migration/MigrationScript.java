// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.migration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import io.github.jaeseongs95.sennard.domain.Digests;

/**
 * One migration script of the jar: {@code V<number>__<description>.sql} under a location such as {@code db/expand}. The number orders the scripts; the digest is the SHA-256 of the
 * file's bytes as they are in the jar, so it is the same on every database whatever type name {@code ${long_text}} becomes there.
 */
public record MigrationScript(long version, String file, byte[] bytes, String digest) {
    private static final Pattern NAME = Pattern.compile("^V(\\d+)__.+\\.sql$");

    /** The text of the script as the database takes it: the long text type of the database for {@code ${long_text}}, and the product's spelling of the phrases it spells otherwise ({@link ScriptDialect}). */
    String text(String longTextType, ScriptDialect dialect) {
        return dialect.translate(new String(bytes, StandardCharsets.UTF_8).replace("${long_text}", longTextType));
    }

    /** Every script under {@code location} (a folder of the classpath and its subfolders), in number order. A number used twice is a failure ({@link MigrationCode#FAILED}). */
    public static List<MigrationScript> load(String location) {
        List<MigrationScript> scripts = new ArrayList<>();
        Set<Long> numbers = new HashSet<>();
        try {
            for (Resource resource : new PathMatchingResourcePatternResolver().getResources("classpath*:" + location + "/**/V*__*.sql")) {
                String file = resource.getFilename();
                Matcher name = file == null ? null : NAME.matcher(file);
                if (name == null || !name.matches()) {
                    continue;
                }
                long version = Long.parseLong(name.group(1));
                if (!numbers.add(version)) {
                    throw new MigrationException(MigrationCode.FAILED, "Two migration scripts have the number " + version + " under " + location + ".");
                }
                byte[] bytes = resource.getContentAsByteArray();
                scripts.add(new MigrationScript(version, file, bytes, Digests.sha256Hex(bytes)));
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("The migration scripts under " + location + " cannot be read.", failure);
        }
        scripts.sort(Comparator.comparingLong(MigrationScript::version));
        return List.copyOf(scripts);
    }
}
