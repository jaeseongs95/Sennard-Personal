// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.wake;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.github.jaeseongs95.sennard.server.hook.host.CodexHost;
import io.github.jaeseongs95.sennard.server.hook.host.HostInput;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.deser.std.StdDeserializer;
import tools.jackson.databind.json.JsonMapper;

/** Reads the Codex wake setting and updates the flag when the installer migrates a 1.3 copy. */
public final class CodexQueueWakeSettings {
    public static final String OVERRIDE = "SENNARD_CODEX_QUEUE_WAKE";
    private static final String FILE_NAME = "session-messaging.json";
    private static final int LIMIT = 4096;
    private final JsonMapper mapper;
    private final ObjectReader reader;

    public CodexQueueWakeSettings(JsonMapper mapper) {
        this.mapper = mapper;
        reader = WakeJson.strictReader(mapper, QueueConfiguration.class);
    }

    boolean enabled(Map<String, String> environment) throws IOException {
        String folder = environment.get("PLUGIN_DATA");
        return folder == null || read(Path.of(folder)).map(Configuration::enabled).orElse(true);
    }

    public Optional<Configuration> read(Path folder) throws IOException {
        if (!folder.isAbsolute()) {
            throw new IOException("Expected an absolute plugin data path.");
        }
        Path file = folder.resolve(FILE_NAME);
        // A dangling link still occupies the configuration path. Treat an open failure as unreadable.
        if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        byte[] bytes;
        try (InputStream input = Files.newInputStream(file)) {
            bytes = input.readNBytes(LIMIT + 1);
        }
        if (bytes.length > LIMIT) {
            throw new IOException("Queue wake configuration exceeds its size limit.");
        }
        QueueConfiguration config = reader.readValue(bytes);
        if (config == null || !"1.0.0".equals(config.schemaVersion()) || config.codex() == null || config.codex().queueWake() == null) {
            throw new IOException("Invalid queue wake configuration.");
        }
        return Optional.of(new Configuration(config.codex().queueWake(), ByteBuffer.wrap(bytes)));
    }

    public void enable(Path folder, Configuration expected) throws IOException {
        Path file = folder.resolve(FILE_NAME);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Expected a regular Codex queue wake settings file: " + file);
        }
        byte[] contents = enableFlag(expected);
        Path temporary = Files.createTempFile(folder, ".sennard-queue-wake-", ".tmp");
        try {
            Files.copy(file, temporary, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS);
            if (!Files.isRegularFile(temporary, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Codex queue wake settings changed during setup: " + file);
            }
            Files.write(temporary, contents);
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || read(folder).filter(expected::equals).isEmpty()) {
                throw new IOException("Codex queue wake settings changed during setup: " + file);
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private byte[] enableFlag(Configuration configuration) throws IOException {
        ByteBuffer original = configuration.bytes();
        JsonEncoding encoding = JsonEncoding.of(original.getInt(0));
        original.position(encoding.bomLength());
        String text = encoding.charset().newDecoder().decode(original.slice()).toString();
        try (JsonParser parser = mapper.createParser(text)) {
            if (parser.nextToken() != JsonToken.START_OBJECT || findField(parser, CodexHost.ID.label()) != JsonToken.START_OBJECT
                    || findField(parser, "queueWake") != JsonToken.VALUE_FALSE) {
                throw new IOException("Expected an existing Codex queueWake set to false.");
            }
            int start = Math.toIntExact(parser.currentTokenLocation().getCharOffset());
            int end = Math.toIntExact(parser.currentLocation().getCharOffset());
            byte[] enabled = (text.substring(0, start) + "true" + text.substring(end)).getBytes(encoding.charset());
            ByteBuffer result = ByteBuffer.allocate(encoding.bomLength() + enabled.length);
            result.put(original.position(0).limit(encoding.bomLength()));
            return result.put(enabled).array();
        }
    }

    private static JsonToken findField(JsonParser parser, String key) {
        while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
            String current = parser.currentName();
            JsonToken value = parser.nextToken();
            if (key.equals(current)) {
                return value;
            }
            parser.skipChildren();
        }
        return null;
    }

    /** The bounded snapshot is compared again after confirmation. */
    public record Configuration(boolean enabled, ByteBuffer bytes) {
        public Configuration {
            bytes = bytes.asReadOnlyBuffer();
        }

        @Override
        public ByteBuffer bytes() {
            return bytes.asReadOnlyBuffer();
        }
    }

    private record JsonEncoding(Charset charset, int bomLength) {
        static JsonEncoding of(int prefix) {
            // JSON starts with an ASCII token or whitespace, so its zero padding also specifies the byte order without a BOM.
            if (prefix == 0x0000feff || (prefix & 0xffffff00) == 0) {
                return new JsonEncoding(Charset.forName("UTF-32BE"), prefix == 0x0000feff ? 4 : 0);
            }
            if (prefix == 0xfffe0000 || (prefix & 0x00ffffff) == 0) {
                return new JsonEncoding(Charset.forName("UTF-32LE"), prefix == 0xfffe0000 ? 4 : 0);
            }
            if (prefix >>> 16 == 0xfeff || (prefix & 0xff00ff00) == 0) {
                return new JsonEncoding(StandardCharsets.UTF_16BE, prefix >>> 16 == 0xfeff ? 2 : 0);
            }
            if (prefix >>> 16 == 0xfffe || (prefix & 0x00ff00ff) == 0) {
                return new JsonEncoding(StandardCharsets.UTF_16LE, prefix >>> 16 == 0xfffe ? 2 : 0);
            }
            return new JsonEncoding(StandardCharsets.UTF_8, prefix >>> 8 == 0xefbbbf ? 3 : 0);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record QueueConfiguration(@HostInput.Text String schemaVersion, CodexQueue codex) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CodexQueue(@JsonDeserialize(using = QueueFlag.class) Boolean queueWake) {
    }

    /** Configuration permits the JSON boolean itself, with no text or numeric coercion. */
    public static final class QueueFlag extends StdDeserializer<Boolean> {
        public QueueFlag() {
            super(Boolean.class);
        }

        @Override
        public Boolean deserialize(JsonParser parser, DeserializationContext context) {
            if (parser.hasToken(JsonToken.VALUE_TRUE) || parser.hasToken(JsonToken.VALUE_FALSE)) {
                return parser.hasToken(JsonToken.VALUE_TRUE);
            }
            parser.skipChildren();
            return null;
        }
    }
}
