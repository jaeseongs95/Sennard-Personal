// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import io.github.jaeseongs95.sennard.app.message.CatalogMessageSource;
import io.github.jaeseongs95.sennard.domain.jvm.JvmPresets;
import io.github.jaeseongs95.sennard.domain.jvm.MemoryEstimate;
import tools.jackson.databind.json.JsonMapper;

/**
 * The wording of the memory estimate the admin page shows: the catalog's own {@code admin.jvm.estimate} (domain {@code admin}, English and Korean),
 * rendered by the application's message source with the arguments {@code AdminPageData} gives: the estimated size, and the JVM's reading in GB with one decimal.
 *
 * <pre>
 * e1  en   a 29.7 GB reading: "Estimated memory 32GB (calculated from the 29.7GB capacity confirmed by the JVM. It may differ from the RAM actually installed.)"
 * e2  ko   the same in Korean
 * e3  one decimal for the reading whatever the value (8 GB shows 8.0GB), no space left where the number goes
 * e4  de   the reading's separator is the request language's (29,7)
 * </pre>
 */
class JvmEstimatePatternTest {
    private static final CatalogMessageSource SOURCE = new CatalogMessageSource(new PathMatchingResourcePatternResolver(), JsonMapper.builder().build());

    private static String render(double gigabytes, Locale locale) {
        MemoryEstimate estimate = JvmPresets.estimate((long) (gigabytes * (1L << 30)));
        return SOURCE.getMessage("admin.jvm.estimate", new Object[] {AdminPageData.estimateArguments(estimate, locale)}, locale);
    }

    @Test
    void theEstimateIsSaidInEnglishAndInKorean() {
        assertEquals("Estimated memory 32GB (calculated from the 29.7GB capacity confirmed by the JVM. It may differ from the RAM actually installed.)", render(29.7, Locale.ENGLISH));
        assertEquals("추정 메모리 32GB (JVM에서 확인된 용량 29.7GB 기준으로 산정되었습니다. 실제 장착된 RAM 용량과 다를 수 있습니다.)", render(29.7, Locale.KOREAN));
    }

    @Test
    void theReadingHasOneDecimalWhateverItsValue() {
        assertEquals("Estimated memory 8GB (calculated from the 8.0GB capacity confirmed by the JVM. It may differ from the RAM actually installed.)", render(8, Locale.ENGLISH));
        assertEquals("Estimated memory 256GB (calculated from the 200.0GB capacity confirmed by the JVM. It may differ from the RAM actually installed.)", render(200, Locale.ENGLISH));
    }

    @Test
    void theReadingUsesTheRequestLanguagesDecimalSeparator() {
        assertEquals("29,7", AdminPageData.estimateArguments(JvmPresets.estimate((long) (29.7 * (1L << 30))), Locale.GERMAN).get("observed"));
    }
}
