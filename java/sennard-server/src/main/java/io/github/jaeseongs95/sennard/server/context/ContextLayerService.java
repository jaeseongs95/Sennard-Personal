// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.context;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import java.text.Collator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.jaeseongs95.sennard.app.operation.OperationKey;
import io.github.jaeseongs95.sennard.app.operation.Receipted;
import io.github.jaeseongs95.sennard.app.operation.OperationReceipts;
import io.github.jaeseongs95.sennard.domain.Digests;
import io.github.jaeseongs95.sennard.domain.secret.SecretValue;
import io.github.jaeseongs95.sennard.server.approval.ApprovalSourceKind;
import io.github.jaeseongs95.sennard.server.approval.RootApprovalService;
import io.github.jaeseongs95.sennard.server.approval.RootReleaseService;
import io.github.jaeseongs95.sennard.server.workflow.BlockedPlanAdminService;
import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.domain.error.BindingCode;
import io.github.jaeseongs95.sennard.domain.error.BindingException;
import io.github.jaeseongs95.sennard.domain.error.ConflictCode;
import io.github.jaeseongs95.sennard.domain.error.ConflictException;
import io.github.jaeseongs95.sennard.domain.error.GateCode;
import io.github.jaeseongs95.sennard.domain.error.GateRefusedException;
import io.github.jaeseongs95.sennard.domain.error.InputCode;
import io.github.jaeseongs95.sennard.domain.error.IntegrityCode;
import io.github.jaeseongs95.sennard.domain.error.IntegrityException;
import io.github.jaeseongs95.sennard.domain.error.InvalidInputException;
import io.github.jaeseongs95.sennard.domain.error.NotFoundCode;
import io.github.jaeseongs95.sennard.domain.error.NotFoundException;
import io.github.jaeseongs95.sennard.domain.error.UnavailableException;
import io.github.jaeseongs95.sennard.server.message.MessageRef;
import io.github.jaeseongs95.sennard.server.message.SessionText;
import io.github.jaeseongs95.sennard.server.web.AdminPageAddress;
import io.github.jaeseongs95.sennard.server.web.RequestLocale;
import io.github.jaeseongs95.sennard.service.secret.SecretSealException;
import io.github.jaeseongs95.sennard.service.settings.DatabaseLinkRequest;
import io.github.jaeseongs95.sennard.service.settings.DatabaseLinkSettings;
import io.github.jaeseongs95.sennard.service.settings.PasskeyAlgorithm;
import io.github.jaeseongs95.sennard.service.settings.PasskeyCredential;
import io.github.jaeseongs95.sennard.service.settings.PasskeyRegistration;
import io.github.jaeseongs95.sennard.service.settings.PasskeyRegistry;
import io.github.jaeseongs95.sennard.service.settings.PasskeyState;
import io.github.jaeseongs95.sennard.service.store.ProposalState;
import io.github.jaeseongs95.sennard.service.store.StoreCode;
import io.github.jaeseongs95.sennard.service.store.StoreException;
import io.github.jaeseongs95.sennard.service.store.modules.ContextLayerStore;
import io.github.jaeseongs95.sennard.service.store.modules.ContextLayerStore.LogRecord;
import io.github.jaeseongs95.sennard.service.store.modules.ContextLayerStore.Proposal;
import io.github.jaeseongs95.sennard.service.store.modules.ContextLayerStore.RoleContext;
import io.github.jaeseongs95.sennard.service.store.modules.ContextLayerStore.RoleSave;
import io.github.jaeseongs95.sennard.service.store.modules.StoreValues;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.json.JsonMapper;

/**
 * The shared context policy every entry point uses: the MCP tools and the local admin page. What the user decides (the global context, the
 * role registry, the bindings, the passkeys) comes only from the signed admin log; an agent can only ask, with a proposal the user signs on
 * the page. The role a session may use comes only from its signed binding, never from the session board's self-written role line.
 */
@Service
public class ContextLayerService {
    private static final Logger LOG = LoggerFactory.getLogger(ContextLayerService.class);
    public static final int GLOBAL_CONTEXT_MAX_BYTES = 4000;
    public static final int ROLE_CONTEXT_MAX_BYTES = 2000;
    /** The catalog keys of the two lines that carry a layer's body, which a start with no room for them replaces (see the subagent start in the continuity module). */
    public static final String GLOBAL_BLOCK = "hooks.contextLayers.global";
    public static final String ROLE_BLOCK = "hooks.contextLayers.role";
    private static final long SEEN_REFRESH_MS = 3600_000L;

    public record GlobalView(String body, String stateToken, long revision, long bytes, int maxBytes, String updatedAt) {
    }

    public record RoleView(String body, String stateToken, String digest, long bytes, int maxBytes, String updatedAt, String updatedBy) {
    }

    public record Binding(String roleId, String role, String boundAt) {
    }

    public record LayerFailure(String layer, Long revision, String code) {
    }

    public record SharedContext(String schemaVersion, GlobalView global, int globalMaxBytes, Binding binding, RoleView role, int roleMaxBytes, List<LayerFailure> failures) {
    }

    public record ProposalResult(boolean pending, String proposalId, String adminUrl) {
    }

    public record Requester(String host, String sessionId, String actor) {
    }

    public record SessionKey(String host, String sessionId) {
    }

    public record PreparedIntent(String intent, String challenge, List<String> allowCredentials) {
    }

    /**
     * @param secret a JEV key's value, which travels only with its signed registration
     * @param publicKey a passkey's public key (the SPKI as base64url text), which travels only with its signed registration: the signed change names its fingerprint
     */
    public record CommitInput(String intent, AdminWebAuthn.Assertion assertion, String requestId, String proposalId, String origin, SecretValue secret, String publicKey) {
    }

    public record CommitResult(LogRecord record, boolean duplicate) {
    }

    public record KeyView(String credentialId, String label, String fingerprint, String addedAt, int alg, String lastUsedAt) {
    }

    public record GlobalHistoryItem(long revision, String body, String updatedAt, String credentialId, String keyLabel) {
    }

    public record RolePageView(String roleId, String name, String createdAt, RoleView context, String contextError, long bindingCount) {
    }

    public record BindingView(String host, String sessionId, String roleId, String boundAt, String lastUsedAt, boolean lapsed) {
    }

    /** {@code change} is the stored text of the proposal, shown as it is: an agent wrote it, and a proposal of an older spelling must still be listed (it is read as an {@link AdminChange} only when the user signs it). */
    public record ProposalView(String id, String target, JsonNode change, String baseHash, String host, String sessionId, String actor, String createdAt, ProposalState state, String decidedAt,
            boolean baseChanged) {
    }

    public record FailureView(String target, long seq, String code, AdminChange change, String layer, Long revision) {
    }

    public record JevKeyView(String name, String fingerprint, String setAt, boolean stored) {
    }

    public record SignedSettings(AdminLog.JevState jev, List<JevKeyView> jevKeys, AdminLog.EditExempt editExempt, AdminLog.JvmPresetState jvmPreset,
            AdminLog.ConvergenceLimitsState convergenceLimits, AdminLog.ResourceLimitsState resourceLimits, String resourceLimitsToken, AdminLog.AttachmentStorageState attachmentStorage, String attachmentStorageToken) {
    }

