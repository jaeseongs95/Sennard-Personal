// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.admin;

import java.io.IOException;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

import com.fasterxml.jackson.annotation.JsonValue;

import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.domain.secret.SecretValue;
import io.github.jaeseongs95.sennard.domain.jvm.JvmPreset;
import io.github.jaeseongs95.sennard.domain.jvm.JvmPresets;
import io.github.jaeseongs95.sennard.domain.jvm.MemoryEstimate;
import io.github.jaeseongs95.sennard.server.approval.RootApprovalService;
import io.github.jaeseongs95.sennard.server.approval.RootReleaseService;
import io.github.jaeseongs95.sennard.server.workflow.BlockedPlanAdminService;
import io.github.jaeseongs95.sennard.service.store.modules.WorkflowStore.BlockedPlanView;
import io.github.jaeseongs95.sennard.server.web.RequestLocale;
import io.github.jaeseongs95.sennard.server.board.PresenceStore;
import io.github.jaeseongs95.sennard.server.context.AdminLog;
import io.github.jaeseongs95.sennard.server.context.AdminLogSource;
import io.github.jaeseongs95.sennard.server.context.AdminWebAuthn;
import io.github.jaeseongs95.sennard.server.context.ContextLayerService;
import io.github.jaeseongs95.sennard.server.context.PasskeyRevocations;
import io.github.jaeseongs95.sennard.server.context.PreLinkAdminService;
import io.github.jaeseongs95.sennard.server.skills.JevBudget;
import io.github.jaeseongs95.sennard.server.skills.JevProvider;
import io.github.jaeseongs95.sennard.server.skills.JevProviders;
import io.github.jaeseongs95.sennard.server.skills.JevReadiness;
import io.github.jaeseongs95.sennard.server.skills.SkillCategory;
import io.github.jaeseongs95.sennard.server.skills.SkillInventoryReader;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes;
import io.github.jaeseongs95.sennard.server.limits.ResourceLimits;
import io.github.jaeseongs95.sennard.server.workflow.ConvergenceLimits;
import io.github.jaeseongs95.sennard.app.board.BoardService;
import io.github.jaeseongs95.sennard.domain.board.BoardEntry;
import io.github.jaeseongs95.sennard.server.mcp.HostCallerAdapter;
import io.github.jaeseongs95.sennard.service.settings.DatabaseLinkSettings;
import io.github.jaeseongs95.sennard.service.settings.DatabaseTls;
import io.github.jaeseongs95.sennard.service.store.modules.ContextLayerStore;
import io.github.jaeseongs95.sennard.service.store.modules.SkillUsageService;
import io.github.jaeseongs95.sennard.service.store.modules.SkillUsageStore;
import io.github.jaeseongs95.sennard.service.store.modules.StoreValues;
import io.github.jaeseongs95.sennard.service.store.modules.WorkflowStatsService;
import io.github.jaeseongs95.sennard.service.store.pg.PreparedStore;
import io.github.jaeseongs95.sennard.service.store.pg.StorePhase;
import io.github.jaeseongs95.sennard.service.store.pg.StorePreparation;

/**
 * What the admin page shows, composed from the domain services. Viewing needs no passkey; a secret is shown only as its fingerprint. Each answer is a record, so
 * the page's contract is the record's components and the compiler checks it; Jackson writes them as the response.
 */
@Service
public class AdminPageData {
    private static final Logger LOG = LoggerFactory.getLogger(AdminPageData.class);
    static final List<Integer> SKILL_USAGE_PERIODS = List.of(1, 7, 30, SkillUsageStore.RETAIN_DAYS);
    private static final String SCHEMA_VERSION = "1.0.0";
    private static final SessionsError SESSIONS_UNAVAILABLE = new SessionsError(SessionsError.Code.SESSIONS_UNAVAILABLE);

    /**
     * The page of one session: the board's row joined with the session's signed role binding. {@code summaryAt} is when the session wrote its summary and {@code lastPromptAt}
     * when it was last asked something (UTC ISO-8601 text, null when never); {@code summaryStale} is the board's judgment that the summary is older than the last request
     * (null when no request was ever seen), so the page can say that what it shows may no longer be what the session does.
     */
    public record SessionView(String host, String hostLabel, String sessionId, String state, String cwd, String summary, String summaryAt, String lastPromptAt, Boolean summaryStale,
            String selfRole, String lastSeenAt, boolean kindUnknown, BindingSummary binding) {
    }

