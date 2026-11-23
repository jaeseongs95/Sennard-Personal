// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.context;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import io.github.jaeseongs95.sennard.domain.jvm.JvmPreset;
import io.github.jaeseongs95.sennard.server.context.AdminConstraints.Chain;
import io.github.jaeseongs95.sennard.server.context.AdminConstraints.ExemptPath;
import io.github.jaeseongs95.sennard.server.context.AdminConstraints.NoDuplicates;
import io.github.jaeseongs95.sennard.server.context.AdminConstraints.RoleName;
import io.github.jaeseongs95.sennard.server.limits.ResourceLimits;
import io.github.jaeseongs95.sennard.server.workflow.ConvergenceLimits;
import io.github.jaeseongs95.sennard.service.settings.DatabaseTls;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * The change a signed admin record carries: one variant per {@link AdminOp}, the type id being the constant's name in {@code op}. A variant is the
 * whole shape of its kind: its components are the keys the change has (the signature covers the canonical JSON of the record, which writes them all,
 * a null one as {@code null}), and the constraints on them are what each value may be. Binding is strict (see {@link AdminLogVerifier}): a key the
 * variant does not have, a missing key, or a value of another JSON type is refused, so the canonical text that was signed and the record read from it
 * say the same.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "op")
@JsonSubTypes({
    @JsonSubTypes.Type(value = AdminChange.KeyAdd.class, name = "KEY_ADD"),
    @JsonSubTypes.Type(value = AdminChange.KeyRevoke.class, name = "KEY_REVOKE"),
    @JsonSubTypes.Type(value = AdminChange.GlobalSet.class, name = "GLOBAL_SET"),
    @JsonSubTypes.Type(value = AdminChange.RoleAdd.class, name = "ROLE_ADD"),
    @JsonSubTypes.Type(value = AdminChange.RoleRename.class, name = "ROLE_RENAME"),
    @JsonSubTypes.Type(value = AdminChange.RoleDelete.class, name = "ROLE_DELETE"),
    @JsonSubTypes.Type(value = AdminChange.RoleBodySet.class, name = "ROLE_BODY_SET"),
    @JsonSubTypes.Type(value = AdminChange.BindingSet.class, name = "BINDING_SET"),
    @JsonSubTypes.Type(value = AdminChange.Ack.class, name = "ACK"),
    @JsonSubTypes.Type(value = AdminChange.JevSet.class, name = "JEV_SET"),
    @JsonSubTypes.Type(value = AdminChange.JevKeySet.class, name = "JEV_KEY_SET"),
    @JsonSubTypes.Type(value = AdminChange.JevKeyDelete.class, name = "JEV_KEY_DELETE"),
    @JsonSubTypes.Type(value = AdminChange.EditExemptSet.class, name = "EDIT_EXEMPT_SET"),
    @JsonSubTypes.Type(value = AdminChange.JvmPresetSet.class, name = "JVM_PRESET_SET"),
    @JsonSubTypes.Type(value = AdminChange.ConvergenceLimitsSet.class, name = "CONVERGENCE_LIMITS_SET"),
    @JsonSubTypes.Type(value = AdminChange.ResourceLimitsSet.class, name = "RESOURCE_LIMITS_SET"),
    @JsonSubTypes.Type(value = AdminChange.AttachmentStorageSet.class, name = "ATTACHMENT_STORAGE_SET"),
    @JsonSubTypes.Type(value = AdminChange.RootApprove.class, name = "ROOT_APPROVE"),
    @JsonSubTypes.Type(value = AdminChange.RootRelease.class, name = "ROOT_RELEASE"),
    @JsonSubTypes.Type(value = AdminChange.WorkflowPlanUnblock.class, name = "WORKFLOW_PLAN_UNBLOCK"),
    @JsonSubTypes.Type(value = AdminChange.DatabaseLink.class, name = "DATABASE_LINK")
})
@JsonInclude(JsonInclude.Include.ALWAYS)
public sealed interface AdminChange {
    /** The regular expressions of the identifiers a change names (a constraint takes a constant, and the whole value must match). */
    String ID = "[A-Za-z0-9_-]{1,200}";
    String JEV_KEY_NAME = "[A-Za-z_][A-Za-z0-9_]{0,127}";
    String FINGERPRINT = "sha256:[a-f0-9]{64}";
    String PRESET_ID = "[a-z0-9-]{1,60}";
    String PROVIDER_ID = "[a-z0-9-]{1,60}";
    String MODEL_ID = "[A-Za-z0-9._:-]{1,100}";

    String JDBC_URL = "jdbc:.*";
    int DB_PATH_MAX_LENGTH = 1024;
    int DB_URL_MAX_LENGTH = 2048;
    int DB_ACCOUNT_MAX_LENGTH = 255;
    int PUBLIC_KEY_MAX_LENGTH = 4000;
    int HOST_MAX_LENGTH = 200;
    int SESSION_ID_MAX_LENGTH = 500;
    int STATE_TOKEN_MAX_LENGTH = 200;
    int MAX_PRESET_SWITCHES = 20;
    /** The reason of a root release is kept in the root, whose contract allows 2000 characters. */
    int ROOT_RELEASE_REASON_MAX_LENGTH = 2000;

    /**
     * Registers a passkey: the credential id, the fingerprint of the SPKI key the browser exposes ({@link AdminWebAuthn#fingerprint}), its COSE algorithm and its label. The key
     * itself is not part of the record: it travels with the signed commit and is kept in the passkey registry of the settings database, never in the business database.
     */
    record KeyAdd(@NotNull @Pattern(regexp = ID) String credentialId, @NotNull @Pattern(regexp = FINGERPRINT) String fingerprint, int alg,
            @NotNull @Size(max = AdminLog.KEY_LABEL_MAX_LENGTH) String label) implements AdminChange {
    }

