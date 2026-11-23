// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.updates;

import java.time.Clock;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.github.jaeseongs95.sennard.service.store.UpdateComparison;
import io.github.jaeseongs95.sennard.service.store.modules.StoreValues;
import io.github.jaeseongs95.sennard.service.store.modules.WorkflowStore;
import io.github.jaeseongs95.sennard.service.store.modules.WorkflowStore.PluginUpdateState;

/**
 * Whether a newer stable release exists, and what is stored of it. Advisory: every failure is recorded on the status and none reaches the tool as an error. Every method here is
 * one use case in one transaction and none asks the network: the repository is asked by {@link UpdateCheckService} through a {@link ReleaseSource}, between {@link #due} and
 * {@link #record}.
 *
 * <p>The state is one row, made once when the server starts ({@link #prepare}). A check writes the columns of the check and a notice writes the columns of the notice, each in one
 * statement, so neither overwrites the other; the one-time notice is the statement whose {@code WHERE} names the last notified version
 * ({@link WorkflowStore#claimPluginUpdateNotice}), and of the requests that ask for it together exactly one changes the row.
 */
@Service
public class PluginUpdateService {
    private static final Logger LOG = LoggerFactory.getLogger(PluginUpdateService.class);
    public static final String PLUGIN_ID = "sennard";
    private static final long SUCCESS_TTL_MS = 24 * 3600_000L;
    private static final long FAILURE_RETRY_MS = 3600_000L;
    private static final Pattern PRERELEASE = Pattern.compile("^([^-]+)-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*$");
    private static final Pattern COMMIT = Pattern.compile("^[a-f0-9]{40}$");
    private static final String REPOSITORY = "https://github.com/jaeseongs95/Sennard-Personal";

    public record Status(String schemaVersion, String pluginId, String currentVersion, String latestVersion, String latestTag, String latestCommit, UpdateComparison comparison,
            String lastAttemptAt, String lastSuccessfulCheckAt, String nextCheckAt, boolean stale, String lastErrorCode, boolean automaticInstall,
            @JsonInclude(JsonInclude.Include.NON_EMPTY) List<HostPackageStatus.Mismatch> hostPackageMismatches) {
        Status withHostPackageMismatches(List<HostPackageStatus.Mismatch> mismatches) {
            return new Status(schemaVersion, pluginId, currentVersion, latestVersion, latestTag, latestCommit, comparison,
                    lastAttemptAt, lastSuccessfulCheckAt, nextCheckAt, stale, lastErrorCode, automaticInstall, mismatches);
        }
    }

    /** Why a check recorded a failure; the name is the status's {@code lastErrorCode}. */
    enum Reason {
        INVALID_RESPONSE, NO_STABLE_TAG, HTTP, NETWORK, TIMEOUT
    }

    /** What a check starts from: the stored state, whether the repository is to be asked, and the status to answer when it is not. */
    record Due(PluginUpdateState state, boolean ask, Status status) {
    }

    private final WorkflowStore store;
    private final String version;
    private final Clock clock;

    public PluginUpdateService(WorkflowStore store, McpServerProperties server, Clock clock) {
        this.store = store;
        this.version = server.getVersion();
        this.clock = clock;
    }

    /** The server's start: makes the row of the update state, so that a check and a notice only update it. */
    @Transactional
    public void prepare() {
        store.createPluginUpdateStateIfAbsent(empty(clock.millis(), null));
    }

    /** The last check's result, asking nothing. */
    @Transactional(readOnly = true)
    public Status status() {
        long now = clock.millis();
        return publicStatus(currentState(now), now);
    }

    /** The state a check starts from, and whether the window has passed (or the check is forced): the repository is asked only then. */
    @Transactional(readOnly = true)
    public Due due(boolean force, long now) {
        PluginUpdateState current = currentState(now);
        boolean ask = force || now >= StoreValues.parseIso(current.nextCheckAt());
        return new Due(current, ask, publicStatus(current, now));
    }

    /** Stores what a check found, in one statement that leaves the notice's columns as they are. */
    @Transactional
    public Status record(PluginUpdateState current, ReleaseSource.Answer answer, long now) {
        PluginUpdateState checked = switch (answer) {
            case ReleaseSource.Latest latest -> checked(current, latest, now);
            case ReleaseSource.NotModified unchanged -> current.latestVersion() == null ? failed(current, now, Reason.INVALID_RESPONSE) : notModified(current, now);
            case ReleaseSource.Failed failure -> failed(current, now, failure.reason());
        };
        if (!store.recordPluginUpdateCheck(checked)) {
            LOG.warn("The update state has no row to record the check on: the server makes it when it starts.");
        }
        return publicStatus(checked, now);
    }

    /** The status from the last check, without asking the repository; empty when no check has run. */
    @Transactional(readOnly = true)
    public Optional<Status> cachedStatus() {
        if (store.getPluginUpdateState(PLUGIN_ID) == null) {
            return Optional.empty();
        }
        long now = clock.millis();
        return Optional.of(publicStatus(currentState(now), now));
    }

    private PluginUpdateState currentState(long now) {
        PluginUpdateState stored = store.getPluginUpdateState(PLUGIN_ID);
        if (stored == null) {
            return empty(now, null);
        }
        boolean latestValid = stored.latestVersion() == null ? stored.latestTag() == null && stored.latestCommit() == null
                : StableVersion.accepts(stored.latestVersion()) && ("v" + stored.latestVersion()).equals(stored.latestTag())
                        && stored.latestCommit() != null && COMMIT.matcher(stored.latestCommit()).matches();
        if (!latestValid || !validDate(stored.nextCheckAt())) {
            return empty(now, Reason.INVALID_RESPONSE.name());
        }
        return with(stored, comparison(stored.latestVersion()), stored.lastAttemptAt(), stored.lastSuccessfulCheckAt(), stored.nextCheckAt(), stored.lastErrorCode());
    }

