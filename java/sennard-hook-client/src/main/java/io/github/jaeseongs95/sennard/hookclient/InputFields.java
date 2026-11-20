// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.hookclient;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The fields of a host's hook input that the server reads, by their names in the host's JSON: what the client sends of a hook's input and drops the rest
 * ({@link InputReducer}). A host that hands the command its whole input on stdin (Gemini CLI's {@code BeforeModel} is the model request with the conversation so far,
 * megabytes) would otherwise send all of it for the one field the server reads of it.
 *
 * <p>The server's record for a host ({@code HostDefinition.inputType()}) is the one definition of what is read; the client has no way to load it (it is in the
 * server, with Spring and Jackson), so each host has a file in this module, {@code sennard-hook-input/<label>.fields}, and a test of the server's module holds every file to the
 * record it describes, field for field, and every host definition to a file. A host is added with its adapter, its definition and that file; the test fails until it is.
 *
 * <p>A file has one field per line: its path, the member names from the input's top object separated by {@code /}, and for a list of texts {@code first-text} after
 * it. A line {@code llm_request/model} keeps that member of that object and nothing else of it; {@code session_id} keeps the member whole. Blank lines and lines starting
 * with {@code #} are left out.
 */
public final class InputFields {
    private static final String FOLDER = "/sennard-hook-input/";
    private static final String SUFFIX = ".fields";
    private static final String FIRST_TEXT = "first-text";
    private static final String SEPARATOR = "/";

    /** What is kept of a member of the host's input. */
    public sealed interface Field {
        /** The member whole, whatever it holds. */
        record Value() implements Field {
        }

        /** The members of an object that are kept; anything else in its place is dropped. */
        record Section(Map<String, Field> members) implements Field {
            public Section {
                members = Map.copyOf(members);
            }
        }

        /** The first element of a list, when it is a text, written back as a list of that one text. */
        record FirstText() implements Field {
        }
    }

    private final Map<String, Field> members;

    private InputFields(Map<String, Field> members) {
        this.members = Map.copyOf(members);
    }

    /** The members of the input's top object that are kept. */
    public Map<String, Field> members() {
        return members;
    }

    /**
     * @return the fields of the host's hooks, or empty for a label that has no file: a label of no host this client knows, whose input is sent as it came
     * @throws IOException when the file cannot be read
     */
    public static Optional<InputFields> of(String label) throws IOException {
        // A label is a path segment of the server's endpoint; one that could name another resource has no file.
        if (label.isEmpty() || !label.chars().allMatch(letter -> Character.isLetterOrDigit(letter) || letter == '-' || letter == '_')) {
            return Optional.empty();
        }
        try (InputStream file = InputFields.class.getResourceAsStream(FOLDER + label + SUFFIX)) {
            return file == null ? Optional.empty() : Optional.of(parse(new String(file.readAllBytes(), StandardCharsets.UTF_8)));
        }
    }

    /** @throws IllegalArgumentException when a line is not a field, or a field is both a whole member and a section */
    public static InputFields parse(String text) {
        Node root = new Node();
        for (String line : text.lines().toList()) {
            String stripped = line.strip();
            if (stripped.isEmpty() || stripped.startsWith("#")) {
                continue;
            }
            String[] words = stripped.split("\\s+");
            if (words.length > 2 || words.length == 2 && !FIRST_TEXT.equals(words[1])) {
                throw new IllegalArgumentException("A line of a fields file is a path and, for a list of texts, " + FIRST_TEXT + ": " + line);
            }
            Node node = root;
            for (String segment : words[0].split(SEPARATOR, -1)) {
                if (segment.isEmpty() || !segment.chars().allMatch(letter -> Character.isLetterOrDigit(letter) || letter == '_')) {
                    throw new IllegalArgumentException("A path of a fields file is names of letters, digits and underscores between slashes: " + line);
                }
                node = node.children.computeIfAbsent(segment, name -> new Node());
            }
            node.leaf = true;
            node.firstText = words.length == 2;
        }
        return new InputFields(root.freezeMembers());
    }

    /** A field while the file is read: a leaf, a list of texts, or the parent of other fields. */
    private static final class Node {
        private final Map<String, Node> children = new LinkedHashMap<>();
        private boolean leaf;
        private boolean firstText;

        Map<String, Field> freezeMembers() {
            Map<String, Field> frozen = new LinkedHashMap<>();
            children.forEach((name, child) -> frozen.put(name, child.freeze(name)));
            return frozen;
        }

        private Field freeze(String name) {
            if (leaf && !children.isEmpty()) {
                throw new IllegalArgumentException("A field of a fields file is a whole member or a section, not both: " + name);
            }
            if (!children.isEmpty()) {
                return new Field.Section(freezeMembers());
            }
            return firstText ? new Field.FirstText() : new Field.Value();
        }
    }
}
