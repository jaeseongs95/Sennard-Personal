// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit.golden;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.jaeseongs95.sennard.serverit.GoldenHarness;
import tools.jackson.core.util.DefaultIndenter;
import tools.jackson.core.util.DefaultPrettyPrinter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The one place a golden answer is normalized, compared and written. The rules come from the case table {@code golden/CASES.md}: each row
 * names its case and, one by one, the JSON pointers whose value changes from run to run and the placeholder that stands for it. Nothing
 * else is ignored. With {@code -Dsennard.golden.update=true} the normalized answer is written as the new golden file; otherwise it is compared,
 * and a difference fails with the case and the JSON pointer of the first difference.
 */
final class Golden {
    static final boolean UPDATE = Boolean.getBoolean("sennard.golden.update");
    static final JsonMapper MAPPER = JsonMapper.builder().build();
    static final Path REPOSITORY = Path.of(System.getProperty("sennard.repo.root")).toAbsolutePath();
    static final Path DIRECTORY = REPOSITORY.resolve("java/sennard-personal/src/test/resources/golden");
    /** Pretty with LF on every OS, so a file written on Windows is the file Linux reads. */
    private static final ObjectWriter WRITER = MAPPER.writer().with(new DefaultPrettyPrinter().withObjectIndenter(new DefaultIndenter("  ", "\n")).withArrayIndenter(new DefaultIndenter("  ", "\n")));
    /** Where the envelope of a tool answer is: {@code content[0].text}, parsed. */
    private static final String ENVELOPE = "/body/result/content/0/text";
    private static final Pattern ROW = Pattern.compile("^\\|\\s*([LTXH]-[A-Za-z0-9_.-]+)\\s*\\|");
    private static final Pattern RULE = Pattern.compile("`(/[^`]*)`\\s*(?:~\\s*`([^`]+)`\\s*)?→\\s*`([<{][A-Z_]+[>}])`");

    /**
     * One normalization: the value at {@code pointer} ({@code *} is every element of an array), replaced whole by a {@code <PLACEHOLDER>},
     * or, with {@code within}, only the parts of the string that match it; a {@code {TOKEN}} replaces this run's value of that token inside the string.
     */
    record Rule(String pointer, Pattern within, String placeholder) {
    }

    /** A value of this run that a {@code {TOKEN}} stands for. A path is matched in its native and its slash form, case-insensitively, and the rest of that string then uses slashes. */
    record Token(String name, String value, boolean path) {
    }

    private static Map<String, List<Rule>> table;

    private Golden() {
    }

    /** The case table: every case ID and its rules, in the table's order. */
    static synchronized Map<String, List<Rule>> table() {
        if (table == null) {
            Map<String, List<Rule>> rows = new LinkedHashMap<>();
            for (String line : read(DIRECTORY.resolve("CASES.md")).split("\n")) {
                Matcher row = ROW.matcher(line);
                if (!row.find()) {
                    continue;
                }
                // | ID | target | input | what is fixed | normalization |  : cells[0] is the empty text before the first bar.
                String[] cells = line.split("\\|");
                if (cells.length != 6) {
                    throw new IllegalStateException("CASES.md row " + row.group(1) + " has " + (cells.length - 1) + " cells, not 5 (a bar inside a cell?)");
                }
                List<Rule> rules = new ArrayList<>();
                Matcher rule = RULE.matcher(cells[5]);
                while (rule.find()) {
                    rules.add(new Rule(prefixed(row.group(1), rule.group(1)), rule.group(2) == null ? null : Pattern.compile(rule.group(2)), rule.group(3)));
                }
                if (rows.put(row.group(1), rules) != null) {
                    throw new IllegalStateException("CASES.md lists " + row.group(1) + " twice");
                }
            }
            table = rows;
        }
        return table;
    }

    /** The table rows of this prefix are exactly the cases a test ran: a row without a case and a case without a row both fail. */
    static void assertTableCovers(String prefix, Collection<String> executed) {
        Set<String> listed = new TreeSet<>();
        table().keySet().stream().filter(id -> id.startsWith(prefix)).forEach(listed::add);
        assertEquals(listed, new TreeSet<>(executed), "CASES.md rows starting " + prefix + " and the cases the test runs");
    }

