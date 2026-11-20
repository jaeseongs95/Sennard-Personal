// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.skills;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.domain.error.InputCode;
import io.github.jaeseongs95.sennard.domain.error.InvalidInputException;
import io.github.jaeseongs95.sennard.server.contract.CanonicalJson;
import io.github.jaeseongs95.sennard.service.files.FileFacts;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@code arguments} object of one call. A document argument is either the JSON value itself or {@code {"file": "<absolute path>"}};
 * a file argument reaches {@link FileFacts}, never a path opened here. The object is bound to a name-to-value map once; each value is converted to the type the check asks for.
 */
public final class SkillArguments {
    public static final int DOCUMENT_LIMIT = 16 * 1024 * 1024;
    private static final TypeReference<Map<String, JsonNode>> ARGUMENTS = new TypeReference<>() {
    };
    private static final String FILE = "file";

    /** The document argument's file form: exactly one member, {@code file}. */
    private record FileReference(String file) {
    }

    private final Map<String, JsonNode> values;
    private final FileFacts files;
    private final JsonMapper mapper;

    public SkillArguments(JsonNode raw, FileFacts files, JsonMapper mapper) {
        this.files = files;
        this.mapper = mapper;
        try {
            this.values = raw == null || raw.isNull() ? Map.of() : mapper.convertValue(raw, ARGUMENTS);
        } catch (JacksonException | IllegalArgumentException malformed) {
            throw new InvalidInputException(InputCode.SKILL_CHECK_MALFORMED_ARGUMENT, Map.of(), Map.of(), malformed);
        }
    }

    public FileFacts files() {
        return files;
    }

    public boolean has(String name) {
        return raw(name) != null;
    }

    private JsonNode raw(String name) {
        JsonNode value = values.get(name);
        return value == null || value.isNull() ? null : value;
    }

    private JsonNode required(String name) {
        JsonNode value = raw(name);
        if (value == null) {
            throw new InvalidInputException(InputCode.SKILL_CHECK_ARGUMENT_REQUIRED, SennardException.args("name", name));
        }
        return value;
    }

    private <T> T convert(String name, Class<T> type, InputCode mismatch) {
        JsonNode value = raw(name);
        if (value == null) {
            return null;
        }
        try {
            return mapper.treeToValue(value, type);
        } catch (JacksonException unreadable) {
            throw new InvalidInputException(mismatch, SennardException.args("name", name), Map.of(), unreadable);
        }
    }

    public String text(String name) {
        return convert(name, String.class, InputCode.SKILL_CHECK_ARGUMENT_MUST_BE_STRING);
    }

    public String requiredText(String name) {
        String value = text(name);
        if (value == null || value.isEmpty()) {
            throw new InvalidInputException(InputCode.SKILL_CHECK_ARGUMENT_REQUIRED, SennardException.args("name", name));
        }
        return value;
    }

    /** An integer argument, or {@code absent} when the call did not give it. */
    public long integer(String name, long absent) {
        Long value = convert(name, Long.class, InputCode.SKILL_CHECK_ARGUMENT_MUST_BE_INTEGER);
        return value == null ? absent : value;
    }

    public boolean flag(String name) {
        return Boolean.TRUE.equals(convert(name, Boolean.class, InputCode.SKILL_CHECK_ARGUMENT_MUST_BE_BOOLEAN));
    }

    public List<String> stringList(String name) {
        String[] items = convert(name, String[].class, InputCode.SKILL_CHECK_ARGUMENT_MUST_BE_ARRAY);
        return items == null ? List.of() : List.of(items);
    }

    /** An object of string values (names to texts), or an empty map when the call did not give it; a value that is not a string is refused, not turned into text. */
    public Map<String, String> stringMap(String name) {
        JsonNode value = raw(name);
        if (value == null) {
            return Map.of();
        }
        Map<String, JsonNode> entries;
        try {
            entries = mapper.convertValue(value, ARGUMENTS);
        } catch (JacksonException | IllegalArgumentException notAnObject) {
            throw new InvalidInputException(InputCode.SKILL_CHECK_ARGUMENT_MUST_BE_OBJECT_OF_STRINGS, SennardException.args("name", name), Map.of(), notAnObject);
        }
        Map<String, String> strings = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : entries.entrySet()) {
            if (entry.getValue() == null || !entry.getValue().isString()) {
                throw new InvalidInputException(InputCode.SKILL_CHECK_ARGUMENT_MUST_BE_OBJECT_OF_STRINGS, SennardException.args("name", name));
            }
            strings.put(entry.getKey(), entry.getValue().stringValue(""));
        }
        return strings;
    }

    /** A JSON document: inline, or read from an absolute path. */
    public JsonNode document(String name) {
        return document(name, DOCUMENT_LIMIT);
    }

    /** A JSON document whose file form uses the calling check's byte limit. */
    JsonNode document(String name, int maxBytes) {
        JsonNode value = required(name);
        FileReference reference = fileReference(value);
        return reference == null ? value : parse(readFile(reference, maxBytes), mapper);
    }

    /** Whether the document argument is given in its file form ({@code {"file": "<absolute path>"}}) rather than inline or as a path the check reads under a root. */
    public boolean isFileReference(String name) {
        return fileReference(required(name)) != null;
    }

    /** A document's bytes when the check binds them exactly: a file's own bytes, or the inline value's canonical text. */
    public byte[] documentBytes(String name) {
        JsonNode value = required(name);
        FileReference reference = fileReference(value);
        return reference == null ? CanonicalJson.of(value).getBytes(StandardCharsets.UTF_8) : readFile(reference, DOCUMENT_LIMIT);
    }

    private FileReference fileReference(JsonNode value) {
        if (!value.isObject() || value.size() != 1 || !value.propertyNames().contains(FILE)) {
            return null;
        }
        try {
            return mapper.treeToValue(value, FileReference.class);
        } catch (JacksonException notAString) {
            throw new InvalidInputException(InputCode.SKILL_CHECK_ARGUMENT_FILE_MUST_BE_STRING, Map.of(), Map.of(), notAString);
        }
    }

    private byte[] readFile(FileReference reference, int maxBytes) {
        if (reference.file() == null || reference.file().isEmpty()) {
            throw new InvalidInputException(InputCode.SKILL_CHECK_ARGUMENT_FILE_MUST_BE_STRING);
        }
        return files.read(reference.file(), maxBytes);
    }

    /** JSON read from the bytes themselves (Jackson decodes them): bytes that are not a JSON document, or whose encoding is malformed where Jackson reads them, are refused. */
    public static JsonNode parse(byte[] bytes, JsonMapper mapper) {
        try {
            return mapper.readTree(bytes);
        } catch (JacksonException e) {
            throw new InvalidInputException(InputCode.SKILL_CHECK_INVALID_JSON, Map.of(), Map.of(), e);
        }
    }
}
