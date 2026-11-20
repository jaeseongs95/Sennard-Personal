// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;

import tools.jackson.databind.json.JsonMapper;

/**
 * What a JSON Schema validator says about a value that does not fit is English whatever the language of the JVM ({@link SchemaLocale}): the details of the refusals of one server are
 * read by a model, and the same refusal said "상수 값 '1.0.0'이어야 합니다" from one tool and "must be" from another. Cases planned before the code:
 * <ol>
 * <li>a missing required member and a value that is not the constant are each named in English, with the JVM's default language Korean;</li>
 * <li>the sentence names the member that is missing, so a caller can mend that member alone.</li>
 * </ol>
 */
class SchemaLocaleTest {
    private static final String CONTRACT = """
            {"type":"object","required":["schemaVersion","rootId"],"properties":{"schemaVersion":{"const":"1.0.0"},"rootId":{"type":"string"}}}""";

    @Test
    void theValidatorSpeaksEnglishWhateverTheDefaultLanguageOfTheJvm() throws Exception {
        JsonMapper mapper = JsonMapper.builder().build();
        Schema schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(CONTRACT);
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(Locale.KOREAN);
            List<Error> missing = schema.validate(mapper.readTree("{\"rootId\":\"r\"}"), SchemaLocale.IN_ENGLISH);
            assertEquals(1, missing.size(), missing.toString());
            assertTrue(missing.get(0).getMessage().contains("schemaVersion"), "names the member that is missing: " + missing.get(0).getMessage());
            List<Error> wrong = schema.validate(mapper.readTree("{\"schemaVersion\":\"2\",\"rootId\":\"r\"}"), SchemaLocale.IN_ENGLISH);
            assertEquals(1, wrong.size(), wrong.toString());
            for (Error error : List.of(missing.get(0), wrong.get(0))) {
                assertFalse(error.getMessage().chars().anyMatch(letter -> letter >= 0xAC00 && letter <= 0xD7A3), "English, not the language of the machine: " + error.getMessage());
            }
        } finally {
            Locale.setDefault(before);
        }
    }
}
