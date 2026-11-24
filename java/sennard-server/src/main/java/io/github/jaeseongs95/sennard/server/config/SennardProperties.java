// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.config;

import java.nio.file.Path;
import java.time.Duration;

import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
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
     * @param maxConcurrent how many programs of executions may be running at once ({@code sennard.run.max-concurrent}); an execution asked for beyond it is refused at once, not
     *     queued. Every running program is a process tree of the caller's choosing on this computer; the default 8 leaves room for programs that do not end by themselves (a
     *     development server) next to the short ones
     * @param retainedFinished how many finished executions the runner keeps in memory to answer a question about them ({@code sennard.run.retained-finished}); the running ones are all
     *     kept. Each keeps up to {@code StreamOutput.HEAD_BYTES + TAIL_BYTES} bytes of each of its two streams; the record in the database and the log files stay when one is let go
     * @param logRetention how long after it ended the log files of an execution are kept ({@code sennard.run.log-retention}, default 7 days); older ones are deleted, never those of
     *     a running execution
     * @param logCapacity how many bytes the log files of all the executions of this server may take together ({@code sennard.run.log-capacity}, default 1 GiB; a unit such as
     *     {@code 512MB} may be written): beyond it the ones that ended longest ago are deleted first, never those of a running execution (which count in the total)
     * @param stopDeadline how long the stop of the server may take for its executions in all ({@code sennard.run.stop-deadline}, default 15 seconds, at most 20 seconds): the deadline is
     *     fixed when the stop begins and the end of the programs, the output they left and the closing of their records all wait for what is left of it, not each for a time of its own.
     *     What is not closed by then stays RUNNING and is accounted for at the next start. {@code sennard stop} gives up on the server after 30 seconds ({@code ServerControl}), and
     *     the rest of the server's stop takes its part of those: up to 2 seconds for the web server's graceful end ({@code spring.lifecycle.timeout-per-shutdown-phase}) and the closing
     *     of the beans and the stores after it. A deadline that took up the whole of the 30 seconds would make every stop of a server with a program that does not end a failed
     *     {@code sennard stop}; 20 seconds leaves 10 for those, and a value above it is refused at the start
     * @param observationMaxEntries how many entries (folders, files, links) one look at a working folder takes in ({@code sennard.run.observation-max-entries}, default 50000). A look
     *     is taken before and after each observed program; the look before is kept in memory (measured: 126 to 154 bytes an entry for paths of 10 to 43 characters, about one byte
     *     more for each character of the path when its characters are all Latin-1, two bytes for each when one is not (Korean, Chinese, Cyrillic, an emoji: the JVM keeps such a text as
     *     UTF-16), and the same on every system, because only the path relative to the folder is kept as a text and the size and time as two numbers); a folder with more is not looked
     *     at beyond it and the result says so. It is at most {@code observationMaxTotalEntries}, which holds the looks of all the observations together
     * @param observationMaxTime how long one look at a working folder may take ({@code sennard.run.observation-max-time}, default 5 seconds): the look before delays the start of the
     *     program and the look after the answer about its end by at most this
     * @param observationMaxTotalEntries how many entries all the observations of the server may hold in memory together ({@code sennard.run.observation-max-total-entries}, default
     *     100000, at least {@code observationMaxEntries}: a setting below it is refused at the start, because one look of a folder as large as a look may take could never be kept whole).
     *     The look before is kept until its program ends (a development server may not end for days), and up to {@code max-concurrent} programs are observed at once, so the bound of
     *     one look does not bound the memory: 8 observations in large folders could hold 8 times 50000 entries and more. The look before takes one entry of this for every entry it keeps,
     *     the look after takes one only for each change it keeps (a new path, a modified file, a path it could not read: what is as it was is compared with the look before as it is
     *     met and is only marked there), so an observation leases the entries of its folder and one more for each change, up to twice the bound of a look when all of it changed (what
     *     the look before leased for an entry the look after met stays leased until the observation ends: the lease is an upper bound of the entries kept). A total equal to
     *     {@code observationMaxEntries} is allowed, and then in a folder of as many entries as a look may take in, the look before leases all of the total and the look after stops at its
     *     first change with {@code TOTAL_ENTRY_LIMIT} (the result is incomplete: only what both looks saw is compared); a total of twice the bound lets every change of such a
     *     folder be kept. An observation gives
     *     back what it took when it ends (finish or close, whichever way), and a look that finds none left stops where it is and says so ({@code TOTAL_ENTRY_LIMIT}, as
     *     {@code ENTRY_LIMIT} and {@code TIME_LIMIT} say it): the execution is not refused and does not wait. The result that an observation hands over is not counted in it; it holds
     *     the paths that differ, at most those of the two looks, so it is bounded by {@code observationMaxEntries} and not by the total. The default keeps the observations to a part of
     *     the smallest heap preset ({@code -Xmx224m}, for a computer of up to 4 GB), so that the server with its hooks and MCP is not at risk of running out of memory: at 256 bytes an
     *     entry (the measurement above, for paths of about 130 characters of Latin-1, with room for the table of a walk while it grows) 100000 entries are 25.6 MB, 11 percent of 224 MiB
     *     (at the measured 154 bytes, 7 percent); at 384 bytes (the same paths when their characters are not Latin-1: about 116 bytes that do not depend on the path, and 2 for each of 130
     *     characters, with the same room) they are 38.4 MB, 16 percent. The default is twice the bound of one look, so that the look before of a folder as large as a look may take, and
     *     as much more as it changes, fit. It is not set from the heap the server finds itself with: a bigger heap is a bigger value in the settings file
     */
    public record Run(@DefaultValue("8") @Min(1) @Max(64) int maxConcurrent, @DefaultValue("32") @Min(1) @Max(1024) int retainedFinished,
            @DefaultValue("7d") @NotNull @DurationMin(millis = 1) Duration logRetention, @DefaultValue("1GB") @NotNull DataSize logCapacity,
            @DefaultValue("15s") @NotNull @DurationMin(millis = 1) @DurationMax(seconds = 20) Duration stopDeadline, @DefaultValue("50000") @Min(1) long observationMaxEntries,
            @DefaultValue("5s") @NotNull @DurationMin(millis = 1) Duration observationMaxTime, @DefaultValue("100000") @Min(1) long observationMaxTotalEntries) {
        public Run {
            if (logCapacity != null && logCapacity.toBytes() < 1) {
                throw new IllegalArgumentException("sennard.run.log-capacity is at least one byte: " + logCapacity);
            }
        }

        /** The total of the observations holds at least one look as large as one may be: a smaller total refuses every folder of the size the bound of a look allows. */
        @AssertTrue(message = "sennard.run.observation-max-total-entries is at least sennard.run.observation-max-entries")
        public boolean isObservationMaxTotalEntriesAtLeastObservationMaxEntries() {
            return observationMaxTotalEntries >= observationMaxEntries;
        }
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