    public record BindingSummary(String roleId, String boundAt, String lastUsedAt, boolean lapsed) {
    }

    /** What the page shows when the board could not be read; the code is shown as its name. */
    public record SessionsError(Code code) {
        public enum Code { SESSIONS_UNAVAILABLE }
    }

    /** Where the files the edit gate lets through come from. */
    public enum GateSource {
        NONE, SIGNED;

        /** The page compares these as lower-case words. */
        @JsonValue
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public record EditGateExceptions(List<String> paths, GateSource source, List<String> signedPaths, int maxPaths) {
    }

    public record PresetChoice(String id, String collector, String heap) {
    }

    public record Estimate(long estimatedGigabytes, long observedBytes, String defaultPreset, String message) {
    }

    public record JvmView(List<PresetChoice> presets, String signed, String saved, String current, boolean pending, Estimate estimate) {
    }

    public record JevPreset(String presetId, String tool, boolean enabled) {
    }

    public record JevProviderView(String providerId, String label, String endpoint, String credentialName, double perCallUsd, JevProvider.CatalogSnapshot catalog,
            JevProvider.TransmittedFields transmitted) {
    }

    public record JevBudgetView(Double signedLimitUsd, double perCallUsd) {
    }

    public record JevKeyRow(String name, boolean jev, String fingerprint, String setAt, boolean stored) {
    }

    /** {@code fallbackModel} is the signed fallback evaluator, null when none is chosen. The tab's sentences are catalog messages the page makes itself ({@link AdminTexts}). */
    public record JevView(boolean signed, boolean switchOn, boolean ready, List<JevReadiness.Missing> missing, List<JevPreset> presets, AdminLog.JevModel model, AdminLog.JevModel fallbackModel,
            boolean transmissionAllowed, List<JevProviderView> providers, JevBudgetView budget, JevBudget.UsageView usage, List<JevKeyRow> keys) {
    }

    /**
     * The convergence limits: {@code limits} is what a root opened now gets (the signed values, else the defaults), {@code defaults} is what unset means, and {@code signed} says
     * whether the user signed a setting. A root already open keeps the limits it was opened with.
     */
    public record ConvergenceView(ConvergenceLimits limits, ConvergenceLimits defaults, boolean signed) {
    }

    public record ResourceLimitsView(ResourceLimits limits, ResourceLimits defaults, boolean signed, String stateToken,
            int minCsArtifactBytes, int maxCsArtifactBytes, int maxWorkflowsPerSession) {
    }

    public record Settings(EditGateExceptions editGateExceptions, JevView jev, JvmView jvm, ConvergenceView convergence, ResourceLimitsView resourceLimits, AttachmentStorageView attachmentStorage) {
    }

    /** The whole page state (GET /api/state). */
    public record State(String schemaVersion, String rpId, List<ContextLayerService.KeyView> keys, AdminLog.Bootstrap bootstrap, List<ContextLayerStore.Reset> resets,
            ContextLayerService.GlobalView global, List<ContextLayerService.GlobalHistoryItem> globalHistory, List<ContextLayerService.RolePageView> roles,
            List<ContextLayerService.BindingView> bindings, List<ContextLayerService.ProposalView> proposals, List<ContextLayerService.FailureView> failures,
            ContextLayerService.SignedSettings signedSettings, long lastSeq, int globalMaxBytes, int roleMaxBytes, int roleNameMaxLength, SessionsError sessionsError,
            List<SessionView> sessions, Settings settings, List<RootApprovalService.Pending> rootApprovals, OpenRoots openRoots, List<BlockedPlanView> blockedPlans, DatabaseLinkView link) {
    }

    /** The link the user signed on the page, as the settings database keeps it. The password is not here (nor anywhere in the settings database): only the file that holds it. */
    public record SavedLink(String jdbcUrl, String accountName, String driverPath, String passwordRef, DatabaseTls tls, String savedAt) {
    }