    record KeyRevoke(@NotNull @Pattern(regexp = ID) String credentialId) implements AdminChange {
    }

    record GlobalSet(@NotNull String body) implements AdminChange {
    }

    record RoleAdd(@NotNull @Pattern(regexp = ID) String roleId, @NotNull @RoleName String name) implements AdminChange {
    }

    record RoleRename(@NotNull @Pattern(regexp = ID) String roleId, @NotNull @RoleName String name) implements AdminChange {
    }

    record RoleDelete(@NotNull @Pattern(regexp = ID) String roleId) implements AdminChange {
    }

    /** {@code expectedStateToken} is null for the first write of a role's body. */
    record RoleBodySet(@NotNull @Pattern(regexp = ID) String roleId, @NotNull String body, @Size(min = 1, max = STATE_TOKEN_MAX_LENGTH) String expectedStateToken) implements AdminChange {
    }

    /** {@code roleId} is null to unbind the session. */
    record BindingSet(@NotEmpty @Size(max = HOST_MAX_LENGTH) String host, @NotEmpty @Size(max = SESSION_ID_MAX_LENGTH) String sessionId, @Pattern(regexp = ID) String roleId)
            implements AdminChange {
    }

    /** Sets a failed change of one chain aside. */
    record Ack(@NotNull @Chain String target) implements AdminChange {
    }

    /**
     * {@code limitUsd}, {@code model} and {@code fallbackModel} are null when none is set. The fallback model is the evaluator asked when {@code model}'s provider cannot advise
     * for a reason that trying the same target again would not mend (no key, a refused key, an answer in the wrong form); it is a different model and needs {@code model}.
     */
    record JevSet(boolean enabled, @PositiveOrZero Double limitUsd, @Valid AdminLog.JevModel model, @Valid AdminLog.JevModel fallbackModel,
            @NotNull @Size(max = MAX_PRESET_SWITCHES) Map<@NotNull @Pattern(regexp = PRESET_ID) String, @NotNull Boolean> presets, boolean transmissionAllowed) implements AdminChange {
    }

    record JevKeySet(@NotNull @Pattern(regexp = JEV_KEY_NAME) String name, @NotNull @Pattern(regexp = FINGERPRINT) String fingerprint) implements AdminChange {
    }

    record JevKeyDelete(@NotNull @Pattern(regexp = JEV_KEY_NAME) String name) implements AdminChange {
    }

    record EditExemptSet(@NotNull @Size(max = AdminLog.EDIT_EXEMPT_MAX_PATHS) @NoDuplicates List<@NotNull @Size(min = 1, max = AdminLog.EDIT_EXEMPT_PATH_MAX_LENGTH) @ExemptPath String> paths)
            implements AdminChange {
    }

    /** One of the closed set of JVM presets, by its constant name. */
    record JvmPresetSet(@NotNull JvmPreset preset) implements AdminChange {
    }

    /**
     * The attempts one epoch of a convergence root allows and the epochs a root may open: each an integer of at least 1, with no upper bound. Only a root opened after the
     * signature takes them; a root already open keeps the limits of its frame.
     */
    record ConvergenceLimitsSet(@NotNull @Valid ConvergenceLimits limits) implements AdminChange {
    }

    record ResourceLimitsSet(@NotNull @Valid ResourceLimits limits) implements AdminChange {
    }

    record AttachmentStorageSet(@NotNull @Valid AttachmentStorageSettings settings) implements AdminChange {
    }

    record RootApprove(@NotNull @Pattern(regexp = ID) String approvalId, @NotNull @Pattern(regexp = FINGERPRINT) String scopeDigest) implements AdminChange {
    }

    /**
     * The user releases an open convergence root whose session cannot. The signature binds the root, the revision the page showed and the reason, so a root that
     * moved since is refused, not released on a signature for another state.
     */
    record RootRelease(@NotNull @Pattern(regexp = ID) String rootId, @PositiveOrZero long expectedRevision, @NotBlank @Size(max = ROOT_RELEASE_REASON_MAX_LENGTH) String reason) implements AdminChange {
    }

    /** The exact denied plan and the instance of the denial shown to the user. */
    record WorkflowPlanUnblock(@NotNull @Pattern(regexp = "[a-f0-9]{64}") String workspaceId, @NotNull @Pattern(regexp = "[A-Z_]{1,40}") String kind,
            @NotNull @Pattern(regexp = FINGERPRINT) String planDigest, @NotNull @Pattern(regexp = "[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}") String recordId) implements AdminChange {
    }

    /**
     * Links the business database: the user's JDBC driver (a jar or a folder of jars), the URL, the account, the file that holds the account's password and how the link is
     * protected. The password is never part of the record, and a URL that carries one is refused ({@code DatabaseLinkRequest}). The link is the one source of the business database
     * (the settings database keeps it). Before the database is ready for the first time the record goes to the section of the log before the link ({@code PreLinkAdminRecords}) and
     * the store prepares with the link; while it is ready the record goes to the business log, the database it names is tried first, and the store uses the link from the next start.
     */
    record DatabaseLink(@NotBlank @Size(max = DB_PATH_MAX_LENGTH) String driverPath, @NotBlank @Size(max = DB_URL_MAX_LENGTH) @Pattern(regexp = JDBC_URL) String jdbcUrl,
            @NotBlank @Size(max = DB_ACCOUNT_MAX_LENGTH) String accountName, @NotBlank @Size(max = DB_PATH_MAX_LENGTH) String passwordRef, @NotNull DatabaseTls tls) implements AdminChange {
    }
}