    /**
     * @param revokedKeys the keys the log revoked: the entrance that shows the state brings the registry in step with them ({@link PasskeyRevocations#reconcile}), so the read
     *        that makes this state writes nothing
     */
    public record AdminPageState(List<KeyView> keys, AdminLog.Bootstrap bootstrap, List<ContextLayerStore.Reset> resets, GlobalView global, List<GlobalHistoryItem> globalHistory,
            List<RolePageView> roles, List<BindingView> bindings, List<ProposalView> proposals, List<FailureView> failures, SignedSettings signedSettings, long lastSeq,
            List<String> revokedKeys) {
    }

    /** What this process has seen of the log; a target whose head falls below it was rolled back. ceiling: per process, so a restart forgets it. */
    private static final class Watermark {
        final Map<String, Long> highWater = new HashMap<>();
        Long lastResetId;
    }

    private final ContextLayerStore store;
    private final Clock clock;
    private final JsonMapper mapper;
    private final RootApprovalService rootApprovals;
    private final RootReleaseService rootReleases;
    private final BlockedPlanAdminService blockedPlans;
    private final ApplicationEventPublisher events;
    private final AdminLogVerifier verifier;
    private final ObjectWriter changeWriter;
    private final AdminPageAddress address;
    private final AdminCommitChecks commitChecks;
    private final AdminIssuedIntents issuedIntents;
    private final PasskeyRegistry passkeys;
    private final AdminLogSource logs;
    private final DatabaseLinkSettings links;
    private final LinkedDatabaseProbe linkedDatabase;
    private final ChildSessionRoots childRoots;
    private final Watermark watermark = new Watermark();
    /** The receipts of the three write tools of the layers: a request that comes again with its operationKey is answered with the first success (AGS3-D-0267). */
    private final OperationReceipts receipts;

    public ContextLayerService(ContextLayerStore store, JsonMapper mapper, RootApprovalService rootApprovals,
            RootReleaseService rootReleases, BlockedPlanAdminService blockedPlans, ApplicationEventPublisher events, AdminPageAddress address, Clock clock, AdminLogVerifier verifier,
            AdminCommitChecks commitChecks, AdminIssuedIntents issuedIntents, PasskeyRegistry passkeys,
            AdminLogSource logs, OperationReceipts receipts, DatabaseLinkSettings links, LinkedDatabaseProbe linkedDatabase, ChildSessionRoots childRoots) {
        this.receipts = receipts;
        this.links = links;
        this.linkedDatabase = linkedDatabase;
        this.childRoots = childRoots;
        this.logs = logs;
        this.passkeys = passkeys;
        this.commitChecks = commitChecks;
        this.issuedIntents = issuedIntents;
        this.store = store;
        this.events = events;
        this.rootApprovals = rootApprovals;
        this.rootReleases = rootReleases;
        this.blockedPlans = blockedPlans;
        this.clock = clock;
        this.mapper = mapper;
        this.verifier = verifier;
        // A proposal's change is stored with its kind in {@code op}, whatever variant it is.
        this.changeWriter = mapper.writerFor(AdminChange.class);
        this.address = address;
    }

    private String now() {
        return StoreValues.iso(clock.millis());
    }

    private static int utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    /** Whether the bound role has a body saved; the role save cue selects on the wire text. */
    private enum RoleBodyState {
        FILLED("filled"),
        EMPTY("empty");

        private final String wire;

        RoleBodyState(String wire) {
            this.wire = wire;
        }

        String wire() {
            return wire;
        }
    }

    /** A context layer an agent may write, with the size limit of its body; {@code label} is how a message names it. */
    private enum Layer {
        GLOBAL("global", GLOBAL_CONTEXT_MAX_BYTES),
        ROLE("role", ROLE_CONTEXT_MAX_BYTES);

        private final String label;
        private final int maxBytes;

        Layer(String label, int maxBytes) {
            this.label = label;
            this.maxBytes = maxBytes;
        }
    }

    private static String checkedBody(Object value, Layer layer) {
        if (!(value instanceof String body)) {
            throw new InvalidInputException(InputCode.CONTEXT_LAYER_BODY_INVALID);
        }
        int limit = layer.maxBytes;
        int bytes = utf8(body);
        if (bytes > limit) {
            throw new InvalidInputException(InputCode.CONTEXT_LAYER_TOO_LARGE, Map.of("layer", layer.label, "bytes", bytes, "limit", limit), Map.of("actualBytes", bytes, "maxBytes", limit));
        }
        return body;
    }