    /**
     * The business database as the page shows it. The link is the one source of the database (the settings database keeps it). {@code phase} is how far the store has come
     * ({@link StorePhase}) and {@code failure} the reason of the last failure while it is not ready. {@code beforeLink} says that the page is the settings database's (the business
     * database was never ready): the page then shows the passkeys and this link and nothing else, and a signature is appended to the section of the log before the link.
     * {@code saved} is the link that is saved. {@code restartRequired} says that the saved link was saved after the store started and the store is ready: the store is prepared once
     * in a process, so the link is used by the next start.
     */
    public record DatabaseLinkView(StorePhase phase, boolean beforeLink, String failure, SavedLink saved, boolean restartRequired) {
        /** The view of a business database that no link was saved for: the page reads {@code "saved": null} on the wire as "none saved". */
        static DatabaseLinkView unsaved(StorePhase phase, boolean beforeLink, String failure) {
            return new DatabaseLinkView(phase, beforeLink, failure, null, false);
        }
    }

    /** The roots a person may release, as the page lists them. The screen's sentences are catalog messages the page makes itself ({@link AdminTexts}). Times are UTC ISO-8601 text; the page converts them to the viewer's zone. */
    public record OpenRoots(List<RootReleaseService.OpenRoot> roots) {
    }

    /** The skills of one group in the skill list, with the group's title in the request's language. */
    public record SkillGroup(SkillCategory category, String title, List<String> skills) {
    }

    /** The skill statistics (GET /api/skill-usage): the skills in their groups, the calls, and the hosts that recorded any. */
    public record SkillUsage(String schemaVersion, int days, int retainDays, List<SkillGroup> groups, List<SkillUsageStore.UsageRow> rows, List<SkillUsageStore.HostFirst> hosts) {
    }

    public record AttachmentStorageView(io.github.jaeseongs95.sennard.server.context.AttachmentStorageSettings settings,
            io.github.jaeseongs95.sennard.server.context.AttachmentStorageSettings defaults, boolean signed, String stateToken) { }

    private final io.github.jaeseongs95.sennard.app.attachment.AttachmentProperties attachmentProperties;
    private final ContextLayerService context;
    private final PresenceStore presence;
    private final BoardService board;
    private final JevProviders providers;
    private final JevReadiness readiness;
    private final JevBudget budget;
    private final SkillUsageService skillUsage;
    private final WorkflowStatsService workflowStats;
    private final SkillInventoryReader inventory;
    private final JvmInstallation jvmInstallation;
    private final MessageSource messages;
    private final RootApprovalService rootApprovals;
    private final RootReleaseService rootReleases;
    private final BlockedPlanAdminService blockedPlans;
    /** The hosts whose hooks cannot tell a subagent from a main agent, by the name their sessions are stored under. */
    private final Set<String> hostsMarkingNoSubagents;
    private final Clock clock;
    private final PreLinkAdminService preLink;
    private final AdminLogSource logs;
    private final PreparedStore prepared;
    private final PasskeyRevocations revocations;
    private final DatabaseLinkSettings links;

    public AdminPageData(ContextLayerService context, PresenceStore presence, BoardService board, JevProviders providers, JevReadiness readiness, JevBudget budget, SkillUsageService skillUsage, WorkflowStatsService workflowStats,
            SkillInventoryReader inventory, RootApprovalService rootApprovals, RootReleaseService rootReleases, BlockedPlanAdminService blockedPlans, List<HostCallerAdapter> hosts,
            JvmInstallation jvmInstallation, MessageSource messages, Clock clock, PreLinkAdminService preLink, AdminLogSource logs, PreparedStore prepared,
            DatabaseLinkSettings links, PasskeyRevocations revocations, io.github.jaeseongs95.sennard.app.attachment.AttachmentProperties attachmentProperties) {
        this.attachmentProperties = attachmentProperties;
        this.revocations = revocations;
        this.preLink = preLink;
        this.logs = logs;
        this.prepared = prepared;
        this.links = links;
        this.rootReleases = rootReleases;
        this.blockedPlans = blockedPlans;
        this.jvmInstallation = jvmInstallation;
        this.messages = messages;
        this.clock = clock;
        this.hostsMarkingNoSubagents = hosts.stream().filter(host -> !host.hooksMarkSubagents()).map(host -> host.host().sessionHost()).collect(Collectors.toUnmodifiableSet());
        this.context = context;
        this.presence = presence;
        this.board = board;
        this.providers = providers;
        this.readiness = readiness;
        this.budget = budget;
        this.skillUsage = skillUsage;
        this.workflowStats = workflowStats;
        this.inventory = inventory;
        this.rootApprovals = rootApprovals;
    }

