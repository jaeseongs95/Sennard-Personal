// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.context;

/**
 * The kind of change a signed admin record carries. The constant's name is what the record's {@code change.op} holds (Jackson's own form of an enum),
 * and the same name is the type id of the matching {@link AdminChange} variant: the shape of each kind (its keys, no more and no fewer, and what
 * each value may be) is that record's components and constraints, not a list kept here.
 */
public enum AdminOp {
    KEY_ADD,
    KEY_REVOKE,
    GLOBAL_SET,
    ROLE_ADD,
    ROLE_RENAME,
    ROLE_DELETE,
    ROLE_BODY_SET,
    BINDING_SET,
    ACK,
    JEV_SET,
    JEV_KEY_SET,
    JEV_KEY_DELETE,
    EDIT_EXEMPT_SET,
    JVM_PRESET_SET,
    CONVERGENCE_LIMITS_SET,
    RESOURCE_LIMITS_SET,
    ATTACHMENT_STORAGE_SET,
    ROOT_APPROVE,
    ROOT_RELEASE,
    WORKFLOW_PLAN_UNBLOCK,
    DATABASE_LINK;

    /** The kind of a change; the switch is exhaustive, so a variant added to {@link AdminChange} is a compile error here until it has its kind. */
    public static AdminOp of(AdminChange change) {
        return switch (change) {
            case AdminChange.KeyAdd _ -> KEY_ADD;
            case AdminChange.KeyRevoke _ -> KEY_REVOKE;
            case AdminChange.GlobalSet _ -> GLOBAL_SET;
            case AdminChange.RoleAdd _ -> ROLE_ADD;
            case AdminChange.RoleRename _ -> ROLE_RENAME;
            case AdminChange.RoleDelete _ -> ROLE_DELETE;
            case AdminChange.RoleBodySet _ -> ROLE_BODY_SET;
            case AdminChange.BindingSet _ -> BINDING_SET;
            case AdminChange.Ack _ -> ACK;
            case AdminChange.JevSet _ -> JEV_SET;
            case AdminChange.JevKeySet _ -> JEV_KEY_SET;
            case AdminChange.JevKeyDelete _ -> JEV_KEY_DELETE;
            case AdminChange.EditExemptSet _ -> EDIT_EXEMPT_SET;
            case AdminChange.JvmPresetSet _ -> JVM_PRESET_SET;
            case AdminChange.ConvergenceLimitsSet _ -> CONVERGENCE_LIMITS_SET;
            case AdminChange.ResourceLimitsSet _ -> RESOURCE_LIMITS_SET;
            case AdminChange.AttachmentStorageSet _ -> ATTACHMENT_STORAGE_SET;
            case AdminChange.RootApprove _ -> ROOT_APPROVE;
            case AdminChange.RootRelease _ -> ROOT_RELEASE;
            case AdminChange.WorkflowPlanUnblock _ -> WORKFLOW_PLAN_UNBLOCK;
            case AdminChange.DatabaseLink _ -> DATABASE_LINK;
        };
    }
}
