// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.install;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.domain.error.UnavailableCode;
import io.github.jaeseongs95.sennard.domain.error.UnavailableException;

/**
 * Starts the server and leaves it running when the command that started it ends. There is no OS registration that would do it: the installer and the commands start the server
 * themselves, from the installed version's own jar.
 */
@FunctionalInterface
public interface ServerStarter {
    /**
     * @param argv the program and its arguments ({@link JvmOptions#command})
     * @param workingDirectory the folder the server runs in: the installation's, so that the JVM's log files are written there by relative names
     * @param environment variables the server's process has, over the starting process's own
     */
    void start(List<String> argv, Path workingDirectory, Map<String, String> environment);

    /** The real start: a child process with no input and no output of its own (the server writes its own log file). */
    static ServerStarter ofProcess() {
        return (argv, workingDirectory, environment) -> {
            ProcessBuilder builder = new ProcessBuilder(argv).directory(workingDirectory.toFile()).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
            environment.forEach((name, value) -> {
                if (value == null) {
                    builder.environment().remove(name);
                } else {
                    builder.environment().put(name, value);
                }
            });
            try {
                builder.start().getOutputStream().close();
            } catch (IOException cause) {
                throw new UnavailableException(UnavailableCode.START_FAILED, SennardException.args("what", "programFailed", "program", argv.get(0), "cause", cause.getMessage()), cause);
            }
        };
    }
}