    /**
     * On a host whose hooks do not mark a subagent, a session that has written nothing about itself may be a subagent's, and the page cannot
     * tell: it goes to the page's undecided group, where the user sets a role or takes it off the list.
     */
    private boolean kindUnknown(String host, String summary, String role) {
        return hostsMarkingNoSubagents.contains(host) && (summary == null || summary.isBlank()) && (role == null || role.isBlank());
    }

    /** The sessions the board knows, then the bound sessions it no longer knows, shown as ended so their binding can still be seen and removed. */
    private record Sessions(SessionsError error, List<SessionView> views) {
    }

    private Sessions sessions(ContextLayerService.AdminPageState admin) {
        Map<String, ContextLayerService.BindingView> bindings = new HashMap<>();
        admin.bindings().forEach(binding -> bindings.put(AdminLog.bindingKey(binding.host(), binding.sessionId()), binding));
        List<SessionView> views = new ArrayList<>();
        Set<String> known = new LinkedHashSet<>();
        SessionsError error = null;
        try {
            for (PresenceStore.Row row : presence.currentPresence("", "").sessions()) {
                known.add(AdminLog.bindingKey(row.host(), row.sessionId()));
                views.add(board.readEntry(row.host(), row.sessionId()).map(entry -> boardView(row, entry, bindings)).orElseGet(() -> boardView(row, null, bindings)));
            }
        } catch (DataAccessException | TransactionException | SennardException failed) {
            LOG.warn("The admin page could not read the session board", failed);
            views.clear();
            known.clear();
            error = SESSIONS_UNAVAILABLE;
        }
        for (ContextLayerService.BindingView binding : admin.bindings()) {
            if (!known.contains(AdminLog.bindingKey(binding.host(), binding.sessionId()))) {
                views.add(view(binding.host(), binding.sessionId(), "ended", null, null, false, bindings));
            }
        }
        return new Sessions(error, views);
    }

    /** A session the board lists, with what its board entry says (null where the session has no entry). */
    private SessionView boardView(PresenceStore.Row row, BoardEntry entry, Map<String, ContextLayerService.BindingView> bindings) {
        boolean undecided = kindUnknown(row.host(), entry == null ? null : entry.summary(), entry == null ? null : entry.role());
        return view(row.host(), row.sessionId(), row.state(), entry, row.heartbeatAt(), undecided, bindings);
    }

    private static SessionView view(String host, String sessionId, String state, BoardEntry entry, String lastSeenAt, boolean kindUnknown, Map<String, ContextLayerService.BindingView> bindings) {
        ContextLayerService.BindingView binding = bindings.get(AdminLog.bindingKey(host, sessionId));
        return new SessionView(host, host, sessionId, state, entry == null ? null : entry.cwd(), entry == null ? null : entry.summary(), entry == null || entry.summaryAt() == null ? null : entry.summaryAt().toString(),
                entry == null || entry.lastPromptAt() == null ? null : entry.lastPromptAt().toString(), entry == null ? null : entry.summaryStale(), entry == null ? null : entry.role(), lastSeenAt, kindUnknown,
                binding == null ? null : new BindingSummary(binding.roleId(), binding.boundAt(), binding.lastUsedAt(), binding.lapsed()));
    }

    private Settings settings(ContextLayerService.SignedSettings signed) {
        List<String> signedPaths = signed.editExempt() == null ? List.of() : signed.editExempt().paths();
        EditGateExceptions gate = new EditGateExceptions(signedPaths, signedPaths.isEmpty() ? GateSource.NONE : GateSource.SIGNED, signedPaths, AdminLog.EDIT_EXEMPT_MAX_PATHS);
        return new Settings(gate, jevView(signed), jvmView(signed), convergenceView(signed), resourceLimitsView(signed), attachmentStorageView(signed));
    }

