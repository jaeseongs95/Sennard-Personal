// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.repotools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import tools.jackson.databind.JsonNode;

/**
 * The product depends on no vendor's model (AGS3-D-0149): the model and the reasoning effort are what the host reports, and the sets of names belong to the host. The
 * product's own contracts and shipped texts therefore hold no closed list of effort labels and name no vendor's model.
 */
class VendorNeutralProductTest {
    private static final Path ROOT = Path.of(System.getProperty("sennard.repo.root")).toAbsolutePath().normalize();
    private static final Repo REPO = new Repo(ROOT);
    /** The fields that carry a reasoning effort label, which a contract may type as text but not as a list of the labels one vendor has today. */
    private static final Set<String> EFFORT_FIELDS = Set.of("reasoningEffort", "reasoningEfforts", "minimumReasoningEffort", "effort");
    /**
     * A vendor's model family or tier name in a shipped text: a versioned or prefixed name, or a bare tier name (haiku, sonnet, opus, fable) that stands alone between
     * quote marks, as a value or a code span would hold it.
     */
    private static final Pattern VENDOR_MODEL = Pattern.compile(
            "(?i)\\bgemini-\\d|\\bgpt-\\d|\\bclaude-(?:opus|sonnet|haiku)\\b|\\bastra\\b|[\"'`](?:haiku|sonnet|opus|fable)[\"'`]");
    /**
     * Where the product's own shipped files are: its contracts, runtime data, skills, the user and contributor documents, the two READMEs, the plugin manifests and
     * the resources of its modules. Release notes are not listed: an earlier release's note may name what that release changed.
     */
    private static final List<String> SHIPPED = List.of("contracts", "runtime", "skills", "docs/user", "docs/contributing", "README.md", "README.ko.md", "plugin.json",
            ".codex-plugin/plugin.json", ".gemini-plugin/plugin.json", "claude-overlay/.claude-plugin/plugin.json");
    private static final Set<String> TEXT = Set.of(".json", ".md", ".properties", ".yaml", ".yml", ".txt");

    @Test
    void noContractTypesAReasoningEffortAsAClosedListOfLabels() throws IOException {
        List<String> closed = new ArrayList<>();
        try (Stream<Path> files = Files.list(ROOT.resolve("contracts"))) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".json")).sorted().toList()) {
                collectClosedEffort(REPO.json("contracts/" + file.getFileName()), file.getFileName().toString(), closed);
            }
        }
        assertEquals(List.of(), closed, "a reasoning effort is the host's own label: any non-empty text");
    }

    private static void collectClosedEffort(JsonNode node, String where, List<String> closed) {
        if (node.isObject()) {
            node.properties().forEach(entry -> {
                if (EFFORT_FIELDS.contains(entry.getKey()) && mentionsEnumeration(entry.getValue())) {
                    closed.add(where + " / " + entry.getKey());
                }
                collectClosedEffort(entry.getValue(), where + " / " + entry.getKey(), closed);
            });
        } else if (node.isArray()) {
            node.forEach(item -> collectClosedEffort(item, where, closed));
        }
    }

    /** An {@code enum} or {@code const} anywhere in the field's own definition (its items too), not following references. */
    private static boolean mentionsEnumeration(JsonNode definition) {
        if (definition.isObject()) {
            return definition.has("enum") || definition.has("const") || definition.properties().stream().anyMatch(entry -> mentionsEnumeration(entry.getValue()));
        }
        return definition.isArray() && definition.valueStream().anyMatch(VendorNeutralProductTest::mentionsEnumeration);
    }

    @Test
    void theSharedModelPolicyContractAcceptsAnyNonEmptyEffortLabel() {
        JsonNode effort = REPO.json("contracts/model-policy.v1.schema.json").path("$defs").path("reasoningEffort");
        assertEquals("string", effort.path("type").asString(), effort.toString());
        assertEquals(1, effort.path("minLength").asInt(), effort.toString());
        assertFalse(effort.has("enum"), effort.toString());
    }

    @Test
    void noShippedTextOrTrainingInputNamesAVendorsModel() throws IOException {
        List<Path> roots = new ArrayList<>();
        SHIPPED.forEach(directory -> roots.add(ROOT.resolve(directory)));
        try (Stream<Path> modules = Files.list(ROOT.resolve("java"))) {
            modules.map(module -> module.resolve("src/main/resources")).filter(Files::isDirectory).forEach(roots::add);
        }
        List<String> found = new ArrayList<>();
        for (Path start : roots) {
            if (!Files.exists(start)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(start)) {
                for (Path file : files.filter(Files::isRegularFile).filter(VendorNeutralProductTest::isText).sorted().toList()) {
                    String text = Files.readString(file, StandardCharsets.UTF_8);
                    var match = VENDOR_MODEL.matcher(text);
                    if (match.find()) {
                        found.add(ROOT.relativize(file).toString().replace('\\', '/') + ": " + match.group());
                    }
                }
            } catch (UncheckedIOException unreadable) {
                throw unreadable.getCause();
            }
        }
        assertTrue(found.isEmpty(), "a shipped file names a vendor's model: " + found);
    }

    /** The pattern itself: a bare tier name between quote marks is refused, in a value, a single-quoted string and a code span, in any case. */
    @ParameterizedTest
    @ValueSource(strings = {"{\"model\": \"opus\"}", "model = 'sonnet'", "use `haiku` for this", "\"Fable\"", "claude-opus", "gpt-5", "gemini-2"})
    void theVendorModelPatternRefusesABareQuotedTierName(String text) {
        assertTrue(VENDOR_MODEL.matcher(text).find(), text);
    }

    /** The other side: prose that holds the same words without naming a model by itself stays allowed. */
    @ParameterizedTest
    @ValueSource(strings = {"an opus of many parts", "a sonnet has fourteen lines", "a fable about haiku", "\"opus magnum\"", "\"the model\""})
    void theVendorModelPatternAllowsTheWordsInOrdinaryProse(String text) {
        assertFalse(VENDOR_MODEL.matcher(text).find(), text);
    }

    private static boolean isText(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 && TEXT.contains(name.substring(dot));
    }
}
