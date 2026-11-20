// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.prompt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.util.unit.DataSize;

/**
 * The setting of the prompt archive ({@code sennard.prompt-archive.max-bytes}), bound the way Spring Boot binds it, with no database.
 *
 * <pre>
 * d1  default      no setting at all -> one mebibyte (1,048,576 bytes)
 * b1  binding      a relaxed name and a size unit bind: "2KB" -> 2048, "10B" -> 10, "3MB" -> 3,145,728, a plain number is bytes
 * v1  validation   zero bytes is refused (a prompt keeps at least one byte), and so is a negative size
 * </pre>
 */
class PromptArchivePropertiesTest {
    private static PromptArchiveProperties bind(Map<String, String> settings) {
        return new Binder(new MapConfigurationPropertySource(settings)).bindOrCreate("sennard.prompt-archive", Bindable.of(PromptArchiveProperties.class));
    }

    @Test
    void theDefaultIsOneMebibyte() {
        assertEquals(DataSize.ofMegabytes(1), bind(Map.of()).maxBytes(), "Spring's megabyte is 1024 * 1024 bytes");
        assertEquals(1_048_576L, bind(Map.of()).maxBytes().toBytes());
    }

    @Test
    void aSizeUnitBinds() {
        assertEquals(2048L, bind(Map.of("sennard.prompt-archive.max-bytes", "2KB")).maxBytes().toBytes());
        assertEquals(10L, bind(Map.of("sennard.prompt-archive.max-bytes", "10B")).maxBytes().toBytes());
        assertEquals(3L * 1024 * 1024, bind(Map.of("sennard.prompt-archive.maxBytes", "3MB")).maxBytes().toBytes());
        assertEquals(500L, bind(Map.of("sennard.prompt-archive.max-bytes", "500")).maxBytes().toBytes());
    }

    @Test
    void aSizeBelowOneByteIsRefused() {
        assertThrows(BindException.class, () -> bind(Map.of("sennard.prompt-archive.max-bytes", "0B")));
        assertThrows(BindException.class, () -> bind(Map.of("sennard.prompt-archive.max-bytes", "-1B")));
    }
}
