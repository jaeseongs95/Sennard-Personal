// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jaeseongs95.sennard.domain.error.InputCode;
import io.github.jaeseongs95.sennard.domain.error.InvalidInputException;
import io.github.jaeseongs95.sennard.domain.jvm.JvmPreset;
import io.github.jaeseongs95.sennard.personal.Sentences;

/**
 * {@code sennard-jvm.yaml}: written by programs, read back the same.
 *
 * <pre>
 * f1  round trip   every preset and the observed memory come back as written; no memory is allowed
 * f2  absent       a missing file is empty, not a failure
 * f3  unknown      a preset name that is not in the closed set is a failure that names the file and the set, never a default
 * f4  replace      a second write replaces the first and leaves no temporary file
 * </pre>
 */
class JvmSettingsFileTest {
    @TempDir
    Path temporary;

    @Test
    void everyPresetAndTheObservedMemoryComeBackAsWritten() {
        Path file = temporary.resolve("sennard-jvm.yaml");
        for (JvmPreset preset : JvmPreset.values()) {
            JvmSettingsFile.write(file, new JvmSettingsFile.Content(preset, 31_890_388_787L));
            assertEquals(new JvmSettingsFile.Content(preset, 31_890_388_787L), JvmSettingsFile.read(file).orElseThrow());
        }
        JvmSettingsFile.write(file, new JvmSettingsFile.Content(JvmPreset.ZGC_4G, null));
        assertNull(JvmSettingsFile.read(file).orElseThrow().observedBytes());
    }

    @Test
    void aMissingFileIsEmpty() {
        assertEquals(Optional.empty(), JvmSettingsFile.read(temporary.resolve("none.yaml")));
    }

    @Test
    void aPresetOutsideTheSetIsAFailureNotADefault() throws IOException {
        Path file = temporary.resolve("sennard-jvm.yaml");
        Files.writeString(file, "sennard:\n  jvm:\n    preset: -Xmx999g\n", StandardCharsets.UTF_8);
        InvalidInputException failure = assertThrows(InvalidInputException.class, () -> JvmSettingsFile.read(file));
        assertEquals(InputCode.JVM_PRESET_UNKNOWN, failure.code());
        String sentence = Sentences.english(failure);
        assertTrue(sentence.contains("-Xmx999g") && sentence.contains("G1_512M"), sentence);
        Files.writeString(file, "sennard:\n  other: 1\n", StandardCharsets.UTF_8);
        assertEquals(InputCode.JVM_PRESET_UNKNOWN, assertThrows(InvalidInputException.class, () -> JvmSettingsFile.read(file)).code());
    }

    /** The file is read the way Boot reads any settings file, so the written text is plain block YAML a person can edit. */
    @Test
    void theWrittenFileIsBlockYamlWithTheKeysTheServerReads() throws IOException {
        Path file = temporary.resolve("sennard-jvm.yaml");
        JvmSettingsFile.write(file, new JvmSettingsFile.Content(JvmPreset.G1_1G, 17_179_869_184L));
        assertEquals("# Written by sennard install and by the admin page. Change the preset on the admin page.\nsennard:\n  jvm:\n    preset: G1_1G\n    observed-memory: 17179869184\n",
                Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void aSecondWriteReplacesTheFirstAndLeavesNoTemporaryFile() throws IOException {
        Path file = temporary.resolve("sennard-jvm.yaml");
        JvmSettingsFile.write(file, new JvmSettingsFile.Content(JvmPreset.G1_224M, 1L));
        JvmSettingsFile.write(file, new JvmSettingsFile.Content(JvmPreset.G1_4G, 1L));
        assertEquals(JvmPreset.G1_4G, JvmSettingsFile.read(file).orElseThrow().preset());
        try (var files = Files.list(temporary)) {
            assertEquals(1, files.count());
        }
    }
}
