// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.context;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.domain.error.ConflictCode;
import io.github.jaeseongs95.sennard.domain.error.ConflictException;
import io.github.jaeseongs95.sennard.domain.error.GateCode;
import io.github.jaeseongs95.sennard.domain.error.GateRefusedException;
import io.github.jaeseongs95.sennard.domain.error.InputCode;
import io.github.jaeseongs95.sennard.domain.error.InvalidInputException;
import io.github.jaeseongs95.sennard.domain.jvm.JvmPreset;
import io.github.jaeseongs95.sennard.server.context.AdminConstraints.OffsetDateTimeText;
import io.github.jaeseongs95.sennard.server.hook.ShellPaths;
import io.github.jaeseongs95.sennard.server.limits.ResourceLimits;
import io.github.jaeseongs95.sennard.server.workflow.ConvergenceLimits;
import io.github.jaeseongs95.sennard.service.settings.PasskeyRegistry;
import io.github.jaeseongs95.sennard.service.store.modules.ContextLayerStore.LogRecord;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.json.JsonMapper;

/**
 * The signed admin log: the only source of what the user decided on the admin page. Each record holds one canonical intent the user's
 * passkey signed. Records chain per target, so unrelated targets never contend. Reading folds the whole log and keeps, per target, the
 * last state whose chain and signatures are valid; a record that fails is reported and never applied. The intent is read as a record
 * ({@link Intent}, its change an {@link AdminChange}) from the exact canonical text the signature covers; reading, building and verifying
 * intents is {@link AdminLogVerifier}'s, this class holds what the log folds to and the rules that need no JSON.
 */
public final class AdminLog {
    public static final String DOMAIN = "sennard.admin";
    public static final int INTENT_VERSION = 1;
    public static final long INTENT_TTL_MS = 10 * 60_000L;
    public static final int ROLE_NAME_MAX_LENGTH = 100;
    public static final int KEY_LABEL_MAX_LENGTH = 60;
    public static final int EDIT_EXEMPT_MAX_PATHS = 50;
    public static final int EDIT_EXEMPT_PATH_MAX_LENGTH = 1024;
    public static final List<String> JEV_PRESET_IDS = List.of("skill-classification");
    public static final Map<String, String> JEV_PRESET_TOOLS = Map.of("skill-classification", "classify_skills");
    /** The chains whose name is fixed; a role body, a binding and a root approval each have a chain named after what they hold. */
    public static final String TARGET_KEYS = "keys";
    public static final String TARGET_GLOBAL = "global";
    public static final String TARGET_ROLES = "roles";
    public static final String TARGET_JEV = "jev";
    public static final String TARGET_JEV_KEYS = "jevKeys";
    public static final String TARGET_EDIT_EXEMPT = "editExempt";
    public static final String TARGET_JVM_PRESET = "jvmPreset";
    public static final String TARGET_CONVERGENCE_LIMITS = "convergenceLimits";
    public static final String TARGET_RESOURCE_LIMITS = "resourceLimits";
    public static final String TARGET_ATTACHMENT_STORAGE = "attachmentStorage";
    /** The chain of the link of the business database: its first records are in the section of the log before the business database ({@code PreLinkAdminRecords}), a later one in the business log. */
    public static final String TARGET_DATABASE = "database";
    public static final Set<String> SETTING_TARGETS = Set.of(TARGET_JEV, TARGET_JEV_KEYS, TARGET_EDIT_EXEMPT, TARGET_JVM_PRESET, TARGET_CONVERGENCE_LIMITS,
            TARGET_RESOURCE_LIMITS, TARGET_ATTACHMENT_STORAGE, TARGET_DATABASE);
    public static final String ROLE_BODY_PREFIX = "roleBody:";
    public static final String BINDING_PREFIX = "binding:";
    public static final String ROOT_APPROVAL_PREFIX = "rootApproval:";
    public static final String ROOT_RELEASE_PREFIX = "rootRelease:";
    /** The expression a constraint takes for {@link #DOMAIN} (a constraint needs a constant, and the dot is a metacharacter). */
    static final String DOMAIN_REGEX = "sennard\\.admin";

