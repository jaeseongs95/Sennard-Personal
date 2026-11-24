// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.execution;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * A program to run. {@code argv} is the program and its arguments exactly as they are handed to the operating system: no shell reads them, so no quoting, expansion or
 * redirection applies to them. Every part is given by whoever asks: there is no default environment, time limit or log limit.
 *
 * @param argv the program and its arguments, 1 to {@value #MAX_ARGV} items
 * @param workingDirectory the absolute path of the folder the program runs in
 * @param environment the variables the program is given ({@link EnvironmentSpec}); not optional
 * @param timeout how long the program may run; it is killed with what it started when the time is over
 * @param logLimitBytes the most bytes each of the two log files takes ({@code 1} to {@value #MAX_LOG_BYTES}); the program is not stopped when a stream passes it
 */
public record ExecutionRequest(List<String> argv, Path workingDirectory, EnvironmentSpec environment, Duration timeout, long logLimitBytes) {
    /** The most items an argv holds. */
    public static final int MAX_ARGV = 128;
    /** The most a log file may be asked to take: 64 MiB. */
    public static final long MAX_LOG_BYTES = 64L * 1024 * 1024;

    public ExecutionRequest {
        Objects.requireNonNull(argv, "argv");
        argv = List.copyOf(argv);
        if (argv.isEmpty() || argv.size() > MAX_ARGV) {
            throw new IllegalArgumentException("An argv holds 1 to " + MAX_ARGV + " items, not " + argv.size());
        }
        Objects.requireNonNull(workingDirectory, "workingDirectory");
        if (!workingDirectory.isAbsolute()) {
            throw new IllegalArgumentException("The working directory is an absolute path: " + workingDirectory);
        }
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("The time limit is longer than zero: " + timeout);
        }
        if (logLimitBytes < 1 || logLimitBytes > MAX_LOG_BYTES) {
            throw new IllegalArgumentException("The log limit is 1 to " + MAX_LOG_BYTES + " bytes, not " + logLimitBytes);
        }
    }
}
