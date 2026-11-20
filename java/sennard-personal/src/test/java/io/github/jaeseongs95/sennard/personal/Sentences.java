// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal;

import java.util.Locale;

import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import io.github.jaeseongs95.sennard.app.message.SennardMessageResolvable;
import io.github.jaeseongs95.sennard.app.message.CatalogMessageSource;
import io.github.jaeseongs95.sennard.domain.error.SennardException;
import tools.jackson.databind.json.JsonMapper;

/** The sentence the catalog makes of a failure, the way the CLI and the web boundary make it: the tests of a command read what it tells the user through this. */
public final class Sentences {
    private static final CatalogMessageSource SOURCE = new CatalogMessageSource(new PathMatchingResourcePatternResolver(), JsonMapper.builder().build());

    private Sentences() {
    }

    public static String english(SennardException failure) {
        return SOURCE.getMessage(SennardMessageResolvable.of(failure), Locale.ENGLISH);
    }

    public static String korean(SennardException failure) {
        return SOURCE.getMessage(SennardMessageResolvable.of(failure), Locale.KOREAN);
    }
}
