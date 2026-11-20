// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.github.jaeseongs95.sennard.hookclient.InputFields;
import io.github.jaeseongs95.sennard.hookclient.InputFields.Field;
import io.github.jaeseongs95.sennard.server.hook.HostDefinition;
import io.github.jaeseongs95.sennard.server.hook.HostDefinitions;
import io.github.jaeseongs95.sennard.server.hook.host.HostInput;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * The hook client keeps of a host's input only what the server reads, by a list of fields that it has in a file of its own ({@code sennard-hook-input/<label>.fields} of the hook-client
 * module), because it cannot load the server's record for the host ({@link HostDefinition#inputType()}). The record is the one definition of what is read, so the file is held to it
 * here, line for line, and a host cannot be added without its file. Case table (target: {@link InputFields#of} for each {@link HostDefinition}; input: the record's components,
 * read by reflection; expected: the same list of fields, no more and no fewer):
 * <ol>
 * <li>every host definition the product has: a fields file with at least one field (a host without one fails, saying which file to add)</li>
 * <li>the fields of that file are the record's: a component is named by the {@code @JsonProperty} of its accessor if it has one, else snake_case when the record's type has
 * {@code @JsonNaming(SnakeCaseStrategy)}, else by its own name; a component marked {@link HostInput.Section} is a section of the fields of its own record type (one level
 * down, the path joined by {@code /}), one marked {@link HostInput.FirstText} is {@code first-text}, and every other (a text, marker, flag, count or a JSON tree) is the whole
 * member</li>
 * <li>every {@code .fields} file of the folder, read from the repository's sources, is a host definition's: no file is left behind by a host that was removed</li>
 * </ol>
 *
 * <p>What a deliberate omission shows: taking {@code session_id} out of {@code claude.fields} fails the host's case with
 * {@code claude: fields the record reads and the file lacks ==> expected: <[]> but was: <[session_id]>}; adding a line the record does not have fails with the "file names
 * fields the record does not read" message for it; a field that is a list of texts in the record and a whole member in the file (or the reverse) fails with both.
 */
class HostInputFieldsTest {
    private static final HostDefinitions DEFINITIONS = HostDefinitions.load();
    /** The folder is read from the repository's sources, not from the test class path, so that a file that is only in the sources is found. */
    private static final Path FOLDER = Path.of(System.getProperty("sennard.repo.root"), "java", "sennard-hook-client", "src", "main", "resources", "sennard-hook-input");
    private static final String SUFFIX = ".fields";
    private static final String FIRST_TEXT = " first-text";

    static Stream<String> labels() {
        return DEFINITIONS.all().stream().map(definition -> definition.host().label());
    }

    /** The lines of a fields list, one per field: its path, and {@code first-text} after it for a list of texts; sorted. */
    private static List<String> lines(Map<String, Field> members, String parent) {
        List<String> lines = new ArrayList<>();
        members.forEach((name, field) -> {
            String path = parent + name;
            switch (field) {
                case Field.Value value -> lines.add(path);
                case Field.FirstText first -> lines.add(path + FIRST_TEXT);
                case Field.Section section -> lines.addAll(lines(section.members(), path + "/"));
            }
        });
        return lines.stream().sorted().toList();
    }

    /** The name a component has in the host's JSON: the rule of the records of the hosts, written out here and not asked of Jackson. */
    private static String nameOf(Class<?> owner, RecordComponent component) {
        JsonProperty named = component.getAccessor().getAnnotation(JsonProperty.class);
        if (named != null && !named.value().isEmpty()) {
            return named.value();
        }
        JsonNaming naming = owner.getAnnotation(JsonNaming.class);
        if (naming != null) {
            assertEquals(PropertyNamingStrategies.SnakeCaseStrategy.class, naming.value(), owner.getName() + " names its components by a strategy this test does not know");
            return snakeCase(component.getName());
        }
        return component.getName();
    }

    /** camelCase to snake_case: an underscore before each capital letter that is not the first character, and the letter in lower case. */
    private static String snakeCase(String name) {
        StringBuilder snake = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char letter = name.charAt(i);
            if (Character.isUpperCase(letter)) {
                if (i > 0) {
                    snake.append('_');
                }
                snake.append(Character.toLowerCase(letter));
            } else {
                snake.append(letter);
            }
        }
        return snake.toString();
    }

    /** The fields of a record as the fields file of its host must list them: the lines of {@link #lines}, derived from the components. */
    private static List<String> derived(Class<?> type, String parent) {
        assertTrue(type.isRecord(), type.getName() + " is a host's input record or a section of one");
        List<String> lines = new ArrayList<>();
        for (RecordComponent component : type.getRecordComponents()) {
            String path = parent + nameOf(type, component);
            if (component.isAnnotationPresent(HostInput.Section.class)) {
                lines.addAll(derived(component.getType(), path + "/"));
            } else if (component.isAnnotationPresent(HostInput.FirstText.class)) {
                lines.add(path + FIRST_TEXT);
            } else {
                lines.add(path);
            }
        }
        return lines.stream().sorted().toList();
    }

    private static List<String> minus(List<String> from, List<String> taken) {
        List<String> rest = new ArrayList<>(from);
        rest.removeAll(taken);
        return rest;
    }

    @ParameterizedTest
    @MethodSource("labels")
    void theFieldsFileOfAHostIsTheFieldsItsRecordReads(String label) throws IOException {
        HostDefinition definition = DEFINITIONS.ofLabel(label).orElseThrow();
        InputFields fields = InputFields.of(label).orElseThrow(() -> new AssertionError(
                "호스트를 추가하면 java/sennard-hook-client/src/main/resources/sennard-hook-input/" + label + ".fields 가 필요합니다 (host " + label + ", record " + definition.inputType().getName() + ")"));
        assertFalse(fields.members().isEmpty(), label + SUFFIX + " names no field");

        List<String> actual = lines(fields.members(), "");
        List<String> expected = derived(definition.inputType(), "");

        assertEquals(List.of(), minus(expected, actual), label + ": fields the record reads and the file lacks");
        assertEquals(List.of(), minus(actual, expected), label + ": fields the file names and the record does not read");
        assertEquals(expected, actual, label + ": the file is the record's, line for line");
    }

    @Test
    void everyFieldsFileOfTheFolderIsAHostDefinitionsAndEveryDefinitionHasOne() throws IOException {
        Set<String> files;
        try (Stream<Path> listed = Files.list(FOLDER)) {
            files = listed.map(path -> path.getFileName().toString()).filter(name -> name.endsWith(SUFFIX)).map(name -> name.substring(0, name.length() - SUFFIX.length()))
                    .collect(Collectors.toCollection(TreeSet::new));
        }
        Set<String> hosts = labels().collect(Collectors.toCollection(TreeSet::new));

        assertTrue(hosts.size() >= 4, "the host definitions found: " + hosts);
        assertEquals(hosts, files, "a fields file for every host, and a host for every fields file, in " + FOLDER);
    }
}
