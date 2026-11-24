// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook.host;

import java.io.InputStream;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * What a reader of the requests of a transcript needs of a line that is too long to be held whole. A request line holds the request's body ({@code params}), which is the conversation
 * sent; a long conversation makes the line longer than what is read of the end of a file. The line is read once with a streaming parser that keeps only the small fields a reader relies
 * on and skips the rest without ever holding it: the line's own marks ({@link #ROOT}), {@code params.model}, {@code params.output_config.effort}, and of {@code message} its
 * {@code model} and the kind of each block of its {@code content} ({@link #BLOCK}). The digest has the shape of the line it stands for, so {@link ClaudeEntry} reads it as it reads a line.
 * A text of the line is not kept (a user's {@code content} that is a string is kept as an empty string, which is what makes it a prompt); what a reader needs that a digest does not
 * hold, such as the text of a prompt, it does not get for a line of this size.
 */
final class ClaudeLineDigest {
    private static final Logger LOG = LoggerFactory.getLogger(ClaudeLineDigest.class);
    private static final Set<String> ROOT = Set.of("type", "uuid", "parentUuid", "logicalParentUuid", "sessionId", "agentId", "isSidechain", "isMeta", "querySource", "timestamp", "effort", "promptId");
    private static final Set<String> BLOCK = Set.of("type", "id", "tool_use_id", "name");

    private ClaudeLineDigest() {
    }

    /** The digest of the one JSON object the stream begins with, or none when it is not a complete object (a line that is still being written, or is not JSON). */
    static Optional<JsonNode> of(JsonMapper mapper, InputStream line) {
        try (JsonParser parser = mapper.createParser(line)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                return Optional.empty();
            }
            ObjectNode digest = mapper.createObjectNode();
            while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
                String name = parser.currentName();
                JsonToken value = parser.nextToken();
                if (ROOT.contains(name) && value.isScalarValue()) {
                    scalar(parser, name, value, digest);
                } else if ("params".equals(name) && value == JsonToken.START_OBJECT) {
                    params(parser, digest.putObject("params"));
                } else if ("message".equals(name) && value == JsonToken.START_OBJECT) {
                    message(parser, digest.putObject("message"));
                } else {
                    parser.skipChildren();
                }
            }
            return parser.currentToken() == JsonToken.END_OBJECT ? Optional.of(digest) : Optional.empty();
        } catch (JacksonException notAnObject) {
            LOG.debug("a line of the transcript that is not a whole JSON object has no digest", notAnObject);
            return Optional.empty();
        }
    }

    private static void params(JsonParser parser, ObjectNode params) {
        while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
            String name = parser.currentName();
            JsonToken value = parser.nextToken();
            if ("model".equals(name) && value == JsonToken.VALUE_STRING) {
                params.put(name, parser.getString());
            } else if ("output_config".equals(name) && value == JsonToken.START_OBJECT) {
                ObjectNode outputConfig = params.putObject(name);
                while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
                    String key = parser.currentName();
                    JsonToken inner = parser.nextToken();
                    if ("effort".equals(key) && inner == JsonToken.VALUE_STRING) {
                        outputConfig.put(key, parser.getString());
                    } else {
                        parser.skipChildren();
                    }
                }
            } else {
                parser.skipChildren();
            }
        }
    }

    private static void message(JsonParser parser, ObjectNode message) {
        while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
            String name = parser.currentName();
            JsonToken value = parser.nextToken();
            if ("model".equals(name) && value == JsonToken.VALUE_STRING) {
                message.put(name, parser.getString());
            } else if ("content".equals(name) && value == JsonToken.VALUE_STRING) {
                message.put(name, "");
            } else if ("content".equals(name) && value == JsonToken.START_ARRAY) {
                var blocks = message.putArray(name);
                for (JsonToken element = parser.nextToken(); element != JsonToken.END_ARRAY && element != null; element = parser.nextToken()) {
                    if (element != JsonToken.START_OBJECT) {
                        parser.skipChildren();
                        continue;
                    }
                    ObjectNode block = blocks.addObject();
                    while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
                        String key = parser.currentName();
                        JsonToken inner = parser.nextToken();
                        if (BLOCK.contains(key) && inner == JsonToken.VALUE_STRING) {
                            block.put(key, parser.getString());
                        } else {
                            parser.skipChildren();
                        }
                    }
                }
            } else {
                parser.skipChildren();
            }
        }
    }

    private static void scalar(JsonParser parser, String name, JsonToken value, ObjectNode digest) {
        switch (value) {
            case VALUE_STRING -> digest.put(name, parser.getString());
            case VALUE_TRUE -> digest.put(name, true);
            case VALUE_FALSE -> digest.put(name, false);
            default -> {
                // A number or a null is no mark the reader relies on.
            }
        }
    }
}
