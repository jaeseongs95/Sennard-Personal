// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.setup;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.module.ModuleDescriptor.Version;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;

import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.personal.install.Installer;
import io.github.jaeseongs95.sennard.personal.install.Installer.Status;

/** Runs the same installer as the CLI and re-reads the installation even when an operation fails partway. */
final class SetupService {
    enum Action {
        REFRESH, INSTALL, UPGRADE, START, HOOKS
    }

    enum Mode {
        INSTALL, UPGRADE, CURRENT, NEWER
    }

    record Request(Action action, Status displayed) {
    }

    record Result(Status status, boolean changed, String failure, String statusFailure, String details) {
    }

    private final Function<PrintStream, Installer> installer;
    private final Function<SennardException, String> describeFailure;

    SetupService(Function<PrintStream, Installer> installer, Function<SennardException, String> describeFailure) {
        this.installer = installer;
        this.describeFailure = describeFailure;
    }

    Result run(Request request) {
        ByteArrayOutputStream details = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(details, true, StandardCharsets.UTF_8)) {
            return run(request, installer.apply(out), details);
        }
    }

    private Result run(Request request, Installer target, ByteArrayOutputStream details) {
        Status observed = null;
        String statusFailure = null;
        try {
            observed = target.status();
        } catch (RuntimeException failure) {
            statusFailure = describe(failure);
        }
        if (request.action() == Action.REFRESH || observed == null) {
            return new Result(observed, false, null, statusFailure, details.toString(StandardCharsets.UTF_8));
        }
        if (!observed.installRoot().equals(request.displayed().installRoot())
                || !observed.versions().equals(request.displayed().versions())) {
            return new Result(observed, true, null, null, details.toString(StandardCharsets.UTF_8));
        }

        String failure = null;
        try {
            switch (request.action()) {
                case INSTALL -> target.install(null);
                case UPGRADE -> target.upgrade();
                case START -> target.start();
                case HOOKS -> target.hooks();
                case REFRESH -> throw new IllegalStateException("Refresh has already returned");
            }
        } catch (RuntimeException problem) {
            failure = describe(problem);
        }
        try {
            observed = target.status();
        } catch (RuntimeException problem) {
            observed = null;
            statusFailure = describe(problem);
        }
        return new Result(observed, false, failure, statusFailure, details.toString(StandardCharsets.UTF_8));
    }

    private String describe(RuntimeException failure) {
        return failure instanceof SennardException known ? describeFailure.apply(known) : failure.toString();
    }

    static Mode mode(Status status, String releaseVersion) {
        if (status.versions().isEmpty()) {
            return Mode.INSTALL;
        }
        int comparison = Version.parse(releaseVersion).compareTo(Version.parse(status.versions().getFirst()));
        return comparison > 0 ? Mode.UPGRADE : comparison == 0 ? Mode.CURRENT : Mode.NEWER;
    }
}