    private PluginUpdateState empty(long now, String error) {
        return new PluginUpdateState(PLUGIN_ID, version, null, null, null, null, UpdateComparison.UNKNOWN, null, null, StoreValues.iso(now), null, null, error);
    }

    private PluginUpdateState with(PluginUpdateState base, UpdateComparison comparison, String attempt, String success, String next, String error) {
        return new PluginUpdateState(PLUGIN_ID, version, base.latestVersion(), base.latestTag(), base.latestCommit(), base.etag(), comparison, attempt, success, next,
                base.lastNotifiedVersion(), base.lastNotifiedAt(), error);
    }

    private PluginUpdateState failed(PluginUpdateState current, long now, Reason reason) {
        return with(current, current.comparison(), StoreValues.iso(now), current.lastSuccessfulCheckAt(), StoreValues.iso(now + FAILURE_RETRY_MS), reason.name());
    }

    /** The repository answered that nothing changed since the entity tag: the release the state names is still the newest. */
    private PluginUpdateState notModified(PluginUpdateState state, long now) {
        String iso = StoreValues.iso(now);
        return with(state, comparison(state.latestVersion()), iso, iso, StoreValues.iso(now + SUCCESS_TTL_MS), null);
    }

    /** What a successful check found; the stored entity tag is empty (null in the row) when the answer carried none. */
    private PluginUpdateState checked(PluginUpdateState state, ReleaseSource.Latest latest, long now) {
        return switch (latest) {
            case ReleaseSource.Tagged tagged -> checked(state, latest, tagged.etag(), now);
            case ReleaseSource.Untagged untagged -> checked(state, latest, null, now);
        };
    }

    private PluginUpdateState checked(PluginUpdateState state, ReleaseSource.Latest latest, String etag, long now) {
        String iso = StoreValues.iso(now);
        return new PluginUpdateState(PLUGIN_ID, version, latest.version(), "v" + latest.version(), latest.commit(), etag, comparison(latest.version()), iso, iso,
                StoreValues.iso(now + SUCCESS_TTL_MS), state.lastNotifiedVersion(), state.lastNotifiedAt(), null);
    }

    private static boolean validDate(String value) {
        if (value == null) {
            return false;
        }
        try {
            StoreValues.parseIso(value);
            return true;
        } catch (DateTimeParseException invalid) {
            // The JDK's ISO instant parse reports a malformed text only by exception; the stored text is data of an earlier run, so a malformed one is a value, not a failure.
            LOG.debug("The stored next-check time is not a time", invalid);
            return false;
        }
    }

    private UpdateComparison comparison(String latest) {
        return latest == null ? UpdateComparison.UNKNOWN : UpdateComparison.ofOrder(compareInstalled(version, latest));
    }

    /** A prerelease precedes its own release. */
    static int compareInstalled(String installed, String stable) {
        Matcher prerelease = PRERELEASE.matcher(installed);
        boolean pre = prerelease.matches();
        int order = StableVersion.parse(pre ? prerelease.group(1) : installed).compareTo(StableVersion.parse(stable));
        return order == 0 && pre ? -1 : order;
    }

    private Status publicStatus(PluginUpdateState state, long now) {
        long next = StoreValues.parseIso(state.nextCheckAt());
        return new Status("1.0.0", PLUGIN_ID, version, state.latestVersion(), state.latestTag(), state.latestCommit(), state.comparison(), state.lastAttemptAt(),
                state.lastSuccessfulCheckAt(), state.nextCheckAt(), state.lastSuccessfulCheckAt() == null || state.lastErrorCode() != null || now >= next, state.lastErrorCode(), false, List.of());
    }

    /** The one-time notice of a turn: the cached status and the claim of its notice, as one use case in one transaction; empty when there is nothing new to say. */
    @Transactional
    public Optional<Notice> noticeForTurn() {
        return cachedStatus().flatMap(this::takeNotice);
    }

    /** The one-time notice of a newer release, once per version across every session; empty when there is nothing new to say. */
    @Transactional
    public Optional<Notice> takeNotice(Status status) {
        if (status.comparison() != UpdateComparison.UPDATE_AVAILABLE || status.latestVersion() == null || status.lastSuccessfulCheckAt() == null) {
            return Optional.empty();
        }
        String now = StoreValues.iso(clock.millis());
        StableVersion latest = StableVersion.parse(status.latestVersion());
        boolean claimed = store.claimPluginUpdateNotice(PLUGIN_ID, status.latestVersion(), now, notified -> latest.compareTo(StableVersion.parse(notified)) <= 0);
        return claimed ? Optional.of(new Notice("1.0.0", "plugin-update-notice", PLUGIN_ID, status.currentVersion(), status.latestVersion(), status.latestTag(), status.latestCommit(),
                status.lastSuccessfulCheckAt(), REPOSITORY + "/tree/" + status.latestTag(), false)) : Optional.empty();
    }

    public record Notice(String schemaVersion, String kind, String pluginId, String currentVersion, String latestVersion, String latestTag, String latestCommit, String checkedAt,
            String tagUrl, boolean automaticInstall) {
    }
}