    private AttachmentStorageView attachmentStorageView(ContextLayerService.SignedSettings signed) {
        var defaults = io.github.jaeseongs95.sennard.server.context.AttachmentStorageSettings.of(new io.github.jaeseongs95.sennard.app.attachment.AttachmentSettings(attachmentProperties).current());
        var state = signed.attachmentStorage();
        return new AttachmentStorageView(state == null ? defaults : state.settings(), defaults, state != null, signed.attachmentStorageToken());
    }

    private static ResourceLimitsView resourceLimitsView(ContextLayerService.SignedSettings signed) {
        AdminLog.ResourceLimitsState state = signed.resourceLimits();
        return new ResourceLimitsView(state == null ? ResourceLimits.DEFAULT : state.limits(), ResourceLimits.DEFAULT, state != null, signed.resourceLimitsToken(),
                ResourceLimits.MIN_CS_ARTIFACT_BYTES, ResourceLimits.MAX_CS_ARTIFACT_BYTES, Integer.MAX_VALUE);
    }

    private static ConvergenceView convergenceView(ContextLayerService.SignedSettings signed) {
        AdminLog.ConvergenceLimitsState state = signed.convergenceLimits();
        return new ConvergenceView(state == null ? ConvergenceLimits.DEFAULT : state.limits(), ConvergenceLimits.DEFAULT, state != null);
    }

    /**
     * The server's JVM preset: the choices (a closed set, never text), the signed choice and the one the launcher was last written for, and what the installer's one
     * memory reading was. The memory is always shown as an estimate (the JVM reports what the OS gives it, not what is installed), in the language of the request.
     */
    private JvmView jvmView(ContextLayerService.SignedSettings signed) {
        JvmPreset signedPreset = signed.jvmPreset() == null ? null : signed.jvmPreset().preset();
        return jvmInstallation.saved().map(saved -> jvmView(signedPreset, saved)).orElseGet(() -> jvmView(signedPreset, null));
    }

    /** @param saved what the installer saved, null when it saved nothing */
    private JvmView jvmView(JvmPreset signedPreset, JvmInstallation.Saved saved) {
        List<PresetChoice> presets = Arrays.stream(JvmPreset.values()).map(preset -> new PresetChoice(preset.name(), preset.collector().name(), preset.heapOption())).toList();
        String savedName = saved == null ? null : saved.preset().name();
        Long observed = saved == null ? null : saved.observedBytes();
        Estimate estimate = null;
        if (observed != null) {
            MemoryEstimate memory = JvmPresets.estimate(observed);
            // An unknown key renders as the key, as everywhere else in Sennard: a catalog that lacks it must not take the whole page down.
            Locale locale = RequestLocale.current();
            estimate = new Estimate(memory.estimatedGigabytes(), memory.observedBytes(), memory.preset().name(),
                    messages.getMessage("admin.jvm.estimate", new Object[] {estimateArguments(memory, locale)}, "admin.jvm.estimate", locale));
        }
        return new JvmView(presets, signedPreset == null ? null : signedPreset.name(), savedName,
                // What a start would use: the signed choice when there is one, else the installer's.
                signedPreset != null ? signedPreset.name() : savedName, signedPreset != null && (saved == null || saved.preset() != signedPreset), estimate);
    }

    /**
     * The named arguments of {@code admin.jvm.estimate}: the estimate in whole gigabytes, and the JVM's reading in gigabytes with one decimal whatever its value (8 GB
     * shows as 8.0). The catalog's pattern reads only {@code {name}} and {@code {name,number}}, so the one-decimal text is made here, with the request language's separator.
     */
    static Map<String, Object> estimateArguments(MemoryEstimate estimate, Locale locale) {
        DecimalFormat oneDecimal = new DecimalFormat("0.0", DecimalFormatSymbols.getInstance(locale));
        return Map.of("estimate", estimate.estimatedGigabytes(), "observed", oneDecimal.format(estimate.observedBytes() / (double) (1L << 30)));
    }

