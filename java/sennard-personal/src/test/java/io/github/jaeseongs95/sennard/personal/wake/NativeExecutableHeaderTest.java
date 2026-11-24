// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.wake;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static io.github.jaeseongs95.sennard.personal.wake.NativeLauncher.Platform.*;
import static io.github.jaeseongs95.sennard.personal.wake.NativeLauncher.ScriptPolicy.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeExecutableHeaderTest {
    @TempDir
    Path temporary;

    @Test
    void mapsSupportedOperatingSystemNamesAndRejectsUnknownNames() {
        assertAll(
                () -> assertEquals(WINDOWS, NativeLauncher.Platform.fromName("Windows 11")),
                () -> assertEquals(LINUX, NativeLauncher.Platform.fromName("LINUX")),
                () -> assertEquals(MAC, NativeLauncher.Platform.fromName("Mac OS X")),
                () -> assertEquals(MAC, NativeLauncher.Platform.fromName("Darwin")),
                () -> assertEquals(UNSUPPORTED, NativeLauncher.Platform.fromName("FreeBSD")),
                () -> assertEquals(UNSUPPORTED, NativeLauncher.Platform.fromName("Linux Mint")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("formats")
    void recognizesExecutableHeadersOnlyOnTheirPlatform(String description, byte[] header, NativeLauncher.Platform expected)
            throws Exception {
        Path path = NativeLauncherFixture.executable(temporary.resolve("launcher.exe"), header);
        for (var platform : NativeLauncher.Platform.values()) {
            assertEquals(platform == expected, NativeLauncher.eligible(path, platform, NATIVE_ONLY), platform.toString());
        }
    }

    private static Stream<Arguments> formats() {
        var hex = HexFormat.of();
        byte[] pe = NativeLauncherFixture.pe();
        byte[] elf = NativeLauncherFixture.elf();
        byte[] macho = NativeLauncherFixture.machO();
        byte[] fat32 = fat(false);
        byte[] fat64 = fat(true);
        byte[] invalidOffset = pe.clone();
        ByteBuffer.wrap(invalidOffset).order(ByteOrder.LITTLE_ENDIAN).putInt(60, -1);
        return Stream.of(
                Arguments.of("PE32+", pe, WINDOWS),
                Arguments.of("ELF64 PIE", elf, LINUX),
                Arguments.of("thin Mach-O", macho, MAC),
                Arguments.of("universal Mach-O with 32-bit offsets", fat32, MAC),
                Arguments.of("universal Mach-O with 64-bit offsets", fat64, MAC),
                Arguments.of("plain text", hex.parseHex("61626364"), null),
                Arguments.of("DOS magic only", hex.parseHex("4d5a0000"), null),
                Arguments.of("PE offset outside file", invalidOffset, null),
                Arguments.of("truncated PE", Arrays.copyOf(pe, pe.length - 1), null),
                Arguments.of("truncated ELF", Arrays.copyOf(elf, elf.length - 1), null),
                Arguments.of("truncated thin Mach-O", Arrays.copyOf(macho, macho.length - 1), null),
                Arguments.of("truncated universal Mach-O", Arrays.copyOf(fat32, fat32.length - 1), null),
                Arguments.of("Java class", hex.parseHex("cafebabe00000041000207000201000141"), null));
    }

    private static byte[] fat(boolean wide) {
        byte[] slice = NativeLauncherFixture.machO();
        int offset = 64;
        ByteBuffer image = ByteBuffer.allocate(offset + slice.length);
        image.putInt(wide ? 0xcafebabf : 0xcafebabe).putInt(1).putInt(0x01000007).putInt(0);
        if (wide) {
            image.putLong(offset).putLong(slice.length).putInt(0).putInt(0);
        } else {
            image.putInt(offset).putInt(slice.length).putInt(0);
        }
        image.position(offset).put(slice);
        return image.array();
    }

    @Test
    void acceptsOnlyValidPosixShebangWhenLauncherPolicyAllowsIt() throws Exception {
        Path launcher = temporary.resolve("launcher.exe");
        for (String shebang : new String[] {"#!/bin/sh\n", "#!  /usr/bin/env bash\n"}) {
            NativeLauncherFixture.executable(launcher, shebang.getBytes(StandardCharsets.US_ASCII));
            assertTrue(NativeLauncher.eligible(launcher, LINUX, POSIX_SHEBANG));
            assertTrue(NativeLauncher.eligible(launcher, MAC, POSIX_SHEBANG));
            assertFalse(NativeLauncher.eligible(launcher, WINDOWS, POSIX_SHEBANG));
            assertFalse(NativeLauncher.eligible(launcher, NATIVE_ONLY, failure -> fail(failure)));
            assertFalse(NativeLauncher.eligible(launcher, UNSUPPORTED, POSIX_SHEBANG));
        }
        for (String shebang : new String[] {"#!\n", "#!\t \n", "#!sh\n", "#!/\n", "#!/bin/sh\r\n", "#!/usr/bin/env sh\0\n"}) {
            NativeLauncherFixture.executable(launcher, shebang.getBytes(StandardCharsets.US_ASCII));
            assertFalse(NativeLauncher.eligible(launcher, LINUX, POSIX_SHEBANG), shebang);
        }
    }

    @Test
    void onlyAbsoluteRegularFilesAreEligible() throws Exception {
        assertFalse(NativeLauncher.eligible(Path.of("launcher.exe"), WINDOWS, NATIVE_ONLY));
        assertFalse(NativeLauncher.eligible(temporary.resolve("missing"), WINDOWS, NATIVE_ONLY));
        assertFalse(NativeLauncher.eligible(temporary, WINDOWS, NATIVE_ONLY));
    }

    @Test
    void nativeOnlyRejectsCommandScriptNamesEvenWithAnExecutableHeader() throws Exception {
        for (String suffix : new String[] {".cmd", ".BAT", ".Ps1"}) {
            Path disguised = NativeLauncherFixture.executable(temporary.resolve("launcher" + suffix), NativeLauncherFixture.pe());
            assertFalse(NativeLauncher.eligible(disguised, WINDOWS, NATIVE_ONLY), suffix);
        }
        Path nativeImage = NativeLauncherFixture.executable(temporary.resolve("launcher.exe"), NativeLauncherFixture.pe());
        assertTrue(NativeLauncher.eligible(nativeImage, WINDOWS, NATIVE_ONLY));
    }

    @Test
    void pathSelectionIgnoresRelativeEntriesAndKeepsDirectoryAndNameOrder() throws Exception {
        Path first = Files.createDirectory(temporary.resolve("first"));
        Path second = Files.createDirectory(temporary.resolve("second"));
        var env = Map.of("Path", String.join(File.pathSeparator, ".", first.toString(), second.toString()));
        Path preferred = first.resolve("launcher");
        Path firstExe = first.resolve("launcher.exe");
        Path secondLauncher = second.resolve("launcher");
        Files.createFile(preferred);
        Files.createFile(firstExe);
        Files.createFile(secondLauncher);
        assertEquals(preferred, NativeLauncher.onPath(env, "launcher", Files::isRegularFile).orElseThrow());
        assertEquals(firstExe, NativeLauncher.onPath(env, "launcher", path -> Files.isRegularFile(path) && !preferred.equals(path)).orElseThrow());
        assertEquals(secondLauncher, NativeLauncher.onPath(env, "launcher", path -> path.equals(secondLauncher)).orElseThrow());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void acceptsExecutableSymlinkTargetButKeepsOriginalPath() throws Exception {
        Path target = NativeLauncherFixture.executable(temporary.resolve("target"), NativeLauncherFixture.localHeader());
        Path launcher = Files.createSymbolicLink(temporary.resolve("launcher"), target.getFileName());
        assertEquals(launcher, NativeLauncher.inDirectory(temporary, "launcher",
                path -> NativeLauncher.eligible(path, NATIVE_ONLY, failure -> fail(failure))).orElseThrow());
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-------"));
        assertFalse(NativeLauncher.eligible(launcher, NATIVE_ONLY, failure -> fail(failure)));
        Files.delete(target);
        assertFalse(NativeLauncher.eligible(launcher, NATIVE_ONLY, failure -> fail(failure)));
    }
}