    /** {@code expectedStateToken} as sent: null is the first write, anything but a 1 to 200 character string is refused. */
    private static String checkedToken(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof String token) || token.isEmpty() || token.length() > 200) {
            throw new InvalidInputException(InputCode.CONTEXT_LAYER_TOKEN_INVALID);
        }
        return token;
    }

    private static ConflictException stale(String actualStateToken) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("actualStateToken", actualStateToken);
        return new ConflictException(ConflictCode.CONTEXT_LAYER_STATE_STALE, Map.of(), details);
    }

    /**
     * The store's save of a role body, where the loser of two first writes is answered as the stale conflict it is ({@code staleCode}: the code of the caller's own stale answer). The
     * statement that lost met the winner's primary key, and the transaction it failed in cannot read the winner's token any more, so this answer names none; the caller reads the
     * layer again.
     */
    private RoleSave saveRoleBody(String roleId, String expectedStateToken, String body, String updatedBy, String at, ConflictCode staleCode) {
        try {
            return store.saveRole(roleId, expectedStateToken, body, updatedBy, at);
        } catch (StoreException lost) {
            if (lost.code() != StoreCode.STORE_UNIQUE_VIOLATION) {
                throw lost;
            }
            throw new ConflictException(staleCode, Map.of(), lost);
        }
    }

    private static RoleView roleView(RoleContext record) {
        if (record == null) {
            return null;
        }
        // A stored body over its limit or off its digest is an integrity failure (another writer or damaged data).
        if (record.bytes() > ROLE_CONTEXT_MAX_BYTES || !ContextLayerStore.bodyDigest(record.body()).equals(record.digest())) {
            throw new IntegrityException(IntegrityCode.CONTEXT_LAYER_INTEGRITY_FAILED, Map.of(), Map.of("layer", "role"));
        }
        return new RoleView(record.body(), record.stateToken(), record.digest(), record.bytes(), ROLE_CONTEXT_MAX_BYTES, record.updatedAt(), record.updatedBy());
    }

    private static GlobalView globalView(AdminLog.State state) {
        AdminLog.GlobalState global = state.global;
        return global == null ? null : new GlobalView(global.body(), global.recordHash(), global.seq(), utf8(global.body()), GLOBAL_CONTEXT_MAX_BYTES, global.issuedAt());
    }

    /** The layer a failing chain belongs to: a binding's and a role body's chains are named after what they hold, so they are told by that prefix (the chain name is what the log stores). */
    static String failureLayer(String target) {
        return target.startsWith(AdminLog.BINDING_PREFIX) ? "binding" : target.startsWith(AdminLog.ROLE_BODY_PREFIX) ? "roles" : target;
    }

    static Long headSeq(AdminLog.State state, String target) {
        AdminLog.Head head = state.heads.get(target);
        return head == null ? null : head.seq();
    }

    /** The failures that change what this session works with: the keys, the global context, the roles, the settings the page edits and its own binding. */
    private static List<LayerFailure> sessionFailures(AdminLog.State state, String host, String sessionId) {
        Set<String> own = new LinkedHashSet<>(List.of(AdminLog.TARGET_KEYS, AdminLog.TARGET_GLOBAL, AdminLog.TARGET_ROLES, AdminLog.bindingTarget(host, sessionId)));
        own.addAll(AdminLog.SETTING_TARGETS);
        Map<String, AdminLog.Failure> byTarget = new LinkedHashMap<>();
        for (AdminLog.Failure failure : state.failures) {
            if (own.contains(failure.target())) {
                byTarget.putIfAbsent(failure.target(), failure);
            }
        }
        return byTarget.values().stream().map(failure -> new LayerFailure(failureLayer(failure.target()), headSeq(state, failure.target()), failure.code())).toList();
    }

    /**
     * The verified state; a target whose head went back below what this process saw is reported as rolled back. The mark is read before the
     * log, so a commit racing this read can only raise the log above it, never make it look regressed.
     */
    private AdminLog.State fold() {
        Watermark mark = watermark;
        Map<String, Long> seen;
        Long seenReset;
        synchronized (mark) {
            seen = Map.copyOf(mark.highWater);
            seenReset = mark.lastResetId;
        }
        List<LogRecord> records = logs.records();
        Long resetId = store.latestResetId();
        AdminLog.State state = verifier.fold(records);
        // A record still present but failing its check is reported by the fold (for example ADMIN_RECORD_ALTERED), not as a rollback.
        if (Objects.equals(resetId, seenReset)) {
            Map<String, Long> present = new HashMap<>();
            records.forEach(record -> present.merge(record.target(), record.seq(), Math::max));
            seen.forEach((target, seq) -> {
                if (present.getOrDefault(target, 0L) < seq) {
                    state.failures.add(new AdminLog.Failure(target, seq, ConflictCode.ADMIN_LOG_REGRESSED.name(), null));
                }
            });
        }
        synchronized (mark) {
            if (!Objects.equals(resetId, mark.lastResetId)) {
                mark.highWater.clear();
                mark.lastResetId = resetId;
            }
            state.heads.forEach((target, head) -> mark.highWater.merge(target, head.seq(), Math::max));
        }
        return state;
    }

    /** The single role access decision: the binding the user signed for this (root) session, while the session used its context in the last 30 days. */
    private Binding bindingFor(AdminLog.State state, String host, String sessionId) {
        AdminLog.BindingState binding = state.bindings.get(AdminLog.bindingKey(host, sessionId));
        AdminLog.RoleState role = binding == null ? null : state.roles.get(binding.roleId());
        if (role == null) {
            return null;
        }
        String seen = store.sessionSeen(host, sessionId);
        long lastUsed = Math.max(StoreValues.parseIso(binding.boundAt()), StoreValues.parseIso(seen == null ? binding.boundAt() : seen));
        return clock.millis() - lastUsed > ContextLayerStore.RETAIN_MS ? null : new Binding(role.roleId(), role.name(), binding.boundAt());
    }

    /** The signed role of each listed session, keyed by host NUL session. A caller treats a failure as "unknown" and never presents an unverified role. */
    @Transactional(readOnly = true)
    public Map<String, String> signedRoles(List<SessionKey> sessions) {
        AdminLog.State state = fold();
        Map<String, String> roles = new HashMap<>();
        for (SessionKey session : sessions) {
            Binding binding = bindingFor(state, session.host(), session.sessionId());
            if (binding != null) {
                roles.put(AdminLog.bindingKey(session.host(), session.sessionId()), binding.role());
            }
        }
        return roles;
    }

    /** {@code load_shared_context}. */
    @Transactional(readOnly = true)
    public SharedContext load(String host, String sessionId) {
        AdminLog.State state = fold();
        Binding binding = bindingFor(state, host, sessionId);
        return new SharedContext("1.0.0", globalView(state), GLOBAL_CONTEXT_MAX_BYTES, binding, binding == null ? null : roleView(store.readRole(binding.roleId())),
                ROLE_CONTEXT_MAX_BYTES, sessionFailures(state, host, sessionId));
    }

    private ProposalResult propose(Requester caller, AdminChange change, String baseHash) {
        if (caller.host() == null || caller.host().isEmpty() || caller.sessionId() == null || caller.sessionId().isEmpty()) {
            throw new InvalidInputException(InputCode.CONTEXT_SESSION_INVALID);
        }
        String id = "prop-" + UUID.randomUUID();
        store.addProposal(new Proposal(id, AdminLog.changeTarget(change), changeWriter.writeValueAsString(change), baseHash, caller.host(), caller.sessionId(), caller.actor(), now(), null, null));
        return new ProposalResult(true, id, address.origin());
    }

    /**
     * An agent's request to replace the global context; it applies only when the user signs it on the page. The log head read here is the base the proposal names, and a proposal whose
     * base is no longer the head is shown as outdated and refused at its commit, so a record appended between this read and the proposal's insert needs no lock. Two proposals of one
     * session for one target that insert together may both stay pending: both are shown, and the one the user signs makes the other outdated.
     */
    @Transactional
    public ProposalResult proposeGlobal(Requester caller, Object expectedStateToken, Object body, OperationKey operation) {
        return receipts.once(operation, ProposalResult.class, () -> proposeGlobal(caller, expectedStateToken, body)).value();
    }

    /** An agent's request to bind its session to a role, once per operationKey: a repeat does not add a second pending proposal. */
    @Transactional
    public ProposalResult proposeBinding(Requester caller, Object role, OperationKey operation) {
        return receipts.once(operation, ProposalResult.class, () -> proposeBinding(caller, role)).value();
    }

    /** A save of the whole body of the session's role, once per operationKey: the repeat of a save whose token has moved on is answered with the first save. */
    @Transactional
    public Receipted<RoleView> saveRole(String host, String sessionId, Object expectedStateToken, Object body, String actor, OperationKey operation) {
        return receipts.once(operation, RoleView.class, () -> saveRole(host, sessionId, expectedStateToken, body, actor));
    }

    // The three writes above are the entries; the three below are their work, with no transaction of their own (they run inside the entry's).

    private ProposalResult proposeGlobal(Requester caller, Object expectedStateToken, Object body) {
        String expected = checkedToken(expectedStateToken);
        String checked = checkedBody(body, Layer.GLOBAL);
        AdminLog.State state = fold();
        String current = state.global == null ? null : state.global.recordHash();
        if (!Objects.equals(current, expected)) {
            throw stale(current);
        }
        return propose(caller, new AdminChange.GlobalSet(checked), current);
    }

    /** An agent's request to bind its session to a registered role (or to unbind it with null); like {@link #proposeGlobal}, it names the head it read and takes no lock. */
    private ProposalResult proposeBinding(Requester caller, Object role) {
        String name = role instanceof String text ? AdminLog.normalizeRoleName(text) : null;
        if (role != null && name == null) {
            throw new InvalidInputException(InputCode.ROLE_NAME_INVALID, Map.of("limit", AdminLog.ROLE_NAME_MAX_LENGTH));
        }
        AdminLog.State state = fold();
        String roleId = null;
        if (name != null) {
            String key = AdminLog.roleKey(name);
            roleId = state.roles.values().stream().filter(candidate -> candidate.key().equals(key)).findFirst().map(AdminLog.RoleState::roleId).orElseThrow(() -> {
                Map<String, Object> details = new HashMap<>();
                details.put("adminUrl", address.origin());
                return new InvalidInputException(InputCode.ROLE_NOT_REGISTERED, Map.of("role", name, "adminUrl", address.origin()), details);
            });
        }
        AdminLog.Head head = state.heads.get(AdminLog.bindingTarget(caller.host(), caller.sessionId()));
        return propose(caller, new AdminChange.BindingSet(caller.host(), caller.sessionId(), roleId), head == null ? null : head.recordHash());
    }

    /** A session bound to a role rebuilds that role's whole body; any session of the role may, guarded by the token. */
    private RoleView saveRole(String host, String sessionId, Object expectedStateToken, Object body, String actor) {
        String expected = checkedToken(expectedStateToken);
        String checked = checkedBody(body, Layer.ROLE);
        // The body's own state token decides between two writers (one conditional UPDATE, or the primary key of a first write). A role deleted between the binding check and the write
        // can leave a body no log record names; no reader reaches it (a body is read only for a role the verified log holds), so that is not a state worth a lock.
        Binding binding = bindingFor(fold(), host, sessionId);
        if (binding == null) {
            Map<String, Object> details = new HashMap<>();
            details.put("reasonCode", "ROLE_NOT_BOUND");
            details.put("adminUrl", address.origin());
            throw new BindingException(BindingCode.CONTEXT_ROLE_UNBOUND, Map.of("adminUrl", address.origin()), details);
        }
        RoleSave result = saveRoleBody(binding.roleId(), expected, checked, actor, now(), ConflictCode.CONTEXT_LAYER_STATE_STALE);
        return switch (result) {
            case ContextLayerStore.RoleStale stale -> throw stale(stale.actualStateToken());
            case ContextLayerStore.RoleSaved saved -> roleView(saved.record());
        };
    }

    /** A page edit of a role body: within the limit and based on the body the user was looking at. */
    private void checkRoleBody(AdminChange.RoleBodySet change) {
        int bytes = utf8(change.body());
        if (bytes > ROLE_CONTEXT_MAX_BYTES) {
            throw changeInvalid(Map.of("reason", "TOO_LARGE", "bytes", bytes, "limit", ROLE_CONTEXT_MAX_BYTES));
        }
        RoleContext role = store.readRole(change.roleId());
        String actual = role == null ? null : role.stateToken();
        if (!Objects.equals(actual, change.expectedStateToken())) {
            Map<String, Object> details = new HashMap<>();
            details.put("actualStateToken", actual);
            throw new ConflictException(ConflictCode.ADMIN_TARGET_STALE, Map.of(), details);
        }
    }

    private static void checkGlobalSize(AdminChange.GlobalSet change) {
        int bytes = utf8(change.body());
        if (bytes > GLOBAL_CONTEXT_MAX_BYTES) {
            throw changeInvalid(Map.of("reason", "TOO_LARGE", "bytes", bytes, "limit", GLOBAL_CONTEXT_MAX_BYTES));
        }
    }

    /** What a change must satisfy beyond its own shape and the log's rules, because it reaches a store: a role body or the global context within its limit, a role body based on the current one. */
    private void checkReachesStore(AdminChange change) {
        switch (change) {
            case AdminChange.GlobalSet global -> checkGlobalSize(global);
            case AdminChange.RoleBodySet body -> checkRoleBody(body);
            // A link signed while the business database is ready: the shape is the one check of a link (the connection is tried when the signature is committed).
            case AdminChange.DatabaseLink link -> PreLinkAdminService.checkedLink(link);
            case AdminChange.KeyAdd _, AdminChange.KeyRevoke _, AdminChange.RoleAdd _, AdminChange.RoleRename _, AdminChange.RoleDelete _, AdminChange.BindingSet _, AdminChange.Ack _,
                    AdminChange.JevSet _, AdminChange.JevKeySet _, AdminChange.JevKeyDelete _, AdminChange.EditExemptSet _, AdminChange.JvmPresetSet _, AdminChange.ConvergenceLimitsSet _, AdminChange.ResourceLimitsSet _, AdminChange.AttachmentStorageSet _,
                    AdminChange.RootApprove _, AdminChange.RootRelease _, AdminChange.WorkflowPlanUnblock _ -> {
                // The other kinds write nothing to a store, or check what they need at the place that writes it.
            }
        }
    }

    /** Everything the page shows (viewing needs no passkey). */
    @Transactional(readOnly = true)
    public AdminPageState adminState() {
        AdminLog.State state = fold();
        Map<String, String> seen = store.allSessionSeen();
        long now = clock.millis();
        List<BindingView> bindings = state.bindings.values().stream().map(binding -> {
            String last = seen.getOrDefault(AdminLog.bindingKey(binding.host(), binding.sessionId()), binding.boundAt());
            long lastUsed = Math.max(StoreValues.parseIso(binding.boundAt()), StoreValues.parseIso(last));
            return new BindingView(binding.host(), binding.sessionId(), binding.roleId(), binding.boundAt(), StoreValues.iso(lastUsed), now - lastUsed > ContextLayerStore.RETAIN_MS);
        }).toList();
        Comparator<AdminLog.RoleState> byName = Comparator.comparing(AdminLog.RoleState::name, Collator.getInstance(RequestLocale.current())::compare);
        List<RolePageView> roles = state.roles.values().stream().sorted(byName).map(role -> {
            RoleView context = null;
            String contextError = null;
            try {
                context = roleView(store.readRole(role.roleId()));
            } catch (IntegrityException failed) {
                LOG.warn("The stored context of role {} failed its check", role.roleId(), failed);
                contextError = failed.code().name();
            }
            long count = bindings.stream().filter(binding -> binding.roleId().equals(role.roleId()) && !binding.lapsed()).count();
            return new RolePageView(role.roleId(), role.name(), role.createdAt(), context, contextError, count);
        }).toList();
        List<KeyView> keys = keyViews(state);
        List<GlobalHistoryItem> history = state.globalHistory.stream()
                .map(item -> new GlobalHistoryItem(item.seq(), item.body(), item.issuedAt(), item.credentialId(), state.keyLabels.get(item.credentialId()))).toList();
        List<ProposalView> proposals = store.pendingProposals().stream().map(proposal -> {
            AdminLog.Head head = state.heads.get(proposal.target());
            String headHash = head == null ? null : head.recordHash();
            return new ProposalView(proposal.id(), proposal.target(), mapper.readTree(proposal.change()), proposal.baseHash(), proposal.host(), proposal.sessionId(), proposal.actor(),
                    proposal.createdAt(), proposal.state(), proposal.decidedAt(), !Objects.equals(headHash, proposal.baseHash()));
        }).toList();
        List<FailureView> failures = state.failures.stream()
                .map(failure -> new FailureView(failure.target(), failure.seq(), failure.code(), failure.change(), failureLayer(failure.target()), headSeq(state, failure.target()))).toList();
        return new AdminPageState(keys, state.bootstrap, store.resets(), globalView(state), history, roles, bindings, proposals, failures, signedSettings(state), state.lastSeq,
                List.copyOf(state.revoked));
    }

    /** The keys the log lists, as the page shows them (the short fingerprint beside each). */
    static List<KeyView> keyViews(AdminLog.State state) {
        return state.keys.values().stream().map(key -> new KeyView(key.credentialId(), key.label(), AdminWebAuthn.displayFingerprint(key.fingerprint()), key.addedAt(), key.alg(),
                state.keyLastUsed.get(key.credentialId()))).toList();
    }

    private SignedSettings signedSettings(AdminLog.State state) {
        List<JevKeyView> keys = state.jevKeys.values().stream().map(key -> new JevKeyView(key.name(), key.fingerprint(), key.issuedAt(), keyUsable(state, key.name()))).toList();
        AdminLog.Head resourceLimitsHead = state.heads.get(AdminLog.TARGET_RESOURCE_LIMITS);
        return new SignedSettings(state.jev, keys, state.editExempt, state.jvmPreset, state.convergenceLimits, state.resourceLimits,
                resourceLimitsHead == null ? null : resourceLimitsHead.recordHash(), state.attachmentStorage,
                state.heads.containsKey(AdminLog.TARGET_ATTACHMENT_STORAGE) ? state.heads.get(AdminLog.TARGET_ATTACHMENT_STORAGE).recordHash() : null);
    }

    /** The verified state for the readers in this server (JEV). */
    @Transactional(readOnly = true)
    public AdminLog.State verifiedState() {
        return fold();
    }

    /** Whether a JEV key is registered under this name and its stored value is the one the last valid signed record names. */
    private boolean keyUsable(AdminLog.State state, String name) {
        AdminLog.JevKeyState signed = state.jevKeys.get(name);
        // The fingerprint alone is read: whether a key is usable does not open it (so it needs no vault).
        String stored = signed == null ? null : store.jevKeyFingerprint(name);
        return stored != null && stored.equals(signed.fingerprint());
    }

    /** {@link #keyUsable} on the verified state, for the readers outside this class (the classification's readiness). */
    @Transactional(readOnly = true)
    public boolean jevKeyUsable(String name) {
        return keyUsable(fold(), name);
    }

    /** The JEV key under this name: opened only when the stored value matches the fingerprint of the last valid signed record; null when none is usable. */
    @Transactional(readOnly = true)
    public SecretValue jevKey(String name) {
        AdminLog.State state = fold();
        if (!keyUsable(state, name)) {
            return null;
        }
        // The store opens the value (the secret column of the row is declared {@code @Encrypted}, bound to the key's name and fingerprint); this method never sees the sealed form.
        return store.jevKey(name).secret();
    }

    /**
     * Builds the intent the passkey signs, checked first so a change that would be refused is refused before the prompt. It writes no row (the intent it issues is held in this
     * process's memory), so it reads the log and takes no lock: a record appended meanwhile is found by the chain head the intent names, when the intent is committed.
     */
    @Transactional(readOnly = true)
    public PreparedIntent prepareIntent(JsonNode requested, boolean bootstrap, boolean baseHashGiven, String baseHash) {
        // The shape of the change is its own: a change that has none is refused before the log is read.
        AdminChange change = verifier.bindChange(requested);
        String now = now();
        AdminLog.State state = fold();
        // A log that fails its integrity takes neither the first key nor a new signature (only the admin reset starts it again).
        AdminLog.requireIntact(state);
        if (bootstrap && !state.keys.isEmpty()) {
            throw new ConflictException(ConflictCode.ADMIN_ALREADY_BOOTSTRAPPED, Map.of(), bootstrapDetails(state));
        }
        if (!bootstrap && state.keys.isEmpty()) {
            throw new GateRefusedException(GateCode.ADMIN_KEY_NOT_REGISTERED);
        }
        AdminLog.requireSinglePasskey(state, change);
        checkReachesStore(change);
        if (change instanceof AdminChange.RootApprove approve) {
            checkRootApproval(approve);
        }
        if (change instanceof AdminChange.RootRelease release) {
            checkRootRelease(release);
        }
        if (change instanceof AdminChange.WorkflowPlanUnblock unblock) {
            blockedPlans.requireCurrent(unblock.workspaceId(), unblock.kind(), unblock.planDigest(), unblock.recordId());
        }
        // The log has no key yet exactly when this is the bootstrap (checked above): only a key registering itself can sign then.
        // A key signs only while the log lists it and the registry holds it as active; a log that lists keys the registry cannot vouch for (the settings database was lost) has no
        // key that can sign, and the admin reset is what starts it again.
        List<String> usable = bootstrap ? List.of() : usableKeys(state);
        if (!bootstrap && usable.isEmpty()) {
            throw new GateRefusedException(GateCode.ADMIN_KEY_UNKNOWN);
        }
        String signer = !bootstrap ? usable.getFirst() : change instanceof AdminChange.KeyAdd add ? add.credentialId() : null;
        AdminLog.checkChange(state, change, signer);
        AdminLog.Head current = state.heads.get(AdminLog.changeTarget(change));
        String head = current == null ? null : current.recordHash();
        // The page names the revision its edit started from; a target that moved since is a conflict, not a silent overwrite.
        if (baseHashGiven && !Objects.equals(baseHash, head)) {
            Map<String, Object> details = new HashMap<>();
            details.put("actualStateToken", head);
            throw new ConflictException(ConflictCode.ADMIN_TARGET_STALE, Map.of(), details);
        }
        String signed = verifier.buildIntent(change, head, now);
        // Only an intent this process prepared is committed later (see AdminIssuedIntents).
        issuedIntents.issue(signed);
        return new PreparedIntent(signed, AdminWebAuthn.intentChallenge(signed), bootstrap ? List.of(signer) : new ArrayList<>(usable));
    }

    /** The keys the log lists that the registry holds as active, in the order the log registered them. */
    private List<String> usableKeys(AdminLog.State state) {
        AdminLogVerifier.KeyBook book = verifier.keys();
        return state.keys.keySet().stream().filter(id -> book.find(id).map(key -> key.state() == PasskeyState.ACTIVE).orElse(false)).toList();
    }

    private static Map<String, Object> bootstrapDetails(AdminLog.State state) {
        Map<String, Object> details = new HashMap<>();
        details.put("bootstrap", state.bootstrap);
        return details;
    }

    /**
     * Appends one signed record. The whole check (freshness, chain head, signer, signature, the change's rules) and the append share one
     * transaction, with no lock of its own. Two commits that check the same log at the same moment (two passkey signatures in one instant) are a limit this accepts: the later
     * record then fails the chain head check when the log is next read and is shown as a failed record, which the user signs again. A request ID signs one record (a unique key). A repeated request ID returns the record already stored,
     * so a page that lost the response asks again instead of signing twice.
     */
    @Transactional
    public CommitResult commit(CommitInput input) {
        long now = clock.millis();
        // The intent is read once: what it asks for decides the secret that goes with it and every check below.
        AdminLog.Intent intent = verifier.parseIntent(input.intent());
        AdminChange change = intent.change();
        checkSecret(change, input.secret());
        PasskeyRegistration passkey = passkeyOf(change, input.publicKey());
        // A request that was already committed has nothing to write: it is answered from what is stored.
        Optional<LogRecord> existing = logs.byRequest(input.requestId());
        if (existing.isPresent()) {
            return new CommitResult(existing.get(), true);
        }
        commitChecks.checkFresh(input.intent(), intent, input.assertion(), input.origin(), now);
        AdminLog.State state = fold();
        AdminLog.requireIntact(state);
        if (change instanceof AdminChange.KeyAdd && intent.prevTargetHash() == null && !state.keys.isEmpty()) {
            throw new ConflictException(ConflictCode.ADMIN_ALREADY_BOOTSTRAPPED, Map.of(), bootstrapDetails(state));
        }
        AdminLog.requireSinglePasskey(state, change);
        checkReachesStore(change);
        Proposal proposal = input.proposalId() == null ? null : store.proposal(input.proposalId());
        if (input.proposalId() != null && (proposal == null || proposal.state() != ProposalState.PENDING || !proposal.target().equals(intent.target()))) {
            throw new NotFoundException(NotFoundCode.ADMIN_PROPOSAL_NOT_FOUND);
        }
        AdminWebAuthn.Assertion assertion = input.assertion();
        String at = StoreValues.iso(now);
        String hash = verifier.recordHash(input.intent(), assertion.credentialId(), assertion.authenticatorData(), assertion.clientDataJson(), assertion.signature());
        LogRecord record = new LogRecord(state.lastSeq + 1, intent.target(), input.intent(), assertion.credentialId(), assertion.authenticatorData(), assertion.clientDataJson(),
                assertion.signature(), hash, at);
        // The signature is checked with the key of the registry (the key being registered, for the first key, which signs its own registration), and the signer must be active there.
        AdminLogVerifier.KeyBook keys = verifier.keys();
        if (passkey != null) {
            keys = keys.with(new PasskeyCredential(passkey, PasskeyState.ACTIVE, clock.instant(), null));
        }
        verifier.verifyIntent(state, record, intent, keys);
        verifier.requireActive(keys, assertion.credentialId());
        if (passkey != null) {
            // The registry is written before the record: the settings database and the business database are not one transaction, and a key with no record in the log is one
            // nothing can sign with (the log decides which keys are registered), so a record that then fails leaves a key that does no harm, and the same registration is taken again
            // (the registry's register is idempotent: the same key bytes are answered with the held key, other bytes are refused).
            // This call is the settings database's own use case (its service method and its transaction, not this one) and stays here, not in the entrance, because the key may be
            // registered only once this method has checked the freshness, the chain and the signature: at the entrance an unsigned request could plant a key under any credential id.
            passkeys.register(passkey);
        }
        LogRecord appended = logs.append(record, input.requestId());
        switch (change) {
            case AdminChange.RoleDelete delete -> store.deleteRoleBody(delete.roleId());
            case AdminChange.RoleBodySet body -> {
                // The check above read the token; a role's own save (which takes no lock) may have replaced the body since, and then this write changes nothing and the record rolls back.
                if (saveRoleBody(body.roleId(), body.expectedStateToken(), body.body(), "admin-page", at, ConflictCode.ADMIN_TARGET_STALE) instanceof ContextLayerStore.RoleStale moved) {
                    Map<String, Object> details = new HashMap<>();
                    details.put("actualStateToken", moved.actualStateToken());
                    throw new ConflictException(ConflictCode.ADMIN_TARGET_STALE, Map.of(), details);
                }
            }
            case AdminChange.JevKeySet key -> storeJevKey(key, input.secret(), at);
            case AdminChange.JevKeyDelete delete -> store.deleteJevKey(delete.name());
            case AdminChange.RootApprove approve -> {
                checkRootApproval(approve);
                // The approval and the signed record that authorizes it commit together.
                if (!rootApprovals.approve(approve.approvalId(), approve.scopeDigest(),
                        new RootApprovalService.Source(ApprovalSourceKind.ADMIN_PAGE, appended.recordHash(), appended.credentialId()))) {
                    throw new NotFoundException(NotFoundCode.ADMIN_PROPOSAL_NOT_FOUND);
                }
            }
            case AdminChange.RootRelease release -> {
                // The release and the signed record that authorizes it commit together; a release that cannot be made now rolls the record back.
                RootReleaseService.Result result = rootReleases.release(release.rootId(), release.expectedRevision(), release.reason(),
                        new RootApprovalService.Source(ApprovalSourceKind.ADMIN_PAGE, appended.recordHash(), appended.credentialId()));
                if (result instanceof RootReleaseService.Result.Refused refused) {
                    throw rootReleaseRefused(refused.refusal());
                }
            }
            case AdminChange.WorkflowPlanUnblock unblock -> blockedPlans.unblock(unblock.workspaceId(), unblock.kind(), unblock.planDigest(), unblock.recordId());
            // The launcher's file is written after this transaction commits (the listener's phase), so a rolled-back record never changes a start.
            case AdminChange.JvmPresetSet preset -> events.publishEvent(new JvmPresetSigned(preset.preset()));
            // The registry revokes the key after this transaction commits (PasskeyRevocations): the last-key rule was decided by the log's chain above.
            case AdminChange.KeyRevoke revoke -> events.publishEvent(new PasskeyRevoked(revoke.credentialId()));
            case AdminChange.KeyAdd _, AdminChange.GlobalSet _, AdminChange.RoleAdd _, AdminChange.RoleRename _, AdminChange.BindingSet _, AdminChange.Ack _,
                    AdminChange.JevSet _, AdminChange.EditExemptSet _, AdminChange.ConvergenceLimitsSet _, AdminChange.ResourceLimitsSet _, AdminChange.AttachmentStorageSet _ -> {
                // Every other change lives in the signed log alone.
            }
            case AdminChange.DatabaseLink link -> {
                // The business database is ready and the store is prepared once in a process: the link the user signs now is used by the next start. The database it names is tried
                // first (connected, its product checked, its tables prepared) so a restart is not spent on one that cannot start the store; a link that fails is not kept and the
                // record rolls back with it. The link is written last: the settings database is not this transaction, and a link whose record then fails to commit is one the passkey
                // signed in this very request (the signature is checked above), so the failure leaves no unsigned change, only a link without its record in the log.
                DatabaseLinkRequest request = PreLinkAdminService.checkedLink(link);
                linkedDatabase.prepare(request);
                links.save(request);
            }
        }
        if (proposal != null) {
            store.decideProposal(proposal.id(), ProposalState.APPLIED, at);
        }
        // Spent when this transaction commits (the listener's phase), not here: a commit that is refused above or fails when the database commits leaves the intent for the page to
        // commit again. A second commit of the same text in the instant between the database's commit and the spending is refused by the chain head the text stands on.
        events.publishEvent(new AdminIntentSpent(input.intent()));
        return new CommitResult(appended, false);
    }

    /** A signature releases only the root as the page showed it: a root that is gone, closed since, or changed since is refused before the passkey is asked. */
    private void checkRootRelease(AdminChange.RootRelease change) {
        rootReleases.refusal(change.rootId(), change.expectedRevision()).ifPresent(refusal -> {
            throw rootReleaseRefused(refusal);
        });
    }

    /** The refusal of a release as the page's code: the category (and so the status) of each is its own. */
    private static SennardException rootReleaseRefused(RootReleaseService.Refusal refusal) {
        return switch (refusal) {
            case ROOT_NOT_FOUND -> new NotFoundException(NotFoundCode.ADMIN_ROOT_NOT_FOUND);
            case ROOT_NOT_ACTIVE -> new ConflictException(ConflictCode.ADMIN_ROOT_NOT_ACTIVE);
            case ROOT_REVISION_STALE -> new ConflictException(ConflictCode.ADMIN_ROOT_STALE);
        };
    }

    private static InvalidInputException changeInvalid(Map<String, Object> details) {
        return new InvalidInputException(InputCode.ADMIN_CHANGE_INVALID, Map.of(), details);
    }

    /** A signature approves only the scope the page showed: a request that is gone, or whose scope moved, is refused. */
    private void checkRootApproval(AdminChange.RootApprove change) {
        if (rootApprovals.pending().stream().noneMatch(pending -> pending.approvalId().equals(change.approvalId()) && pending.scopeDigest().equals(change.scopeDigest()))) {
            throw new NotFoundException(NotFoundCode.ADMIN_PROPOSAL_NOT_FOUND);
        }
    }

    /**
     * Marks the session as still in use, which keeps its role binding from lapsing after 30 days. A step refreshes only a stamp that has aged,
     * so a long run writes once an hour, not once a step.
     *
     * @param refresh the session started or took a user turn: write now
     */
    @Transactional
    public void recordSessionUse(String host, String sessionId, boolean refresh) {
        String seen = refresh ? null : store.sessionSeen(host, sessionId);
        if (refresh || seen == null || clock.millis() - StoreValues.parseIso(seen) > SEEN_REFRESH_MS) {
            store.touchSession(host, sessionId, now());
        }
    }

    /**
     * The layers a hook adds for one actor. Each layer is read on its own: a failing layer is announced and the others are still given, and a
     * verification failure is announced once per change with the revision in use.
     *
     * @param full a context start, which gets every non-empty layer; other hooks carry notices and the first role of a newly linked child
     * @param turnStart the session's own user turn, which also gets the cue that makes the role save happen (the JEV advice is not asked of the agent: the server asks JEV itself, see {@code JevAdviceHook})
     * @param parentUnnamed the host's hooks do not name the parent of the conversation they run under ({@code HostCallerAdapter#hooksMarkSubagents} is false), so a conversation
     *            that has no binding and no recorded root, at the first hook this actor gets, is told how to have its parent found: by one tool call, whose metadata names both
     */
    @Transactional
    public List<MessageRef> hookText(String host, String sessionId, String actorId, boolean full, boolean turnStart, boolean parentUnnamed) {
        List<MessageRef> lines = new ArrayList<>();
        AdminLog.State state;
        try {
            state = fold();
        } catch (DataAccessException | JacksonException | IllegalStateException unreadable) {
            LOG.warn("The admin log could not be read for the hook of {}", host, unreadable);
            lines.add(MessageRef.of("hooks.contextLayers.unreadable", "layer", "admin-log"));
            return lines;
        }
        ContextLayerStore.Delivery stored = store.delivered(actorId);
        ContextLayerStore.Delivery before = full ? null : stored;
        boolean changed = !full && before != null;
        String beforeRole = before == null ? null : before.role();
        String beforeRoleToken = before == null ? null : before.roleToken();
        // Restoring the body at a context start does not mean the actor is seeing the global layer for the first time.
        String beforeGlobal = stored == null ? null : stored.globalToken();
        // A child a host runs under its own conversation id has the binding of its root: the hook carries only the child's id.
        var recordedRoot = childRoots.rootOf(host, sessionId);
        String bindingSession = recordedRoot.orElse(sessionId);
        List<LayerFailure> failures = sessionFailures(state, host, bindingSession);
        String failureMark = failures.stream().map(failure -> failure.layer() + ":" + failure.code() + ":" + (failure.revision() == null ? "-" : failure.revision())).collect(Collectors.joining(","));
        GlobalView global = globalView(state);
        String previousGlobalToken = tokenBeforeMark(beforeGlobal);
        boolean hasGlobalBody = global != null && !global.body().isEmpty();
        String deliveredGlobalToken = full || !hasGlobalBody ? (global == null ? "" : global.stateToken()) : Objects.toString(previousGlobalToken, "");
        String globalToken = deliveredGlobalToken + "#" + failureMark;
        if (full || !globalToken.equals(beforeGlobal)) {
            addGlobalLines(lines, failures, global, previousGlobalToken, full, stored != null);
        }
        String roleName = null;
        String roleToken = null;
        MessageRef roleSaveCue = null;
        try {
            Binding binding = bindingFor(state, host, bindingSession);
            RoleView role = binding == null ? null : roleView(store.readRole(binding.roleId()));
            roleName = binding == null ? null : binding.role();
            boolean sameRole = Objects.equals(beforeRole, roleName);
            boolean hasRoleBody = role != null && !role.body().isEmpty();
            boolean firstLinkedRole = parentUnnamed && recordedRoot.isPresent() && beforeRole == null;
            boolean sendRoleBody = full || firstLinkedRole;
            roleToken = role != null && (sendRoleBody || !hasRoleBody) ? role.stateToken() : sameRole ? beforeRoleToken : null;
            if (binding != null && turnStart) {
                roleSaveCue = MessageRef.of("hooks.contextLayers.roleSaveCue", "role", binding.role(), "state", (role != null && !role.body().isEmpty() ? RoleBodyState.FILLED : RoleBodyState.EMPTY).wire());
            }
            if (binding != null && (sendRoleBody || !sameRole || !Objects.equals(beforeRoleToken, roleToken))) {
                addRoleLine(lines, binding, role, sendRoleBody, changed, sameRole && beforeRoleToken != null && !beforeRoleToken.isEmpty());
            } else if (binding == null && changed && beforeRole != null && !beforeRole.isEmpty()) {
                lines.add(MessageRef.of("hooks.contextLayers.roleUnbound", "role", beforeRole));
            }
            if (parentUnnamed && binding == null && recordedRoot.isEmpty() && stored == null) {
                // A hook of this host cannot say whose child a conversation is, and the conversation may be one: only its own tool call can (HostSessionResolver links it), so it is asked to make one.
                lines.add(MessageRef.of("hooks.contextLayers.parentUnknown"));
            }
        } catch (DataAccessException | JacksonException | IllegalStateException | IntegrityException unreadable) {
            LOG.warn("The role context could not be read for the hook of {}", host, unreadable);
            lines.add(MessageRef.of("hooks.contextLayers.unreadable", "layer", "role"));
            roleName = stored == null ? null : stored.role();
            roleToken = stored == null ? null : stored.roleToken();
        }
        if (roleSaveCue != null) {
            lines.add(roleSaveCue);
        }
        if (full || !globalToken.equals(beforeGlobal) || !Objects.equals(beforeRole, roleName) || !Objects.equals(beforeRoleToken, roleToken)) {
            store.recordDelivery(actorId, new ContextLayerStore.Delivery(globalToken, roleName, roleToken), now());
        }
        return lines;
    }

    private void addGlobalLines(List<MessageRef> lines, List<LayerFailure> failures, GlobalView global, String beforeToken, boolean full, boolean receivedBefore) {
        for (LayerFailure failure : failures) {
            lines.add(MessageRef.of("hooks.contextLayers.verifyFailed", "layer", failure.layer(), "revision", failure.revision() == null ? "none" : String.valueOf(failure.revision()),
                    "reason", failure.code(), "adminUrl", address.origin()));
        }
        String stateToken = global == null ? "" : global.stateToken();
        boolean revisionChanged = !stateToken.equals(beforeToken);
        boolean verifiedChange = receivedBefore && revisionChanged && failures.stream()
                .noneMatch(failure -> AdminLog.TARGET_GLOBAL.equals(failure.layer()) || AdminLog.TARGET_KEYS.equals(failure.layer()));
        if (global != null && !global.body().isEmpty()) {
            if (full) {
                lines.add(MessageRef.of(GLOBAL_BLOCK, "stateToken", stateToken, "changed", verifiedChange ? "yes" : "no", "body", global.body()));
            }
        } else if (verifiedChange && beforeToken != null && !beforeToken.isEmpty()) {
            // Only an actor that may still hold the earlier body needs the clearing notice.
            lines.add(MessageRef.of("hooks.contextLayers.cleared", "layer", "global", "role", ""));
        }
    }

    /** The binding is the user's assignment and is given even while its role has no body yet. */
    private static void addRoleLine(List<MessageRef> lines, Binding binding, RoleView role, boolean sendBody, boolean changed, boolean sameRoleHadBody) {
        if (sendBody && role != null && !role.body().isEmpty()) {
            lines.add(MessageRef.of(ROLE_BLOCK, "role", binding.role(), "stateToken", role.stateToken(), "changed", changed ? "yes" : "no", "body", SessionText.quoted(role.body())));
        } else if (changed && sameRoleHadBody) {
            lines.add(MessageRef.of("hooks.contextLayers.cleared", "layer", "role", "role", binding.role()));
        } else {
            lines.add(MessageRef.of("hooks.contextLayers.roleBound", "role", binding.role(), "changed", changed ? "yes" : "no"));
        }
    }

    /** The delivered global token without the failures it was delivered with. */
    private static String tokenBeforeMark(String delivered) {
        if (delivered == null) {
            return null;
        }
        int mark = delivered.indexOf('#');
        return mark < 0 ? delivered : delivered.substring(0, mark);
    }

    /** A key registration carries the value beside the signed intent: it must hash to the fingerprint the passkey signed. Any other change carries none. */
    private static void checkSecret(AdminChange change, SecretValue secret) {
        if (!(change instanceof AdminChange.JevKeySet key)) {
            if (secret != null) {
                throw changeInvalid(Map.of("reason", "SECRET_UNEXPECTED"));
            }
            return;
        }
        if (secret == null) {
            throw changeInvalid(Map.of("reason", "JEV_KEY_FINGERPRINT_MISMATCH"));
        }
        byte[] plaintext = secret.revealBytes();
        try {
            if (!("sha256:" + Digests.sha256Hex(plaintext)).equals(key.fingerprint())) {
                throw changeInvalid(Map.of("reason", "JEV_KEY_FINGERPRINT_MISMATCH"));
            }
        } finally {
            SecretValue.clear(plaintext);
        }
    }

    /**
     * Stores the key. The store seals the value ({@code @Encrypted} on the secret of its row), so no sealing is done here; what is handled here is the vault's refusal, which is the
     * answer the page shows for a key that cannot be stored encrypted. Boundary (secret vault adapter): the vault is an adapter bean whose failures are not typed by its interface; the cause stays on the refusal.
     */
    private void storeJevKey(AdminChange.JevKeySet key, SecretValue secret, String at) {
        try {
            store.putJevKey(new ContextLayerStore.JevKey(key.name(), key.fingerprint(), secret, at));
        } catch (SecretSealException | UnavailableException failed) {
            throw new InvalidInputException(InputCode.ADMIN_CHANGE_INVALID, Map.of(), Map.of("reason", "JEV_KEY_VAULT_UNAVAILABLE"), failed);
        }
    }

    /**
     * The key a signed {@code KEY_ADD} registers, from the public key that came with the commit: the key must be the one the record's fingerprint names and must import as the
     * record's algorithm. Null for a change that registers no key, which carries none.
     */
    static PasskeyRegistration passkeyOf(AdminChange change, String publicKey) {
        if (!(change instanceof AdminChange.KeyAdd add)) {
            if (publicKey != null) {
                throw changeInvalid(Map.of("reason", "PUBLIC_KEY_UNEXPECTED"));
            }
            return null;
        }
        if (publicKey == null || !AdminWebAuthn.fingerprint(publicKey).equals(add.fingerprint())) {
            throw changeInvalid(Map.of("reason", "KEY_FINGERPRINT_MISMATCH"));
        }
        AdminWebAuthn.importKey(publicKey, add.alg());
        // The log allows an empty label and the registry keeps none that is blank: such a key is kept under its credential id.
        return new PasskeyRegistration(add.credentialId(), publicKey, PasskeyAlgorithm.ofCose(add.alg()), add.label().isBlank() ? add.credentialId() : add.label());
    }

    @Transactional(readOnly = true)
    public Optional<LogRecord> recordByRequest(String requestId) {
        return logs.byRequest(requestId);
    }

    @Transactional
    public void rejectProposal(String id) {
        if (!store.decideProposal(id, ProposalState.REJECTED, now())) {
            throw new NotFoundException(NotFoundCode.ADMIN_PROPOSAL_NOT_FOUND);
        }
    }
}
