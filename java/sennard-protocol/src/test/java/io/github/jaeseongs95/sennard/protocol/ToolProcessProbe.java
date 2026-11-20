// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.protocol;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** A process for {@link ToolProcessTest}: one {@code mode} argument picks what it does, so the tests need no shell and no OS tool. */
public final class ToolProcessProbe {
    private ToolProcessProbe() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        FileOutputStream out = new FileOutputStream(FileDescriptor.out);
        FileOutputStream err = new FileOutputStream(FileDescriptor.err);
        switch (args[0]) {
            case "say" -> {
                out.write("out-text".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.flush();
                err.write("err-text".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                err.flush();
                System.exit(Integer.parseInt(args[1]));
            }
            case "sleep" -> {
                writePid(args[1]);
                Thread.sleep(120_000);
            }
            case "tree" -> {
                // A child that starts a grandchild, as a launcher starts a server: both must die with the deadline.
                new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx" + System.getProperty("sennard.test.childHeap"), "-cp", System.getProperty("java.class.path"),
                        ToolProcessProbe.class.getName(), "sleep", args[2]).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD).start();
                writePid(args[1]);
                Thread.sleep(120_000);
            }
            case "flood" -> {
                byte[] chunk = new byte[64 * 1024];
                Arrays.fill(chunk, (byte) 'x');
                long remaining = Long.parseLong(args[1]);
                while (remaining > 0) {
                    int size = (int) Math.min(chunk.length, remaining);
                    out.write(chunk, 0, size);
                    remaining -= size;
                }
                out.flush();
            }
            case "flood-forever" -> {
                writePid(args[1]);
                byte[] chunk = new byte[64 * 1024];
                Arrays.fill(chunk, (byte) 'x');
                for (int round = 0; round < 100_000; round++) {
                    out.write(chunk);
                    out.flush();
                    Thread.sleep(2);
                }
            }
            case "env" -> {
                String value = System.getenv(args[1]);
                out.write((value == null ? "<unset>" : value).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.flush();
            }
            case "echo-input" -> {
                out.write(System.in.readAllBytes());
                out.flush();
            }
            case "cwd" -> {
                out.write(System.getProperty("user.dir").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.flush();
            }
            default -> throw new IllegalArgumentException("mode " + args[0]);
        }
    }

    private static void writePid(String file) throws IOException {
        Files.writeString(Path.of(file), Long.toString(ProcessHandle.current().pid()));
    }
}
