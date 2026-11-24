// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.wake;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Pattern;

final class NativeLauncher {
    enum Platform {
        WINDOWS, LINUX, MAC, UNSUPPORTED;

        private static final Map<String, Platform> EXACT_NAMES = Map.of(
                "linux", LINUX, "mac os x", MAC, "darwin", MAC);

        static Platform fromName(String name) {
            String system = name.toLowerCase(Locale.ROOT);
            if (system.startsWith("windows")) return WINDOWS;
            return EXACT_NAMES.getOrDefault(system, UNSUPPORTED);
        }
    }

    enum ScriptPolicy { NATIVE_ONLY, POSIX_SHEBANG }

    private static final Platform LOCAL_PLATFORM =
            Platform.fromName(ManagementFactory.getOperatingSystemMXBean().getName());
    private static final Pattern PATH_SEPARATOR = Pattern.compile(Pattern.quote(File.pathSeparator));
    private static final int MAX_FAT_SLICES = 64;
    private static final int SHEBANG_LIMIT = 256;

    private NativeLauncher() {
    }

    static Optional<Path> onPath(Map<String, String> environment, String name, Predicate<Path> eligible) {
        String path = environment.getOrDefault("PATH", environment.getOrDefault("Path", ""));
        for (String entry : PATH_SEPARATOR.split(path)) {
            if (entry.isBlank()) continue;
            Optional<Path> candidate = inDirectory(Path.of(entry), name, eligible);
            if (candidate.isPresent()) return candidate;
        }
        return Optional.empty();
    }

    static Optional<Path> inDirectory(Path directory, String name, Predicate<Path> eligible) {
        if (!directory.isAbsolute()) return Optional.empty();
        Path candidate = directory.resolve(name);
        if (eligible.test(candidate)) return Optional.of(candidate);
        candidate = directory.resolve(name + ".exe");
        return eligible.test(candidate) ? Optional.of(candidate) : Optional.empty();
    }

    static boolean eligible(Path path, ScriptPolicy scripts, Consumer<IOException> onReadFailure) {
        try {
            return eligible(path, LOCAL_PLATFORM, scripts);
        } catch (IOException unreadable) {
            onReadFailure.accept(unreadable);
            return false;
        }
    }

    /** Follows the file target for inspection. The caller keeps the original launcher path. */
    static boolean eligible(Path path, Platform platform, ScriptPolicy scripts) throws IOException {
        if (platform == Platform.UNSUPPORTED || !path.isAbsolute()
                || path.getFileName() == null
                || (scripts == ScriptPolicy.NATIVE_ONLY && hasScriptSuffix(path.getFileName()))
                || !Files.isRegularFile(path) || !Files.isExecutable(path)) {
            return false;
        }
        try (FileChannel file = FileChannel.open(path, StandardOpenOption.READ)) {
            long size = file.size();
            ByteBuffer magic = read(file, 0, 4);
            if (magic == null) return false;
            if (scripts == ScriptPolicy.POSIX_SHEBANG && (platform == Platform.LINUX || platform == Platform.MAC)
                    && magic.getShort(0) == 0x2321) {
                return shebang(file, size);
            }
            // ceiling: identify executable containers by bounded reads. The OS loader checks the image and ABI at launch.
            return switch (platform) {
                case WINDOWS -> pe(file, size);
                case LINUX -> elf(file, magic.getInt(0));
                case MAC -> machO(file, size, magic.getInt(0));
                case UNSUPPORTED -> false;
            };
        }
    }