    /** A tool case's pointers are written from the envelope its answer carries; the rest of the pointer is the HTTP answer's own. */
    private static String prefixed(String caseId, String pointer) {
        boolean tool = caseId.startsWith("T-") || caseId.startsWith("X-");
        return tool && !pointer.startsWith("/body") ? ENVELOPE + pointer : pointer;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    private static Path file(String group, String caseId) {
        return DIRECTORY.resolve(group).resolve(caseId + ".json");
    }

    /** An HTTP answer as a golden tree: status, Content-Type, and the body (JSON when it parses, else its text). */
    static ObjectNode answer(GoldenHarness.Reply reply) {
        ObjectNode answer = MAPPER.createObjectNode();
        answer.put("status", reply.status());
        answer.put("contentType", reply.contentType());
        String text = new String(reply.body(), StandardCharsets.UTF_8);
        answer.set("body", parsedOrText(text));
        return answer;
    }

    static JsonNode parsedOrText(String text) {
        String trimmed = text.strip();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                return MAPPER.readTree(text);
            } catch (RuntimeException notJson) {
                return MAPPER.getNodeFactory().stringNode(text);
            }
        }
        return MAPPER.getNodeFactory().stringNode(text);
    }

    // ------------------------------------------------------------------------------------------------------- normalization

    static JsonNode normalize(String caseId, JsonNode actual, List<Token> tokens) {
        JsonNode copy = actual.deepCopy();
        for (Rule rule : table().getOrDefault(caseId, List.of())) {
            List<String> segments = segments(rule.pointer());
            apply(copy, segments, 0, rule, tokens);
        }
        return copy;
    }

    private static List<String> segments(String pointer) {
        List<String> segments = new ArrayList<>();
        for (String raw : pointer.substring(1).split("/", -1)) {
            segments.add(raw.replace("~1", "/").replace("~0", "~"));
        }
        return segments;
    }

    private static void apply(JsonNode node, List<String> segments, int at, Rule rule, List<Token> tokens) {
        String segment = segments.get(at);
        boolean last = at == segments.size() - 1;
        if (node instanceof ArrayNode array) {
            List<Integer> indexes = new ArrayList<>();
            if (segment.equals("*")) {
                for (int i = 0; i < array.size(); i++) {
                    indexes.add(i);
                }
            } else if (segment.matches("\\d+") && Integer.parseInt(segment) < array.size()) {
                indexes.add(Integer.parseInt(segment));
            }
            for (int index : indexes) {
                if (last) {
                    array.set(index, replaced(array.get(index), rule, tokens));
                } else {
                    apply(array.get(index), segments, at + 1, rule, tokens);
                }
            }
        } else if (node instanceof ObjectNode object && object.has(segment)) {
            if (last) {
                object.set(segment, replaced(object.get(segment), rule, tokens));
            } else {
                apply(object.get(segment), segments, at + 1, rule, tokens);
            }
        }
    }

    /** A value that does not have the form its placeholder names (a time that is not a time, a null) is left as it is, so the comparison shows it. */
    private static JsonNode replaced(JsonNode value, Rule rule, List<Token> tokens) {
        String placeholder = rule.placeholder();
        if (placeholder.startsWith("{")) {
            if (!value.isString()) {
                return value;
            }
            Token token = tokens.stream().filter(candidate -> candidate.name().equals(placeholder)).findFirst()
                    .orElseThrow(() -> new IllegalStateException("No run value for " + placeholder));
            return MAPPER.getNodeFactory().stringNode(substitute(value.asString(), token));
        }
        if (rule.within() != null) {
            return value.isString() ? MAPPER.getNodeFactory().stringNode(rule.within().matcher(value.asString()).replaceAll(Matcher.quoteReplacement(placeholder))) : value;
        }
        if (value.isNull() || value.isContainer()) {
            return value;
        }
        if (placeholder.equals("<TIME>") && !isTime(value)) {
            return value;
        }
        return MAPPER.getNodeFactory().stringNode(placeholder);
    }

    private static boolean isTime(JsonNode value) {
        if (!value.isString()) {
            return false;
        }
        try {
            OffsetDateTime.parse(value.asString());
            return true;
        } catch (DateTimeParseException notATime) {
            return false;
        }
    }

    private static String substitute(String text, Token token) {
        Set<String> forms = new LinkedHashSet<>(List.of(token.value(), token.value().replace('\\', '/')));
        String out = text;
        boolean found = false;
        for (String form : forms) {
            Matcher matcher = Pattern.compile(Pattern.quote(form), Pattern.CASE_INSENSITIVE).matcher(out);
            if (matcher.find()) {
                found = true;
                out = matcher.replaceAll(Matcher.quoteReplacement(token.name()));
            }
        }
        return found && token.path() ? out.replace('\\', '/') : out;
    }

    // ------------------------------------------------------------------------------------------------------- comparison

    /** Compares (or, in update mode, writes) one case's normalized answer. */
    static void check(String group, String caseId, JsonNode actual, List<Token> tokens) {
        JsonNode normalized = normalize(caseId, actual, tokens);
        Path file = file(group, caseId);
        if (UPDATE) {
            write(file, (WRITER.writeValueAsString(sorted(normalized)) + "\n").getBytes(StandardCharsets.UTF_8));
            return;
        }
        assertTrue(Files.isRegularFile(file), "case " + caseId + ": no golden file " + DIRECTORY.relativize(file) + " (write it with -Dsennard.golden.update=true)");
        JsonNode expected = MAPPER.readTree(read(file));
        String at = firstDifference(expected, normalized, "");
        if (at != null) {
            fail("case " + caseId + ": first difference at " + (at.isEmpty() ? "/" : at) + ": golden " + shown(expected.at(at)) + ", now " + shown(normalized.at(at)));
        }
    }

    /** Compares (or writes) the exact bytes of one answer. A difference names the first differing JSON pointer, or the byte offset when the trees are equal. */
    static void checkBytes(String group, String caseId, byte[] actual) {
        Path file = file(group, caseId);
        if (UPDATE) {
            write(file, actual);
            return;
        }
        assertTrue(Files.isRegularFile(file), "case " + caseId + ": no golden file " + DIRECTORY.relativize(file) + " (write it with -Dsennard.golden.update=true)");
        byte[] expected;
        try {
            expected = Files.readAllBytes(file);
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
        if (Arrays.equals(expected, actual)) {
            return;
        }
        JsonNode before = MAPPER.readTree(expected);
        JsonNode after = MAPPER.readTree(actual);
        String at = firstDifference(before, after, "");
        if (at != null) {
            fail("case " + caseId + ": first difference at " + (at.isEmpty() ? "/" : at) + ": golden " + shown(before.at(at)) + ", now " + shown(after.at(at)));
        }
        fail("case " + caseId + ": the same JSON in other bytes, first at byte " + Arrays.mismatch(expected, actual));
    }

    /**
     * The same tree with the members of every object in name order. The server builds some objects from {@code Map.of}, whose order changes
     * from one JVM to the next; a JSON object has no order, so the comparison goes by name and the file is written in one order.
     */
    private static JsonNode sorted(JsonNode node) {
        if (node instanceof ObjectNode object) {
            ObjectNode copy = MAPPER.createObjectNode();
            new TreeSet<>(object.propertyNames()).forEach(name -> copy.set(name, sorted(object.get(name))));
            return copy;
        }
        if (node instanceof ArrayNode array) {
            ArrayNode copy = MAPPER.createArrayNode();
            array.forEach(item -> copy.add(sorted(item)));
            return copy;
        }
        return node;
    }

    private static void write(Path file, byte[] bytes) {
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, bytes);
        } catch (IOException unwritable) {
            throw new UncheckedIOException(unwritable);
        }
    }

    private static String shown(JsonNode node) {
        String text = node.isMissingNode() ? "(absent)" : node.toString();
        return text.length() > 300 ? text.substring(0, 300) + "..." : text;
    }

    private static String escape(String key) {
        return key.replace("~", "~0").replace("/", "~1");
    }

    /** The JSON pointer of the first difference, or null when equal. An object's members are compared by name, an array's elements by position. */
    static String firstDifference(JsonNode expected, JsonNode actual, String at) {
        if (expected.getNodeType() != actual.getNodeType()) {
            return at;
        }
        if (expected instanceof ObjectNode object) {
            for (String name : object.propertyNames()) {
                if (!actual.has(name)) {
                    return at + "/" + escape(name);
                }
                String inner = firstDifference(object.get(name), actual.get(name), at + "/" + escape(name));
                if (inner != null) {
                    return inner;
                }
            }
            for (String name : actual.propertyNames()) {
                if (!object.has(name)) {
                    return at + "/" + escape(name);
                }
            }
            return null;
        }
        if (expected instanceof ArrayNode array) {
            int common = Math.min(array.size(), actual.size());
            for (int i = 0; i < common; i++) {
                String inner = firstDifference(array.get(i), actual.get(i), at + "/" + i);
                if (inner != null) {
                    return inner;
                }
            }
            return array.size() == actual.size() ? null : at + "/" + common;
        }
        return expected.equals(actual) ? null : at;
    }
}