    private static final Set<String> FIXED_TARGETS = Set.of(TARGET_KEYS, TARGET_GLOBAL, TARGET_ROLES, TARGET_JEV, TARGET_JEV_KEYS, TARGET_EDIT_EXEMPT, TARGET_JVM_PRESET, TARGET_CONVERGENCE_LIMITS,
            TARGET_RESOURCE_LIMITS, TARGET_ATTACHMENT_STORAGE, TARGET_DATABASE);
    private static final Pattern ROLE_BODY_TARGET = Pattern.compile(ROLE_BODY_PREFIX + AdminChange.ID);
    private static final Pattern ROOT_APPROVAL_TARGET = Pattern.compile(ROOT_APPROVAL_PREFIX + AdminChange.ID);
    private static final Pattern ROOT_RELEASE_TARGET = Pattern.compile(ROOT_RELEASE_PREFIX + AdminChange.ID);
    private static final Pattern BINDING_TARGET = Pattern.compile(BINDING_PREFIX + "([^:]+):([^:]+)");
    private static final Logger LOG = LoggerFactory.getLogger(AdminLog.class);

    private AdminLog() {
    }

    /** A key the log registered. {@code fingerprint} is the signed one ({@code sha256:...}); the key's bytes are in the passkey registry. */
    public record KeyState(String credentialId, int alg, String label, String fingerprint, String addedAt, long addedSeq) {
    }

    public record RoleState(String roleId, String name, String key, String createdAt, long seq) {
    }

    public record BindingState(String host, String sessionId, String roleId, String boundAt, long seq) {
    }

    public record GlobalState(String body, long seq, String recordHash, String issuedAt, String credentialId) {
    }

    /** The first key; {@code fingerprint} is the short one the page shows ({@link AdminWebAuthn#displayFingerprint}). */
    public record Bootstrap(String credentialId, String fingerprint, String at) {
    }

    /** The model a signed JEV setting names; also the shape of {@code model} in the change that signs it. */
    public record JevModel(@NotNull @jakarta.validation.constraints.Pattern(regexp = AdminChange.PROVIDER_ID) String providerId,
            @NotNull @jakarta.validation.constraints.Pattern(regexp = AdminChange.MODEL_ID) String modelId) {
    }

    public record JevState(boolean enabled, Double limitUsd, Map<String, Boolean> presets, JevModel model, JevModel fallbackModel, boolean transmissionAllowed, long seq, String issuedAt) {
    }

    public record JevKeyState(String name, String fingerprint, long seq, String issuedAt) {
    }

    public record EditExempt(List<String> paths, long seq, String issuedAt) {
    }

    /** The server's JVM preset the user signed; it takes effect when the server is next started. */
    public record JvmPresetState(JvmPreset preset, long seq, String issuedAt) {
    }

    /** The convergence limits the user signed; a root opened after it takes them, a root already open keeps its own. */
    public record ConvergenceLimitsState(ConvergenceLimits limits, long seq, String issuedAt) {
    }

    public record ResourceLimitsState(ResourceLimits limits, long seq, String issuedAt) {
    }

    public record AttachmentStorageState(AttachmentStorageSettings settings, long seq, String issuedAt) {
    }

    /** The link of the business database the user signed (no password: the record names the file that holds it). */
    public record DatabaseLinkState(AdminChange.DatabaseLink link, long seq, String issuedAt) {
    }

    public record Head(String recordHash, long seq) {
    }

    /** {@code change} is the change of the failed record, null when its intent could not be read (or when the failure is a log that went back). */
    public record Failure(String target, long seq, String code, AdminChange change) {
    }

