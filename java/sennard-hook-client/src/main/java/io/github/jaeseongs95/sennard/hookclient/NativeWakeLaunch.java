// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.hookclient;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

/** Starts the installed wake worker with only the current host's local launch environment. */
final class NativeWakeLaunch {
    private final Path jar;
    private final Map<String, String> environment;
    private final String key = UUID.randomUUID().toString();

    NativeWakeLaunch(Path jar, Map<String, String> environment) {
        this.jar = jar;
        this.environment = environment;
    }

    String key() {
        return key;
    }

    void start(HookClientCall call, ServerAddress address, byte[] input) throws IOException {
        String java = ProcessHandle.current().info().command().orElseThrow(() -> new IOException("The Java executable running this hook is unavailable."));
        var command = new ProcessBuilder(java, "-jar", jar.toString(), NativeWakeProtocol.COMMAND, call.label(), call.event());
        command.environment().clear();
        command.environment().putAll(environment);
        command.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
        Process process = command.start();
        try (var stdin = new DataOutputStream(process.getOutputStream())) {
            stdin.writeUTF(key);
            stdin.writeInt(address.port());
            stdin.writeLong(address.pid());
            stdin.writeUTF(address.processStart().toString());
            stdin.write(input);
        } catch (IOException failed) {
            process.destroyForcibly();
            throw failed;
        }
    }
}
