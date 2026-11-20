// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.install;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.BindResult;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;

import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.domain.error.InputCode;
import io.github.jaeseongs95.sennard.domain.error.InvalidInputException;
import io.github.jaeseongs95.sennard.domain.error.UnavailableCode;
import io.github.jaeseongs95.sennard.domain.error.UnavailableException;
import io.github.jaeseongs95.sennard.domain.jvm.JvmPreset;

/**
 * {@code sennard-jvm.yaml} beside the jar: the server's JVM preset ({@code sennard.jvm.preset}) and the memory the installer read to choose its first value
 * ({@code sennard.jvm.observed-memory}, bytes). The installer writes it once; after that the admin page changes the preset, and an upgrade carries the file
 * along, so neither a reinstall nor an upgrade puts a computed value over a choice. It is not {@code sennard.yaml}, which people write and programs must not rewrite.
 * It is read the way Boot reads any settings file: {@link YamlPropertySourceLoader} and {@link Binder}.
 */
public final class JvmSettingsFile {
    private static final String HEADER = "# Written by sennard install and by the admin page. Change the preset on the admin page.\n";
    private static final String PREFIX = "sennard.jvm";

    private JvmSettingsFile() {
    }

    /**
     * @param preset the preset the launchers are configured with
     * @param observedBytes the total memory the installer read, or null when the preset was not chosen from a reading
     */
    public record Content(JvmPreset preset, Long observedBytes) {
        public Content withPreset(JvmPreset changed) {
            return new Content(changed, observedBytes);
        }
    }

    /** What the file binds to: {@code sennard.jvm.preset} and {@code sennard.jvm.observed-memory}. */
    record Stored(JvmPreset preset, Long observedMemory) {
    }

    /** @return empty when the file does not exist; a file that exists and cannot be understood is a failure, not a guess */
    public static Optional<Content> read(Path file) {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        List<PropertySource<?>> sources;
        try {
            sources = new YamlPropertySourceLoader().load(file.toString(), new FileSystemResource(file));
        } catch (IOException | YAMLException cause) {
            throw new UnavailableException(UnavailableCode.JVM_SETTINGS_UNREADABLE, SennardException.args("file", file), cause);
        }
        BindResult<Stored> bound;
        try {
            bound = new Binder(ConfigurationPropertySources.from(sources)).bind(PREFIX, Stored.class);
        } catch (BindException unknown) {
            Object value = unknown.getProperty() == null ? "(unreadable)" : unknown.getProperty().getValue();
            throw new InvalidInputException(InputCode.JVM_PRESET_UNKNOWN, SennardException.args("problem", "notAPreset", "value", value, "file", file, "presets", Arrays.toString(JvmPreset.values())), Map.of(), unknown);
        }
        // A binding with no preset is unbound too: BindResult.map turns a null result into no result.
        Stored stored = bound.map(found -> found.preset() == null ? null : found)
                .orElseThrow(() -> new InvalidInputException(InputCode.JVM_PRESET_UNKNOWN, SennardException.args("problem", "missing", "file", file, "presets", Arrays.toString(JvmPreset.values()))));
        return Optional.of(new Content(stored.preset(), stored.observedMemory()));
    }

    public static void write(Path file, Content content) {
        Map<String, Object> jvm = new LinkedHashMap<>();
        jvm.put("preset", content.preset().name());
        if (content.observedBytes() != null) {
            jvm.put("observed-memory", content.observedBytes());
        }
        Path temporary = file.resolveSibling(file.getFileName() + ".new");
        try {
            Files.writeString(temporary, HEADER + dump(Map.of("sennard", Map.of("jvm", jvm))), StandardCharsets.UTF_8);
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException cause) {
            throw new UnavailableException(UnavailableCode.JVM_SETTINGS_NOT_WRITTEN, SennardException.args("file", file), cause);
        }
    }

    /** The document in block style, one key per line, which is how a person reads and edits an {@code sennard.yaml}. */
    static String dump(Map<String, Object> document) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        return new Yaml(options).dump(document);
    }
}