    /**
     * What a passkey signs, in the exact canonical text of its JSON (every key written, a null one as {@code null}). The constraints are what an intent
     * of this log must be: version, domain and relying party are fixed, and the change is one of the {@link AdminChange} variants.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Intent(@Min(INTENT_VERSION) @Max(INTENT_VERSION) int v, @NotNull @jakarta.validation.constraints.Pattern(regexp = DOMAIN_REGEX) String domain,
            @NotNull @jakarta.validation.constraints.Pattern(regexp = AdminWebAuthn.RP_ID) String rpId, @NotNull String target, String prevTargetHash,
            @NotNull @Valid AdminChange change, @NotNull @OffsetDateTimeText String issuedAt) {
    }

    /** What the log folds to. Mutable while it is folded, read-only afterwards. */
    public static final class State {
        public final Map<String, KeyState> keys = new LinkedHashMap<>();
        public final Set<String> revoked = new HashSet<>();
        /** The label each key was registered with, those revoked since included. */
        public final Map<String, String> keyLabels = new LinkedHashMap<>();
        public Bootstrap bootstrap;
        public GlobalState global;
        public final List<GlobalState> globalHistory = new ArrayList<>();
        public final Map<String, RoleState> roles = new LinkedHashMap<>();
        public final Map<String, BindingState> bindings = new LinkedHashMap<>();
        public JevState jev;
        public final Map<String, JevKeyState> jevKeys = new LinkedHashMap<>();
        public EditExempt editExempt;
        public JvmPresetState jvmPreset;
        public ConvergenceLimitsState convergenceLimits;
        public ResourceLimitsState resourceLimits;
        public AttachmentStorageState attachmentStorage;
        public final Set<String> attachmentDirectories = new java.util.LinkedHashSet<>();
        public DatabaseLinkState databaseLink;
        public final Map<String, Head> heads = new LinkedHashMap<>();
        public final Map<String, String> keyLastUsed = new LinkedHashMap<>();
        public final List<Failure> failures = new ArrayList<>();
        public long lastSeq;
    }

    private static String encode(String part) {
        return URLEncoder.encode(part, StandardCharsets.UTF_8).replace("+", "%20").replace("*", "%2A").replace("%21", "!").replace("%27", "'").replace("%28", "(")
                .replace("%29", ")").replace("%7E", "~");
    }

    public static String bindingTarget(String host, String sessionId) {
        return BINDING_PREFIX + encode(host) + ":" + encode(sessionId);
    }

    public static String bindingKey(String host, String sessionId) {
        return host + '\u0000' + sessionId;
    }

    /** Whether the text has a control character (the category Cc: the C0 and C1 ranges and DEL). */
    static boolean hasControl(String text) {
        return text.chars().anyMatch(Character::isISOControl);
    }

    /** NFC, trimmed, one line of at most 100 characters without control characters; null when it cannot be a role name. */
    public static String normalizeRoleName(String value) {
        if (value == null) {
            return null;
        }
        String name = Normalizer.normalize(value, Normalizer.Form.NFC).strip();
        return !name.isEmpty() && name.codePointCount(0, name.length()) <= ROLE_NAME_MAX_LENGTH && !hasControl(name) ? name : null;
    }

    public static String roleKey(String name) {
        return Normalizer.normalize(name, Normalizer.Form.NFC).strip().toLowerCase(Locale.ROOT);
    }