    /** The JEV tab; its usage is the recorded totals, and when they cannot be read the tab says there are none (null in the state, which the page shows as no usage). */
    private JevView jevView(ContextLayerService.SignedSettings signed) {
        return budget.view().map(usage -> jevView(signed, usage)).orElseGet(() -> jevView(signed, null));
    }

    private JevView jevView(ContextLayerService.SignedSettings signed, JevBudget.UsageView usage) {
        AdminLog.JevState state = signed.jev();
        Set<String> credentialNames = providers.all().stream().map(JevProvider::credentialName).collect(Collectors.toCollection(TreeSet::new));
        Optional<SkillTypes.Profile> profile = state == null ? Optional.empty() : providers.profile(state.model());
        // The judgment is the hook's and the classification's too (JevReadiness); the key is usable when it is registered and its stored value is the one that was signed.
        List<JevReadiness.Missing> missing = readiness.missing(state, name -> signed.jevKeys().stream().anyMatch(item -> item.name().equals(name) && item.stored()));
        Double limit = state == null ? null : state.limitUsd();
        List<JevPreset> presets = AdminLog.JEV_PRESET_IDS.stream()
                .map(presetId -> new JevPreset(presetId, AdminLog.JEV_PRESET_TOOLS.get(presetId), state == null || state.presets().getOrDefault(presetId, true))).toList();
        List<JevProviderView> providerViews = providers.all().stream().map(provider -> new JevProviderView(provider.providerId(), provider.label(), provider.endpoint(),
                provider.credentialName(), provider.maximumCostUsd(), provider.catalog(storedKey(provider), clock.millis()), provider.transmittedFields())).toList();
        double mostPerCall = providers.all().stream().mapToDouble(JevProvider::maximumCostUsd).max().orElse(0);
        Set<String> names = new TreeSet<>(credentialNames);
        signed.jevKeys().forEach(item -> names.add(item.name()));
        List<JevKeyRow> keys = new ArrayList<>();
        for (String name : names) {
            boolean credential = credentialNames.contains(name);
            keys.add(signed.jevKeys().stream().filter(candidate -> candidate.name().equals(name)).findFirst().map(item -> new JevKeyRow(name, credential, item.fingerprint(), item.setAt(), item.stored()))
                    .orElseGet(() -> new JevKeyRow(name, credential, null, null, false)));
        }
        return new JevView(state != null, state != null && state.enabled(), missing.isEmpty(), missing, presets, state == null ? null : state.model(),
                state == null ? null : state.fallbackModel(), state != null && state.transmissionAllowed(), providerViews, new JevBudgetView(limit, profile.map(SkillTypes.Profile::maximumCostUsd).orElse(mostPerCall)), usage,
                keys);
    }

    /** The stored key of a provider; null when there is none or the vault cannot be read. */
    private SecretValue storedKey(JevProvider provider) {
        try {
            return context.jevKey(provider.credentialName());
        } catch (RuntimeException unreadable) {
            // Boundary (secret vault adapter): the vault is an adapter bean whose failures its interface does not type; the page shows the key as not readable.
            LOG.warn("The admin page could not read the stored JEV key", unreadable);
            return null;
        }
    }

    /**
     * Whether the page is the settings database's: the business database is not ready and has never been (the section of the log before the link is open). The page then keeps
     * the passkeys and the link of the business database, and every signature goes to that section; once the business database is ready for the first time the section is closed
     * and the page is the business database's.
     */
    public boolean beforeLink() {
        return prepared.status().phase() != StorePhase.READY && logs.preLinkOpen();
    }

    /** The business database as the page shows it now. */
    private DatabaseLinkView link(boolean beforeLink) {
        StorePreparation.Status status = prepared.status();
        // The store is prepared once in a process (a pool is not swapped under the requests), so a link saved after the store started, while it is ready, is used by the next start.
        return links.find()
                .map(link -> new DatabaseLinkView(status.phase(), beforeLink, status.reason(),
                        new SavedLink(link.request().jdbcUrl(), link.request().accountName(), link.request().driverPath().toString(), link.request().passwordRef().toString(),
                                link.request().tls(), StoreValues.iso(link.savedAt().toEpochMilli())),
                        status.phase() == StorePhase.READY && link.savedAt().isAfter(status.startedAt())))
                .orElseGet(() -> DatabaseLinkView.unsaved(status.phase(), beforeLink, status.reason()));
    }

