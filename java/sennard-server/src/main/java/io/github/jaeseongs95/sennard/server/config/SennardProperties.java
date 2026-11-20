// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.config;

import java.nio.file.Path;
import java.time.Duration;

import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * The server's settings under {@code sennard}: the one record of this prefix in the server module. Every key a server class reads sits here, so no class reads one with
 * {@code @Value} and no second record binds the same prefix. (The state folder is {@code PersonalProperties}: it sits in a module above this one, which cannot hold it.)
 *
 * @param guard what the filter in front of every path enforces
 * @param skillsDir the deployed skills folder ({@code sennard.skills-dir}); null when none is configured
 * @param skillsRoot where the {@code skills/} tree is read from ({@code sennard.skills-root}); {@code home} when it is not set
 * @param home the application folder ({@code sennard.home}); the working directory when it is not set
 * @param scheduledProbes whether the server asks the outside (the update check) on its own schedule; a test that needs it asks directly
 * @param updates the update check
 * @param hooks how the hook endpoints wait
 * @param hosts where the agent hosts keep their own files ({@code sennard.hosts.*}); application.yaml sets them, so a class never reads the environment or {@code user.home}
 * @param jev the settings every JEV provider shares ({@code sennard.jev.*}); a provider's own settings are its adapter's record
 * @param stateDir the per-user state folder, where the host identity is kept ({@code sennard.state-dir}); null in a context that has no installation (the workflow's host identity
 *     refuses to be made without it)
 * @param contentRoot the folder holding the skills and the contract files the workflow reads ({@code sennard.content-root}); {@code home} when it is not set
 * @param run the commands the test-engineering {@code run} starts ({@code sennard.run.*})
 */
@ConfigurationProperties("sennard")
@Validated
public record SennardProperties(@DefaultValue @Valid Guard guard, Path skillsDir, Path skillsRoot, @DefaultValue(".") Path home,
        @DefaultValue("true") boolean scheduledProbes, @DefaultValue @Valid Updates updates, @DefaultValue @Valid Hooks hooks, @DefaultValue @Valid Hosts hosts, @DefaultValue @Valid Jev jev, Path stateDir,
        Path contentRoot, @DefaultValue @Valid Run run) {
    /** The tag listing of the repository the plugin is released from. */
    public static final String DEFAULT_TAGS_API = "https://api.github.com/repos/jaeseongs95/Sennard-Personal/git/matching-refs/tags/v";

    /**
     * @param maxBody the largest request body any path accepts
     * @param adminMaxBody the largest request body the admin page's port accepts (it carries only small signed records)
     */
    public record Guard(@DefaultValue("16MB") DataSize maxBody, @DefaultValue("64KB") DataSize adminMaxBody) {
    }

    /**
     * @param tagsApi where the repository's release tags are listed
     * @param pollInterval how long after the last check (and after the start) the server asks for the tags again ({@code sennard.updates.poll-interval}); a plain number is
     *     milliseconds
     */
    public record Updates(@DefaultValue(DEFAULT_TAGS_API) String tagsApi, @DefaultValue("5m") @NotNull @DurationMin(millis = 1) Duration pollInterval) {
    }

    /**
     * The folders of the agent hosts the server reads notes from. No default is made in code: application.yaml gives the two values from Spring's property sources (the
     * {@code CLAUDE_CONFIG_DIR} variable, which may be empty, and {@code user.home}), and a missing one is a binding error (validation), so the server does not start.
     *
     * @param claudeConfigDir the raw value of {@code CLAUDE_CONFIG_DIR}: empty (or only white space) when the variable is not set or is empty
     * @param userHome the user's home folder ({@code user.home})
     */
    public record Hosts(@NotNull String claudeConfigDir, @NotNull Path userHome) {
        /** Claude Code's configuration folder: the variable's folder when it holds something, else {@code .claude} in the user's home (the rule of {@code ClaudePackageLocation}). */
        public Path claudeConfig() {
            return claudeConfigDir.isBlank() ? userHome.resolve(".claude") : Path.of(claudeConfigDir);
        }

        /** The folder Antigravity keeps its notes under. */
        public Path geminiConfig() {
            return userHome.resolve(".gemini");
        }
    }

    /**
     * @param wakeHoldMs how long one wake hook is held open; a plain number is milliseconds. The default is a day less a minute ({@code WakeController.DEFAULT_HOLD_MS}), a
     *     little under what the host's hook waits, so the server answers first. Zero ends every hold at once
     * @param hostGraceMs how long after a session start the host process is decided among the processes the launcher named: the shells and launchers above the hook end within it
     */
    public record Hooks(@DefaultValue("86340000ms") @NotNull Duration wakeHoldMs, @DefaultValue("5000ms") @NotNull Duration hostGraceMs) {
    }

    /**
     * What every JEV provider shares; a provider's own settings (its address) are the record of its adapter ({@code TypeSafeProperties}).
     *
     * @param retry how a provider call that failed in communication is tried again ({@code sennard.jev.retry.*})
     */
    public record Jev(@DefaultValue @Valid Retry retry) {
    }

    /**
     * A call that failed in communication (no connection, cut off, no answer in time, HTTP 429 or 5xx) is tried again at the same target, the pause growing each time; a
     * provider's {@code Retry-After} is followed instead of the pause. The number of tries is not fixed: the request's deadline and the budget end it.
     *
     * @param initialDelay the pause before the first new try ({@code sennard.jev.retry.initial-delay}); a plain number is milliseconds
     * @param multiplier how much longer each following pause is than the one before ({@code sennard.jev.retry.multiplier}), at least 1
     * @param maxDelay the longest pause that is not a provider's own {@code Retry-After} ({@code sennard.jev.retry.max-delay})
     * @param deadline how long after a classification starts a new try may still begin ({@code sennard.jev.retry.deadline}); a try that is running is not cut short by it, only its own
     *     call time limit ends it
     */
    public record Retry(@DefaultValue("1s") @NotNull @DurationMin(millis = 1) Duration initialDelay, @DefaultValue("2.0") @DecimalMin("1.0") double multiplier,
            @DefaultValue("10s") @NotNull @DurationMin(millis = 1) Duration maxDelay, @DefaultValue("60s") @NotNull @DurationMin(millis = 1) Duration deadline) {
    }

    /**
     * @param maxConcurrent how many commands of test runs may be running at once ({@code sennard.run.max-concurrent}); a run asked for beyond it is refused at once, not queued.
     *     Every running command is a process tree of the caller's choosing on this computer, so the default is small
     * @param outputCheckInterval how often a running command is looked at for output past the bound ({@code sennard.run.output-check-interval}); the bytes past the bound are dropped
     *     as they are read, and a command that has written past it is killed when a look finds it, so this is how long a fast writer can go on after the bound. A plain number is
     *     milliseconds, and at least one millisecond is required. A command that ends before the first look is not killed for its output (its exit code is kept, the output is
     *     marked as truncated)
     */
    public record Run(@DefaultValue("2") @Min(1) @Max(64) int maxConcurrent, @DefaultValue("25ms") @NotNull @DurationMin(millis = 1) Duration outputCheckInterval) {
    }

    /** The folder the skills and contract files of the workflow are read from. */
    public Path content() {
        return contentRoot != null ? contentRoot : home;
    }

    /** The deployed skills folder as the intake readers take it: the empty text when none is configured. */
    public String skillsDirectory() {
        return skillsDir == null ? "" : skillsDir.toString();
    }

    /** Where the {@code skills/} tree is read from. */
    public Path skillsTree() {
        return skillsRoot != null ? skillsRoot : home;
    }
}
