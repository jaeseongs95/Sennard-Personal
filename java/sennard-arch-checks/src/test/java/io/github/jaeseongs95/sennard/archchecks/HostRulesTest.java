// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.archchecks;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Rule q (HostRules): a host's name is written in the one file that defines the host. The product passes with the rule's zero (ArchitectureRulesTest); these fixtures
 * prove the rule goes red. Case table (sources; expected):
 * <pre>
 * q1  a definition   a file with new HostId("alpha", "alpha-stored") defines both names, whatever the file writes them again -> no violation of its own
 * q2  another file   a file that writes "alpha" or "alpha-stored" as a whole literal -> one violation each, at its line
 * q3  not a name     ".alpha", "alpha-plugin", "Alpha" and a literal that no HostId defines -> none
 * q4  a qualified    new some.pkg.HostId("gamma") defines "gamma" the same way
 * q5  a second       a name two files define is the first file's; the second file's literal is a violation (a name has one place)
 * </pre>
 */
class HostRulesTest {
    private static final String DEFINITION = "class AlphaHost { static final HostId ID = new HostId(\"alpha\", \"alpha-stored\"); String again = \"alpha\"; }";

    private static List<String> found(Map<String, String> sources) {
        return HostRules.find(JavaSources.parse(sources)).stream().map(violation -> violation.file() + ":" + violation.line()).toList();
    }

    @Test
    void aDefinitionMayWriteItsOwnNames() {
        assertEquals(List.of(), found(Map.of("AlphaHost.java", DEFINITION)));
        assertEquals(Set.of("alpha", "alpha-stored"), HostRules.definedNames(JavaSources.parse(Map.of("AlphaHost.java", DEFINITION))).keySet());
    }

    @Test
    void aNameWrittenAnywhereElseIsAViolationAtItsLine() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("AlphaHost.java", DEFINITION);
        sources.put("Common.java", "class Common {\n    String a = \"alpha\";\n    String b = \"alpha-stored\";\n    String c = \"other\";\n}\n");

        assertEquals(List.of("Common.java:2", "Common.java:3"), found(sources));
    }

    @Test
    void aTextThatOnlyContainsANameOrIsNotOneIsNoViolation() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("AlphaHost.java", DEFINITION);
        sources.put("Common.java", "class Common {\n    String a = \".alpha\";\n    String b = \"alpha-plugin\";\n    String c = \"Alpha\";\n    String d = \"beta\";\n}\n");

        assertEquals(List.of(), found(sources));
    }

    @Test
    void aQualifiedTypeDefinesAHostToo() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("GammaHost.java", "class GammaHost { static final some.pkg.HostId ID = new some.pkg.HostId(\"gamma\"); }");
        sources.put("Common.java", "class Common {\n    String a = \"gamma\";\n}\n");

        assertEquals(List.of("Common.java:2"), found(sources));
    }

    @Test
    void aNameTwoFilesDefineBelongsToTheFirstOne() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("AlphaHost.java", DEFINITION);
        sources.put("Copy.java", "class Copy { static final HostId ID = new HostId(\"alpha\"); }");

        assertEquals(List.of("Copy.java:1"), found(sources));
    }
}
