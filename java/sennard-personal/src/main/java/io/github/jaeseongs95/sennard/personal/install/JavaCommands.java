// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.install;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.domain.error.UnavailableCode;
import io.github.jaeseongs95.sennard.domain.error.UnavailableException;
import io.github.jaeseongs95.sennard.protocol.ToolProcess;

/**
 * The real runs of the installation's own {@code java} commands (the jar extraction and the AOT training), through {@link ToolProcess}: the deadline holds while the program hangs,
 * and its output goes to a file read at the end. The program is the JDK's {@code java}, which no Java API starts a JVM of its own kind in place of; a child process is the only way.
 */
public final class JavaCommands implements Commands {
    @Override
    public Result run(List<String> argv, Map<String, String> environment, Duration timeout) {
        return run(argv, null, environment, null, timeout);
    }

    @Override
    public Result run(List<String> argv, Map<String, String> environment, byte[] input, Duration timeout) {
        return run(argv, null, environment, input, timeout);
    }

    @Override
    public Result run(List<String> argv, Path directory, Map<String, String> environment, Duration timeout) {
        return run(argv, directory, environment, null, timeout);
    }

    private Result run(List<String> argv, Path directory, Map<String, String> environment, byte[] input, Duration timeout) {
        try {
            ToolProcess.Outcome outcome = ToolProcess.run(ToolProcess.Request.of(argv, ToolProcess.Stderr.MERGE, timeout).inDirectory(directory).withEnvironment(environment).withInput(input));
            return switch (outcome) {
                case ToolProcess.Finished done -> new Result(done.exitCode(), new String(done.output(), StandardCharsets.UTF_8));
                case ToolProcess.TimedOut late -> throw new UnavailableException(UnavailableCode.COMMAND_TIMEOUT, SennardException.args("command", argv.get(0), "seconds", String.valueOf(timeout.toSeconds())));
                // No output limit is set, so a run cannot overflow.
                case ToolProcess.Overflowed big -> throw new IllegalStateException(argv.get(0) + " overflowed an output limit that was not set.");
            };
        } catch (IOException cause) {
            throw new UnavailableException(UnavailableCode.COMMAND_NOT_STARTED, SennardException.args("command", argv.get(0), "cause", cause.getMessage()), cause);
        } catch (InterruptedException cause) {
            Thread.currentThread().interrupt();
            throw new UnavailableException(UnavailableCode.COMMAND_INTERRUPTED, SennardException.args("command", argv.get(0)), cause);
        }
    }
}
