// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import io.github.jaeseongs95.sennard.server.contract.CanonicalJson;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The frozen vectors of the canonical JSON: the canonical text of every number and string form. Every digest and signature the server makes is over this text. */
class ConformanceVectorsTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final Path ROOT = Path.of(System.getProperty("sennard.repo.root"), "conformance");

    static Stream<Arguments> canonicalVectors() throws IOException {
        List<Arguments> cases = new ArrayList<>();
        for (JsonNode vector : MAPPER.readTree(Files.readString(ROOT.resolve("vectors/canonical-json.json"))).get("vectors")) {
            cases.add(Arguments.of(vector.get("id").asString(), vector.get("raw").asString(), vector.path("canonical").asString(null), vector.path("reject").asBoolean(false)));
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("canonicalVectors")
    void theCanonicalFormIsTheVectorsByteForByte(String id, String raw, String canonical, boolean reject) {
        JsonNode value = MAPPER.readTree(raw);
        if (reject) {
            assertThrows(IllegalArgumentException.class, () -> CanonicalJson.of(value), id);
        } else {
            assertEquals(canonical, CanonicalJson.of(value), id);
        }
    }
}