    /**
     * Whether the text is an absolute path: it parses as a path and {@link Path#isAbsolute()} says so (JDK 25: "Tells whether or not this path is absolute. An
     * absolute path is complete in that it doesn't need to be combined with other path information in order to locate a file."), so {@code C:foo}, which
     * Windows resolves against the drive's current directory, is not. The Git Bash spellings of a path on Windows ({@code /d/ws}, {@code /tmp}) count too: the
     * edit gate compares them as text. A text that is no path at all (Windows refuses {@code *}, {@code ?}, {@code <}, {@code >}, {@code |} and {@code "}) is not absolute.
     *
     * @param shell how the shell the host runs its commands in spells the paths that are no path of this file system
     */
    static boolean absolute(String path, ShellPaths shell) {
        Path parsed;
        try {
            parsed = Path.of(path);
        } catch (InvalidPathException invalid) {
            LOG.debug("The text is not a path of this file system", invalid);
            return false;
        }
        if (parsed.isAbsolute()) {
            return true;
        }
        // Not absolute for this file system, but a path of the Git Bash installation: its spelling of a drive path ({@code /d/ws}) or a path of its own root ({@code /tmp}).
        String drive = shell.windowsSpelling(path);
        if (!drive.equals(path)) {
            return pathOrNull(drive) instanceof Path spelled && spelled.isAbsolute();
        }
        return shell.rootRelative(path);
    }

    private static Path pathOrNull(String text) {
        try {
            return Path.of(text);
        } catch (InvalidPathException invalid) {
            LOG.debug("The text is not a path of this file system", invalid);
            return null;
        }
    }

    /** The characters {@link java.nio.file.FileSystem#getPathMatcher} reads as glob syntax: an entry with one of them is a pattern, not a path. */
    private static final String GLOB_CHARACTERS = "*?[{";

    /** Whether the exempt-list entry is a glob pattern: it is kept as the pattern text and matched with {@code getPathMatcher("glob:" + pattern)}, never turned into a {@link Path}. */
    public static boolean isGlob(String entry) {
        for (int index = 0; index < GLOB_CHARACTERS.length(); index++) {
            if (entry.indexOf(GLOB_CHARACTERS.charAt(index)) >= 0) {
                return true;
            }
        }
        return false;
    }

    /** Whether the file system takes the text as a glob: {@code getPathMatcher} refuses a malformed one with {@code PatternSyntaxException}, the only check the JDK offers. */
    static boolean validGlob(String pattern) {
        try {
            FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            return true;
        } catch (PatternSyntaxException invalid) {
            LOG.debug("The text is not a glob pattern", invalid);
            return false;
        }
    }

    /** An exempt-list entry is a well-formed glob pattern or an absolute path. */
    static boolean exemptEntry(String entry, ShellPaths shell) {
        return isGlob(entry) ? validGlob(entry) : absolute(entry, shell);
    }

    /** The chain a change is appended to. */
    public static String changeTarget(AdminChange change) {
        return switch (change) {
            case AdminChange.KeyAdd _, AdminChange.KeyRevoke _ -> TARGET_KEYS;
            case AdminChange.GlobalSet _ -> TARGET_GLOBAL;
            case AdminChange.RoleAdd _, AdminChange.RoleRename _, AdminChange.RoleDelete _ -> TARGET_ROLES;
            case AdminChange.RoleBodySet body -> ROLE_BODY_PREFIX + body.roleId();
            case AdminChange.BindingSet binding -> bindingTarget(binding.host(), binding.sessionId());
            case AdminChange.Ack ack -> ack.target();
            case AdminChange.JevSet _ -> TARGET_JEV;
            case AdminChange.JevKeySet _, AdminChange.JevKeyDelete _ -> TARGET_JEV_KEYS;
            case AdminChange.EditExemptSet _ -> TARGET_EDIT_EXEMPT;
            case AdminChange.JvmPresetSet _ -> TARGET_JVM_PRESET;
            case AdminChange.ConvergenceLimitsSet _ -> TARGET_CONVERGENCE_LIMITS;
            case AdminChange.ResourceLimitsSet _ -> TARGET_RESOURCE_LIMITS;
            case AdminChange.AttachmentStorageSet _ -> TARGET_ATTACHMENT_STORAGE;
            case AdminChange.DatabaseLink _ -> TARGET_DATABASE;
            case AdminChange.RootApprove approve -> ROOT_APPROVAL_PREFIX + approve.approvalId();
            case AdminChange.RootRelease release -> ROOT_RELEASE_PREFIX + release.rootId();
        };
    }