    State state() {
        if (beforeLink()) {
            return preLinkState();
        }
        ContextLayerService.AdminPageState admin = context.adminState();
        // The read has ended: a key the log revoked that the registry still holds as active (its revocation did not reach the registry) is revoked there now, by the registry's own write.
        revocations.reconcile(admin.revokedKeys());
        Sessions sessions = sessions(admin);
        return new State(SCHEMA_VERSION, AdminWebAuthn.RP_ID, admin.keys(), admin.bootstrap(), admin.resets(), admin.global(), admin.globalHistory(), admin.roles(), admin.bindings(),
                admin.proposals(), admin.failures(), admin.signedSettings(), admin.lastSeq(), ContextLayerService.GLOBAL_CONTEXT_MAX_BYTES, ContextLayerService.ROLE_CONTEXT_MAX_BYTES,
                AdminLog.ROLE_NAME_MAX_LENGTH, sessions.error(), sessions.views(), settings(admin.signedSettings()), rootApprovals.pending(), openRoots(), blockedPlans.list(), link(false));
    }

    /** The page before the link: the keys and the failed records of the settings database's log, the link, and nothing of the business database (it is not there). */
    private State preLinkState() {
        ContextLayerService.AdminPageState admin = preLink.adminState();
        revocations.reconcile(admin.revokedKeys());
        return new State(SCHEMA_VERSION, AdminWebAuthn.RP_ID, admin.keys(), admin.bootstrap(), admin.resets(), null, List.of(), List.of(), List.of(), List.of(), admin.failures(),
                admin.signedSettings(), admin.lastSeq(), ContextLayerService.GLOBAL_CONTEXT_MAX_BYTES, ContextLayerService.ROLE_CONTEXT_MAX_BYTES, AdminLog.ROLE_NAME_MAX_LENGTH, null,
                List.of(), null, List.of(), new OpenRoots(List.of()), List.of(), link(true));
    }

    /** The roots a person may release. */
    private OpenRoots openRoots() {
        return new OpenRoots(rootReleases.openRoots());
    }

    SkillUsage skillUsage(int days) {
        List<String> skills;
        Map<String, SkillCategory> categories;
        try {
            skills = inventory.observed().inventory().skills().stream().map(SkillTypes.Skill::skillId).toList();
            categories = inventory.categories();
        } catch (IOException | RuntimeException unreadable) {
            // Boundary (file I/O): the inventory is read from the skill files on disk, whose unreadable or malformed content must not take the page down.
            LOG.warn("The admin page could not read the skill inventory", unreadable);
            skills = List.of();
            categories = Map.of();
        }
        SkillUsageStore.Summary summary = skillUsage.summary(days, Instant.ofEpochMilli(clock.millis()));
        return new SkillUsage(SCHEMA_VERSION, days, SkillUsageStore.RETAIN_DAYS, skillGroups(skills, categories), summary.rows(), summary.hosts());
    }

    /** The starts of the last {@code days} days by kind ({@link WorkflowStatsView}). */
    WorkflowStatsView.Stats workflowStats(int days) {
        return WorkflowStatsView.of(SCHEMA_VERSION, days, workflowStats.stats(days, Instant.ofEpochMilli(clock.millis()), WorkflowStatsView.RECENT_LIMIT));
    }

    /** The skills in the two groups, each group titled in the request's language; a group with no skill is not listed. */
    private List<SkillGroup> skillGroups(List<String> skills, Map<String, SkillCategory> categories) {
        Locale locale = RequestLocale.current();
        List<SkillGroup> groups = new ArrayList<>();
        for (SkillCategory category : SkillCategory.values()) {
            List<String> members = skills.stream().filter(skill -> categories.get(skill) == category).toList();
            if (!members.isEmpty()) {
                groups.add(new SkillGroup(category, messages.getMessage(category.messageKey(), null, category.messageKey(), locale), members));
            }
        }
        return groups;
    }

    boolean rejectRootApproval(String id) {
        return rootApprovals.reject(id);
    }
}