    private static boolean hasScriptSuffix(Path fileName) {
        String name = fileName.toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".cmd") || name.endsWith(".bat") || name.endsWith(".ps1");
    }

    private static boolean pe(FileChannel file, long size) throws IOException {
        ByteBuffer dos = read(file, 0, 64);
        if (dos == null || dos.getShort(0) != 0x4d5a) return false;
        long offset = Integer.toUnsignedLong(dos.order(ByteOrder.LITTLE_ENDIAN).getInt(60));
        if (offset < 64 || size - offset < 26) return false;
        ByteBuffer coff = read(file, offset, 26);
        if (coff == null || coff.getInt(0) != 0x50450000) return false;
        coff.order(ByteOrder.LITTLE_ENDIAN);
        int characteristics = Short.toUnsignedInt(coff.getShort(22));
        int optionalSize = Short.toUnsignedInt(coff.getShort(20));
        int minimum = switch (Short.toUnsignedInt(coff.getShort(24))) {
            case 0x10b -> 96;  // PE32 standard and Windows fields
            case 0x20b -> 112; // PE32+ standard and Windows fields
            default -> -1;
        };
        // Require an executable image and exclude DLLs.
        return (characteristics & 0x2002) == 2 && minimum > 0 && optionalSize >= minimum
                && size - offset - 24 >= optionalSize;
    }

    private static boolean elf(FileChannel file, int magic) throws IOException {
        if (magic != 0x7f454c46) return false;
        ByteBuffer identification = read(file, 0, 16);
        if (identification == null || identification.get(6) != 1) return false;
        int width = identification.get(4);
        int encoding = identification.get(5);
        if ((width != 1 && width != 2) || (encoding != 1 && encoding != 2)) return false;
        int length = width == 1 ? 52 : 64;
        ByteBuffer header = read(file, 0, length);
        if (header == null) return false;
        header.order(encoding == 1 ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
        int type = Short.toUnsignedInt(header.getShort(16));
        // ET_DYN also covers position-independent executables.
        return (type == 2 || type == 3) && header.getInt(20) == 1
                && Short.toUnsignedInt(header.getShort(width == 1 ? 40 : 52)) == length;
    }

    private static boolean machO(FileChannel file, long size, int magic) throws IOException {
        if (magic == 0xcafebabe || magic == 0xbebafeca || magic == 0xcafebabf || magic == 0xbfbafeca) {
            return fatMachO(file, size, magic);
        }
        return thinMachO(file, 0, size, magic);
    }

    private static boolean fatMachO(FileChannel file, long size, int magic) throws IOException {
        boolean wide = magic == 0xcafebabf || magic == 0xbfbafeca;
        ByteOrder order = (magic == 0xcafebabe || magic == 0xcafebabf) ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN;
        ByteBuffer header = read(file, 0, 8);
        if (header == null) return false;
        int slices = header.order(order).getInt(4);
        if (slices < 1 || slices > MAX_FAT_SLICES) return false;
        int entrySize = wide ? 32 : 20;
        ByteBuffer table = read(file, 8, slices * entrySize);
        if (table == null) return false;
        table.order(order);
        long tableEnd = 8L + table.limit();
        for (int entry = 0; entry < table.limit(); entry += entrySize) {
            long offset = wide ? table.getLong(entry + 8) : Integer.toUnsignedLong(table.getInt(entry + 8));
            long length = wide ? table.getLong(entry + 16) : Integer.toUnsignedLong(table.getInt(entry + 12));
            if (offset < tableEnd || offset > size || length < 4 || length > size - offset) return false;
            ByteBuffer signature = read(file, offset, 4);
            if (signature == null || !thinMachO(file, offset, length, signature.getInt(0))) return false;
        }
        return true;
    }

    private static boolean thinMachO(FileChannel file, long offset, long size, int magic) throws IOException {
        int length = switch (magic) {
            case 0xfeedface, 0xcefaedfe -> 28;
            case 0xfeedfacf, 0xcffaedfe -> 32;
            default -> 0;
        };
        if (length == 0 || size < length) return false;
        ByteBuffer header = read(file, offset, length);
        if (header == null) return false;
        header.order((magic == 0xcefaedfe || magic == 0xcffaedfe) ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
        return header.getInt(12) == 2 && Integer.toUnsignedLong(header.getInt(20)) <= size - length;
    }

    private static boolean shebang(FileChannel file, long size) throws IOException {
        ByteBuffer line = read(file, 0, (int) Math.min(size, SHEBANG_LIMIT));
        if (line == null) return false;
        int index = 2;
        while (index < line.limit() && horizontalSpace(line.get(index))) index++;
        if (index >= line.limit() || line.get(index++) != '/') return false;
        int first = index;
        int interpreterEnd = -1;
        while (index < line.limit()) {
            byte current = line.get(index);
            if (current == 0 || current == '\r') return false;
            if (current == '\n') return (interpreterEnd < 0 ? index : interpreterEnd) > first;
            if (interpreterEnd < 0 && horizontalSpace(current)) interpreterEnd = index;
            index++;
        }
        return (interpreterEnd >= 0 || size < SHEBANG_LIMIT) && (interpreterEnd < 0 ? index : interpreterEnd) > first;
    }

    private static boolean horizontalSpace(byte value) {
        return value == ' ' || value == '\t';
    }

    private static ByteBuffer read(FileChannel file, long offset, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length);
        while (buffer.hasRemaining()) {
            if (file.read(buffer, offset + buffer.position()) <= 0) return null;
        }
        return buffer.flip();
    }
}