    public static boolean isTarget(String target) {
        if (FIXED_TARGETS.contains(target) || ROLE_BODY_TARGET.matcher(target).matches() || ROOT_APPROVAL_TARGET.matcher(target).matches()
                || ROOT_RELEASE_TARGET.matcher(target).matches()) {
            return true;
        }
        Matcher parts = BINDING_TARGET.matcher(target);
        if (!parts.matches() || target.length() > 1000) {
            return false;
        }
        try {
            return bindingTarget(URLDecoder.decode(parts.group(1), StandardCharsets.UTF_8), URLDecoder.decode(parts.group(2), StandardCharsets.UTF_8)).equals(target);
        } catch (IllegalArgumentException malformed) {
            LOG.debug("The binding target is not percent-encoded text", malformed);
            return false;
        }
    }

    /**
     * Folds a log without a Spring context of the server (the admin reset command runs as a plain program): it builds its own validator and closes the
     * validator's factory. The caller hands in the JSON mapper of the context it already holds (Boot's), so this method makes none, and the passkey registry whose keys the
     * signatures are checked with. Everything inside the server folds with the {@link AdminLogVerifier} bean.
     */
    public static State fold(List<LogRecord> records, JsonMapper mapper, PasskeyRegistry passkeys) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            return new AdminLogVerifier(mapper, factory.getValidator(), new AdminWebAuthn(mapper), passkeys).fold(records);
        }
    }

    /**
     * The log takes no new signature, and no first key, while a record of it fails its integrity: a record altered after it was signed, or a key the record names that the passkey
     * registry does not hold or holds as another key (the settings database was lost or changed). Only the admin reset starts it again. Without this a log whose first key record
     * fails would fold to "no key", which is the state in which the first key registers itself, and whoever changed the registry or the log would register their own. For the first key
     * any failed record stops it (a log that has failed records and no key is not a new log); for a change signed by a listed key only an integrity failure does, because another failed
     * record (a stale chain head: two signatures in one instant) is one the user signs again.
     */
    public static void requireIntact(State state) {
        boolean broken = state.keys.isEmpty() ? !state.failures.isEmpty() : state.failures.stream().anyMatch(failure -> InputCode.ADMIN_RECORD_ALTERED.name().equals(failure.code()));
        if (broken) {
            throw new InvalidInputException(InputCode.ADMIN_RECORD_ALTERED, Map.of(), Map.of("reason", "LOG_INTEGRITY_FAILED"));
        }
    }

    /**
     * The personal edition has one admin passkey: the person's own, on the person's own computer. A registration while the log lists a key is refused, whoever signs it. This is
     * a rule of writing, not of {@link #checkChange}: that one also reads the records a log already holds, and an installation from before the rule may hold more than one key.
     * A lost passkey is registered again by the admin reset, which empties the log's keys first.
     */
    public static void requireSinglePasskey(State state, AdminChange change) {
        if (change instanceof AdminChange.KeyAdd && !state.keys.isEmpty()) {
            throw changeInvalid(Map.of("reason", "SINGLE_PASSKEY"));
        }
    }

    /** Checks one change against the current state without applying it; {@code signer} is the credential that signs it. */
    public static void checkChange(State state, AdminChange change, String signer) {
        if (state.keys.isEmpty()) {
            // Trust on first use: only a key registering itself starts the log.
            if (!(change instanceof AdminChange.KeyAdd first) || !first.credentialId().equals(signer)) {
                throw new GateRefusedException(GateCode.ADMIN_KEY_NOT_REGISTERED);
            }
        } else if (!state.keys.containsKey(signer)) {
            throw keyRefused(state, signer);
        }
        switch (change) {
            case AdminChange.KeyAdd add -> {
                // The key itself is checked where it arrives (the commit) and where it is read (the registry); the record names only its algorithm.
                AdminWebAuthn.requireAlgorithm(add.alg());
                if (state.keys.containsKey(add.credentialId()) || state.revoked.contains(add.credentialId())) {
                    throw changeInvalid(Map.of("reason", "KEY_ALREADY_KNOWN"));
                }
            }
            case AdminChange.KeyRevoke revoke -> {
                if (!state.keys.containsKey(revoke.credentialId())) {
                    throw keyRefused(state, revoke.credentialId());
                }
                if (state.keys.size() == 1) {
                    throw new ConflictException(ConflictCode.ADMIN_LAST_KEY_REVOKE_REFUSED);
                }
            }
            case AdminChange.RoleAdd add -> checkRoleName(state, add.roleId(), add.name(), true);
            case AdminChange.RoleRename rename -> checkRoleName(state, rename.roleId(), rename.name(), false);
            case AdminChange.RoleDelete delete -> checkRoleKnown(state, delete.roleId());
            case AdminChange.RoleBodySet body -> checkRoleKnown(state, body.roleId());
            case AdminChange.BindingSet binding -> {
                if (binding.roleId() != null) {
                    checkRoleKnown(state, binding.roleId());
                }
            }
            case AdminChange.JevKeyDelete delete -> {
                if (!state.jevKeys.containsKey(delete.name())) {
                    throw changeInvalid(Map.of("reason", "JEV_KEY_NOT_FOUND"));
                }
            }
            case AdminChange.JevSet set -> {
                for (String id : new TreeSet<>(set.presets().keySet())) {
                    if (!JEV_PRESET_IDS.contains(id)) {
                        throw changeInvalid(Map.of("reason", "JEV_PRESET_UNKNOWN", "presetId", id));
                    }
                }
                if (set.fallbackModel() != null && (set.model() == null || set.fallbackModel().equals(set.model()))) {
                    throw changeInvalid(Map.of("reason", "JEV_FALLBACK_INVALID"));
                }
            }
            case AdminChange.GlobalSet _, AdminChange.Ack _, AdminChange.JevKeySet _, AdminChange.EditExemptSet _, AdminChange.JvmPresetSet _, AdminChange.ConvergenceLimitsSet _,
                    AdminChange.ResourceLimitsSet _, AdminChange.AttachmentStorageSet _, AdminChange.RootApprove _, AdminChange.RootRelease _, AdminChange.DatabaseLink _ -> {
                // These have no state-dependent rule.
            }
        }
    }

    /** A credential that is not a signer of the log: revoked once, or never known. */
    private static SennardException keyRefused(State state, String credentialId) {
        return new GateRefusedException(state.revoked.contains(credentialId) ? GateCode.ADMIN_KEY_REVOKED : GateCode.ADMIN_KEY_UNKNOWN);
    }

    private static InvalidInputException changeInvalid(Map<String, Object> details) {
        return new InvalidInputException(InputCode.ADMIN_CHANGE_INVALID, Map.of(), details);
    }

    private static void checkRoleKnown(State state, String roleId) {
        if (!state.roles.containsKey(roleId)) {
            throw changeInvalid(Map.of("reason", "ROLE_NOT_FOUND"));
        }
    }

    /** A role name is one role's (compared by its key), and a role is added once and renamed only when it exists. */
    private static void checkRoleName(State state, String roleId, String name, boolean add) {
        String key = roleKey(name);
        for (RoleState role : state.roles.values()) {
            if (role.key().equals(key) && !role.roleId().equals(roleId)) {
                throw changeInvalid(Map.of("reason", "ROLE_NAME_TAKEN", "roleId", role.roleId()));
            }
        }
        if (add == state.roles.containsKey(roleId)) {
            throw changeInvalid(Map.of("reason", add ? "ROLE_ID_TAKEN" : "ROLE_NOT_FOUND"));
        }
    }

    /** Applies a verified record to the state. */
    static void apply(State state, LogRecord record, Intent intent) {
        switch (intent.change()) {
            case AdminChange.KeyAdd add -> {
                KeyState key = new KeyState(add.credentialId(), add.alg(), add.label(), add.fingerprint(), intent.issuedAt(), record.seq());
                if (state.keys.isEmpty() && state.bootstrap == null) {
                    state.bootstrap = new Bootstrap(key.credentialId(), AdminWebAuthn.displayFingerprint(key.fingerprint()), intent.issuedAt());
                }
                state.keys.put(key.credentialId(), key);
                state.keyLabels.put(key.credentialId(), add.label());
            }
            case AdminChange.KeyRevoke revoke -> {
                state.keys.remove(revoke.credentialId());
                state.revoked.add(revoke.credentialId());
            }
            case AdminChange.GlobalSet set -> {
                state.global = new GlobalState(set.body(), record.seq(), record.recordHash(), intent.issuedAt(), record.credentialId());
                state.globalHistory.add(0, state.global);
            }
            case AdminChange.RoleAdd add -> state.roles.put(add.roleId(), new RoleState(add.roleId(), add.name(), roleKey(add.name()), intent.issuedAt(), record.seq()));
            case AdminChange.RoleRename rename -> {
                RoleState prior = state.roles.get(rename.roleId());
                state.roles.put(prior.roleId(), new RoleState(prior.roleId(), rename.name(), roleKey(rename.name()), prior.createdAt(), record.seq()));
            }
            case AdminChange.RoleDelete delete -> {
                // A deleted role's bindings end with it; its ID is random and never comes back.
                state.roles.remove(delete.roleId());
                state.bindings.values().removeIf(binding -> binding.roleId().equals(delete.roleId()));
            }
            case AdminChange.BindingSet set -> {
                String key = bindingKey(set.host(), set.sessionId());
                if (set.roleId() == null) {
                    state.bindings.remove(key);
                } else {
                    state.bindings.put(key, new BindingState(set.host(), set.sessionId(), set.roleId(), intent.issuedAt(), record.seq()));
                }
            }
            case AdminChange.JevSet set -> state.jev = new JevState(set.enabled(), set.limitUsd(), Collections.unmodifiableMap(set.presets()), set.model(), set.fallbackModel(), set.transmissionAllowed(),
                    record.seq(), intent.issuedAt());
            case AdminChange.JevKeySet set -> state.jevKeys.put(set.name(), new JevKeyState(set.name(), set.fingerprint(), record.seq(), intent.issuedAt()));
            case AdminChange.JevKeyDelete delete -> state.jevKeys.remove(delete.name());
            case AdminChange.EditExemptSet set -> state.editExempt = new EditExempt(List.copyOf(set.paths()), record.seq(), intent.issuedAt());
            case AdminChange.JvmPresetSet set -> state.jvmPreset = new JvmPresetState(set.preset(), record.seq(), intent.issuedAt());
            case AdminChange.ConvergenceLimitsSet set -> state.convergenceLimits = new ConvergenceLimitsState(set.limits(), record.seq(), intent.issuedAt());
            case AdminChange.ResourceLimitsSet set -> state.resourceLimits = new ResourceLimitsState(set.limits(), record.seq(), intent.issuedAt());
            case AdminChange.AttachmentStorageSet set -> {
                state.attachmentStorage = new AttachmentStorageState(set.settings(), record.seq(), intent.issuedAt());
                state.attachmentDirectories.add(set.settings().directory());
            }
            case AdminChange.DatabaseLink link -> state.databaseLink = new DatabaseLinkState(link, record.seq(), intent.issuedAt());
            case AdminChange.Ack _, AdminChange.RoleBodySet _, AdminChange.RootApprove _, AdminChange.RootRelease _ -> {
                // These stay in the verified chain and change nothing here.
            }
        }
    }
}
