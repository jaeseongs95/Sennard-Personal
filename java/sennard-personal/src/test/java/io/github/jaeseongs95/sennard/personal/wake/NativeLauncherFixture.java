// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.wake;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Minimal header fixtures for discovery only. These files are never launched. */
final class NativeLauncherFixture {
    private NativeLauncherFixture() {
    }

    static Path executable(Path path, byte[] header) throws IOException {
        Files.write(path, header);
        assertTrue(path.toFile().setExecutable(true));
        return path;
    }

    static byte[] localHeader() {
        String system = ManagementFactory.getOperatingSystemMXBean().getName().toLowerCase(Locale.ROOT);
        if (system.startsWith("windows")) return pe();
        if (system.equals("linux")) return elf();
        if (system.equals("mac os x") || system.equals("darwin")) return machO();
        throw new AssertionError("No native launcher fixture for " + system);
    }

    static byte[] foreignHeader() {
        return localHeader()[0] == 'M' ? elf() : pe();
    }

    static byte[] pe() {
        ByteBuffer header = ByteBuffer.allocate(64 + 24 + 112).order(ByteOrder.LITTLE_ENDIAN);
        header.putShort(0, (short) 0x5a4d);
        header.putInt(60, 64);
        header.putInt(64, 0x00004550);
        header.putShort(68, (short) 0x8664);
        header.putShort(70, (short) 1);
        header.putShort(84, (short) 112);
        header.putShort(86, (short) 2);
        header.putShort(88, (short) 0x20b);
        return header.array();
    }

    static byte[] elf() {
        ByteBuffer header = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
        header.put(new byte[] {0x7f, 'E', 'L', 'F', 2, 1, 1});
        header.putShort(16, (short) 3);
        header.putShort(18, (short) 62);
        header.putInt(20, 1);
        header.putShort(52, (short) 64);
        return header.array();
    }

    static byte[] machO() {
        ByteBuffer header = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(0, 0xfeedfacf);
        header.putInt(4, 0x01000007);
        header.putInt(12, 2);
        return header.array();
    }
}
