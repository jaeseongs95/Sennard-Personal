// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.skills;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import com.networknt.schema.path.PathType;

import io.github.jaeseongs95.sennard.domain.error.InputCode;
import io.github.jaeseongs95.sennard.domain.error.InvalidInputException;
import io.github.jaeseongs95.sennard.server.contract.SchemaLocale;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every packaged contract, compiled once and resolved by its {@code $id} or by the path a relative {@code $ref} names, so a schema can
 * refer to a sibling without the validator ever opening a file. The contracts' patterns run on the schema library's default regular expression
 * engine ({@code java.util.regex}); a digest ending in a line feed is refused (ConformanceLocaleRegexServerTest).
 */
@Component
public class SkillSchemas {
    /** The only member of a schema this class reads itself: the address it is registered under. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Header(@JsonProperty("$id") String id) {
    }

    private final SkillAssets assets;
    private final JsonMapper mapper;
    private final SchemaRegistry registry;
    private final Map<String, String> ids = new HashMap<>();

    public SkillSchemas(SkillAssets assets, JsonMapper mapper) {
        this.assets = assets;
        this.mapper = mapper;
        Map<String, String> byId = new HashMap<>();
        Map<String, String> schemas = assets.matching("**/*.schema.json");
        Map<String, Set<String>> bases = new HashMap<>();
        schemas.forEach((location, text) -> {
            String id = mapper.readValue(text, Header.class).id();
            if (id != null) {
                ids.put(location, id);
                byId.putIfAbsent(id, text);
                String tree = tree(location);
                String within = location.substring(tree.length());
                if (id.endsWith(within)) {
                    bases.computeIfAbsent(tree, ignored -> new LinkedHashSet<>()).add(id.substring(0, id.length() - within.length()));
                }
            }
        });
        // A sibling named by a relative $ref may carry a different $id of its own, so register it under the base the tree's other schemas use.
        schemas.forEach((location, text) -> {
            String tree = tree(location);
            for (String base : bases.getOrDefault(tree, Set.of())) {
                byId.putIfAbsent(base + location.substring(tree.length()), text);
            }
        });
        SchemaRegistryConfig config = SchemaRegistryConfig.builder().formatAssertionsEnabled(true).pathType(PathType.JSON_POINTER).build();
        this.registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12, builder -> builder.schemaRegistryConfig(config).schemas(byId));
    }

    /** {@code skills/<name>/} or {@code contracts/}, the unit that shares one base URI. */
    private static String tree(String location) {
        Path path = Path.of(location);
        int depth = path.startsWith("skills") ? 2 : 1;
        if (path.getNameCount() <= depth) {
            return "";
        }
        return StreamSupport.stream(path.subpath(0, depth).spliterator(), false).map(Path::toString).collect(Collectors.joining("/", "", "/"));
    }

    /** A violation in the shape the checks report: the instance pointer, the keyword, and the validator's message. */
    public record Violation(String instancePath, String keyword, String message) {
    }

    public List<Violation> violations(String location, JsonNode value) {
        String id = ids.get(location);
        if (id == null) {
            throw new IllegalArgumentException("No packaged contract at " + location);
        }
        Schema schema = registry.getSchema(SchemaLocation.of(id));
        List<Violation> found = new ArrayList<>();
        for (Error error : schema.validate(value == null ? mapper.nullNode() : value, SchemaLocale.IN_ENGLISH)) {
            found.add(new Violation(error.getInstanceLocation().toString(), error.getKeyword(), error.getMessage()));
        }
        return found;
    }

    /** Whether the value conforms. */
    public boolean valid(String location, JsonNode value) {
        return violations(location, value).isEmpty();
    }

    /**
     * A document that conforms to its contract, bound to the record that describes it. The schema is the gate (an unknown or missing member is a violation); a value the
     * record still cannot hold is a refused input, never a server fault.
     */
    public <T> T bind(JsonNode document, Class<T> type) {
        try {
            return mapper.treeToValue(document, type);
        } catch (JacksonException mismatch) {
            throw new InvalidInputException(InputCode.SKILL_CHECK_MALFORMED_ARGUMENT, Map.of(), Map.of(), mismatch);
        }
    }

    /** The {@code /path message} form a report's error list uses. */
    public static List<String> texts(List<Violation> violations) {
        return violations.stream().map(v -> (v.instancePath().isEmpty() ? "/" : v.instancePath()) + " " + v.message()).toList();
    }

    public boolean exists(String location) {
        return ids.containsKey(location);
    }

    public SkillAssets assets() {
        return assets;
    }

    public JsonMapper mapper() {
        return mapper;
    }
}
